import Foundation
import CipherHapticCore

/// 一次播放的句柄 —— 对应「句柄状态机」§一。镜像 android/library/engine/PlaybackHandle.kt。
///
/// iOS 这边是**真句柄**（P-11）：持有 `CHHapticPatternPlayer`。持有关系：
/// `runtime.handles` 是唯一强引用来源 → handle 强引用 player → token 弱引用 handle。
///
/// | 资源 | 释放点 |
/// |---|---|
/// | player | `release`（另：`stop` / `suspend` / `report` / 进入 Completed 时先停马达） |
/// | end / idle / keepAlive / grace / loopDeadline / EXPIRE / 续排 定时器 | `release` |
/// | coalescer 的补发块 | `stop` 与 `release`（Android 在 stop 里漏了这步，审查 A4） |
///
/// **全部且仅在进入 `Reclaimed` 时释放。** 任何抵达不了 `Reclaimed` 的路径都是泄漏。
final class PlaybackHandle: PlaybackActions {
    /// ⚠️ **待实测**（性能 §5.4）
    static let graceMs = 50
    /// critical 后台保活窗口。⚠️ 待实测，且 iOS 可行性本身未定（P-06 / V1）
    static let keepAliveMs = 30_000
    /// 连续通道续排的提前量：在单次排程上限（30s）到期前换上新 player，对 FSM 不可见
    static let renewLeadMs = 100

    let id: Int
    let resolved: ResolvedWaveform
    private let scheduler: HapticScheduler
    private let gateway: HapticGateway
    private let engine: EngineController
    /// 仅 looping：应用告知的循环时长（已按库上限截断），到期发 CANCEL
    private let loopDeadlineMs: Int?
    private let probe: LatencyProbe?
    private let metrics: MetricsCollector?
    private let onLog: (String) -> Void

    private(set) var fsm: PlaybackFsm!
    private(set) lazy var coalescer = ContinuousCoalescer(scheduler: scheduler) { [weak self] i, s in
        self?.sendContinuous(i, s)
    }

    private var player: HapticPlayer?
    private var endTimer: HapticCancellable?
    private var idleTimer: HapticCancellable?
    private var keepAliveTimer: HapticCancellable?
    private var graceTimer: HapticCancellable?
    private var loopDeadline: HapticCancellable?
    private var expireTimer: HapticCancellable?
    private var renewTimer: HapticCancellable?

    /// 宿主（runtime）在此接收"已回收"通知 —— 与本类自己的状态副作用分开，不抢 onStateEntered 的槽
    var onReclaimed: (() -> Void)?
    /// 进入任一终态（token 的 isFinished 靠它）
    var onFinished: (() -> Void)?

    var state: String { fsm.state }

    init(id: Int, resolved: ResolvedWaveform, scheduler: HapticScheduler, gateway: HapticGateway,
         engine: EngineController, loopDeadlineMs: Int?, probe: LatencyProbe?, metrics: MetricsCollector?,
         onLog: @escaping (String) -> Void) {
        self.id = id
        self.resolved = resolved
        self.scheduler = scheduler
        self.gateway = gateway
        self.engine = engine
        self.loopDeadlineMs = loopDeadlineMs
        self.probe = probe
        self.metrics = metrics
        self.onLog = onLog
    }

    func attach(_ table: TransitionTable) {
        let f = PlaybackFsm(table: table, kind: resolved.kind, category: resolved.category, actions: self)
        f.onStateEntered = { [weak self] st in self?.entered(st) }
        fsm = f
    }

    private func entered(_ st: String) {
        onLog("\(resolved.semanticId)#\(id) → \(st)")
        switch st {
        case "Completed":
            // continuous 由 idle 超时进 Completed 时，马达还按最后强度在震 —— 必须停
            stopPlayer()
            startGraceTimer()
            onFinished?()
        case "Cancelled":
            startGraceTimer()
            onFinished?()
        case "Failed":
            // 审查 B2：Failed 只有 EXPIRE 一个出口，而 Android 没有任何发送方 → 永不回收
            stopPlayer()
            expireTimer?.cancel()
            expireTimer = scheduler.schedule(afterMs: Self.graceMs) { [weak self] in self?.fsm.send("EXPIRE") }
            onFinished?()
        case "Reclaimed":
            onFinished?()
            onReclaimed?()
        default:
            break
        }
    }

    // MARK: - PlaybackActions（各端唯一不共用的部分）

    func invoke(_ action: String) {
        switch action {
        case "submit", "resubmit": doSubmit()
        case "startEndTimer": startEndTimer()
        case "startIdleTimer", "applyParams": startIdleTimer()
        case "bufferParams": break                 // coalescer 已在 runtime 侧记录
        case "startKeepAlive": startKeepAlive()
        case "clearKeepAlive":
            keepAliveTimer?.cancel()
            keepAliveTimer = nil
        case "suspend":
            stopPlayer()
            cancel(&endTimer)
            cancel(&renewTimer)
        case "stop":
            // 只停本 player（P-03）。Android 这里调的是全局 cancelAll（审查 B5）
            stopPlayer()
            cancelAllTimers()
            coalescer.reset()
            startGraceTimer()
        case "report":
            // 失败必须停马达：否则已提交的一轮 / 连续通道会在 Failed 里继续震（审查 A1 同类）
            metrics?.onFail()
            stopPlayer()
            onLog("FAIL \(resolved.semanticId)#\(id)")
        case "release": release()
        case "none": break
        default: onLog("未知动作：\(action) —— PlaybackActions 与迁移表脱节了")
        }
    }

    // MARK: - 平台提交

    private func doSubmit() {
        let t1 = monotonicNanos()
        do {
            try engine.ensureRunning()
            let p: HapticPlayer
            if resolved.kind == .continuous, let c = resolved.continuous {
                // v4.3：起播强度取 coalescer 的 latest，不是 IR 的 initialIntensity（§4.6）
                p = try makeContinuousPlayer(c)
                try p.start()
                coalescer.markSentAt(scheduler.nowMs())
                scheduleRenew(c.maxDurationMs)
            } else {
                p = try gateway.makePlayer(events: IOSTranslator.events(resolved))
                try p.start()
            }
            player?.stop()
            player = p
            probe?.onSample(.platformSubmit, nanos: monotonicNanos() &- t1)
            fsm.send("SUBMIT_OK")
        } catch {
            onLog("submitFail \(resolved.semanticId)#\(id) ← \(error)")
            fsm.send("FAIL")
        }
    }

    private func makeContinuousPlayer(_ c: ContinuousSpec) throws -> HapticPlayer {
        let (i, s) = coalescer.latest() ?? (c.initialIntensity, c.initialSharpness)
        let ctl = IOSTranslator.continuousControls(intensity: i, sharpness: s)
        return try gateway.makeContinuousPlayer(event: IOSTranslator.continuousEvent(maxDurationMs: c.maxDurationMs),
                                                intensityControl: ctl.intensity, sharpnessControl: ctl.sharpness)
    }

    /// 连续通道到达单次排程上限前续排：换上新 player，对 FSM 不可见（IR §3.2b 约束 3）。
    private func scheduleRenew(_ maxDurationMs: Int) {
        renewTimer?.cancel()
        renewTimer = scheduler.schedule(afterMs: max(1, maxDurationMs - Self.renewLeadMs)) { [weak self] in
            self?.renew()
        }
    }

    private func renew() {
        renewTimer = nil
        guard fsm.state == "Active", let c = resolved.continuous else { return }
        do {
            try engine.ensureRunning()
            let p = try makeContinuousPlayer(c)
            try p.start()
            player?.stop()
            player = p
            scheduleRenew(c.maxDurationMs)
        } catch {
            onLog("renewFail \(resolved.semanticId)#\(id) ← \(error)")
            fsm.send("FAIL")
        }
    }

    private func sendContinuous(_ intensity: Float, _ sharpness: Float) {
        let ctl = IOSTranslator.continuousControls(intensity: intensity, sharpness: sharpness)
        do {
            try player?.sendParameters(intensityControl: ctl.intensity, sharpnessControl: ctl.sharpness)
        } catch {
            onLog("sendParameters 失败 \(resolved.semanticId)#\(id) ← \(error)")
        }
    }

    private func stopPlayer() {
        player?.stop()
        player = nil
    }

    // MARK: - 定时器

    private func startEndTimer() {
        cancel(&endTimer)
        endTimer = scheduler.schedule(afterMs: resolved.totalDurationMs) { [weak self] in
            self?.fsm.send("NATURAL_END")
        }
        // looping 的时长：由应用告知（库兜底上限），只排一次，不随每轮 resubmit 重置
        if resolved.kind == .looping && loopDeadline == nil {
            let ms = loopDeadlineMs ?? DecisionPipeline.maxLoopDurationMs
            loopDeadline = scheduler.schedule(afterMs: ms) { [weak self] in
                self?.onLog("looping 到达应用告知的时长 \(ms)ms，结束")
                self?.fsm.send("CANCEL")
            }
        }
    }

    private func startIdleTimer() {
        cancel(&idleTimer)
        guard let timeout = resolved.continuous?.idleTimeoutMs else { return }
        idleTimer = scheduler.schedule(afterMs: timeout) { [weak self] in self?.fsm.send("NATURAL_END") }
    }

    private func startGraceTimer() {
        cancel(&graceTimer)
        graceTimer = scheduler.schedule(afterMs: Self.graceMs) { [weak self] in self?.fsm.send("GRACE_EXPIRED") }
    }

    private func startKeepAlive() {
        cancel(&keepAliveTimer)
        keepAliveTimer = scheduler.schedule(afterMs: Self.keepAliveMs) { [weak self] in self?.fsm.send("CANCEL") }
    }

    private func cancel(_ t: inout HapticCancellable?) {
        t?.cancel()
        t = nil
    }

    private func cancelAllTimers() {
        cancel(&endTimer)
        cancel(&idleTimer)
        cancel(&keepAliveTimer)
        cancel(&renewTimer)
    }

    /// **唯一的资源释放点。** finally 语义。
    private func release() {
        cancelAllTimers()
        cancel(&graceTimer)
        cancel(&loopDeadline)
        cancel(&expireTimer)
        coalescer.reset()
        stopPlayer()
    }

    /// 回收时是否还持有任何资源 —— 非 true 即状态机有抵达不了 Reclaimed 的路径（leakSuspect）。
    func anyResourceHeld() -> Bool {
        player != nil || endTimer != nil || idleTimer != nil || keepAliveTimer != nil ||
            graceTimer != nil || loopDeadline != nil || expireTimer != nil || renewTimer != nil ||
            coalescer.hasPendingFlush
    }
}
