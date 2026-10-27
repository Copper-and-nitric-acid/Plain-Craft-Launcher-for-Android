#!/usr/bin/env python3
"""
把 JVM GC 日志和帧率采样对齐，回答一个问题：
「掉帧里有多少是被 GC 停顿解释掉的？」

用法:
  python3 analyze_gc.py gc.log                     # 只看 GC 统计
  python3 analyze_gc.py gc.log fps.csv             # 对齐帧率谷底
  python3 analyze_gc.py gc.log fps.csv --window 500

gc.log 支持两种格式(自动识别):
  JDK 8  : 2026-10-20T12:00:01.234+0800: [GC pause (G1 Evacuation Pause) (young), 0.0123456 secs]
  JDK 17+: [2026-10-20T12:00:01.234+0800][info][gc] GC(12) Pause Young (Normal) (G1 Evacuation Pause) 123M->45M(2048M) 12.345ms

fps.csv 格式(有无表头都行, 列顺序: 秒, fps):
  1.0,144
  1.5,139
"""
import csv
import re
import sys
from datetime import datetime

TS = r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{4})"
RE_JDK8 = re.compile(TS + r".*?_GC_REGISTRY_")
RE_PAUSE = re.compile(r"(?:Pause[^,]*,?\s*)?([\d.]+)\s*ms\s*$")
RE_PAUSE_JDK8 = re.compile(r"([\d.]+)\s*secs\]?\s*$")


def parse_ts(s):
    for f in ("%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S.%f"):
        try:
            return datetime.strptime(s[:24] + s[24:] if len(s) > 24 else s, f)
        except ValueError:
            continue
    return None


def parse_gc(path):
    events = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.search(TS, line)
        if not m:
            continue
        ts = parse_ts(m.group(1))
        if ts is None:
            continue
        if "Pause" not in line and "[GC" not in line and "[Full GC" not in line:
            continue
        p = RE_PAUSE.search(line.rstrip())
        if p:
            events.append((ts, float(p.group(1))))
            continue
        p = RE_PAUSE_JDK8.search(line.rstrip())
        if p:
            events.append((ts, float(p.group(1)) * 1000.0))
    return events


def parse_fps(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for r in csv.reader(f):
            if len(r) < 2:
                continue
            try:
                rows.append((float(r[0]), float(r[1])))
            except ValueError:
                continue  # 表头
    return rows


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    gc_events = parse_gc(sys.argv[1])
    if not gc_events:
        print("没解析到 GC 事件。确认日志是用 -Xlog:gc* 或 -Xloggc 打的。")
        return 1

    pauses = [d for _, d in gc_events]
    span = (gc_events[-1][0] - gc_events[0][0]).total_seconds()
    print(f"GC 事件 {len(gc_events)} 次，跨度 {span:.1f}s")
    print(f"  最长停顿 {max(pauses):.1f} ms")
    print(f"  平均停顿 {sum(pauses)/len(pauses):.1f} ms")
    print(f"  停顿总和 {sum(pauses):.0f} ms = 占墙钟 {sum(pauses)/1000/max(span,1e-9)*100:.2f}%")
    over50 = [p for p in pauses if p > 50]
    print(f"  >50ms 的停顿 {len(over50)} 次（这些才会被肉眼看到）")

    if len(sys.argv) < 3:
        return 0

    window = 500
    if "--window" in sys.argv:
        window = int(sys.argv[sys.argv.index("--window") + 1])
    fps = parse_fps(sys.argv[2])
    if not fps:
        print("帧率文件没解析到数据。")
        return 1

    values = [v for _, v in fps]
    med = sorted(values)[len(values) // 2]
    low = [t for t, v in fps if v < med * 0.6]
    hit = sum(
        1 for t in low
        if any(abs((ts - fps[0][0]).total_seconds() - t) * 1000 <= window for ts, _ in gc_events)
    )
    print(f"\n帧率中位数 {med:.0f}，谷底采样 {len(low)} 个")
    print(f"  其中 {hit} 个落在某次 GC 前后 {window}ms 内 → {hit/max(len(low),1)*100:.0f}%")
    if hit / max(len(low), 1) > 0.6:
        print("  结论：谷底基本由 GC 解释，实锤。")
    elif hit / max(len(low), 1) < 0.2:
        print("  结论：谷底和 GC 无关，去查光照/渲染路径。")
    else:
        print("  结论：部分相关，GC 是因素之一但不是全部。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
