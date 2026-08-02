package com.cipherlex.haptic.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cipherlex.haptic.CipherHaptic
import com.cipherlex.haptic.CipherHapticCancelToken
import com.cipherlex.haptic.CipherHapticSemantic
import com.cipherlex.haptic.engine.NoWakeLock

/**
 * **V3 的熄屏 / Doze 触发器**（P-10）。
 *
 * ## 为什么不能用 Activity
 *
 * 调音台是 Activity —— 熄屏即 `onStop`，而 `am start` 又会**点亮屏幕**，
 * 于是"熄屏下的表现"永远测不到：你一发命令，前提条件就没了。
 *
 * BroadcastReceiver 不需要界面，`adb shell am broadcast` 可以在**屏幕保持熄灭**
 * 的状态下发起播放 —— 这是 V3 能做成的前提。
 *
 * ## 测什么（2026-08-02 真机取证后改过）
 *
 * 原方案测"振动完成率"，需要加速度计或录音判定。但取证发现**系统调 `vibrate()`
 * 时会自行持有 `*vibrator*` partial wake lock**，已提交的振动不会被 CPU 睡眠打断
 * —— 那一半问题基本可以否掉。
 *
 * 剩下的真问题是**两次提交之间的调度间隙**：`looping` 靠我们自己的 `end-timer`
 * 触发 `resubmit`，那个定时器要 CPU 醒着才准时。系统的锁覆盖不到间隙。
 *
 * 于是观测量改为**相邻两次提交的实际间隔 vs 设计间隔**，而它可以直接从
 * `dumpsys vibrator_manager` 的时间戳算出来 —— **不需要任何仪器**。
 *
 * ## 用法
 *
 * ```
 * adb shell am broadcast -a com.cipherlex.haptic.demo.V3 \
 *     --es wakelock on --es secs 20
 * adb shell am broadcast -a com.cipherlex.haptic.demo.V3 --es stop 1
 * ```
 */
class V3Receiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("stop") != null) {
            token?.cancel()
            token = null
            haptic?.stopAllEffects()
            Log.i(TAG, "V3 stop")
            return
        }

        // ⚠️ 对照组的唯一开关。没有它 V3 得不出结论 —— 只测"持锁通过"证明不了锁有用，
        //    可能它本来就不需要。V3 是五项验证里唯一预期结论是【删代码】的。
        val hold = intent.getStringExtra("wakelock") != "off"
        val secs = intent.getStringExtra("secs")?.toLongOrNull() ?: 20L

        // 每轮重建实例：wakeLockOverride 只能在 create 时注入
        haptic?.stopAllEffects()
        val h = CipherHaptic.create(
            context.applicationContext,
            wakeLockOverride = if (hold) null else NoWakeLock,
        )
        haptic = h

        Log.i(TAG, "V3 start wakelock=${if (hold) "ON" else "OFF"} secs=$secs")
        // 必须用 kind=looping 的效果 —— kind 来自【效果数据】而非 API 调用，
        // 对 oneshot 效果调 playLoopingEffect 只会播一次（2026-08-02 实测发现）
        token = h.playLoopingEffect(CipherHapticSemantic.SECURITY_ALARM)

        android.os.Handler(context.mainLooper).postDelayed({
            token?.cancel()
            token = null
            Log.i(TAG, "V3 done wakelock=${if (hold) "ON" else "OFF"}")
        }, secs * 1000)
    }

    private companion object {
        const val TAG = "CipherHapticTuner"

        // 进程级持有：广播之间要保持同一个实例与 token
        @Volatile var haptic: CipherHaptic? = null
        @Volatile var token: CipherHapticCancelToken? = null
    }
}
