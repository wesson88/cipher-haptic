import Foundation
@testable import CipherHaptic
import CipherHapticCore

enum SpecFiles {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    static func loader() throws -> SpecLoader {
        try SpecLoader(data: Data(contentsOf: root.appendingPathComponent("spec/runtime.min.json")))
    }
}

struct FakeError: Error {}

final class FakePlayer: HapticPlayer {
    let events: [IOSEvent]
    let continuous: Bool
    var initialControls: (Float, Float)?
    var started = 0
    var stopped = 0
    var params: [(Float, Float)] = []
    private unowned let gw: FakeGateway

    init(_ events: [IOSEvent], continuous: Bool, gw: FakeGateway) {
        self.events = events
        self.continuous = continuous
        self.gw = gw
    }

    var isPlaying: Bool { started > 0 && stopped == 0 }

    func start() throws {
        if gw.failing { throw FakeError() }
        started += 1
    }

    func stop() { stopped += 1 }

    func sendParameters(intensityControl: Float, sharpnessControl: Float) throws {
        params.append((intensityControl, sharpnessControl))
    }
}

/// 平台接缝的 fake —— 逐次记录平台调用（与 Android 测试的 mockk gateway 同构）。
final class FakeGateway: HapticGateway {
    var supportsHaptics = true
    var supportsSharpness = true
    var onInterruption: ((EngineInterruption) -> Void)?
    /// 置 true 后 makePlayer / start 抛错（模拟 engine 已失效）
    var failing = false
    /// 置 true 后 startEngine 抛错
    var failStart = false
    var startAttempts = 0
    var stopsSeen: [Bool] = []
    var prepared: [[IOSEvent]] = []
    var players: [FakePlayer] = []

    var oneShotPlayers: [FakePlayer] { players.filter { !$0.continuous } }
    var continuousPlayers: [FakePlayer] { players.filter { $0.continuous } }

    func startEngine() throws {
        startAttempts += 1
        if failStart { throw FakeError() }
    }

    func engineDidStop(reset: Bool) { stopsSeen.append(reset) }

    func makePlayer(events: [IOSEvent]) throws -> HapticPlayer {
        if failing { throw FakeError() }
        let p = FakePlayer(events, continuous: false, gw: self)
        players.append(p)
        return p
    }

    func makeContinuousPlayer(event: IOSEvent, intensityControl: Float, sharpnessControl: Float) throws -> HapticPlayer {
        if failing { throw FakeError() }
        let p = FakePlayer([event], continuous: true, gw: self)
        p.initialControls = (intensityControl, sharpnessControl)
        players.append(p)
        return p
    }

    func prepare(events: [IOSEvent]) { prepared.append(events) }
}

/// 手动推进的帧时钟：tick() 之前任务不执行。
final class ManualFrameClock: FrameClock {
    var pending: [() -> Void] = []
    func postFrameCallback(_ task: @escaping () -> Void) { pending.append(task) }
    func tick() {
        let t = pending
        pending.removeAll()
        t.forEach { $0() }
    }
}

final class FakeLifecycle: LifecycleSource {
    var suspend: (() -> Void)?
    var resume: (() -> Void)?
    func start(onSuspend: @escaping () -> Void, onResume: @escaping () -> Void) {
        suspend = onSuspend
        resume = onResume
    }
}

final class RecordingDelegate: CipherHapticDebugDelegate {
    var drops: [(CipherHapticSemantic, String)] = []
    var degrades: [(CipherHapticSemantic, String)] = []
    var states: [String] = []
    func hapticEngine(didChangeState state: String) { states.append(state) }
    func hapticEngine(didDegradeEffect semantic: CipherHapticSemantic, reason: String) { degrades.append((semantic, reason)) }
    func hapticEngine(didDropEffect semantic: CipherHapticSemantic, reason: String) { drops.append((semantic, reason)) }
}

final class RecordingSink: CipherHapticMetricsSink {
    var snapshots: [CipherHapticMetricsSnapshot] = []
    func hapticMetrics(_ snapshot: CipherHapticMetricsSnapshot) { snapshots.append(snapshot) }
}

/// 装配：假时钟 + fake 网关 + 立即投递回调。回归测试只推进时间，不手动发 GRACE_EXPIRED / EXPIRE（真机 §四）。
final class Rig {
    let sched = TestScheduler()
    let gw = FakeGateway()
    let frame = ManualFrameClock()
    let life = FakeLifecycle()
    let sink = RecordingSink()
    let runtime: HapticRuntime
    let haptic: CipherHaptic

    init(hw: CipherHapticHardwareClass? = nil, supportsHaptics: Bool = true) throws {
        gw.supportsHaptics = supportsHaptics
        gw.supportsSharpness = supportsHaptics
        runtime = try HapticRuntime(loader: SpecFiles.loader(), scheduler: sched, gateway: gw, frameClock: frame,
                                    lifecycle: life, hardwareClassOverride: hw, metricsSink: sink, deliver: { $0() })
        haptic = CipherHaptic(runtime: runtime)
    }

    var metrics: CipherHapticMetricsSnapshot { haptic.metricsSnapshot() }
}
