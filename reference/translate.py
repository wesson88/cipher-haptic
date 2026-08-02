# -*- coding: utf-8 -*-
"""
双端生成器 —— 对应「语义层与中立 IR」文档 §四。

> 「这段 12 行代码就是"双端时序永不错位"的全部保证。它是纯函数，双端等价测试的
>   头号用例。」

本模块是那 12 行的参考实现 + 反向累加校验器（CI 规则 6 用它把生成结果与 SSOT 里
手写的 Android 镜像逐值 diff——不一致即说明手写数组有错，这正是要抓的）。
"""

from __future__ import annotations

from .model import IREvent


def to_ios_events(events: list[IREvent]) -> list[dict]:
    """IR → CHHapticEvent 的构造参数。iOS 对 transient 忽略 duration（P-12）。"""
    out = []
    for e in events:
        ev = {
            "eventType": "hapticTransient" if e.kind == "pulse" else "hapticContinuous",
            "relativeTime": round(e.atMs / 1000.0, 6),
            "intensity": e.intensity,
            "sharpness": e.sharpness,
        }
        if e.kind == "sustain":
            ev["duration"] = round(e.durationMs / 1000.0, 6)
        out.append(ev)
    return out


def to_android_waveform(events: list[IREvent], looping: bool = False) -> dict:
    """
    IR → createWaveform(timings, amplitudes, repeat)。

    ⚠️ `timings[i]` 是**该段的持续时长**，不是绝对时间戳。v1.1.0 的四套波形全部
    错位就是把这两者搞混了——脉冲起点须逐段累加求得。本函数从绝对时刻生成，
    结构上不可能错。
    """
    timings: list[int] = []
    amps: list[int] = []
    cursor = 0
    for e in sorted(events, key=lambda x: x.atMs):
        if e.atMs > cursor:                       # 静默间隔
            timings.append(e.atMs - cursor)
            amps.append(0)
        elif e.atMs < cursor:
            raise ValueError(
                f"事件在 t={e.atMs} 与前一段（结束于 {cursor}）重叠——"
                f"IR 不允许，应在 validate() 阶段就被拦下"
            )
        timings.append(e.durationMs)
        amps.append(round(e.intensity * 255))     # P-13：量化误差 < 1/255
        cursor = e.atMs + e.durationMs
    return {"timings_ms": timings, "amplitudes": amps,
            "repeat": 0 if looping else -1}


def to_android_composition(events: list[IREvent]) -> dict:
    """
    IR → Composition.addPrimitive。仅当全 pulse 且 API≥30 且原语受支持（P-08/P-15）。
    `delay` 是相对上一原语的前置延迟，同样由绝对时刻算出。
    """
    if any(e.kind != "pulse" for e in events):
        raise ValueError("Composition 表达不了 sustain 段——应退 waveform 路径")
    prims, prev = [], 0
    for e in sorted(events, key=lambda x: x.atMs):
        prims.append({"type": "PRIMITIVE_CLICK",
                      "scale": e.intensity,
                      "delay_ms": e.atMs - prev})
        prev = e.atMs
    return {"primitives": prims}


# ── 反向校验：Android 数组 → 脉冲起点 ────────────────────────────────

def waveform_pulse_starts(timings: list[int], amps: list[int]) -> list[int]:
    """把分段时长数组累加回绝对起点，用于与 IR 的 atMs 对拍（IR 文档 §4.3）。"""
    starts, cursor = [], 0
    for t, a in zip(timings, amps):
        if a > 0:
            starts.append(cursor)
        cursor += t
    return starts


def composition_pulse_starts(prims: list[dict]) -> list[int]:
    starts, cursor = [], 0
    for p in prims:
        cursor += p.get("delay_ms", 0)
        starts.append(cursor)
    return starts
