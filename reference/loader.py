# -*- coding: utf-8 -*-
"""
SpecLoader 参考实现 —— spec/*.yaml → 中立模型。

对应「语义层与中立 IR」文档 §六的拆分：`SpecLoader` 是**通用同构**的（解析、schema
校验、引用完整性、语义→效果解析、归一化），`IRTranslator` 才是各端原生的。双端各写
一遍解析与校验 = 双份解析 bug + 双份校验遗漏，与"波形数据双端手抄"是同一个失败模式。
"""

from __future__ import annotations

import io
import os
from typing import Optional

import yaml

from .model import ContinuousSpec, IREvent, ResolvedWaveform
from .degrade import SILENT, degrade

SPEC_DIR = os.path.join(os.path.dirname(__file__), "..", "spec")

DEFAULT_IDLE_TIMEOUT_MS = 1500      # idle_timeout_ms 待实测（性能 §5.4）时的兜底


class SpecError(Exception):
    pass


def _load(name: str, spec_dir: str) -> dict:
    path = os.path.join(spec_dir, name)
    if not os.path.isfile(path):
        raise SpecError(f"spec 产物缺失：{path}（先跑 tools/extract.py）")
    return yaml.safe_load(io.open(path, encoding="utf-8"))


class Spec:
    def __init__(self, spec_dir: str = SPEC_DIR):
        self.dir = os.path.abspath(spec_dir)
        self.semantics: dict = _load("semantics.yaml", self.dir)["semantics"]
        self.effects: dict = _load("effects.yaml", self.dir)["effects"]
        self.degradation: dict = _load("degradation.yaml", self.dir)["degradation"]
        self.transitions: dict = _load("transitions.yaml", self.dir)
        self.parity: dict = _load("parity.yaml", self.dir)["parity"]
        self.idle_timeout_defaulted: list[str] = []

    # ── 中立事件源 ───────────────────────────────────────────────
    # SSOT v1.3.0 header：运行时读 ios_core_haptics.events（它已是绝对时刻表示），
    # android.* 块是镜像、仅供 CI diff，运行时不读。
    def neutral_events(self, effect_id: str) -> list[IREvent]:
        eff = self.effects.get(effect_id)
        if eff is None:
            raise SpecError(f"effects.yaml 无此效果：{effect_id}")
        out: list[IREvent] = []
        for i, ev in enumerate(eff.get("ios_core_haptics", {}).get("events", [])):
            if "duration_ms" not in ev:
                raise SpecError(
                    f"{effect_id}.events[{i}] 缺 duration_ms。"
                    f"transient 也必填——它是无瞬时事件概念的平台上的物理时长（SSOT §1.1）"
                )
            out.append(IREvent(
                atMs=int(ev["time_ms"]),
                durationMs=int(ev["duration_ms"]),
                intensity=float(ev["intensity"]),
                sharpness=float(ev["sharpness"]),
                kind="pulse" if ev["type"] == "hapticTransient" else "sustain",
            ))
        return sorted(out, key=lambda e: e.atMs)

    def kind_of(self, effect_id: str) -> str:
        return self.effects[effect_id].get("kind", "oneshot")

    def continuous_of(self, effect_id: str) -> Optional[ContinuousSpec]:
        blk = self.effects[effect_id].get("continuous")
        if blk is None:
            return None
        idle = blk.get("idle_timeout_ms")
        if idle is None:
            self.idle_timeout_defaulted.append(effect_id)
            idle = DEFAULT_IDLE_TIMEOUT_MS
        return ContinuousSpec(
            initialIntensity=float(blk["initial_intensity"]),
            initialSharpness=float(blk["initial_sharpness"]),
            maxDurationMs=int(blk["max_duration_ms"]),
            segmentMs=int(blk["segment_ms"]),
            idleTimeoutMs=int(idle),
        )

    # ── 管线 ⓪→⑤ 的参考实现（不含 ①②③ 拦截与 ⑥ 抢占）──────────
    def resolve(self, semantic_id: str, hardware_class: str,
                global_scale: float = 1.0) -> Optional[ResolvedWaveform]:
        """
        语义 token + 硬件档 → IR。返回 None 表示降级为 silent（管线 drop，不建 handle）。

        求值顺序严格按主文档 B.3：④ scale → ⑤ degrade。**scale 在降级之前**——
        SSOT §3.1 通则 2，`amplitude_scale` 乘的是已缩放后的 intensity。
        """
        sem = self.semantics.get(semantic_id)
        if sem is None:
            raise SpecError(f"semantics.yaml 无此 token：{semantic_id}")
        effect_id = sem["effect"]

        events = self.neutral_events(effect_id)
        if global_scale != 1.0:                                  # ④ scale
            events = [e.scaled(amp=global_scale) for e in events]

        cell = self.degradation.get(effect_id, {}).get(hardware_class)
        if cell is None:
            raise SpecError(
                f"degradation.yaml 缺格：{effect_id} × {hardware_class}（CI 规则 8）"
            )
        out = degrade(events, cell, effect_id)                    # ⑤ degrade
        if out is SILENT:
            return None

        kind = self.kind_of(effect_id)
        cont = self.continuous_of(effect_id)
        loop_gap = int(self.effects[effect_id].get("loop_gap_ms", 0))
        total = 0 if kind == "continuous" else ResolvedWaveform.total_of(out, loop_gap)

        return ResolvedWaveform(
            semanticId=semantic_id,
            effectId=effect_id,
            category=sem["category"],
            kind=kind,
            totalDurationMs=total,
            loopGapMs=loop_gap,
            events=out,
            degradeTrace=[cell["action"]],
            protected=bool(sem.get("protected", False)),
            continuous=cont,
        )
