package com.cipherlex.haptic.core

/**
 * 播放决策管线 —— 主文档 B.3 / 工程骨架 §3.3 的落地形态（v1.4.0，2026-09-27）。
 *
 * **纯函数**：给定语义 token、调用选项、上下文快照与活跃句柄快照，输出唯一确定的 [Decision]。
 * facade 只做三件事：取快照 → [decide] → 执行。Python `reference/pipeline.py` 与本类同构，
 * `spec/golden.json` 的 `decisions` 用例双端逐字段对拍。
 *
 * 为什么要从 facade 里抽出来（2026-09-27 代码审查）：此前各步内联在 facade，与可变状态、
 * 指标、句柄表混写，而 API 能力门在 IR 之后由 facade 一刀切判定「支持原语 → Composition」，
 * 于是带 sustain 的 `item.detach` 在 API30+ 机型上每次 FAIL（审查 B1）——正是「IR 之后做决策」。
 * 现在表达形式在第 ⑤ 步**逐效果**判定、随 Decision 下发，engine 只照单翻译。
 */

/** 系统静音态（第 ③ 步输入）。平台层的 `MuteState` 映射到这里，core 不依赖 library 的公开类型。 */
enum class SystemMute { NONE, DND, HARDWARE }

/** 平台能力门快照（第 ⑤ 步输入）。只决定「用哪种表达」，不决定「播多少内容」（那是硬件档的事）。 */
data class ApiGate(val sdkInt: Int, val compositionSupported: Boolean)

/** 表达形式 —— 管线输出之一。engine 按它机械翻译，自己不再判断。 */
enum class ExpressionForm { WAVEFORM, COMPOSITION }

/** 上下文快照：决策所需的全部外部状态，一次性取值，决策期间不变。 */
data class PipelineContext(
    val masterEnabled: Boolean,
    /** P-14 系统触觉总开关（第 ② 步）。⚠️ 平台监听尚未接入（代码审查 C1），facade 暂恒传 true */
    val systemHapticsEnabled: Boolean,
    val mute: SystemMute,
    val globalScale: Float,
    val hardwareClass: HardwareClass,
    val apiGate: ApiGate,
)

/** 调用选项。 */
data class PlayOpts(
    /**
     * 经 `playLoopingEffect` 调用时由**应用告知**的循环时长（ms）；null = 非循环 API。
     * 循环多久是业务决策，库只兜底 [DecisionPipeline.MAX_LOOP_DURATION_MS]（主文档 A.2 接口 3，v1.4.0）。
     */
    val loopMaxDurationMs: Long? = null,
)

sealed interface Decision {
    data class Play(
        val resolved: ResolvedWaveform,
        val form: ExpressionForm,
        val preemptTargets: List<Long>,
        /** 仅 looping：到期发 CANCEL 结束循环。已按库上限截断 */
        val loopDeadlineMs: Long?,
    ) : Decision

    data class Drop(val reason: String) : Decision
}

/** drop 原因词表（每一种都是不同的产品问题，必须分开计数）。 */
object DropReason {
    const val DISABLED = "disabled"
    const val SYSTEM_OFF = "system-off"
    const val DND = "dnd"
    const val HARDWARE_MUTE = "hardware-mute"
    const val DEGRADED_TO_SILENT = "degraded-to-silent"
    /** looping 没走 playLoopingEffect：拿不到停止手段，拒播（2026-08-02 真机事故防线） */
    const val LOOPING_NEEDS_TOKEN = "looping-needs-token"
    /** 应用告知的循环时长 ≤ 0 */
    const val LOOPING_NEEDS_DURATION = "looping-needs-duration"
}

object DecisionPipeline {

    /** 循环效果的库兜底上限：应用告知的时长超过它按它截断。拦的是「忘记取消」，不是正常用法。 */
    const val MAX_LOOP_DURATION_MS = 300_000L

    fun decide(
        semanticId: String,
        opts: PlayOpts,
        ctx: PipelineContext,
        loader: SpecLoader,
        active: List<PreemptionPolicy.ActiveHandleInfo>,
        capacity: Int,
        coalesceWindowMs: Long,
    ): Decision {
        val category = loader.categoryOf(semanticId)                          // ⓪
        if (!ctx.masterEnabled) return Decision.Drop(DropReason.DISABLED)      // ①
        if (!ctx.systemHapticsEnabled) return Decision.Drop(DropReason.SYSTEM_OFF)   // ②
        if (ctx.mute != SystemMute.NONE && category != Category.CRITICAL) {   // ③ critical 绕过
            return Decision.Drop(if (ctx.mute == SystemMute.DND) DropReason.DND else DropReason.HARDWARE_MUTE)
        }
        val rw = loader.resolve(semanticId, ctx.hardwareClass, ctx.globalScale)     // ④⑤
            ?: return Decision.Drop(DropReason.DEGRADED_TO_SILENT)

        var deadline: Long? = null
        if (rw.kind == WaveKind.LOOPING) {
            val asked = opts.loopMaxDurationMs ?: return Decision.Drop(DropReason.LOOPING_NEEDS_TOKEN)
            if (asked <= 0) return Decision.Drop(DropReason.LOOPING_NEEDS_DURATION)
            deadline = minOf(asked, MAX_LOOP_DURATION_MS)
        }

        val targets = PreemptionPolicy.computeTargets(rw.category, active, capacity, coalesceWindowMs).toList()  // ⑥
        return Decision.Play(rw, expressionForm(rw, ctx.apiGate), targets, deadline)
    }

    /**
     * 第 ⑤ 步的 API 能力门，**逐效果**判定（IR 文档 §4.2 路径 A / B）：
     * 只有全 pulse 的效果才能走 Composition；continuous 走分段 waveform。
     */
    fun expressionForm(rw: ResolvedWaveform, gate: ApiGate): ExpressionForm =
        if (gate.compositionSupported && rw.kind != WaveKind.CONTINUOUS && rw.events.isNotEmpty() &&
            rw.events.all { it.kind == EventKind.PULSE }
        ) ExpressionForm.COMPOSITION else ExpressionForm.WAVEFORM
}
