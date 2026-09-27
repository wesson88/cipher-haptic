import Foundation

/// 抢占目标计算 —— 对应「句柄状态机」§八。镜像 android/core/PreemptionPolicy.kt。
///
/// 抢占是"跨 handle 的调度决策"，放决策管线，不放单个 handle 的 FSM。计算是纯函数，
/// 执行是 engine 对目标发 `CANCEL(reason=preempted)`，不新增状态/事件。
///
/// ⚠️ 审查 B6（容量溢出时不与新来者比优先级）双端一起修，此处先保持与 Kotlin 同构。
public enum PreemptionPolicy {

    /// 活跃 handle 的优先级快照 —— 纯数据，不含 handle 本身。
    public struct ActiveHandleInfo: Sendable {
        public let id: Int
        public let category: CipherHapticCategory
        public let kind: WaveKind
        /// 已播时长，用于连点合并窗口判定（§8.3）
        public let elapsedMs: Int
        /// 来自 `semantics.yaml` 的抢占保护标记（§8.4）
        public let protectedFromPreemption: Bool
        public let state: String

        public init(id: Int, category: CipherHapticCategory, kind: WaveKind, elapsedMs: Int,
                    protectedFromPreemption: Bool, state: String) {
            self.id = id
            self.category = category
            self.kind = kind
            self.elapsedMs = elapsedMs
            self.protectedFromPreemption = protectedFromPreemption
            self.state = state
        }
    }

    /// 只有 `Active` / `Paused` 占容量槽位 —— grace 中的僵尸不算（§五.2）。
    static func occupiesSlot(_ s: String) -> Bool { s == "Active" || s == "Paused" }

    /// - Returns: 应被抢占（收 `CANCEL`）的 handle id，按确定顺序。
    public static func computeTargets(
        newCategory: CipherHapticCategory,
        active: [ActiveHandleInfo],
        capacity: Int,
        coalesceWindowMs: Int
    ) -> [Int] {
        let slots = active.filter { occupiesSlot($0.state) }
        var targets: [Int] = []

        // ① 同级叠加防护（§8.3 矩阵对角线）：同级 FIFO，critical 不做同级抢占
        let sameLevel = slots.filter { $0.category == newCategory }
        if newCategory != .critical, let oldest = sameLevel.min(by: { $0.id < $1.id }) {
            // §8.4：手势 continuous 不被普通点击打断
            let protectedNow = oldest.protectedFromPreemption || oldest.kind == .continuous
            // §8.3 连点合并窗口：已播时长在窗口内则不抢，让它自然播完
            let withinCoalesce = oldest.elapsedMs < coalesceWindowMs
            if !protectedNow && !withinCoalesce { targets.append(oldest.id) }
        }

        // ② 容量上限（§8.1）——"高抢低"只在容量不足时生效，不是无条件清场
        let survivors = slots.filter { !targets.contains($0.id) }
        let overflow = survivors.count - (capacity - 1)          // 新的这个也要占一槽
        if overflow > 0 {
            let order = survivors.sorted { a, b in
                // 受保护的排最后被牺牲；critical 次之；同级 FIFO：最旧先走
                let ka = (a.protectedFromPreemption ? 1 : 0, a.category.rank, a.id)
                let kb = (b.protectedFromPreemption ? 1 : 0, b.category.rank, b.id)
                return ka < kb
            }
            for h in order.prefix(overflow) where !targets.contains(h.id) { targets.append(h.id) }
        }
        return targets
    }
}
