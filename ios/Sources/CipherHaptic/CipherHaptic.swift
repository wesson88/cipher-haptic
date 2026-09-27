import Foundation
import CipherHapticCore

/// CipherHaptic facade —— **对外能力的唯一入口**（主文档 A.2，17 方法 / 11 项能力）。
///
/// 签名与 `spec/contracts.md` 逐字一致（`tools/check.py` 的 contract 规则整行对拍）。
///
/// ## 线程契约
///
/// 外壳沿用 `actor` + `nonisolated` 同步签名，调用方不必写 async。所有 mutator 与定时器回调
/// marshal 到单一串行调度队列；所有同步 getter 读锁保护的快照，任意线程可调、不阻塞。
/// 回调（debugDelegate / MuteStateObserver / MetricsSink）一律投递到主线程。
///
/// ## 所有方法均不抛异常
///
/// 硬件不可用时静默降级或丢弃（A.1 约束 3），但失败必须可观测：`debugDelegate`（逐事件、开发期）
/// 与 `CipherHapticMetricsSink`（聚合、生产期）两条路。
public actor CipherHaptic {

    let runtime: HapticRuntime

    /// 循环效果的库兜底上限（ms）：应用告知的 `maxDurationMs` 超过它按它截断。
    public static let maxLoopDurationMs = DecisionPipeline.maxLoopDurationMs

    /// 生产装配入口。
    ///
    /// - Parameters:
    ///   - hardwareClassOverride: 产出 `linearXLimited` 的**唯一**路径（P-07）
    ///   - latencyProbe: 延迟埋点。调音台必须注入（V5 §6.2b）；生产按需
    ///   - metricsSink: 生产指标出口，60s 低频聚合
    public init(hardwareClassOverride: CipherHapticHardwareClass? = nil,
                latencyProbe: LatencyProbe? = nil,
                metricsSink: CipherHapticMetricsSink? = nil) {
        do {
            #if canImport(UIKit) && !os(watchOS) && !os(tvOS)
            let frameClock: FrameClock = DisplayLinkFrameClock()
            let lifecycle: LifecycleSource = UIKitLifecycle()
            #else
            let frameClock: FrameClock = MainQueueFrameClock()
            let lifecycle: LifecycleSource = NoLifecycle()
            #endif
            runtime = try HapticRuntime(
                loader: SpecLoader.embedded(), scheduler: DispatchHapticScheduler(),
                gateway: PlatformGateway.make(), frameClock: frameClock, lifecycle: lifecycle,
                hardwareClassOverride: hardwareClassOverride, latencyProbe: latencyProbe, metricsSink: metricsSink)
        } catch {
            // 内嵌 spec 损坏是打包错误（CI 规则 10 / ResourceEmbed 测试应已拦下），不是运行时可恢复的状况
            fatalError("CipherHaptic: 内嵌 runtime.min.json 无法解析 —— \(error)")
        }
    }

    init(runtime: HapticRuntime) {
        self.runtime = runtime
    }

    /// 开发期逐事件出口（A.6）。弱引用；回调投递到主线程。
    public nonisolated var debugDelegate: CipherHapticDebugDelegate? {
        get { runtime.debugDelegate }
        set { runtime.debugDelegate = newValue }
    }

    // MARK: - 核心控制（6 方法）

    public nonisolated func playEffect(_ s: CipherHapticSemantic) {
        runtime.play(s, onNextFrame: false, t0: monotonicNanos())
    }

    /// 接口 2：**只承诺"在下一个 VSync 边界提交"**，不承诺绝对时刻精度（P-02 pending）。
    public nonisolated func playEffect(_ s: CipherHapticSemantic, onNextFrame: Bool) {
        runtime.play(s, onNextFrame: onNextFrame, t0: monotonicNanos())
    }

    /// 接口 3：循环多久由应用告知（v1.4.0）。库兜底 `maxLoopDurationMs`；`maxDurationMs ≤ 0` 则 drop。
    /// 到期或 `token.cancel()` 都会结束循环；到期结束时 `isFinished == true` 而 `isCancelled == false`。
    public nonisolated func playLoopingEffect(_ s: CipherHapticSemantic, maxDurationMs: Int) -> CipherHapticCancelToken {
        runtime.playLooping(s, maxDurationMs: maxDurationMs, t0: monotonicNanos())
    }

    public nonisolated func stopAllEffects() {
        runtime.stopAll()
    }

    public nonisolated func updateContinuousEffect(intensity: Float, sharpness: Float) {
        runtime.updateContinuous(intensity: intensity, sharpness: sharpness)
    }

    public nonisolated func endContinuousEffect() {
        runtime.endContinuous()
    }

    // MARK: - 预热与可用性（2 方法）

    public nonisolated func prepare(_ s: CipherHapticSemantic) {
        runtime.prepare(s)
    }

    public nonisolated func preview(_ s: CipherHapticSemantic) -> CipherHapticAvailability {
        runtime.preview(s)
    }

    // MARK: - 配置（4 方法）

    public nonisolated func setHapticsEnabled(_ enabled: Bool) {
        runtime.setEnabled(enabled)
    }

    public nonisolated func isHapticsEnabled() -> Bool {
        runtime.isEnabled
    }

    /// ⚠️ P-04：scale 只缩放 intensity（「变轻不变钝」）。
    public nonisolated func setGlobalScale(_ scale: Float) {
        runtime.setScale(scale)
    }

    public nonisolated func globalScale() -> Float {
        runtime.globalScale
    }

    // MARK: - 系统状态（5 方法）

    public nonisolated func syncSystemMuteState() -> MuteState {
        runtime.muteState
    }

    public nonisolated func registerMuteObserver(_ o: MuteStateObserver) {
        runtime.register(o)
    }

    public nonisolated func unregisterMuteObserver(_ o: MuteStateObserver) {
        runtime.unregister(o)
    }

    public nonisolated func hardwareCapabilities() -> CipherHapticCapabilities {
        runtime.capabilities
    }

    public nonisolated func engineState() -> CipherHapticEngineState {
        runtime.engineState
    }

    // MARK: - 观测（契约外，与 Android 同名）

    /// 当前指标快照。调音台与宿主埋点都从这里读。
    public nonisolated func metricsSnapshot() -> CipherHapticMetricsSnapshot {
        runtime.metrics.snapshot()
    }
}
