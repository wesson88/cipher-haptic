#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真机测试驱动 —— P0 验证计划 V4 / V5 与 P-21 的多机采集工具。

## 为什么要有这个脚本

P-21（OEM 替换短脉冲波形）是手工比对 `dumpsys` 发现的，而它现在的状态是
**pending（需多 OEM 采样）**。手工在每台机器上重复"播放 → dump → 逐段对拍"
既慢又容易错 —— 而这恰恰是机器最该干的事：

1. **期望值**由参考实现算出（与 Android/iOS 实现同源于 spec，不是手抄）；
2. **实际值**从 `dumpsys vibrator_manager` 解析；
3. **对拍**逐脉冲比起点，偏移量直接出表。

## 用法

    python tools/devicetest.py                    # 全套
    python tools/devicetest.py --only v4,waveform # 只跑指定项
    python tools/devicetest.py --serial <SN>      # 指定设备
    python tools/devicetest.py --monkey 20000     # 附带 monkey（只验 ANR）

产出 `reports/<机型>-<SDK>-<日期>.md`，多台机器的报告可直接并排比较。

## 它测得了什么、测不了什么

| 能 | 不能 |
|---|---|
| V4 硬件档判定与原始依据 | **手感**（要人） |
| V5 A 段软件延迟分位 | **V5 B 段**：马达 rise time、对齐误差（要加速度计 / ≥960fps 摄像） |
| P-21 波形保真度（提交 vs 实播） | 用户主观"是否脱节" |
| 压测不变式（泄漏 / 静默失败） | 软失败中"振错了"的部分 |
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from reference.loader import Spec                                   # noqa: E402
from reference.translate import to_android_waveform                 # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "com.cipherlex.haptic.demo"
ACT = f"{PKG}/.TuningActivity"
TAG = "CipherHapticTuner"
# FLAG_ACTIVITY_SINGLE_TOP —— 不加则 am start 不触发 onNewIntent，命令会被静默吞掉
SINGLE_TOP = "0x20000000"
# 与 V3Service.BURST_GAP_MS 一致 —— 串内设计间隔，也就是 V3 的观测基准
BURST_GAP_MS = 800


class Adb:
    def __init__(self, serial: str | None = None):
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args: str, timeout: int = 60) -> str:
        p = subprocess.run(self.base + list(args), capture_output=True,
                           timeout=timeout)
        return p.stdout.decode("utf-8", "replace").replace("\r", "")

    def shell(self, cmd: str, timeout: int = 60) -> str:
        return self.run("shell", cmd, timeout=timeout)

    def devices(self) -> list[str]:
        out = self.run("devices")
        return [l.split()[0] for l in out.splitlines()[1:]
                if l.strip() and l.split()[-1] == "device"]

    def prop(self, key: str) -> str:
        return self.shell(f"getprop {key}").strip()

    def start(self, *extras: str):
        self.shell(f"am start -f {SINGLE_TOP} -n {ACT} " + " ".join(extras))

    def logcat_lines(self) -> list[str]:
        return self.run("logcat", "-d", "-s", f"{TAG}:I").splitlines()


# ── dumpsys 解析 ────────────────────────────────────────────────────

# 通用 AOSP 形态：Step=200ms(amplitude=0.35)
STEP_RE = re.compile(r"Step=(\d+)ms\(amplitude=([\d.]+)\)")
# OEM 形态（OPPO/ColorOS 实测）：OplusPrebakedSegment{... mDuration=45, mEffectStrength=1684 ...}
OEM_RE = re.compile(r"\w*PrebakedSegment\{[^}]*mDuration=(\d+)[^}]*mEffectStrength=(\d+)")


def parse_played(text: str) -> list[tuple[int, float]] | None:
    """
    `played: …` 片段 → [(时长ms, 相对振幅0-1)]。

    ⚠️ 不同 OEM 的 dumpsys 格式不同。**解析不出来就返回 None**，由调用方原样记录
    —— 静默当成"没振动"会得出完全错误的结论。
    """
    steps = STEP_RE.findall(text)
    if steps:
        return [(int(d), float(a)) for d, a in steps]
    oem = OEM_RE.findall(text)
    if oem:
        peak = max(int(s) for _, s in oem) or 1
        # OEM 的 strength 是自有量纲（实测 ColorOS 满幅=2400），按峰值归一化
        return [(int(d), round(int(s) / peak, 3)) for d, s in oem]
    return None


def pulse_starts(segments: list[tuple[int, float]]) -> list[int]:
    """分段时长 → 脉冲绝对起点。振幅 0 的段是静默间隔。"""
    out, cursor = [], 0
    for dur, amp in segments:
        if amp > 0:
            out.append(cursor)
        cursor += dur
    return out


# 行首时间戳：`08-02 22:04:32.203 |   effect | …`
TS_RE = re.compile(r"^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")


def last_played(adb: Adb, after: str | None = None
                ) -> tuple[str, list[tuple[int, float]] | None, str]:
    """
    取本 App 最近一次振动记录。

    ⚠️ **不能用 `tail -1`**：`dumpsys vibrator_manager` 的振动历史**不按时间排在末尾**
    （实测 ColorOS 上新记录会插在旧记录之前），取末行会拿到陈旧数据 —— 首版就是这么
    让六个效果全都报出同一条记录的。**按行首时间戳择新**，并可用 [after] 过滤掉
    本次播放之前的记录。
    """
    txt = adb.shell("dumpsys vibrator_manager", timeout=90)
    cand: list[tuple[str, str]] = []
    for l in txt.splitlines():
        if PKG not in l or "played:" not in l:
            continue
        m = TS_RE.match(l.strip())
        ts = m.group(1) if m else ""
        if after and ts and ts <= after:
            continue
        cand.append((ts, l))
    if not cand:
        return "", None, ""
    ts, line = max(cand, key=lambda x: x[0])
    raw = line.split("played:")[1].split("| original")[0].strip()
    return raw, parse_played(raw), ts


def device_now(adb: Adb) -> str:
    """设备端时刻，格式对齐 dumpsys 的行首时间戳。"""
    return adb.shell('date "+%m-%d %H:%M:%S.000"').strip()


# ── 各测试项 ────────────────────────────────────────────────────────

def collect_device(adb: Adb) -> dict:
    info = {
        "model": adb.prop("ro.product.model"),
        "manufacturer": adb.prop("ro.product.manufacturer"),
        "sdk": adb.prop("ro.build.version.sdk"),
        "release": adb.prop("ro.build.version.release"),
        "fingerprint": adb.prop("ro.build.fingerprint"),
    }
    dump = adb.shell("dumpsys vibrator_manager", timeout=90)
    keep = ("capabilities", "supportedPrimitives", "frequencyProfile",
            "mResonantFrequency", "hasAmplitudeControl")
    info["vibrator_raw"] = [l.strip() for l in dump.splitlines()
                            if any(k in l for k in keep)][:8]
    return info


def test_v4(adb: Adb) -> dict:
    """V4：硬件档判定 + 原始依据。判据见 P0 验证计划 §五。"""
    adb.run("logcat", "-c")
    adb.start("--es dump 1")
    time.sleep(2)
    out = {}
    for line in adb.logcat_lines():
        m = re.search(r"V4 (\w+)=(\S+)", line)
        if m:
            out[m.group(1)] = m.group(2)
    return out


def test_waveform_fidelity(adb: Adb, spec: Spec) -> list[dict]:
    """
    **P-21 的机械化版本**：逐效果比对"我们提交的"与"设备实播的"。

    期望值来自参考实现（与双端同源于 spec），不是手抄 —— 手抄就又制造了一个真相源。
    """
    rows = []
    for sem_id in sorted(spec.semantics):
        rw = spec.resolve(sem_id, "LINEAR_X_FULL")
        if rw is None or rw.kind == "continuous":
            continue                      # silent 不产出 IR；continuous 无固定波形
        want_wf = to_android_waveform(rw.events)
        want_starts = [e.atMs for e in rw.events]

        mark = device_now(adb)            # 只认这一刻【之后】的记录
        adb.start(f"--es play {sem_id}")
        time.sleep(2.0)                   # 等播完 + dumpsys 落账
        raw, got, ts = last_played(adb, after=mark)

        row = {
            "semantic": sem_id,
            "effect": rw.effectId,
            "submitted": f"{want_wf['timings_ms']} / {want_wf['amplitudes']}",
            "want_starts": want_starts,
            "raw": raw[:160],
            "at": ts,
        }
        if not raw:
            row["verdict"] = "**本次播放没有任何振动记录** —— 可能被系统拦截或未触发"
            row["got_starts"] = None
        elif got is None:
            row["verdict"] = "解析失败（OEM 格式未知）—— 原样记录，勿当成未振动"
            row["got_starts"] = None
        else:
            got_starts = pulse_starts(got)
            row["got_starts"] = got_starts
            if got_starts == want_starts:
                row["verdict"] = "一致 ✓"
            elif len(got_starts) != len(want_starts):
                row["verdict"] = f"脉冲数不符：期望 {len(want_starts)} 实得 {len(got_starts)}"
            else:
                drift = [g - w for g, w in zip(got_starts, want_starts)]
                row["verdict"] = f"起点漂移 {drift}"
        rows.append(row)
    return rows


def test_v5(adb: Adb, samples: int) -> dict:
    """V5 A 段：软件延迟分位。**逐次播放，不用 burst** —— burst 下队列饱和，数据不代表正常使用。"""
    adb.shell(f"am force-stop {PKG}")
    time.sleep(1)
    adb.run("logcat", "-c")
    adb.shell(f"am start -n {ACT}")
    time.sleep(2)
    for _ in range(samples):
        adb.start("--es play item.dissolve")
        time.sleep(0.7)
    adb.start("--es dump 1")
    time.sleep(2)
    return {"lines": [l.split(f"{TAG}: ")[-1] for l in adb.logcat_lines()
                      if " V5 " in l]}


def test_stress(adb: Adb, ops: int) -> dict:
    """压测 + 运行时不变式。**monkey 补不上的那只眼睛**（见 StressHarness 注释）。"""
    adb.shell(f"am force-stop {PKG}")
    time.sleep(1)
    adb.run("logcat", "-c")
    adb.shell(f"am start -n {ACT}")
    time.sleep(2)
    adb.start(f"--es stress {ops}")
    time.sleep(max(12, ops * 0.03))       # 与夹具内的静息上限同量级
    adb.start("--es dump 1")
    time.sleep(2)
    lines = adb.logcat_lines()
    return {
        "violations": [l.split(f"{TAG}: ")[-1] for l in lines
                       if "违规" in l or l.strip().endswith("· 活跃 handle 未归零")
                       or " · " in l],
        "metrics": [l.split(f"{TAG}: ")[-1] for l in lines if "METRICS" in l],
        "states": [l.split(f"{TAG}: ")[-1] for l in lines if "STATES" in l],
    }


def _submission_times(adb: Adb, after: str) -> list[str]:
    """本 App 在 `after` 之后的所有振动提交时刻（行首时间戳）。"""
    txt = adb.shell("dumpsys vibrator_manager", timeout=90)
    out = []
    for l in txt.splitlines():
        if PKG not in l or "played:" not in l:
            continue
        m = TS_RE.match(l.strip())
        if m and m.group(1) > after:
            out.append(m.group(1))
    return sorted(out)


def _intervals_ms(stamps: list[str]) -> list[int]:
    """相邻提交的间隔（ms）。时间戳形如 `08-02 22:51:08.624`。"""
    def to_ms(t: str) -> int:
        hms, ms = t.split(" ")[1].split(".")
        h, m, sec = (int(x) for x in hms.split(":"))
        return ((h * 60 + m) * 60 + sec) * 1000 + int(ms)
    v = [to_ms(t) for t in stamps]
    return [b - a for a, b in zip(v, v[1:])]


FIRE_RE = re.compile(r"LS fire at=(\d+) dev=(-?\d+)ms wl=(\w+)")


def _ls_one(adb: Adb, mode: str, cell: str, sleep_ms: int, margin_ms: int):
    """
    跑一个样本：熄屏 → 等 sleep+margin → **脚本亮屏唤醒** → 读日志。

    唤醒交给脚本而不是 App 内闹钟：实测 `setAlarmClock` 在本机也唤不醒设备
    （它和被测定时器一起，在人手点亮屏幕那刻才响）。既然终究靠外力，就让外力可控。

    返回 dev（毫秒）；`None` 表示唤醒后都没触发。
    """
    adb.shell("am force-stop " + PKG)
    time.sleep(1)
    adb.run("logcat", "-c")
    adb.shell("am start -n " + ACT)
    time.sleep(2)
    adb.shell("am start-foreground-service -n %s/.V3Service --es wakelock %s --es sleepms %d"
              % (PKG, mode, sleep_ms))
    time.sleep(1)

    if cell in ("screen_off", "doze"):
        adb.shell("input keyevent 26")
        time.sleep(1.5)
    if cell == "doze":
        # USB 供电会把设备一直摁在 active，必须先伪装拔电
        adb.shell("dumpsys battery unplug")
        adb.shell("dumpsys deviceidle force-idle")

    time.sleep(sleep_ms / 1000.0 + margin_ms / 1000.0)

    if cell == "doze":
        adb.shell("dumpsys deviceidle unforce")
        adb.shell("dumpsys battery reset")
    if cell in ("screen_off", "doze"):
        adb.shell("input keyevent 26")      # ← 唤醒时刻由脚本掌握
        time.sleep(3)

    dev = None
    for l in adb.logcat_lines():
        m = FIRE_RE.search(l)
        if m:
            dev = int(m.group(2))
    adb.shell("am start-foreground-service -n %s/.V3Service --es cmd stop" % PKG)
    return dev


def _ls_stats(devs: list, margin_ms: int) -> dict:
    if not devs:
        return {"n": 0}
    ok = [d for d in devs if d is not None]
    d = sorted(ok)
    return {
        "n": len(devs),
        "missed": sum(1 for x in devs if x is None),
        "p50": d[len(d) // 2] if d else None,
        "max": d[-1] if d else None,
        # 偏差接近 margin = 被挂起到脚本唤醒才触发
        "deferred": sum(1 for x in d if x > margin_ms * 0.5),
        "ontime": sum(1 for x in d if x < 1000),
    }


def test_v3(adb: Adb, samples: int, sleep_ms: int, margin_ms: int, cells: list) -> dict:
    """
    V3 - wake lock 有效性（P-10）。**长睡眠调度延迟测法**。

    ## 测什么

    直接驱动库里的 `WakeLockGateway`（`PlaybackHandle` 用的同一个），跨一个
    **足够长到 CPU 真能挂起**的间隙排一个定时器，测它准不准。被保护的对象是
    **两次提交之间的调度间隙** —— 系统的 `*vibrator*` 锁只覆盖正在播的那一段。

    ## 上一版为什么作废

    800ms 串内间隔，两组都是 800ms±1ms，"看起来"是无效。**那是假的**：间隔太短设备
    来不及挂起，且每次播放自己就把 CPU 摁醒了。两组没差别是必然的 —— 不是证据，
    是实验没能让 CPU 睡着。改为 45s 长睡后，OFF 组首轮实测 **dev=91440ms**。

    ## 判读

    | `dev` | 含义 |
    |---|---|
    | < 1s | **准时** —— CPU 没睡，或锁挡住了睡眠 |
    | ≈ margin | **被挂起到脚本唤醒才触发** —— 正是 wake lock 要防的 |
    | 无记录 | 连唤醒后都没触发 |
    """
    out = {"sleep_ms": sleep_ms, "margin_ms": margin_ms, "samples": samples, "cells": {}}
    for cell in cells:
        out["cells"][cell] = {}
        for mode in ("on", "off"):
            devs = [_ls_one(adb, mode, cell, sleep_ms, margin_ms) for _ in range(samples)]
            out["cells"][cell][mode] = _ls_stats(devs, margin_ms)
            print("[devicetest]   %s/%s: %s" % (cell, mode, devs))
    return out


def test_monkey(adb: Adb, events: int) -> dict:
    """
    monkey —— **判据是"零 ANR"，不是"零 crash"**。

    零 crash 是本库的设计前提（A.1 约束 3），测出来是应该的、没信息量。
    monkey 唯一不可替代的价值是验证性能 §四.2：`makePlayer` / `vibrate` 不得在
    主线程同步调，多次调用会累积成 ANR。
    """
    adb.run("logcat", "-c")
    out = adb.run("shell", "monkey", "-p", PKG, "--throttle", "20",
                  "--pct-syskeys", "0", "-v", str(events), timeout=900)
    anr = adb.run("logcat", "-d", "-b", "main", "-s", "ActivityManager:E")
    crashes = adb.run("logcat", "-d", "-s", "AndroidRuntime:E")
    return {
        "finished": "Monkey finished" in out,
        "anr": [l for l in anr.splitlines() if "ANR" in l and PKG in l],
        "crash": [l for l in crashes.splitlines() if PKG in l],
    }


# ── 报告 ────────────────────────────────────────────────────────────

def render(dev: dict, res: dict) -> str:
    L: list[str] = []
    a = L.append
    a(f"# 真机测试报告 · {dev['manufacturer']} {dev['model']}")
    a("")
    a(f"- SDK **{dev['sdk']}**（Android {dev['release']}）")
    a(f"- 采集时间 {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    a(f"- fingerprint `{dev['fingerprint']}`")
    a("")
    a("## 设备振动器原始能力")
    a("")
    a("```")
    for l in dev["vibrator_raw"]:
        a(l)
    a("```")

    if "v4" in res:
        a("")
        a("## V4 · 硬件档探测（P-07）")
        a("")
        a("| 键 | 值 |")
        a("|---|---|")
        for k, v in res["v4"].items():
            a(f"| `{k}` | `{v}` |")
        a("")
        a("> **判据**（P0 计划 §五）：第三档 `LINEAR_X_LIMITED` 可探测需三条同时成立 ——")
        a("> `getFrequencyProfile()` 返回非 null、各机型数值有区分度、且该区分度与主观手感相关。")
        a("> 缺一即判「不可探测」，那样应考虑删掉该档整列。")

    if "waveform" in res:
        a("")
        a("## P-21 · 波形保真度（提交 vs 实播）")
        a("")
        a("| 效果 | 我们提交 | 期望脉冲起点 | 实播起点 | 结论 |")
        a("|---|---|---|---|---|")
        for r in res["waveform"]:
            a(f"| `{r['effect']}` | `{r['submitted']}` | {r['want_starts']} "
              f"| {r['got_starts']} | {r['verdict']} |")
        a("")
        a("<details><summary>dumpsys 原文</summary>")
        a("")
        for r in res["waveform"]:
            a(f"- `{r['effect']}`：`{r['raw']}`")
        a("")
        a("</details>")
        a("")
        a("> **起点漂移非 0 即为 OEM 改写了我们的波形**，不是实现缺陷 —— CI 规则 6 拦不到，")
        a("> 它比对的是生成数组与手写镜像，管不到系统实际播出什么。详见 P-21。")

    if "v5" in res:
        a("")
        a("## V5 A 段 · 软件延迟")
        a("")
        a("```")
        for l in res["v5"]["lines"]:
            a(l)
        a("```")
        a("")
        a("> ⚠️ 这里只有 **T0→T2 软件段**。T2→T3 马达 rise time 与对齐误差需外置加速度计")
        a("> 或 ≥960fps 摄像（V5 B 段），本脚本测不了。")

    if "stress" in res:
        a("")
        a("## 压测 · 运行时不变式")
        a("")
        a("```")
        for l in res["stress"]["metrics"] + res["stress"]["states"]:
            a(l)
        a("```")
        v = [x for x in res["stress"]["violations"] if x.strip()]
        a("")
        a("**违规**：" + ("无 ✓" if not v else ""))
        for l in v:
            a(f"- {l}")

    if "v3" in res:
        v = res["v3"]
        a("")
        a("## V3 - wake lock 有效性（P-10）· 长睡眠调度延迟")
        a("")
        a("- 每轮睡 **%dms** 其间什么都不做,让设备真正挂起;超时 %dms 后**由脚本亮屏唤醒**"
          % (v["sleep_ms"], v["margin_ms"]))
        a("- 直接驱动库里的 `WakeLockGateway`(`PlaybackHandle` 用的同一个)")
        a("")
        a("| 场景 | wake lock | n | 准时(<1s) | **被推迟** | 未触发 | 偏差 p50 | 偏差最大 |")
        a("|---|---|---|---|---|---|---|---|")
        for cell, g in v["cells"].items():
            for mode in ("on", "off"):
                d = g.get(mode, {})
                if not d.get("n"):
                    a("| %s | %s | **0** | - | - | - | - | - |" % (cell, mode.upper()))
                    continue
                p50 = "-" if d["p50"] is None else "%dms" % d["p50"]
                mx = "-" if d["max"] is None else "%dms" % d["max"]
                a("| %s | **%s** | %d | %d | **%d** | %d | %s | %s |"
                  % (cell, mode.upper(), d["n"], d["ontime"], d["deferred"],
                     d["missed"], p50, mx))
        a("")
        for cell, g in v["cells"].items():
            on, off = g.get("on", {}), g.get("off", {})
            if not on.get("n") or not off.get("n"):
                a("- `%s`：样本不足,无结论" % cell)
                continue
            bad_on = on["deferred"] + on["missed"]
            bad_off = off["deferred"] + off["missed"]
            diff = bad_off - bad_on
            rel = diff * 100.0 / max(1, on["n"])
            tag = "**有效**" if rel >= 20 else ("无效" if abs(rel) < 5 else "灰区")
            a("- `%s`：OFF 组坏样本 %d/%d,ON 组 %d/%d,相差 %.0f 个百分点 -> %s"
              % (cell, bad_off, off["n"], bad_on, on["n"], rel, tag))
        a("")
        a("> **判据**(P0 计划 4.6)：OFF 组坏样本率比 ON 组高 >=20 个百分点 -> **有效,保留**;")
        a("> 两组无差异 -> 无效,删掉 `AndroidWakeLock` 与 `WAKE_LOCK` 权限。")
        a("> 坏样本 = 被推迟(dev > margin/2) 或 未触发。")
        a(">")
        a("> `screen_on` 是基线 —— CPU 本来就醒着,两组都该准时;它证明夹具没坏。")
        a(">")
        a("> ⚠️ 上一版用 800ms 串内间隔,两组都是 800ms±1ms —— **那是假结论**:间隔太短")
        a("> 设备来不及挂起,且每次播放自己就把 CPU 摁醒了(`*vibrator*` 系统锁)。")

    if "monkey" in res:
        m = res["monkey"]
        a("")
        a("## monkey")
        a("")
        a(f"- 跑完：{'是' if m['finished'] else '**否（可能中途崩溃）**'}")
        a(f"- ANR：{len(m['anr'])}  ← **这才是判据**")
        a(f"- crash：{len(m['crash'])}（零 crash 是设计前提，没信息量）")
        for l in m["anr"] + m["crash"]:
            a(f"  - `{l[:160]}`")

    a("")
    a("---")
    a("")
    a("**未覆盖**：真机手感、T2→T3 物理延迟、对齐误差、多点连续手势（P-20 已声明只支持一路）。")
    return "\n".join(L) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description="CipherHaptic 真机测试驱动")
    ap.add_argument("--serial")
    ap.add_argument("--only", default="v4,waveform,v5,stress",
                    help="逗号分隔：v4,waveform,v5,stress,monkey")
    ap.add_argument("--v5-samples", type=int, default=20)
    ap.add_argument("--stress-ops", type=int, default=500)
    ap.add_argument("--monkey", type=int, default=20000)
    ap.add_argument("--v3-samples", type=int, default=5)
    ap.add_argument("--v3-sleep-ms", type=int, default=45000,
                    help="必须 < AndroidWakeLock.MAX_HOLD_MS(60s),否则 ON 组锁会自己过期")
    ap.add_argument("--v3-margin-ms", type=int, default=15000,
                    help="预定触发后再等多久才由脚本亮屏唤醒")
    ap.add_argument("--v3-cells", default="screen_on,screen_off,doze")
    ap.add_argument("--out", default=os.path.join(ROOT, "reports"))
    args = ap.parse_args()

    adb = Adb(args.serial)
    devs = adb.devices()
    if not devs:
        print("[devicetest] 没有连接的设备。`adb devices` 确认后重试。", file=sys.stderr)
        return 2
    if len(devs) > 1 and not args.serial:
        print(f"[devicetest] 检测到多台设备 {devs}，用 --serial 指定。", file=sys.stderr)
        return 2

    only = {s.strip() for s in args.only.split(",") if s.strip()}
    if args.monkey and "monkey" in only:
        pass

    print(f"[devicetest] 目标设备 {devs[0] if not args.serial else args.serial}")
    dev = collect_device(adb)
    print(f"[devicetest] {dev['manufacturer']} {dev['model']} · SDK {dev['sdk']}")

    # 装应用（若未装）
    if PKG not in adb.shell(f"pm list packages {PKG}"):
        apk = os.path.join(ROOT, "android", "demo", "build", "outputs",
                           "apk", "debug", "demo-debug.apk")
        if not os.path.isfile(apk):
            print("[devicetest] demo APK 不存在，先跑 "
                  "`cd android && ./gradlew :demo:assembleDebug`", file=sys.stderr)
            return 2
        print("[devicetest] 安装 demo…")
        adb.run("install", "-r", apk, timeout=180)

    spec = Spec()
    res: dict = {}
    if "v4" in only:
        print("[devicetest] V4 硬件档…")
        res["v4"] = test_v4(adb)
    if "waveform" in only:
        print("[devicetest] P-21 波形保真度…")
        res["waveform"] = test_waveform_fidelity(adb, spec)
    if "v5" in only:
        print(f"[devicetest] V5 延迟（{args.v5_samples} 次）…")
        res["v5"] = test_v5(adb, args.v5_samples)
    if "stress" in only:
        print(f"[devicetest] 压测（{args.stress_ops} 次）…")
        res["stress"] = test_stress(adb, args.stress_ops)
    if "v3" in only:
        cells = [c.strip() for c in args.v3_cells.split(",") if c.strip()]
        per = args.v3_samples * (args.v3_sleep_ms / 1000.0 + args.v3_margin_ms / 1000.0 + 9)
        print(f"[devicetest] V3 长睡眠（格 {cells}，每组 {args.v3_samples} 次 x "
              f"{args.v3_sleep_ms}ms，预计 {int(len(cells) * 2 * per / 60)} 分钟）…")
        res["v3"] = test_v3(adb, args.v3_samples, args.v3_sleep_ms,
                            args.v3_margin_ms, cells)
    if "monkey" in only:
        print(f"[devicetest] monkey（{args.monkey} 事件，可能数分钟）…")
        res["monkey"] = test_monkey(adb, args.monkey)

    os.makedirs(args.out, exist_ok=True)
    name = f"{dev['model']}-sdk{dev['sdk']}-{datetime.now():%Y%m%d}"
    md = os.path.join(args.out, f"{name}.md")
    io.open(md, "w", encoding="utf-8", newline="").write(render(dev, res))
    io.open(os.path.join(args.out, f"{name}.json"), "w",
            encoding="utf-8", newline="").write(
        json.dumps({"device": dev, "results": res}, ensure_ascii=False, indent=2))
    print(f"[devicetest] 报告 → {md}")

    # 波形漂移是本脚本最该显式提醒的结论
    drift = [r for r in res.get("waveform", []) if "漂移" in r["verdict"]]
    if drift:
        print(f"[devicetest] ⚠️ {len(drift)} 个效果的脉冲起点被 OEM 改写（见 P-21）：")
        for r in drift:
            print(f"    {r['effect']}: {r['want_starts']} → {r['got_starts']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
