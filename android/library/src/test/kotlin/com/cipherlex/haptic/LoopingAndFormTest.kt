package com.cipherlex.haptic

import com.cipherlex.haptic.core.HardwareClass
import com.cipherlex.haptic.core.SpecLoader
import com.cipherlex.haptic.core.SpecPaths
import com.cipherlex.haptic.core.TestScheduler
import com.cipherlex.haptic.engine.HardwareClassProbe
import com.cipherlex.haptic.engine.VibratorGateway
import com.cipherlex.haptic.engine.WakeLockGateway
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-09-27 代码审查修复的回归用例（JUnit5 + mockk）：
 * - B1：表达形式由管线逐效果判定，带 sustain 的效果不进 Composition；
 * - A1–A2：looping 每轮有限提交、间隔由引擎表达、时长由应用告知。
 *
 * 平台接缝 [VibratorGateway] 用 mockk 打桩，逐次记录平台调用。
 */
class LoopingAndFormTest {

    /** 一次 `vibrateWaveform` 调用 */
    private data class Wave(val timings: List<Long>, val amplitudes: List<Int>, val repeat: Int)

    private class Rig(sdk: Int, hw: HardwareClass, primitivesSupported: Boolean) {
        val waves = mutableListOf<Wave>()
        var compositions = 0
        /** 置 true 后平台调用抛异常（模拟 DeadObjectException） */
        var failing = false

        val gateway: VibratorGateway = mockk(relaxed = true) {
            every { hasVibrator } returns true
            every { hasAmplitudeControl } returns (hw != HardwareClass.ERM_Z)
            every { sdkInt } returns sdk
            every { areAllPrimitivesSupported(*anyIntArray()) } returns primitivesSupported
            every { vibrateWaveform(any(), any(), any()) } answers {
                if (failing) throw RuntimeException("fake DeadObjectException")
                waves += Wave(firstArg<LongArray>().toList(), secondArg<IntArray>().toList(), thirdArg())
            }
            every { vibrateComposition(any()) } answers {
                if (failing) throw RuntimeException("fake DeadObjectException")
                compositions++
            }
        }
        val scheduler = TestScheduler()
        val haptic = CipherHaptic(
            SpecLoader(SpecPaths.runtimeJson()),
            scheduler,
            gateway,
            mockk<WakeLockGateway>(relaxed = true) { every { shouldHold(any()) } returns false },
            HardwareClassProbe(gateway, override = hw),
            mockk(relaxed = true),
            capacity = 2,
        )
    }

    private fun rig(sdk: Int = 29, hw: HardwareClass = HardwareClass.LINEAR_X_FULL, primitives: Boolean = true) =
        Rig(sdk, hw, primitives)

    // ── B1：表达形式 ─────────────────────────────────────────────────

    @Test
    fun `B1 回归：item-detach 在 API 34 支持原语的机型上走 waveform 而不是 FAIL`() {
        // ticket_rip 含 sustain。此前 facade 在 IR 之后一刀切「支持原语 → Composition」，
        // toComposition 的 require 抛异常 → 每次 FAIL 且泄漏 handle。
        val r = rig(sdk = 34)
        r.haptic.playEffect(CipherHapticSemantic.ITEM_DETACH)
        verify(exactly = 0) { r.gateway.vibrateComposition(any()) }
        assertEquals(1, r.waves.size, "★ 必须真的播出去")
        assertEquals(0, r.haptic.metricsSnapshot().failCount, "不得 FAIL")
    }

    @Test
    fun `全 pulse 的效果在 API 34 支持原语时走 Composition`() {
        val r = rig(sdk = 34)
        r.haptic.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertEquals(1, r.compositions)
        assertTrue(r.waves.isEmpty())
    }

    // ── A2：每轮有限、间隔由引擎表达 ────────────────────────────────

    @Test
    fun `A2 回归：每轮有限提交，间隔由引擎表达`() {
        // 此前 repeat=0 的平台级无限循环与引擎 resubmit 并存：间隔丢失、双重循环。
        val r = rig()
        r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 10_000)
        r.scheduler.advance(520 * 3 + 10)          // 一轮 = 30+60+30 + loopGap 400 = 520ms
        assertEquals(4, r.waves.size, "0 / 520 / 1040 / 1560 各提交一轮")
        assertTrue(r.waves.all { it.repeat == -1 }, "★ 每轮都必须是有限波形（repeat=-1）")
        assertEquals(listOf(30L, 60L, 30L), r.waves.first().timings, "波形只含一轮，不含 gap")
    }

    @Test
    fun `A2 回归：ERM 档告警一声一声，不再持续长震`() {
        // 此前 ERM_Z 的 forced_amplitude 产出 [200]@255 且 repeat=0 —— 一直不停地震。
        val r = rig(hw = HardwareClass.ERM_Z)
        r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 10_000)
        r.scheduler.advance(600 * 2 + 10)          // 一轮 = 200 + gap 400 = 600ms
        assertEquals(List(3) { Wave(listOf(200L), listOf(255), -1) }, r.waves)
    }

    // ── A1：失败路径停得下来 ─────────────────────────────────────────

    @Test
    fun `A1 回归：重提交失败后不会留下停不下来的平台循环`() {
        // 此前重提交失败 → Failed，而上一轮是 repeat=0 的无限循环仍在跑，Failed 又吞掉 CANCEL。
        // 现在每一轮都有限：失败后已提交的那一轮自行结束，也不会再有新的提交。
        val r = rig()
        r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 10_000)
        assertEquals(1, r.waves.size)
        r.failing = true
        r.scheduler.advance(10_530)                // 第二轮重提交失败，之后一直推时间
        assertEquals(1, r.waves.size, "失败后不再提交")
        assertTrue(r.waves.all { it.repeat == -1 }, "★ 已提交的全部是有限波形 —— 平台侧不会一直震")
    }

    // ── 时长由应用告知 ───────────────────────────────────────────────

    @Test
    fun `应用告知的时长超过库上限时按上限截断`() {
        val r = rig()
        r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = Long.MAX_VALUE)
        r.scheduler.advance(CipherHaptic.MAX_LOOP_DURATION_MS - 1_000)
        assertEquals(CipherHapticEngineState.RUNNING, r.haptic.engineState())
        r.scheduler.advance(2_000)
        assertEquals(CipherHapticEngineState.IDLE, r.haptic.engineState(), "★ 超过库上限按上限截断")
    }

    @Test
    fun `looping 时长非法时拒播并记 drop`() {
        val r = rig()
        val delegate = mockk<CipherHapticDebugDelegate>(relaxed = true)
        r.haptic.debugDelegate = delegate
        val token = r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 0)
        assertTrue(r.waves.isEmpty())
        verify(exactly = 1) { delegate.onDropped("security.alarm", "looping-needs-duration") }
        assertTrue(token.isFinished)
        assertFalse(token.isCancelled)
    }
}
