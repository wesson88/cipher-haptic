import Foundation

// 播放决策管线 —— 主文档 B.3 / 工程骨架 §3.3 的落地形态（v1.4.0）。镜像 android/core/DecisionPipeline.kt。
//
// **纯函数**：给定语义 token、调用选项、上下文快照与活跃句柄快照，输出唯一确定的 `Decision`。
// facade 只做三件事：取快照 → `decide` → 执行。`spec/golden.json` 的 `decisions` 用例三端逐字段对拍。

/// 系统静音态（第 ③ 步输入）。
public enum SystemMute: Sendable { case none, dnd, hardware }

/// 平台能力门快照（第 ⑤ 步输入）。只决定「用哪种表达」，不决定「播多少内容」。
/// iOS 没有 Composition 这条路径，运行时恒传 false；字段保留是为了与 golden 的 Decision 对拍。
public struct ApiGate: Sendable {
    public let compositionSupported: Bool
    public init(compositionSupported: Bool) { self.compositionSupported = compositionSupported }
}

/// 表达形式 —— 管线输出之一。iOS 一律按 IR 事件翻译成 CHHapticEvent，但仍照算，供三端对拍。
public enum ExpressionForm: String, Sendable { case waveform, composition }

/// 上下文快照：决策所需的全部外部状态，一次性取值，决策期间不变。
public struct PipelineContext: Sendable {
    public let masterEnabled: Bool
    /// P-14 系统触觉总开关（第 ② 步）。⚠️ iOS 无公开 API 可读，facade 暂恒传 true（待拍板）
    public let systemHapticsEnabled: Bool
    public let mute: SystemMute
    public let globalScale: Float
    public let hardwareClass: CipherHapticHardwareClass
    public let apiGate: ApiGate

    public init(masterEnabled: Bool, systemHapticsEnabled: Bool, mute: SystemMute, globalScale: Float,
                hardwareClass: CipherHapticHardwareClass, apiGate: ApiGate) {
        self.masterEnabled = masterEnabled
        self.systemHapticsEnabled = systemHapticsEnabled
        self.mute = mute
        self.globalScale = globalScale
        self.hardwareClass = hardwareClass
        self.apiGate = apiGate
    }
}

/// 调用选项。
public struct PlayOpts: Sendable {
    /// 经 `playLoopingEffect` 调用时由**应用告知**的循环时长（ms）；nil = 非循环 API（主文档 A.2 接口 3，v1.4.0）
    public let loopMaxDurationMs: Int?
    public init(loopMaxDurationMs: Int? = nil) { self.loopMaxDurationMs = loopMaxDurationMs }
}

public enum Decision {
    public struct Play {
        public let resolved: ResolvedWaveform
        public let form: ExpressionForm
        public let preemptTargets: [Int]
        /// 仅 looping：到期发 CANCEL 结束循环。已按库上限截断
        public let loopDeadlineMs: Int?
    }

    case play(Play)
    case drop(String)
}

/// drop 原因词表（每一种都是不同的产品问题，必须分开计数）。与 Kotlin `DropReason` 一致。
public enum DropReason {
    public static let disabled = "disabled"
    public static let systemOff = "system-off"
    public static let dnd = "dnd"
    public static let hardwareMute = "hardware-mute"
    public static let degradedToSilent = "degraded-to-silent"
    /// looping 没走 playLoopingEffect：拿不到停止手段，拒播（2026-08-02 真机事故防线）
    public static let loopingNeedsToken = "looping-needs-token"
    /// 应用告知的循环时长 ≤ 0
    public static let loopingNeedsDuration = "looping-needs-duration"
    /// 内嵌 spec 与代码脱节（CI 应已拦下）。iOS 独有：Kotlin 在此处抛异常，iOS 守「API 绝不抛异常」
    public static let specError = "spec-error"
}

public enum DecisionPipeline {

    /// 循环效果的库兜底上限：应用告知的时长超过它按它截断。拦的是「忘记取消」，不是正常用法。
    public static let maxLoopDurationMs = 300_000

    public static func decide(
        semanticId: String,
        opts: PlayOpts,
        ctx: PipelineContext,
        loader: SpecLoader,
        active: [PreemptionPolicy.ActiveHandleInfo],
        capacity: Int,
        coalesceWindowMs: Int
    ) -> Decision {
        do {
            let category = try loader.categoryOf(semanticId)                          // ⓪
            if !ctx.masterEnabled { return .drop(DropReason.disabled) }               // ①
            if !ctx.systemHapticsEnabled { return .drop(DropReason.systemOff) }       // ②
            if ctx.mute != .none && category != .critical {                           // ③ critical 绕过
                return .drop(ctx.mute == .dnd ? DropReason.dnd : DropReason.hardwareMute)
            }
            guard let rw = try loader.resolve(semanticId, ctx.hardwareClass, ctx.globalScale) else {   // ④⑤
                return .drop(DropReason.degradedToSilent)
            }

            var deadline: Int?
            if rw.kind == .looping {
                guard let asked = opts.loopMaxDurationMs else { return .drop(DropReason.loopingNeedsToken) }
                if asked <= 0 { return .drop(DropReason.loopingNeedsDuration) }
                deadline = min(asked, maxLoopDurationMs)
            }

            let targets = PreemptionPolicy.computeTargets(                             // ⑥
                newCategory: rw.category, active: active, capacity: capacity, coalesceWindowMs: coalesceWindowMs)
            return .play(Decision.Play(resolved: rw, form: expressionForm(rw, ctx.apiGate),
                                       preemptTargets: targets, loopDeadlineMs: deadline))
        } catch {
            return .drop(DropReason.specError)
        }
    }

    /// 第 ⑤ 步的 API 能力门，**逐效果**判定（IR 文档 §4.2 路径 A / B）。
    public static func expressionForm(_ rw: ResolvedWaveform, _ gate: ApiGate) -> ExpressionForm {
        gate.compositionSupported && rw.kind != .continuous && !rw.events.isEmpty &&
            rw.events.allSatisfy { $0.kind == .pulse } ? .composition : .waveform
    }
}
