#if canImport(UIKit) && !os(watchOS) && !os(tvOS)
import UIKit
import CipherHapticCore

/// `supportsHaptics == false` 时的回退（主文档 B.8，硬件档 `ERM_Z`）。
///
/// ⚠️ IR → UIImpact 的翻译规则文档未定义（已登记待办）。先按机械规则：每个事件在其 atMs
/// 处打一次 `impactOccurred(intensity:)`（iOS 13+）；sustain 只打起点；无锐度维度。
/// `UIFeedbackGenerator` 是主线程 API，故投递到主队列，stop 取消未到点的击打。
final class ImpactFallbackGateway: HapticGateway {
    let supportsHaptics = false
    let supportsSharpness = false
    var onInterruption: ((EngineInterruption) -> Void)?

    func startEngine() throws {}
    func engineDidStop(reset: Bool) {}
    func prepare(events: [IOSEvent]) {}

    func makePlayer(events: [IOSEvent]) throws -> HapticPlayer { ImpactPlayer(events) }

    func makeContinuousPlayer(event: IOSEvent, intensityControl: Float, sharpnessControl: Float) throws -> HapticPlayer {
        ImpactPlayer([IOSEvent(eventType: .hapticTransient, relativeTime: 0, intensity: intensityControl,
                               sharpness: 0, duration: nil)])
    }

    private final class ImpactPlayer: HapticPlayer {
        private let events: [IOSEvent]
        private var items: [DispatchWorkItem] = []
        init(_ events: [IOSEvent]) { self.events = events }

        func start() throws {
            for e in events {
                let intensity = CGFloat(e.intensity)
                let item = DispatchWorkItem {
                    let g = UIImpactFeedbackGenerator(style: .heavy)
                    g.impactOccurred(intensity: intensity)
                }
                items.append(item)
                DispatchQueue.main.asyncAfter(deadline: .now() + e.relativeTime, execute: item)
            }
        }

        func stop() {
            items.forEach { $0.cancel() }
            items.removeAll()
        }

        func sendParameters(intensityControl: Float, sharpnessControl: Float) throws {}
    }
}

/// `CADisplayLink` 一次性帧回调（接口 2）。CADisplayLink 必须挂主 run loop。
final class DisplayLinkFrameClock: NSObject, FrameClock {
    private var pending: [() -> Void] = []
    private var link: CADisplayLink?

    func postFrameCallback(_ task: @escaping () -> Void) {
        DispatchQueue.main.async {
            self.pending.append(task)
            if self.link == nil {
                let l = CADisplayLink(target: self, selector: #selector(self.tick))
                l.add(to: .main, forMode: .common)
                self.link = l
            }
        }
    }

    @objc private func tick() {
        link?.invalidate()
        link = nil
        let tasks = pending
        pending.removeAll()
        tasks.forEach { $0() }
    }
}

/// 进后台 → SUSPEND，回前台 → RESUME（FSM §四）。`willResignActive` 语义未定（P0 §2.4），不映射。
final class UIKitLifecycle: LifecycleSource {
    private var tokens: [NSObjectProtocol] = []

    func start(onSuspend: @escaping () -> Void, onResume: @escaping () -> Void) {
        let nc = NotificationCenter.default
        tokens.append(nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil,
                                     queue: nil) { _ in onSuspend() })
        tokens.append(nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil,
                                     queue: nil) { _ in onResume() })
    }

    deinit { tokens.forEach { NotificationCenter.default.removeObserver($0) } }
}
#endif
