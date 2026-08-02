package com.cipherlex.haptic.engine

import com.cipherlex.haptic.core.AndroidTranslator
import com.cipherlex.haptic.core.HardwareClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `VibratorGateway` 的 fake —— 平台调用被收窄成一个接口带来的直接红利：
 * 这些用例全在 JVM 上跑，不需要 Robolectric、不需要设备。
 */
class FakeGateway(
    override val hasVibrator: Boolean = true,
    override val hasAmplitudeControl: Boolean = true,
    override val sdkInt: Int = 34,
    private val supportedPrimitives: Set<Int> = setOf(1),   // 1 = PRIMITIVE_CLICK
    private val failOnVibrate: Boolean = false,
) : VibratorGateway {

    val waveformCalls = mutableListOf<Triple<LongArray, IntArray, Int>>()
    val compositionCalls = mutableListOf<List<AndroidTranslator.Primitive>>()
    var cancelCount = 0

    override fun areAllPrimitivesSupported(vararg primitives: Int) =
        primitives.all { it in supportedPrimitives }

    override fun vibrateWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int) {
        if (failOnVibrate) throw VibrateFailed(RuntimeException("fake DeadObjectException"))
        waveformCalls += Triple(timings, amplitudes, repeat)
    }

    override fun vibrateComposition(primitives: List<AndroidTranslator.Primitive>) {
        if (failOnVibrate) throw VibrateFailed(RuntimeException("fake DeadObjectException"))
        compositionCalls += primitives
    }

    override fun cancelAll() { cancelCount++ }
}

class HardwareClassProbeTest {

    @Test
    fun `无振幅控制定为 ERM_Z`() {
        val p = HardwareClassProbe(FakeGateway(hasAmplitudeControl = false))
        assertEquals(HardwareClass.ERM_Z, p.hardwareClass)
    }

    @Test
    fun `有振幅控制定为 LINEAR_X_FULL`() {
        val p = HardwareClassProbe(FakeGateway(hasAmplitudeControl = true))
        assertEquals(HardwareClass.LINEAR_X_FULL, p.hardwareClass)
    }

    @Test
    fun `默认永不产出 LINEAR_X_LIMITED —— 它在 minSdk 29 上不可探测`() {
        // P-07：第三档的判定依据（"有振幅控制但频宽窄"）需要 API 31+ 的
        // getFrequencyProfile()，而 minSdk 是 29。任何 API 段都不得自动产出它，
        // 否则就是把机型表偷偷塞了回来。
        for (sdk in intArrayOf(29, 30, 31, 33, 34)) {
            for (amp in booleanArrayOf(true, false)) {
                val cls = HardwareClassProbe(
                    FakeGateway(sdkInt = sdk, hasAmplitudeControl = amp)
                ).hardwareClass
                assertTrue(
                    cls != HardwareClass.LINEAR_X_LIMITED,
                    "sdk=$sdk amp=$amp 竟自动产出了 LINEAR_X_LIMITED",
                )
            }
        }
    }

    @Test
    fun `配置注入是产出 LINEAR_X_LIMITED 的唯一路径`() {
        val p = HardwareClassProbe(
            FakeGateway(hasAmplitudeControl = true),
            override = HardwareClass.LINEAR_X_LIMITED,
        )
        assertEquals(HardwareClass.LINEAR_X_LIMITED, p.hardwareClass)
    }

    @Test
    fun `定档结果运行时不变`() {
        val p = HardwareClassProbe(FakeGateway(hasAmplitudeControl = true))
        val first = p.hardwareClass
        repeat(5) { assertEquals(first, p.hardwareClass) }
    }

    @Test
    fun `API 能力门 —— SDK 30 不足以保证原语可用`() {
        val click = intArrayOf(1)
        // API 29：无论原语是否"支持"，Composition 都不存在
        assertFalse(HardwareClassProbe(FakeGateway(sdkInt = 29)).canUseComposition(click))
        // API 30 且原语受支持 → 可用
        assertTrue(HardwareClassProbe(FakeGateway(sdkInt = 30)).canUseComposition(click))
        // ★ API 34 但机型不支持该原语 → 不可用。只查 API level 会在这里静默失败（P-08）
        assertFalse(
            HardwareClassProbe(
                FakeGateway(sdkInt = 34, supportedPrimitives = emptySet())
            ).canUseComposition(click)
        )
    }

    @Test
    fun `无振动器时不抛异常,只是标记不可用`() {
        // P-18：缺权限 / 无硬件都要静默降级 + 指标，不能在探测阶段崩
        val p = HardwareClassProbe(FakeGateway(hasVibrator = false, hasAmplitudeControl = false))
        assertFalse(p.vibratorAvailable)
        assertEquals(HardwareClass.ERM_Z, p.hardwareClass)   // 仍定出档位
    }
}
