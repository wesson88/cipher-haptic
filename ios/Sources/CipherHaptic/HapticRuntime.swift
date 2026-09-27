import Foundation
import CipherHapticCore

/// facade 背后的真正状态持有者。镜像 android/library/CipherHaptic.kt 的类体。
///
/// ## 为什么不直接放在 actor 里
///
/// 契约要求 `public actor CipherHaptic` + `nonisolated` 同步签名（调用方不写 async）。nonisolated 方法
/// 碰不到 actor 隔离的状态，而 actor 本身只串行化 manager 状态、不保证底层输出串行（骨架 §3.1）。
/// 于是真正的状态放在这里，由**一条串行调度队列**驱动（FSM §七.1）；actor 退化为对外的壳。
///
/// ## 线程
///
/// - 队列内（`scheduler`）：handles / startedAt / continuousHandle / 引擎状态机 —— 只在队列上读写
/// - 任意线程：master / scale / mute / 引擎相位 / observers / delegate —— 锁保护的快照，同步 getter 不阻塞
/// - 回调（debugDelegate / MuteStateObserver / MetricsSink）一律经 `deliver` 投递，生产为主线程
///   （A.2 回调线程契约；Android 在调度线程上投递，审查 C4）
final class HapticRuntime: @unchecked Sendable {
    static let defaultCapacity = 2                  // ⚠️ 待实测（性能 §二）
    static let defaultCoalesceWindowMs = 100        // ⚠️ 待实测（性能 §5.4）

    let loader: SpecLoader
    let scheduler: HapticScheduler
    let gateway: HapticGateway
    let hardwareClass: CipherHapticHardwareClass
    let metrics: MetricsCollector
    private(set) var engine: EngineController!
    private let table: TransitionTable
    private let frameClock: FrameClock
    private let lifecycle: LifecycleSource
    private let capacity: Int
    private let coalesceWindowMs: Int
    private let latencyProbe: LatencyProbe?
    private let deliver: (@escaping () -> Void) -> Void

    // ── 队列内状态 ────────────────────────────────────────────────
    private var handles: [Int: PlaybackHandle] = [:]
    private var startedAt: [Int: Int] = [:]
    private var nextId = 1
    /// 连续通道：**全局单例**（P-20 登记的显式取舍）
    private var continuousHandle: PlaybackHandle?

    // ── 任意线程可读的快照（锁保护）─────────────────────────────────
    private let lock = NSLock()
    private var masterEnabled = true
    private var scale: Float = 1
    private var mute: MuteState = .unmuted
    /// P-14：iOS 无公开 API 读「系统触感反馈」开关，暂恒为 true（待拍板）
    private var systemHapticsEnabled = true
    private var phase: CipherHapticEngineState = .idle
    private var observers: [MuteStateObserver] = []
    private weak var _debugDelegate: CipherHapticDebugDelegate?
    private var metricsSink: CipherHapticMetricsSink?

    init(loader: SpecLoader, scheduler: HapticScheduler, gateway: HapticGateway,
         frameClock: FrameClock, lifecycle: LifecycleSource,
         hardwareClassOverride: CipherHapticHardwareClass? = nil,
         capacity: Int = HapticRuntime.defaultCapacity,
         coalesceWindowMs: Int = HapticRuntime.defaultCoalesceWindowMs,
         latencyProbe: LatencyProbe? = nil, metricsSink: CipherHapticMetricsSink? = nil,
         deliver: @escaping (@escaping () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) }) throws {
        self.loader = loader
        self.scheduler = scheduler
        self.gateway = gateway
        self.frameClock = frameClock
        self.lifecycle = lifecycle
        self.capacity = capacity
        self.coalesceWindowMs = coalesceWindowMs
        self.latencyProbe = latencyProbe
        self.metricsSink = metricsSink
        self.deliver = deliver
        self.table = try TransitionTable(json: loader.transitionsJson)
        // 硬件档：初始化时定档，运行时不变。iOS 只有 supportsHaptics 一个布尔 → 自动探测只出两档（P-07）；
        // LINEAR_X_LIMITED 只能来自注入 —— 诚实地承认"这就是一张白名单"
        let hw = hardwareClassOverride ?? (gateway.supportsHaptics ? .linearXFull : .ermZ)
        self.hardwareClass = hw
        self.metrics = MetricsCollector { hw }
        self.engine = EngineController(gateway: gateway, scheduler: scheduler, metrics: metrics) { [weak self] p in
            self?.locked { self?.phase = p }
            self?.notifyState("engine → \(p)")
        }

        // Core Haptics 的 stopped / reset 在系统队列上触发 → marshal 到串行队列（FSM §七.1）
        gateway.onInterruption = { [weak self] i in
            self?.scheduler.submit { self?.handleInterruption(i) }
        }
        lifecycle.start(
            onSuspend: { [weak self] in self?.scheduler.submit { self?.broadcast("SUSPEND") } },
            onResume: { [weak self] in self?.scheduler.submit { self?.broadcast("RESUME") } }
        )
    }

    @discardableResult
    private func locked<T>(_ f: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return f()
    }

    var debugDelegate: CipherHapticDebugDelegate? {
        get { locked { _debugDelegate } }
        set { locked { _debugDelegate = newValue } }
    }

    // MARK: - 核心控制

    func play(_ s: CipherHapticSemantic, onNextFrame: Bool, t0: UInt64) {
        let go = { [weak self] in
            guard let self = self else { return }
            self.scheduler.submit { _ = self.startPlayback(s, opts: PlayOpts(), t0: t0) }
        }
        if onNextFrame { frameClock.postFrameCallback(go) } else { go() }
    }

    /// 接口 3：循环多久由应用告知（v1.4.0）；库兜底 5 分钟，≤0 则 drop（looping-needs-duration）。
    /// 每一轮提交一次**有限** pattern，由 end-timer 按 totalDurationMs（含 loopGapMs）重提交下一轮（P-17）。
    func playLooping(_ s: CipherHapticSemantic, maxDurationMs: Int, t0: UInt64) -> CipherHapticCancelToken {
        let token = HandleToken(runtime: self)
        scheduler.submit { [weak self] in
            guard let self = self else { return }
            let h = self.startPlayback(s, opts: PlayOpts(loopMaxDurationMs: maxDurationMs), t0: t0) { h in
                token.bind(h)
                h.onFinished = { [weak token] in token?.markFinished() }
            }
            if h == nil { token.markFinished() }
        }
        return token
    }

    func stopAll() {
        scheduler.submit { [weak self] in self?.broadcast("CANCEL") }
    }

    func updateContinuous(intensity: Float, sharpness: Float) {
        scheduler.submit { [weak self] in
            guard let self = self else { return }
            // 上一条通道已被 idle 超时结束、还在 grace 里时，视同没有通道 —— 否则这段时间的手势全被吸收
            if let existing = self.continuousHandle, Self.isLive(existing.state) {
                // v4.3：平台就绪前只缓冲（不碰平台、不动 idle-timer），就绪后才 trailing coalesce
                if existing.state == "Active" {
                    existing.coalescer.update(intensity, sharpness)
                } else {
                    existing.coalescer.buffer(intensity, sharpness)
                }
                existing.fsm.send("UPDATE")
            } else {
                // 首次调用：起播。参数必须在 SUBMIT 之前进 coalescer，由 submit 取用（§4.6）
                _ = self.startPlayback(.gestureTrack, opts: PlayOpts(), t0: monotonicNanos()) { h in
                    h.coalescer.buffer(intensity, sharpness)
                    self.continuousHandle = h
                }
            }
        }
    }

    func endContinuous() {
        scheduler.submit { [weak self] in
            guard let self = self else { return }
            if let h = self.continuousHandle {
                h.coalescer.flushPending()          // §七.5 末句：结束前必须 flush
                h.fsm.send("CANCEL")
            }
            self.continuousHandle = nil
            self.sweep()
        }
    }

    // MARK: - 预热与可用性

    func prepare(_ s: CipherHapticSemantic) {
        let scale = globalScale
        scheduler.submit { [weak self] in
            guard let self = self else { return }
            try? self.engine.ensureRunning()
            // 预编译 pattern：缓存 key 是翻译后的事件（已含 scale / 硬件档的作用结果）
            if let rw = try? self.loader.resolve(s.rawValue, self.hardwareClass, scale), rw.kind != .continuous {
                self.gateway.prepare(events: IOSTranslator.events(rw))
            }
        }
    }

    /// 与播放同一口径：直接问纯函数管线（Android 的 preview 漏查 system-off / dnd）。
    func preview(_ s: CipherHapticSemantic) -> CipherHapticAvailability {
        let d = DecisionPipeline.decide(
            semanticId: s.rawValue, opts: PlayOpts(loopMaxDurationMs: DecisionPipeline.maxLoopDurationMs),
            ctx: snapshot(), loader: loader, active: [], capacity: capacity, coalesceWindowMs: coalesceWindowMs)
        switch d {
        case .drop(let r):
            return CipherHapticAvailability(willPlay: false,
                                            degradedTo: r == DropReason.degradedToSilent ? "silent" : nil, reason: r)
        case .play(let p):
            let a = p.resolved.degradeTrace.first
            return CipherHapticAvailability(willPlay: true, degradedTo: a == "full" ? nil : a, reason: nil)
        }
    }

    // MARK: - 配置 / 系统状态

    func setEnabled(_ enabled: Bool) {
        locked { masterEnabled = enabled }
        if !enabled { stopAll() }
    }

    var isEnabled: Bool { locked { masterEnabled } }

    /// ⚠️ P-04：scale 只缩放 intensity（「变轻不变钝」）
    func setScale(_ s: Float) { locked { scale = min(max(s, 0), 1) } }

    var globalScale: Float { locked { scale } }

    var muteState: MuteState { locked { mute } }

    func register(_ o: MuteStateObserver) { locked { observers.append(o) } }

    func unregister(_ o: MuteStateObserver) { locked { observers.removeAll { $0 === o } } }

    var capabilities: CipherHapticCapabilities {
        CipherHapticCapabilities(
            hardwareClass: hardwareClass,
            supportsSharpness: gateway.supportsSharpness,
            supportsBackgroundPlayback: false,          // P-06：保守值，待 V1 真机验证
            systemHapticsEnabled: locked { systemHapticsEnabled }
        )
    }

    var engineState: CipherHapticEngineState { locked { phase } }

    // MARK: - 决策管线：取快照 → 纯函数决策 → 执行

    private func snapshot() -> PipelineContext {
        locked {
            PipelineContext(
                masterEnabled: masterEnabled,
                systemHapticsEnabled: systemHapticsEnabled,
                mute: mute == .unmuted ? .none : (mute == .dnd ? .dnd : .hardware),
                globalScale: scale,
                hardwareClass: hardwareClass,
                apiGate: ApiGate(compositionSupported: false)   // iOS 没有 Composition 路径
            )
        }
    }

    private func activeSnapshot() -> [PreemptionPolicy.ActiveHandleInfo] {
        sweep()
        let now = scheduler.nowMs()
        return handles.values.sorted { $0.id < $1.id }.map {
            PreemptionPolicy.ActiveHandleInfo(
                id: $0.id, category: $0.resolved.category, kind: $0.resolved.kind,
                elapsedMs: now - (startedAt[$0.id] ?? now),
                protectedFromPreemption: $0.resolved.protectedFromPreemption, state: $0.state)
        }
    }

    /// **决策 + 抢占 + 提交在同一个队列任务里**，是不可分的 critical section（§七.3）。
    /// - Parameter t0: facade 入口（marshal 之前）取的时刻 —— Android 取在队列内，位置不对（审查 C5）
    private func startPlayback(_ s: CipherHapticSemantic, opts: PlayOpts, t0: UInt64,
                               preSubmit: (PlaybackHandle) -> Void = { _ in }) -> PlaybackHandle? {
        metrics.onRequest()
        let decision = DecisionPipeline.decide(
            semanticId: s.rawValue, opts: opts, ctx: snapshot(), loader: loader,
            active: activeSnapshot(), capacity: capacity, coalesceWindowMs: coalesceWindowMs)
        let play: Decision.Play
        switch decision {
        case .drop(let reason):
            drop(s, reason)
            return nil
        case .play(let p):
            play = p
        }
        let rw = play.resolved
        // `full` = 没有降级，不计入（否则该指标恒等于总播放数）；且只在 Play 时记
        if let action = rw.degradeTrace.first, action != "full" {
            metrics.onDegrade(action)
            deliver { [weak self] in self?.debugDelegate?.hapticEngine(didDegradeEffect: s, reason: action) }
        }
        // ⑥ 执行抢占 —— 目标由管线算好，执行是发 CANCEL（§8.2）
        for id in play.preemptTargets {
            metrics.onPreempted()
            handles[id]?.fsm.send("CANCEL")
        }
        latencyProbe?.onSample(.decision, nanos: monotonicNanos() &- t0)

        // ⑦ submit —— handle 创建 + 平台提交在同一 critical section 内（§七.3）
        let h = PlaybackHandle(
            id: nextId, resolved: rw, scheduler: scheduler, gateway: gateway, engine: engine,
            loopDeadlineMs: play.loopDeadlineMs, probe: latencyProbe, metrics: metrics
        ) { [weak self] msg in self?.notifyState(msg) }
        nextId += 1
        h.attach(table)
        // 回收必须是【推送式】：Reclaimed 由 grace / EXPIRE 定时器驱动，没有下一次业务调用时也要摘表
        h.onReclaimed = { [weak self, weak h] in
            guard let self = self, let h = h else { return }
            self.retire(h.id)
        }
        handles[h.id] = h
        startedAt[h.id] = scheduler.nowMs()
        preSubmit(h)                        // continuous 在此塞入手指当前位置；looping 在此绑定 token
        h.fsm.send("SUBMIT")
        latencyProbe?.onSample(.softwareTotal, nanos: monotonicNanos() &- t0)
        // 失败由 report 动作计数（Android 在这里另记一次，首提交失败被计两遍）
        if h.state == "Active" { metrics.onSubmitted() }
        sweep()
        metrics.onActiveCountChanged(handles.count)
        if let snap = metrics.dueSnapshot(nowMs: scheduler.nowMs()), let sink = metricsSink {
            deliver { sink.hapticMetrics(snap) }
        }
        return h
    }

    /// 管线拦截。**每一次 drop 都必须被计数**。
    private func drop(_ s: CipherHapticSemantic, _ reason: String) {
        metrics.onDrop(reason)
        deliver { [weak self] in self?.debugDelegate?.hapticEngine(didDropEffect: s, reason: reason) }
    }

    private func notifyState(_ msg: String) {
        deliver { [weak self] in self?.debugDelegate?.hapticEngine(didChangeState: msg) }
    }

    /// engine 级中断（FSM §十 表尾）：对所有 Active / Paused / Submitting 发 `CANCEL(engine_reset)`，
    /// 不恢复播放（策略 A「保守终结」）。进后台的 stop 例外 —— 那由生命周期的 SUSPEND 走 keepAlive 分支，
    /// 在这里一刀切 CANCEL 会立刻杀掉 critical（文档未区分 stop 与 reset，已登记）。
    private func handleInterruption(_ i: EngineInterruption) {
        engine.handle(i)
        if case .stopped(suspended: true) = i { return }
        for h in handles.values.sorted(by: { $0.id < $1.id })
        where ["Active", "Paused", "Submitting"].contains(h.state) {
            notifyState("\(h.resolved.semanticId)#\(h.id) CANCEL(engine_reset)")
            h.fsm.send("CANCEL")
        }
        sweep()
    }

    private func broadcast(_ event: String) {
        for h in handles.values.sorted(by: { $0.id < $1.id }) { h.fsm.send(event) }
        sweep()
    }

    /// handle 进入 `Reclaimed` 时摘表（推送式）。grace 中的仍留表但不占容量槽。
    private func retire(_ id: Int) {
        if let h = handles[id], h.anyResourceHeld() { metrics.onLeakSuspect() }
        handles.removeValue(forKey: id)
        startedAt.removeValue(forKey: id)
        if continuousHandle?.id == id { continuousHandle = nil }
        metrics.onActiveCountChanged(handles.count)
    }

    /// 兜底清理：正常路径靠 retire 推送。
    fileprivate func sweep() {
        for (id, h) in handles where h.state == "Reclaimed" { retire(id) }
    }

    fileprivate func cancel(_ h: PlaybackHandle) {
        h.fsm.send("CANCEL")
        sweep()
    }

    private static func isLive(_ state: String) -> Bool {
        !["Completed", "Cancelled", "Failed", "Reclaimed"].contains(state)
    }

    // MARK: - 调试

    /// 活跃 handle 的状态分布（压测排查"handle 未归零"用）。须在队列上调用。
    func debugHandleStates() -> [String: Int] {
        var m: [String: Int] = [:]
        for h in handles.values { m["\(h.state)/\(h.resolved.kind.rawValue)", default: 0] += 1 }
        return m
    }

    var activeHandleCount: Int { handles.count }
}

/// `playLoopingEffect` 返回的 token。字段线程安全（Android 的 token 字段不是 volatile，审查 C4）。
/// 弱引用 handle：token 丢了不影响 engine 按时回收。
final class HandleToken: CipherHapticCancelToken {
    private let lock = NSLock()
    private var cancelled = false
    private var finished = false
    /// 只在调度队列上读写
    private weak var handle: PlaybackHandle?
    private weak var runtime: HapticRuntime?

    init(runtime: HapticRuntime) { self.runtime = runtime }

    func bind(_ h: PlaybackHandle) { handle = h }

    func markFinished() {
        lock.lock()
        finished = true
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        cancelled = true
        lock.unlock()
        runtime?.scheduler.submit { [weak self] in
            guard let self = self, let h = self.handle else { return }
            self.runtime?.cancel(h)
        }
    }

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    var isFinished: Bool {
        lock.lock()
        defer { lock.unlock() }
        return finished
    }
}
