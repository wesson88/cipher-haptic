package com.cipherlex.haptic.core

import org.json.JSONObject

/**
 * SpecLoader —— 【通用同构】层，对应「语义层与中立 IR」§六。
 *
 * 职责：解析 + schema 校验 + 引用完整性 + 语义→效果解析 + 归一化。
 * **不碰任何平台 API**，故双端能共用同一组 golden 用例、能先于真机验证。
 *
 * 内嵌的是 `spec/runtime.min.json` 而非 `bundle.json`：
 *
 * - **不带 `parity`**（20 条双端差异的中文描述，给 CI 与人看的）。把平台差异说明
 *   文档打进 App 二进制是不对的，与体积大小无关。
 * - **不带 `effects.*.android` 镜像**（SSOT 明写"仅供 CI diff，运行时不读"）。
 * - **事件已归一化为 IR 形态**：`{atMs, durationMs, intensity, sharpness, kind}`，
 *   而不是过渡期的 `ios_core_haptics.events[{type, time_ms, …}]`。于是双端 loader
 *   都不必再写 `hapticTransient → pulse` 这类映射（少一处可漂移点），而且等
 *   `effects.yaml` 迁到 v2.0.0 中立格式时，**本类一个字都不用改**——迁移被挡在生成器里。
 *
 * 用 JSON 而非 YAML：JSON 在双端都是标准库（`org.json` / `JSONDecoder`），
 * 而 YAML 1.1 的隐式类型转换会同时坑双端（`transitions` 曾用 `on:` 作键，
 * 被解析成布尔 `true`）。
 */
class SpecLoader(runtimeJson: String) {

    private val root = JSONObject(runtimeJson)
    private val semanticsJson = root.getJSONObject("semantics")
    private val effectsJson = root.getJSONObject("effects")
    private val degradationJson = root.getJSONObject("degradation")

    val semanticIds: List<String> = semanticsJson.keys().asSequence().sorted().toList()

    /** `idleTimeoutMs` 未实测时的兜底（性能 §5.4 待测项），与 Python 参考实现一致。 */
    private val defaultIdleTimeoutMs = 1500

    /** 迁移表。FSM runner 用它构造 [TransitionTable]。 */
    val transitions: JSONObject get() = root.getJSONObject("transitions")

    private fun sem(id: String): JSONObject =
        semanticsJson.optJSONObject(id) ?: error("semantics 无此 token：$id")

    private fun eff(id: String): JSONObject =
        effectsJson.optJSONObject(id) ?: error("effects 无此效果：$id")

    fun categoryOf(semanticId: String): Category = Category.from(sem(semanticId).getString("category"))

    /** 弃用治理：标了 `deprecatedBy` 的 token 自动转发到新 token 并告警（IR §2.5）。 */
    fun resolveAlias(semanticId: String): String {
        val d = sem(semanticId).optString("deprecatedBy", "")
        return if (d.isEmpty()) semanticId else d
    }

    fun effectIdOf(semanticId: String): String = sem(semanticId).getString("effect")

    fun kindOf(effectId: String): WaveKind = WaveKind.from(eff(effectId).getString("kind"))

    fun neutralEvents(effectId: String): List<IrEvent> {
        val arr = eff(effectId).getJSONArray("events")
        val out = ArrayList<IrEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            out += IrEvent(
                atMs = e.getInt("atMs"),
                durationMs = e.getInt("durationMs"),
                intensity = e.getDouble("intensity").toFloat(),
                sharpness = e.getDouble("sharpness").toFloat(),
                kind = if (e.getString("kind") == "pulse") EventKind.PULSE else EventKind.SUSTAIN,
            )
        }
        return out.sortedBy { it.atMs }
    }

    fun continuousOf(effectId: String): ContinuousSpec? {
        val c = eff(effectId).optJSONObject("continuous") ?: return null
        return ContinuousSpec(
            initialIntensity = c.getDouble("initialIntensity").toFloat(),
            initialSharpness = c.getDouble("initialSharpness").toFloat(),
            maxDurationMs = c.getInt("maxDurationMs"),
            segmentMs = c.getInt("segmentMs"),
            idleTimeoutMs = if (c.isNull("idleTimeoutMs")) defaultIdleTimeoutMs
                            else c.getInt("idleTimeoutMs"),
        )
    }

    fun degradeCell(effectId: String, hw: HardwareClass): DegradeCell {
        val row = degradationJson.optJSONObject(effectId)
            ?: error("degradation 缺格：$effectId（CI 规则 8）")
        val c = row.optJSONObject(hw.name)
            ?: error("degradation 缺格：$effectId × ${hw.name}（CI 规则 8）")
        return DegradeCell(
            action = c.getString("action"),
            durationMs = c.opt("durationMs") as? Int,
            amplitudeScale = (c.opt("amplitudeScale") as? Number)?.toFloat(),
            durationScale = (c.opt("durationScale") as? Number)?.toFloat(),
            count = c.opt("count") as? Int,
            intervalMs = c.opt("intervalMs") as? Int,
        )
    }

    /**
     * 管线 ⓪→⑤ 的核心：语义 token + 硬件档 → IR。
     *
     * 求值顺序严格按主文档 B.3：**④ scale 先于 ⑤ degrade**（SSOT §3.1 通则 2），
     * 故 `amplitudeScale` 乘的是**已缩放后**的 intensity。
     *
     * @return `null` = 降级为 silent，管线 drop，**不创建 handle、不进 engine**（IR §3.3③）。
     */
    fun resolve(semanticId: String, hw: HardwareClass, globalScale: Float = 1f): ResolvedWaveform? {
        val id = resolveAlias(semanticId)
        val effectId = effectIdOf(id)

        var events = neutralEvents(effectId)
        if (globalScale != 1f) events = events.map { it.scaled(amp = globalScale) }  // ④

        val cell = degradeCell(effectId, hw)
        val out = Degrade.apply(events, cell, effectId) ?: return null                // ⑤

        val kind = kindOf(effectId)
        val loopGap = eff(effectId).optInt("loopGapMs", 0)
        return ResolvedWaveform(
            semanticId = id,
            effectId = effectId,
            category = categoryOf(id),
            kind = kind,
            totalDurationMs = if (kind == WaveKind.CONTINUOUS) 0
                              else ResolvedWaveform.totalOf(out, loopGap),
            loopGapMs = loopGap,
            events = out,
            degradeTrace = listOf(cell.action),
            protectedFromPreemption = sem(id).optBoolean("protected", false),
            continuous = continuousOf(effectId),
        )
    }

    companion object {
        /** 从内嵌 resources 读取 —— 构建时由 Gradle 从 `spec/runtime.min.json` 拷入。 */
        fun fromResources(): SpecLoader {
            val stream = SpecLoader::class.java.getResourceAsStream("/runtime.min.json")
                ?: error("runtime.min.json 未内嵌 —— 先跑 tools/extract.py，再让构建拷入 resources")
            return SpecLoader(stream.bufferedReader().use { it.readText() })
        }
    }
}
