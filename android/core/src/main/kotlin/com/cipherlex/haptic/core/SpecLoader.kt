package com.cipherlex.haptic.core

import org.json.JSONObject

/**
 * SpecLoader —— 【通用同构】层，对应「语义层与中立 IR」§六。
 *
 * 职责：解析 + schema 校验 + 引用完整性 + 语义→效果解析 + 归一化。
 * **不碰任何平台 API**，故双端能共用同一组 golden 用例、能先于真机验证。
 *
 * 内嵌产物是 `spec/bundle.json`（不是 yaml）：JSON 在双端都是标准库，
 * 而 YAML 需要第三方解析器，且 YAML 1.1 的隐式类型转换会同时坑双端
 * ——`transitions` 曾用 `on:` 作键，被解析成布尔 `true`。
 */
class SpecLoader(bundleJson: String) {

    private val root = JSONObject(bundleJson)
    private val semanticsJson = root.getJSONObject("semantics")
    private val effectsJson = root.getJSONObject("effects")
    private val degradationJson = root.getJSONObject("degradation")

    val semanticIds: List<String> = semanticsJson.keys().asSequence().sorted().toList()

    /** 未实测时的兜底值（性能 §5.4 待测项），与 Python 参考实现保持一致。 */
    private val defaultIdleTimeoutMs = 1500

    fun categoryOf(semanticId: String): Category =
        Category.from(sem(semanticId).getString("category"))

    fun effectIdOf(semanticId: String): String = sem(semanticId).getString("effect")

    private fun sem(id: String): JSONObject =
        semanticsJson.optJSONObject(id) ?: error("semantics 无此 token：$id")

    private fun eff(id: String): JSONObject =
        effectsJson.optJSONObject(id) ?: error("effects 无此效果：$id")

    fun kindOf(effectId: String): WaveKind =
        WaveKind.from(eff(effectId).optString("kind", "oneshot"))

    /**
     * 中立事件源。SSOT v1.3.0 定死：运行时读 `ios_core_haptics.events`
     * （它已是绝对时刻表示），`android.*` 块是**镜像**、仅供 CI diff，运行时不读。
     */
    fun neutralEvents(effectId: String): List<IrEvent> {
        val arr = eff(effectId).getJSONObject("ios_core_haptics").getJSONArray("events")
        val out = ArrayList<IrEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (!e.has("duration_ms")) error(
                "$effectId.events[$i] 缺 duration_ms —— transient 也必填，" +
                    "它是无瞬时事件概念的平台上的物理时长（SSOT §1.1）")
            out += IrEvent(
                atMs = e.getInt("time_ms"),
                durationMs = e.getInt("duration_ms"),
                intensity = e.getDouble("intensity").toFloat(),
                sharpness = e.getDouble("sharpness").toFloat(),
                kind = if (e.getString("type") == "hapticTransient")
                    EventKind.PULSE else EventKind.SUSTAIN,
            )
        }
        return out.sortedBy { it.atMs }
    }

    fun continuousOf(effectId: String): ContinuousSpec? {
        val c = eff(effectId).optJSONObject("continuous") ?: return null
        return ContinuousSpec(
            initialIntensity = c.getDouble("initial_intensity").toFloat(),
            initialSharpness = c.getDouble("initial_sharpness").toFloat(),
            maxDurationMs = c.getInt("max_duration_ms"),
            segmentMs = c.getInt("segment_ms"),
            idleTimeoutMs = if (c.isNull("idle_timeout_ms")) defaultIdleTimeoutMs
                            else c.getInt("idle_timeout_ms"),
        )
    }

    fun degradeCell(effectId: String, hw: HardwareClass): DegradeCell {
        val row = degradationJson.optJSONObject(effectId)
            ?: error("degradation 缺格：$effectId（CI 规则 8）")
        val c = row.optJSONObject(hw.name)
            ?: error("degradation 缺格：$effectId × ${hw.name}（CI 规则 8）")
        return DegradeCell(
            action = c.getString("action"),
            durationMs = c.opt("duration_ms") as? Int,
            amplitudeScale = (c.opt("amplitude_scale") as? Number)?.toFloat(),
            durationScale = (c.opt("duration_scale") as? Number)?.toFloat(),
            count = c.opt("count") as? Int,
            intervalMs = c.opt("interval_ms") as? Int,
        )
    }

    /**
     * 管线 ⓪→⑤ 的核心：语义 token + 硬件档 → IR。
     *
     * 求值顺序严格按主文档 B.3：**④ scale 先于 ⑤ degrade**（SSOT §3.1 通则 2）。
     *
     * @return `null` = 降级为 silent，管线 drop，不创建 handle。
     */
    fun resolve(semanticId: String, hw: HardwareClass, globalScale: Float = 1f): ResolvedWaveform? {
        val effectId = effectIdOf(semanticId)

        var events = neutralEvents(effectId)
        if (globalScale != 1f) events = events.map { it.scaled(amp = globalScale) }  // ④

        val cell = degradeCell(effectId, hw)
        val out = Degrade.apply(events, cell, effectId) ?: return null                // ⑤

        val kind = kindOf(effectId)
        val loopGap = eff(effectId).optInt("loop_gap_ms", 0)
        return ResolvedWaveform(
            semanticId = semanticId,
            effectId = effectId,
            category = categoryOf(semanticId),
            kind = kind,
            totalDurationMs = if (kind == WaveKind.CONTINUOUS) 0
                              else ResolvedWaveform.totalOf(out, loopGap),
            loopGapMs = loopGap,
            events = out,
            degradeTrace = listOf(cell.action),
            protectedFromPreemption = sem(semanticId).optBoolean("protected", false),
            continuous = continuousOf(effectId),
        )
    }

    companion object {
        /** 从内嵌 resources 读取 —— 构建时由 Gradle 从 `spec/bundle.json` 拷入。 */
        fun fromResources(): SpecLoader {
            val stream = SpecLoader::class.java.getResourceAsStream("/bundle.json")
                ?: error("bundle.json 未内嵌 —— 先跑 tools/extract.py，再让构建拷入 resources")
            return SpecLoader(stream.bufferedReader().use { it.readText() })
        }
    }
}
