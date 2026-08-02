package com.cipherlex.haptic

import com.cipherlex.haptic.core.HardwareClass
import com.cipherlex.haptic.core.ResolvedWaveform
import com.cipherlex.haptic.core.SpecLoader
import com.cipherlex.haptic.core.SpecPaths
import com.cipherlex.haptic.core.TestScheduler
import com.cipherlex.haptic.engine.FakeGateway
import com.cipherlex.haptic.engine.HardwareClassProbe
import com.cipherlex.haptic.engine.WakeLockGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class NoWakeLock : WakeLockGateway {
    override fun shouldHold(resolved: ResolvedWaveform) = false
    override fun acquire() = Unit
    override fun release() = Unit
}

/** 手动触发的帧时钟 —— 让 `onNextFrame` 的"下一帧提交"语义可断言。 */
private class ManualFrameClock : FrameClock {
    private val pending = mutableListOf<() -> Unit>()
    var postCount = 0
        private set

    override fun postFrameCallback(task: () -> Unit) {
        postCount++
        pending += task
    }

    fun fireFrame() {
        val batch = pending.toList()
        pending.clear()
        batch.forEach { it() }
    }
}

private class RecordingDelegate : CipherHapticDebugDelegate {
    val drops = mutableListOf<Pair<String, String>>()
    val degrades = mutableListOf<Pair<String, String>>()
    override fun onStateChanged(state: String) = Unit
    override fun onDegraded(semantic: String, action: String) { degrades += semantic to action }
    override fun onDropped(semantic: String, reason: String) { drops += semantic to reason }
}

/**
 * facade 的管线与契约断言（主文档 A.2 / B.3）。
 *
 * 全部在 JVM 上跑 —— 平台调用被 [FakeGateway] / [ManualFrameClock] 挡住，
 * 时间被 [TestScheduler] 的假时钟接管。
 */
class CipherHapticTest {

    private fun build(
        hw: HardwareClass = HardwareClass.LINEAR_X_FULL,
        // 默认 API 29：无 Composition，走 waveform 路径 —— 断言落在 timings/amplitudes 上。
        // Composition 路径由 `API 30+ 且原语受支持时走 Composition` 单独覆盖。
        gw: FakeGateway = FakeGateway(sdkInt = 29),
        sched: TestScheduler = TestScheduler(),
        frame: ManualFrameClock = ManualFrameClock(),
        capacity: Int = 2,
    ): Quad {
        val loader = SpecLoader(SpecPaths.runtimeJson())
        val probe = HardwareClassProbe(gw, override = hw)
        val h = CipherHaptic(loader, sched, gw, NoWakeLock(), probe, frame, capacity)
        return Quad(h, gw, sched, frame)
    }

    private data class Quad(
        val haptic: CipherHaptic,
        val gateway: FakeGateway,
        val scheduler: TestScheduler,
        val frame: ManualFrameClock,
    )

    // ── 管线拦截顺序（B.3）──────────────────────────────────────────

    @Test
    fun `master 关闭时直接 drop,不碰平台`() {
        val (h, gw) = build()
        val d = RecordingDelegate().also { h.debugDelegate = it }
        h.setHapticsEnabled(false)
        h.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertTrue(gw.waveformCalls.isEmpty(), "① master 拦截应发生在平台调用之前")
        assertEquals("disabled", d.drops.single().second)
    }

    @Test
    fun `silent 降级不创建 handle,也不碰平台`() {
        // IR §3.3③：降级结果为 silent 时管线直接 drop —— 不建 handle、不进 engine
        val (h, gw) = build(hw = HardwareClass.ERM_Z)
        val d = RecordingDelegate().also { h.debugDelegate = it }
        h.playEffect(CipherHapticSemantic.CONTROL_TAP)   // ERM_Z × control.tap = silent
        assertTrue(gw.waveformCalls.isEmpty())
        assertEquals("degraded-to-silent", d.drops.single().second)
        assertEquals(CipherHapticEngineState.IDLE, h.engineState(), "不该留下 handle")
    }

    @Test
    fun `降级不是 full 时通知 debugDelegate`() {
        val (h, _) = build(hw = HardwareClass.ERM_Z)
        val d = RecordingDelegate().also { h.debugDelegate = it }
        h.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)  // ERM_Z → forced_amplitude
        assertEquals("forced_amplitude", d.degrades.single().second)
    }

    @Test
    fun `globalScale 缩放 intensity —— 但双端不等价,见 P-04`() {
        val (h, gw) = build()
        h.setGlobalScale(0.5f)
        assertEquals(0.5f, h.globalScale())
        h.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        val amps = gw.waveformCalls.single().second.filter { it > 0 }
        // 原始 0.80/0.60/0.95 × 0.5 × 255（半数进一）
        assertEquals(listOf(102, 77, 121), amps)
    }

    // ── 接口 2：onNextFrame（P-02）───────────────────────────────────

    @Test
    fun `onNextFrame 为 false 时立即提交,不经帧回调`() {
        val (h, gw, _, frame) = build()
        h.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertEquals(0, frame.postCount)
        assertEquals(1, gw.waveformCalls.size)
    }

    @Test
    fun `onNextFrame 为 true 时等到下一帧才提交`() {
        val (h, gw, _, frame) = build()
        h.playEffect(CipherHapticSemantic.ITEM_DISSOLVE, onNextFrame = true)
        assertEquals(1, frame.postCount, "应排进帧回调")
        assertTrue(gw.waveformCalls.isEmpty(), "★ 帧未到来时不得提交硬件命令")

        frame.fireFrame()
        assertEquals(1, gw.waveformCalls.size, "帧到来后才提交")
    }

    // ── 连续通道：全局单例（P-20）───────────────────────────────────

    @Test
    fun `API 30+ 且原语受支持时走 Composition,否则退 waveform`() {
        // SSOT §1.2：API 能力门先于硬件档求值。只查 API level 会在部分机型静默失败（P-08）
        val (h30, gw30) = build(gw = FakeGateway(sdkInt = 34))
        h30.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertEquals(1, gw30.compositionCalls.size, "API 34 + 原语受支持 → Composition")
        assertTrue(gw30.waveformCalls.isEmpty())

        val (h29, gw29) = build(gw = FakeGateway(sdkInt = 29))
        h29.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertTrue(gw29.compositionCalls.isEmpty(), "API 29 无 Composition")
        assertEquals(1, gw29.waveformCalls.size, "应退到 waveform")

        val (hNoPrim, gwNoPrim) = build(
            gw = FakeGateway(sdkInt = 34, supportedPrimitives = emptySet())
        )
        hNoPrim.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertTrue(gwNoPrim.compositionCalls.isEmpty(),
                   "★ API 34 但机型不支持原语 → 必须退 waveform，否则静默失败（P-08）")
        assertEquals(1, gwNoPrim.waveformCalls.size)
    }

    @Test
    fun `连续通道是全局单例 —— 第二路覆盖第一路`() {
        // P-20 是登记在案的显式取舍：同一时刻只支持一路连续手势。
        // 业务方若有多点场景必须自行仲裁 —— 契约写明了，不是遗漏。
        val (h, gw, sched) = build()
        h.updateContinuousEffect(0.3f, 0f)     // 手指 A —— 起播
        val afterStart = gw.waveformCalls.size
        assertEquals(1, afterStart, "起播应送一条平台命令")

        h.updateContinuousEffect(0.9f, 0f)     // 手指 B —— 覆盖 A，【不新建通道】
        assertEquals(CipherHapticEngineState.RUNNING, h.engineState())
        assertEquals(afterStart, gw.waveformCalls.size,
                     "16ms 节流窗口内不该立刻再发 —— 起播那一发已登记（markSentAt）")

        sched.advance(20)                      // 推过节流窗口，补发块到期
        assertEquals(afterStart + 1, gw.waveformCalls.size, "补发块应送出手指 B 的值")
        val amp = gw.waveformCalls.last().second.first()
        assertEquals(230, amp, "★ 送的是手指 B 的 0.9（=230），A 的 0.3 被覆盖 —— 这就是 P-20")
    }

    @Test
    fun `endContinuousEffect 先 flush 未决值再结束`() {
        val (h, gw, sched) = build()
        h.updateContinuousEffect(0.3f, 0f)
        sched.advance(2)
        h.updateContinuousEffect(0.8f, 0f)     // 落在 16ms 窗口内，未决
        val before = gw.waveformCalls.size

        h.endContinuousEffect()
        assertTrue(gw.waveformCalls.size > before, "★ 结束前必须 flush，否则最后一次 update 丢失")

        // 此刻仍有一个 grace 定时器在排 —— 那是【正确行为】：播完/取消后要留时间让
        // 马达制动收尾（§五.2），不能立即释放。推过 grace 之后才该清空。
        sched.advance(1000)
        assertEquals(0, sched.pendingTimers(), "grace 到期后不得残留任何定时器")
        assertEquals(CipherHapticEngineState.IDLE, h.engineState(), "grace 到期后应完成回收")
    }

    // ── 抢占与容量（§八）────────────────────────────────────────────

    @Test
    fun `stopAllEffects 清空全部活跃 handle`() {
        val (h, _, sched) = build(capacity = 8)
        h.playEffect(CipherHapticSemantic.NOTIFY_MESSAGE)
        h.playEffect(CipherHapticSemantic.SECURITY_INTRUSION)
        assertEquals(CipherHapticEngineState.RUNNING, h.engineState())

        h.stopAllEffects()
        sched.advance(1000)                    // 推过 grace 窗口
        assertEquals(CipherHapticEngineState.IDLE, h.engineState())
    }

    @Test
    fun `setHapticsEnabled false 会停掉正在播的`() {
        val (h, _, sched) = build(capacity = 8)
        h.playEffect(CipherHapticSemantic.NOTIFY_MESSAGE)
        h.setHapticsEnabled(false)
        sched.advance(1000)
        assertEquals(CipherHapticEngineState.IDLE, h.engineState())
        assertFalse(h.isHapticsEnabled())
    }

    // ── 契约（A.2 / A.4）───────────────────────────────────────────

    @Test
    fun `preview 在 silent 档告知不会播 —— 降级闭环的另一半`() {
        val (h, _) = build(hw = HardwareClass.ERM_Z)
        val a = h.preview(CipherHapticSemantic.CONTROL_TAP)
        assertFalse(a.willPlay)
        assertEquals("silent", a.degradedTo)
        assertEquals("degraded-to-silent", a.reason)
    }

    @Test
    fun `preview 在满血档告知会播且无降级`() {
        val (h, _) = build()
        val a = h.preview(CipherHapticSemantic.ITEM_DISSOLVE)
        assertTrue(a.willPlay)
        assertEquals(null, a.degradedTo)
    }

    @Test
    fun `hardwareCapabilities 承载 D 类差异`() {
        val (h, _) = build()
        val c = h.hardwareCapabilities()
        assertFalse(c.supportsSharpness, "P-04：Android 恒为 false")
        assertFalse(c.supportsBackgroundPlayback, "P-06：待 V1 验证前保守返回 false")
    }

    @Test
    fun `CancelToken 能区分还在播与已播完`() {
        val (h, _, sched) = build()
        val token = h.playLoopingEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertFalse(token.isCancelled)
        assertFalse(token.isFinished, "刚起播不该是 finished")

        token.cancel()
        assertTrue(token.isCancelled)
        sched.advance(1000)
        assertTrue(token.isFinished, "★ 取消后应可查到已终结（v1.1.0 缺 isFinished）")
    }

    @Test
    fun `自然播完的效果必须自行回收 —— 不靠任何人手动发 GRACE_EXPIRED`() {
        // ⚠️ 这条是真机压测抓出来的泄漏的回归测试。
        //    此前所有测试都【手动发 GRACE_EXPIRED】，于是"没人排 grace 定时器"这件事
        //    被完全掩盖：facade 用 fsm.onStateEntered 做 retire，把 PlaybackHandle
        //    排 grace 的那个回调顶掉了，自然播完的效果永远停在 Completed。
        //    本测试只推进时间，【不发任何事件】—— 这才是真实运行时的样子。
        val (h, _, sched) = build(capacity = 8)
        h.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertEquals(CipherHapticEngineState.RUNNING, h.engineState())

        sched.advance(5_000)          // 只推时间：end-timer → NATURAL_END → grace → Reclaimed
        assertEquals(CipherHapticEngineState.IDLE, h.engineState(),
                     "★ 自然播完后必须自行回收，否则 activeHandles 只增不减")
        assertEquals(0, sched.pendingTimers(), "回收后不得残留定时器")
    }

    @Test
    fun `观察者可注销 —— 否则必然泄漏`() {
        val (h, _) = build()
        val obs = object : MuteStateObserver {
            override fun onMuteStateChanged(state: MuteState) = Unit
        }
        h.registerMuteObserver(obs)
        h.unregisterMuteObserver(obs)          // v1.2.0 补的能力，不崩即通过
    }

    @Test
    fun `无振动器时不崩溃,只是不播`() {
        val (h, gw) = build(gw = FakeGateway(hasVibrator = false))
        h.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)   // A.1 约束 3：绝不抛异常
        assertTrue(h.preview(CipherHapticSemantic.ITEM_DISSOLVE).willPlay.not() ||
                   gw.waveformCalls.isNotEmpty())
    }
}
