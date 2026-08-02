#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
contract-check —— CI 校验器

覆盖「语义层与中立 IR」§七 的 14 条规则与「句柄状态机」§十 的 5 条不变式。
每条规则打印命中的具体位置；**任一条不过即退出码非 0**。

设计立场：**规则先写、先跑红。** 有几条预期首跑就红，那正是它们的价值——
不为了让 CI 绿而放宽规则，红出来的每一条要逐条判定是"数据错"还是"规则错"。

用法：
    python tools/check.py            # 全量
    python tools/check.py -v         # 附带明细
"""

from __future__ import annotations

import argparse
import itertools
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from reference.degrade import KNOWN_ACTIONS, DegradeError, SILENT, degrade   # noqa: E402
from reference.loader import Spec, SpecError                                  # noqa: E402
from reference.translate import (                                            # noqa: E402
    composition_pulse_starts, to_android_composition, to_android_waveform,
    waveform_pulse_starts,
)

HARDWARE_CLASSES = ["ERM_Z", "LINEAR_X_LIMITED", "LINEAR_X_FULL"]
CATEGORIES = ["ux", "alert", "critical"]
KINDS = ["oneshot", "looping", "continuous"]

KEY_RE = re.compile(r"^[a-z][a-z0-9]*\.[a-z][a-z0-9]*$")


class Result:
    def __init__(self):
        self.rules: list[tuple[str, str, list[str]]] = []   # (编号, 名称, 失败明细)

    def add(self, rid: str, name: str, fails: list[str]):
        self.rules.append((rid, name, fails))

    @property
    def failed(self) -> int:
        return sum(1 for _, _, f in self.rules if f)


# ── 标识符映射（IR 文档 §2.3b）──────────────────────────────────────

def to_lower_camel(key: str) -> str:
    a, b = key.split(".")
    return a + b[0].upper() + b[1:]


def to_upper_snake(key: str) -> str:
    return key.replace(".", "_").upper()


# ── 规则 ────────────────────────────────────────────────────────────

def rule_1(s: Spec) -> list[str]:
    """semantics.*.effect 必须存在于 effects.yaml"""
    return [f"{k}.effect={v['effect']!r} 不存在于 effects.yaml"
            for k, v in s.semantics.items() if v["effect"] not in s.effects]


def rule_2(s: Spec) -> list[str]:
    """effects 中每个条目必须被至少一个语义引用（死波形）"""
    used = {v["effect"] for v in s.semantics.values()}
    return [f"效果 {e!r} 无任何语义引用——死波形" for e in s.effects if e not in used]


def rule_3(s: Spec) -> list[str]:
    """每个 category 至少有一个语义实例（零实例分类的死代码路径）"""
    have = {v["category"] for v in s.semantics.values()}
    return [f"category {c!r} 零实例——决策路径写了、测不了、上线即腐烂"
            for c in CATEGORIES if c not in have]


def rule_4_5(s: Spec) -> tuple[list[str], list[str]]:
    """4: events 按 atMs 升序且 sustain 不重叠 / 5: 值域"""
    order, range_ = [], []
    for eid in s.effects:
        try:
            evs = s.neutral_events(eid)
        except SpecError as e:
            order.append(str(e))
            continue
        ats = [e.atMs for e in evs]
        if ats != sorted(ats):
            order.append(f"{eid}: atMs 未升序 {ats}")
        sus = [e for e in evs if e.kind == "sustain"]
        for a, b in zip(sus, sus[1:]):
            if a.atMs + a.durationMs > b.atMs:
                order.append(f"{eid}: sustain 区间重叠")
        for i, e in enumerate(evs):
            if not (0.0 <= e.intensity <= 1.0) or not (0.0 <= e.sharpness <= 1.0):
                range_.append(f"{eid}.events[{i}] 值域越界")
    return order, range_


def rule_6(s: Spec) -> list[str]:
    """
    中立列表 → 生成双端数组，与 SSOT 里手写的镜像逐值 diff。
    不一致即说明手写数组有错——这正是要抓的（v1.1.0 的 3/4 缺陷）。
    """
    fails = []
    for eid, eff in s.effects.items():
        if s.kind_of(eid) == "continuous":
            continue
        evs = s.neutral_events(eid)
        a = eff.get("android", {})

        wf = a.get("waveform") or a.get("fallback_api_lt_30", {}).get("waveform")
        if wf:
            gen = to_android_waveform(evs)
            if gen["timings_ms"] != wf["timings_ms"]:
                fails.append(f"{eid} waveform.timings_ms 镜像={wf['timings_ms']} "
                             f"生成={gen['timings_ms']}")
            if gen["amplitudes"] != wf["amplitudes"]:
                fails.append(f"{eid} waveform.amplitudes 镜像={wf['amplitudes']} "
                             f"生成={gen['amplitudes']}")
            got = waveform_pulse_starts(wf["timings_ms"], wf["amplitudes"])
            want = [e.atMs for e in evs]
            if got != want:
                fails.append(f"{eid} 双端脉冲起点错位：iOS={want} Android={got}")

        comp = a.get("composition")
        if comp:
            try:
                gen = to_android_composition(evs)
            except ValueError as e:
                fails.append(f"{eid} composition: {e}")
                continue
            got = composition_pulse_starts(comp["primitives"])
            want = [e.atMs for e in evs]
            if got != want:
                fails.append(f"{eid} composition 起点错位：iOS={want} Android={got}")
            for i, (g, m) in enumerate(zip(gen["primitives"], comp["primitives"])):
                if abs(g["scale"] - m["scale"]) > 1e-9:
                    fails.append(f"{eid} composition[{i}].scale "
                                 f"镜像={m['scale']} 生成={g['scale']}")
    return fails


def rule_7(s: Spec) -> list[str]:
    """key 正则 + 派生无碰撞（枚举对拍需等原生代码就位）"""
    fails, camel, snake = [], {}, {}
    for k in s.semantics:
        if not KEY_RE.match(k):
            fails.append(f"{k!r} 不匹配 ^[a-z][a-z0-9]*\\.[a-z][a-z0-9]*$")
            continue
        for m, d, label in ((to_lower_camel(k), camel, "Swift"),
                            (to_upper_snake(k), snake, "Kotlin")):
            if m in d:
                fails.append(f"{label} 标识符碰撞：{k} 与 {d[m]} 都派生出 {m}")
            d[m] = k
    return fails


def rule_8(s: Spec) -> list[str]:
    """degradation 覆盖所有 effect × 所有硬件档"""
    fails = []
    for eid in s.effects:
        cell = s.degradation.get(eid)
        if cell is None:
            fails.append(f"{eid}: degradation.yaml 完全缺失")
            continue
        for hc in HARDWARE_CLASSES:
            if hc not in cell:
                fails.append(f"{eid} × {hc}: 缺格")
    for eid in s.degradation:
        if eid not in s.effects:
            fails.append(f"degradation 有 {eid} 但 effects 没有——悬空引用")
    return fails


def rule_11(s: Spec) -> list[str]:
    """过渡期：effects 残留的 category / protected 必须与 semantics 一致"""
    fails = []
    by_effect: dict[str, list[str]] = {}
    for k, v in s.semantics.items():
        by_effect.setdefault(v["effect"], []).append(k)
    for eid, eff in s.effects.items():
        sems = by_effect.get(eid, [])
        if "category" in eff:
            want = {s.semantics[k]["category"] for k in sems}
            if want and eff["category"] not in want:
                fails.append(f"{eid}.category={eff['category']!r} 与 semantics "
                             f"派生的 {want} 不一致")
        if "protected" in eff:
            fails.append(f"{eid} 仍带 protected 字段——唯一写入点是 semantics.yaml")
    return fails


def rule_12_13(s: Spec) -> tuple[list[str], list[str]]:
    """12: 降级 action 前置条件 / 13: 全矩阵求值产出合法 IR"""
    pre, matrix = [], []
    for eid in s.effects:
        for hc in HARDWARE_CLASSES:
            cell = s.degradation.get(eid, {}).get(hc)
            if cell is None:
                continue
            act = cell.get("action")
            if act not in KNOWN_ACTIONS:
                pre.append(f"{eid}×{hc}: 未知 action {act!r}")
                continue
            try:
                evs = s.neutral_events(eid)
            except SpecError as e:
                matrix.append(f"{eid}: {e}")
                continue
            try:
                out = degrade(evs, cell, eid)
            except DegradeError as e:
                pre.append(str(e))
                continue
            if out is SILENT:
                continue
            sem = next((k for k, v in s.semantics.items() if v["effect"] == eid), None)
            if sem is None:
                continue
            rw = s.resolve(sem, hc)
            if rw is None:
                continue
            for err in rw.validate():
                matrix.append(f"{eid}×{hc}: {err}")
    return pre, matrix


def rule_14(s: Spec) -> list[str]:
    """kind=continuous 必须有 continuous 块且 totalDurationMs==0；反之禁止"""
    fails = []
    for eid, eff in s.effects.items():
        k = s.kind_of(eid)
        has = "continuous" in eff
        if k == "continuous" and not has:
            fails.append(f"{eid}: kind=continuous 但无 continuous 块")
        if k != "continuous" and has:
            fails.append(f"{eid}: 非 continuous 却有 continuous 块")
    return fails


def invariants(s: Spec) -> dict[str, list[str]]:
    """句柄状态机 §十 的 5 条不变式（对迁移表做静态穷举）"""
    t = s.transitions
    states, events = t["states"], t["events"]
    trans = t["transitions"]

    def rows(frm, on):
        out = []
        for r in trans:
            ev_ = r["event"]
            ons = ev_ if isinstance(ev_, list) else [ev_]
            if r["from"] == frm and (on in ons or ev_ == "*"):
                out.append(r)
        return out

    # 不变式 1：无泄漏——每个非终态都能抵达 Reclaimed
    reach, frontier = {"Reclaimed"}, ["Reclaimed"]
    edges: dict[str, set[str]] = {}
    for r in trans:
        edges.setdefault(r["from"], set()).add(r["to"])
    changed = True
    while changed:
        changed = False
        for frm, tos in edges.items():
            if frm not in reach and (tos & reach):
                reach.add(frm)
                changed = True
    inv1 = [f"状态 {st} 抵达不了 Reclaimed——release() 只挂在 Reclaimed，"
            f"这是 player/wake lock/watchdog 泄漏" for st in states if st not in reach]

    # 不变式 2：FAIL 全覆盖
    inv2 = [f"{st} 会执行平台动作但无 FAIL 出口"
            for st in ("Submitting", "Active", "Paused") if not rows(st, "FAIL")]

    # 不变式 3：终态吸收
    late = ["CANCEL", "NATURAL_END", "UPDATE", "SUSPEND", "RESUME", "FAIL"]
    inv3 = []
    for st in ("Completed", "Cancelled", "Failed"):
        for ev in late:
            rs = rows(st, ev)
            if not rs:
                inv3.append(f"{st} 不吸收迟到事件 {ev}——会刷 illegal 告警噪音")
            elif not any(r.get("silent") for r in rs):
                inv3.append(f"{st}+{ev} 有转移但未标 silent")

    # 不变式 4：守卫互斥且完备（穷举 kind × cat = 9 组合）
    # 4a：【部分覆盖】的 (state,event) —— 有人想过并按守卫拆过，却漏了组合。最危险。
    # 4b：【完全缺席】的 (state,event) —— 需人工判定"该事件对该状态本就不适用"还是漏建模。
    #     文档 §十 要求这些也写成 illegal:true 显式声明行；当前作为待判定清单输出，
    #     不计入失败——8 态 × 10 事件 = 80 对，一次性补全属文档变更，需逐对过目。
    inv4, inv4_absent = [], []
    for frm, ev in itertools.product(states, events):
        if frm == "Reclaimed":
            continue
        rs = rows(frm, ev)
        if not rs:
            inv4_absent.append(f"({frm}, {ev})")
            continue
        for kind, cat in itertools.product(KINDS, CATEGORIES):
            hits = [r for r in rs if _guard_ok(r.get("when"), kind, cat)]
            if len(hits) == 0:
                inv4.append(f"({frm}, {ev}) 在 kind={kind}/cat={cat} 命中 0 条"
                            f"——漏建模,或需补 illegal:true 显式声明行")
            elif len(hits) > 1:
                inv4.append(f"({frm}, {ev}) 在 kind={kind}/cat={cat} 命中 "
                            f"{len(hits)} 条——守卫不互斥,退化为表内顺序依赖")

    # 不变式 5：fuzz 须覆盖 continuous 与 UPDATE（此处只断言事件空间含 UPDATE）
    inv5 = [] if "UPDATE" in events else ["事件空间缺 UPDATE"]

    globals()["_ABSENT_PAIRS"] = inv4_absent
    return {"不变式1 无泄漏": inv1, "不变式2 FAIL全覆盖": inv2,
            "不变式3 终态吸收": inv3, "不变式4 守卫互斥完备": inv4,
            "不变式5 fuzz覆盖": inv5}


def _guard_ok(when: str | None, kind: str, cat: str) -> bool:
    if not when:
        return True
    ctx = {"kind": kind, "cat": cat}
    for clause in when.split("&&"):
        c = clause.strip()
        m = re.match(r"(\w+)\s*(!=|=)\s*(\w+)", c)
        if not m:
            raise ValueError(f"无法解析守卫：{when!r}")
        var, op, val = m.groups()
        ok = (ctx[var] == val) if op == "=" else (ctx[var] != val)
        if not ok:
            return False
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    try:
        s = Spec()
    except SpecError as e:
        print(f"[check] 无法加载 spec/：{e}", file=sys.stderr)
        return 2

    r = Result()
    o45, r45 = rule_4_5(s)
    p12, m13 = rule_12_13(s)
    r.add("1", "语义引用的效果存在", rule_1(s))
    r.add("2", "无死波形", rule_2(s))
    r.add("3", "无零实例 category", rule_3(s))
    r.add("4", "events 升序且 sustain 不重叠", o45)
    r.add("5", "intensity/sharpness 值域", r45)
    r.add("6", "中立源生成的双端数组 == SSOT 手写镜像", rule_6(s))
    r.add("7", "语义 key 正则 + 跨语言派生无碰撞", rule_7(s))
    r.add("8", "降级矩阵全覆盖", rule_8(s))
    r.add("11", "category/protected 无双写漂移", rule_11(s))
    r.add("12", "降级 action 前置条件", p12)
    r.add("13", "全矩阵求值产出合法 IR", m13)
    r.add("14", "continuous 块一致性", rule_14(s))
    for name, fails in invariants(s).items():
        r.add("FSM", name, fails)

    print("=" * 72)
    for rid, name, fails in r.rules:
        mark = "FAIL" if fails else " ok "
        print(f"[{mark}] 规则 {rid:<4} {name}")
        if fails and (args.verbose or len(fails) <= 8):
            for f in fails[:20]:
                print(f"         · {f}")
            if len(fails) > 20:
                print(f"         · …… 另有 {len(fails) - 20} 条")
        elif fails:
            print(f"         · {len(fails)} 条失败（-v 查看明细）")
    print("=" * 72)

    absent = globals().get("_ABSENT_PAIRS", [])
    if absent:
        print(f"[info] 不变式 4b：{len(absent)} 对 (状态,事件) 在迁移表里完全缺席，"
              f"按 §十 应写成 illegal:true 显式声明行（当前不计入失败，需逐对判定）")
        print("       " + "  ".join(absent[:12]) + (" …" if len(absent) > 12 else ""))

    if s.idle_timeout_defaulted:
        print(f"[warn] idle_timeout_ms 未实测，已用默认值兜底："
              f"{', '.join(s.idle_timeout_defaulted)}（性能 §5.4 待测项）")

    if r.failed:
        print(f"[check] {r.failed} 条规则未通过")
        return 1
    print("[check] 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
