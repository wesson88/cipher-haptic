"""CI 失败详情 → GitHub annotation。公开仓库的 job 日志需鉴权才能读，annotation 不需要。"""
import glob
import re
import sys
import xml.etree.ElementTree as ET


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


log = open(sys.argv[1], encoding="utf-8", errors="replace").read() if len(sys.argv) > 1 else ""
n = 0
# Kotlin 编译诊断。2.x：`e: file:///p/X.kt:12:5 msg`；1.9：`e: file:///p/X.kt: (12, 5): msg`。
# 开了 -Werror 的模块里 `w:` 同样致命，一并转出
for m in re.finditer(r"^([ew]): (?:file://)?(\S+?\.kts?):? ?\(?(\d+)(?::|, )\d+\)?:? (.*)$", log, re.M):
    level = "error" if m.group(1) == "e" else "warning"
    print("::%s file=%s,line=%s::%s" % (level, m.group(2), m.group(3), esc(m.group(4))))
    n += 1
for m in re.finditer(r"^e: (?!file:)(.*)$", log, re.M):
    print("::error::%s" % esc(m.group(1)))
    n += 1
# Gradle 的 What went wrong 段
for m in re.finditer(r"\* What went wrong:\n(.+?)(?:\n\* Try:|\Z)", log, re.S):
    print("::error::%s" % esc(m.group(1).strip()[:1500]))
    n += 1
# JUnit 失败用例
for f in glob.glob("**/build/test-results/**/*.xml", recursive=True):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for tc in root.iter("testcase"):
        for bad in list(tc.findall("failure")) + list(tc.findall("error")):
            msg = (bad.get("message") or "") + "\n" + (bad.text or "")[:1200]
            print("::error title=%s::%s" % (esc(tc.get("classname", "") + "." + tc.get("name", ""))[:200], esc(msg)))
            n += 1
print("annotations: %d" % n)
