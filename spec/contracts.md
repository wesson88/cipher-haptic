# CipherHaptic 对外契约 · 方法签名基准

> 【生成产物 · 禁止手改】由 `tools/extract.py` 从工程骨架 §3.1 的 facade 代码块抽取。
> `contract-check` 以本文件的**方法签名清单**对拍双端原生接口，**不以条目数对拍**。

**方法总数：17**（对应 11 项能力条目——能力是给业务方读的分组，方法才是对拍单位）

| # | 签名（iOS 基准） |
|---|---|
| 1 | `public nonisolated func playEffect(_ s: CipherHapticSemantic)` |
| 2 | `public nonisolated func playEffect(_ s: CipherHapticSemantic, onNextFrame: Bool)` |
| 3 | `public nonisolated func playLoopingEffect(_ s: CipherHapticSemantic) -> CipherHapticCancelToken` |
| 4 | `public nonisolated func stopAllEffects()` |
| 5 | `public nonisolated func updateContinuousEffect(intensity: Float, sharpness: Float)` |
| 6 | `public nonisolated func endContinuousEffect()` |
| 7 | `public nonisolated func prepare(_ s: CipherHapticSemantic)` |
| 8 | `public nonisolated func preview(_ s: CipherHapticSemantic) -> CipherHapticAvailability` |
| 9 | `public nonisolated func setHapticsEnabled(_ enabled: Bool)` |
| 10 | `public nonisolated func isHapticsEnabled() -> Bool` |
| 11 | `public nonisolated func setGlobalScale(_ scale: Float)` |
| 12 | `public nonisolated func globalScale() -> Float` |
| 13 | `public nonisolated func syncSystemMuteState() -> MuteState` |
| 14 | `public nonisolated func registerMuteObserver(_ o: MuteStateObserver)` |
| 15 | `public nonisolated func unregisterMuteObserver(_ o: MuteStateObserver)` |
| 16 | `public nonisolated func hardwareCapabilities() -> CipherHapticCapabilities` |
| 17 | `public nonisolated func engineState() -> CipherHapticEngineState` |
