package com.cipherlex.haptic.engine

import com.cipherlex.haptic.core.AndroidTranslator
import com.cipherlex.haptic.core.ContinuousCoalescer
import com.cipherlex.haptic.core.HapticScheduler
import com.cipherlex.haptic.core.LatencyProbe
import com.cipherlex.haptic.core.MetricsCollector
import com.cipherlex.haptic.core.PlaybackActions
import com.cipherlex.haptic.core.PlaybackFsm
import com.cipherlex.haptic.core.ResolvedWaveform
import com.cipherlex.haptic.core.WaveKind

/**
 * 一次播放的句柄 —— 对应「句柄状态机」§一。
 *
 * > 「规格 11 项能力中有 5 项物理上依赖**持有 player 句柄**。」
 *
 * Android 侧的 `vibrate()` **无返回值**（P-11），所以这里是个**虚拟句柄**：
 * 自维护排程状态 + 预计结束时刻 + 取消标志。对上层语义与 iOS 的真句柄一致。
 *
 * ## 它持有什么（这些就是"泄漏"指的东西）
 *
 * | 资源 | 释放点 |
 * |---|---|
 * | wake lock（Android 独有，P-10） | `release` 动作 |
 * | end-timer / idle-timer / keepAlive-timer | `release` 动作 |
 * | coalescer 的补发块 | `release` 动作 |
 *
 * **全部且仅在进入 `Reclaimed` 时释放。** 任何抵达不了 `Reclaimed` 的状态路径都是泄漏
 * —— 这正是状态机不变式 1 存在的理由，也是为什么 `FAIL` 必须在三态都有出口。
 */
class PlaybackHandle(
    val id: Long,
    val resolved: ResolvedWaveform,
    private val scheduler: HapticScheduler,
    private val gateway: VibratorGateway,
    private val wakeLock: WakeLockGateway,
    private val useComposition: Boolean,
    private val probe: LatencyProbe = LatencyProbe.NOOP,
    private val metrics: MetricsCollector? = null,
    private val onLog: (String) -> Unit = {},
) {
    /** 状态机。动作由本类注入（[actions]）。 */
    lateinit var fsm: PlaybackFsm
        private set

    // ⚠️ 第二个参数 sharpness 在此端被【丢弃】—— Android 没有这个维度，振幅是唯一
    //    输出（P-04）。刻意不传进 sendContinuous：让"这个值到此为止"成为签名上的事实，
    //    而不是实现里一个静默忽略的形参。sharpness 的双端不等价由 D 类契约暴露。
    val coalescer = ContinuousCoalescer(scheduler) { i, _ -> sendContinuous(i) }

    private var endTimer: HapticScheduler.Cancellable? = null
    private var idleTimer: HapticScheduler.Cancellable? = null
    private var keepAliveTimer: HapticScheduler.Cancellable? = null
    private var graceTimer: HapticScheduler.Cancellable? = null
    private var wakeLockHeld = false

    /** 供抢占策略读取（§8.2 `activeSnapshot`）。 */
    val state: String get() = fsm.state

    /** 宿主（facade）在此接收"已回收"通知。**与本类自己的状态副作用分开** —— 见 attach。 */
    var onReclaimed: (() -> Unit)? = null

    fun attach(fsm: PlaybackFsm) {
        this.fsm = fsm
        // Completed / Cancelled 都要排 grace，但迁移表里 NATURAL_END→Completed 的
        // action 是 none（状态机只管"自己怎么活怎么死"，不管资源）。故在此挂进入终态的
        // 观察点，而不是往迁移表里塞 action —— 保持"迁移表是纯逻辑"。
        //
        // ⚠️ **这里曾被 facade 覆盖掉，造成真泄漏**：onStateEntered 是单槽回调，
        //    facade 也想用它做 retire，后设置的把前面的顶掉了。结果是【自然播完】
        //    的效果永远排不上 grace 定时器 → 永远停在 Completed → 永不 Reclaimed。
        //    CANCEL 路径没事（grace 由 stop 动作排），所以只有自然结束会泄漏。
        //    JVM 测试没抓到，因为测试【手动发了 GRACE_EXPIRED】—— 又一次"手动补发的
        //    事件掩盖了没人产生这个事件"。真机压测 500 次后才现形。
        //
        //    现在本类只用一个 onStateEntered，宿主改用 [onReclaimed]，不再抢同一个槽。
        fsm.onStateEntered = { st ->
            when (st) {
                "Completed", "Cancelled" -> startGraceTimer()
                "Reclaimed" -> onReclaimed?.invoke()
            }
        }
    }

    /**
     * `PlaybackActions` 的 Android 实现 —— **各端唯一不共用的部分**。
     *
     * `submit` / `resubmit` **必须回报 `SUBMIT_OK` 或 `FAIL`，不得静默返回**：
     * 静默会让 handle 永久停在 `Submitting`，等价于 player / wake lock 泄漏。
     */
    val actions = object : PlaybackActions {
        override fun invoke(action: String) {
            when (action) {
                "submit", "resubmit" -> doSubmit()
                "startEndTimer" -> startEndTimer()
                "startIdleTimer" -> startIdleTimer()
                "bufferParams" -> Unit          // coalescer 已在 facade 侧记录，此处无副作用
                "applyParams" -> restartIdleTimer()
                "startKeepAlive" -> startKeepAlive()
                "clearKeepAlive" -> { keepAliveTimer?.cancel(); keepAliveTimer = null }
                "suspend" -> { stopMotor(); cancelEndTimer() }
                "stop" -> { stopMotor(); cancelAllTimers(); startGraceTimer() }
                "report" -> {
                    metrics?.onFail()
                    onLog("FAIL ${resolved.semanticId}")
                }
                "release" -> release()
                "none" -> Unit
                else -> error("未知动作：$action —— PlaybackActions 与迁移表脱节了")
            }
        }
    }

    // ── 平台提交 ────────────────────────────────────────────────────

    private fun doSubmit() {
        val t1 = System.nanoTime()
        try {
            acquireWakeLockIfNeeded()
            if (resolved.kind == WaveKind.CONTINUOUS) {
                // v4.3：起播强度取 coalescer 的 latest，【不是】IR 的 initialIntensity。
                // 后者只是"从未收到过 UPDATE 时的兜底值"（§4.6）。
                val c = resolved.continuous!!
                val (i, _) = coalescer.latest() ?: (c.initialIntensity to c.initialSharpness)
                sendContinuous(i)
                // 起播这一发绕过了 coalescer，必须补登记，否则节流从第二次才生效
                coalescer.markSentAt(scheduler.nowMs())
            } else if (useComposition) {
                gateway.vibrateComposition(AndroidTranslator.toComposition(resolved))
            } else {
                val w = AndroidTranslator.toWaveform(resolved)
                gateway.vibrateWaveform(
                    w.timings.map { it.toLong() }.toLongArray(),
                    w.amplitudes.toIntArray(),
                    w.repeat,
                )
            }
            // T1→T2：平台调用往返。这段是 binder IPC，改不了 —— 但要量出来，
            // 才知道 T0→T1 在整体里占多少（V5 §6.2b 的决策规则按占比写死）。
            probe.onSample(LatencyProbe.Segment.PLATFORM_SUBMIT, System.nanoTime() - t1)
            fsm.send("SUBMIT_OK")
        } catch (e: Throwable) {
            // 「API 绝不抛异常」的物理保证：一切平台调用 try/catch 全包。
            // 但失败必须【可观测】—— 走 FAIL 事件 + 指标，不是静默吞掉（性能 §四.1）。
            onLog("submitFail ${resolved.semanticId} ← ${e::class.simpleName}")
            fsm.send("FAIL")
        }
    }

    /**
     * Android 无 continuous 语义：以"恒振幅分段 + 参数变化时取消当前段重排"近似（P-01）。
     *
     * **不收 sharpness** —— Android 没有这个维度（P-04）。把它挡在签名外，
     * 比在实现里静默忽略一个形参诚实。
     */
    private fun sendContinuous(intensity: Float) {
        val seg = resolved.continuous?.segmentMs ?: return
        val amp = Math.round(intensity * 255).coerceIn(1, 255)
        gateway.vibrateWaveform(longArrayOf(seg.toLong()), intArrayOf(amp), -1)
    }

    private fun stopMotor() {
        // ⚠️ cancelAll 是【全局】的（P-03）。调用方须先确认本 handle 是唯一活跃振动，
        // 否则应走"标记到点即停"路径 —— 该判断在 facade 的 activeHandles 处做。
        gateway.cancelAll()
    }

    // ── 定时器 ──────────────────────────────────────────────────────

    private fun startEndTimer() {
        cancelEndTimer()
        endTimer = scheduler.schedule(resolved.totalDurationMs.toLong()) {
            fsm.send("NATURAL_END")
        }
    }

    private fun startIdleTimer() {
        idleTimer?.cancel()
        val timeout = resolved.continuous?.idleTimeoutMs?.toLong() ?: return
        idleTimer = scheduler.schedule(timeout) { fsm.send("NATURAL_END") }
    }

    private fun restartIdleTimer() = startIdleTimer()

    /**
     * grace 窗口 —— 进入 `Completed` / `Cancelled` 后排，到期发 `GRACE_EXPIRED` 回收。
     *
     * ⚠️ **这条曾整个漏掉**：`GRACE_EXPIRED` 是迁移表里的事件，但没有任何动作排它的
     * 定时器，于是 handle 永远停在 `Cancelled`，`activeHandles` 清不掉 —— 正是不变式 1
     * 要防的泄漏。fuzz 没抓到是因为收尾时我手动发了 `GRACE_EXPIRED`，**把缺失的
     * 定时器替代掉了**；facade 层的 `stopAllEffects` 一测就露馅。
     *
     * 教训：fuzz 手动补发的事件会掩盖"没人产生这个事件"这类缺陷。
     */
    private fun startGraceTimer() {
        graceTimer?.cancel()
        graceTimer = scheduler.schedule(GRACE_MS) { fsm.send("GRACE_EXPIRED") }
    }

    private fun startKeepAlive() {
        keepAliveTimer?.cancel()
        keepAliveTimer = scheduler.schedule(KEEPALIVE_MS) { fsm.send("CANCEL") }
    }

    private fun cancelEndTimer() { endTimer?.cancel(); endTimer = null }

    private fun cancelAllTimers() {
        cancelEndTimer()
        idleTimer?.cancel(); idleTimer = null
        keepAliveTimer?.cancel(); keepAliveTimer = null
    }

    private fun cancelEveryTimer() {
        cancelAllTimers()
        graceTimer?.cancel(); graceTimer = null
    }

    // ── wake lock ───────────────────────────────────────────────────

    private fun acquireWakeLockIfNeeded() {
        // ⚠️ P-10：这道防线的【有效性本身待验证】（V3）。vibrate() 实际执行在
        // system_server 的 VibratorService，它自己持 wake lock，app 侧再持
        // partial wake lock 对已提交的振动可能完全无用。
        // V3 若实测无差异，删掉的是这一处 + WakeLockGateway，状态机不用动。
        if (!wakeLockHeld && wakeLock.shouldHold(resolved)) {
            wakeLock.acquire()
            wakeLockHeld = true
        }
    }

    /** **唯一的资源释放点。** finally 语义 —— 但"绝不泄漏"依赖状态机可达性，不是它本身。 */
    private fun release() {
        cancelEveryTimer()
        coalescer.reset()
        if (wakeLockHeld) {
            wakeLock.release()
            wakeLockHeld = false
        }
    }

    /** 测试断言用：是否还持有任何资源。 */
    fun anyResourceHeld(): Boolean =
        wakeLockHeld || endTimer != null || idleTimer != null ||
            keepAliveTimer != null || graceTimer != null

    companion object {
        /**
         * critical 后台保活窗口。
         * ⚠️ **待实测**（性能 §5.4）；且 iOS 侧可行性本身未定（P-06 / V1）。
         */
        const val KEEPALIVE_MS = 30_000L

        /**
         * grace 窗口：播完/取消后不立即释放，留时间让马达制动收尾（§五.2）。
         * ⚠️ **待实测**（性能 §5.4）—— v2 写"如 50ms"，依据是"马达有几十 ms 制动"，
         * 但 LRA 主动制动通常远短于此。
         */
        const val GRACE_MS = 50L
    }
}

/**
 * wake lock 抽象。抽出来有两个理由：
 * ① JVM 单测能断言"取了必还"；
 * ② **V3 若实测这道防线无效，删掉的是一个实现类，不是散落各处的调用**（性能 §一）。
 */
interface WakeLockGateway {
    /** 按【场景】判定，不按时长 —— 时长阈值是错误的判断维度（性能 §一 v1.2.0 修正）。 */
    fun shouldHold(resolved: ResolvedWaveform): Boolean
    fun acquire()
    fun release()
}
