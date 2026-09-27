package com.cipherlex.haptic.core

/**
 * IRTranslator · Android 半边 —— 对应「语义层与中立 IR」§四.2。
 *
 * > 「这段 12 行代码就是"双端时序永不错位"的全部保证。它是纯函数，双端等价测试的头号用例。」
 *
 * **机械翻译，零决策。** 这里出现任何 `hardwareClass` / `category` / `globalScale`
 * 的判断都是架构违规（CI 规则 9）——那些在 IR 之前就已经算完了。
 *
 * 注意本类仍在 `core` 里而不是 `library` 里：它只产出**纯数据**（timings/amplitudes 数组），
 * 不碰 `VibrationEffect`。真正 new 出 `VibrationEffect` 的那一步才在 `library`。
 * 这样切的好处是**生成逻辑能在纯 JVM 单测里跑**，不需要 Android 设备或 Robolectric。
 */
object AndroidTranslator {

    data class Waveform(
        val timings: List<Int>,
        val amplitudes: List<Int>,
        /**
         * `createWaveform(timings, amplitudes, repeat)` 的第三参。**本库恒为 -1**（只提交一轮）：
         * looping 由引擎 end-timer 在 `totalDurationMs`（含 `loopGapMs`）到期后重提交下一轮
         * （状态机 §4.7 方案 B，2026-09-27 定案）。平台级 `repeat=0` 曾与引擎 resubmit 并存，
         * 造成双重循环、gap 丢失、ERM 档持续长震，且进程被冻结时停不下来（代码审查 A1–A3）。
         */
        val repeat: Int = -1,
    )

    data class Primitive(val type: String, val scale: Float, val delayMs: Int)

    /**
     * IR → `createWaveform` 的入参。
     *
     * ⚠️ `timings[i]` 是**该段的持续时长**，不是绝对时间戳。v1.1.0 的四套波形全部
     * 错位就是把这两者搞混了。本函数从**绝对时刻**生成，结构上不可能错。
     */
    fun toWaveform(rw: ResolvedWaveform): Waveform {
        val timings = ArrayList<Int>()
        val amps = ArrayList<Int>()
        var cursor = 0
        for (e in rw.events.sortedBy { it.atMs }) {
            when {
                e.atMs > cursor -> {                       // 静默间隔
                    timings += e.atMs - cursor
                    amps += 0
                }
                e.atMs < cursor -> error(
                    "事件在 t=${e.atMs} 与前一段（结束于 $cursor）重叠——" +
                        "IR 不允许，应在 validate() 阶段就被拦下")
            }
            timings += e.durationMs
            amps += Math.round(e.intensity * 255)          // P-13：量化误差 < 1/255
            cursor = e.atMs + e.durationMs
        }
        return Waveform(timings, amps)
    }

    /**
     * IR → `Composition.addPrimitive` 的入参。
     * 走不走这条路由管线第 ⑤ 步判定（[DecisionPipeline.expressionForm]：全 pulse 且 API≥30 且
     * `areAllPrimitivesSupported`，P-08 / P-15），这里只做翻译。`require` 是最后一道防线：
     * 触发即说明有人绕过了管线。
     */
    fun toComposition(rw: ResolvedWaveform): List<Primitive> {
        require(rw.events.all { it.kind == EventKind.PULSE }) {
            "Composition 表达不了 sustain 段——应退 waveform 路径"
        }
        var prev = 0
        return rw.events.sortedBy { it.atMs }.map { e ->
            val p = Primitive("PRIMITIVE_CLICK", e.intensity, e.atMs - prev)
            prev = e.atMs
            p
        }
    }

    /** 反向累加：分段时长数组 → 脉冲绝对起点。用于与 IR 的 atMs 对拍（IR §4.3）。 */
    fun pulseStarts(w: Waveform): List<Int> {
        val starts = ArrayList<Int>()
        var cursor = 0
        for (i in w.timings.indices) {
            if (w.amplitudes[i] > 0) starts += cursor
            cursor += w.timings[i]
        }
        return starts
    }
}
