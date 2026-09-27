package com.cipherlex.haptic

import android.content.Context
import com.cipherlex.haptic.core.ApiGate
import com.cipherlex.haptic.core.Decision
import com.cipherlex.haptic.core.DecisionPipeline
import com.cipherlex.haptic.core.HapticScheduler
import com.cipherlex.haptic.core.CipherHapticMetricsSink
import com.cipherlex.haptic.core.LatencyProbe
import com.cipherlex.haptic.core.MetricsCollector
import com.cipherlex.haptic.core.HardwareClass
import com.cipherlex.haptic.core.PipelineContext
import com.cipherlex.haptic.core.PlayOpts
import com.cipherlex.haptic.core.PreemptionPolicy
import com.cipherlex.haptic.core.PlaybackFsm
import com.cipherlex.haptic.core.SpecLoader
import com.cipherlex.haptic.core.SystemMute
import com.cipherlex.haptic.core.TransitionTable
import com.cipherlex.haptic.engine.AndroidFrameClock
import com.cipherlex.haptic.engine.AndroidHapticScheduler
import com.cipherlex.haptic.engine.AndroidVibratorGateway
import com.cipherlex.haptic.engine.AndroidWakeLock
import com.cipherlex.haptic.engine.HardwareClassProbe
import com.cipherlex.haptic.engine.PlaybackHandle
import com.cipherlex.haptic.engine.VibratorGateway
import com.cipherlex.haptic.engine.WakeLockGateway
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 帧时钟 —— 接口 2 `onNextFrame` 的落点。
 *
 * 抽成接口的理由与 [VibratorGateway] 相同：`Choreographer` 是平台 API，
 * 抽出来后"下一帧提交"这个语义能在 JVM 单测里断言，不必上真机。
 *
 * ⚠️ 它**只保证"在下一 VSync 边界提交"**，不保证绝对时刻——这正是 v1.3.0 砍掉
 * `playEffect(at: hostTime)` 的原因：为一个 Android 物理拿不到的精度，
 * 付双端全额复杂度（P-02 / P-19）。
 */
interface FrameClock {
    fun postFrameCallback(task: () -> Unit)
}

/** 语义 token。case 集合的权威来源是 `semantics.yaml` 的 key（CI 规则 7）。 */
enum class CipherHapticSemantic(val id: String) {
    ITEM_DISSOLVE("item.dissolve"),
    ITEM_DETACH("item.detach"),
    SELECTION_SNAP("selection.snap"),
    CONTROL_TAP("control.tap"),
    GESTURE_TRACK("gesture.track"),
    NOTIFY_MESSAGE("notify.message"),
    SECURITY_ALARM("security.alarm"),
    SECURITY_INTRUSION("security.intrusion"),
}

/** 播放前的可用性预览（接口 8）—— 降级闭环的另一半，让上层能补偿。 */
data class CipherHapticAvailability(
    val willPlay: Boolean,
    val degradedTo: String?,
    val reason: String?,
)

/** D 类差异在契约层的强制出口 —— 字段集 = Parity Ledger 的 D 类条目集。 */
data class CipherHapticCapabilities(
    val hardwareClass: HardwareClass,
    /** P-04：Android 恒为 false —— 没有 sharpness 维度 */
    val supportsSharpness: Boolean,
    /** P-06：**待 V1 真机验证**，当前保守返回 false */
    val supportsBackgroundPlayback: Boolean,
    /** P-14：系统级触觉总开关 */
    val systemHapticsEnabled: Boolean,
)

enum class CipherHapticEngineState { IDLE, RUNNING, RECOVERING, CIRCUIT_OPEN }

interface CipherHapticCancelToken {
    fun cancel()
    val isCancelled: Boolean
    /** v1.2.0 新增：自然播完的 token `isCancelled == false`，业务方无法区分"还在播"与"已播完"。 */
    val isFinished: Boolean
}

enum class MuteState { UNMUTED, DND, HARDWARE_MUTED }

interface MuteStateObserver {
    fun onMuteStateChanged(state: MuteState)
}

interface CipherHapticDebugDelegate {
    fun onStateChanged(state: String)
    fun onDegraded(semantic: String, action: String)
    fun onDropped(semantic: String, reason: String)
}

/**
 * CipherHaptic facade —— **对外能力的唯一入口**（主文档 A.2，17 方法 / 11 项能力）。
 *
 * ## 线程契约
 *
 * 所有方法 `marshal` 到单一串行 [HapticScheduler]；所有同步 getter 读 atomic 快照。
 * **决策 + 抢占 + 提交是不可分的 critical section**（§七.3）——一旦分裂，
 * cancel 可能在"创建后、提交前"到达，导致状态不一致。
 *
 * ## 所有方法均不抛异常
 *
 * 硬件不可用时静默降级或丢弃（A.1 约束 3）。但**失败必须可观测**：
 * 走 [debugDelegate]（逐事件、开发期）与指标出口（聚合、生产期）两条路，
 * 而不是静默吞掉 —— "零崩溃"是对的，"零信息"不是它的必然推论。
 */
class CipherHaptic internal constructor(
    private val loader: SpecLoader,
    private val scheduler: HapticScheduler,
    private val gateway: VibratorGateway,
    private val wakeLock: WakeLockGateway,
    private val probe: HardwareClassProbe,
    private val frameClock: FrameClock,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,
    private val probe2: LatencyProbe = LatencyProbe.NOOP,
    private val metricsSink: CipherHapticMetricsSink = CipherHapticMetricsSink.NOOP,
) {
    private val metrics = MetricsCollector { probe.hardwareClass }
    private val table = TransitionTable.from(loader.transitions)
    private val handles = LinkedHashMap<Long, PlaybackHandle>()
    private val nextId = AtomicLong(1)

    // 同步 getter 读 atomic 快照（A.2 回调线程契约）
    private val masterEnabled = AtomicBoolean(true)
    private val scaleBits = AtomicInteger(java.lang.Float.floatToIntBits(1f))
    private val muteState = java.util.concurrent.atomic.AtomicReference(MuteState.UNMUTED)
    private val observers = java.util.concurrent.CopyOnWriteArrayList<MuteStateObserver>()

    var debugDelegate: CipherHapticDebugDelegate? = null

    /** 连续通道：**全局单例**（P-20 登记的显式取舍，非遗漏）。 */
    private var continuousHandle: PlaybackHandle? = null

    // ── 核心控制（6 方法）────────────────────────────────────────────

    fun playEffect(semantic: CipherHapticSemantic) = submitPlay(semantic, onNextFrame = false)

    /**
     * 接口 2：**只承诺"在下一个 VSync 边界提交硬件命令"，不承诺绝对时刻精度**。
     *
     * iOS 侧会用 `start(atTime:)` 拿到硬件级落点（质量向强端保留），Android 侧只能在
     * `Choreographer` 回调里直接 `vibrate`，再经 binder IPC → system_server → HAL，
     * 是**调度级**。这是登记在案的 B 类残差 **P-02**，不是 bug。
     *
     * 已在当前帧内调用时：本帧直接提交，不再空等一帧。
     */
    fun playEffect(semantic: CipherHapticSemantic, onNextFrame: Boolean) =
        submitPlay(semantic, onNextFrame)

    /**
     * 接口 3：循环播放。**循环多久由应用告知**（v1.4.0，2026-09-27 定案）——告警该响多久是业务决策；
     * 库只兜底 [MAX_LOOP_DURATION_MS]（超过按它截断），`maxDurationMs ≤ 0` 则 drop（`looping-needs-duration`）。
     *
     * 每一轮提交一次**有限**波形，由引擎按 `totalDurationMs`（含 `loopGapMs`）重提交下一轮
     * （状态机 §4.7 方案 B）。所以即使重提交失败、或进程被系统冻结，最多也只残留一轮。
     * 到期或 `token.cancel()` 都会结束循环。
     */
    fun playLoopingEffect(semantic: CipherHapticSemantic, maxDurationMs: Long): CipherHapticCancelToken {
        val token = HandleToken()
        scheduler.submit {
            startPlayback(semantic, opts = PlayOpts(loopMaxDurationMs = maxDurationMs))
                ?.let { token.bind(it) } ?: token.markFinished()
        }
        return token
    }

    fun stopAllEffects() = scheduler.submit {
        handles.values.toList().forEach { it.fsm.send("CANCEL") }
        sweep()
    }

    fun updateContinuousEffect(intensity: Float, sharpness: Float) = scheduler.submit {
        val existing = continuousHandle
        if (existing == null) {
            // 首次调用：起播。参数在 SUBMIT 之前塞进 coalescer，由 submit 取用（§4.6）
            startContinuous(intensity, sharpness)
            return@submit
        }
        // v4.3：平台就绪前只缓冲（不碰平台、不动 idle-timer），就绪后才 trailing coalesce
        if (existing.fsm.state == "Active") {
            existing.coalescer.update(intensity, sharpness)
        } else {
            existing.coalescer.buffer(intensity, sharpness)
        }
        existing.fsm.send("UPDATE")
    }

    fun endContinuousEffect() = scheduler.submit {
        continuousHandle?.let {
            it.coalescer.flushPending()          // §七.5 末句：结束前必须 flush
            it.fsm.send("CANCEL")
        }
        continuousHandle = null
        sweep()
    }

    // ── 预热与可用性（2 方法）───────────────────────────────────────

    fun prepare(semantic: CipherHapticSemantic) = scheduler.submit {
        // Android 侧 Vibrator 获取廉价、无"启动"概念，故 prepare 只做数据预热
        loader.resolve(semantic.id, probe.hardwareClass, globalScale())
        Unit
    }

    fun preview(semantic: CipherHapticSemantic): CipherHapticAvailability {
        if (!masterEnabled.get()) return CipherHapticAvailability(false, null, "disabled")
        if (!probe.vibratorAvailable) return CipherHapticAvailability(false, null, "no-vibrator")
        val rw = loader.resolve(semantic.id, probe.hardwareClass, globalScale())
            ?: return CipherHapticAvailability(false, "silent", "degraded-to-silent")
        val action = rw.degradeTrace.firstOrNull()
        return CipherHapticAvailability(true, if (action == "full") null else action, null)
    }

    // ── 配置 setter / getter（4 方法）───────────────────────────────

    fun setHapticsEnabled(enabled: Boolean) {
        masterEnabled.set(enabled)
        if (!enabled) stopAllEffects()
    }

    fun isHapticsEnabled(): Boolean = masterEnabled.get()

    /** ⚠️ P-04：`scale` 只缩放 intensity。Android 无 sharpness 维度，双端手感**不等价**。 */
    fun setGlobalScale(scale: Float) {
        scaleBits.set(java.lang.Float.floatToIntBits(scale.coerceIn(0f, 1f)))
    }

    fun globalScale(): Float = java.lang.Float.intBitsToFloat(scaleBits.get())

    // ── 系统状态（5 方法）──────────────────────────────────────────

    fun syncSystemMuteState(): MuteState = muteState.get()

    fun registerMuteObserver(observer: MuteStateObserver) { observers += observer }

    /** v1.2.0 补 —— 原来只能注册不能注销，必然泄漏。 */
    fun unregisterMuteObserver(observer: MuteStateObserver) { observers -= observer }

    fun hardwareCapabilities() = CipherHapticCapabilities(
        hardwareClass = probe.hardwareClass,
        supportsSharpness = false,                 // P-04：Android 恒为 false
        supportsBackgroundPlayback = false,        // P-06：保守值，待 V1 真机验证
        systemHapticsEnabled = muteState.get() == MuteState.UNMUTED,
    )

    fun engineState(): CipherHapticEngineState =
        if (handles.isEmpty()) CipherHapticEngineState.IDLE
        else CipherHapticEngineState.RUNNING

    // ── 内部：决策管线 ⓪→⑦ ─────────────────────────────────────────

    private fun submitPlay(semantic: CipherHapticSemantic, onNextFrame: Boolean) {
        val go = { scheduler.submit { startPlayback(semantic); Unit } }
        if (onNextFrame) frameClock.postFrameCallback(go) else go()
    }

    /**
     * 决策管线的**快照**：决策所需的全部外部状态一次取齐（B.3 / 骨架 §3.3）。
     * 决策本身是 [DecisionPipeline.decide] 纯函数，facade 不再内联任何决策步骤。
     */
    private fun snapshot() = PipelineContext(
        masterEnabled = masterEnabled.get(),
        // ⚠️ P-14 系统触觉总开关的平台监听尚未接入（代码审查 C1，待排期）。字段先立起来：
        //    管线第 ② 步已按它 drop，接上监听后这里换成真实值即可，决策逻辑不用改。
        systemHapticsEnabled = true,
        mute = when (muteState.get()) {
            MuteState.UNMUTED -> SystemMute.NONE
            MuteState.DND -> SystemMute.DND
            MuteState.HARDWARE_MUTED -> SystemMute.HARDWARE
        },
        globalScale = globalScale(),
        hardwareClass = probe.hardwareClass,
        apiGate = ApiGate(gateway.sdkInt, probe.canUseComposition(intArrayOf(PRIMITIVE_CLICK))),
    )

    private fun activeSnapshot(): List<PreemptionPolicy.ActiveHandleInfo> {
        sweep()
        return handles.values.map {
            PreemptionPolicy.ActiveHandleInfo(
                id = it.id,
                category = it.resolved.category,
                kind = it.resolved.kind,
                elapsedMs = scheduler.nowMs() - (startedAt[it.id] ?: scheduler.nowMs()),
                protectedFromPreemption = it.resolved.protectedFromPreemption,
                state = it.fsm.state,
            )
        }
    }

    /**
     * 取快照 → 纯函数决策 → 执行。**决策 + 抢占 + 提交在同一个 scheduler 任务里**，
     * 是不可分的 critical section（§七.3）。
     */
    private fun startPlayback(
        semantic: CipherHapticSemantic,
        preSubmit: (PlaybackHandle) -> Unit = {},
        opts: PlayOpts = PlayOpts(),
    ): PlaybackHandle? {
        val t0 = System.nanoTime()
        metrics.onRequest()
        val decision = DecisionPipeline.decide(
            semantic.id, opts, snapshot(), loader, activeSnapshot(), capacity, coalesceWindowMs,
        )
        val play = when (decision) {
            is Decision.Drop -> return drop(semantic, decision.reason)
            is Decision.Play -> decision
        }
        val rw = play.resolved
        // ⚠️ `full` 的含义是【没有降级】。把它记进 degradeCountsByAction 会让该指标
        //    恒等于总播放数 —— 真机压测里就是 {full=1641} 而请求正好也是 1641，
        //    这个数完全没有信息量。它要回答的是"多少效果被降级了"，不是"播了多少次"。
        //    且只在 Play 时记：此前 looping-needs-token 这类 drop 也先被记成了「已降级」。
        rw.degradeTrace.firstOrNull()?.takeIf { it != "full" }?.let {
            metrics.onDegrade(it)
            debugDelegate?.onDegraded(semantic.id, it)
        }
        // ⑥ 执行抢占 —— 目标由管线算好，执行是发 CANCEL（§8.2）
        play.preemptTargets.forEach { metrics.onPreempted(); handles[it]?.fsm?.send("CANCEL") }

        // ── T0→T1 采样：决策管线到此结束（V5 §6.2b）──────────────────
        // 这一段是【本库唯一能优化的部分】，也是"是否下沉 C++"唯一相关的度量。
        probe2.onSample(LatencyProbe.Segment.DECISION, System.nanoTime() - t0)

        // ⑦ submit —— handle 创建 + 平台提交在同一 critical section 内（§七.3）
        //    表达形式与循环时长都来自 Decision：engine 只照单执行，不再判断（B.1 / 规则 9）
        val h = PlaybackHandle(
            id = nextId.getAndIncrement(), resolved = rw, scheduler = scheduler,
            gateway = gateway, wakeLock = wakeLock, form = play.form,
            loopDeadlineMs = play.loopDeadlineMs, probe = probe2, metrics = metrics,
        ) {
            debugDelegate?.onStateChanged(it)
        }
        // ⚠️ 回收必须是【推送式】：Reclaimed 由 grace 定时器驱动，而 sweep() 是拉取式的
        //    —— 没有下一次业务调用时，回收后的 handle 会一直挂在 activeHandles 里。
        // ⚠️ 且【不能】直接设 fsm.onStateEntered —— 那个槽归 PlaybackHandle 自己
        //    （它要用来排 grace 定时器）。抢占它会让自然播完的效果永不回收。
        h.onReclaimed = { retire(h.id) }
        h.attach(PlaybackFsm(table, rw.kind, rw.category, h.actions))
        handles[h.id] = h
        startedAt[h.id] = scheduler.nowMs()
        preSubmit(h)                       // continuous 在此塞入手指当前位置
        h.fsm.send("SUBMIT")
        probe2.onSample(LatencyProbe.Segment.SOFTWARE_TOTAL, System.nanoTime() - t0)
        if (h.fsm.state == "Active") metrics.onSubmitted() else metrics.onFail()
        sweep()
        metrics.onActiveCountChanged(handles.size)
        metrics.maybeReport(metricsSink, scheduler.nowMs())
        return h
    }

    /**
     * 起播连续通道。**参数必须在 `SUBMIT` 之前进 coalescer** —— `submit` 动作会取
     * `latest()` 作为起播强度，取不到才回落 IR 的 `initialIntensity`（§4.6）。
     * 顺序反了就会"按下去拖了一段才起震，且第一下强度对不上手指位置"。
     */
    private fun startContinuous(intensity: Float, sharpness: Float): PlaybackHandle? =
        startPlayback(CipherHapticSemantic.GESTURE_TRACK, preSubmit = { h ->
            h.coalescer.buffer(intensity, sharpness)
            continuousHandle = h
        })

    /**
     * 管线拦截。**每一次 drop 都必须被计数** —— 这是"用户说点了没感觉"时唯一能
     * 回答"为什么"的数据。disabled / system-off / dnd / degraded-to-silent /
     * no-vibrator 是完全不同的产品问题，混在一起就查不出来。
     */
    private fun drop(semantic: CipherHapticSemantic, reason: String): PlaybackHandle? {
        metrics.onDrop(reason)
        debugDelegate?.onDropped(semantic.id, reason)
        return null
    }

    /** 取当前指标快照。调音台与宿主埋点都从这里读。 */
    fun metricsSnapshot() = metrics.snapshot()

    /**
     * 活跃 handle 的**状态分布**。压测发现"handle 未归零"时，光知道个数没用 ——
     * 卡在 `Active`（looping 未取消，正常）与卡在 `Submitting`（平台回调丢了，泄漏）
     * 是完全不同的两件事。
     */
    fun debugHandleStates(): Map<String, Int> =
        handles.values.groupingBy { "${it.fsm.state}/${it.resolved.kind}" }.eachCount()

    private val startedAt = HashMap<Long, Long>()

    /**
     * handle 进入 `Reclaimed` 时把它摘出表 —— 由 [PlaybackFsm.onStateEntered] 推送触发。
     *
     * grace 中的 `Completed` / `Cancelled` **仍留在表里，但不占容量槽**
     * （占槽判定见 [PreemptionPolicy]，只数 `Active` + `Paused`）。
     */
    private fun retire(id: Long) {
        // 回收时仍持有资源 = 状态机有抵达不了 Reclaimed 的路径。正常恒 0。
        // 这是压测/monkey 唯一能抓到【泄漏】的信号 —— 泄漏本身不崩溃、不报错。
        handles[id]?.let { if (it.anyResourceHeld()) metrics.onLeakSuspect() }
        handles.remove(id)
        startedAt.remove(id)
        if (continuousHandle?.id == id) continuousHandle = null
        metrics.onActiveCountChanged(handles.size)
    }

    /** 兜底清理：正常路径靠 [retire] 推送，这里只防御性地扫一遍。 */
    private fun sweep() {
        handles.filterValues { it.fsm.state == "Reclaimed" }.keys.toList().forEach { retire(it) }
    }

    private inner class HandleToken : CipherHapticCancelToken {
        private var handle: PlaybackHandle? = null
        private var cancelledFlag = false
        private var finishedFlag = false

        fun bind(h: PlaybackHandle) { handle = h }
        fun markFinished() { finishedFlag = true }

        override fun cancel() {
            cancelledFlag = true
            scheduler.submit { handle?.fsm?.send("CANCEL"); sweep() }
        }

        override val isCancelled: Boolean get() = cancelledFlag
        override val isFinished: Boolean
            get() = finishedFlag || handle?.fsm?.state.let {
                it == "Completed" || it == "Cancelled" || it == "Failed" || it == "Reclaimed"
            }
    }

    companion object {
        /**
         * **生产装配入口。** 业务方唯一需要调用的构造方式。
         *
         * 装配的四件平台相关物件都各自可替换（[VibratorGateway] / [WakeLockGateway] /
         * [FrameClock] / [HapticScheduler]），这是"绝大部分逻辑能在 JVM 单测里跑"
         * 的前提 —— 也是不引入 Robolectric 的底气。
         *
         * @param latencyProbe 延迟埋点。**调音台必须注入**（V5 §6.2b 要求 T0→T1
         *   单独成段）；生产环境按需，默认不采样。
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            hardwareClassOverride: HardwareClass? = null,
            latencyProbe: LatencyProbe = LatencyProbe.NOOP,
            metricsSink: CipherHapticMetricsSink = CipherHapticMetricsSink.NOOP,
            /**
             * **V3 的对照组开关**（P-10）。传 [NoWakeLock] 即"不持锁"组。
             *
             * V3 是五项验证里唯一一个**预期结论是删代码**的：若持锁与不持锁的振动
             * 完成率无差异，就直接删掉这条防线，不留装饰性代码。而**没有对照组就
             * 证不了它有用** —— 只测"持锁通过"证明不了锁起了作用，可能它本来就不需要。
             *
             * 这个参数存在的唯一理由就是让那个对照组做得成。V3 定论后应连同
             * [AndroidWakeLock] 一起处理（删掉或转正）。
             */
            wakeLockOverride: WakeLockGateway? = null,
        ): CipherHaptic {
            val app = context.applicationContext
            val gateway = AndroidVibratorGateway(app)
            return CipherHaptic(
                loader = SpecLoader.fromResources(),
                scheduler = AndroidHapticScheduler(),
                gateway = gateway,
                wakeLock = wakeLockOverride ?: AndroidWakeLock(app),
                // hardwareClassOverride 是产出 LINEAR_X_LIMITED 的【唯一】路径（P-07）——
                // 它诚实地承认"这就是一张白名单"，而不是伪装成运行时探测。
                probe = HardwareClassProbe(gateway, hardwareClassOverride),
                frameClock = AndroidFrameClock(),
                probe2 = latencyProbe,
                metricsSink = metricsSink,
            )
        }

        /** `VibrationEffect.Composition.PRIMITIVE_CLICK` 的常量值，避免在 core 引入平台常量。 */
        private const val PRIMITIVE_CLICK = 1

        /** ⚠️ **待实测**（性能 §二建议起点 2）—— 触觉叠加是振幅相加后 clip。 */
        const val DEFAULT_CAPACITY = 2

        /** ⚠️ **待实测**（性能 §5.4）。 */
        const val DEFAULT_COALESCE_WINDOW_MS = 100L

        /**
         * 循环效果的**库兜底上限**：应用告知的 `maxDurationMs` 超过它按它截断。
         *
         * ⚠️ **这条是 2026-08-02 真机事故之后补的**：`continuous` 有 `idleTimeoutMs`
         * 兜底，而 `looping` 当时什么兜底都没有。v1.4.0 起时长改由应用告知，
         * 这个值退为兜底 —— 5 分钟远超任何合理的触觉告警时长，拦的是**误传**，不是正常用法。
         */
        const val MAX_LOOP_DURATION_MS = DecisionPipeline.MAX_LOOP_DURATION_MS
    }
}
