package com.cipherlex.haptic.core

import com.cipherlex.haptic.core.PreemptionPolicy.ActiveHandleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 抢占规则矩阵（§8.3）的逐格断言。
 *
 * | P_new ╲ P_old | ux | alert | critical |
 * |---|---|---|---|
 * | ux | 连点窗口内不抢；窗口外抢最旧同类 | 不抢 | 不抢 |
 * | alert | 抢 | 抢最旧同类 | 不抢 |
 * | critical | 抢 | 抢 | 按容量（可叠加） |
 */
class PreemptionPolicyTest {

    private fun h(
        id: Long,
        cat: Category,
        elapsed: Long = 9_999,                 // 默认在连点窗口之外
        kind: WaveKind = WaveKind.ONESHOT,
        protectedFlag: Boolean = false,
        state: String = "Active",
    ) = ActiveHandleInfo(id, cat, kind, elapsed, protectedFlag, state)

    private fun targets(
        newCat: Category,
        active: List<ActiveHandleInfo>,
        capacity: Int = 4,
        coalesce: Long = 100,
    ) = PreemptionPolicy.computeTargets(newCat, active, capacity, coalesce)

    // ── §8.3 矩阵 ────────────────────────────────────────────────────

    @Test
    fun `ux 抢最旧的同级 ux`() {
        val t = targets(Category.UX, listOf(h(1, Category.UX), h(2, Category.UX)))
        assertEquals(setOf(1L), t, "同级 FIFO —— 抢最旧那个")
    }

    @Test
    fun `ux 不抢 alert 与 critical`() {
        val t = targets(Category.UX, listOf(h(1, Category.ALERT), h(2, Category.CRITICAL)))
        assertTrue(t.isEmpty(), "低不抢高")
    }

    @Test
    fun `alert 只抢最旧的同级 alert,不碰 ux 与 critical —— 容量够时不清场`() {
        val t = targets(
            Category.ALERT,
            listOf(h(1, Category.UX), h(2, Category.ALERT), h(3, Category.ALERT),
                   h(4, Category.CRITICAL)),
            capacity = 8,
        )
        assertEquals(setOf(2L), t, "同级 FIFO 抢最旧的 alert(2)；容量够就不动 ux(1)")
    }

    @Test
    fun `容量够时 critical 不清场 —— 高抢低只在容量不足时生效`() {
        // ⚠️ 实现最初写成"无条件抢掉所有低优先级"，被 grace 僵尸那条测试抓出来。
        //    §8.1 的抢占触发场景只有【叠加防护】与【容量上限】,没有"高优先级一来
        //    就清场"。按错的写法，一次通知会把正在播的手势拖拽掐掉。
        val t = targets(
            Category.CRITICAL,
            listOf(h(1, Category.UX), h(2, Category.ALERT), h(3, Category.CRITICAL)),
            capacity = 8,
        )
        assertTrue(t.isEmpty(), "容量绰绰有余，谁都不该被抢")
    }

    @Test
    fun `容量不足时才高抢低 —— 牺牲优先级最低且最旧的`() {
        val t = targets(
            Category.CRITICAL,
            listOf(h(1, Category.UX), h(2, Category.ALERT), h(3, Category.CRITICAL)),
            capacity = 2,
        )
        assertEquals(setOf(1L, 2L), t, "留 1 槽给新 critical，牺牲 ux(1) 与 alert(2)")
    }

    // ── §8.3 连点合并窗口 ────────────────────────────────────────────

    @Test
    fun `连点窗口内不抢占 —— 否则快速连点每次都在制动阶段被打断`() {
        // v2 的 "ux vs ux 立刻抢占" 与 §五.2 的 grace 直接打架：一边要留时间让马达
        // 制动收尾，一边新的同类一来就 stop。人类连点可达 ~100ms/次，手感会"发糊"。
        val t = targets(Category.UX, listOf(h(1, Category.UX, elapsed = 30)), coalesce = 100)
        assertTrue(t.isEmpty(), "已播 30ms < 窗口 100ms，应让它自然播完")
    }

    @Test
    fun `连点窗口外恢复抢占`() {
        val t = targets(Category.UX, listOf(h(1, Category.UX, elapsed = 150)), coalesce = 100)
        assertEquals(setOf(1L), t)
    }

    // ── §8.4 continuous 保护 ─────────────────────────────────────────

    @Test
    fun `手势 continuous 不被普通 ux 点击打断`() {
        val t = targets(
            Category.UX,
            listOf(h(1, Category.UX, kind = WaveKind.CONTINUOUS, protectedFlag = true)),
        )
        assertTrue(t.isEmpty(), "拖拽中按一下按钮，不该把手势触觉掐掉")
    }

    @Test
    fun `容量不足时 critical 仍可抢占受保护的 continuous,但它最后才被牺牲`() {
        // 受保护的 continuous 在抢占排序里排最后 —— 有别的可牺牲就先牺牲别的
        val withOther = targets(
            Category.CRITICAL,
            listOf(h(1, Category.UX, kind = WaveKind.CONTINUOUS, protectedFlag = true),
                   h(2, Category.UX)),
            capacity = 2,
        )
        assertEquals(setOf(2L), withOther, "有普通 ux 可牺牲时，不动受保护的手势")

        val onlyProtected = targets(
            Category.CRITICAL,
            listOf(h(1, Category.UX, kind = WaveKind.CONTINUOUS, protectedFlag = true)),
            capacity = 1,
        )
        assertEquals(setOf(1L), onlyProtected, "容量只剩它时，防窥告警仍优先")
    }

    // ── §8.1 容量与 grace 僵尸 ───────────────────────────────────────

    @Test
    fun `grace 中的僵尸不占容量槽`() {
        // ⚠️ v2 认为 "Reclaimed 才移出 activeHandles"，于是 4 个槽可能有 2 个是死的，
        //    导致【误抢占真正在播的效果】（§五.2）。
        val active = listOf(
            h(1, Category.ALERT, state = "Completed"),   // grace 僵尸
            h(2, Category.ALERT, state = "Cancelled"),   // grace 僵尸
            h(3, Category.ALERT, state = "Active"),
        )
        val t = targets(Category.CRITICAL, active, capacity = 2)
        assertTrue(1L !in t && 2L !in t, "僵尸不该被当作占槽者纳入抢占计算")
        assertTrue(t.isEmpty(), "只有 1 个真活跃 handle，容量 2 放得下，无需抢占")
    }

    @Test
    fun `超容量时抢占最低优先级且最旧的`() {
        val active = listOf(
            h(1, Category.CRITICAL),
            h(2, Category.UX, elapsed = 5_000),
            h(3, Category.UX, elapsed = 4_000),
        )
        val t = targets(Category.CRITICAL, active, capacity = 2)
        assertEquals(setOf(2L, 3L), t, "留 1 槽给新的，牺牲两个 ux，保住 critical(1)")
    }

    @Test
    fun `同级超容量时按 FIFO 抢占`() {
        val active = (1L..4L).map { h(it, Category.CRITICAL) }
        val t = targets(Category.CRITICAL, active, capacity = 2)
        // critical 同级不互抢，但容量兜底仍会抢最旧的 3 个（留 capacity-1 = 1 个）
        assertEquals(setOf(1L, 2L, 3L), t)
    }

    @Test
    fun `空活跃列表不抢占任何东西`() {
        assertTrue(targets(Category.CRITICAL, emptyList()).isEmpty())
    }
}
