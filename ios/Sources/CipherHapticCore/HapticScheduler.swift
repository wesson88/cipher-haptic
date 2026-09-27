import Foundation

/// 可取消的延时任务。取消是**尽力而为**：迟到的回调由迁移表吸收（§4.1）。
public protocol HapticCancellable: AnyObject {
    func cancel()
}

/// 串行执行器抽象 —— 对应「句柄状态机」§七.1。镜像 android/core/HapticScheduler.kt。
///
/// 串行化的是**决策 + 提交硬件命令**这一段。**所有 facade 方法与所有定时器回调**
/// （含 Core Haptics 的 stopped / reset 回调）都 marshal 到它 → 决策在它上面原子执行。
///
/// ⚠️ 不得用 `Thread.sleep` 排节拍（Haptico 实证坑）；也不得用非结构化 `Task {}` 派发
/// —— 不保证 FIFO，会破坏 cancel-vs-play 串行（§七.2）。
public protocol HapticScheduler: AnyObject {
    /// 单调时钟，毫秒。**不是** wall clock。
    func nowMs() -> Int
    /// 提交到串行队列。已在队列上时直接执行（handle 创建 + 平台提交须在同一 critical section，§七.3）。
    func submit(_ task: @escaping () -> Void)
    @discardableResult
    func schedule(afterMs delayMs: Int, _ task: @escaping () -> Void) -> HapticCancellable
}

/// 生产实现：单条串行 `DispatchQueue` + `DispatchWorkItem` 定时器。
public final class DispatchHapticScheduler: HapticScheduler {
    private let queue: DispatchQueue
    private let key = DispatchSpecificKey<UInt8>()

    public init(label: String = "com.cipherlex.haptic") {
        // makePlayer / start 不许在主线程同步调用（性能 §四.2），故自建串行队列
        queue = DispatchQueue(label: label, qos: .userInteractive)
        queue.setSpecific(key: key, value: 1)
    }

    public func nowMs() -> Int {
        Int(DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }

    public func submit(_ task: @escaping () -> Void) {
        if DispatchQueue.getSpecific(key: key) != nil { task() } else { queue.async(execute: task) }
    }

    @discardableResult
    public func schedule(afterMs delayMs: Int, _ task: @escaping () -> Void) -> HapticCancellable {
        let item = DispatchWorkItem(block: task)
        queue.asyncAfter(deadline: .now() + .milliseconds(max(0, delayMs)), execute: item)
        return WorkItemCancellable(item)
    }

    /// 测试 / 调音台用：等队列里已排的任务执行完。
    public func drain() { queue.sync {} }

    private final class WorkItemCancellable: HapticCancellable {
        let item: DispatchWorkItem
        init(_ item: DispatchWorkItem) { self.item = item }
        func cancel() { item.cancel() }
    }
}

/// 测试用调度器：**手动推进的假时钟 + 立即执行的串行队列**。
/// 它让"1.5 秒的 idle 超时"变成一次 `advance(1500)`，测试瞬间完成。
public final class TestScheduler: HapticScheduler {
    private var now = 0
    private var seq = 0
    private var timers: [Timer] = []

    private final class Timer: HapticCancellable {
        let at: Int
        let seq: Int
        let run: () -> Void
        var cancelled = false
        init(at: Int, seq: Int, run: @escaping () -> Void) {
            self.at = at
            self.seq = seq
            self.run = run
        }
        func cancel() { cancelled = true }
    }

    public init() {}

    public func nowMs() -> Int { now }

    public func submit(_ task: @escaping () -> Void) { task() }   // 串行 = 立即，测试里无并发

    @discardableResult
    public func schedule(afterMs delayMs: Int, _ task: @escaping () -> Void) -> HapticCancellable {
        let t = Timer(at: now + max(0, delayMs), seq: seq, run: task)
        seq += 1
        timers.append(t)
        return t
    }

    /// 推进假时钟，按 (时刻, 排程顺序) 触发到期任务；回调里新排的任务同样参与本轮推进。
    public func advance(_ ms: Int) {
        let target = now + ms
        while true {
            timers.removeAll { $0.cancelled }
            guard let next = timers.filter({ $0.at <= target })
                .min(by: { ($0.at, $0.seq) < ($1.at, $1.seq) }) else { break }
            timers.removeAll { $0 === next }
            now = next.at
            next.run()
        }
        now = target
    }

    /// 当前仍在排的未取消定时器数 —— 用于断言"没有定时器泄漏"。
    public func pendingTimers() -> Int { timers.filter { !$0.cancelled }.count }
}
