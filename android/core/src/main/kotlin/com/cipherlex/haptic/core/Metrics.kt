package com.cipherlex.haptic.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 生产指标出口 —— 对应主文档 **A.6.1** 与性能文档 **§四.1**。
 *
 * ## 为什么必须有它，而不是只有 debugDelegate
 *
 * > 「A.1 约束 3 要求"API 绝不抛异常"，所有错误被 try/catch 吞掉，而**唯一的错误
 * >   出口叫 `debugDelegate`** —— 名字就宣告了它是 debug 用的、生产不接。结果是
 * >   **生产环境触觉静默失败率完全不可观测**：用户报"点了没感觉"，你没有任何数据。
 * >   "零崩溃"是对的，"零信息"不是它的必然推论。」
 *
 * ## 触觉的失败分两类，这里主要防的是第二类
 *
 * | 类 | 表现 | 可观测性 |
 * |---|---|---|
 * | **硬失败** | 崩溃 / ANR / 资源泄漏 | 崩溃平台能抓，monkey 能压出来 |
 * | **软失败** | **不振动 / 振错了** | **看不见** —— 不崩、不报错、日志里啥也没有 |
 *
 * 而本库的设计（零崩溃、静默降级）**把绝大多数失败推进了第二类**。
 * 所以这个 sink 不是"锦上添花的埋点"，它是软失败的**唯一**观测手段。
 */
interface CipherHapticMetricsSink {
    /** 低频聚合上报，**非每次调用**。宿主接自有埋点。 */
    fun onSnapshot(snapshot: CipherHapticMetricsSnapshot)

    companion object {
        val NOOP = object : CipherHapticMetricsSink {
            override fun onSnapshot(snapshot: CipherHapticMetricsSnapshot) = Unit
        }
    }
}

/**
 * @property dropCountsByReason 被管线拦掉的次数，按原因分。**这是"用户说点了没感觉"
 *   时唯一能回答"为什么"的数据** —— disabled / system-off / dnd / degraded-to-silent
 *   / no-vibrator 是完全不同的产品问题，混在一起就查不出来。
 * @property degradeCountsByAction **实际发生的**降级动作分布，低端机触觉覆盖率大盘的来源。
 *   ⚠️ **不含 `full`** —— `full` 的含义是"没有降级"，记进来会让这个数恒等于总播放数，
 *   于是完全没有信息量（真机压测里就出现过 `{full=1641}` 而请求也正好 1641）。
 * @property failCount 平台调用抛错次数（`DeadObjectException` 等）。
 * @property leakSuspectCount **回收时仍持有资源的 handle 数**。正常恒为 0；
 *   非 0 即状态机存在抵达不了 `Reclaimed` 的路径 —— 这是 monkey / 压测唯一能抓到
 *   泄漏的信号，因为泄漏本身不崩溃。
 */
data class CipherHapticMetricsSnapshot(
    val hardwareClass: HardwareClass,
    val playRequestCount: Int,
    val playSubmittedCount: Int,
    val dropCountsByReason: Map<String, Int>,
    val degradeCountsByAction: Map<String, Int>,
    val preemptedCount: Int,
    val failCount: Int,
    val engineRestartCount: Int,
    val circuitOpenCount: Int,
    val leakSuspectCount: Int,
    val activeHandleCount: Int,
    val peakActiveHandleCount: Int,
) {
    /** 静默失败率 —— 请求了但没真的振动的比例。**大盘首要看这个数**。 */
    val silentFailureRate: Double
        get() = if (playRequestCount == 0) 0.0
                else (playRequestCount - playSubmittedCount).toDouble() / playRequestCount

    fun summary(): String = buildString {
        appendLine("硬件档 $hardwareClass  请求 $playRequestCount  提交 $playSubmittedCount")
        appendLine("静默失败率 %.1f%%".format(silentFailureRate * 100))
        if (dropCountsByReason.isNotEmpty()) appendLine("drop  $dropCountsByReason")
        appendLine(
            if (degradeCountsByAction.isEmpty()) "降级  无（全部满血播放）"
            else "降级  $degradeCountsByAction（占 %.1f%%）".format(
                degradeCountsByAction.values.sum() * 100.0 / maxOf(1, playSubmittedCount))
        )
        appendLine("抢占 $preemptedCount  失败 $failCount  活跃 $activeHandleCount(峰值 $peakActiveHandleCount)")
        append(if (leakSuspectCount == 0) "泄漏嫌疑 0 ✓" else "⚠️ 泄漏嫌疑 $leakSuspectCount")
    }
}

/** 线程安全的计数器聚合。所有写入都在 scheduler 串行线上，读取可跨线程。 */
class MetricsCollector(private val hardwareClass: () -> HardwareClass) {

    private val requests = AtomicInteger()
    private val submitted = AtomicInteger()
    private val preempted = AtomicInteger()
    private val fails = AtomicInteger()
    private val restarts = AtomicInteger()
    private val circuitOpens = AtomicInteger()
    private val leaks = AtomicInteger()
    private val peak = AtomicInteger()
    private val drops = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val degrades = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val activeNow = AtomicInteger()
    private val lastReportAt = AtomicLong(0)

    fun onRequest() = requests.incrementAndGet()
    fun onSubmitted() = submitted.incrementAndGet()
    fun onPreempted() = preempted.incrementAndGet()
    fun onFail() = fails.incrementAndGet()
    fun onEngineRestart() = restarts.incrementAndGet()
    fun onCircuitOpen() = circuitOpens.incrementAndGet()

    /** 回收时仍持有资源 —— 正常恒 0，非 0 即状态机有抵达不了 `Reclaimed` 的路径。 */
    fun onLeakSuspect() = leaks.incrementAndGet()

    fun onDrop(reason: String) {
        drops.getOrPut(reason) { AtomicInteger() }.incrementAndGet()
    }

    fun onDegrade(action: String) {
        degrades.getOrPut(action) { AtomicInteger() }.incrementAndGet()
    }

    fun onActiveCountChanged(n: Int) {
        activeNow.set(n)
        peak.updateAndGet { maxOf(it, n) }
    }

    fun snapshot() = CipherHapticMetricsSnapshot(
        hardwareClass = hardwareClass(),
        playRequestCount = requests.get(),
        playSubmittedCount = submitted.get(),
        dropCountsByReason = drops.mapValues { it.value.get() },
        degradeCountsByAction = degrades.mapValues { it.value.get() },
        preemptedCount = preempted.get(),
        failCount = fails.get(),
        engineRestartCount = restarts.get(),
        circuitOpenCount = circuitOpens.get(),
        leakSuspectCount = leaks.get(),
        activeHandleCount = activeNow.get(),
        peakActiveHandleCount = peak.get(),
    )

    /** 低频上报：距上次超过 [intervalMs] 才推。**不是每次调用都推** —— 那会变成噪音。 */
    fun maybeReport(sink: CipherHapticMetricsSink, nowMs: Long, intervalMs: Long = 60_000) {
        val last = lastReportAt.get()
        if (nowMs - last >= intervalMs && lastReportAt.compareAndSet(last, nowMs)) {
            sink.onSnapshot(snapshot())
        }
    }
}
