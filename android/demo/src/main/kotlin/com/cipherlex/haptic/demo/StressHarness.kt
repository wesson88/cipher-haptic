package com.cipherlex.haptic.demo

import com.cipherlex.haptic.CipherHaptic
import com.cipherlex.haptic.CipherHapticSemantic
import kotlin.random.Random

/**
 * 真机压测夹具 —— **给 monkey 补上它缺的那只"眼睛"**。
 *
 * ## 为什么 monkey 单独用处有限
 *
 * 触觉的失败分两类，monkey 只能看见第一类：
 *
 * | 类 | 表现 | monkey 能否发现 |
 * |---|---|---|
 * | **硬失败** | 崩溃 / ANR | ✅ 这正是 monkey 的设计目标 |
 * | **软失败** | **不振动 / 振错强度 / 振错时序** | ❌ **完全看不见** |
 *
 * 而本库的设计约束（A.1 约束 3：零崩溃、API 绝不抛异常、静默降级）**主动把绝大多数
 * 失败推进了第二类**。所以对这个库跑 monkey，天然处在"最容易全绿、也最没信息量"
 * 的位置 —— 它不崩，不代表它对。
 *
 * ## 这个夹具补的是"判定依据"（oracle）
 *
 * monkey 缺的不是操作量，是**断言**。本夹具在随机操作之后检查三条运行时不变式：
 *
 * 1. **静息后活跃 handle 归零** —— 抵达不了 `Reclaimed` 的路径 = player/wake lock 泄漏
 * 2. **`leakSuspectCount == 0`** —— 回收时仍持有资源的次数
 * 3. **静默失败率不异常** —— 请求了但没提交的比例；突然升高说明降级或拦截出了问题
 *
 * 前两条是 monkey 永远测不出来的（泄漏不崩溃），第三条是软失败的唯一观测口。
 *
 * ## 与 JVM fuzz 的分工
 *
 * | | 覆盖什么 |
 * |---|---|
 * | JVM fuzz（55 个测试里的两份） | 迁移表 + 实现逻辑，假时钟、假 gateway、可跑 5 万轮 |
 * | **本夹具** | **真 `Vibrator`、真 binder IPC、真 `HandlerThread`、真时间** |
 *
 * JVM fuzz 抓不到的：binder 抛 `DeadObjectException`、系统繁忙导致的调度抖动、
 * 真实定时器精度、省电模式介入。这些只有真机上跑才会出现。
 */
class StressHarness(
    private val haptic: CipherHaptic,
    private val log: (String) -> Unit,
) {
    /**
     * 随机操作序列。**故意包含大量非法/边界组合** —— 重复 end、无 begin 就 update、
     * 播放中途改 scale、连点、抢占风暴。
     */
    fun burst(ops: Int, seed: Int = 42) {
        val rng = Random(seed)
        val semantics = CipherHapticSemantic.entries
        repeat(ops) {
            when (rng.nextInt(10)) {
                0, 1, 2, 3 -> haptic.playEffect(semantics[rng.nextInt(semantics.size)])
                4 -> haptic.playEffect(semantics[rng.nextInt(semantics.size)], onNextFrame = true)
                5 -> haptic.playLoopingEffect(semantics[rng.nextInt(semantics.size)])
                6 -> haptic.updateContinuousEffect(rng.nextFloat(), rng.nextFloat())
                7 -> haptic.endContinuousEffect()      // 可能没有正在进行的通道 —— 故意的
                8 -> haptic.stopAllEffects()
                9 -> haptic.setGlobalScale(rng.nextFloat())
            }
        }
        haptic.setGlobalScale(1f)                       // 复位，免得影响后续手动试听
    }

    /**
     * 静息后检查不变式。**必须在 burst 之后等足够久再调**（让 grace / idle 超时到期），
     * 否则会把"还没到期"误判成泄漏。
     *
     * @return 违规清单，空 = 通过
     */
    fun assertInvariants(): List<String> {
        val m = haptic.metricsSnapshot()
        val fails = mutableListOf<String>()

        if (m.activeHandleCount != 0) {
            fails += "活跃 handle 未归零：${m.activeHandleCount} —— " +
                "存在抵达不了 Reclaimed 的路径，即 player / wake lock 泄漏"
        }
        if (m.leakSuspectCount != 0) {
            fails += "回收时仍持有资源 ${m.leakSuspectCount} 次 —— 状态机可达性不完备"
        }
        // 软失败率：drop 是正常的（master 关、DND、silent 降级），但要能解释得清。
        // 这里只在"没有任何已知 drop 原因却大量失败"时报警。
        val unexplained = m.playRequestCount - m.playSubmittedCount -
            m.dropCountsByReason.values.sum()
        if (unexplained > 0) {
            fails += "有 $unexplained 次请求既没提交也没记录 drop 原因 —— " +
                "这正是「用户说点了没感觉、日志里啥也没有」的形态"
        }
        return fails
    }

    fun report(): String = haptic.metricsSnapshot().summary()

    /** 一轮完整压测：burst → 等静息 → 断言。 */
    fun runRound(ops: Int, quiesceMs: Long, onDone: (List<String>) -> Unit) {
        log("压测开始：$ops 次随机操作")
        burst(ops)
        log("已发送，等待 ${quiesceMs}ms 静息（让 grace / idle 超时到期）")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val fails = assertInvariants()
            log(if (fails.isEmpty()) "✓ 不变式全部通过" else "✗ ${fails.size} 条违规")
            fails.forEach { log("  · $it") }
            onDone(fails)
        }, quiesceMs)
    }
}
