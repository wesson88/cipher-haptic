# -*- coding: utf-8 -*-
"""
中立模型与 IR —— 对应「语义层与中立 IR」文档 §3.2 / §3.2b。

这份 Python 实现是**参考实现（reference implementation）**，不是产品代码。它的三个职责：

1. **证明规格可实现。** `degrade()` 的 8 个 action 是 2026-08-02 才写进 SSOT §3.1 的，
   在此之前全库零定义。没跑过一遍就不知道它们是否自洽。
2. **产出黄金测试用例。** 双端原生实现将以本实现的输出为基准对拍——这是「双端行为
   等价测试」的基准来源。
3. **让 CI 规则 12/13/14 可执行。** 规则 13 要求对 7×3=21 格全矩阵求值并断言产出合法 IR，
   没有实现就只是一句口号。

⚠️ **它不参与运行时**，双端各自有原生实现。本实现与原生实现的差异由等价测试兜住。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal, Optional

Kind = Literal["oneshot", "looping", "continuous"]
EventKind = Literal["pulse", "sustain"]
Category = Literal["ux", "alert", "critical"]
HardwareClass = Literal["ERM_Z", "LINEAR_X_LIMITED", "LINEAR_X_FULL"]


def clamp01(x: float) -> float:
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


@dataclass(frozen=True)
class IREvent:
    """
    IR 事件。`atMs` 是**相对效果起点的绝对时刻**，不是间隔——见 IR 文档 §3.4：
    间隔表示法要求人脑做累加才能验证对齐，而 v1.1.0 的 3/4 错误率证明人脑会算错。
    绝对时刻让"双端时序错位"这一整类缺陷从"要靠 CI 拦"降级为"结构上不可能发生"。
    """
    atMs: int
    durationMs: int
    intensity: float
    sharpness: float
    kind: EventKind

    def scaled(self, *, amp: float = 1.0, dur: float = 1.0,
               sharpness: Optional[float] = None) -> "IREvent":
        return IREvent(
            atMs=round(self.atMs * dur),
            durationMs=max(1, round(self.durationMs * dur)),
            intensity=clamp01(self.intensity * amp),
            sharpness=self.sharpness if sharpness is None else sharpness,
            kind=self.kind,
        )


@dataclass(frozen=True)
class ContinuousSpec:
    """仅 kind=continuous 有。来源是 effects.yaml 的 continuous 块（SSOT v1.3.0）。"""
    initialIntensity: float
    initialSharpness: float
    maxDurationMs: int
    segmentMs: int
    idleTimeoutMs: Optional[int]        # None = 待实测（性能 §5.4），loader 用默认值并告警


@dataclass
class ResolvedWaveform:
    """决策管线的唯一输出；engine 的唯一输入。不含任何平台词汇。"""
    semanticId: str
    effectId: str
    category: Category
    kind: Kind
    totalDurationMs: int
    loopGapMs: int
    events: list[IREvent]
    degradeTrace: list[str] = field(default_factory=list)
    protected: bool = False
    continuous: Optional[ContinuousSpec] = None

    # ── 不变式（IR 文档 §3.2 + §3.3）─────────────────────────────────
    def validate(self) -> list[str]:
        """返回违规清单，空列表 = 合法。CI 规则 13 对 21 格逐格调用本方法。"""
        errs: list[str] = []

        for i, e in enumerate(self.events):
            if not (0.0 <= e.intensity <= 1.0):
                errs.append(f"events[{i}].intensity={e.intensity} 越界")
            if not (0.0 <= e.sharpness <= 1.0):
                errs.append(f"events[{i}].sharpness={e.sharpness} 越界")
            if e.durationMs <= 0:
                errs.append(f"events[{i}].durationMs={e.durationMs} 必须 > 0")
            if e.atMs < 0:
                errs.append(f"events[{i}].atMs={e.atMs} 必须 ≥ 0")

        # 按 atMs 升序（CI 规则 4）
        ats = [e.atMs for e in self.events]
        if ats != sorted(ats):
            errs.append(f"events 未按 atMs 升序：{ats}")

        # sustain 区间不得重叠（CI 规则 4）
        sus = sorted([e for e in self.events if e.kind == "sustain"],
                     key=lambda e: e.atMs)
        for a, b in zip(sus, sus[1:]):
            if a.atMs + a.durationMs > b.atMs:
                errs.append(
                    f"sustain 区间重叠：[{a.atMs},{a.atMs + a.durationMs}) "
                    f"与 [{b.atMs},…)"
                )

        # totalDurationMs 与 events 自洽（IR 文档 §3.3②）
        if self.kind == "continuous":
            if self.totalDurationMs != 0:
                errs.append(
                    f"kind=continuous 的 totalDurationMs 必须为 0（实际 {self.totalDurationMs}）"
                    f"——否则实现者会拿它当总时长用,拖拽到上限时触觉自己断掉"
                )
            if self.continuous is None:
                errs.append("kind=continuous 缺 continuous 块（CI 规则 14）")
        else:
            if self.continuous is not None:
                errs.append("非 continuous 的效果不得有 continuous 块（CI 规则 14）")
            want = (max((e.atMs + e.durationMs for e in self.events), default=0)
                    + self.loopGapMs)
            if self.totalDurationMs != want:
                errs.append(
                    f"totalDurationMs={self.totalDurationMs} ≠ "
                    f"max(atMs+durationMs)+loopGapMs={want}"
                    f"——它是 NATURAL_END 定时器的唯一来源,不能与 events 脱节"
                )
        return errs

    @staticmethod
    def total_of(events: list[IREvent], loop_gap: int = 0) -> int:
        return max((e.atMs + e.durationMs for e in events), default=0) + loop_gap
