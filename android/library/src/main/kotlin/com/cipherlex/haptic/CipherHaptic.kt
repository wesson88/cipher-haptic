package com.cipherlex.haptic

import com.cipherlex.haptic.core.Category
import com.cipherlex.haptic.core.HapticScheduler
import com.cipherlex.haptic.core.HardwareClass
import com.cipherlex.haptic.core.PreemptionPolicy
import com.cipherlex.haptic.core.PlaybackFsm
import com.cipherlex.haptic.core.SpecLoader
import com.cipherlex.haptic.core.TransitionTable
import com.cipherlex.haptic.core.WaveKind
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
) {
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

    fun playLoopingEffect(semantic: CipherHapticSemantic): CipherHapticCancelToken {
        val token = HandleToken()
        scheduler.submit { startPlayback(semantic)?.let { token.bind(it) } ?: token.markFinished() }
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

    private fun startPlayback(
        semantic: CipherHapticSemantic,
        preSubmit: (PlaybackHandle) -> Unit = {},
    ): PlaybackHandle? {
        // ① master
        if (!masterEnabled.get()) return drop(semantic, "disabled")
        // ② system-off（P-14）  ③ dnd —— critical 绕过
        val category = loader.categoryOf(semantic.id)
        val mute = muteState.get()
        if (mute != MuteState.UNMUTED && category != Category.CRITICAL) {
            return drop(semantic, if (mute == MuteState.DND) "dnd" else "hardware-mute")
        }
        // ④ scale  ⑤ degrade（silent → 不建 handle，IR §3.3③）
        val rw = loader.resolve(semantic.id, probe.hardwareClass, globalScale())
            ?: return drop(semantic, "degraded-to-silent")
        rw.degradeTrace.firstOrNull()?.takeIf { it != "full" }?.let {
            debugDelegate?.onDegraded(semantic.id, it)
        }

        // ⑥ preempt —— 纯逻辑算目标，执行是发 CANCEL（§8.2）
        sweep()
        val snapshot = handles.values.map {
            PreemptionPolicy.ActiveHandleInfo(
                id = it.id,
                category = it.resolved.category,
                kind = it.resolved.kind,
                elapsedMs = scheduler.nowMs() - (startedAt[it.id] ?: scheduler.nowMs()),
                protectedFromPreemption = it.resolved.protectedFromPreemption,
                state = it.fsm.state,
            )
        }
        PreemptionPolicy.computeTargets(rw.category, snapshot, capacity, coalesceWindowMs)
            .forEach { handles[it]?.fsm?.send("CANCEL") }

        // ⑦ submit —— handle 创建 + 平台提交在同一 critical section 内（§七.3）
        val useComposition = probe.canUseComposition(intArrayOf(PRIMITIVE_CLICK))
        val h = PlaybackHandle(nextId.getAndIncrement(), rw, scheduler, gateway, wakeLock,
                               useComposition) { debugDelegate?.onStateChanged(it) }
        val fsm = PlaybackFsm(table, rw.kind, rw.category, h.actions)
        h.attach(fsm)
        // ⚠️ 回收必须是【推送式】：Reclaimed 由 grace 定时器驱动，而 sweep() 是拉取式的
        //    —— 没有下一次业务调用时，回收后的 handle 会一直挂在 activeHandles 里，
        //    engineState() 永远返回 RUNNING。这不是"晚一点清"，是【永远不清】。
        fsm.onStateEntered = { st -> if (st == "Reclaimed") retire(h.id) }
        handles[h.id] = h
        startedAt[h.id] = scheduler.nowMs()
        preSubmit(h)                       // continuous 在此塞入手指当前位置
        h.fsm.send("SUBMIT")
        sweep()
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

    private fun drop(semantic: CipherHapticSemantic, reason: String): PlaybackHandle? {
        debugDelegate?.onDropped(semantic.id, reason)
        return null
    }

    private val startedAt = HashMap<Long, Long>()

    /**
     * handle 进入 `Reclaimed` 时把它摘出表 —— 由 [PlaybackFsm.onStateEntered] 推送触发。
     *
     * grace 中的 `Completed` / `Cancelled` **仍留在表里，但不占容量槽**
     * （占槽判定见 [PreemptionPolicy]，只数 `Active` + `Paused`）。
     */
    private fun retire(id: Long) {
        handles.remove(id)
        startedAt.remove(id)
        if (continuousHandle?.id == id) continuousHandle = null
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
        /** `VibrationEffect.Composition.PRIMITIVE_CLICK` 的常量值，避免在 core 引入平台常量。 */
        private const val PRIMITIVE_CLICK = 1

        /** ⚠️ **待实测**（性能 §二建议起点 2）—— 触觉叠加是振幅相加后 clip。 */
        const val DEFAULT_CAPACITY = 2

        /** ⚠️ **待实测**（性能 §5.4）。 */
        const val DEFAULT_COALESCE_WINDOW_MS = 100L
    }
}
