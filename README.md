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
| 3. Android 骨架编译 + 单测 | ⬜ 需 JDK 17（现 SDK 已就绪、JDK 仅 11） |
| 4. `contract-check` 四件事 | 🟡 CI 规则与 FSM 不变式已跑通；双端签名对拍待原生代码 |
| 5. FSM 不变式 fuzz | 🟡 静态穷举已通；随机序列 fuzz 待原生 runner |
| 6–10 | ⬜ |

---

## 目录

```
spec/          ★ 生成产物,禁止手改 —— 由 tools/extract.py 从 vault 单向生成
  contracts.md      17 方法签名基准（← 工程骨架 §3.1）
  semantics.yaml    7 个语义 token（← 语义层与中立IR §2.2）
  effects.yaml      7 个效果波形（← 波形数据SSOT §二）
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
  check.py       12 条 CI 规则 + 5 条 FSM 不变式
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
