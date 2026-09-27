import Foundation

/// 生产指标出口 —— 对应主文档 **A.6.1** 与性能文档 **§四.1**。镜像 android/core/Metrics.kt。
///
/// 本库零崩溃、静默降级，把绝大多数失败推进了"软失败"（不振动 / 振错了）。
/// 这个 sink 是软失败的**唯一**观测手段。低频聚合上报，非每次调用。
public protocol CipherHapticMetricsSink: AnyObject {
    func hapticMetrics(_ snapshot: CipherHapticMetricsSnapshot)
}

public struct CipherHapticMetricsSnapshot: Sendable {
    public let hardwareClass: CipherHapticHardwareClass
    public let playRequestCount: Int
    public let playSubmittedCount: Int
    /// 被管线拦掉的次数，按原因分
    public let dropCountsByReason: [String: Int]
    /// **实际发生的**降级动作分布。⚠️ 不含 `full`
    public let degradeCountsByAction: [String: Int]
    public let preemptedCount: Int
    /// 平台调用抛错次数
    public let failCount: Int
    /// Core Haptics `resetHandler` 触发次数（iOS 独有语义，P-16）
    public let engineRestartCount: Int
    public let circuitOpenCount: Int
    /// 回收时仍持有资源的 handle 数。正常恒 0
    public let leakSuspectCount: Int
    public let activeHandleCount: Int
    public let peakActiveHandleCount: Int

    /// 静默失败率 —— 请求了但没真的振动的比例。大盘首要看这个数。
    public var silentFailureRate: Double {
        playRequestCount == 0 ? 0 : Double(playRequestCount - playSubmittedCount) / Double(playRequestCount)
    }
}

/// 线程安全的计数器聚合。写入都在串行队列上，读取可跨线程。
public final class MetricsCollector {
    private let lock = NSLock()
    private let hardwareClass: () -> CipherHapticHardwareClass
    private var requests = 0, submitted = 0, preempted = 0, fails = 0
    private var restarts = 0, circuitOpens = 0, leaks = 0, peak = 0, activeNow = 0
    private var drops: [String: Int] = [:]
    private var degrades: [String: Int] = [:]
    private var lastReportAt: Int?

    public init(hardwareClass: @escaping () -> CipherHapticHardwareClass) {
        self.hardwareClass = hardwareClass
    }

    private func locked(_ f: () -> Void) {
        lock.lock()
        f()
        lock.unlock()
    }

    public func onRequest() { locked { requests += 1 } }
    public func onSubmitted() { locked { submitted += 1 } }
    public func onPreempted() { locked { preempted += 1 } }
    public func onFail() { locked { fails += 1 } }
    public func onEngineRestart() { locked { restarts += 1 } }
    public func onCircuitOpen() { locked { circuitOpens += 1 } }
    public func onLeakSuspect() { locked { leaks += 1 } }
    public func onDrop(_ reason: String) { locked { drops[reason, default: 0] += 1 } }
    public func onDegrade(_ action: String) { locked { degrades[action, default: 0] += 1 } }
    public func onActiveCountChanged(_ n: Int) {
        locked {
            activeNow = n
            peak = max(peak, n)
        }
    }

    public func snapshot() -> CipherHapticMetricsSnapshot {
        let hw = hardwareClass()
        lock.lock()
        defer { lock.unlock() }
        return CipherHapticMetricsSnapshot(
            hardwareClass: hw, playRequestCount: requests, playSubmittedCount: submitted,
            dropCountsByReason: drops, degradeCountsByAction: degrades, preemptedCount: preempted,
            failCount: fails, engineRestartCount: restarts, circuitOpenCount: circuitOpens,
            leakSuspectCount: leaks, activeHandleCount: activeNow, peakActiveHandleCount: peak
        )
    }

    /// 低频上报：距上次超过 `intervalMs` 才推。返回 nil = 本次不推。
    public func dueSnapshot(nowMs: Int, intervalMs: Int = 60_000) -> CipherHapticMetricsSnapshot? {
        lock.lock()
        let due = lastReportAt.map { nowMs - $0 >= intervalMs } ?? true
        if due { lastReportAt = nowMs }
        lock.unlock()
        return due ? snapshot() : nil
    }
}

/// 延迟埋点 —— 对应 P0 验证计划 **V5 §6.2b**。T0→T1 必须单独切出来（是否下沉 C++ 的唯一度量）。
public protocol LatencyProbe: AnyObject {
    func onSample(_ segment: LatencySegment, nanos: UInt64)
}

public enum LatencySegment: Sendable {
    /// T0→T1：facade 入口（marshal 之前）→ 决策管线产出 `Decision`
    case decision
    /// T1→T2：`makePlayer` + `start` 往返
    case platformSubmit
    /// T0→T2：软件延迟合计（含调度排队）
    case softwareTotal
}

/// 单调纳秒时钟（与 `mach_absolute_time` 同类，睡眠期间不走）。
@inlinable
public func monotonicNanos() -> UInt64 { DispatchTime.now().uptimeNanoseconds }
