import Foundation
import CipherHapticCore

/// CHHapticEngine 生命周期 + 熔断（主文档 B.4，性能 §一，FSM §9.2）。iOS 独有（P-16）。
///
/// - 懒启动：第一次 submit / prepare 时 `startEngine`；启动后常驻，库不主动销毁。
/// - 被系统 stop / reset 后进 `recovering`，**下次提交时懒重启**，对上层无感。
/// - 连续启动失败、或短时间内频繁 reset（session 冲突信号）→ `circuitOpen`，冷却期内一律 FAIL
///   并计数 —— 按 FSM §9.2 处理为 FAIL，不在运行时切到 UIImpact（那等于在 IR 之后做决策）。
///
/// 只在串行调度队列上调用。
final class EngineController {
    /// ⚠️ 以下阈值全部**待实测**（性能 §5.4 / V5），文档无数值
    static let startFailLimit = 3
    static let circuitCooldownMs = 5_000
    static let resetStormWindowMs = 10_000
    static let resetStormLimit = 3

    private let gateway: HapticGateway
    private let scheduler: HapticScheduler
    private let metrics: MetricsCollector
    private let onPhase: (CipherHapticEngineState) -> Void

    private(set) var phase: CipherHapticEngineState = .idle {
        didSet { if phase != oldValue { onPhase(phase) } }
    }
    private var startFailures = 0
    private var circuitUntil = 0
    private var recentResets: [Int] = []

    init(gateway: HapticGateway, scheduler: HapticScheduler, metrics: MetricsCollector,
         onPhase: @escaping (CipherHapticEngineState) -> Void) {
        self.gateway = gateway
        self.scheduler = scheduler
        self.metrics = metrics
        self.onPhase = onPhase
    }

    func ensureRunning() throws {
        if phase == .circuitOpen {
            guard scheduler.nowMs() >= circuitUntil else {
                throw GatewayError(description: "engine 熔断冷却中")
            }
            phase = .recovering
        }
        if phase == .running { return }
        do {
            try gateway.startEngine()
            startFailures = 0
            phase = .running
        } catch {
            startFailures += 1
            if startFailures >= Self.startFailLimit { openCircuit() }
            throw error
        }
    }

    func handle(_ i: EngineInterruption) {
        gateway.engineDidStop(reset: i == .reset)
        if i == .reset {
            metrics.onEngineRestart()
            let now = scheduler.nowMs()
            recentResets = recentResets.filter { now - $0 < Self.resetStormWindowMs } + [now]
            if recentResets.count >= Self.resetStormLimit {
                // 短时间频繁 reset 视为 session 冲突信号，不能无脑重启（性能 §一）
                recentResets.removeAll()
                openCircuit()
                return
            }
        }
        if phase != .circuitOpen { phase = .recovering }
    }

    private func openCircuit() {
        startFailures = 0
        circuitUntil = scheduler.nowMs() + Self.circuitCooldownMs
        phase = .circuitOpen
        metrics.onCircuitOpen()
    }
}
