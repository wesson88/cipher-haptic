package com.cipherlex.haptic.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 决策管线（主文档 B.3 / 骨架 §3.3，v1.4.0）—— 纯函数，只断言输入 → Decision。
 * 与 golden 的 Decision 对拍互补：这里覆盖 golden 不带的抢占目标，以及几条关键回归。
 */
class DecisionPipelineTest {
    private val loader = SpecLoader(SpecPaths.runtimeJson())

    private fun ctx(
        master: Boolean = true,
        system: Boolean = true,
        mute: SystemMute = SystemMute.NONE,
        hw: HardwareClass = HardwareClass.LINEAR_X_FULL,
        composition: Boolean = true,
    ) = PipelineContext(master, system, mute, 1f, hw, ApiGate(if (composition) 34 else 29, composition))

    private fun decide(sem: String, c: PipelineContext = ctx(), loop: Long? = null,
                       active: List<PreemptionPolicy.ActiveHandleInfo> = emptyList()) =
        DecisionPipeline.decide(sem, PlayOpts(loop), c, loader, active, 2, 100)

    @Test
    fun `审查 B1 回归：带 sustain 的效果即使支持原语也走 waveform`() {
        // 此前 facade 在 IR 之后一刀切「支持原语 → Composition」，item.detach（ticket_rip 含 sustain）
        // 在 API30+ 机型上 toComposition 的 require 抛异常 → 每次 FAIL。
        val d = assertIs<Decision.Play>(decide("item.detach"))
        assertTrue(d.resolved.events.any { it.kind == EventKind.SUSTAIN }, "前提：ticket_rip 含 sustain")
        assertEquals(ExpressionForm.WAVEFORM, d.form)
    }

    @Test
    fun `全 pulse 且支持原语才走 Composition`() {
        assertEquals(ExpressionForm.COMPOSITION, assertIs<Decision.Play>(decide("item.dissolve")).form)
        assertEquals(ExpressionForm.WAVEFORM,
                     assertIs<Decision.Play>(decide("item.dissolve", ctx(composition = false))).form)
    }

    @Test
    fun `drop 顺序：master → system-off → 静音（critical 绕过）→ silent`() {
        assertEquals(Decision.Drop("disabled"), decide("item.dissolve", ctx(master = false, system = false)))
        assertEquals(Decision.Drop("system-off"), decide("item.dissolve", ctx(system = false, mute = SystemMute.DND)))
        assertEquals(Decision.Drop("dnd"), decide("item.dissolve", ctx(mute = SystemMute.DND)))
        assertEquals(Decision.Drop("hardware-mute"), decide("item.dissolve", ctx(mute = SystemMute.HARDWARE)))
        assertIs<Decision.Play>(decide("security.intrusion", ctx(mute = SystemMute.DND)), "critical 绕过静音")
        assertEquals(Decision.Drop("degraded-to-silent"), decide("control.tap", ctx(hw = HardwareClass.ERM_Z)))
    }

    @Test
    fun `looping 时长由应用告知：缺失拒播，非法拒播，超上限截断`() {
        assertEquals(Decision.Drop("looping-needs-token"), decide("security.alarm"))
        assertEquals(Decision.Drop("looping-needs-duration"), decide("security.alarm", loop = 0))
        assertEquals(2_000L, assertIs<Decision.Play>(decide("security.alarm", loop = 2_000)).loopDeadlineMs)
        assertEquals(DecisionPipeline.MAX_LOOP_DURATION_MS,
                     assertIs<Decision.Play>(decide("security.alarm", loop = Long.MAX_VALUE)).loopDeadlineMs)
        assertEquals(null, assertIs<Decision.Play>(decide("item.dissolve", loop = 2_000)).loopDeadlineMs,
                     "非 looping 效果不带时长")
    }

    @Test
    fun `抢占目标由管线算出，随 Decision 下发`() {
        val active = (1L..2L).map {
            PreemptionPolicy.ActiveHandleInfo(it, Category.UX, WaveKind.ONESHOT, 500, false, "Active")
        }
        val d = assertIs<Decision.Play>(decide("security.intrusion", active = active))
        assertTrue(d.preemptTargets.isNotEmpty(), "critical 满容量时应抢占 UX")
    }
}
