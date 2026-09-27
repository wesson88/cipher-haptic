import Foundation

/// 一条迁移。
public struct Transition: Sendable {
    public let from: String
    public let events: [String]
    public let to: String
    public let action: String
    public let guardExpr: String?
    public let silent: Bool
    public let illegal: Bool
}

/// 迁移表 —— 与 Android 共用同一张 `transitions`（runtime.min.json）。
///
/// **守卫必须互斥且完备**（不变式 4）。Kotlin 在命中多条时 `check()` 抛异常；
/// iOS 守「API 绝不抛异常」：多命中按非法处理并告警，穷举测试兜底（`hits`）。
public final class TransitionTable {
    public let states: [String]
    public let events: [String]
    public let transitions: [Transition]
    private let byState: [String: [Transition]]

    public init(json: [String: Any]) throws {
        guard let arr = json["transitions"] as? [[String: Any]],
              let states = json["states"] as? [String],
              let events = json["events"] as? [String]
        else { throw SpecError("transitions 结构不完整") }
        var list: [Transition] = []
        for r in arr {
            // ⚠️ 键名是 `event` 而非 `on` —— `on` 是 YAML 1.1 的布尔字面量（§十 表头注释）
            let evs: [String]
            if let a = r["event"] as? [String] { evs = a } else if let s = r["event"] as? String { evs = [s] } else {
                throw SpecError("transition 缺 event：\(r)")
            }
            guard let from = r["from"] as? String, let to = r["to"] as? String else {
                throw SpecError("transition 缺 from/to：\(r)")
            }
            list.append(Transition(
                from: from, events: evs, to: to,
                action: r["action"] as? String ?? "none",
                guardExpr: r["when"] as? String,
                silent: r["silent"] as? Bool ?? false,
                illegal: r["illegal"] as? Bool ?? false
            ))
        }
        self.states = states
        self.events = events
        self.transitions = list
        self.byState = Dictionary(grouping: list, by: \.from)
    }

    /// 命中的全部规则 —— 正常应至多 1 条（穷举测试用）。
    public func hits(_ state: String, _ event: String, kind: WaveKind, category: CipherHapticCategory) -> [Transition] {
        (byState[state] ?? []).filter { t in
            (t.events.contains("*") || t.events.contains(event)) && Self.guardOk(t.guardExpr, kind, category)
        }
    }

    public func lookup(_ state: String, _ event: String, kind: WaveKind, category: CipherHapticCategory) -> Transition? {
        let h = hits(state, event, kind: kind, category: category)
        return h.count == 1 ? h[0] : nil
    }

    /// 守卫：变量只有 `kind` / `cat`，操作符只有 `=` / `!=` / `&&`。
    static func guardOk(_ g: String?, _ kind: WaveKind, _ cat: CipherHapticCategory) -> Bool {
        guard let g = g, !g.trimmingCharacters(in: .whitespaces).isEmpty else { return true }
        return g.components(separatedBy: "&&").allSatisfy { clause in
            let c = clause.trimmingCharacters(in: .whitespaces)
            let neq = c.range(of: "!=")
            let parts = neq != nil ? c.components(separatedBy: "!=") : c.components(separatedBy: "=")
            guard parts.count == 2 else { return false }
            let v = parts[0].trimmingCharacters(in: .whitespaces)
            let value = parts[1].trimmingCharacters(in: .whitespaces)
            let cur: String
            switch v {
            case "kind": cur = kind.rawValue
            case "cat": cur = cat.rawValue
            default: return false                     // 未知变量：不命中（穷举测试会暴露）
            }
            return neq != nil ? cur != value : cur == value
        }
    }
}

/// 动作接口 —— **各端各自原生实现，唯一不共用的部分**。
///
/// `submit` / `resubmit` **必须回报 SUBMIT_OK 或 FAIL，不得静默返回**：
/// 静默会让 handle 永久停在 `Submitting`，等价于 player 泄漏。
public protocol PlaybackActions: AnyObject {
    func invoke(_ action: String)
}

/// PlaybackFSM —— 对应「句柄状态机」§十。8 态 / 10 事件。镜像 android/core/PlaybackFsm.kt。
/// runner 本身极薄：查表 + 派发动作。迁移逻辑是数据，不是代码。
public final class PlaybackFsm {
    private let table: TransitionTable
    private let kind: WaveKind
    private let category: CipherHapticCategory
    private weak var actions: PlaybackActions?

    public private(set) var state = "Pending"

    /// 非法 (状态,事件) 组合的记录。**忽略 + 告警**，绝不崩溃。
    public private(set) var illegal: [String] = []

    /// 进入新状态时的观察点（只在状态真的改变时触发）。**不是迁移表的一部分**。
    public var onStateEntered: ((String) -> Void)?

    public init(table: TransitionTable, kind: WaveKind, category: CipherHapticCategory, actions: PlaybackActions) {
        self.table = table
        self.kind = kind
        self.category = category
        self.actions = actions
    }

    public func send(_ event: String) {
        let h = table.hits(state, event, kind: kind, category: category)
        guard h.count == 1 else {
            illegal.append(h.isEmpty ? "\(event) in \(state)" : "\(event) in \(state) 命中 \(h.count) 条（不变式 4）")
            return
        }
        let t = h[0]
        if t.illegal {
            illegal.append("\(event) in \(state) (declared illegal)")
            return
        }
        let from = state
        state = t.to
        if t.action != "none" { actions?.invoke(t.action) }
        if t.to != from { onStateEntered?(t.to) }
    }
}
