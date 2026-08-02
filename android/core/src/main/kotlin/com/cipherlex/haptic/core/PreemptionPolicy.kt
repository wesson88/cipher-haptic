package com.cipherlex.haptic.core

/**
 * 抢占目标计算 —— 对应「句柄状态机」§八。
 *
 * ## 为什么它在 core 而不在 engine
 *
 * > 「**最关键的架构判断：抢占是"跨 handle 的调度决策"，放 `DecisionPipeline`，
 * >   不放单个 handle 的 FSM。** 状态机只管"一个 handle 自己怎么活怎么死"，
 * >   不管"它和别的 handle 的关系"。」
 *
 * 于是抢占**不新增状态/事件**：算出目标后对它们发 `CANCEL(reason=preempted)`，
 * 复用状态机现有事件。计算是纯函数（给定 [ActiveHandleInfo] 快照算出目标集合），
 * 可单测；执行才是 engine 动作。
 */
object PreemptionPolicy {

    /** 活跃 handle 的优先级快照 —— **纯数据**，不含 handle 本身。 */
    data class ActiveHandleInfo(
        val id: Long,
        val category: Category,
        val kind: WaveKind,
        /** 已播时长，用于连点合并窗口判定（§8.3）。 */
        val elapsedMs: Long,
        /** 来自 `semantics.yaml` 的抢占保护标记（§8.4）。 */
        val protectedFromPreemption: Boolean,
        val state: String,
    )

    /**
     * 只有 `Active` / `Paused` 占容量槽位。
     *
     * ⚠️ v2 的表述是"`Reclaimed` 才移出 `activeHandles`"，意味着 **grace 中的僵尸算进容量**
     * —— 4 个槽位可能有 2 个是死的，**导致误抢占真正在播的效果**（§五.2）。
     */
    private fun occupiesSlot(s: String) = s == "Active" || s == "Paused"

    private fun rank(c: Category) = when (c) {
        Category.UX -> 0
        Category.ALERT -> 1
        Category.CRITICAL -> 2
    }

    /**
     * @param newCategory 新播放的 category
     * @param active      当前活跃 handle 快照
     * @param capacity    容量上限。⚠️ **待实测**（性能 §二建议起点 2）——
     *                    触觉叠加是振幅相加后 clip，超过 2 个基本糊成一团
     * @param coalesceWindowMs 连点合并窗口。⚠️ **待实测**（性能 §5.4）
     * @return 应被抢占（收 `CANCEL`）的 handle id 集合
     */
    fun computeTargets(
        newCategory: Category,
        active: List<ActiveHandleInfo>,
        capacity: Int,
        coalesceWindowMs: Long,
    ): Set<Long> {
        val slots = active.filter { occupiesSlot(it.state) }
        val targets = LinkedHashSet<Long>()

        // ── ① 同级叠加防护（§8.3 矩阵对角线）────────────────────────
        // 高抢低、同级 FIFO、低不抢高。这里先处理"同级"。
        val sameLevel = slots.filter { it.category == newCategory }
        if (sameLevel.isNotEmpty() && newCategory != Category.CRITICAL) {
            val oldest = sameLevel.minBy { it.id }
            // §8.4：手势 continuous 不被普通点击打断
            val protectedNow = oldest.protectedFromPreemption ||
                oldest.kind == WaveKind.CONTINUOUS
            // §8.3 连点合并窗口：已播时长在窗口内则【不抢】，让它自然播完。
            // 否则快速连点（人类可达 ~100ms/次）会让每次触感都在制动阶段被打断，
            // 手感"发糊" —— 这是 v2 的抢占规则与 grace 直接打架的地方。
            val withinCoalesce = oldest.elapsedMs < coalesceWindowMs
            if (!protectedNow && !withinCoalesce) targets += oldest.id
        }

        // ── ② 容量上限（§8.1）——"高抢低"在这里生效，而不是无条件清场 ────
        //
        // ⚠️ 这里曾写错：先无条件抢掉所有低优先级 handle，再补容量。那不符合 §8.1
        //    ——抢占的触发场景只有【叠加防护】与【容量上限】两个，**没有"高优先级
        //    一来就清场"这一条**。按错的写法，一次 critical 告警会把正在播的所有
        //    ux/alert 全掐掉，哪怕容量绰绰有余；手势拖拽也会被一次通知打断。
        //
        // 正确语义：容量不够时才抢，而"抢谁"由优先级决定 —— 这才是 §8.3 矩阵
        // 「高抢低」的真实含义（高优先级在抢占排序里排最后，即最不容易被牺牲）。
        val survivors = slots.filter { it.id !in targets }
        val overflow = survivors.size - (capacity - 1)      // 新的这个也要占一槽
        if (overflow > 0) {
            survivors.asSequence()
                // 受保护的 continuous 排最后被牺牲（§8.4）；critical 次之
                .sortedWith(
                    compareBy(
                        { if (it.protectedFromPreemption) 1 else 0 },
                        { rank(it.category) },
                        { it.id },                          // 同级 FIFO：最旧先走
                    )
                )
                .take(overflow)
                .forEach { targets += it.id }
        }
        return targets
    }
}
