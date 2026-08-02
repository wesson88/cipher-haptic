package com.cipherlex.haptic.engine

import android.view.Choreographer
import com.cipherlex.haptic.FrameClock

/**
 * `FrameClock` 的 Android 实现 —— 对应主文档 B.4 与双端差异 **P-02**。
 *
 * Android 侧**只能在 `Choreographer` 帧回调里直接调 `vibrate`**，没有"未来精确时刻
 * 触发"的 API；提交后还要经 binder IPC → system_server → HAL。故这里是**精度降级点**，
 * 登记为 B 类残差，具体量待 V5 实测。
 *
 * 对比 iOS：`CADisplayLink` 回调中把该帧时刻传给 `start(atTime:)`，能拿到**硬件级**
 * 落点 —— 质量向能力强的一端保留，语义向弱端对齐（Parity Ledger §二）。
 */
class AndroidFrameClock : FrameClock {
    override fun postFrameCallback(task: () -> Unit) {
        Choreographer.getInstance().postFrameCallback { task() }
    }
}
