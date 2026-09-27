import Foundation

/// `CHHapticEvent` 的构造参数 —— **纯数据**，不 import CoreHaptics，故能在 macOS 主机上与 golden 对拍。
public struct IOSEvent: Hashable, Sendable {
    public enum EventType: String, Sendable {
        case hapticTransient, hapticContinuous
    }

    public let eventType: EventType
    /// 秒。IR 用绝对时刻（§3.4），直接除 1000
    public let relativeTime: Double
    public let intensity: Float
    public let sharpness: Float
    /// 仅 continuous。transient 不传（P-12）
    public let duration: Double?
}

/// IRTranslator · iOS 半边 —— 对应「语义层与中立 IR」§四.1 与 `reference/translate.py:to_ios_events`。
///
/// **机械翻译，零决策。** 这里出现任何 `hardwareClass` / `category` / `globalScale` 的判断都是
/// 架构违规（CI 规则 9）——那些在 IR 之前就已经算完了。
///
/// looping 每轮只翻译一轮事件：`loopGapMs` 不写进 pattern，由 end-timer 在 `totalDurationMs`
/// 到期后重提交表达（状态机 §4.7 方案 B / P-17）。
public enum IOSTranslator {

    public static func events(_ rw: ResolvedWaveform) -> [IOSEvent] { events(rw.events) }

    public static func events(_ ir: [IrEvent]) -> [IOSEvent] {
        ir.map { e in
            IOSEvent(
                eventType: e.kind == .pulse ? .hapticTransient : .hapticContinuous,
                relativeTime: round6(Double(e.atMs) / 1000),
                intensity: e.intensity,
                sharpness: e.sharpness,
                duration: e.kind == .sustain ? round6(Double(e.durationMs) / 1000) : nil
            )
        }
    }

    // ── 连续通道（IR §3.2b / P-01）──────────────────────────────────
    //
    // ⚑ 平台语义（待 Mac 真机核实，已登记待办）：`hapticIntensityControl` 是**乘子**，
    //   `hapticSharpnessControl` 是**加性偏移**。若以 IR 的基值（0.5 / 0.5）建事件，
    //   update 传绝对值就会算错（0.8 → 0.4）。故连续通道的事件固定以
    //   intensity = 1、sharpness = 0 为基，手指强度全部走 dynamic parameter：
    //   control(intensity) = 目标值，control(sharpness) = 目标值 − 0。
    //   起播值同样作为 pattern 的初始 dynamic parameter 传入，避免"先满强度再降下来"。

    public static let continuousBaseIntensity: Float = 1
    public static let continuousBaseSharpness: Float = 0

    /// 连续通道的单个事件：时长 = `maxDurationMs`（到期由运行时续排，对 FSM 不可见）。
    public static func continuousEvent(maxDurationMs: Int) -> IOSEvent {
        IOSEvent(eventType: .hapticContinuous, relativeTime: 0,
                 intensity: continuousBaseIntensity, sharpness: continuousBaseSharpness,
                 duration: Double(maxDurationMs) / 1000)
    }

    /// 目标强度 / 锐度（0–1）→ dynamic parameter 取值。
    public static func continuousControls(intensity: Float, sharpness: Float) -> (intensity: Float, sharpness: Float) {
        (clamp01(intensity) / continuousBaseIntensity, clamp01(sharpness) - continuousBaseSharpness)
    }

    static func clamp01(_ x: Float) -> Float { min(max(x, 0), 1) }

    /// 与 Python `round(x, 6)` 对齐（对拍时再加容差）
    static func round6(_ x: Double) -> Double { (x * 1_000_000).rounded() / 1_000_000 }
}
