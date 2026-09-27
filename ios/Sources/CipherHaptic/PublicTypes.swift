import Foundation
import CipherHapticCore

/// 语义 token。case 集合的权威来源是 `semantics.yaml` 的 key（CI 规则 7：`tools/check.py` 对拍）。
/// Swift 用 lowerCamel（去点、第二段首字母大写），rawValue 即 dotted key（IR §2.3b）。
public enum CipherHapticSemantic: String, CaseIterable, Sendable {
    case itemDissolve = "item.dissolve"
    case itemDetach = "item.detach"
    case selectionSnap = "selection.snap"
    case controlTap = "control.tap"
    case gestureTrack = "gesture.track"
    case notifyMessage = "notify.message"
    case securityAlarm = "security.alarm"
    case securityIntrusion = "security.intrusion"
}

/// 播放前的可用性预览（接口 8）—— 降级闭环的另一半，让上层能补偿。
public struct CipherHapticAvailability: Equatable, Sendable {
    public let willPlay: Bool
    /// 降级 action 名；`full` 时为 nil
    public let degradedTo: String?
    /// drop 原因，词表同 `DropReason`
    public let reason: String?
}

/// D 类差异在契约层的强制出口 —— 字段集 = Parity Ledger 的 D 类条目集。
public struct CipherHapticCapabilities: Equatable, Sendable {
    public let hardwareClass: CipherHapticHardwareClass
    /// P-04：Core Haptics 路径为 true；UIKit 回退（`ERM_Z`）无锐度维度
    public let supportsSharpness: Bool
    /// P-06：**待 V1 真机验证**，当前保守返回 false
    public let supportsBackgroundPlayback: Bool
    /// P-14：系统触感总开关。⚠️ iOS 无公开 API 可读，暂恒为 true（待拍板）
    public let systemHapticsEnabled: Bool
}

public enum CipherHapticEngineState: Sendable {
    /// engine 未创建或未启动
    case idle
    /// engine 运行中
    case running
    /// 被系统停止 / 重置，下次提交时懒重启
    case recovering
    /// 连续启动失败或频繁 reset，冷却期内一律 FAIL（FSM §9.2）
    case circuitOpen
}

public protocol CipherHapticCancelToken: AnyObject {
    func cancel()
    var isCancelled: Bool { get }
    /// 已进入 Completed / Cancelled / Failed / Reclaimed 任一状态（v1.2.0）
    var isFinished: Bool { get }
}

public enum MuteState: Sendable {
    case unmuted, dnd, hardwareMuted
}

public protocol MuteStateObserver: AnyObject {
    func onMuteStateChanged(_ state: MuteState)
}

/// 开发期逐事件出口（主文档 A.6）。回调一律投递到主线程。
public protocol CipherHapticDebugDelegate: AnyObject {
    func hapticEngine(didChangeState state: String)
    func hapticEngine(didDegradeEffect semantic: CipherHapticSemantic, reason: String)
    func hapticEngine(didDropEffect semantic: CipherHapticSemantic, reason: String)
}
