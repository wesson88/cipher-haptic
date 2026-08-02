package com.cipherlex.haptic.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FSM 不变式 fuzz · Kotlin 半边（工程骨架 §六.5）。
 *
 * 与 Python 参考实现 `reference/fsm.py` 用**同一张迁移表**、**同一组断言**。
 * 两端各跑一遍不是冗余——它验证的是**两端的 runner 对同一张表的解释一致**，
 * 尤其是守卫求值（`kind!=continuous && cat=critical` 这类复合条件）。
 *
 * ⚠️ 断言打在**资源**上而不是状态上：文档 §十 提醒过「查表单测只验证迁移表本身，
 * v2→v3 修掉的 8 个缺陷里 6 个纯 FSM 单测一条都抓不到」。泄漏才是这个状态机存在的理由。
 */
class FsmInvariantTest {

    private val loader = SpecLoader(SpecPaths.runtimeJson())
    private val table = TransitionTable.from(loader.transitions)

    /** 模拟 PlaybackActions 持有的资源。 */
    private class Res : PlaybackActions {
        var player = false
        var wakelock = false
        var endTimer = false
        var idleTimer = false
        var keepAlive = false
        var released = 0
        var continuous = false
        var latestBuffered = false          // v4.3：平台就绪前缓存的最新参数

        fun anyHeld() = player || wakelock || endTimer || idleTimer || keepAlive

        override fun invoke(action: String) {
            when (action) {
                "submit", "resubmit" -> {
                    player = true; wakelock = true; endTimer = !continuous
                }
                "startEndTimer" -> endTimer = true
                "startIdleTimer" -> idleTimer = true
                // v4.3：只更新 trailing coalesce 的 latest，不碰平台、不动 idle-timer
                "bufferParams" -> latestBuffered = true
                "applyParams" -> idleTimer = true      // 重置 = 仍持有
                "startKeepAlive" -> keepAlive = true
                "clearKeepAlive" -> keepAlive = false
                "suspend" -> endTimer = false
                "stop" -> { endTimer = false; idleTimer = false }
                "release" -> {
                    player = false; wakelock = false
                    endTimer = false; idleTimer = false; keepAlive = false
                    released++
                }
            }
        }
    }

    @Test
    fun `随机事件序列下资源不泄漏`() {
        val rng = Random(20260802)
        val kinds = WaveKind.entries
        val cats = Category.entries
        var runs = 0

        repeat(50_000) { i ->
            val kind = kinds[rng.nextInt(kinds.size)]
            val cat = cats[rng.nextInt(cats.size)]
            val res = Res().also { it.continuous = kind == WaveKind.CONTINUOUS }
            val m = PlaybackFsm(table, kind, cat, res)

            val seq = List(rng.nextInt(1, 25)) { table.events[rng.nextInt(table.events.size)] }
            seq.forEach { m.send(it) }

            // 收尾：必须先 CANCEL 推进终态，GRACE_EXPIRED/EXPIRE 才有意义。
            // 不做早退——终态吸收保证多余的事件是 no-op，早退反而要引入歧义标签。
            repeat(4) {
                m.send("CANCEL"); m.send("GRACE_EXPIRED"); m.send("EXPIRE")
            }

            val ctx = "[$i] kind=$kind cat=$cat seq=$seq"
            assertEquals("Reclaimed", m.state, "$ctx 抵达不了 Reclaimed —— 资源泄漏")
            assertEquals(1, res.released, "$ctx release 应恰好 1 次（0=没释放，>1=重复释放）")
            assertTrue(!res.anyHeld(), "$ctx Reclaimed 后仍持有资源")

            // 终态吸收：Reclaimed 之后任何事件都不改变状态
            table.events.forEach { ev ->
                m.send(ev)
                assertEquals("Reclaimed", m.state, "$ctx Reclaimed 收到 $ev 后变了")
            }
            runs++
        }
        println("FSM fuzz：$runs 轮，资源不泄漏 / 终态吸收 全部通过")
    }

    @Test
    fun `守卫互斥 —— 任何组合都不得命中多条转移`() {
        // lookup() 内部 check(hits.size <= 1)，这里穷举全部 (状态 × 事件 × kind × cat)
        var n = 0
        for (s in table.states) for (e in table.events)
            for (k in WaveKind.entries) for (c in Category.entries) {
                table.lookup(s, e, k, c)   // 命中多条会抛
                n++
            }
        println("守卫互斥：穷举 $n 组 (状态×事件×kind×cat) 全部至多命中一条")
    }
}
