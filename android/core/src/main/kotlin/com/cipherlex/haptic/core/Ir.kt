package com.cipherlex.haptic.core

/**
 * 中立 IR —— 对应「语义层与中立 IR」文档 §3.2 / §3.2b。
 *
 * 这是**通用层与平台层的唯一接缝**。`ResolvedWaveform` 之后不允许再有任何决策：
 * engine 只做机械翻译。一旦 engine 里出现 `if (hardwareClass == …)` / `if (category == …)`，
 * 双端立刻开始漂移，而 `DecisionPipeline` 的"纯逻辑可测"就白做了 —— 真正决定手感的
 * 逻辑跑到了不可测的地方。
 *
 * 本模块（`core`）刻意不依赖任何 Android API，且 `HardwareClass` / `PipelineContext`
 * 等标识符全部 `internal` —— 于是 `library` 模块在**语言层面**就看不见它们。
 */

/** 硬件档。`LINEAR_X_LIMITED` 在 minSdk 29 上不可自动探测，默认不产出（见 P-07）。 */
enum class HardwareClass { ERM_Z, LINEAR_X_LIMITED, LINEAR_X_FULL }

enum class Category { UX, ALERT, CRITICAL;
    companion object {
        fun from(s: String) = when (s) {
            "ux" -> UX; "alert" -> ALERT; "critical" -> CRITICAL
            else -> error("未知 category: $s")
        }
    }
}

enum class WaveKind { ONESHOT, LOOPING, CONTINUOUS;
    companion object {
        fun from(s: String) = when (s) {
            "oneshot" -> ONESHOT; "looping" -> LOOPING; "continuous" -> CONTINUOUS
            else -> error("未知 kind: $s")
        }
    }
}

enum class EventKind { PULSE, SUSTAIN }

/**
 * @property atMs 相对效果起点的**绝对时刻** —— 不是间隔（IR 文档 §3.4）。
 *   间隔表示法要求人脑做累加才能验证对齐，而 v1.1.0 的 3/4 错误率证明人脑会算错。
 * @property durationMs pulse: 物理脉冲时长（iOS 忽略、Android 使用，见 P-12）
 * @property intensity 0.0–1.0，**已**经过 globalScale 与降级处理
 */
data class IrEvent(
    val atMs: Int,
    val durationMs: Int,
    val intensity: Float,
    val sharpness: Float,
    val kind: EventKind,
) {
    fun scaled(amp: Float = 1f, dur: Float = 1f, forceSharpness: Float? = null) = IrEvent(
        atMs = Math.round(atMs * dur),
        durationMs = maxOf(1, Math.round(durationMs * dur)),
        intensity = (intensity * amp).coerceIn(0f, 1f),
        sharpness = forceSharpness ?: sharpness,
        kind = kind,
    )
}

/** 仅 [WaveKind.CONTINUOUS] 有。来源是 effects 的 continuous 块（SSOT v1.3.0）。 */
data class ContinuousSpec(
    val initialIntensity: Float,
    val initialSharpness: Float,
    /** 单次排程上限；到期由运行时**续排**，不是效果结束（IR §3.2b 约束 3） */
    val maxDurationMs: Int,
    /** 分段粒度：有原生连续通道的平台忽略此值 */
    val segmentMs: Int,
    /** 空闲超时 —— 连续通道**唯一**的防泄漏出口（状态机 §七.4） */
    val idleTimeoutMs: Int,
)

data class ResolvedWaveform(
    val semanticId: String,
    val effectId: String,
    val category: Category,
    val kind: WaveKind,
    /** ★ NATURAL_END 定时器的唯一来源。continuous 恒为 0 且不启定时器。 */
    val totalDurationMs: Int,
    val loopGapMs: Int,
    val events: List<IrEvent>,
    val degradeTrace: List<String>,
    val protectedFromPreemption: Boolean,
    val continuous: ContinuousSpec?,
) {
    /** 返回违规清单，空 = 合法。与 Python 参考实现 `ResolvedWaveform.validate()` 一一对应。 */
    fun validate(): List<String> {
        val errs = mutableListOf<String>()
        events.forEachIndexed { i, e ->
            if (e.intensity !in 0f..1f) errs += "events[$i].intensity=${e.intensity} 越界"
            if (e.sharpness !in 0f..1f) errs += "events[$i].sharpness=${e.sharpness} 越界"
            if (e.durationMs <= 0) errs += "events[$i].durationMs=${e.durationMs} 必须 > 0"
            if (e.atMs < 0) errs += "events[$i].atMs=${e.atMs} 必须 ≥ 0"
        }
        val ats = events.map { it.atMs }
        if (ats != ats.sorted()) errs += "events 未按 atMs 升序：$ats"

        val sus = events.filter { it.kind == EventKind.SUSTAIN }.sortedBy { it.atMs }
        sus.zipWithNext { a, b ->
            if (a.atMs + a.durationMs > b.atMs)
                errs += "sustain 区间重叠：[${a.atMs},${a.atMs + a.durationMs}) 与 [${b.atMs},…)"
        }

        if (kind == WaveKind.CONTINUOUS) {
            if (totalDurationMs != 0)
                errs += "kind=continuous 的 totalDurationMs 必须为 0（实际 $totalDurationMs）" +
                    "——否则实现者会拿它当总时长用,拖拽到上限时触觉自己断掉"
            if (continuous == null) errs += "kind=continuous 缺 continuous 块（CI 规则 14）"
        } else {
            if (continuous != null) errs += "非 continuous 的效果不得有 continuous 块"
            val want = (events.maxOfOrNull { it.atMs + it.durationMs } ?: 0) + loopGapMs
            if (totalDurationMs != want)
                errs += "totalDurationMs=$totalDurationMs ≠ max(atMs+durationMs)+loopGapMs=$want"
        }
        return errs
    }

    companion object {
        fun totalOf(events: List<IrEvent>, loopGap: Int = 0) =
            (events.maxOfOrNull { it.atMs + it.durationMs } ?: 0) + loopGap
    }
}
