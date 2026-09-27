package com.cipherlex.haptic.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.cipherlex.haptic.core.HapticScheduler
import com.cipherlex.haptic.core.ResolvedWaveform
import com.cipherlex.haptic.core.WaveKind

/**
 * `HapticScheduler` 的 Android 实现 —— **单线程串行执行器**（§七.1）。
 *
 * 串行化的是**决策 + 提交硬件命令**这一段，不是底层播放本身。所有 facade 方法与
 * **所有定时器回调**都 marshal 到这一条线上，于是：
 *
 * - `cancel vs play` 竞态天然消解（§七.2）——严格排队，不存在悬空中间态；
 * - `timer vs play` 竞态同样消解（§七.2b）——否则抢占读到的 `activeSnapshot`
 *   会包含实际已结束的 handle。
 *
 * ## 为什么不放主线程
 *
 * `makePlayer`（pattern 编译）与 `vibrate`（binder IPC）**都不能在主线程同步调**，
 * 多次调用会累积成 ANR（性能 §四.2）。
 *
 * ## 为什么用 `SystemClock.uptimeMillis`
 *
 * 单调时钟。wall clock 会被用户改时间 / NTP 校时打乱，而这里所有判定
 * （idle 超时、grace、节流窗口）都是"过了多久"，不是"几点了"。
 */
class AndroidHapticScheduler(name: String = "CipherHaptic") : HapticScheduler {

    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    override fun nowMs(): Long = SystemClock.uptimeMillis()

    override fun submit(task: () -> Unit) {
        // 已在串行线程上时直接执行 —— 否则 handle 创建与平台提交会被拆到两个
        // 消息里，而它们必须在【同一个 critical section】内完成（§七.3）。
        if (Thread.currentThread() === thread) task() else handler.post { guarded(task) }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): HapticScheduler.Cancellable {
        // ⚠️ 不得用 Thread.sleep 排节拍 —— 那是 Haptico 的实证坑（PatternEngine.swift
        //    在串行 OperationQueue 上 Thread.sleep）：阻塞且无法精确取消。
        val r = Runnable { guarded(task) }
        handler.postDelayed(r, delayMs)
        return object : HapticScheduler.Cancellable {
            override fun cancel() = handler.removeCallbacks(r)
        }
    }

    /** 进程退出前调用。库自身不主动销毁 —— engine 一旦启动常驻到进程结束。 */
    fun shutdown() {
        thread.quitSafely()
    }

    /**
     * 最后一道防线（审查 B3）：串行线程上的未捕获异常会直接让宿主进程崩溃，违反「API 绝不抛异常」。
     * 主防线在 `PlaybackHandle`（平台调用一律 try/catch → FAIL）；这里只兜住漏网的，并留日志。
     */
    private fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (e: Exception) {
            Log.e("CipherHaptic", "串行任务抛出未捕获异常（已吞掉，不崩宿主）", e)
        }
    }
}

/**
 * **V3 的对照组实现**：什么都不做。
 *
 * 传给 `CipherHaptic.create(wakeLockOverride = NoWakeLock)` 即构成"不持锁"组。
 * V3 的判据是**两组的振动完成率之差**：任一格 ≥20 个百分点算有效，所有格 <5 算无效。
 */
object NoWakeLock : WakeLockGateway {
    override fun shouldHold(resolved: ResolvedWaveform) = false
    override fun acquire(timeoutMs: Long) = Unit
    override fun release() = Unit
}

/**
 * `WakeLockGateway` 的 Android 实现（P-10）。
 *
 * > ⚠️ **这道防线的有效性本身待验证（V3）**，而 2026-08-02 真机取证已让天平明显倾斜：
 * >
 * > `dumpsys power` 显示，我们调 `vibrate()` 时**系统会自行获取一个 `*vibrator*`
 * > partial wake lock**（`*名字*` 是 system_server 内部锁的命名约定，uid 归属调用方）：
 * >
 * > ```
 * > 08-02 22:51:08.624 - 10464 (com.cipherlex.haptic.demo) - ACQ *vibrator* (partial)
 * > ```
 * >
 * > 这直接印证了性能文档的怀疑：**对已提交的振动，app 侧再持锁基本是多余的。**
 *
 * ## 但 V3 的问题因此变小了、也变准了
 *
 * 真正还需要锁的，不是"振动播放期间"，而是**两次提交之间的调度间隙**：
 * `looping` 效果靠我们自己的 `end-timer` 触发 `resubmit`，`continuous` 靠 idle-timer
 * —— **那些定时器要 CPU 醒着才会准时触发**。系统的 `*vibrator*` 锁只覆盖它正在播的
 * 那一段，覆盖不到间隙。
 *
 * 所以 V3 的实验应聚焦：**熄屏 / Doze 下的 looping 与 continuous，两次提交的间隔
 * 是否被拉长**，而不是"单次 oneshot 是否播完"。见 [[P0验证计划]] V3。
 *
 * ## V3 实测结论（2026-08-03，OPPO PJJ110 / ColorOS / SDK 36）
 *
 * **在这台机器上这道防线无效 —— 但不是因为它没用，而是它防的东西根本不是威胁。**
 *
 * 45 秒长睡实验，双时钟取证：
 *
 * ```
 * ON : dev=16364ms wall=61364ms cpu=61364ms slept=0ms
 * OFF: dev=16331ms wall=61331ms cpu=61330ms slept=1ms
 * ```
 *
 * `slept=0` 表示 **CPU 全程没有挂起**（`uptimeMillis` 与 `elapsedRealtime` 同步走完）。
 * 那 wake lock 就无事可做。真正让定时器晚 16 秒的是 **cgroup freezer 冻结进程**
 * （心跳在第 20 秒断裂，解冻瞬间全部积压触发），**前台服务也挡不住**。
 *
 * | | wake lock 防的 | 实际发生的 |
 * |---|---|---|
 * | 机制 | CPU suspend | cgroup freezer |
 * | 锁能否阻止 | 能 | **完全不能** —— 两套独立机制 |
 *
 * ⚠️ **但不要据此删除本类。** 这是一台 ColorOS 机器的结论；AOSP / Pixel 上深睡是真会
 * 发生的，届时锁防的才是真威胁。海外版要覆盖 Pixel，**且模拟器答不了这个问题**
 * （虚拟机不真挂起）。P-10 的状态是"问题被重新定义"，不是"已判无效"。
 *
 * 判定条件按**场景**而非时长：v1.1.0 写「短 transient（<50ms）不持」，
 * **判断维度错了** —— wake lock 防的是 CPU 睡眠打断振动，与屏幕状态相关、
 * 与振动时长无关。
 */
class AndroidWakeLock(context: Context) : WakeLockGateway {

    private val power = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val lock: PowerManager.WakeLock? = runCatching {
        // 底层锁保持【非】引用计数：计数由 WakeLockRefCounter 在本进程内做。
        // 平台的引用计数模式与带超时的 acquire 混用时，超时释放与显式 release 会重复扣减，
        // 扣到负数直接抛 "WakeLock under-locked"。
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CipherHaptic:playback")
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    /**
     * 审查 B4：此前全库共用一把非引用计数的锁 —— 一个 handle 回收就把别的 handle 的锁也放了；
     * 兜底超时固定 60s，比 looping 的 300s 上限还短，且重提交时不会重新获取。
     * 现在按 handle 计数，超时取所有持有者要求的最晚时刻。
     */
    private val counter = WakeLockRefCounter(
        object : WakeLockRefCounter.Target {
            override fun hold(timeoutMs: Long) { runCatching { lock?.acquire(timeoutMs) } }
            override fun drop() { runCatching { if (lock?.isHeld == true) lock.release() } }
        },
        clock = SystemClock::uptimeMillis,
    )

    override fun shouldHold(resolved: ResolvedWaveform): Boolean {
        // 按场景：屏幕熄灭或效果本身是长生命周期（looping / continuous）时才持有。
        val screenOff = power?.isInteractive == false
        val longLived = resolved.kind != WaveKind.ONESHOT
        return screenOff || longLived
    }

    override fun acquire(timeoutMs: Long) = counter.acquire(timeoutMs)

    override fun release() = counter.release()
}

/**
 * 进程内的 wake lock 引用计数（审查 B4）。抽成纯 JVM 类是为了能单测 —— `PowerManager` 在 JVM 上不可用。
 *
 * - 每个持有者 `acquire` / `release` 严格成对，计数归零才真正释放底层锁；
 * - 兜底超时取**所有在持者要求的最晚时刻**：底层锁每次都以剩余时长重新 acquire（非引用计数模式下
 *   重复 acquire 会重置超时），所以一个短效果的持有不会把长效果的超时缩短；
 * - 多余的 `release`（计数已为 0）是 no-op，不会扣成负数。
 *
 * 兜底超时是【最后一道】保险，不是主防线 —— "绝不泄漏"依赖状态机的可达性完备。
 */
class WakeLockRefCounter(private val target: Target, private val clock: () -> Long) {

    interface Target {
        fun hold(timeoutMs: Long)
        fun drop()
    }

    private var count = 0
    private var holdUntil = 0L

    val holders: Int
        @Synchronized get() = count

    @Synchronized
    fun acquire(timeoutMs: Long) {
        val now = clock()
        count++
        holdUntil = maxOf(holdUntil, now + timeoutMs)
        target.hold(holdUntil - now)
    }

    @Synchronized
    fun release() {
        if (count == 0) return
        count--
        if (count == 0) {
            holdUntil = 0L
            target.drop()
        }
    }
}
