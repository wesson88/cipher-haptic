import XCTest
@testable import CipherHapticCore

/// SplitMix64 —— 可复现的 PRNG（种子固定，失败可重放）。
struct SplitMix64 {
    private var s: UInt64
    init(seed: UInt64) { s = seed }
    mutating func next() -> UInt64 {
        s &+= 0x9E37_79B9_7F4A_7C15
        var z = s
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
    mutating func int(_ n: Int) -> Int { Int(next() % UInt64(n)) }
}

/// FSM 不变式 fuzz · Swift 半边（工程骨架 §六.5）。与 Kotlin / Python 用**同一张迁移表**、**同一组断言**
/// —— 验证的是三端 runner 对同一张表的解释一致（尤其守卫求值）。断言打在**资源**上而不是状态上。
final class FsmInvariantTests: XCTestCase {

    /// 模拟 PlaybackActions 持有的资源。
    private final class Res: PlaybackActions {
        var player = false, endTimer = false, idleTimer = false, keepAlive = false
        var released = 0
        let continuous: Bool
        init(continuous: Bool) { self.continuous = continuous }

        var anyHeld: Bool { player || endTimer || idleTimer || keepAlive }

        func invoke(_ action: String) {
            switch action {
            case "submit", "resubmit":
                player = true
                endTimer = !continuous
            case "startEndTimer": endTimer = true
            case "startIdleTimer", "applyParams": idleTimer = true
            case "startKeepAlive": keepAlive = true
            case "clearKeepAlive": keepAlive = false
            case "suspend": endTimer = false
            case "stop":
                endTimer = false
                idleTimer = false
            case "release":
                player = false
                endTimer = false
                idleTimer = false
                keepAlive = false
                released += 1
            default: break
            }
        }
    }

    func testRandomSequencesNeverLeak() throws {
        let table = try TransitionTable(json: SpecFiles.loader().transitionsJson)
        var rng = SplitMix64(seed: 20_260_802)
        let kinds = WaveKind.allCases
        let cats = CipherHapticCategory.allCases
        var kindsSeen = Set<WaveKind>(), updateSeen = false
        for i in 0..<50_000 {
            let kind = kinds[rng.int(kinds.count)]
            let cat = cats[rng.int(cats.count)]
            let res = Res(continuous: kind == .continuous)
            let m = PlaybackFsm(table: table, kind: kind, category: cat, actions: res)
            let seq = (0..<(1 + rng.int(24))).map { _ in table.events[rng.int(table.events.count)] }
            seq.forEach(m.send)
            kindsSeen.insert(kind)
            if seq.contains("UPDATE") { updateSeen = true }

            // 收尾：先 CANCEL 推进终态，GRACE_EXPIRED / EXPIRE 才有意义
            for _ in 0..<4 {
                m.send("CANCEL")
                m.send("GRACE_EXPIRED")
                m.send("EXPIRE")
            }
            let ctx = "[\(i)] kind=\(kind) cat=\(cat) seq=\(seq)"
            guard m.state == "Reclaimed" else { return XCTFail("\(ctx) 抵达不了 Reclaimed —— 资源泄漏") }
            guard res.released == 1 else { return XCTFail("\(ctx) release 应恰好 1 次，实际 \(res.released)") }
            guard !res.anyHeld else { return XCTFail("\(ctx) Reclaimed 后仍持有资源") }
            for ev in table.events {
                m.send(ev)
                guard m.state == "Reclaimed" else { return XCTFail("\(ctx) Reclaimed 收到 \(ev) 后变了") }
            }
        }
        XCTAssertEqual(kindsSeen, Set(kinds), "fuzz 必须覆盖 continuous")
        XCTAssertTrue(updateSeen, "fuzz 必须覆盖 UPDATE")
    }

    /// 穷举 8 态 × 10 事件 × 3 kind × 3 cat：每种至多命中一条（不变式 4）。
    func testGuardsMutuallyExclusive() throws {
        let table = try TransitionTable(json: SpecFiles.loader().transitionsJson)
        var n = 0
        for s in table.states {
            for e in table.events {
                for k in WaveKind.allCases {
                    for c in CipherHapticCategory.allCases {
                        let h = table.hits(s, e, kind: k, category: c)
                        XCTAssertLessThanOrEqual(h.count, 1, "(\(s), \(e)) kind=\(k) cat=\(c) 命中 \(h.count) 条")
                        n += 1
                    }
                }
            }
        }
        XCTAssertEqual(n, 720)
    }

    func testGuardEvaluation() {
        XCTAssertTrue(TransitionTable.guardOk("kind!=continuous && cat=critical", .looping, .critical))
        XCTAssertFalse(TransitionTable.guardOk("kind!=continuous && cat=critical", .continuous, .critical))
        XCTAssertFalse(TransitionTable.guardOk("kind!=continuous && cat=critical", .oneshot, .ux))
        XCTAssertTrue(TransitionTable.guardOk(nil, .oneshot, .ux))
        XCTAssertFalse(TransitionTable.guardOk("unknown=1", .oneshot, .ux), "未知变量不得命中")
    }
}
