package com.cipherlex.haptic.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * trailing coalesce 的行为断言（状态机 §七.5 + §4.6）。
 *
 * 这些用例全靠 [TestScheduler] 的假时钟瞬间完成 —— 若用真时钟，
 * 单单一个 16ms 窗口的测试就要 sleep，而这里有十几个时间点要验。
 */
class ContinuousCoalescerTest {

    private fun setup(): Triple<TestScheduler, MutableList<Pair<Float, Float>>, ContinuousCoalescer> {
        val sched = TestScheduler()
        val sent = mutableListOf<Pair<Float, Float>>()
        val c = ContinuousCoalescer(sched) { i, s -> sent += i to s }
        return Triple(sched, sent, c)
    }

    @Test
    fun `首次 update 立即发送`() {
        val (_, sent, c) = setup()
        c.update(0.5f, 0.3f)
        assertEquals(listOf(0.5f to 0.3f), sent)
    }

    @Test
    fun `窗口内的多次 update 合并为一次尾部补发,且送的是最新值`() {
        val (sched, sent, c) = setup()
        c.update(0.1f, 0f)            // t=0 立即发
        sched.advance(5)
        c.update(0.2f, 0f)            // 窗口内 → 排补发
        sched.advance(5)
        c.update(0.3f, 0f)            // 窗口内 → 覆盖 latest，不重复排
        sched.advance(5)
        c.update(0.9f, 0f)            // 窗口内 → 覆盖 latest

        assertEquals(1, sent.size, "补发块尚未到期，仍应只有首次那一发")
        sched.advance(10)             // t=16，补发块到期
        assertEquals(2, sent.size)
        assertEquals(0.9f to 0f, sent.last(), "★ 送出的必须是最新值，不是窗口内第一个")
    }

    @Test
    fun `最后一次 update 的值必定被发送 —— 这是丢弃策略做不到的`() {
        // v2 的"丢弃"策略在这里会永久丢掉 0.77：手势结束时的最终强度落在窗口内
        val (sched, sent, c) = setup()
        c.update(0.2f, 0f)
        sched.advance(3)
        c.update(0.77f, 0f)           // 松手瞬间的最终值，恰好落在 16ms 窗口内
        sched.advance(20)
        assertEquals(0.77f to 0f, sent.last(), "松手瞬间的强度不得丢失")
    }

    @Test
    fun `flushPending 让结束前的未决值立即送出`() {
        val (sched, sent, c) = setup()
        c.update(0.2f, 0f)
        sched.advance(2)
        c.update(0.6f, 0f)            // 未决
        assertEquals(1, sent.size)

        c.flushPending()              // endContinuousEffect 到达
        assertEquals(2, sent.size)
        assertEquals(0.6f to 0f, sent.last())
        assertEquals(0, sched.pendingTimers(), "flush 后不得残留补发块")
    }

    @Test
    fun `reset 取消补发块,不留定时器`() {
        val (sched, sent, c) = setup()
        c.update(0.2f, 0f)
        sched.advance(2)
        c.update(0.6f, 0f)
        c.reset()
        sched.advance(50)
        assertEquals(1, sent.size, "reset 后补发块不得再触发")
        assertEquals(0, sched.pendingTimers())
        assertNull(c.latest(), "reset 应清空 latest")
    }

    // ── v4.3 bufferParams ────────────────────────────────────────────

    @Test
    fun `平台就绪前的 buffer 不发送,但保留最新值供起播使用`() {
        val (_, sent, c) = setup()
        c.buffer(0.4f, 0.2f)
        c.buffer(0.8f, 0.5f)          // 手指连着动了两次，都在 SUBMIT_OK 之前
        assertTrue(sent.isEmpty(), "平台未就绪时不得调用平台")
        assertEquals(0.8f to 0.5f, c.latest(), "★ 起播必须用最新值，不是 IR 默认值")
    }

    @Test
    fun `从未收到 UPDATE 时 latest 为空,由调用方回落 IR 默认值`() {
        val (_, _, c) = setup()
        assertNull(c.latest())
    }
}
