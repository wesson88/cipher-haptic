import Foundation
import CipherHapticCore

/// 平台播放器句柄 —— 对应 `CHHapticPatternPlayer`（P-11：makePlayer 返回真句柄）。
protocol HapticPlayer: AnyObject {
    func start() throws
    /// 只停本 player（P-03）。绝不影响其他 handle
    func stop()
    func sendParameters(intensityControl: Float, sharpnessControl: Float) throws
}

/// engine 被系统打断（Core Haptics `stoppedHandler` / `resetHandler`）。
enum EngineInterruption: Equatable {
    /// 服务端重置：原有 player 全部失效，须重新 makePlayer（P-16）
    case reset
    /// 被系统停止。`suspended` = 进入后台（`.applicationSuspended`），由生命周期的 SUSPEND 处理
    case stopped(suspended: Bool)
}

/// 平台接缝 —— 与 Android `VibratorGateway` 对位。抽成协议后 facade 全部逻辑能在
/// macOS 主机上用 fake 跑单测，不需要真机。
///
/// 线程：除 `onInterruption` 由系统队列触发外，全部方法只在串行调度队列上调用。
protocol HapticGateway: AnyObject {
    /// `CHHapticEngine.capabilitiesForHardware().supportsHaptics`
    var supportsHaptics: Bool { get }
    /// 能否表达锐度维度（P-04）
    var supportsSharpness: Bool { get }
    /// 幂等。已运行时直接返回
    func startEngine() throws
    /// 运行时收到打断后调用：标记已停；reset 时清空 pattern 缓存（player 已失效）
    func engineDidStop(reset: Bool)
    func makePlayer(events: [IOSEvent]) throws -> HapticPlayer
    func makeContinuousPlayer(event: IOSEvent, intensityControl: Float, sharpnessControl: Float) throws -> HapticPlayer
    /// 预编译 pattern（接口 7）。缓存生命周期跟随 engine
    func prepare(events: [IOSEvent])
    var onInterruption: ((EngineInterruption) -> Void)? { get set }
}

struct GatewayError: Error, CustomStringConvertible {
    let description: String
}

/// 既无 Core Haptics 也无 UIKit 的平台（macOS 主机）：一切提交失败，走 FAIL 路径。
final class NullGateway: HapticGateway {
    let supportsHaptics = false
    let supportsSharpness = false
    var onInterruption: ((EngineInterruption) -> Void)?
    func startEngine() throws {}
    func engineDidStop(reset: Bool) {}
    func makePlayer(events: [IOSEvent]) throws -> HapticPlayer { throw GatewayError(description: "本平台无触觉输出") }
    func makeContinuousPlayer(event: IOSEvent, intensityControl: Float, sharpnessControl: Float) throws -> HapticPlayer {
        throw GatewayError(description: "本平台无触觉输出")
    }
    func prepare(events: [IOSEvent]) {}
}

enum PlatformGateway {
    /// 生产装配：支持 Core Haptics → `CoreHapticsGateway`（LINEAR_X_FULL）；
    /// 否则（iPhone 8 以下、全部 iPad、模拟器）→ UIKit 回退（ERM_Z，B.8）。
    static func make() -> HapticGateway {
        #if canImport(CoreHaptics)
        if CoreHapticsGateway.hardwareSupportsHaptics { return CoreHapticsGateway() }
        #endif
        #if canImport(UIKit) && !os(watchOS) && !os(tvOS)
        return ImpactFallbackGateway()
        #else
        return NullGateway()
        #endif
    }
}

/// 帧时钟 —— 接口 2 `onNextFrame` 的落点。只保证"在下一 VSync 边界提交"，不保证绝对时刻（P-02）。
protocol FrameClock: AnyObject {
    func postFrameCallback(_ task: @escaping () -> Void)
}

/// 无显示链路的平台：主队列下一轮执行。
final class MainQueueFrameClock: FrameClock {
    func postFrameCallback(_ task: @escaping () -> Void) { DispatchQueue.main.async(execute: task) }
}

/// 进程前后台切换 → SUSPEND / RESUME（FSM §四）。
protocol LifecycleSource: AnyObject {
    func start(onSuspend: @escaping () -> Void, onResume: @escaping () -> Void)
}

final class NoLifecycle: LifecycleSource {
    func start(onSuspend: @escaping () -> Void, onResume: @escaping () -> Void) {}
}
