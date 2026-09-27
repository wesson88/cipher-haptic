import Foundation

/// 60Hz trailing coalesce —— 对应「句柄状态机」§七.5。镜像 android/core/ContinuousCoalescer.kt。
///
/// 不是"丢弃"：`latest` 永远覆盖为最新值，窗口内排一个尾部补发块。
/// **关键性质：最后一次 update 的值必定被发送**（要么立即、要么由补发块送出）。
///
/// 它同时承担 v4.3 的 `bufferParams`：平台就绪前只记值、不发送；起播时 submit 取 `latest()`。
public final class ContinuousCoalescer {
    private let scheduler: HapticScheduler
    private let windowMs: Int                       // 60Hz。待实测确认（性能 §5.4）
    private let send: (Float, Float) -> Void

    private var latestIntensity: Float?
    private var latestSharpness: Float?
    private var lastSentAt: Int?
    private var pendingFlush: HapticCancellable?

    public init(scheduler: HapticScheduler, windowMs: Int = 16, send: @escaping (Float, Float) -> Void) {
        self.scheduler = scheduler
        self.windowMs = windowMs
        self.send = send
    }

    /// 平台就绪前的缓冲（v4.3 `bufferParams`）：只记值，不发送。
    public func buffer(_ intensity: Float, _ sharpness: Float) {
        latestIntensity = intensity
        latestSharpness = sharpness
    }

    /// 起播时取缓冲值；从未收到过 UPDATE 时返回 nil，由调用方回落到 IR 默认值。
    public func latest() -> (Float, Float)? {
        guard let i = latestIntensity else { return nil }
        return (i, latestSharpness ?? 0)
    }

    /// 起播那一发绕过了本类，必须补登记，否则节流从第二次才生效。
    public func markSentAt(_ nowMs: Int) { lastSentAt = nowMs }

    /// 平台就绪后的更新（v4 `applyParams`）：trailing coalesce。
    public func update(_ intensity: Float, _ sharpness: Float) {
        latestIntensity = intensity
        latestSharpness = sharpness

        let now = scheduler.nowMs()
        let elapsed = lastSentAt.map { now - $0 } ?? Int.max
        if elapsed >= windowMs {
            flushNow(now)
        } else if pendingFlush == nil {
            pendingFlush = scheduler.schedule(afterMs: windowMs - elapsed) { [weak self] in
                guard let self = self else { return }
                self.pendingFlush = nil
                self.flushNow(self.scheduler.nowMs())
            }
        }
        // else：已有补发块在排，latest 已被覆盖为最新值
    }

    /// 结束前必须 flush 未决的 `latest`（§七.5 末句）。
    public func flushPending() {
        guard let p = pendingFlush else { return }
        p.cancel()
        pendingFlush = nil
        flushNow(scheduler.nowMs())
    }

    /// 通道结束：取消补发块并清空。**不 flush**。
    public func reset() {
        pendingFlush?.cancel()
        pendingFlush = nil
        latestIntensity = nil
        latestSharpness = nil
        lastSentAt = nil
    }

    public var hasPendingFlush: Bool { pendingFlush != nil }

    private func flushNow(_ now: Int) {
        guard let i = latestIntensity else { return }
        send(i, latestSharpness ?? 0)
        lastSentAt = now
    }
}
