package com.cipherlex.haptic.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
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
        if (Thread.currentThread() === thread) task() else handler.post { task() }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): HapticScheduler.Cancellable {
        // ⚠️ 不得用 Thread.sleep 排节拍 —— 那是 Haptico 的实证坑（PatternEngine.swift
        //    在串行 OperationQueue 上 Thread.sleep）：阻塞且无法精确取消。
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return object : HapticScheduler.Cancellable {
            override fun cancel() = handler.removeCallbacks(r)
        }
    }

    /** 进程退出前调用。库自身不主动销毁 —— engine 一旦启动常驻到进程结束。 */
    fun shutdown() {
        thread.quitSafely()
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
    override fun acquire() = Unit
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
 * 判定条件按**场景**而非时长：v1.1.0 写「短 transient（<50ms）不持」，
 * **判断维度错了** —— wake lock 防的是 CPU 睡眠打断振动，与屏幕状态相关、
 * 与振动时长无关。
 */
class AndroidWakeLock(context: Context) : WakeLockGateway {

    private val power = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val lock: PowerManager.WakeLock? = runCatching {
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CipherHaptic:playback")
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    override fun shouldHold(resolved: ResolvedWaveform): Boolean {
        // 按场景：屏幕熄灭或效果本身是长生命周期（looping / continuous）时才持有。
        val screenOff = power?.isInteractive == false
        val longLived = resolved.kind != WaveKind.ONESHOT
        return screenOff || longLived
    }

    override fun acquire() {
        // 兜底超时：即便状态机出现未预料的路径，系统也会在此后强制释放。
        // ⚠️ 它是【最后一道】保险，不是主防线 —— "绝不泄漏"依赖状态机的可达性完备。
        runCatching { lock?.acquire(MAX_HOLD_MS) }
    }

    override fun release() {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    private companion object {
        const val MAX_HOLD_MS = 60_000L
    }
}
