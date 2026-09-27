import XCTest
@testable import CipherHaptic
import CipherHapticCore

/// facade 回归（fake gateway + 假时钟）。移植自 Android `CipherHapticTest` / `LoopingAndFormTest`，
/// 并覆盖 iOS 独有的 engine 自愈 / 熔断、以及审查里 Android 尚未修的几条（B2 / B5 / A4）。
final class CipherHapticTests: XCTestCase {

    // MARK: - looping：每轮有限提交、间隔由引擎表达、时长由应用告知（A1–A3 / P-17）

    func testLoopingSubmitsOneFiniteRoundPerCycle() throws {
        let r = try Rig()
        _ = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 10_000)
        r.sched.advance(520 * 3 + 10)          // 一轮 = 0/90ms 两击 + loopGap 400 = 520ms
        XCTAssertEqual(r.gw.oneShotPlayers.count, 4, "0 / 520 / 1040 / 1560 各提交一轮")
        let first = try XCTUnwrap(r.gw.oneShotPlayers.first)
        XCTAssertEqual(first.events.map(\.relativeTime), [0, 0.09], "pattern 只含一轮，不含 gap")
        XCTAssertTrue(first.events.allSatisfy { $0.eventType == .hapticTransient })
        XCTAssertEqual(r.gw.oneShotPlayers.filter(\.isPlaying).count, 1, "上一轮的 player 在重提交时已释放")
    }

    func testLoopingOnErmBeatsOnePulsePerRound() throws {
        let r = try Rig(hw: .ermZ)
        _ = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 10_000)
        r.sched.advance(600 * 2 + 10)          // forced_amplitude 200ms + gap 400
        XCTAssertEqual(r.gw.oneShotPlayers.count, 3)
        XCTAssertTrue(r.gw.oneShotPlayers.allSatisfy { $0.events.count == 1 && $0.events[0].intensity == 1 })
    }

    func testResubmitFailureStopsAndIsReclaimed() throws {
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 10_000)
        XCTAssertEqual(r.gw.oneShotPlayers.count, 1)
        r.gw.failing = true
        r.sched.advance(10_530)
        XCTAssertEqual(r.gw.oneShotPlayers.count, 1, "失败后不再提交")
        XCTAssertFalse(r.gw.oneShotPlayers[0].isPlaying, "report 必须停马达（A1 同类）")
        XCTAssertTrue(token.isFinished)
        // 审查 B2：Failed 必须被回收（Android 没有 EXPIRE 的发送方）
        XCTAssertEqual(r.runtime.activeHandleCount, 0, "Failed 的 handle 必须被 EXPIRE 回收")
        XCTAssertEqual(r.metrics.failCount, 1, "失败只计一次")
        XCTAssertEqual(r.metrics.leakSuspectCount, 0)
        XCTAssertEqual(r.sched.pendingTimers(), 0, "没有定时器泄漏")
    }

    func testLoopingEndsWhenAppToldDurationExpires() throws {
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 2_000)
        r.sched.advance(1_500)
        XCTAssertFalse(token.isFinished)
        r.sched.advance(1_000)
        XCTAssertTrue(token.isFinished, "★ 时长到期必须结束")
        XCTAssertFalse(token.isCancelled, "到期结束不是业务方取消")
        let n = r.gw.oneShotPlayers.count
        r.sched.advance(5_000)
        XCTAssertEqual(r.gw.oneShotPlayers.count, n, "结束后不得再提交")
        XCTAssertEqual(r.runtime.activeHandleCount, 0)
    }

    func testLoopingDurationClampedToLibraryCap() throws {
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: Int.max)
        r.sched.advance(CipherHaptic.maxLoopDurationMs - 1_000)
        XCTAssertFalse(token.isFinished)
        r.sched.advance(2_000)
        XCTAssertTrue(token.isFinished, "★ 超过库上限按上限截断")
    }

    func testLoopingInvalidDurationDrops() throws {
        let r = try Rig()
        let d = RecordingDelegate()
        r.haptic.debugDelegate = d
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 0)
        XCTAssertTrue(r.gw.players.isEmpty)
        XCTAssertEqual(d.drops.map { $0.1 }, ["looping-needs-duration"])
        XCTAssertTrue(token.isFinished)
        XCTAssertFalse(token.isCancelled)
    }

    func testPlayEffectRefusesLooping() throws {
        let r = try Rig()
        let d = RecordingDelegate()
        r.haptic.debugDelegate = d
        r.haptic.playEffect(.securityAlarm)
        XCTAssertTrue(r.gw.players.isEmpty, "拿不到停止手段的循环宁可不播（2026-08-02 真机事故）")
        XCTAssertEqual(d.drops.map { $0.1 }, ["looping-needs-token"])
    }

    func testTokenCancelStopsOnlyItsOwnPlayer() throws {
        // 审查 B5：Android 的 stop 动作调全局 cancelAll，会把别的效果一起掐掉。iOS 只停本 player（P-03）
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 10_000)
        r.haptic.playEffect(.notifyMessage)
        XCTAssertEqual(r.gw.oneShotPlayers.count, 2)
        token.cancel()
        XCTAssertTrue(token.isCancelled)
        XCTAssertFalse(r.gw.oneShotPlayers[0].isPlaying, "被取消的循环停了")
        XCTAssertTrue(r.gw.oneShotPlayers[1].isPlaying, "★ 另一个效果不受影响")
        r.sched.advance(100)
        XCTAssertTrue(token.isFinished)
    }

    // MARK: - 生命周期与回收

    func testOneShotReclaimedWithoutLeak() throws {
        let r = try Rig()
        r.haptic.playEffect(.controlTap)
        XCTAssertEqual(r.gw.oneShotPlayers.count, 1)
        r.sched.advance(1_000)
        XCTAssertEqual(r.runtime.activeHandleCount, 0, "推送式回收：没有后续调用也要摘表")
        XCTAssertEqual(r.metrics.leakSuspectCount, 0)
        XCTAssertEqual(r.sched.pendingTimers(), 0)
        XCTAssertEqual(r.metrics.playSubmittedCount, 1)
    }

    func testStopAllThenDrainLeavesNothing() throws {
        let r = try Rig()
        _ = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 60_000)
        r.haptic.playEffect(.itemDetach)
        r.haptic.updateContinuousEffect(intensity: 0.5, sharpness: 0.5)
        r.haptic.stopAllEffects()
        r.sched.advance(200)
        XCTAssertEqual(r.runtime.activeHandleCount, 0)
        XCTAssertEqual(r.metrics.leakSuspectCount, 0)
        XCTAssertEqual(r.sched.pendingTimers(), 0)
        XCTAssertTrue(r.gw.players.allSatisfy { !$0.isPlaying })
    }

    func testDisablingStopsEverything() throws {
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 60_000)
        r.haptic.setHapticsEnabled(false)
        XCTAssertFalse(r.haptic.isHapticsEnabled())
        r.sched.advance(100)
        XCTAssertTrue(token.isFinished)
        r.haptic.playEffect(.controlTap)
        XCTAssertEqual(r.metrics.dropCountsByReason["disabled"], 1)
    }

    func testCriticalSuspendKeepsAliveThenExpires() throws {
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 120_000)
        r.life.suspend?()
        r.sched.advance(PlaybackHandle.keepAliveMs - 1_000)
        XCTAssertFalse(token.isFinished, "critical 进后台先保活")
        r.sched.advance(2_000)
        XCTAssertTrue(token.isFinished, "保活窗口到期结束")
    }

    func testResumeClearsKeepAlive() throws {
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 120_000)
        r.life.suspend?()
        r.sched.advance(10_000)
        r.life.resume?()
        r.sched.advance(PlaybackHandle.keepAliveMs)
        XCTAssertFalse(token.isFinished)
        token.cancel()
    }

    // MARK: - 抢占（§八）

    func testSameLevelPreemptionOutsideCoalesceWindow() throws {
        let r = try Rig()
        r.haptic.playEffect(.itemDetach)
        r.sched.advance(150)
        r.haptic.playEffect(.itemDetach)
        XCTAssertEqual(r.gw.oneShotPlayers.count, 2)
        XCTAssertFalse(r.gw.oneShotPlayers[0].isPlaying, "同级 FIFO：旧的被抢")
        XCTAssertEqual(r.metrics.preemptedCount, 1)
    }

    // MARK: - 连续通道（§4.6 / §七.5 / IR §3.2b）

    func testContinuousStartsWithFingerValueAndCoalesces() throws {
        let r = try Rig()
        r.haptic.updateContinuousEffect(intensity: 0.3, sharpness: 0.2)
        let p = try XCTUnwrap(r.gw.continuousPlayers.first)
        XCTAssertEqual(p.initialControls?.0, 0.3, "起播强度取手指当前值，不是 IR 的 initialIntensity")
        XCTAssertEqual(p.initialControls?.1, 0.2)
        XCTAssertEqual(p.events.first?.eventType, .hapticContinuous)
        r.sched.advance(5)
        r.haptic.updateContinuousEffect(intensity: 0.6, sharpness: 0.1)
        r.haptic.updateContinuousEffect(intensity: 0.8, sharpness: 0.1)
        XCTAssertTrue(p.params.isEmpty, "起播后 16ms 内进补发，不连发")
        r.sched.advance(20)
        XCTAssertEqual(p.params.map { $0.0 }, [0.8], "补发的是最新值")
    }

    func testEndContinuousFlushesThenStops() throws {
        let r = try Rig()
        r.haptic.updateContinuousEffect(intensity: 0.3, sharpness: 0)
        r.sched.advance(5)
        r.haptic.updateContinuousEffect(intensity: 0.9, sharpness: 0)
        r.haptic.endContinuousEffect()
        let p = try XCTUnwrap(r.gw.continuousPlayers.first)
        XCTAssertEqual(p.params.last?.0, 0.9, "结束前 flush 未决值")
        XCTAssertFalse(p.isPlaying)
        r.sched.advance(100)
        XCTAssertEqual(r.runtime.activeHandleCount, 0)
        XCTAssertEqual(r.sched.pendingTimers(), 0, "A4：补发块不得在结束后残留")
    }

    func testContinuousIdleTimeoutStopsMotor() throws {
        let r = try Rig()
        r.haptic.updateContinuousEffect(intensity: 0.5, sharpness: 0.5)
        r.sched.advance(1_500 + 100)
        let p = try XCTUnwrap(r.gw.continuousPlayers.first)
        XCTAssertFalse(p.isPlaying, "idle 超时进 Completed 必须停马达 —— 否则按最后强度一直震到 30s")
        XCTAssertEqual(r.runtime.activeHandleCount, 0)
        // 通道已结束后的新手势重新起播
        r.haptic.updateContinuousEffect(intensity: 0.4, sharpness: 0.4)
        XCTAssertEqual(r.gw.continuousPlayers.count, 2)
    }

    func testContinuousRenewsBeforeMaxDurationInvisibleToFsm() throws {
        let r = try Rig()
        for _ in 0..<31 {
            r.haptic.updateContinuousEffect(intensity: 0.5, sharpness: 0.5)
            r.sched.advance(1_000)
        }
        XCTAssertEqual(r.gw.continuousPlayers.count, 2, "30s 单次排程上限前续排一次")
        XCTAssertFalse(r.gw.continuousPlayers[0].isPlaying)
        XCTAssertTrue(r.gw.continuousPlayers[1].isPlaying)
        XCTAssertEqual(r.runtime.debugHandleStates(), ["Active/continuous": 1], "续排对 FSM 不可见")
        r.haptic.endContinuousEffect()
    }

    // MARK: - engine 自愈 / 熔断（P-16，iOS 独有）

    func testEngineResetCancelsActiveAndRestartsLazily() throws {
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 60_000)
        XCTAssertEqual(r.haptic.engineState(), .running)
        r.gw.onInterruption?(.reset)
        XCTAssertEqual(r.gw.stopsSeen, [true])
        XCTAssertEqual(r.haptic.engineState(), .recovering)
        XCTAssertFalse(r.gw.oneShotPlayers[0].isPlaying, "reset 后活跃 handle 收 CANCEL(engine_reset)，不恢复播放")
        r.sched.advance(100)
        XCTAssertTrue(token.isFinished)
        XCTAssertEqual(r.metrics.engineRestartCount, 1)
        r.haptic.playEffect(.controlTap)
        XCTAssertEqual(r.gw.startAttempts, 2, "下次提交时懒重启")
        XCTAssertEqual(r.haptic.engineState(), .running)
    }

    func testBackgroundStopDoesNotKillCritical() throws {
        let r = try Rig()
        let token = r.haptic.playLoopingEffect(.securityAlarm, maxDurationMs: 60_000)
        r.gw.onInterruption?(.stopped(suspended: true))
        r.sched.advance(100)
        XCTAssertFalse(token.isFinished, "进后台的 stop 交给 SUSPEND 的 keepAlive 分支，不一刀切 CANCEL")
        token.cancel()
    }

    func testCircuitOpensAfterRepeatedStartFailures() throws {
        let r = try Rig()
        r.gw.failStart = true
        for _ in 0..<EngineController.startFailLimit { r.haptic.playEffect(.controlTap) }
        XCTAssertEqual(r.haptic.engineState(), .circuitOpen)
        XCTAssertEqual(r.metrics.circuitOpenCount, 1)
        let attempts = r.gw.startAttempts
        r.haptic.playEffect(.controlTap)
        XCTAssertEqual(r.gw.startAttempts, attempts, "冷却期内不再尝试启动，直接 FAIL")
        XCTAssertEqual(r.metrics.failCount, EngineController.startFailLimit + 1)
        r.gw.failStart = false
        r.sched.advance(EngineController.circuitCooldownMs)
        r.haptic.playEffect(.controlTap)
        XCTAssertEqual(r.haptic.engineState(), .running)
        XCTAssertEqual(r.gw.oneShotPlayers.count, 1)
        r.sched.advance(1_000)
        XCTAssertEqual(r.runtime.activeHandleCount, 0, "失败的 handle 全部回收")
    }

    func testResetStormOpensCircuit() throws {
        let r = try Rig()
        r.haptic.playEffect(.controlTap)
        for _ in 0..<EngineController.resetStormLimit { r.gw.onInterruption?(.reset) }
        XCTAssertEqual(r.haptic.engineState(), .circuitOpen, "短时间频繁 reset 视为 session 冲突")
    }

    // MARK: - 可用性 / 能力 / 其他接口

    func testPreviewMatchesPipeline() throws {
        let r = try Rig()
        XCTAssertEqual(r.haptic.preview(.securityAlarm), CipherHapticAvailability(willPlay: true, degradedTo: nil, reason: nil))
        r.haptic.setHapticsEnabled(false)
        XCTAssertEqual(r.haptic.preview(.controlTap).reason, "disabled")
        let e = try Rig(hw: .ermZ)
        XCTAssertEqual(e.haptic.preview(.controlTap),
                       CipherHapticAvailability(willPlay: false, degradedTo: "silent", reason: "degraded-to-silent"))
        XCTAssertEqual(e.haptic.preview(.securityAlarm).degradedTo, "forced_amplitude")
    }

    func testCapabilitiesAndHardwareClass() throws {
        let r = try Rig()
        XCTAssertEqual(r.haptic.hardwareCapabilities(),
                       CipherHapticCapabilities(hardwareClass: .linearXFull, supportsSharpness: true,
                                                supportsBackgroundPlayback: false, systemHapticsEnabled: true))
        let f = try Rig(supportsHaptics: false)
        XCTAssertEqual(f.haptic.hardwareCapabilities().hardwareClass, .ermZ, "无 Core Haptics → ERM_Z（B.8）")
        XCTAssertFalse(f.haptic.hardwareCapabilities().supportsSharpness)
        XCTAssertEqual(try Rig(hw: .linearXLimited).haptic.hardwareCapabilities().hardwareClass, .linearXLimited)
    }

    func testGlobalScaleClampedAndApplied() throws {
        let r = try Rig()
        r.haptic.setGlobalScale(2)
        XCTAssertEqual(r.haptic.globalScale(), 1)
        r.haptic.setGlobalScale(0.5)
        r.haptic.playEffect(.controlTap)
        XCTAssertEqual(r.gw.oneShotPlayers[0].events[0].intensity, 0.25, accuracy: 1e-5, "0.5 × 0.5")
        XCTAssertEqual(r.gw.oneShotPlayers[0].events[0].sharpness, 0.5, accuracy: 1e-5, "变轻不变钝（P-04）")
    }

    func testOnNextFrameSubmitsAtFrameBoundary() throws {
        let r = try Rig()
        r.haptic.playEffect(.controlTap, onNextFrame: true)
        XCTAssertTrue(r.gw.players.isEmpty)
        r.frame.tick()
        XCTAssertEqual(r.gw.oneShotPlayers.count, 1)
    }

    func testPrepareStartsEngineAndPrecompiles() throws {
        let r = try Rig()
        r.haptic.prepare(.itemDissolve)
        XCTAssertEqual(r.haptic.engineState(), .running)
        XCTAssertEqual(r.gw.prepared.count, 1)
    }

    func testMuteObserverRegistration() throws {
        final class Obs: MuteStateObserver { func onMuteStateChanged(_ state: MuteState) {} }
        let r = try Rig()
        let o = Obs()
        r.haptic.registerMuteObserver(o)
        r.haptic.unregisterMuteObserver(o)
        XCTAssertEqual(r.haptic.syncSystemMuteState(), .unmuted)
    }

    func testMetricsSinkAndDegradeReporting() throws {
        let r = try Rig(hw: .linearXLimited)
        let d = RecordingDelegate()
        r.haptic.debugDelegate = d
        r.haptic.playEffect(.securityIntrusion)
        XCTAssertEqual(r.sink.snapshots.count, 1, "首次播放即上报一次，此后 60s 低频")
        XCTAssertFalse(d.degrades.isEmpty)
        XCTAssertNil(r.metrics.degradeCountsByAction["full"], "`full` 不计入降级")
    }

    func testSemanticCasesMatchSpec() throws {
        XCTAssertEqual(Set(CipherHapticSemantic.allCases.map(\.rawValue)), Set(try SpecFiles.loader().semanticIds),
                       "CI 规则 7：Swift 枚举 case 集合 = semantics.yaml 的 key 集合")
    }
}
