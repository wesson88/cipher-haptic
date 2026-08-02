package com.cipherlex.haptic.core

/**
 * 串行执行器抽象 —— 对应「句柄状态机」§七.1。
 *
 * > 「`HapticScheduler` 串行化的是**决策 + 提交硬件命令**这一段，不是底层播放本身。
 * >   **所有 facade 方法与所有定时器回调**都 marshal 到它 → 决策在它上面**原子执行**。」
 *
 * ## 为什么定时器也必须走它（§七.2b）
 *
 * `NATURAL_END` / `GRACE_EXPIRED` / `EXPIRE` / keepAlive 到期**全部由定时器驱动**，
 * 它们是本设计里状态迁移的**主要来源**。若定时器回调不与业务调用共享同一条串行线，
 * 抢占计算读到的 `activeSnapshot` 会包含实际已结束的 handle —— 于是"抢占一个不存在
 * 的振动"，或反过来漏抢真正在播的。
 *
 * ## 为什么它是接口而不是直接用 Handler
 *
 * 时间是这个库最难测的东西：idle 超时、grace 窗口、trailing coalesce 的 16ms 节流，
 * 全都依赖"过了多久"。把调度与时钟抽出来，JVM 单测就能用**假时钟瞬间推进**，
 * 不必真的 sleep —— 否则一个 1.5 秒的 idle 超时测试就要跑 1.5 秒，
 * 而 fuzz 那种量级的用例根本没法测时间行为。
 *
 * ⚠️ **不得用 `Thread.sleep` 排节拍**。这是 Haptico 的实证坑（`PatternEngine.swift`
 * 在串行 `OperationQueue` 上 `Thread.sleep`）：阻塞且无法精确取消。
 */
interface HapticScheduler {

    /** 单调时钟，毫秒。**不是** wall clock —— 后者会被用户改时间/NTP 校时打乱。 */
    fun nowMs(): Long

    /** 提交到串行队列。已在队列线程上时可直接执行，但**不得**跳过排队语义。 */
    fun submit(task: () -> Unit)

    /**
     * 延时提交。返回可取消的句柄。
     *
     * 取消是**尽力而为**：取消动作与已在途的回调之间存在窗口，所以状态机必须能
     * 吸收迟到的定时器事件（`Paused + NATURAL_END`、`Submitting + NATURAL_END`）。
     * **这是 §4.1 那条"显式吸收"要求的根因，不是防御性编程。**
     */
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable

    interface Cancellable {
        fun cancel()
    }
}

/**
 * 测试用调度器：**手动推进的假时钟 + 立即执行的串行队列**。
 *
 * 它让"1.5 秒的 idle 超时"变成一次 `advance(1500)`，测试瞬间完成。
 */
class TestScheduler : HapticScheduler {

    private var now = 0L
    private val timers = sortedMapOf<Long, MutableList<Task>>()
    private var seq = 0L

    private inner class Task(val at: Long, val id: Long, val run: () -> Unit) :
        HapticScheduler.Cancellable {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }

    override fun nowMs() = now

    override fun submit(task: () -> Unit) = task()      // 串行 = 立即，测试里无并发

    override fun schedule(delayMs: Long, task: () -> Unit): HapticScheduler.Cancellable {
        val t = Task(now + delayMs, seq++, task)
        timers.getOrPut(t.at) { mutableListOf() } += t
        return t
    }

    /** 推进假时钟，按时刻顺序触发到期任务。 */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = timers.keys.firstOrNull { it <= target } ?: break
            val batch = timers.remove(next) ?: continue
            now = next
            // 迭代期间可能新排任务（如 looping 重排），故用快照
            for (t in batch.toList()) if (!t.cancelled) t.run()
        }
        now = target
    }

    /** 当前仍在排的未取消定时器数 —— 用于断言"没有定时器泄漏"。 */
    fun pendingTimers(): Int = timers.values.sumOf { l -> l.count { !it.cancelled } }
}
