import Foundation

// 中立 IR —— 对应「语义层与中立 IR」文档 §3.2 / §3.2b。镜像 android/core/Ir.kt。
//
// 这是**通用层与平台层的唯一接缝**。`ResolvedWaveform` 之后不允许再有任何决策：
// engine 只做机械翻译（CI 规则 9）。

/// 硬件档。iOS 只有 `supportsHaptics` 一个布尔（P-07），自动探测只产出两档；
/// `linearXLimited` 只能来自配置注入。rawValue 与 golden / Kotlin 的枚举名一致。
public enum CipherHapticHardwareClass: String, CaseIterable, Sendable {
    case ermZ = "ERM_Z"
    case linearXLimited = "LINEAR_X_LIMITED"
    case linearXFull = "LINEAR_X_FULL"
}

/// 语义类别。优先级 ux < alert < critical（主文档 A.3）。
public enum CipherHapticCategory: String, CaseIterable, Sendable {
    case ux, alert, critical

    var rank: Int {
        switch self {
        case .ux: return 0
        case .alert: return 1
        case .critical: return 2
        }
    }
}

public enum WaveKind: String, CaseIterable, Sendable {
    case oneshot, looping, continuous
}

public enum EventKind: String, Sendable {
    case pulse, sustain
}

/// 与 Kotlin `Math.round(Float)` / Python 参考实现一致的舍入：floor(x + 0.5)。
/// **不能**用银行家舍入（SSOT §1.1）。在 Double 上算，避免 Float 加 0.5 的进位误差。
func roundHalfUp(_ x: Float) -> Int {
    Int((Double(x) + 0.5).rounded(.down))
}

public struct IrEvent: Hashable, Sendable {
    /// 相对效果起点的**绝对时刻** —— 不是间隔（IR 文档 §3.4）
    public var atMs: Int
    /// pulse：物理脉冲时长（iOS 忽略、Android 使用，见 P-12）
    public var durationMs: Int
    /// 0.0–1.0，**已**经过 globalScale 与降级处理
    public var intensity: Float
    public var sharpness: Float
    public var kind: EventKind

    public init(atMs: Int, durationMs: Int, intensity: Float, sharpness: Float, kind: EventKind) {
        self.atMs = atMs
        self.durationMs = durationMs
        self.intensity = intensity
        self.sharpness = sharpness
        self.kind = kind
    }

    func scaled(amp: Float = 1, dur: Float = 1, forceSharpness: Float? = nil) -> IrEvent {
        IrEvent(
            atMs: roundHalfUp(Float(atMs) * dur),
            durationMs: max(1, roundHalfUp(Float(durationMs) * dur)),
            intensity: min(max(intensity * amp, 0), 1),
            sharpness: forceSharpness ?? sharpness,
            kind: kind
        )
    }
}

/// 仅 `continuous` 有。来源是 effects 的 continuous 块（SSOT v1.3.0）。
public struct ContinuousSpec: Hashable, Sendable {
    public let initialIntensity: Float
    public let initialSharpness: Float
    /// 单次排程上限；到期由运行时**续排**，不是效果结束（IR §3.2b 约束 3）
    public let maxDurationMs: Int
    /// 分段粒度：有原生连续通道的平台（iOS）忽略此值（P-01）
    public let segmentMs: Int
    /// 空闲超时 —— 连续通道**唯一**的防泄漏出口（状态机 §七.4）
    public let idleTimeoutMs: Int
}

public struct ResolvedWaveform: Sendable {
    public let semanticId: String
    public let effectId: String
    public let category: CipherHapticCategory
    public let kind: WaveKind
    /// ★ NATURAL_END 定时器的唯一来源。continuous 恒为 0 且不启定时器。
    public let totalDurationMs: Int
    public let loopGapMs: Int
    public let events: [IrEvent]
    public let degradeTrace: [String]
    public let protectedFromPreemption: Bool
    public let continuous: ContinuousSpec?

    /// 返回违规清单，空 = 合法。与 Kotlin / Python 的 `validate()` 一一对应。
    public func validate() -> [String] {
        var errs: [String] = []
        for (i, e) in events.enumerated() {
            if !(0...1).contains(e.intensity) { errs.append("events[\(i)].intensity=\(e.intensity) 越界") }
            if !(0...1).contains(e.sharpness) { errs.append("events[\(i)].sharpness=\(e.sharpness) 越界") }
            if e.durationMs <= 0 { errs.append("events[\(i)].durationMs=\(e.durationMs) 必须 > 0") }
            if e.atMs < 0 { errs.append("events[\(i)].atMs=\(e.atMs) 必须 ≥ 0") }
        }
        let ats = events.map(\.atMs)
        if ats != ats.sorted() { errs.append("events 未按 atMs 升序：\(ats)") }

        let sus = events.filter { $0.kind == .sustain }.sorted { $0.atMs < $1.atMs }
        for (a, b) in zip(sus, sus.dropFirst()) where a.atMs + a.durationMs > b.atMs {
            errs.append("sustain 区间重叠：[\(a.atMs),\(a.atMs + a.durationMs)) 与 [\(b.atMs),…)")
        }

        if kind == .continuous {
            if totalDurationMs != 0 { errs.append("kind=continuous 的 totalDurationMs 必须为 0（实际 \(totalDurationMs)）") }
            if continuous == nil { errs.append("kind=continuous 缺 continuous 块（CI 规则 14）") }
        } else {
            if continuous != nil { errs.append("非 continuous 的效果不得有 continuous 块") }
            let want = Self.totalOf(events, loopGap: loopGapMs)
            if totalDurationMs != want {
                errs.append("totalDurationMs=\(totalDurationMs) ≠ max(atMs+durationMs)+loopGapMs=\(want)")
            }
        }
        return errs
    }

    static func totalOf(_ events: [IrEvent], loopGap: Int = 0) -> Int {
        (events.map { $0.atMs + $0.durationMs }.max() ?? 0) + loopGap
    }
}
