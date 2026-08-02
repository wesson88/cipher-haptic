package com.cipherlex.haptic.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 双端行为等价测试 · Android 半边（工程骨架 §六.6）。
 *
 * 基准是 `spec/golden.json`，由 Python 参考实现产出。**基准不由任一端产出**——
 * 谁产出基准谁就成了事实标准，另一端永远在"对齐它"而它自己的 bug 无人校验，
 * 正是 v1.1.0 手写双端数组的失败模式。参考实现是第三方，其正确性由 CI 规则 6
 * （生成结果与 SSOT 手写镜像 diff）反过来钉住。
 *
 * Swift 侧将来跑**同一个** golden.json，同样逐字段 diff。
 */
class GoldenTest {

    private val loader by lazy { SpecLoader(SpecPaths.runtimeJson()) }

    @Test
    fun `golden 用例逐字段等价`() {
        val cases = JSONObject(SpecPaths.goldenJson()).getJSONArray("cases")
        assertTrue(cases.length() > 0, "golden 用例为空")

        var checked = 0
        var drops = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val sem = c.getString("semantic")
            val hw = HardwareClass.valueOf(c.getString("hardwareClass"))
            val scale = c.getDouble("globalScale").toFloat()
            val label = "$sem × $hw × scale=$scale"

            val rw = loader.resolve(sem, hw, scale)

            if (c.has("drop")) {
                // 降级为 silent：不产出 IR、不创建 handle（IR §3.3③）
                if (rw != null) fail("$label 期望 drop=${c.getString("drop")}，实际产出了 IR")
                drops++
                continue
            }

            if (rw == null) fail("$label 期望产出 IR，实际 drop 了")

            // IR 必须先自洽，再谈与基准是否一致
            val errs = rw.validate()
            assertTrue(errs.isEmpty(), "$label 产出非法 IR：$errs")

            val g = c.getJSONObject("ir")
            assertEquals(g.getString("semanticId"), rw.semanticId, "$label semanticId")
            assertEquals(g.getString("effectId"), rw.effectId, "$label effectId")
            assertEquals(g.getString("category"), rw.category.name.lowercase(), "$label category")
            assertEquals(g.getString("kind"), rw.kind.name.lowercase(), "$label kind")
            assertEquals(g.getInt("totalDurationMs"), rw.totalDurationMs, "$label totalDurationMs")
            assertEquals(g.getInt("loopGapMs"), rw.loopGapMs, "$label loopGapMs")
            assertEquals(g.getBoolean("protected"), rw.protectedFromPreemption, "$label protected")
            assertEquals(
                g.getJSONArray("degradeTrace").toStringList(),
                rw.degradeTrace,
                "$label degradeTrace",
            )

            val ge = g.getJSONArray("events")
            assertEquals(ge.length(), rw.events.size, "$label 事件个数")
            for (j in 0 until ge.length()) {
                val e = ge.getJSONObject(j)
                val a = rw.events[j]
                assertEquals(e.getInt("atMs"), a.atMs, "$label events[$j].atMs")
                assertEquals(e.getInt("durationMs"), a.durationMs, "$label events[$j].durationMs")
                assertEquals(e.getString("kind"), a.kind.name.lowercase(), "$label events[$j].kind")
                assertEquals(
                    e.getDouble("intensity").toFloat(), a.intensity, 1e-5f,
                    "$label events[$j].intensity",
                )
                assertEquals(
                    e.getDouble("sharpness").toFloat(), a.sharpness, 1e-5f,
                    "$label events[$j].sharpness",
                )
            }

            if (g.has("continuous")) {
                val gc = g.getJSONObject("continuous")
                val ac = rw.continuous ?: fail("$label 期望有 continuous 块")
                assertEquals(gc.getInt("maxDurationMs"), ac.maxDurationMs, "$label maxDurationMs")
                assertEquals(gc.getInt("segmentMs"), ac.segmentMs, "$label segmentMs")
                assertEquals(gc.getInt("idleTimeoutMs"), ac.idleTimeoutMs, "$label idleTimeoutMs")
            }
            checked++
        }
        println("golden 等价：$checked 个 IR 用例 + $drops 个 drop 用例全部通过")
    }

    @Test
    fun `Android 波形数组由 IR 生成而非手写`() {
        // 「这段 12 行代码就是"双端时序永不错位"的全部保证」（IR 文档 §四.2）
        val cases = JSONObject(SpecPaths.goldenJson()).getJSONArray("cases")
        var n = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (!c.has("androidWaveform")) continue
            val rw = loader.resolve(
                c.getString("semantic"),
                HardwareClass.valueOf(c.getString("hardwareClass")),
                c.getDouble("globalScale").toFloat(),
            ) ?: continue

            val gen = AndroidTranslator.toWaveform(rw)
            val want = c.getJSONObject("androidWaveform")
            val label = "${c.getString("semantic")} × ${c.getString("hardwareClass")}"
            assertEquals(want.getJSONArray("timings_ms").toIntList(), gen.timings, "$label timings")
            assertEquals(want.getJSONArray("amplitudes").toIntList(), gen.amplitudes, "$label amps")
            assertEquals(want.getInt("repeat"), gen.repeat, "$label repeat")

            // 反向累加：脉冲起点必须与 IR 的 atMs 逐一相等（SSOT §1.1 硬约束）
            assertEquals(
                rw.events.map { it.atMs },
                AndroidTranslator.pulseStarts(gen),
                "$label 双端脉冲起点错位",
            )
            n++
        }
        println("Android 生成器：$n 个用例与基准逐值一致")
    }

    private fun JSONArray.toIntList() = (0 until length()).map { getInt(it) }
    // 不能叫 toList —— 会被 org.json 的同名成员遮蔽
    private fun JSONArray.toStringList() = (0 until length()).map { getString(it) }
}
