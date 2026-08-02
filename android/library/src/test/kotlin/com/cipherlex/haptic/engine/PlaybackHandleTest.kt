package com.cipherlex.haptic.engine

import com.cipherlex.haptic.core.HardwareClass
import com.cipherlex.haptic.core.PlaybackFsm
import com.cipherlex.haptic.core.ResolvedWaveform
import com.cipherlex.haptic.core.SpecLoader
import com.cipherlex.haptic.core.SpecPaths
import com.cipherlex.haptic.core.TestScheduler
import com.cipherlex.haptic.core.TransitionTable
import com.cipherlex.haptic.core.WaveKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeWakeLock : WakeLockGateway {
    var held = 0
    var acquires = 0
    override fun shouldHold(resolved: ResolvedWaveform) = true    // 最坏情况：总是持有
    override fun acquire() { held++; acquires++ }
    override fun release() { held-- }
}

/**
 * **用真 `PlaybackHandle` 跑不变式 fuzz。**
 *
 * 与 core 侧 `FsmInvariantTest` 的分工：
 *   core 那份 —— 用**模拟**资源，验证【迁移表本身】是否让资源不泄漏
 *   本 份    —— 用**真实**的 handle（真定时器、真 wake lock 计数、真 gateway 调用），
 *                验证【实现】有没有把迁移表的保证兑现
 *
 * 这个区分不是形式主义：文档 §十 提醒过「v2→v3 修掉的 8 个缺陷里 6 个根因在
 * side effect 时序、失败路径、外部 timer，**纯 FSM 单测一条都抓不到**」。
 * `PlaybackActions` 的实现漏掉一处 `cancel()`，core 那份 fuzz 照样全绿。
 */
class PlaybackHandleTest {

    private val loader = SpecLoader(SpecPaths.runtimeJson())
    private val table = TransitionTable.from(loader.transitions)

    private fun handleFor(
        semantic: String,
        hw: HardwareClass = HardwareClass.LINEAR_X_FULL,
        sched: TestScheduler = TestScheduler(),
        gw: FakeGateway = FakeGateway(),
        wl: FakeWakeLock = FakeWakeLock(),
    ): Triple<PlaybackHandle, TestScheduler, FakeWakeLock> {
        val rw = loader.resolve(semantic, hw)!!
        val h = PlaybackHandle(1, rw, sched, gw, wl, useComposition = false)
        h.attach(PlaybackFsm(table, rw.kind, rw.category, h.actions))
        return Triple(h, sched, wl)
    }

    @Test
    fun `oneshot 正常播放 —— 定时器到期自然结束并回收`() {
        val (h, sched, wl) = handleFor("item.dissolve")
        h.fsm.send("SUBMIT")
        assertEquals("Active", h.fsm.state, "submit 成功应进 Active")
        assertEquals(1, wl.held, "播放中应持有 wake lock")

        sched.advance(h.resolved.totalDurationMs.toLong())
        assertEquals("Completed", h.fsm.state)

        h.fsm.send("GRACE_EXPIRED")
        assertEquals("Reclaimed", h.fsm.state)
        assertEquals(0, wl.held, "回收后 wake lock 必须归零")
        assertTrue(!h.anyResourceHeld())
        assertEquals(0, sched.pendingTimers(), "回收后不得残留定时器")
    }

    @Test
    fun `平台调用失败走 FAIL 而非崩溃,且资源照样回收`() {
        val sched = TestScheduler()
        val gw = FakeGateway(failOnVibrate = true)
        val wl = FakeWakeLock()
        val rw = loader.resolve("item.dissolve", HardwareClass.LINEAR_X_FULL)!!
        val h = PlaybackHandle(1, rw, sched, gw, wl, useComposition = false)
        h.attach(PlaybackFsm(table, rw.kind, rw.category, h.actions))

        h.fsm.send("SUBMIT")                       // 平台抛 DeadObjectException
        assertEquals("Failed", h.fsm.state, "失败必须走 FAIL 出口，不得停在 Submitting")

        h.fsm.send("EXPIRE")
        assertEquals("Reclaimed", h.fsm.state)
        assertEquals(0, wl.held, "★ 失败路径同样要释放 wake lock —— v2 的泄漏正出在这里")
        assertEquals(0, sched.pendingTimers())
    }

    @Test
    fun `continuous 靠空闲超时结束,不靠时长推断`() {
        val (h, sched, _) = handleFor("gesture.track")
        assertEquals(WaveKind.CONTINUOUS, h.resolved.kind)
        assertEquals(0, h.resolved.totalDurationMs, "continuous 的 totalDurationMs 必须为 0")

        h.fsm.send("SUBMIT")
        assertEquals("Active", h.fsm.state)

        val idle = h.resolved.continuous!!.idleTimeoutMs.toLong()
        sched.advance(idle - 1)
        assertEquals("Active", h.fsm.state, "未到空闲超时不得结束")

        h.fsm.send("UPDATE")                       // 手指动了 → 重置倒计时
        sched.advance(idle - 1)
        assertEquals("Active", h.fsm.state, "★ UPDATE 必须重置 idle-timer")

        sched.advance(2)
        assertEquals("Completed", h.fsm.state, "空闲超时到期应自然结束")
    }

    @Test
    fun `continuous 起播用缓冲的最新值,不是 IR 默认值`() {
        // v4.3 §4.6：begin() 后到 SUBMIT_OK 前手指已经动了，这些值必须被用上
        val sched = TestScheduler()
        val gw = FakeGateway()
        val rw = loader.resolve("gesture.track", HardwareClass.LINEAR_X_FULL)!!
        val h = PlaybackHandle(1, rw, sched, gw, FakeWakeLock(), useComposition = false)
        h.attach(PlaybackFsm(table, rw.kind, rw.category, h.actions))

        h.coalescer.buffer(0.9f, 0.5f)             // 平台就绪前手指已拖到 0.9
        h.fsm.send("SUBMIT")

        val amp = gw.waveformCalls.first().second.first()
        assertEquals(230, amp, "★ 起播振幅应来自 0.9（=230），而非 IR 默认 0.5（=128）")
    }

    @Test
    fun `随机事件序列下真实资源不泄漏`() {
        val rng = Random(20260802)
        val semantics = loader.semanticIds
        var runs = 0

        repeat(5_000) { i ->
            val sem = semantics[rng.nextInt(semantics.size)]
            val hw = HardwareClass.entries[rng.nextInt(HardwareClass.entries.size)]
            val rw = loader.resolve(sem, hw) ?: return@repeat   // silent 降级不建 handle
            val sched = TestScheduler()
            val wl = FakeWakeLock()
            val gw = FakeGateway(failOnVibrate = rng.nextInt(4) == 0)   // 25% 概率平台失败
            val h = PlaybackHandle(i.toLong(), rw, sched, gw, wl, useComposition = false)
            h.attach(PlaybackFsm(table, rw.kind, rw.category, h.actions))

            val seq = List(rng.nextInt(1, 15)) { table.events[rng.nextInt(table.events.size)] }
            for (ev in seq) {
                h.fsm.send(ev)
                if (rng.nextInt(3) == 0) sched.advance(rng.nextLong(1, 200))  // 穿插时间流逝
            }

            repeat(4) {
                h.fsm.send("CANCEL"); h.fsm.send("GRACE_EXPIRED"); h.fsm.send("EXPIRE")
            }
            sched.advance(60_000)                  // 把所有在途定时器推到期

            val ctx = "[$i] $sem × $hw seq=$seq"
            assertEquals("Reclaimed", h.fsm.state, "$ctx 抵达不了 Reclaimed")
            assertEquals(0, wl.held, "$ctx wake lock 泄漏（取 ${wl.acquires} 次）")
            assertTrue(!h.anyResourceHeld(), "$ctx 回收后仍持有资源")
            runs++
        }
        println("真实 handle fuzz：$runs 轮，wake lock 与定时器均无泄漏")
    }
}
