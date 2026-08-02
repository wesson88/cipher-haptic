#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
黄金用例生成器 —— 双端行为等价测试的基准（工程骨架 §六.6）。

对 `semantics × hardwareClass × globalScale` 的全组合跑一遍参考实现，把
`ResolvedWaveform` 与两端翻译结果冻结成 `spec/golden.json`。

双端原生实现各自跑同一组输入，逐字段 diff。**任何一端与本文件不符即构建失败。**

为什么基准由 Python 参考实现产出，而不是由任一端产出：
  谁产出基准，谁就成了事实标准。让 iOS 或 Android 任一端当基准，另一端就永远在
  "对齐它"，而它自己的 bug 无人校验 —— 这正是 v1.1.0 手写双端数组的失败模式。
  参考实现是第三方，它的正确性由 CI 规则 6（生成结果与 SSOT 手写镜像 diff）反过来钉住。
"""

from __future__ import annotations

import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from reference.loader import Spec                                     # noqa: E402
from reference.translate import (                                     # noqa: E402
    to_android_composition, to_android_waveform, to_ios_events,
)

HARDWARE_CLASSES = ["ERM_Z", "LINEAR_X_LIMITED", "LINEAR_X_FULL"]
SCALES = [1.0, 0.5]          # 1.0 = 原始；0.5 验证 ④scale 先于 ⑤degrade 的求值顺序


def main() -> int:
    s = Spec()
    cases = []
    for sem in sorted(s.semantics):
        for hc in HARDWARE_CLASSES:
            for sc in SCALES:
                rw = s.resolve(sem, hc, sc)
                case: dict = {
                    "semantic": sem,
                    "hardwareClass": hc,
                    "globalScale": sc,
                }
                if rw is None:
                    # 降级为 silent：不产出 IR、不创建 handle（IR 文档 §3.3③）
                    case["drop"] = "degraded-to-silent"
                    cases.append(case)
                    continue

                errs = rw.validate()
                if errs:
                    print(f"[golden] 参考实现产出非法 IR：{sem}×{hc}×{sc} {errs}",
                          file=sys.stderr)
                    return 2

                case["ir"] = {
                    "semanticId": rw.semanticId,
                    "effectId": rw.effectId,
                    "category": rw.category,
                    "kind": rw.kind,
                    "totalDurationMs": rw.totalDurationMs,
                    "loopGapMs": rw.loopGapMs,
                    "protected": rw.protected,
                    "degradeTrace": rw.degradeTrace,
                    "events": [
                        {"atMs": e.atMs, "durationMs": e.durationMs,
                         "intensity": round(e.intensity, 6),
                         "sharpness": round(e.sharpness, 6), "kind": e.kind}
                        for e in rw.events
                    ],
                }
                if rw.continuous:
                    c = rw.continuous
                    case["ir"]["continuous"] = {
                        "initialIntensity": c.initialIntensity,
                        "initialSharpness": c.initialSharpness,
                        "maxDurationMs": c.maxDurationMs,
                        "segmentMs": c.segmentMs,
                        "idleTimeoutMs": c.idleTimeoutMs,
                    }
                case["ios"] = to_ios_events(rw.events)
                case["androidWaveform"] = to_android_waveform(
                    rw.events, looping=(rw.kind == "looping"))
                if all(e.kind == "pulse" for e in rw.events) and rw.events:
                    case["androidComposition"] = to_android_composition(rw.events)
                cases.append(case)

    out = {
        "_generated": "由 tools/golden.py 从 reference/ 参考实现产出，禁止手改",
        "_note": "双端原生实现跑同一组输入，逐字段 diff；不符即构建失败",
        "cases": cases,
    }
    path = os.path.join(os.path.dirname(__file__), "..", "spec", "golden.json")
    io.open(path, "w", encoding="utf-8", newline="").write(
        json.dumps(out, ensure_ascii=False, indent=2, sort_keys=True) + "\n")

    drops = sum(1 for c in cases if "drop" in c)
    print(f"[golden] {len(cases)} 个用例（其中 {drops} 个 drop）→ spec/golden.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
