package com.cipherlex.haptic.demo

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.cipherlex.haptic.CipherHaptic
import com.cipherlex.haptic.CipherHapticCancelToken
import com.cipherlex.haptic.CipherHapticSemantic
import com.cipherlex.haptic.engine.NoWakeLock

/**
 * V3 的熄屏 / Doze 执行体 —— **前台服务 + AlarmManager**。
 *
 * ## 为什么必须走到这一步
 *
 * 前两版触发方式都被 ROM 挡住了：
 *
 * | 方式 | 结果 |
 * |---|---|
 * | Activity + `am start` | `am start` **会点亮屏幕** —— 一发命令前提条件就没了 |
 * | BroadcastReceiver | **ColorOS 冻结后台应用的广播投递**（实测：进程存活、`am` 回 result=0，接收器却没跑；把 App 拉回前台后排队的广播才涌出来） |
 *
 * 前台服务是 ROM 一般不冻的那一类（与"来电"同级）。实测 `usage: RINGTONE |
 * com.ss.android.lark` 说明飞书的来电振动正是走这条路。
 *
 * ## AlarmManager 的必要性
 *
 * 光有前台服务还不够：**熄屏后 CPU 会睡**，`Handler.postDelayed` 会被推迟到下次唤醒
 * —— 而那恰恰是 V3 要测的量。`setExactAndAllowWhileIdle` 能穿透 Doze，用它来**触发**
 * 每一轮播放，就把"我们的定时器准不准"与"闹钟准不准"分开了：
 *
 * - 闹钟负责**按时唤醒**（系统保证，穿透 Doze）
 * - 我们负责**播放并记录时刻**
 *
 * 于是测出来的偏差干净地归因到"库自己的调度"，而不是"进程根本没被唤醒"。
 *
 * ## ⚠️ 这个 demo 的做法不代表库的推荐用法
 *
 * 见主文档 **A.1b**：**后台告警不该走这个库**，该走 `NotificationChannel`
 * 让系统代为振动。这里搭前台服务纯粹是为了**把 V3 那个变量测出来**，
 * 不是在示范"应该这样做后台触觉"。
 */
class V3Service : Service() {

    private var haptic: CipherHaptic? = null
    private var token: CipherHapticCancelToken? = null
    private var holdWakeLock = true
    private var roundsLeft = 0
    private var burstPerRound = BURST_PER_ROUND

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.getStringExtra("cmd")) {
            "stop" -> { stopAll(); stopSelf(); return START_NOT_STICKY }
            "tick" -> { playOneRound(); return START_STICKY }   // 闹钟回调
        }

        holdWakeLock = intent?.getStringExtra("wakelock") != "off"
        roundsLeft = intent?.getStringExtra("rounds")?.toIntOrNull() ?: 20
        // Doze 下 setExactAndAllowWhileIdle 被限流到约 9 分钟一次，靠多轮闹钟采样不现实。
        // 故支持"少轮次 + 长串"：一次唤醒内连排很多次，直接测 CPU 会不会在其间睡去。
        burstPerRound = intent?.getStringExtra("burst")?.toIntOrNull() ?: BURST_PER_ROUND
        val h = CipherHaptic.create(
            applicationContext,
            wakeLockOverride = if (holdWakeLock) null else NoWakeLock,
        )
        haptic = h
        Log.i(TAG, "V3SVC start wakelock=${if (holdWakeLock) "ON" else "OFF"} rounds=$roundsLeft")
        playOneRound()
        return START_STICKY
    }

    /**
     * 一轮：播一次 → 排下一次闹钟。
     *
     * **刻意用 oneshot 逐轮触发，而不是 looping**：looping 在平台侧是无限循环
     * （`repeat=0`），**不产生离散的提交记录**，间隔就无从测起（见状态机 §4.7）。
     * 逐轮 oneshot 每次都在 `dumpsys vibrator_manager` 留一条带时间戳的记录，
     * 相邻两条之差就是我们要的观测量。
     */
    private fun playOneRound() {
        val h = haptic ?: return
        if (roundsLeft <= 0) { stopAll(); stopSelf(); return }
        roundsLeft--

        // ⚠️ **闹钟只负责把 CPU 叫醒，被测对象是【库自己的定时器】。**
        //
        // 首版让闹钟直接驱动每一轮播放，结果实测间隔 5019ms 而设计是 2000ms ——
        // 那是 setExactAndAllowWhileIdle 被系统限流，**不是我们的调度慢**。照那样测
        // 下去，测的是闹钟精度，与 V3 要回答的问题无关。
        //
        // 现在改为：闹钟唤醒后，用【库自己的 scheduler】连排一串播放，测那串的间隔。
        // 于是"CPU 睡了导致我们的定时器被推迟"这个变量才被隔离出来 —— 这正是 wake lock
        // 声称要防的东西。
        val burst = burstPerRound
        val gap = BURST_GAP_MS
        for (i in 0 until burst) {
            android.os.Handler(mainLooper).postDelayed({
                h.playEffect(CipherHapticSemantic.NOTIFY_MESSAGE)
                Log.i(TAG, "V3SVC play r=$roundsLeft i=$i at=${SystemClock.elapsedRealtime()}")
            }, i * gap)
        }
        Log.i(TAG, "V3SVC round left=$roundsLeft burst=$burst gap=${gap}ms " +
                   "at=${SystemClock.elapsedRealtime()}")

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getService(
            this, 0,
            Intent(this, V3Service::class.java).putExtra("cmd", "tick"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // setExactAndAllowWhileIdle 是唯一能穿透 Doze 的精确闹钟。
        // ⚠️ 系统对它有频率限制（Doze 下每 app 约 9 分钟一次），所以这个测法在真 Doze
        //    里只能低频采样 —— 熄屏（非 Doze）下不受限。这条限制必须写进报告，
        //    否则会把"闹钟被限流"误读成"我们的调度不准"。
        am.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + ROUND_INTERVAL_MS, pi,
        )
    }

    private fun stopAll() {
        token?.cancel()
        token = null
        haptic?.stopAllEffects()
        Log.i(TAG, "V3SVC stop")
    }

    override fun onDestroy() {
        stopAll()
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
            .setContentText("熄屏 / Doze 下的调度偏差采集")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private companion object {
        const val TAG = "CipherHapticTuner"
        const val CHANNEL = "v3"
        const val NOTIF_ID = 31
        /** 每轮之间的闹钟间隔。**它本身会被系统限流，不是观测量。** */
        const val ROUND_INTERVAL_MS = 10_000L

        /** 每次唤醒后连排几次播放 —— 这一串的间隔才是观测量。 */
        const val BURST_PER_ROUND = 5

        /**
         * 串内间隔。**故意取得比屏幕关闭后 CPU 的典型休眠周期长**，
         * 这样"定时器是否被推迟"才有机会显现；太短则每次都在同一个唤醒窗口内完成，
         * 测不出差异。
         */
        const val BURST_GAP_MS = 800L
    }
}
