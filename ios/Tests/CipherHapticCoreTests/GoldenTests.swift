import Foundation
import XCTest
@testable import CipherHapticCore

/// 定位仓库根的 `spec/`（模拟器与主机共享文件系统，xcodebuild 下同样可读）。
enum SpecFiles {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    static func data(_ name: String) throws -> Data {
        try Data(contentsOf: root.appendingPathComponent("spec/\(name)"))
    }

    static func loader() throws -> SpecLoader { try SpecLoader(data: data("runtime.min.json")) }

    static func golden() throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: data("golden.json")) as! [String: Any]
    }
}

/// 双端行为等价测试 · iOS 半边（工程骨架 §六.6）。与 Android `GoldenTest` 读**同一个** golden.json。
/// 基准由 Python 参考实现产出，不由任一端产出。
final class GoldenTests: XCTestCase {

    private func num(_ v: Any?) -> NSNumber { v as! NSNumber }
    private func obj(_ v: Any?) -> [String: Any] { v as! [String: Any] }
    private func arr(_ v: Any?) -> [Any] { v as! [Any] }

    func testIrCasesFieldByField() throws {
        let loader = try SpecFiles.loader()
        let cases = arr(try SpecFiles.golden()["cases"])
        XCTAssertFalse(cases.isEmpty, "golden 用例为空")
        var checked = 0, drops = 0, iosChecked = 0
        for c in cases.map(obj) {
            let sem = c["semantic"] as! String
            let hw = CipherHapticHardwareClass(rawValue: c["hardwareClass"] as! String)!
            let scale = num(c["globalScale"]).floatValue
            let label = "\(sem) × \(hw.rawValue) × scale=\(scale)"
            let rw = try loader.resolve(sem, hw, scale)

            if let d = c["drop"] as? String {
                XCTAssertNil(rw, "\(label) 期望 drop=\(d)，实际产出了 IR")
                drops += 1
                continue
            }
            guard let r = rw else { XCTFail("\(label) 期望产出 IR，实际 drop 了"); continue }
            XCTAssertEqual(r.validate(), [], "\(label) 产出非法 IR")

            let g = obj(c["ir"])
            XCTAssertEqual(g["semanticId"] as? String, r.semanticId, label)
            XCTAssertEqual(g["effectId"] as? String, r.effectId, label)
            XCTAssertEqual(g["category"] as? String, r.category.rawValue, label)
            XCTAssertEqual(g["kind"] as? String, r.kind.rawValue, label)
            XCTAssertEqual(num(g["totalDurationMs"]).intValue, r.totalDurationMs, "\(label) totalDurationMs")
            XCTAssertEqual(num(g["loopGapMs"]).intValue, r.loopGapMs, "\(label) loopGapMs")
            XCTAssertEqual(g["protected"] as? Bool, r.protectedFromPreemption, "\(label) protected")
            XCTAssertEqual(g["degradeTrace"] as? [String], r.degradeTrace, "\(label) degradeTrace")

            let ge = arr(g["events"]).map(obj)
            XCTAssertEqual(ge.count, r.events.count, "\(label) 事件个数")
            for (j, (e, a)) in zip(ge, r.events).enumerated() {
                XCTAssertEqual(num(e["atMs"]).intValue, a.atMs, "\(label) events[\(j)].atMs")
                XCTAssertEqual(num(e["durationMs"]).intValue, a.durationMs, "\(label) events[\(j)].durationMs")
                XCTAssertEqual(e["kind"] as? String, a.kind.rawValue, "\(label) events[\(j)].kind")
                XCTAssertEqual(num(e["intensity"]).floatValue, a.intensity, accuracy: 1e-5, "\(label) events[\(j)].intensity")
                XCTAssertEqual(num(e["sharpness"]).floatValue, a.sharpness, accuracy: 1e-5, "\(label) events[\(j)].sharpness")
            }

            if let gc = g["continuous"] as? [String: Any] {
                guard let ac = r.continuous else { XCTFail("\(label) 期望有 continuous 块"); continue }
                XCTAssertEqual(num(gc["maxDurationMs"]).intValue, ac.maxDurationMs, label)
                XCTAssertEqual(num(gc["segmentMs"]).intValue, ac.segmentMs, label)
                XCTAssertEqual(num(gc["idleTimeoutMs"]).intValue, ac.idleTimeoutMs, label)
            }

            // IR → CHHapticEvent 构造参数（reference/translate.py:to_ios_events）
            if let gi = c["ios"] as? [[String: Any]] {
                let got = IOSTranslator.events(r)
                XCTAssertEqual(gi.count, got.count, "\(label) ios 事件个数")
                for (j, (w, a)) in zip(gi, got).enumerated() {
                    XCTAssertEqual(w["eventType"] as? String, a.eventType.rawValue, "\(label) ios[\(j)].eventType")
                    XCTAssertEqual(num(w["relativeTime"]).doubleValue, a.relativeTime, accuracy: 1e-9, "\(label) ios[\(j)].relativeTime")
                    XCTAssertEqual(num(w["intensity"]).floatValue, a.intensity, accuracy: 1e-5, "\(label) ios[\(j)].intensity")
                    XCTAssertEqual(num(w["sharpness"]).floatValue, a.sharpness, accuracy: 1e-5, "\(label) ios[\(j)].sharpness")
                    if let d = w["duration"] {
                        XCTAssertEqual(num(d).doubleValue, a.duration ?? -1, accuracy: 1e-9, "\(label) ios[\(j)].duration")
                    } else {
                        XCTAssertNil(a.duration, "\(label) ios[\(j)] transient 不得带 duration（P-12）")
                    }
                }
                iosChecked += 1
            }
            checked += 1
        }
        XCTAssertGreaterThan(checked, 0)
        XCTAssertEqual(iosChecked, checked, "每个 IR 用例都应带 ios 基准")
        print("golden 等价：\(checked) 个 IR 用例（含 ios 翻译）+ \(drops) 个 drop 用例")
    }

    /// golden 曾停在 42 个用例、漏掉唯一的 looping 效果（审查 D5）。用例集必须等于语义 × 3 档 × 2 缩放。
    func testGoldenCoversAllSemantics() throws {
        let loader = try SpecFiles.loader()
        let cases = arr(try SpecFiles.golden()["cases"]).map(obj)
        XCTAssertEqual(Set(cases.map { $0["semantic"] as! String }), Set(loader.semanticIds), "golden 缺语义 —— 重跑 tools/golden.py")
        XCTAssertEqual(cases.count, loader.semanticIds.count * 3 * 2)
    }

    /// Decision 三端对拍：drop 原因、表达形式、looping 时长。iOS 运行时不用 form，但必须照样算出。
    func testDecisionsFieldByField() throws {
        let loader = try SpecFiles.loader()
        let g = try SpecFiles.golden()
        let ctxs = obj(g["decisionContexts"])
        let ds = arr(g["decisions"]).map(obj)
        XCTAssertFalse(ds.isEmpty, "golden 缺 decisions —— 重跑 tools/golden.py")
        for d in ds {
            let sem = d["semantic"] as! String
            let cj = obj(ctxs[d["ctx"] as! String])
            let mute: SystemMute
            switch cj["mute"] as! String {
            case "none": mute = .none
            case "dnd": mute = .dnd
            default: mute = .hardware
            }
            let ctx = PipelineContext(
                masterEnabled: cj["masterEnabled"] as! Bool,
                systemHapticsEnabled: cj["systemHapticsEnabled"] as! Bool,
                mute: mute,
                globalScale: num(cj["globalScale"]).floatValue,
                hardwareClass: CipherHapticHardwareClass(rawValue: cj["hardwareClass"] as! String)!,
                apiGate: ApiGate(compositionSupported: cj["compositionSupported"] as! Bool))
            let loop = (d["loopMaxDurationMs"] as? NSNumber)?.intValue
            let label = "\(sem) × \(d["ctx"]!) × loop=\(String(describing: loop))"
            let got = DecisionPipeline.decide(semanticId: sem, opts: PlayOpts(loopMaxDurationMs: loop), ctx: ctx,
                                              loader: loader, active: [], capacity: 2, coalesceWindowMs: 100)
            let want = obj(d["decision"])
            switch got {
            case .drop(let reason):
                XCTAssertEqual(want["drop"] as? String, reason, label)
            case .play(let p):
                guard let w = want["play"] as? [String: Any] else {
                    XCTFail("\(label) 期望 drop=\(want["drop"] ?? "?")，实际 play"); continue
                }
                XCTAssertEqual(w["effectId"] as? String, p.resolved.effectId, "\(label) effectId")
                XCTAssertEqual(w["form"] as? String, p.form.rawValue, "\(label) form")
                XCTAssertEqual((w["loopDeadlineMs"] as? NSNumber)?.intValue, p.loopDeadlineMs, "\(label) loopDeadlineMs")
            }
        }
    }
}
