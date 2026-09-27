# -*- coding: utf-8 -*-
"""
决策管线参考实现 —— 对应主文档 B.3 / 工程骨架 §3.3（v1.4.0 落地形态）。

`decide()` 是**纯函数**：给定语义 token、调用选项与上下文快照，输出唯一确定的 Decision。
Kotlin `DecisionPipeline.decide` 与本函数同构，`tools/golden.py` 用它产出 `decisions`
用例，双端逐字段对拍。

为什么 Decision 也要进 golden：drop 原因与表达形式这一层此前无法对拍，而它恰是 iOS
落地时最易漂移的一段——2026-09-27 代码审查的 B1（带 sustain 的效果在 API30+ 被一刀切
送进 Composition 而 FAIL）就发生在这一层。

抢占（⑥）由 `PreemptionPolicy` 单独覆盖，本函数对应的 golden 用例不带活跃句柄。
"""

from __future__ import annotations

from typing import Optional

from .loader import Spec

# 循环效果的库兜底上限：应用告知的时长超过它按它截断（主文档 A.2 接口 3，v1.4.0）
MAX_LOOP_DURATION_MS = 300_000


def expression_form(rw, composition_supported: bool) -> str:
    """
    表达形式——管线第 ⑤ 步的 API 能力门，**逐效果**判定（IR 文档 §4.2 路径 A/B）。
    只有全 pulse 的效果才能走 Composition；continuous 走分段 waveform。
    """
    if (composition_supported and rw.kind != "continuous" and rw.events
            and all(e.kind == "pulse" for e in rw.events)):
        return "composition"
    return "waveform"


def decide(spec: Spec, semantic_id: str, ctx: dict,
           loop_max_duration_ms: Optional[int] = None) -> dict:
    """
    ctx = {masterEnabled, systemHapticsEnabled, mute: none|dnd|hardware,
           globalScale, hardwareClass, compositionSupported}
    loop_max_duration_ms：经 playLoopingEffect 调用时由应用告知；None = 非循环 API。
    返回 {"drop": reason} 或 {"play": {effectId, form, loopDeadlineMs}}。
    """
    sem = spec.semantics[semantic_id]                                   # ⓪
    category = sem["category"]
    if not ctx["masterEnabled"]:                                        # ①
        return {"drop": "disabled"}
    if not ctx["systemHapticsEnabled"]:                                 # ②
        return {"drop": "system-off"}
    if ctx["mute"] != "none" and category != "critical":                # ③ critical 绕过
        return {"drop": "dnd" if ctx["mute"] == "dnd" else "hardware-mute"}
    rw = spec.resolve(semantic_id, ctx["hardwareClass"], ctx["globalScale"])   # ④⑤
    if rw is None:
        return {"drop": "degraded-to-silent"}

    deadline = None
    if rw.kind == "looping":
        # 循环多久由应用告知：没经过 playLoopingEffect 就拿不到停止手段，拒播（v1.3.4 事故防线）
        if loop_max_duration_ms is None:
            return {"drop": "looping-needs-token"}
        if loop_max_duration_ms <= 0:
            return {"drop": "looping-needs-duration"}
        deadline = min(loop_max_duration_ms, MAX_LOOP_DURATION_MS)

    return {"play": {
        "effectId": rw.effectId,
        "form": expression_form(rw, ctx["compositionSupported"]),
        "loopDeadlineMs": deadline,
    }}
