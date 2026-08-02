package com.cipherlex.haptic.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * PlaybackFSM —— 对应「句柄状态机」§十。**8 态 / 10 事件，v4.2。**
 *
 * runner 本身极薄：**查表 + 派发动作**。文档说 ~40 行，确实如此——
 * 因为迁移逻辑是**数据**（`transitions`），不是代码。这也是双端能共用同一张表的原因。
 *
 * 动作由 [PlaybackActions] 注入，那是唯一不共用的部分（平台原生）。
 */
class PlaybackFsm(
    private val table: TransitionTable,
    private val kind: WaveKind,
    private val category: Category,
    private val actions: PlaybackActions,
) {
    var state: String = "Pending"
        private set

    /** 非法 (状态,事件) 组合的记录。**忽略 + 告警**，绝不崩溃。 */
    val illegal = mutableListOf<String>()

    fun send(event: String) {
        val t = table.lookup(state, event, kind, category)
        if (t == null) {
            illegal += "$event in $state"
            return
        }
        if (t.illegal) {
            // 显式声明的"故意非法"：行为与缺席相同，但它被登记过 —— 见 §十 守卫求值规则 ②
            illegal += "$event in $state (declared illegal)"
            return
        }
        state = t.to
        if (t.action != "none") actions.invoke(t.action)
    }
}

/** 一条迁移。 */
data class Transition(
    val from: String,
    val events: List<String>,
    val to: String,
    val action: String,
    val guard: String?,
    val silent: Boolean,
    val illegal: Boolean,
)

/**
 * 迁移表。**守卫必须互斥且完备**（不变式 4）——命中多条即架构违规，
 * 因为那意味着行为退化为"表内顺序依赖"。
 */
class TransitionTable(val states: List<String>, val events: List<String>,
                      private val transitions: List<Transition>) {

    fun lookup(state: String, event: String,
               kind: WaveKind, category: Category): Transition? {
        val hits = transitions.filter { t ->
            t.from == state &&
                (t.events.contains("*") || t.events.contains(event)) &&
                guardOk(t.guard, kind, category)
        }
        check(hits.size <= 1) {
            "($state, $event) kind=$kind cat=$category 命中 ${hits.size} 条转移" +
                "——守卫不互斥（不变式 4）。表内顺序依赖是不可接受的"
        }
        return hits.firstOrNull()
    }

    private fun guardOk(guard: String?, kind: WaveKind, category: Category): Boolean {
        if (guard.isNullOrBlank()) return true
        val ctx = mapOf(
            "kind" to kind.name.lowercase(),
            "cat" to category.name.lowercase(),
        )
        return guard.split("&&").all { clause ->
            val m = GUARD_RE.matchEntire(clause.trim())
                ?: error("无法解析守卫：$guard")
            val (v, op, value) = m.destructured
            val cur = ctx[v] ?: error("守卫引用了未知变量：$v")
            if (op == "=") cur == value else cur != value
        }
    }

    companion object {
        private val GUARD_RE = Regex("""(\w+)\s*(!=|=)\s*(\w+)""")

        /** 从 `bundle.json` 的 `transitions` 节构造。 */
        fun from(json: JSONObject): TransitionTable {
            val arr = json.getJSONArray("transitions")
            val list = ArrayList<Transition>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                // ⚠️ 键名是 `event` 而非 `on` —— `on` 是 YAML 1.1 的布尔字面量，
                //    双端 YAML 解析器都会把它变成布尔键 true。见 §十 表头注释。
                val ev = r.get("event")
                list += Transition(
                    from = r.getString("from"),
                    events = when (ev) {
                        is JSONArray -> (0 until ev.length()).map { ev.getString(it) }
                        else -> listOf(ev as String)
                    },
                    to = r.getString("to"),
                    action = r.optString("action", "none"),
                    guard = if (r.isNull("when")) null else r.getString("when"),
                    silent = r.optBoolean("silent", false),
                    illegal = r.optBoolean("illegal", false),
                )
            }
            return TransitionTable(
                states = json.getJSONArray("states").let { a ->
                    (0 until a.length()).map { a.getString(it) }
                },
                events = json.getJSONArray("events").let { a ->
                    (0 until a.length()).map { a.getString(it) }
                },
                transitions = list,
            )
        }
    }
}

/**
 * 动作接口 —— **各端各自原生实现，唯一不共用的部分**。
 *
 * `submit` / `resubmit` **必须回报 SUBMIT_OK 或 FAIL，不得静默返回**：
 * 静默会让 handle 永久停在 `Submitting`，等价于 player / wake lock 泄漏。
 */
interface PlaybackActions {
    fun invoke(action: String)
}
