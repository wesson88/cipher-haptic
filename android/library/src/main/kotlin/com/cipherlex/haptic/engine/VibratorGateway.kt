package com.cipherlex.haptic.engine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.cipherlex.haptic.core.AndroidTranslator

/**
 * **本库唯一碰 `android.os.Vibrator` 的地方。**
 *
 * 收窄成一个接口，是为了让"什么能在 JVM 上测、什么必须上真机"有一条清晰的界：
 *
 * | 层 | 验证方式 |
 * |---|---|
 * | 排程顺序、句柄状态机、wake lock 生命周期、降级选路、抢占计算 | **JVM 单测**（fake gateway） |
 * | `VibrationEffect` 构造是否合法、真机手感、IPC 延迟 | **V1–V4 真机验证** |
 *
 * 不引入 Robolectric：它能验证"我调了 `vibrate()` 且参数是这些"，**验证不了真机行为**——
 * 而后者正是 V1–V4 的范围。花 40MB 依赖换一个影子实现的确认，不划算。
 *
 * ⚠️ 本接口**只收纯数据**（`AndroidTranslator` 产出的 timings/amplitudes/primitives），
 * 不收 IR、不收语义、不收 `hardwareClass`。**IR 之后禁止决策**（骨架原则 4）在这里
 * 由类型签名保证：它根本拿不到做决策所需的信息。
 */
interface VibratorGateway {

    /** 设备是否有振动器。无振动器 ≠ 无权限，两者都要静默降级 + 指标（P-18）。 */
    val hasVibrator: Boolean

    /** `hasAmplitudeControl()` —— 硬件档探测的**唯一**运行时依据（P-07）。 */
    val hasAmplitudeControl: Boolean

    /** `Build.VERSION.SDK_INT`。抽出来是为了让 JVM 单测能构造任意 API 段。 */
    val sdkInt: Int

    /**
     * `areAllPrimitivesSupported(...)`。
     *
     * ⚠️ **`SDK_INT >= 30` 不足以保证原语可用**（P-08）：API 30+ 设备并非都支持所有
     * 原语，必须再过一道运行时查询，否则会在部分机型上**静默失败**。
     */
    fun areAllPrimitivesSupported(vararg primitives: Int): Boolean

    fun vibrateWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int)

    fun vibrateComposition(primitives: List<AndroidTranslator.Primitive>)

    /**
     * ⚠️ **这是全局取消**，不是 per-player（P-03）。
     * 调用方必须先确认被取消的 token 是唯一活跃振动，否则要走"标记到点即停"路径。
     */
    fun cancelAll()
}

/** 真实现。构造失败（无振动器 / 无权限）不抛异常——A.1 约束 3「API 绝不抛异常」。 */
class AndroidVibratorGateway(context: Context) : VibratorGateway {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    override val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    override val hasAmplitudeControl: Boolean
        get() = runCatching { vibrator?.hasAmplitudeControl() == true }.getOrDefault(false)

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    // SDK 检查与调用刻意放在【同一个函数】里：lint 的流分析认这个模式，
    // 于是不必引入 androidx.annotation 只为一个 @RequiresApi ——
    // 「零第三方依赖」是这个库的交付卖点，不该为一个注解破例。
    override fun areAllPrimitivesSupported(vararg primitives: Int): Boolean {
        val v = vibrator ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching { v.areAllPrimitivesSupported(*primitives) }.getOrDefault(false)
    }

    override fun vibrateWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int) {
        val v = vibrator ?: return
        // try/catch 全包：DeadObjectException 在 system_server 繁忙或省电模式下是真实场景。
        // 失败【不抛、不崩】，由调用方回报 FAIL 走状态机的失败路径（性能 §四.1）。
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, repeat))
        }.onFailure { throw VibrateFailed(it) }
    }

    override fun vibrateComposition(primitives: List<AndroidTranslator.Primitive>) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // 走到这里说明 API 能力门没生效 —— 那是调用方的 bug,不是设备问题
            throw VibrateFailed(IllegalStateException("Composition 需 API 30+，应先过 API 能力门"))
        }
        runCatching {
            var c = VibrationEffect.startComposition()
            for (p in primitives) {
                c = c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, p.scale, p.delayMs)
            }
            v.vibrate(c.compose())
        }.onFailure { throw VibrateFailed(it) }
    }

    override fun cancelAll() {
        runCatching { vibrator?.cancel() }
    }
}

/**
 * 平台调用失败。**它不会冒泡到业务方**——`PlaybackActions` 捕获后转成状态机的
 * `FAIL` 事件，走 `Failed → Reclaimed` 释放资源。这是「API 绝不抛异常」与
 * 「失败必须可观测」两条要求的交汇点（性能 §四.1）。
 */
class VibrateFailed(cause: Throwable) : Exception(cause)
