import Foundation

/// degradation 矩阵里的一格。
public struct DegradeCell: Hashable, Sendable {
    public let action: String
    public let durationMs: Int?
    public let amplitudeScale: Float?
    public let durationScale: Float?
    public let count: Int?
    public let intervalMs: Int?
}

/// 降级变换 —— 对应 SSOT §3.1。镜像 android/core/Degrade.kt（逐 action 同构，golden 对拍）。
///
/// 四条通则：
///  1. 输入是中立 events，不是双端数组
///  2. 降级在 globalScale **之后**（管线 ④scale → ⑤degrade）
///  3. 每个 cell 有且仅有一个 action，不叠加
///  4. 变换后重算 totalDurationMs，并重新满足 IR 的升序 / 不重叠约束
public enum Degrade {

    /// 与 Python 参考实现的 `KNOWN_ACTIONS` 一一对应。
    public static let known: Set<String> = [
        "full", "silent", "forced_amplitude", "simplify",
        "tail_pulse_only", "single_pulse", "n_pulses", "amplitude_only",
    ]

    /// - Returns: `nil` 表示 **silent** —— 不产出 IR、不创建 handle、管线直接 drop（IR §3.3③）。
    public static func apply(_ events: [IrEvent], _ cell: DegradeCell, _ effectId: String) throws -> [IrEvent]? {
        let pulses = { events.filter { $0.kind == .pulse } }
        switch cell.action {
        case "full":
            return events

        case "silent":
            return nil

        // ERM 无振幅控制：丢弃全部原时序，压成单一满幅脉冲
        case "forced_amplitude":
            guard let d = cell.durationMs else { throw SpecError("\(effectId): forced_amplitude 需要正整数 duration_ms") }
            guard d > 0 else { throw SpecError("\(effectId): duration_ms 必须 > 0") }
            return [IrEvent(atMs: 0, durationMs: d, intensity: 1, sharpness: 0, kind: .pulse)]

        case "simplify":
            let amp = cell.amplitudeScale ?? 1
            let dur = cell.durationScale ?? 1
            guard amp > 0, dur > 0 else { throw SpecError("\(effectId): simplify 的 scale 必须 > 0") }
            return events.map { $0.scaled(amp: amp, dur: dur) }

        // 长效果只留信息量最大的收尾击
        case "tail_pulse_only":
            let ps = pulses()
            guard var last = ps.first else {
                throw SpecError("\(effectId): tail_pulse_only 要求至少 1 个 pulse 事件，实际 0 个（CI 规则 12）")
            }
            for p in ps.dropFirst() where p.atMs > last.atMs { last = p }   // 并列取第一个，同 Kotlin maxBy
            last.atMs = 0
            return [last]

        // 多击压成一击，保留最强的那一下；强度并列取 atMs 最大的
        case "single_pulse":
            let ps = pulses()
            guard var best = ps.first else {
                throw SpecError("\(effectId): single_pulse 要求至少 1 个 pulse 事件，实际 0 个（CI 规则 12）")
            }
            for p in ps.dropFirst()
            where p.intensity > best.intensity || (p.intensity == best.intensity && p.atMs > best.atMs) {
                best = p
            }
            best.atMs = 0
            return [best]

        // 脉冲链稀释，保留"断奏"的辨识度
        case "n_pulses":
            guard let n = cell.count else { throw SpecError("\(effectId): n_pulses 需要 count") }
            guard let gap = cell.intervalMs else { throw SpecError("\(effectId): n_pulses 需要 interval_ms") }
            guard n >= 1 else { throw SpecError("\(effectId): count 必须 ≥ 1") }
            guard gap >= 0 else { throw SpecError("\(effectId): interval_ms 必须 ≥ 0") }
            let ps = pulses()
            guard ps.count >= n else {
                throw SpecError("\(effectId): n_pulses count=\(n) 但只有 \(ps.count) 个 pulse 事件（CI 规则 12）")
            }
            // durationMs / intensity / sharpness 各自保持原值，只重排 atMs
            return ps.prefix(n).enumerated().map { i, p in
                var q = p
                q.atMs = i * gap
                return q
            }

        // 只有振幅一个维度的通道或设备（P-04）
        case "amplitude_only":
            return events.map { e in
                var q = e
                q.sharpness = 0
                return q
            }

        default:
            throw SpecError("\(effectId): 未知降级 action '\(cell.action)'。合法值见 SSOT §3.1——不猜")
        }
    }
}
