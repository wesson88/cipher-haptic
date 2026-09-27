package com.cipherlex.haptic

import com.cipherlex.haptic.core.HardwareClass
import com.cipherlex.haptic.core.ResolvedWaveform
import com.cipherlex.haptic.core.SpecLoader
import com.cipherlex.haptic.core.SpecPaths
import com.cipherlex.haptic.core.TestScheduler
import com.cipherlex.haptic.core.WaveKind
import com.cipherlex.haptic.engine.HardwareClassProbe
import com.cipherlex.haptic.engine.PlaybackHandle
import com.cipherlex.haptic.engine.VibratorGateway
import com.cipherlex.haptic.engine.WakeLockGateway
import com.cipherlex.haptic.engine.WakeLockRefCounter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-09-27 代码审查的 Android 回移植（JUnit5 + mockk）：
 * B2 Failed 回收 / B3 连续通道平台异常 / B4 wake lock 引用计数 / B5 只停本 handle /
 * A4 stop 清补发块 / report 停马达 / C4 回调投递与 token / failCount 单计 / preview 走管线。
 *
 * 只推进时间，不手动发 GRACE_EXPIRED / EXPIRE（真机测试流程 §四：手动补发的事件会掩盖"没人产生这个事件"）。
 */
class ReviewFixesTest {

    private class Rig(
        hw: HardwareClass = HardwareClass.LINEAR_X_FULL,
        wakeLock: WakeLockGateway = mockk(relaxed = true) { every { shouldHold(any()) } returns false },
        callbackExecutor: (() -> Unit) -> Unit = { it() },
    ) {
        /** 每次 `vibrateWaveform` 的振幅（连续通道一段一个值） */
        val amps = mutableListOf<Int>()
        /** 置 true 后平台调用抛异常（模拟 DeadObjectException） */
        var failing = false

        val gateway: VibratorGateway = mockk(relaxed = true) {
            every { hasVibrator } returns true
            every { hasAmplitudeControl } returns (hw != HardwareClass.ERM_Z)
            every { sdkInt } returns 29
            every { vibrateWaveform(any(), any(), any()) } answers {
                if (failing) throw RuntimeException("fake DeadObjectException")
                amps += secondArg<IntArray>().first()
            }
        }
        val scheduler = TestScheduler()
        val delegate = mockk<CipherHapticDebugDelegate>(relaxed = true)
        val haptic = CipherHaptic(
            SpecLoader(SpecPaths.runtimeJson()), scheduler, gateway, wakeLock,
            HardwareClassProbe(gateway, override = hw), mockk(relaxed = true), capacity = 2,
            callbackExecutor = callbackExecutor,
        ).also { it.debugDelegate = delegate }
    }

    // ── B2：Failed 必须被回收 ────────────────────────────────────────

    @Test
    fun `B2 回归：首次提交失败的 handle 由 EXPIRE 回收，失败只计一次`() {
        val r = Rig()
        r.failing = true
        r.haptic.playEffect(CipherHapticSemantic.ITEM_DISSOLVE)
        assertEquals(CipherHapticEngineState.RUNNING, r.haptic.engineState(), "前提：Failed 仍在表里")
        r.scheduler.advance(PlaybackHandle.GRACE_MS)
        assertEquals(CipherHapticEngineState.IDLE, r.haptic.engineState(), "★ 此前没有任何 EXPIRE 发送方，永不回收")
        val m = r.haptic.metricsSnapshot()
        assertEquals(1, m.failCount, "★ 此前 facade 与 report 各记一次")
        assertEquals(0, m.leakSuspectCount)
        assertEquals(0, r.scheduler.pendingTimers())
    }

    @Test
    fun `首次提交失败时 token 立即可查到已终结`() {
        val r = Rig()
        r.failing = true
        val token = r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 10_000)
        assertTrue(token.isFinished, "token 在 SUBMIT 之前绑定，Failed 的终态通知不会丢")
        assertFalse(token.isCancelled)
    }

    // ── B5：只停本 handle 的振动 ─────────────────────────────────────

    @Test
    fun `B5 回归：取消已被后来者替换的效果时不得全局 cancel`() {
        // vibrate() 会替换本 app 正在播的振动；此时马达上是 notify，cancel 只会误停它
        val r = Rig()
        val token = r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 10_000)
        r.haptic.playEffect(CipherHapticSemantic.NOTIFY_MESSAGE)
        token.cancel()
        verify(exactly = 0) { r.gateway.cancelAll() }
        assertTrue(token.isCancelled)
    }

    @Test
    fun `B5：取消马达上正在播的那个效果时照常停马达`() {
        val r = Rig()
        r.haptic.playEffect(CipherHapticSemantic.NOTIFY_MESSAGE)
        val token = r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 10_000)
        token.cancel()
        verify(exactly = 1) { r.gateway.cancelAll() }
    }

    @Test
    fun `B5：抢占时先停旧振动、再提交新振动 —— 新效果不被误停`() {
        val r = Rig()
        r.haptic.playEffect(CipherHapticSemantic.ITEM_DETACH)          // 230ms
        r.scheduler.advance(150)                                          // 过了连点合并窗口
        r.haptic.playEffect(CipherHapticSemantic.ITEM_DETACH)          // 同级 FIFO 抢占旧的
        assertEquals(1, r.haptic.metricsSnapshot().preemptedCount)
        verifyOrder {
            r.gateway.vibrateWaveform(any(), any(), any())
            r.gateway.cancelAll()
            r.gateway.vibrateWaveform(any(), any(), any())
        }
        verify(exactly = 1) { r.gateway.cancelAll() }
        r.scheduler.advance(50)                                           // 旧的 grace 到期回收
        verify(exactly = 1) { r.gateway.cancelAll() }                    // 回收不再碰马达
    }

    // ── report 停马达 ────────────────────────────────────────────────

    @Test
    fun `looping 重提交失败时停掉上一轮仍在播的振动`() {
        val r = Rig()
        r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 10_000)
        r.failing = true
        r.scheduler.advance(530)                                          // 第二轮重提交失败
        verify(exactly = 1) { r.gateway.cancelAll() }
        r.scheduler.advance(100)
        assertEquals(CipherHapticEngineState.IDLE, r.haptic.engineState())
    }

    // ── A4：stop 清补发块 ────────────────────────────────────────────

    @Test
    fun `A4 回归：CANCEL 之后连续通道的尾部补发不再起振`() {
        val r = Rig()
        r.haptic.updateContinuousEffect(0.3f, 0.5f)                       // 起播 = 第 1 段
        r.scheduler.advance(5)
        r.haptic.updateContinuousEffect(0.8f, 0.5f)                       // 16ms 窗口内 → 排尾部补发
        r.haptic.stopAllEffects()
        r.scheduler.advance(200)
        assertEquals(1, r.amps.size, "★ 此前 stop 不取消补发块，CANCEL 之后还会再振一段")
        assertEquals(CipherHapticEngineState.IDLE, r.haptic.engineState())
        assertEquals(0, r.scheduler.pendingTimers())
    }

    // ── B3：连续通道的平台异常 ───────────────────────────────────────

    @Test
    fun `B3 回归：update 立即发送时平台抛错不外泄，转 FAIL 并回收`() {
        val r = Rig()
        r.haptic.updateContinuousEffect(0.3f, 0.5f)
        r.scheduler.advance(20)
        r.failing = true
        assertDoesNotThrow { r.haptic.updateContinuousEffect(0.7f, 0.5f) }   // 距上次 ≥16ms → 立即发送
        r.scheduler.advance(100)
        assertEquals(CipherHapticEngineState.IDLE, r.haptic.engineState(), "失败的通道被 EXPIRE 回收")
        assertEquals(1, r.haptic.metricsSnapshot().failCount)
        r.failing = false
        r.haptic.updateContinuousEffect(0.4f, 0.5f)
        assertEquals(CipherHapticEngineState.RUNNING, r.haptic.engineState(), "下一次手势重新起播")
    }

    @Test
    fun `B3 回归：尾部补发在定时器里抛错不外泄`() {
        val r = Rig()
        r.haptic.updateContinuousEffect(0.3f, 0.5f)
        r.scheduler.advance(5)
        r.haptic.updateContinuousEffect(0.8f, 0.5f)                       // 排到 16ms 的补发块
        r.failing = true
        assertDoesNotThrow { r.scheduler.advance(200) }                   // 此前在 HandlerThread 上抛出 → 宿主崩溃
        assertEquals(CipherHapticEngineState.IDLE, r.haptic.engineState())
        assertEquals(0, r.haptic.metricsSnapshot().leakSuspectCount)
    }

    @Test
    fun `B3 回归：endContinuous 的 flush 抛错不外泄`() {
        val r = Rig()
        r.haptic.updateContinuousEffect(0.3f, 0.5f)
        r.scheduler.advance(5)
        r.haptic.updateContinuousEffect(0.8f, 0.5f)
        r.failing = true
        assertDoesNotThrow { r.haptic.endContinuousEffect() }
        r.scheduler.advance(200)
        assertEquals(CipherHapticEngineState.IDLE, r.haptic.engineState())
    }

    // ── B4：wake lock 按 handle 计数 ─────────────────────────────────

    @Test
    fun `B4 回归：一个 handle 回收不会放掉别的 handle 的锁`() {
        val target = mockk<WakeLockRefCounter.Target>(relaxed = true)
        val counter = WakeLockRefCounter(target) { 0L }
        val wl = object : WakeLockGateway {
            override fun shouldHold(resolved: ResolvedWaveform) = true
            override fun acquire(timeoutMs: Long) = counter.acquire(timeoutMs)
            override fun release() = counter.release()
        }
        val r = Rig(wakeLock = wl)
        val token = r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 60_000)
        r.haptic.playEffect(CipherHapticSemantic.NOTIFY_MESSAGE)          // 145ms 后自然结束并回收
        assertEquals(2, counter.holders)
        r.scheduler.advance(1_000)
        assertEquals(1, counter.holders)
        verify(exactly = 0) { target.drop() }                             // ★ 此前这里就把 looping 的锁放了
        token.cancel()
        r.scheduler.advance(200)
        assertEquals(0, counter.holders)
        verify(exactly = 1) { target.drop() }
    }

    @Test
    fun `B4 回归：looping 的兜底超时覆盖应用告知的时长，重提交不重复获取`() {
        val timeout = slot<Long>()
        val wl = mockk<WakeLockGateway>(relaxed = true) {
            every { shouldHold(any()) } returns true
            every { acquire(capture(timeout)) } returns Unit
        }
        val r = Rig(wakeLock = wl)
        val token = r.haptic.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM, maxDurationMs = 120_000)
        r.scheduler.advance(520 * 5)
        verify(exactly = 1) { wl.acquire(any()) }
        assertTrue(timeout.captured >= 120_000, "★ 此前固定 60s，比 looping 上限还短：${timeout.captured}")
        token.cancel()
        r.scheduler.advance(200)
        verify(exactly = 1) { wl.release() }
    }

    @Test
    fun `WakeLockRefCounter：超时取所有持有者的最晚时刻`() {
        val target = mockk<WakeLockRefCounter.Target>(relaxed = true)
        var now = 0L
        val c = WakeLockRefCounter(target) { now }
        c.acquire(10_000)
        now = 1_000
        c.acquire(2_000)
        verify { target.hold(10_000) }
        verify { target.hold(9_000) }                                     // 不被短的那个缩短成 2_000
        verify(exactly = 0) { target.hold(2_000) }
    }

    @Test
    fun `WakeLockRefCounter：多余的 release 不扣成负数`() {
        val target = mockk<WakeLockRefCounter.Target>(relaxed = true)
        val c = WakeLockRefCounter(target) { 0L }
        c.release()
        verify(exactly = 0) { target.drop() }
        c.acquire(1_000)
        c.release()
        c.release()
        verify(exactly = 1) { target.drop() }
        assertEquals(0, c.holders)
    }

    // ── C4：回调投递 ─────────────────────────────────────────────────

    @Test
    fun `C4：回调经 callbackExecutor 投递，不在调度线程上直接调宿主代码`() {
        val queued = mutableListOf<() -> Unit>()
        val r = Rig(callbackExecutor = { queued += it })
        r.haptic.playEffect(CipherHapticSemantic.SECURITY_ALARM)          // looping-needs-token → drop
        verify(exactly = 0) { r.delegate.onDropped(any(), any()) }
        queued.toList().forEach { it() }
        verify(exactly = 1) { r.delegate.onDropped("security.alarm", "looping-needs-token") }
    }

    // ── preview 走管线 ───────────────────────────────────────────────

    @Test
    fun `preview 与播放同一口径`() {
        val r = Rig()
        assertTrue(r.haptic.preview(CipherHapticSemantic.SECURITY_ALARM).willPlay, "looping 按经 token 调用预览")
        r.haptic.setHapticsEnabled(false)
        assertEquals("disabled", r.haptic.preview(CipherHapticSemantic.CONTROL_TAP).reason)
        val erm = Rig(hw = HardwareClass.ERM_Z)
        val a = erm.haptic.preview(CipherHapticSemantic.CONTROL_TAP)
        assertEquals(CipherHapticAvailability(false, "silent", "degraded-to-silent"), a)
    }

    @Test
    fun `wake lock 兜底超时按 kind 取寿命，至少覆盖保活窗口`() {
        val loader = SpecLoader(SpecPaths.runtimeJson())
        val rw = loader.resolve("item.dissolve", HardwareClass.LINEAR_X_FULL)!!
        assertEquals(WaveKind.ONESHOT, rw.kind)
        val h = PlaybackHandle(1, rw, TestScheduler(), mockk(relaxed = true), mockk(relaxed = true),
                               form = com.cipherlex.haptic.core.ExpressionForm.WAVEFORM)
        assertEquals(PlaybackHandle.KEEPALIVE_MS + PlaybackHandle.GRACE_MS + PlaybackHandle.WAKELOCK_MARGIN_MS,
                     h.wakeLockTimeoutMs())
    }
}
