package com.cipherlex.haptic.demo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.cipherlex.haptic.CipherHaptic
import com.cipherlex.haptic.CipherHapticCancelToken
import com.cipherlex.haptic.CipherHapticDebugDelegate
import com.cipherlex.haptic.CipherHapticSemantic
import com.cipherlex.haptic.MuteState
import com.cipherlex.haptic.MuteStateObserver

/**
 * 马达调音台 —— 主文档 **B.9** 要求的单页 App。
 *
 * 要求逐条落地：
 * - **全部 facade 方法**以按钮 / 滑块暴露，可实时触发；
 * - 实时显示**设备硬件等级、全局缩放、静音状态、引擎状态**；
 * - 埋入 **T0→T1 / T1→T2 分段延迟计时**（V5 §6.2b —— 这条决定"是否下沉 C++"）。
 *
 * ## 它同时是 P0 验证计划的现场夹具
 *
 * | 验证项 | 在这里怎么看 |
 * |---|---|
 * | **V4** 硬件档探测（P-07） | 顶部"硬件档"一栏 —— 真机上到底被判成哪一档 |
 * | **V5** 性能基线 | "延迟统计"一栏，直接出 p50/p95/p99 |
 * | **P-08** 原语可用性 | 日志里能看到走的是 Composition 还是 waveform 回退 |
 * | **ERM_Z 产品确认** | 用"强制硬件档"开关模拟低端机，听保底震动是否可接受 |
 *
 * ⚠️ **真机手感、T2→T3 马达 rise time、对齐误差**这里测不了，需外置加速度计
 * 或 ≥960fps 摄像（V5 B 段）。这个 App 只覆盖 A 段。
 */
class TuningActivity : Activity() {

    private lateinit var haptic: CipherHaptic
    private val stats = LatencyStats()
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var latencyView: TextView
    private val logLines = ArrayDeque<String>()
    private var loopToken: CipherHapticCancelToken? = null
    private lateinit var stress: StressHarness
    private lateinit var metricsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptic = CipherHaptic.create(this, latencyProbe = stats)
        haptic.debugDelegate = object : CipherHapticDebugDelegate {
            override fun onStateChanged(state: String) = log("state: $state")
            // 降级不是失败 —— 但必须可见，否则"低端机上没触觉"永远查不出来
            override fun onDegraded(semantic: String, action: String) =
                log("degrade: $semantic → $action")
            override fun onDropped(semantic: String, reason: String) =
                log("DROP: $semantic ← $reason")
        }
        haptic.registerMuteObserver(object : MuteStateObserver {
            override fun onMuteStateChanged(state: MuteState) = log("mute: $state")
        })
        stress = StressHarness(haptic) { log(it) }
        setContentView(buildUi())
        refreshStatus()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * **adb 可驱动** —— P0 验证要在多台真机上重复同一组操作，手点不可复现也不可脚本化。
     *
     * ```
     * adb shell am start -n com.cipherlex.haptic.demo/.TuningActivity      *     --es play item.dissolve
     * adb shell am start -n … --es stress 500      # 压测并断言不变式
     * adb shell am start -n … --es dump 1          # 把状态/指标/延迟打进 logcat
     * ```
     *
     * 结果一律同时写 logcat（tag `CipherHapticTuner`），便于 `adb logcat` 采集。
     */
    private fun handleIntent(intent: android.content.Intent?) {
        val i = intent ?: return
        i.getStringExtra("play")?.let { id ->
            val sem = CipherHapticSemantic.entries.firstOrNull { it.id == id }
            if (sem == null) log("未知语义：$id") else {
                haptic.playEffect(sem)
                log("adb play($id)")
            }
        }
        i.getStringExtra("stress")?.toIntOrNull()?.let { n ->
            // 静息上限按操作数缩放：2000 次 burst 会在串行 scheduler 上排出很深的队列，
            // 固定 3s 读到的是【中间态】，会把正常排空误判成泄漏。
            stress.runRound(n, maxOf(5_000L, n * 25L)) { refreshStatus() }
        }
        if (i.hasExtra("dump")) dumpAll()
        refreshStatus()
    }

    private fun dumpAll() {
        val c = haptic.hardwareCapabilities()
        // V4 的原始判定依据，一行一个键值，便于脚本 grep
        android.util.Log.i(TAG, "V4 hardwareClass=${c.hardwareClass}")
        android.util.Log.i(TAG, "V4 supportsSharpness=${c.supportsSharpness}")
        android.util.Log.i(TAG, "V4 supportsBackgroundPlayback=${c.supportsBackgroundPlayback}")
        android.util.Log.i(TAG, "V4 systemHapticsEnabled=${c.systemHapticsEnabled}")
        haptic.metricsSnapshot().summary().lines().forEach {
            android.util.Log.i(TAG, "METRICS $it")
        }
        android.util.Log.i(TAG, "STATES ${haptic.debugHandleStates()}")
        stats.report().lines().forEach { android.util.Log.i(TAG, "V5 $it") }
    }

    // ── UI ───────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }

        statusView = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#EEF3F8"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(statusView)

        root.addView(header("① 语义 token（7 个）—— 业务方唯一词汇表"))
        val grid = column()
        for (s in CipherHapticSemantic.entries) {
            grid.addView(button("${s.id}   ▶") {
                haptic.playEffect(s)
                log("play(${s.id})  preview=${haptic.preview(s)}")
                refreshStatus()
            })
        }
        root.addView(grid)

        root.addView(header("② 帧对齐（接口 2）—— 只承诺下一 VSync 提交，非绝对时刻"))
        root.addView(button("playEffect(selectionSnap, onNextFrame = true)") {
            haptic.playEffect(CipherHapticSemantic.SELECTION_SNAP, onNextFrame = true)
            log("play onNextFrame=true（P-02：Android 是调度级，iOS 是硬件级）")
        })

        root.addView(header("③ 循环与取消（接口 3 / CancelToken）"))
        root.addView(button("playLoopingEffect(notifyMessage)") {
            loopToken = haptic.playLoopingEffect(CipherHapticSemantic.NOTIFY_MESSAGE)
            log("looping 起播，token=${loopToken?.hashCode()}")
            refreshStatus()
        })
        root.addView(button("token.cancel()") {
            loopToken?.cancel()
            log("cancel → isCancelled=${loopToken?.isCancelled} isFinished=${loopToken?.isFinished}")
            refreshStatus()
        })

        root.addView(header("④ 连续手势（接口 5/6）—— 在下面色块上拖动"))
        root.addView(TextView(this).apply {
            text = "⚠️ P-20：同一时刻只支持一路连续手势。多指拖动时后者覆盖前者，" +
                "任一方抬起结束整条通道 —— 这是登记在案的取舍，不是 bug。"
            textSize = 11f
            setTextColor(Color.parseColor("#8A6D3B"))
        })
        root.addView(gesturePad())

        root.addView(header("⑤ 全局配置（接口 9–12）"))
        root.addView(CheckBox(this).apply {
            text = "setHapticsEnabled（master 开关）"
            isChecked = true
            setOnCheckedChangeListener { _, v -> haptic.setHapticsEnabled(v); refreshStatus() }
        })
        root.addView(TextView(this).apply { text = "setGlobalScale —— ⚠️ 只缩放 intensity；Android 无 sharpness（P-04）" })
        root.addView(SeekBar(this).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    haptic.setGlobalScale(p / 100f); refreshStatus()
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        })

        root.addView(header("⑥ 全局控制"))
        root.addView(button("stopAllEffects()") { haptic.stopAllEffects(); refreshStatus() })
        root.addView(button("prepare(所有语义)") {
            CipherHapticSemantic.entries.forEach { haptic.prepare(it) }
            log("prepare 全部完成")
        })

        root.addView(header("⑦ 延迟统计（V5 A 段）"))
        latencyView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(latencyView)
        root.addView(button("刷新延迟统计") { refreshStatus() })
        root.addView(button("清空样本") { stats.reset(); refreshStatus() })

        root.addView(header("⑧ 压测与不变式（monkey 补不上的那只眼睛）"))
        root.addView(TextView(this).apply {
            text = "monkey 只能发现【硬失败】（崩溃 / ANR）。本库设计成零崩溃、静默降级，" +
                "于是绝大多数失败是【软失败】——不振动或振错，monkey 完全看不见。" +
                "下面两条按钮补的正是判定依据：压测后断言「活跃 handle 归零」" +
                "「无泄漏嫌疑」「无法解释的失败为 0」。"
            textSize = 11f
            setTextColor(Color.parseColor("#8A6D3B"))
        })
        root.addView(button("压测 200 次随机操作 → 等静息 → 断言") {
            stress.runRound(200, 5_000) { refreshStatus() }
        })
        root.addView(button("压测 2000 次（抢占风暴）→ 等静息 → 断言") {
            stress.runRound(2_000, 50_000) { refreshStatus() }
        })
        metricsView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(metricsView)

        root.addView(header("⑨ 日志"))
        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setTextColor(Color.parseColor("#D4D4D4"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(logView)

        return ScrollView(this).apply { addView(root) }
    }

    /** 连续手势测试区：按下起播、拖动改强度（Y 轴）、抬起结束。 */
    private fun gesturePad(): View = TextView(this).apply {
        text = "按住并上下拖动"
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#D6E4F0"))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(120))
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // 越往上越强
                    val i = (1f - (e.y / v.height).coerceIn(0f, 1f))
                    haptic.updateContinuousEffect(i, i)
                    text = "intensity = %.2f".format(i)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    haptic.endContinuousEffect()
                    text = "按住并上下拖动"
                    log("continuous 结束")
                    refreshStatus()
                }
            }
            true
        }
    }

    // ── 状态与日志 ───────────────────────────────────────────────────

    private fun refreshStatus() {
        val c = haptic.hardwareCapabilities()
        statusView.text = buildString {
            appendLine("硬件档     ${c.hardwareClass}   ← V4 要看的就是这个值")
            appendLine("引擎状态   ${haptic.engineState()}")
            appendLine("全局缩放   %.2f".format(haptic.globalScale()))
            appendLine("master     ${haptic.isHapticsEnabled()}")
            appendLine("静音状态   ${haptic.syncSystemMuteState()}")
            appendLine("── D 类差异（契约强制出口）──")
            appendLine("sharpness            ${c.supportsSharpness}   ← P-04：Android 恒 false")
            append("后台播放             ${c.supportsBackgroundPlayback}   ← P-06：待 V1 真机验证")
        }
        latencyView.text = stats.report()
        if (::metricsView.isInitialized) metricsView.text = haptic.metricsSnapshot().summary()
    }

    private fun log(line: String) {
        android.util.Log.i(TAG, line)          // 同时进 logcat —— 真机排查看不到屏幕上的 TextView
        logLines.addFirst(line)
        while (logLines.size > 40) logLines.removeLast()
        runOnUiThread { logView.text = logLines.joinToString("\n") }
    }

    // ── 小工具 ───────────────────────────────────────────────────────

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(18), 0, dp(6))
        setTextColor(Color.parseColor("#1A5490"))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CipherHapticTuner"
    }
}
