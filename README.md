# CipherHaptic

CipherLex 生态专用的线性马达触觉渲染库 · iOS 13+ / Android API 29+

设计文档是本仓库的 **SSOT（唯一写入点）**，位于 vault：
`D:\wiki\general-os-system\20-知识\项目记录\触觉引擎CipherHaptic-*.md`（9 篇）。

---

## 当前状态

| 落地步骤（工程骨架 §六） | 状态 |
|---|---|
| **W0–W1 · P0 真机验证 V1–V4** | ⬜ 未开始（需真机；出口条件 = `parity.yaml` 无 pending） |
| 1. `spec/` + 抽取脚本 + IR schema 定型 | ✅ **完成** |
| 2. iOS 骨架编译 + 单测 | ⬜ 需 macOS（Core Haptics 是 Apple 平台框架） |
| 3. Android 骨架编译 + 单测 | ✅ **core + library 全通，55/55 测试** |
| 4. `contract-check` 四件事 | ✅ Android 侧（规则 CT）；iOS 待 Swift |
| 5. FSM 不变式 fuzz | ✅ 三份：Python / Kotlin 模拟资源 / Kotlin 真 handle |
| 6. 双端行为等价测试 | 🟡 基准 + Android 半边就位，Swift 半边待 Mac |
| 7. 调音台 demo | ✅ **`android/demo`，APK 可装** |
| 8–10 | ⬜ 真机驱动 / P0 验证 / 可运营化 |

## 交付形态

**两个互不相干的包**，monorepo 只是开发期的组织方式：

| | 产物 | 上层引用 |
|---|---|---|
| Android | `com.cipherlex:cipher-haptic:1.0.0`（AAR）<br>+ `cipher-haptic-core`（JAR，POM 自动传递） | `implementation("com.cipherlex:cipher-haptic:1.0.0")` |
| iOS | SwiftPM 包（源码）或 XCFramework | `.package(url:…, from: "1.0.0")` |

**"Android 只打 Android 的"这个问题不存在**——Android app 的 Gradle 只解析 AAR 坐标，根本不知道 `ios/` 存在；SwiftPM 只看 `Package.swift` 声明的路径。这是不走 KMP / C++ 的红利：**没有共享二进制，就没有"哪端该打哪份"的问题**。

实测依赖树只有 **kotlin-stdlib** 一个第三方（`org.json` 是 `compileOnly`，由 Android 平台提供），**无 native code → 无 ABI 分包**。

```bash
./gradlew :library:assembleRelease        # AAR
./gradlew :core:publishToMavenLocal :library:publishToMavenLocal
./gradlew :demo:assembleDebug             # 调音台 APK
```

---

## 目录

```
spec/          ★ 生成产物,禁止手改 —— 由 tools/extract.py 从 vault 单向生成
  contracts.md      17 方法签名基准（← 工程骨架 §3.1）
  semantics.yaml    7 个语义 token（← 语义层与中立IR §2.2）
  effects.yaml      7 个效果波形（← 波形数据SSOT §二）
  runtime.min.json  ★ 各端【真正内嵌】的产物，8.0 KB —— 无 parity、无双端镜像、已归一化为 IR
  degradation.yaml  7×3=21 格 + 8 个 action（← 波形数据SSOT §三/§3.1）
  transitions.yaml  8 态 10 事件（← 句柄状态机 §十）
  parity.yaml       20 条双端差异（← 双端差异登记表 §四）

reference/     参考实现（Python）—— 不参与运行时
  model.py       IR 类型 + validate()
  degrade.py     8 个降级 action 的确定性变换
  translate.py   IR → 双端对象 + 反向累加校验
  loader.py      SpecLoader：spec/*.yaml → 中立模型 → resolve()

tools/
  extract.py     vault → spec/（单向。--check 即 CI 规则 10）
  check.py       CI 规则 + FSM 不变式 + contracts.md 签名对拍
  golden.py      从参考实现产出双端等价基准
  testreport.py  读 JUnit XML —— BUILD SUCCESSFUL 不等于测试跑过了

android/
  core/          纯 Kotlin/JVM：语义解析 / 决策管线 / 降级 / IR / FSM / 抢占
  library/       com.android.library：engine 接缝 + 平台桥 + facade（17 方法）
  demo/          调音台（主文档 B.9）—— 不参与发布产物
```

`reference/` 的三个职责：**证明规格可实现**、**产出黄金测试用例**（双端原生实现以它为对拍基准）、**让 CI 规则 12/13 可执行**。它不参与运行时。

---

## 用法

```bash
pip install pyyaml

python tools/extract.py            # vault → spec/
python tools/extract.py --check    # CI 规则 10：spec/ 与 vault 逐字节一致
python tools/check.py -v           # 全部规则与不变式
```

`--vault` 或环境变量 `CIPHERHAPTIC_VAULT` 可覆盖文档路径。

---

## 两条铁律

1. **`spec/` 禁止手改。** 唯一写入点是 vault 文档。改 spec/ 会在 CI 规则 10 被逐字节 diff 打回。
2. **抽取器抽不到就红，不静默降级。** 找不到锚点、锚点命中多个、表格列数不符，一律硬失败——静默产出一份不完整的 spec/ 比抽取失败危险得多。
