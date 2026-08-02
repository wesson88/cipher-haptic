package com.cipherlex.haptic.engine

import com.cipherlex.haptic.core.HardwareClass

/**
 * 硬件档探测 —— 对应主文档 B.1 与双端差异 **P-07**。
 *
 * **初始化时定档，运行时不变。**
 *
 * ## 为什么默认只出两档
 *
 * v1.1.0 曾主张三档运行时探测，同时宣称"只信运行时 API，不维护机型硬编码表"。
 * **这两条不能同时成立**：
 *
 * | 端 | 可用探测 API | 实际可分档数 |
 * |---|---|---|
 * | Android | `hasAmplitudeControl()`（API 26+） | **2 档**——只能分"有无振幅控制" |
 * | | 要分频宽需 `VibratorInfo.getFrequencyProfile()`，**API 31+** | 而 minSdk 钉的是 **29** |
 *
 * 所以 `LINEAR_X_LIMITED` 的判定依据（"有振幅控制但频宽窄"）在 minSdk 29 上
 * **没有对应 API**——写成运行时探测等于把机型表偷偷塞了回来，只是没写出来。
 *
 * ## 第三档的唯一合法来源
 *
 * **显式配置注入**（[override]）。这条路径诚实地承认"它就是一张白名单"，
 * 而不是伪装成运行时探测。默认不启用，故降级矩阵的中间列在未配置时不生效。
 *
 * > ⚠️ **V4 未决**：API 31+ 的 `getFrequencyProfile()` 到底能否拿到有区分度、
 * > 且与主观手感相关的数值，尚未在真机上确认（见 P0 验证计划 §五）。
 * > 结论若为"不可探测"，应考虑**删掉 `LINEAR_X_LIMITED` 整列**——留一整列
 * > 永不执行的数据，是「零实例决策路径」换个位置复现。本类届时只需删 [override]。
 */
class HardwareClassProbe(
    private val gateway: VibratorGateway,
    /** 配置注入：**唯一**能产出 `LINEAR_X_LIMITED` 的路径。默认 null = 不启用。 */
    private val override: HardwareClass? = null,
) {

    /** 定档结果。初始化时算一次，之后不变。 */
    val hardwareClass: HardwareClass by lazy { probe() }

    /**
     * 无振动器 / 无 `VIBRATE` 权限（P-18）。此时仍会定出一个档位（`ERM_Z`），
     * 但所有播放都会在平台调用处失败并走 `FAIL` 路径 —— **不在探测阶段抛异常**。
     */
    val vibratorAvailable: Boolean get() = gateway.hasVibrator

    private fun probe(): HardwareClass {
        override?.let { return it }
        return if (gateway.hasAmplitudeControl) HardwareClass.LINEAR_X_FULL
        else HardwareClass.ERM_Z
    }

    /**
     * API 能力门（SSOT §1.2）—— **必须先于硬件档求值**。
     *
     * 它回答"能用哪种表达形式"（composition / waveform），硬件档回答"播多少内容"。
     * 顺序颠倒会产生真 bug：一台 API 29 的高端机若先查硬件档，可能选到需要
     * API 30+ 的表达形式。
     */
    fun canUseComposition(requiredPrimitives: IntArray): Boolean =
        gateway.sdkInt >= 30 && gateway.areAllPrimitivesSupported(*requiredPrimitives)
}
