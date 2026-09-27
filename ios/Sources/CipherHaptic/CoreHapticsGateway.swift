#if canImport(CoreHaptics)
import CoreHaptics
import Foundation
import CipherHapticCore

/// Core Haptics 网关（主文档 B.4 / B.7，性能 §一）。
///
/// - **只走** `[CHHapticEvent] → CHHapticPattern(events:parameters:) → engine.makePlayer(with:)`，
///   并持有返回的句柄。禁用 `CHHapticPattern(contentsOf:)`（iOS 16+）与 `playPattern(from:)`（无句柄）。
/// - engine 懒启动，启动后常驻；`playsHapticsOnly = true` 且在 start 之前设，**不碰 AVAudioSession**
///   （不 setActive、不改 category，P-09 / V2 定论前的保守做法）。
/// - `isAutoShutdownEnabled` 保持 false：否则会以 idle 原因自停，与"常驻"冲突。
/// - stopped / reset 回调在系统队列上触发，这里只转发；状态变更由运行时 marshal 到串行队列后做。
final class CoreHapticsGateway: HapticGateway {

    static var hardwareSupportsHaptics: Bool { CHHapticEngine.capabilitiesForHardware().supportsHaptics }

    let supportsHaptics = true
    let supportsSharpness = true
    var onInterruption: ((EngineInterruption) -> Void)?

    private var engine: CHHapticEngine?
    private var running = false
    /// 预编译缓存：key 是翻译后的事件本身（已含 scale / 硬件档的作用结果），随 engine reset 清空
    private var patternCache: [[IOSEvent]: CHHapticPattern] = [:]

    func startEngine() throws {
        if engine == nil {
            let e = try CHHapticEngine()
            e.playsHapticsOnly = true
            e.isAutoShutdownEnabled = false
            e.stoppedHandler = { [weak self] reason in
                self?.onInterruption?(.stopped(suspended: reason == .applicationSuspended))
            }
            e.resetHandler = { [weak self] in
                self?.onInterruption?(.reset)
            }
            engine = e
        }
        if !running {
            try engine?.start()
            running = true
        }
    }

    func engineDidStop(reset: Bool) {
        running = false
        if reset { patternCache.removeAll() }
    }

    func makePlayer(events: [IOSEvent]) throws -> HapticPlayer {
        guard let engine = engine else { throw GatewayError(description: "engine 未启动") }
        let pattern = try cachedPattern(events)
        return PatternPlayer(try engine.makePlayer(with: pattern))
    }

    func makeContinuousPlayer(event: IOSEvent, intensityControl: Float, sharpnessControl: Float) throws -> HapticPlayer {
        guard let engine = engine else { throw GatewayError(description: "engine 未启动") }
        // 起播值作为 pattern 的初始 dynamic parameter 传入（见 IOSTranslator 的连续通道注释）
        let params = [
            CHHapticDynamicParameter(parameterID: .hapticIntensityControl, value: intensityControl, relativeTime: 0),
            CHHapticDynamicParameter(parameterID: .hapticSharpnessControl, value: sharpnessControl, relativeTime: 0),
        ]
        let pattern = try CHHapticPattern(events: [Self.chEvent(event)], parameters: params)
        return PatternPlayer(try engine.makePlayer(with: pattern))
    }

    func prepare(events: [IOSEvent]) {
        _ = try? cachedPattern(events)
    }

    private func cachedPattern(_ events: [IOSEvent]) throws -> CHHapticPattern {
        if let p = patternCache[events] { return p }
        let p = try CHHapticPattern(events: events.map(Self.chEvent), parameters: [])
        patternCache[events] = p
        return p
    }

    private static func chEvent(_ e: IOSEvent) -> CHHapticEvent {
        let params = [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: e.intensity),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: e.sharpness),
        ]
        switch e.eventType {
        case .hapticTransient:
            return CHHapticEvent(eventType: .hapticTransient, parameters: params, relativeTime: e.relativeTime)
        case .hapticContinuous:
            return CHHapticEvent(eventType: .hapticContinuous, parameters: params,
                                 relativeTime: e.relativeTime, duration: e.duration ?? 0)
        }
    }

    private final class PatternPlayer: HapticPlayer {
        private let player: CHHapticPatternPlayer
        init(_ p: CHHapticPatternPlayer) { player = p }

        func start() throws { try player.start(atTime: CHHapticTimeImmediate) }

        func stop() { try? player.stop(atTime: CHHapticTimeImmediate) }

        func sendParameters(intensityControl: Float, sharpnessControl: Float) throws {
            try player.sendParameters([
                CHHapticDynamicParameter(parameterID: .hapticIntensityControl, value: intensityControl, relativeTime: 0),
                CHHapticDynamicParameter(parameterID: .hapticSharpnessControl, value: sharpnessControl, relativeTime: 0),
            ], atTime: CHHapticTimeImmediate)
        }
    }
}
#endif
