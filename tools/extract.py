#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CipherHaptic spec 抽取器  ——  vault SSOT 文档  →  spec/*.yaml

同步方向是【单向且唯一】的（见 SSOT 文档 §5.1）：

    vault 文档  →  本脚本  →  spec/*.yaml  →  构建时内嵌各端 Resources

`spec/` 是【生成产物，禁止手改】。CI 规则 10 会重跑本脚本并与 spec/ 逐字节 diff，
不一致即构建失败——这消除了"人工同步"这个漂移入口。

设计约束（为什么这么写）：

1. **不得有任何人工转录环节。** 每一份 spec 产物都必须能追溯到 vault 文档里的
   某个 yaml 代码块或 markdown 表格。抄一遍就等于制造第二个真相源，正是这套
   文档一路在批的失败模式（Haptico 机型表 / category 双写 / protected 双写）。

2. **定位靠内容锚点，不靠行号。** 文档会增删段落，行号必然漂移。

3. **抽不到就红，不静默降级。** 抽取器找不到锚点、找到多个、或表格列数不符，
   一律抛错。静默产出一份不完整的 spec/ 比抽取失败危险得多。

用法：
    python tools/extract.py [--vault <路径>] [--out <路径>] [--check]

    --check  只校验 spec/ 是否与重新抽取的结果一致（CI 规则 10），不写文件
"""

from __future__ import annotations

import argparse
import io
import os
import re
import sys

import yaml

# ── vault 文档定位 ──────────────────────────────────────────────────────
DEFAULT_VAULT = r"D:\wiki\general-os-system\20-知识\项目记录"

DOCS = {
    "spec":    "触觉引擎CipherHaptic-规格与架构设计-2026-07-29.md",
    "ssot":    "触觉引擎CipherHaptic-波形数据SSOT-2026-07-29.md",
    "fsm":     "触觉引擎CipherHaptic-句柄状态机-2026-07-29.md",
    "ir":      "触觉引擎CipherHaptic-语义层与中立IR-2026-07-30.md",
    "parity":  "触觉引擎CipherHaptic-双端差异登记表-2026-07-30.md",
    "skeleton":"触觉引擎CipherHaptic-工程骨架-2026-07-29.md",
}

GENERATED_HEADER = (
    "# ══════════════════════════════════════════════════════════════════\n"
    "# 【生成产物 · 禁止手改】\n"
    "# 由 tools/extract.py 从 vault SSOT 文档单向生成。\n"
    "# 唯一写入点是 vault 文档；改这里的内容会在 CI 规则 10 被逐字节 diff 打回。\n"
    "# 来源：{src}\n"
    "# ══════════════════════════════════════════════════════════════════\n"
)


class ExtractError(Exception):
    """抽取失败。一律硬失败，不静默降级。"""


# ── 基础设施 ────────────────────────────────────────────────────────────

def read_doc(vault: str, key: str) -> str:
    path = os.path.join(vault, DOCS[key])
    if not os.path.isfile(path):
        raise ExtractError(f"vault 文档不存在：{path}")
    return io.open(path, encoding="utf-8").read()


def code_block(doc: str, lang: str, contains: str, doc_name: str) -> str:
    """取出唯一一个包含 `contains` 的 ```<lang> 代码块。0 个或多个都是错误。"""
    blocks = re.findall(r"^```" + lang + r"\n(.*?)^```", doc, re.S | re.M)
    hits = [b for b in blocks if contains in b]
    if len(hits) == 0:
        raise ExtractError(
            f"{doc_name}: 找不到包含锚点 {contains!r} 的 yaml 块"
            f"（该文档共 {len(blocks)} 个 yaml 块）"
        )
    if len(hits) > 1:
        raise ExtractError(
            f"{doc_name}: 锚点 {contains!r} 命中 {len(hits)} 个 yaml 块，"
            f"锚点不唯一——抽取器不猜，请改用更具体的锚点"
        )
    return hits[0].rstrip("\n")


def yaml_block(doc: str, contains: str, doc_name: str) -> str:
    return code_block(doc, "yaml", contains, doc_name)


def md_tables(doc: str, after: str, count: int, doc_name: str) -> list[list[list[str]]]:
    """
    取 `after` 之后的前 count 张 markdown 表格。
    返回 [表格][行][单元格]，已剥离表头与分隔行。
    """
    idx = doc.find(after)
    if idx < 0:
        raise ExtractError(f"{doc_name}: 找不到区段锚点 {after!r}")
    body = doc[idx:]

    def close(rows):
        """一段连续的 | 行 → 表体（剥掉表头与分隔行）。无分隔行则不是表。"""
        for i, r in enumerate(rows):
            if all(c and set(c) <= set("-: ") for c in r):
                return rows[i + 1:]
        return None

    tables, rows = [], []
    for line in body.split("\n") + [""]:          # 末尾哨兵，收尾最后一张表
        s = line.strip()
        if len(s) > 1 and s.startswith("|") and s.endswith("|"):
            rows.append([c.strip() for c in s[1:-1].split("|")])
            continue
        if rows:
            t = close(rows)
            if t:
                tables.append(t)
                if len(tables) == count:
                    return tables
            rows = []
    if len(tables) < count:
        raise ExtractError(
            f"{doc_name}: 锚点 {after!r} 之后只找到 {len(tables)} 张表，期望 {count} 张"
        )
    return tables[:count]


def clean(cell: str) -> str:
    """剥掉 markdown 强调标记，保留反引号内的代码原文。"""
    s = re.sub(r"\*\*(.+?)\*\*", r"\1", cell)
    s = re.sub(r"~~(.+?)~~", r"\1", s)
    s = s.replace("<br>", " ")
    return s.strip()


def yq(s: str) -> str:
    """YAML 双引号字符串。文档里全是中文与反引号，统一引起来最安全。"""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


# ── 各产物的抽取器 ──────────────────────────────────────────────────────

def extract_effects(vault: str) -> str:
    doc = read_doc(vault, "ssot")
    return yaml_block(doc, "# CipherHaptic effects SSOT", DOCS["ssot"])


def extract_degradation(vault: str) -> str:
    doc = read_doc(vault, "ssot")
    return yaml_block(doc, "# 降级矩阵 SSOT", DOCS["ssot"])


def extract_semantics(vault: str) -> str:
    doc = read_doc(vault, "ir")
    return yaml_block(doc, "\nsemantics:\n", DOCS["ir"])


def extract_transitions(vault: str) -> str:
    doc = read_doc(vault, "fsm")
    return yaml_block(doc, "\nstates: [", DOCS["fsm"])


# parity：数据在 markdown 表格里，不是 yaml 块。
# 四张表列数不同，逐类声明列名 → schema 字段的映射。
PARITY_TABLES = [
    ("A", "### A 类 · 可完全弥合",
     ["id", "title", "ios", "android", "resolution", "residual", "verify", "status"]),
    ("B", "### B 类 · 可近似弥合",
     ["id", "title", "ios", "android", "resolution", "residual", "verify", "metric", "status"]),
    ("C", "### C 类 · 不可弥合，可隔离",
     ["id", "title", "ios", "android", "resolution", "residual", "status"]),
    ("D", "### D 类 · 不可弥合，必须让业务方可见",
     ["id", "title", "ios", "android", "resolution", "status"]),
]

FIELD_ORDER = ["title", "class", "ios", "android",
               "resolution", "residual", "verify", "metric", "status"]


def extract_parity(vault: str) -> str:
    doc = read_doc(vault, "parity")
    entries: dict[str, dict[str, str]] = {}

    for cls, anchor, cols in PARITY_TABLES:
        table = md_tables(doc, anchor, 1, DOCS["parity"])[0]
        for row in table:
            if len(row) != len(cols):
                raise ExtractError(
                    f"{DOCS['parity']} {cls} 类表：行列数 {len(row)} ≠ 表头声明 {len(cols)}\n"
                    f"  行首：{row[0][:40]}"
                )
            e = {k: clean(v) for k, v in zip(cols, row)}
            pid = e.pop("id")
            if not re.fullmatch(r"P-\d{2}", pid):
                raise ExtractError(f"{DOCS['parity']}: 非法条目 ID {pid!r}")
            if pid in entries:
                raise ExtractError(f"{DOCS['parity']}: 条目 ID 重复 {pid}")
            e["class"] = cls
            entries[pid] = e

    # ── schema 校验（§三 + §五 治理规则）────────────────────────────
    for pid, e in entries.items():
        if e["class"] in ("A", "B", "C"):
            if not e.get("residual"):
                raise ExtractError(f"{pid}: 残差为空——§五 规则 1 不允许")
            if e["residual"] in ("未知", "unknown", "-", "—"):
                raise ExtractError(
                    f"{pid}: 残差写成 {e['residual']!r}——§三 明令禁止。"
                    f"写不出残差 = 尚未理解这条差异 = 不能进入实现"
                )
        st = e.get("status", "")
        if not (st.startswith("verified") or st.startswith("pending")):
            raise ExtractError(
                f"{pid}: status={st!r} 不是 verified/pending——§三 schema 必填字段"
            )

    out = [f"# 双端差异登记表 · {len(entries)} 条", "", "parity:"]
    for pid in sorted(entries, key=lambda x: int(x[2:])):
        e = entries[pid]
        out.append(f"  {pid}:")
        for f in FIELD_ORDER:
            v = e.get(f)
            out.append(f"    {f}: " + (yq(v) if v else "null"))
        out.append("")
    return "\n".join(out).rstrip("\n")


EXPECTED_METHODS = 17
EXPECTED_CAPABILITIES = 11


def extract_contracts(vault: str) -> str:
    """
    contracts.md —— 17 个方法的签名基准，contract-check 的对拍源。

    ⚠️ **源不是主文档 A.2 的表。** A.2 是【能力视图】（11 条目），一个单元格里可能
    塞 1–3 个方法签名（`setHapticsEnabled` / `isHapticsEnabled` 同格、DND 一格三方法），
    机械抽取只会得到 12 而不是 17——这正是文档反复栽过的「能力条目 ≠ 方法数」。

    权威源是**工程骨架 §3.1 的 facade 代码块**：它自己写明「`tools/contract-check`
    以下面这份方法签名清单对拍，不以条目数对拍」，且每个方法尾部有 `// N` 编号。

    两个视图必须一致（11 能力 / 17 方法），该一致性由 contract-check 单独校验，
    不在此处混做——抽取器只负责搬运。
    """
    skel = read_doc(vault, "skeleton")
    block = code_block(skel, "swift", "public actor CipherHaptic {",
                       DOCS["skeleton"])

    methods: list[tuple[int, str]] = []
    buf: list[str] = []
    for raw in block.split("\n"):
        line = raw.strip()
        if not line or line.startswith("//") or line.startswith("public actor") \
                or line in ("}", "{"):
            continue
        m = re.match(r"^(.*?)\s*//\s*(\d+)\s*$", line)
        if m:
            buf.append(m.group(1).strip())
            sig = re.sub(r"\s+", " ", " ".join(x for x in buf if x)).strip()
            methods.append((int(m.group(2)), sig))
            buf = []
        else:
            buf.append(line)                      # 跨行签名，继续攒

    nums = [n for n, _ in methods]
    if nums != list(range(1, len(nums) + 1)):
        raise ExtractError(
            f"工程骨架 §3.1 的方法编号不连续：{nums}——编号是 contract-check 的对拍键，"
            f"不能有洞或重复"
        )
    if len(methods) != EXPECTED_METHODS:
        raise ExtractError(
            f"工程骨架 §3.1 抽到 {len(methods)} 个方法，文档声明 {EXPECTED_METHODS} 个。"
            f"两者不符时【不猜】——请先修文档"
        )

    lines = [
        "# CipherHaptic 对外契约 · 方法签名基准",
        "",
        "> 【生成产物 · 禁止手改】由 `tools/extract.py` 从工程骨架 §3.1 的 facade 代码块抽取。",
        "> `contract-check` 以本文件的**方法签名清单**对拍双端原生接口，**不以条目数对拍**。",
        "",
        f"**方法总数：{len(methods)}**（对应 {EXPECTED_CAPABILITIES} 项能力条目——"
        f"能力是给业务方读的分组，方法才是对拍单位）",
        "",
        "| # | 签名（iOS 基准） |",
        "|---|---|",
    ]
    for n, sig in methods:
        lines.append(f"| {n} | `{sig}` |")
    lines.append("")
    return "\n".join(lines)


def build_bundle(vault: str, artifacts: dict[str, str]) -> str:
    """
    spec/bundle.json —— **各端真正内嵌的产物**。

    ⚠️ **这是对工程骨架 §二「Resources/{semantics.yaml, effects.yaml}」的有意偏离**，
    理由有二：

    1. **零第三方解析依赖。** 给一个触觉库塞 snakeyaml（Android）/ Yams（Swift）是
       实打实的成本：方法数、与宿主 App 的版本冲突、额外的攻击面。而 JSON 在双端
       都是标准库（`org.json` / `JSONDecoder`）。
    2. **消掉整类 YAML 1.1 地雷。** `transitions.yaml` 曾用 `on:` 作键——它是 YAML 1.1
       的布尔字面量，PyYAML / snakeyaml / Yams 都会把它解析成布尔 `True`，双端 loader
       会一起坏掉。JSON 没有这类隐式类型转换。

    YAML 仍是**人读、评审、CI 逐字节 diff** 的产物；JSON 是**机器吃**的产物。
    两者同源同批生成，CI 规则 10 一起校验，不构成双写。
    """
    import json

    bundle = {
        "_generated": "由 tools/extract.py 从 vault SSOT 生成，禁止手改",
        "semantics": yaml.safe_load(artifacts["semantics.yaml"])["semantics"],
        "effects": yaml.safe_load(artifacts["effects.yaml"])["effects"],
        "degradation": yaml.safe_load(artifacts["degradation.yaml"])["degradation"],
        "transitions": yaml.safe_load(artifacts["transitions.yaml"]),
        "parity": yaml.safe_load(artifacts["parity.yaml"])["parity"],
    }
    return json.dumps(bundle, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


ARTIFACTS = [
    ("semantics.yaml",   extract_semantics,   "语义层与中立IR §2.2"),
    ("effects.yaml",     extract_effects,     "波形数据SSOT §二"),
    ("degradation.yaml", extract_degradation, "波形数据SSOT §三"),
    ("transitions.yaml", extract_transitions, "句柄状态机 §十"),
    ("parity.yaml",      extract_parity,      "双端差异登记表 §四（markdown 表格）"),
    ("contracts.md",     extract_contracts,   "工程骨架 §3.1 facade 代码块"),
]


def build(vault: str) -> dict[str, str]:
    out = {}
    raw = {}
    for name, fn, src in ARTIFACTS:
        body = fn(vault)
        raw[name] = body
        if name.endswith(".yaml"):
            out[name] = GENERATED_HEADER.format(src=src) + body + "\n"
        else:
            out[name] = body
    out["bundle.json"] = build_bundle(vault, raw)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="vault SSOT → spec/ 单向抽取")
    ap.add_argument("--vault", default=os.environ.get("CIPHERHAPTIC_VAULT", DEFAULT_VAULT))
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "spec"))
    ap.add_argument("--check", action="store_true",
                    help="只比对，不写文件（CI 规则 10）")
    args = ap.parse_args()

    try:
        artifacts = build(args.vault)
    except ExtractError as e:
        print(f"[extract] 抽取失败：{e}", file=sys.stderr)
        return 2

    out_dir = os.path.abspath(args.out)
    drift = []
    for name, body in artifacts.items():
        path = os.path.join(out_dir, name)
        if args.check:
            if not os.path.isfile(path):
                drift.append(f"{name}: spec/ 中不存在")
                continue
            cur = io.open(path, encoding="utf-8", newline="").read()
            if cur != body:
                drift.append(f"{name}: 与重新抽取的结果不一致")
        else:
            os.makedirs(out_dir, exist_ok=True)
            io.open(path, "w", encoding="utf-8", newline="").write(body)
            print(f"[extract] 写入 {name}  ({len(body.splitlines())} 行)")

    if args.check:
        if drift:
            print("[extract] CI 规则 10 失败——spec/ 与 vault 文档漂移：", file=sys.stderr)
            for d in drift:
                print(f"  - {d}", file=sys.stderr)
            print("  修复方式：改 vault 文档后重跑 `python tools/extract.py`，"
                  "不要手改 spec/", file=sys.stderr)
            return 1
        print("[extract] CI 规则 10 通过：spec/ 与 vault 文档逐字节一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
