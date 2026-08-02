package com.cipherlex.haptic.core

/**
 * 延迟埋点 —— 对应 P0 验证计划 **V5 §6.2b**。
 *
 * ## 为什么 T0→T1 必须【单独】切出来
 *
 * 原方案只测 T0→T2 合并段，那回答不了一个已经被提出来的架构问题：
 * **「核心公共模块是否该下沉到 C++」**。因为三段的性质完全不同：
 *
 * | 段 | 内容 | 可优化性 |
 * |---|---|---|
 * | **T0→T1** | 语义解析、决策管线、降级查表、IR 生成 —— **唯一可能被 C++ 改善的部分** | 高（但基数可能极小） |
 * | T1→T2 | `vibrate()` 的 binder IPC → system_server | 平台决定，改不了 |
 * | T2→T3 | 马达物理 rise time（LRA 5–20ms、ERM 20–50ms） | 物理特性，只能选型 |
 *
 * 合并测就只能得到一个混合数字，而**决策规则是按 T0→T1 的占比写死的**：
 * `<5%` 关闭议题 / `5–15%` 先做语言层优化 / `≥15% 且 >1ms` 才重新评估下沉。
 *
 * ## 它不是可选的装饰
 *
 * 性能文档立的规矩是「**没有测量方法的性能指标不是指标，是装饰**」。
 * 这个接口就是那句话的落点：所有 SLO 都必须能从这里取到数。
 */
interface LatencyProbe {

    fun onSample(segment: Segment, nanos: Long)

    enum class Segment {
        /** T0→T1：facade 入口 → 决策管线产出 `Decision`。**纯软件，本库唯一能优化的部分**。 */
        DECISION,

        /** T1→T2：平台 API 调用往返（`vibrate` / `makePlayer`+`start`）。 */
        PLATFORM_SUBMIT,

        /** T0→T2：软件延迟合计。**注意它不等于前两段之和** —— 中间有调度排队。 */
        SOFTWARE_TOTAL,
    }

    companion object {
        /** 默认不采样。生产环境按需注入，避免无谓开销。 */
        val NOOP = object : LatencyProbe {
            override fun onSample(segment: Segment, nanos: Long) = Unit
        }
    }
}
