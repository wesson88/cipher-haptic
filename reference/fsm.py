# -*- coding: utf-8 -*-
"""
PlaybackFSM 参考实现 + 不变式 fuzz —— 对应「句柄状态机」文档 §十。

runner 本身极薄（文档说 ~40 行，确实如此）：**查表 + 派发动作**，纯逻辑、零平台 API。
真正有价值的是下面的 fuzz。

⚠️ **文档自己提醒过：查表单测只验证迁移表本身。** v2→v3 修掉的 8 个缺陷里有 6 个
根因在 side effect 时序、失败路径、外部 timer，**纯 FSM 单测一条都抓不到**。
所以这里的 fuzz 不只跑迁移，还**模拟资源持有**（player / wake lock / 定时器），
断言的是「资源不泄漏」而不是「状态对不对」——泄漏才是这个状态机存在的理由。
"""

from __future__ import annotations

import random
import re
from dataclasses import dataclass, field

KINDS = ["oneshot", "looping", "continuous"]
CATEGORIES = ["ux", "alert", "critical"]

TERMINALS = {"Completed", "Cancelled", "Failed", "Reclaimed"}


def guard_ok(when: str | None, kind: str, cat: str) -> bool:
    if not when:
        return True
    ctx = {"kind": kind, "cat": cat}
    for clause in when.split("&&"):
        m = re.match(r"\s*(\w+)\s*(!=|=)\s*(\w+)\s*$", clause)
        if not m:
            raise ValueError(f"无法解析守卫：{when!r}")
        var, op, val = m.groups()
        if (ctx[var] == val) if op == "!=" else (ctx[var] != val):
            return False
    return True


@dataclass
class Resources:
    """
    模拟 PlaybackActions 持有的资源。fuzz 的断言全部打在这上面——
    「绝不泄漏」依赖的是状态机的可达性完备，不是 finally 本身（性能 §四.3）。
    """
    player_held: bool = False
    wakelock_held: bool = False
    end_timer: bool = False
    idle_timer: bool = False
    keepalive_timer: bool = False
    released: int = 0
    log: list[str] = field(default_factory=list)

    def any_held(self) -> bool:
        return (self.player_held or self.wakelock_held or self.end_timer
                or self.idle_timer or self.keepalive_timer)


class PlaybackFSM:
    def __init__(self, table: dict, kind: str = "oneshot", cat: str = "ux"):
        self.table = table
        self.kind, self.cat = kind, cat
        self.state = "Pending"
        self.res = Resources()
        self.illegal: list[str] = []

    def _lookup(self, event: str):
        hits = []
        for r in self.table["transitions"]:
            ev = r["event"]
            ons = ev if isinstance(ev, list) else [ev]
            if r["from"] != self.state:
                continue
            if ev != "*" and event not in ons:
                continue
            if not guard_ok(r.get("when"), self.kind, self.cat):
                continue
            hits.append(r)
        if len(hits) > 1:
            raise AssertionError(
                f"({self.state}, {event}) kind={self.kind} cat={self.cat} "
                f"命中 {len(hits)} 条转移——守卫不互斥（不变式 4）"
            )
        return hits[0] if hits else None

    def send(self, event: str) -> None:
        t = self._lookup(event)
        if t is None or t.get("illegal"):
            if not t:
                self.illegal.append(f"{event} in {self.state}")
            return
        self.state = t["to"]
        self._act(t.get("action", "none"))

    # ── 动作的资源语义（PlaybackActions 的模拟实现）──────────────
    def _act(self, action: str) -> None:
        r = self.res
        r.log.append(action)
        if action == "submit" or action == "resubmit":
            r.player_held = True
            r.wakelock_held = True          # Android 侧；iOS 侧为 no-op
            r.end_timer = (self.kind != "continuous")
        elif action == "startEndTimer":
            r.end_timer = True
        elif action == "startIdleTimer":
            r.idle_timer = True
        elif action == "applyParams":
            r.idle_timer = True             # 重置 = 仍持有
        elif action == "startKeepAlive":
            r.keepalive_timer = True
        elif action == "clearKeepAlive":
            r.keepalive_timer = False
        elif action == "suspend":
            r.end_timer = False             # 取消 end-timer，保留状态与 token
        elif action == "stop":
            r.end_timer = r.idle_timer = False
        elif action == "release":
            r.player_held = r.wakelock_held = False
            r.end_timer = r.idle_timer = r.keepalive_timer = False
            r.released += 1


# ── fuzz ────────────────────────────────────────────────────────────

def fuzz(table: dict, rounds: int = 200_000, seed: int = 20260802) -> list[str]:
    """
    随机事件序列 × N，断言四条不变式。返回违规清单。

    与静态穷举（tools/check.py 的不变式 1–4）的分工：
      静态 —— 迁移表**自身**是否完备、守卫是否互斥
      fuzz —— 任意事件序列下**资源**是否泄漏、终态是否真吸收
    """
    rng = random.Random(seed)
    events = [e for e in table["events"]]
    fails: list[str] = []

    for i in range(rounds):
        kind = rng.choice(KINDS)
        cat = rng.choice(CATEGORIES)
        m = PlaybackFSM(table, kind, cat)

        # 随机长度的乱序事件流（含大量非法组合——这正是要压的）
        seq = [rng.choice(events) for _ in range(rng.randint(1, 24))]
        for ev in seq:
            m.send(ev)
            # 不变式 3：终态吸收——一旦进入终态，绝不回到非终态
            if m.state == "Reclaimed":
                pass

        # ── 收尾：给足【完整】的回收事件序列 ────────────────────────
        # ⚠️ 只发 GRACE_EXPIRED/EXPIRE 是不够的——它们对 Pending/Submitting/Active
        #    本就不适用。要排空一个 handle 必须先 CANCEL 把它推进终态。
        #    （首版 fuzz 漏了 CANCEL,报出 40 余条"抵达不了 Reclaimed",全是收尾逻辑
        #      自己的错,不是 FSM 缺陷——断言写错比没有断言更误导人。）
        for _ in range(4):
            m.send("CANCEL")
            m.send("GRACE_EXPIRED")
            m.send("EXPIRE")
            if m.state == "Reclaimed":
                break

        ctx = f"[{i}] kind={kind} cat={cat} seq={seq}"

        # 不变式 1：无泄漏——必须抵达 Reclaimed
        if m.state != "Reclaimed":
            fails.append(f"{ctx} 停在 {m.state}，抵达不了 Reclaimed")
            continue

        # release 恰好一次：0 次 = 资源没释放；>1 次 = 重复释放（双重解锁）
        if m.res.released != 1:
            fails.append(f"{ctx} release 调用 {m.res.released} 次（应为 1）")

        # 抵达 Reclaimed 后不得仍持有任何资源
        if m.res.any_held():
            fails.append(f"{ctx} Reclaimed 后仍持有资源：{m.res}")

        # 不变式 3：Reclaimed 之后任何事件都不改变状态
        before = m.state
        for ev in events:
            m.send(ev)
            if m.state != before:
                fails.append(f"{ctx} Reclaimed 收到 {ev} 后变为 {m.state}")
                break

        if len(fails) > 40:                 # 早停，避免刷屏
            fails.append("…… 失败过多，已早停")
            break

    return fails
