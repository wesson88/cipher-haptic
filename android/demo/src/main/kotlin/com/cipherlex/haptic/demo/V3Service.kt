package com.cipherlex.haptic.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.cipherlex.haptic.CipherHaptic
import com.cipherlex.haptic.CipherHapticSemantic
import com.cipherlex.haptic.engine.AndroidWakeLock
import com.cipherlex.haptic.engine.NoWakeLock
import com.cipherlex.haptic.engine.WakeLockGateway

/**
 * V3 的熄屏 / Doze 执行体 —— **长睡眠延迟测法**。
 *
 * ## 被测的是什么
 *
 * 不是"振动播不播得出来"。`dumpsys power` 已证明 `vibrate()` 时**系统会自行获取
 * `*vibrator*` partial wake lock**，对**已提交**的振动，app 侧再持锁是多余的。
 *
 * 真正还需要锁的是**两次提交之间的调度间隙**：`looping` 靠我们自己的 end-timer
 * 重排、`continuous` 靠 idle-timer —— **那些定时器要 CPU 醒着才会准时触发**。
 * 系统的 `*vibrator*` 锁只覆盖它正在播的那一段，覆盖不到间隙。
 *
 * 所以本服务直接驱动 [WakeLockGateway]（库里 `PlaybackHandle` 用的同一个东西），
 * 跨一个长间隙排一个定时器，测它准不准。
 *
 * ## 为什么间隙必须够长 —— 上一版就栽在这里
 *
 * 首版用 800ms 串内间隔，两组实测都是 800ms±1ms，"看起来"结论是无效。**但那是假的**：
 *
 * - 800ms 太短，设备**根本来不及挂起**；
 * - 更要命的是**每次播放自己就把 CPU 摁醒了**（`*vibrator*` 锁），足以撑到 800ms
 *   后的下一次。
 *
 * 两组测不出差别是必然的 —— 那不是"锁没用"的证据，是**实验没能让 CPU 睡着**。
 *
 * 现在改为：**一次睡 [DEFAULT_SLEEP_MS]，其间什么都不做**，让设备真正沉下去。
 *
 * ## 为什么是 45 秒
 *
 * [AndroidWakeLock] 的 `MAX_HOLD_MS = 60_000` —— 锁自带 60 秒兜底超时。取 45 秒是为了
 * **整个观测窗都落在锁真正有效的区间内**；超过 60 秒，ON 组的锁会自己过期，测出来的
 * 就不是"持锁 vs 不持锁"了。
 *
 * ## 唤醒为什么交给脚本，而不是 App 内的闹钟
 *
 * 若定时器被挂起推迟，它**不会自己醒来报告**，得有外力唤醒才能观测。首版试过在 App 内
 * 用 `setAlarmClock` 做观测点（号称不受 Doze 限流），**实测它也没能唤醒设备** ——
 * 它和被测定时器一起，在人手点亮屏幕的那一刻才响。
 *
 * 既然唤醒终究要靠外力，就不在 App 里绕：**脚本熄屏 → 等 sleep+margin → 亮屏 → 读日志**。
 * 唤醒时刻由脚本掌握，因此偏差可以干净地判读：
 *
 * | `dev` | 含义 |
 * |---|---|
 * | ≈ 0 | 定时器**准时触发** —— CPU 没睡，或锁挡住了睡眠 |
 * | ≈ margin | **被挂起到脚本唤醒才触发** —— 正是 wake lock 要防的 |
 * | 无 `LS fire` 行 | 连唤醒后都没触发 |
 *
 * 首轮实测（OFF 组、熄屏）：`dev=91440ms` —— 排在 +45s 的定时器 +136s 才响。
 *
 * ## ⚠️ 这个 demo 的做法不代表库的推荐用法
 *
 * 见主文档 **A.1b**：**后台告警不该走这个库**，该走 `NotificationChannel` 让系统代振。
 * 这里搭前台服务纯粹是为了**把 V3 那个变量测出来**。
 *
 * ## ⛔ 实测纠正：前台服务**并不能**免于被冻结
 *
 * 本文件早先写过"前台服务是 ROM 一般不冻的那一类" —— **实测证伪**。心跳序列（2026-08-03）：
 *
 * ```
 * hb n=3 at=15002ms   ← 准时
 * hb n=4 at=20005ms   ← 准时
 * hb n=5 at=61307ms   ← 本该 25000ms
 * hb n=6..9 at=61307ms  fire at=61308ms  ← 全部积压，在亮屏瞬间挤成一堆
 * ```
 *
 * 熄屏约 20 秒后进程即被冻结，**且全程 `wl=ON`（锁确实持着，`dumpsys power` 可见）**。
 * 更早的 burst 版之所以看着正常，是因为每 10 秒一次的闹钟不断把进程解冻，掩盖了现象。
 */
class V3Service : Service() {

    private var haptic: CipherHaptic? = null
    private var wl: WakeLockGateway = NoWakeLock
    private var holdWakeLock = false

    private var sleepMs = DEFAULT_SLEEP_MS

    private val handler by lazy { Handler(mainLooper) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        if (intent?.getStringExtra("cmd") == "stop") { finish(); return START_NOT_STICKY }

        holdWakeLock = intent?.getStringExtra("wakelock") != "off"
        sleepMs = intent?.getStringExtra("sleepms")?.toLongOrNull() ?: DEFAULT_SLEEP_MS

        // ON 组用库里真正那个锁；OFF 组用库里真正那个空实现。测的是同一对对照。
        wl = if (holdWakeLock) AndroidWakeLock(applicationContext) else NoWakeLock
        haptic = CipherHaptic.create(
            applicationContext,
            wakeLockOverride = if (holdWakeLock) null else NoWakeLock,
        )

        Log.i(TAG, "LS start wl=${if (holdWakeLock) "ON" else "OFF"} sleep=${sleepMs}ms")
        startCycle()
        return START_STICKY
    }

    /**
     * 一轮：持锁（若 ON）→ 排一个 [sleepMs] 之后的播放 → 就地不动。
     *
     * 关键在于**这段时间内我们什么都不做**：不播放、不排其它定时器、不设闹钟。
     * 只有这样设备才有机会真正挂起 —— 而那正是 wake lock 声称要防的事。
     *
     * 跑完一轮即停。多样本由脚本反复拉起，**每轮都是干净的初始态**。
     */
    private fun startCycle() {
        val t0 = SystemClock.elapsedRealtime()
        val u0 = SystemClock.uptimeMillis()
        if (holdWakeLock) wl.acquire()
        Log.i(TAG, "LS cycle t0=$t0 u0=$u0 wl=${if (holdWakeLock) "ON" else "OFF"}")

        // 心跳：用来区分"CPU 挂起"与"进程被冻结"。
        // 若进程被冻结，这些消息不会按时执行，而会在解冻瞬间**挤成一堆**同时触发 ——
        // 那是 cgroup freezer 的特征，wake lock 对它完全无能为力（两者是不同机制）。
        for (i in 1..12) {
            handler.postDelayed({
                Log.i(TAG, "LS hb n=$i at=${SystemClock.elapsedRealtime() - t0}ms")
            }, i * 5_000L)
        }

        handler.postDelayed({
            val at = SystemClock.elapsedRealtime()
            // ⚠️ 同时打两个钟：postDelayed 排程用的是 uptimeMillis，**它在 CPU 挂起期间停走**；
            //    elapsedRealtime 则一直走。两者之差 = 设备真正睡掉的时长。
            //    这是区分"锁没生效"与"锁生效了但定时器另有问题"的唯一直接证据。
            val up = SystemClock.uptimeMillis()
            Log.i(TAG, "LS fire at=$at dev=${at - t0 - sleepMs}ms " +
                       "wall=${at - t0}ms cpu=${up - u0}ms slept=${(at - t0) - (up - u0)}ms " +
                       "wl=${if (holdWakeLock) "ON" else "OFF"}")
            haptic?.playEffect(CipherHapticSemantic.NOTIFY_MESSAGE)
            if (holdWakeLock) wl.release()
        }, sleepMs)
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        runCatching { wl.release() }
        haptic?.stopAllEffects()
        Log.i(TAG, "LS done")
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wl.release() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // ⚠️ 通道本身【关掉振动】—— 否则系统会为通知自己振一下，混进我们要测的数据里。
        //    这个细节本身也印证了 A.1b：通知通道确实能振，而且是系统在振。
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "V3 测试", NotificationManager.IMPORTANCE_LOW)
                .apply { enableVibration(false); setSound(null, null) }
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("CipherHaptic V3 测试中")
            .setContentText("长睡眠调度延迟采集")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private companion object {
        const val TAG = "CipherHapticTuner"
        const val CHANNEL = "v3"
        const val NOTIF_ID = 31

        /** 睡眠时长。**必须 < `AndroidWakeLock.MAX_HOLD_MS`(60s)**，否则 ON 组的锁会自己过期。 */
        const val DEFAULT_SLEEP_MS = 45_000L

    }
}
