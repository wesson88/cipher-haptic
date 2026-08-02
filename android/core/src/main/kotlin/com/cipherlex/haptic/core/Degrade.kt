package com.cipherlex.haptic.core

/**
 * 降级变换 —— 对应 SSOT §3.1。
 *
 * 这一步是 [ResolvedWaveform] 的**生产函数**：它不确定，IR 就不确定，而低端机跑的
 * **全部**是它的产物。v1.3.0 之前全库只有 action 的名字、零定义 —— 等于让双端两个
 * 实现者各自发明一套降级算法，正是 IR 要消灭的漂移类别，且发生在 IR **之前**，
 * 「IR 之后禁止决策」的 CI 规则 9 拦不到它。
 *
 * 四条通则：
 *  1. 输入是中立 events，不是双端数组
 *  2. 降级在 globalScale **之后**（管线 ④scale → ⑤degrade），故 amplitudeScale
 *     乘的是已缩放后的 intensity
 *  3. 每个 cell 有且仅有一个 action，不叠加
 *  4. 变换后重算 totalDurationMs，并重新满足 IR 的升序 / 不重叠约束
 */
object Degrade {

    /** 与 Python 参考实现的 `KNOWN_ACTIONS` 一一对应。 */
    val KNOWN = setOf(
        "full", "silent", "forced_amplitude", "simplify",
        "tail_pulse_only", "single_pulse", "n_pulses", "amplitude_only",
    )

    class DegradeException(msg: String) : IllegalStateException(msg)

    /**
     * @return `null` 表示 **silent** —— 不产出 IR、不创建 handle、管线直接 drop
     *   （IR §3.3③）。返回空列表会被误当成"播一个空效果"，那是两回事。
     */
    fun apply(events: List<IrEvent>, cell: DegradeCell, effectId: String): List<IrEvent>? {
        val pulses = { events.filter { it.kind == EventKind.PULSE } }
        return when (cell.action) {
            "full" -> events

            "silent" -> null

            // ERM 无振幅控制，马达只有开/关。保留时序细节不会更好听,只会把设计好的
            // 节奏变成一串噪音 —— 故【丢弃全部原时序】，压成单一满幅脉冲。
            "forced_amplitude" -> {
                val d = cell.durationMs
                    ?: throw DegradeException("$effectId: forced_amplitude 需要正整数 duration_ms")
                if (d <= 0) throw DegradeException("$effectId: duration_ms 必须 > 0")
                listOf(IrEvent(0, d, 1f, 0f, EventKind.PULSE))
            }

            "simplify" -> {
                val amp = cell.amplitudeScale ?: 1f
                val dur = cell.durationScale ?: 1f
                if (amp <= 0f || dur <= 0f)
                    throw DegradeException("$effectId: simplify 的 scale 必须 > 0")
                events.map { it.scaled(amp = amp, dur = dur) }
            }

            // 长效果只留信息量最大的收尾击（ticket_rip 的"绷断"）——
            // 中段阻尼在弱马达上本来就表达不出来。
            "tail_pulse_only" -> {
                val ps = pulses()
                if (ps.isEmpty()) throw DegradeException(
                    "$effectId: tail_pulse_only 要求至少 1 个 pulse 事件，实际 0 个（CI 规则 12）")
                val last = ps.maxBy { it.atMs }
                listOf(last.copy(atMs = 0))
            }

            // 多击压成一击，保留最强的那一下（magnetic_snap 的"咬合"是第二击）
            "single_pulse" -> {
                val ps = pulses()
                if (ps.isEmpty()) throw DegradeException(
                    "$effectId: single_pulse 要求至少 1 个 pulse 事件，实际 0 个（CI 规则 12）")
                val best = ps.maxWith(compareBy({ it.intensity }, { it.atMs }))
                listOf(best.copy(atMs = 0))
            }

            // 脉冲链稀释（anti_screenshot 4 击 → 2 击），保留"断奏"的辨识度
            "n_pulses" -> {
                val n = cell.count ?: throw DegradeException("$effectId: n_pulses 需要 count")
                val gap = cell.intervalMs ?: throw DegradeException("$effectId: n_pulses 需要 interval_ms")
                if (n < 1) throw DegradeException("$effectId: count 必须 ≥ 1")
                if (gap < 0) throw DegradeException("$effectId: interval_ms 必须 ≥ 0")
                val ps = pulses()
                if (ps.size < n) throw DegradeException(
                    "$effectId: n_pulses count=$n 但只有 ${ps.size} 个 pulse 事件（CI 规则 12）")
                // durationMs / intensity / sharpness 各自保持原值,只重排 atMs
                ps.take(n).mapIndexed { i, p -> p.copy(atMs = i * gap) }
            }

            // 只有振幅一个维度的通道或设备（见 P-04）
            "amplitude_only" -> events.map { it.copy(sharpness = 0f) }

            else -> throw DegradeException(
                "$effectId: 未知降级 action '${cell.action}'。合法值见 SSOT §3.1——不猜")
        }
    }
}

/** degradation 矩阵里的一格。 */
data class DegradeCell(
    val action: String,
    val durationMs: Int? = null,
    val amplitudeScale: Float? = null,
    val durationScale: Float? = null,
    val count: Int? = null,
    val intervalMs: Int? = null,
)
