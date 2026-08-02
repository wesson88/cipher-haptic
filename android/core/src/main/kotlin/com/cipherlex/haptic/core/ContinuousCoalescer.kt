package com.cipherlex.haptic.core

/**
 * 60Hz trailing coalesce —— 对应「句柄状态机」§七.5。
 *
 * ## 为什么不是"丢弃"
 *
 * v2 写「<16ms 的调用**丢弃** + 调试警告」，**策略是错的**：丢弃保留的是【旧】强度、
 * 扔掉的是【新】强度。手势结束时的最终强度值若恰好落在 16ms 窗口内，
 * **会永远发不出去** —— 表现为"松手瞬间触感卡在错误强度"。
 *
 * 正确做法是 **trailing coalesce**：`latest` 永远覆盖为最新值，窗口内不立即发送，
 * 而是排一个尾部补发块。
 *
 * **关键性质：最后一次 update 的值必定被发送**（要么立即、要么由补发块送出）。
 *
 * ## 它同时承担 v4.3 的 bufferParams
 *
 * `begin()` 之后到 `SUBMIT_OK` 之前，平台尚未就绪（scheduler 排队 + binder IPC 的
 * 耗时都在这里），但手指可能已经动了好几次。这些 `UPDATE` 走 [buffer]：
 * **只更新 `latest`，不碰平台、不动 idle-timer**（此时 idle-timer 还没建）。
 *
 * 起播时 `submit` 必须取 [latest] 而非 IR 的 `initialIntensity` —— 否则表现为
 * "按下去拖了一段才起震，而且第一下强度对不上手指位置"（见 §4.6）。
 */
class ContinuousCoalescer(
    private val scheduler: HapticScheduler,
    private val windowMs: Long = 16,          // 60Hz。待实测确认（性能 §5.4）
    private val send: (Float, Float) -> Unit,
) {
    private var latestIntensity: Float? = null
    private var latestSharpness: Float? = null
    private var lastSentAt = Long.MIN_VALUE
    private var pendingFlush: HapticScheduler.Cancellable? = null

    /** 平台就绪前的缓冲（v4.3 `bufferParams`）：只记值，不发送。 */
    fun buffer(intensity: Float, sharpness: Float) {
        latestIntensity = intensity
        latestSharpness = sharpness
    }

    /** 起播时取缓冲值；从未收到过 UPDATE 时返回 null，由调用方回落到 IR 默认值。 */
    fun latest(): Pair<Float, Float>? {
        val i = latestIntensity ?: return null
        return i to (latestSharpness ?: 0f)
    }

    /**
     * 告知"平台已经收到过一次值了"——由起播路径调用。
     *
     * ⚠️ **不登记会有真后果**：起播时 `submit` 直接把强度送给了平台（绕过本类），
     * 若 `lastSentAt` 仍是初始值，起播后 16ms 内的第一次 `update` 会被判为
     * "距上次发送足够久"而**立即再发一次** —— 起播瞬间连发两条平台命令，
     * 且 trailing coalesce 的节流从第二次才真正开始生效。
     */
    fun markSentAt(nowMs: Long) {
        lastSentAt = nowMs
    }

    /** 平台就绪后的更新（v4 `applyParams`）：trailing coalesce。 */
    fun update(intensity: Float, sharpness: Float) {
        latestIntensity = intensity
        latestSharpness = sharpness

        val now = scheduler.nowMs()
        val elapsed = if (lastSentAt == Long.MIN_VALUE) Long.MAX_VALUE else now - lastSentAt
        if (elapsed >= windowMs) {
            flushNow(now)
        } else if (pendingFlush == null) {
            pendingFlush = scheduler.schedule(windowMs - elapsed) {
                pendingFlush = null
                flushNow(scheduler.nowMs())
            }
        }
        // else：已有补发块在排，latest 已被覆盖为最新值 —— 无需重复排程
    }

    /**
     * 结束前必须 flush 未决的 `latest`（§七.5 末句）。
     * 否则最后一次 update 会随着补发块一起被取消掉。
     */
    fun flushPending() {
        pendingFlush?.let {
            it.cancel()
            pendingFlush = null
            flushNow(scheduler.nowMs())
        }
    }

    /** 通道结束：取消补发块并清空。**不 flush** —— 调用方若需要请先调 [flushPending]。 */
    fun reset() {
        pendingFlush?.cancel()
        pendingFlush = null
        latestIntensity = null
        latestSharpness = null
        lastSentAt = Long.MIN_VALUE
    }

    private fun flushNow(now: Long) {
        val i = latestIntensity ?: return
        send(i, latestSharpness ?: 0f)
        lastSentAt = now
    }
}
