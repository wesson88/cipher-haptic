import XCTest
@testable import CipherHapticCore

/// 抢占规则矩阵（§8.3）—— 与 Android `PreemptionPolicyTest` 逐条对应。
final class PreemptionPolicyTests: XCTestCase {
    private func h(_ id: Int, _ cat: CipherHapticCategory, elapsed: Int = 9_999, kind: WaveKind = .oneshot,
                   protected: Bool = false, state: String = "Active") -> PreemptionPolicy.ActiveHandleInfo {
        .init(id: id, category: cat, kind: kind, elapsedMs: elapsed, protectedFromPreemption: protected, state: state)
    }

    private func targets(_ c: CipherHapticCategory, _ a: [PreemptionPolicy.ActiveHandleInfo],
                         capacity: Int = 4, coalesce: Int = 100) -> [Int] {
        PreemptionPolicy.computeTargets(newCategory: c, active: a, capacity: capacity, coalesceWindowMs: coalesce)
    }

    func testUxPreemptsOldestUx() { XCTAssertEqual(targets(.ux, [h(1, .ux), h(2, .ux)]), [1]) }

    func testUxDoesNotPreemptHigher() { XCTAssertEqual(targets(.ux, [h(1, .alert), h(2, .critical)]), []) }

    func testAlertOnlySameLevelWhenCapacityAllows() {
        XCTAssertEqual(targets(.alert, [h(1, .ux), h(2, .alert), h(3, .alert), h(4, .critical)], capacity: 8), [2])
    }

    func testCriticalDoesNotClearWhenCapacityAllows() {
        XCTAssertEqual(targets(.critical, [h(1, .ux), h(2, .alert)], capacity: 4), [])
    }

    func testOverflowSacrificesLowestOldest() {
        XCTAssertEqual(targets(.critical, [h(1, .alert), h(2, .ux), h(3, .ux)], capacity: 2), [2, 3])
    }

    func testCoalesceWindow() {
        XCTAssertEqual(targets(.ux, [h(1, .ux, elapsed: 50)]), [], "连点窗口内不抢")
        XCTAssertEqual(targets(.ux, [h(1, .ux, elapsed: 150)]), [1], "窗口外恢复抢占")
    }

    func testContinuousNotInterruptedByUx() {
        XCTAssertEqual(targets(.ux, [h(1, .ux, kind: .continuous, protected: true)]), [])
    }

    func testGraceZombiesDoNotOccupySlots() {
        XCTAssertEqual(targets(.critical, [h(1, .ux, state: "Cancelled"), h(2, .ux, state: "Completed")], capacity: 1), [])
    }

    func testProtectedSacrificedLast() {
        let t = targets(.critical, [h(1, .ux, kind: .continuous, protected: true), h(2, .ux)], capacity: 2)
        XCTAssertEqual(t, [2], "受保护的 continuous 最后才被牺牲")
    }

    func testEmpty() { XCTAssertEqual(targets(.critical, []), []) }
}

/// trailing coalesce（§七.5）—— 与 Android `ContinuousCoalescerTest` 对应。
final class ContinuousCoalescerTests: XCTestCase {
    private var sent: [(Float, Float)] = []
    private var sched: TestScheduler!
    private var c: ContinuousCoalescer!

    override func setUp() {
        sent = []
        sched = TestScheduler()
        c = ContinuousCoalescer(scheduler: sched) { [unowned self] i, s in self.sent.append((i, s)) }
    }

    func testFirstUpdateSendsImmediately() {
        c.update(0.3, 0.1)
        XCTAssertEqual(sent.map { $0.0 }, [0.3])
    }

    func testBurstCoalescesToTrailingLatest() {
        c.update(0.1, 0)
        sched.advance(5)
        c.update(0.2, 0)
        c.update(0.3, 0)
        XCTAssertEqual(sent.count, 1)
        sched.advance(20)
        XCTAssertEqual(sent.map { $0.0 }, [0.1, 0.3], "补发的是最新值")
    }

    func testLastValueAlwaysSent() {
        c.update(0.1, 0)
        c.update(0.9, 0)
        sched.advance(100)
        XCTAssertEqual(sent.last?.0, 0.9)
    }

    func testFlushPendingSendsNow() {
        c.update(0.1, 0)
        c.update(0.7, 0)
        c.flushPending()
        XCTAssertEqual(sent.last?.0, 0.7)
        XCTAssertEqual(sched.pendingTimers(), 0)
    }

    func testResetCancelsFlush() {
        c.update(0.1, 0)
        c.update(0.7, 0)
        c.reset()
        sched.advance(100)
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(sched.pendingTimers(), 0)
        XCTAssertNil(c.latest())
    }

    func testBufferDoesNotSend() {
        c.buffer(0.4, 0.2)
        XCTAssertTrue(sent.isEmpty)
        XCTAssertEqual(c.latest()?.0, 0.4)
    }

    func testMarkSentAtThrottlesFirstUpdate() {
        c.markSentAt(sched.nowMs())
        c.update(0.5, 0)
        XCTAssertTrue(sent.isEmpty, "起播后 16ms 内的第一次 update 应进补发")
        sched.advance(16)
        XCTAssertEqual(sent.map { $0.0 }, [0.5])
    }
}

/// 决策管线（B.3 / §3.3）—— 与 Android `DecisionPipelineTest` 对应。
final class DecisionPipelineTests: XCTestCase {
    private var loader: SpecLoader!
    override func setUpWithError() throws { loader = try SpecFiles.loader() }

    private func ctx(master: Bool = true, system: Bool = true, mute: SystemMute = .none,
                     hw: CipherHapticHardwareClass = .linearXFull, composition: Bool = true) -> PipelineContext {
        PipelineContext(masterEnabled: master, systemHapticsEnabled: system, mute: mute, globalScale: 1,
                        hardwareClass: hw, apiGate: ApiGate(compositionSupported: composition))
    }

    private func decide(_ sem: String, _ c: PipelineContext? = nil, loop: Int? = nil,
                        active: [PreemptionPolicy.ActiveHandleInfo] = []) -> Decision {
        DecisionPipeline.decide(semanticId: sem, opts: PlayOpts(loopMaxDurationMs: loop), ctx: c ?? ctx(),
                                loader: loader, active: active, capacity: 2, coalesceWindowMs: 100)
    }

    private func reason(_ d: Decision) -> String? { if case .drop(let r) = d { return r } else { return nil } }
    private func play(_ d: Decision) -> Decision.Play? { if case .play(let p) = d { return p } else { return nil } }

    func testB1SustainNeverComposition() {
        let p = play(decide("item.detach"))
        XCTAssertTrue(p?.resolved.events.contains { $0.kind == .sustain } ?? false, "前提：ticket_rip 含 sustain")
        XCTAssertEqual(p?.form, .waveform)
    }

    func testDropOrder() {
        XCTAssertEqual(reason(decide("item.dissolve", ctx(master: false, system: false))), "disabled")
        XCTAssertEqual(reason(decide("item.dissolve", ctx(system: false, mute: .dnd))), "system-off")
        XCTAssertEqual(reason(decide("item.dissolve", ctx(mute: .dnd))), "dnd")
        XCTAssertEqual(reason(decide("item.dissolve", ctx(mute: .hardware))), "hardware-mute")
        XCTAssertNotNil(play(decide("security.intrusion", ctx(mute: .dnd))), "critical 绕过静音")
        XCTAssertEqual(reason(decide("control.tap", ctx(hw: .ermZ))), "degraded-to-silent")
    }

    func testLoopingDurationFromApp() {
        XCTAssertEqual(reason(decide("security.alarm")), "looping-needs-token")
        XCTAssertEqual(reason(decide("security.alarm", loop: 0)), "looping-needs-duration")
        XCTAssertEqual(play(decide("security.alarm", loop: 2_000))?.loopDeadlineMs, 2_000)
        XCTAssertEqual(play(decide("security.alarm", loop: Int.max))?.loopDeadlineMs, DecisionPipeline.maxLoopDurationMs)
        XCTAssertNil(play(decide("item.dissolve", loop: 2_000))?.loopDeadlineMs)
    }

    func testPreemptTargetsCarriedInDecision() {
        let active = (1...2).map {
            PreemptionPolicy.ActiveHandleInfo(id: $0, category: .ux, kind: .oneshot, elapsedMs: 500,
                                              protectedFromPreemption: false, state: "Active")
        }
        XCTAssertFalse(play(decide("security.intrusion", active: active))?.preemptTargets.isEmpty ?? true)
    }

    func testUnknownSemanticIsSpecErrorNotCrash() {
        XCTAssertEqual(reason(decide("no.such")), DropReason.specError)
    }
}

/// 内嵌资源必须与 spec/runtime.min.json 逐字节一致 —— 否则 golden 通过而线上跑的是旧数据。
final class ResourceEmbedTests: XCTestCase {
    func testEmbeddedRuntimeMatchesSpec() throws {
        let embedded = try XCTUnwrap(SpecLoader.embeddedData(), "runtime.min.json 未内嵌")
        XCTAssertEqual(embedded, try SpecFiles.data("runtime.min.json"), "内嵌副本过期 —— 重跑 tools/extract.py")
        XCTAssertNoThrow(try SpecLoader.embedded())
    }
}
