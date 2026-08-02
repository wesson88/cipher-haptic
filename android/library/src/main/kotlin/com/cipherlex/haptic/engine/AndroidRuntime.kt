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
 * `WakeLockGateway` 的 Android 实现（P-10）。
 *
 * > ⚠️ **这道防线的有效性本身待验证（V3）。** `vibrate()` 提交后实际执行在
 * > system_server 的 `VibratorService`，**它自己持有 wake lock**；app 侧再持
 * > partial wake lock 对**已提交**的振动可能完全无用。
 * >
 * > V3 实测若无差异（判据：所有格差值 <5 个百分点），**直接删掉这个类**，
 * > 不留装饰性代码 —— 留着会让下一个人以为这里有防线。
 *
 * 判定条件按**场景**而非时长：v1.1.0 写「短 transient（<50ms）不持」，
 * **判断维度错了** —— wake lock 防的是 CPU 睡眠打断振动，与屏幕状态相关、
 * 与振动时长无关（熄屏下 40ms 的通知振动同样会被打断）。
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
