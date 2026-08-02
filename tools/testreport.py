#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试结果汇总 —— 从 JUnit XML 读实际执行结果。

**为什么需要它**：`BUILD SUCCESSFUL` 不等于测试跑过了。

- Gradle 的 UP-TO-DATE 会跳过任务，控制台照样打 BUILD SUCCESSFUL；
- AGP 的 `testDebugUnitTest` **不往控制台打 PASSED**，只写 XML；
- 于是"grep PASSED 计数"这种做法会漏掉整个模块，而且漏得悄无声息。

2026-08-02 就差点这么放过 library 模块的 7 个测试 —— 控制台只见 core 的 6 个，
BUILD SUCCESSFUL 却照常。**验证结果必须读产物，不能读日志。**

用法：
    python tools/testreport.py [--min N]   # 断言至少 N 个测试执行过
"""

from __future__ import annotations

import argparse
import glob
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SUITE_RE = re.compile(
    r'<testsuite name="([^"]+)"[^>]*tests="(\d+)"\s+skipped="(\d+)"'
    r'\s+failures="(\d+)"\s+errors="(\d+)"'
)
CASE_RE = re.compile(r'<testcase name="([^"]+)"')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min", type=int, default=1,
                    help="至少要有多少个测试执行过，否则判失败")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    pattern = os.path.join(ROOT, "android", "*", "build", "test-results", "**", "*.xml")
    files = glob.glob(pattern, recursive=True)
    if not files:
        print("[testreport] 找不到任何 JUnit XML —— 测试从未执行过", file=sys.stderr)
        return 2

    # variant 会让同一个类跑多遍（debug/release），按 (类名, 用例集) 去重后再计数
    seen: dict[str, tuple[int, int, int, int, list[str]]] = {}
    for p in sorted(files):
        s = io.open(p, encoding="utf-8").read()
        m = SUITE_RE.search(s)
        if not m:
            continue
        name, tests, skipped, fails, errs = m.groups()
        cases = CASE_RE.findall(s)
        prev = seen.get(name)
        cur = (int(tests), int(skipped), int(fails), int(errs), cases)
        if prev is None or cur[2] + cur[3] > prev[2] + prev[3]:
            seen[name] = cur           # 保留失败更多的那次，不掩盖问题

    total = passed = failed = skipped = 0
    for name in sorted(seen):
        t, sk, fa, er, cases = seen[name]
        total += t
        skipped += sk
        failed += fa + er
        passed += t - sk - fa - er
        mark = "FAIL" if (fa or er) else " ok "
        print(f"[{mark}] {name.split('.')[-1]:28s} {t:2d} 个用例"
              + (f"  失败 {fa + er}" if fa or er else ""))
        if args.verbose:
            for c in cases:
                print(f"          · {c}")

    print(f"\n合计 {total} 个测试：通过 {passed} · 失败 {failed} · 跳过 {skipped}")

    if failed:
        print("[testreport] 存在失败用例", file=sys.stderr)
        return 1
    if total < args.min:
        print(f"[testreport] 只有 {total} 个测试执行过，少于要求的 {args.min} —— "
              f"很可能有模块被 UP-TO-DATE 跳过了，用 --rerun-tasks 重跑",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
