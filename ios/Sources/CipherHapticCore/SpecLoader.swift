import Foundation

/// spec 数据不完整 —— CI 规则 8 / 12 应在构建前拦下，运行时出现即说明内嵌数据与代码脱节。
public struct SpecError: Error, CustomStringConvertible {
    public let description: String
    init(_ d: String) { description = d }
}

/// SpecLoader —— 【通用同构】层，对应「语义层与中立 IR」§六。镜像 android/core/SpecLoader.kt。
///
/// 职责：解析 + 语义→效果解析 + 归一化。**不碰任何平台 API**，双端共用同一组 golden 用例。
///
/// 内嵌的是 `spec/runtime.min.json`（不是 yaml、也不是 bundle.json）：事件已归一化为 IR 形态，
/// 双端 loader 都不必再写 `hapticTransient → pulse` 这类映射；JSON 在双端都是标准库，
/// 不必为 YAML 引入 Yams 依赖。
///
/// 线程：缓存加锁 —— `preview` 允许在任意线程调用（A.2 回调线程契约），会与串行队列上的
/// 播放并发读缓存。Android 的 HashMap 缓存没有这层保护。
public final class SpecLoader {

    private let semantics: [String: [String: Any]]
    private let effects: [String: [String: Any]]
    private let degradation: [String: [String: Any]]

    /// 迁移表原始 JSON。FSM runner 用它构造 `TransitionTable`。
    public let transitionsJson: [String: Any]

    public let semanticIds: [String]

    /// `idleTimeoutMs` 未实测时的兜底（性能 §5.4 待测项），与 Kotlin / Python 一致。
    static let defaultIdleTimeoutMs = 1500

    private let lock = NSLock()
    private var eventCache: [String: [IrEvent]] = [:]
    private var cellCache: [String: DegradeCell] = [:]

    public init(data: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sem = root["semantics"] as? [String: [String: Any]],
              let eff = root["effects"] as? [String: [String: Any]],
              let deg = root["degradation"] as? [String: [String: Any]],
              let tr = root["transitions"] as? [String: Any]
        else { throw SpecError("runtime.min.json 结构不完整") }
        semantics = sem
        effects = eff
        degradation = deg
        transitionsJson = tr
        semanticIds = sem.keys.sorted()
    }

    /// 从包内资源读取 —— 由 `tools/extract.py` 同步写入 `Resources/runtime.min.json`。
    public static func embedded() throws -> SpecLoader {
        guard let url = Bundle.module.url(forResource: "runtime.min", withExtension: "json") else {
            throw SpecError("runtime.min.json 未内嵌 —— 先跑 tools/extract.py")
        }
        return try SpecLoader(data: Data(contentsOf: url))
    }

    /// 内嵌资源的原始字节（ResourceEmbed 测试用）。
    public static func embeddedData() -> Data? {
        Bundle.module.url(forResource: "runtime.min", withExtension: "json").flatMap { try? Data(contentsOf: $0) }
    }

    // MARK: - 取值

    private func sem(_ id: String) throws -> [String: Any] {
        guard let s = semantics[id] else { throw SpecError("semantics 无此 token：\(id)") }
        return s
    }

    private func eff(_ id: String) throws -> [String: Any] {
        guard let e = effects[id] else { throw SpecError("effects 无此效果：\(id)") }
        return e
    }

    public func categoryOf(_ semanticId: String) throws -> CipherHapticCategory {
        guard let raw = try sem(semanticId)["category"] as? String, let c = CipherHapticCategory(rawValue: raw) else {
            throw SpecError("\(semanticId): 未知 category")
        }
        return c
    }

    /// 弃用治理：标了 `deprecatedBy` 的 token 自动转发到新 token（IR §2.5）。
    public func resolveAlias(_ semanticId: String) throws -> String {
        let d = try sem(semanticId)["deprecatedBy"] as? String ?? ""
        return d.isEmpty ? semanticId : d
    }

    public func effectIdOf(_ semanticId: String) throws -> String {
        guard let e = try sem(semanticId)["effect"] as? String else { throw SpecError("\(semanticId): 缺 effect") }
        return e
    }

    public func kindOf(_ effectId: String) throws -> WaveKind {
        guard let raw = try eff(effectId)["kind"] as? String, let k = WaveKind(rawValue: raw) else {
            throw SpecError("\(effectId): 未知 kind")
        }
        return k
    }

    /// 中立事件只依赖 spec 数据，与 globalScale / 硬件档无关（二者在 resolve 里后置作用），可缓存。
    public func neutralEvents(_ effectId: String) throws -> [IrEvent] {
        lock.lock()
        let cached = eventCache[effectId]
        lock.unlock()
        if let c = cached { return c }

        guard let arr = try eff(effectId)["events"] as? [[String: Any]] else { throw SpecError("\(effectId): 缺 events") }
        var out: [IrEvent] = []
        out.reserveCapacity(arr.count)
        for e in arr {
            guard let at = int(e["atMs"]), let d = int(e["durationMs"]),
                  let i = float(e["intensity"]), let s = float(e["sharpness"]),
                  let k = e["kind"] as? String
            else { throw SpecError("\(effectId): 事件字段不完整") }
            out.append(IrEvent(atMs: at, durationMs: d, intensity: i, sharpness: s, kind: k == "pulse" ? .pulse : .sustain))
        }
        out.sort { $0.atMs < $1.atMs }

        lock.lock()
        eventCache[effectId] = out
        lock.unlock()
        return out
    }

    public func continuousOf(_ effectId: String) throws -> ContinuousSpec? {
        guard let c = try eff(effectId)["continuous"] as? [String: Any] else { return nil }
        guard let ii = float(c["initialIntensity"]), let isv = float(c["initialSharpness"]),
              let maxD = int(c["maxDurationMs"]), let seg = int(c["segmentMs"])
        else { throw SpecError("\(effectId): continuous 块字段不完整") }
        return ContinuousSpec(
            initialIntensity: ii, initialSharpness: isv, maxDurationMs: maxD, segmentMs: seg,
            // null = 未实测，用内置默认（SSOT）
            idleTimeoutMs: int(c["idleTimeoutMs"]) ?? Self.defaultIdleTimeoutMs
        )
    }

    public func degradeCell(_ effectId: String, _ hw: CipherHapticHardwareClass) throws -> DegradeCell {
        let key = "\(effectId)/\(hw.rawValue)"
        lock.lock()
        let cached = cellCache[key]
        lock.unlock()
        if let c = cached { return c }

        guard let row = degradation[effectId] else { throw SpecError("degradation 缺格：\(effectId)（CI 规则 8）") }
        guard let c = row[hw.rawValue] as? [String: Any], let action = c["action"] as? String else {
            throw SpecError("degradation 缺格：\(effectId) × \(hw.rawValue)（CI 规则 8）")
        }
        let cell = DegradeCell(
            action: action,
            durationMs: int(c["durationMs"]),
            amplitudeScale: float(c["amplitudeScale"]),
            durationScale: float(c["durationScale"]),
            count: int(c["count"]),
            intervalMs: int(c["intervalMs"])
        )
        lock.lock()
        cellCache[key] = cell
        lock.unlock()
        return cell
    }

    /// 管线 ⓪→⑤ 的核心：语义 token + 硬件档 → IR。
    ///
    /// 求值顺序严格按主文档 B.3：**④ scale 先于 ⑤ degrade**（SSOT §3.1 通则 2）。
    /// - Returns: `nil` = 降级为 silent，管线 drop，**不创建 handle、不进 engine**（IR §3.3③）。
    public func resolve(_ semanticId: String, _ hw: CipherHapticHardwareClass, _ globalScale: Float = 1) throws -> ResolvedWaveform? {
        let id = try resolveAlias(semanticId)
        let effectId = try effectIdOf(id)

        var events = try neutralEvents(effectId)
        if globalScale != 1 { events = events.map { $0.scaled(amp: globalScale) } }       // ④

        let cell = try degradeCell(effectId, hw)
        guard let out = try Degrade.apply(events, cell, effectId) else { return nil }       // ⑤

        let kind = try kindOf(effectId)
        let loopGap = try int(eff(effectId)["loopGapMs"]) ?? 0
        return ResolvedWaveform(
            semanticId: id,
            effectId: effectId,
            category: try categoryOf(id),
            kind: kind,
            totalDurationMs: kind == .continuous ? 0 : ResolvedWaveform.totalOf(out, loopGap: loopGap),
            loopGapMs: loopGap,
            events: out,
            degradeTrace: [cell.action],
            protectedFromPreemption: try sem(id)["protected"] as? Bool ?? false,
            continuous: try continuousOf(effectId)
        )
    }
}

// JSONSerialization 的数值一律是 NSNumber；null 是 NSNull（as? 得 nil）
func int(_ v: Any?) -> Int? { (v as? NSNumber)?.intValue }
func float(_ v: Any?) -> Float? { (v as? NSNumber)?.floatValue }
