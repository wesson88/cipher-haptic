package com.cipherlex.haptic.demo

import com.cipherlex.haptic.core.LatencyProbe

/**
 * 分位统计 —— 对应 P0 验证计划 **V5 §5.1**。
 *
 * > 「任何写入文档的性能数字必须同时给出：**① 测量段 ② 测量方法 ③ 分位与样本**
 * >   （p50/p95/p99，多少次，什么机型）。缺一不得写入。」
 *
 * 所以这里不打印平均值 —— **平均值对延迟没有意义**，它会把长尾完全抹平，
 * 而触觉的手感问题恰恰出在长尾（偶尔一次 100ms 的卡顿比稳定的 20ms 更糟）。
 */
class LatencyStats : LatencyProbe {

    private val samples = LinkedHashMap<LatencyProbe.Segment, MutableList<Long>>()

    @Synchronized
    override fun onSample(segment: LatencyProbe.Segment, nanos: Long) {
        samples.getOrPut(segment) { ArrayList() } += nanos
    }

    @Synchronized
    fun reset() = samples.clear()

    @Synchronized
    fun report(): String {
        if (samples.isEmpty()) return "尚无样本 —— 先点几下上面的效果按钮"

        val sb = StringBuilder()
        val decision = pct(LatencyProbe.Segment.DECISION, 50)
        val total = pct(LatencyProbe.Segment.SOFTWARE_TOTAL, 50)

        for (seg in LatencyProbe.Segment.entries) {
            val list = samples[seg] ?: continue
            sb.append(
                "%-16s n=%-5d p50=%.3fms p95=%.3fms p99=%.3fms\n".format(
                    label(seg), list.size,
                    pct(seg, 50) / 1e6, pct(seg, 95) / 1e6, pct(seg, 99) / 1e6,
                )
            )
        }

        // ★ V5 §6.2b 的决策规则：占比决定"是否重新评估 C++ 下沉"
        if (decision > 0 && total > 0) {
            val ratio = decision * 100.0 / total
            sb.append("\n【T0→T1 占软件延迟 %.1f%%】".format(ratio))
            sb.append(
                when {
                    ratio < 5 -> "\n→ <5%：维持纯 Kotlin/Swift，C++ 下沉议题可关闭"
                    ratio < 15 -> "\n→ 5–15%：先做语言层优化（缓存 IR、避免重复解析）后复测"
                    else -> "\n→ ≥15%：若绝对值也 >1ms，才重新评估下沉（须连带评估 SIGSEGV 风险）"
                }
            )
            sb.append("\n⚠️ 这只是软件段占比。端到端还要算上 T2→T3 马达物理 rise time")
            sb.append("\n   （LRA 5–20ms、ERM 20–50ms），那一段【改不了】，会让此比例进一步摊薄。")
        }
        return sb.toString()
    }

    private fun label(s: LatencyProbe.Segment) = when (s) {
        LatencyProbe.Segment.DECISION -> "T0→T1 决策"
        LatencyProbe.Segment.PLATFORM_SUBMIT -> "T1→T2 平台"
        LatencyProbe.Segment.SOFTWARE_TOTAL -> "T0→T2 合计"
    }

    private fun pct(seg: LatencyProbe.Segment, p: Int): Double {
        val list = samples[seg]?.sorted() ?: return 0.0
        if (list.isEmpty()) return 0.0
        val idx = ((list.size - 1) * p / 100.0).toInt().coerceIn(0, list.size - 1)
        return list[idx].toDouble()
    }
}
