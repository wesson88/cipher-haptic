# -*- coding: utf-8 -*-
"""
降级变换 —— 对应 SSOT 文档 §3.1「降级 action 语义定义」。

这一步是 `ResolvedWaveform` 的**生产函数**：它不确定，IR 就不确定。而低端机跑的
**全部**是它的产物。v1.2.0 之前全库只有 action 的名字、零定义，等于让双端两个
实现者各自发明一套降级算法——正是 IR 要消灭的漂移类别，且发生在 IR **之前**，
「IR 之后禁止决策」的 CI 规则 9 拦不到它。

四条通则（SSOT §3.1）：
  ① 输入是中立 events，不是双端数组
  ② 降级在 globalScale 之后（管线 ④ scale → ⑤ degrade），故 amplitude_scale
     乘的是【已缩放后】的 intensity
  ③ 每个 cell 有且仅有一个 action，不叠加
  ④ 变换后重算 totalDurationMs，并重新满足 IR 的升序/不重叠约束
"""

from __future__ import annotations

from typing import Optional

from .model import IREvent, clamp01

# `silent` 用一个哨兵表示：它【不产出 IR】，管线直接 drop，不创建 handle
# （IR 文档 §3.3③）。返回空列表会被误当成"播一个空效果"，那是两回事。
SILENT = None


class DegradeError(Exception):
    """降级配置与数据不匹配。CI 规则 12 靠它在构建期红，而不是运行时静默播空。"""


def _pulses(events: list[IREvent]) -> list[IREvent]:
    return [e for e in events if e.kind == "pulse"]


def degrade(events: list[IREvent], action: dict,
            effect_id: str = "?") -> Optional[list[IREvent]]:
    """
    events → events'（或 SILENT）。纯函数，无副作用。

    `action` 是 degradation.yaml 里某一格的值，形如 {"action": "simplify",
    "amplitude_scale": 0.5}。
    """
    name = action.get("action")
    if not name:
        raise DegradeError(f"{effect_id}: 降级格缺 action 字段：{action!r}")

    # ── full：恒等 ────────────────────────────────────────────────
    if name == "full":
        return list(events)

    # ── silent：不产出 IR ─────────────────────────────────────────
    if name == "silent":
        return SILENT

    # ── forced_amplitude：丢弃全部原时序，压成单一满幅脉冲 ────────
    # ERM 无振幅控制，马达只有开/关。保留时序细节不会更好听,只会把设计好的
    # 节奏变成一串噪音。
    if name == "forced_amplitude":
        d = action.get("duration_ms")
        if not isinstance(d, int) or d <= 0:
            raise DegradeError(f"{effect_id}: forced_amplitude 需要正整数 duration_ms")
        return [IREvent(atMs=0, durationMs=d, intensity=1.0,
                        sharpness=0.0, kind="pulse")]

    # ── simplify：保留事件个数与顺序，缩强度 / 压时间轴 ────────────
    if name == "simplify":
        amp = float(action.get("amplitude_scale", 1.0))
        dur = float(action.get("duration_scale", 1.0))
        if amp <= 0 or dur <= 0:
            raise DegradeError(f"{effect_id}: simplify 的 scale 必须 > 0")
        return [e.scaled(amp=amp, dur=dur) for e in events]

    # ── tail_pulse_only：只留最后一个脉冲，归零 ───────────────────
    # 长效果只留信息量最大的收尾击（ticket_rip 的"绷断"）——中段阻尼在弱马达上
    # 本来就表达不出来。
    if name == "tail_pulse_only":
        ps = _pulses(events)
        if not ps:
            raise DegradeError(
                f"{effect_id}: tail_pulse_only 要求至少 1 个 pulse 事件，实际 0 个"
                f"（CI 规则 12）"
            )
        last = max(ps, key=lambda e: e.atMs)
        return [IREvent(0, last.durationMs, last.intensity, last.sharpness, "pulse")]

    # ── single_pulse：取最强的一击，归零 ──────────────────────────
    if name == "single_pulse":
        ps = _pulses(events)
        if not ps:
            raise DegradeError(
                f"{effect_id}: single_pulse 要求至少 1 个 pulse 事件，实际 0 个"
                f"（CI 规则 12）"
            )
        # intensity 最大；并列取 atMs 最大者（SSOT §3.1）
        best = max(ps, key=lambda e: (e.intensity, e.atMs))
        return [IREvent(0, best.durationMs, best.intensity, best.sharpness, "pulse")]

    # ── n_pulses：取前 N 个脉冲，按固定间隔重排 ───────────────────
    if name == "n_pulses":
        n = action.get("count")
        gap = action.get("interval_ms")
        if not isinstance(n, int) or n < 1:
            raise DegradeError(f"{effect_id}: n_pulses 需要正整数 count")
        if not isinstance(gap, int) or gap < 0:
            raise DegradeError(f"{effect_id}: n_pulses 需要非负整数 interval_ms")
        ps = _pulses(events)
        if len(ps) < n:
            raise DegradeError(
                f"{effect_id}: n_pulses count={n} 但只有 {len(ps)} 个 pulse 事件"
                f"（CI 规则 12）"
            )
        # durationMs / intensity / sharpness 各自保持原值,只重排 atMs
        return [IREvent(i * gap, p.durationMs, p.intensity, p.sharpness, "pulse")
                for i, p in enumerate(ps[:n])]

    # ── amplitude_only：保留时序，丢弃 sharpness 维度 ─────────────
    if name == "amplitude_only":
        return [IREvent(e.atMs, e.durationMs, e.intensity, 0.0, e.kind)
                for e in events]

    raise DegradeError(
        f"{effect_id}: 未知降级 action {name!r}。"
        f"合法值见 SSOT §3.1——抽取器与本实现都不猜"
    )


#: SSOT §3.1 定义的全部 action，用于 CI 规则 12 的覆盖性检查
KNOWN_ACTIONS = {
    "full", "silent", "forced_amplitude", "simplify",
    "tail_pulse_only", "single_pulse", "n_pulses", "amplitude_only",
}
