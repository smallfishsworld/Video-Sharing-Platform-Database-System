#!/usr/bin/env python3
"""Builds the before/after benchmark table of the READMEs from the reports in docs/benchmark.

    python3 scripts/bench_table.py                      # print the English table
    python3 scripts/bench_table.py --write README.md README.zh-CN.md

Every baseline-*.json and optimized-*.json file is one run of the course benchmark (see
scripts/benchmark.sh). For each step the table shows the median elapsed time over the runs of a
group, the range in brackets, and the speedup computed from the two medians; the pass count is
the minimum over the runs. --write replaces the text between the markers
<!-- benchmark-table:start --> and <!-- benchmark-table:end --> in each given file; a file whose
name contains "zh" gets Chinese headings.
"""
import argparse
import glob
import json
import os
import re
import statistics
import sys

STEPS = {
    1: ("import", "数据导入", None),
    2: ("search", "搜索视频", 20),
    3: ("view rate", "平均观看率", 61),
    4: ("hotspot", "弹幕热点", 56),
    5: ("similar videos", "相似视频", 43),
    6: ("general rec", "通用推荐", 11),
    7: ("user rec", "个性化推荐", 27),
    8: ("friend rec", "好友推荐", 142),
    9: ("danmu display", "弹幕展示", 382),
    10: ("danmu send", "发送弹幕", 1016),
    11: ("user info", "用户信息", 500),
    12: ("danmu like", "弹幕点赞", 500),
    13: ("coin", "投币", 511),
    14: ("like", "点赞", 686),
    15: ("collect", "收藏", 644),
    16: ("post video", "发布视频", 184),
    17: ("register", "注册", 273),
    18: ("update video", "更新视频", 184),
    19: ("review", "审核", 35),
    20: ("search after update", "更新后搜索", 6),
    21: ("delete video", "删除视频", 55),
    22: ("delete account", "删除账号", 199),
    23: ("follow", "关注", 87),
}
TOTAL_CASES = sum(t for _, _, t in STEPS.values() if t)

HEADINGS = {
    "en": {
        "step": "Step",
        "before": "Baseline ms",
        "after": "Optimized ms",
        "speedup": "Speedup",
        "pass": "Passed (baseline → optimized)",
        "sum": "Sum of query steps 2–23",
        "cases": "All test cases",
        "untimed": "not timed",
    },
    "zh": {
        "step": "步骤",
        "before": "基线 ms",
        "after": "优化后 ms",
        "speedup": "加速比",
        "pass": "通过数（基线 → 优化后）",
        "sum": "查询步骤 2–23 合计",
        "cases": "全部测试用例",
        "untimed": "不计时",
    },
}
START = "<!-- benchmark-table:start -->"
END = "<!-- benchmark-table:end -->"


def load_group(directory, prefix):
    files = sorted(glob.glob(os.path.join(directory, prefix + "-*.json")))
    if not files:
        sys.exit(f"no {prefix}-*.json files in {directory}")
    runs = []
    for f in files:
        with open(f, encoding="utf-8") as fh:
            runs.append({r["id"]: r for r in json.load(fh)})
    return files, runs


def summarize(runs):
    """step id -> (median ms or None, min, max, minimum pass count or None)"""
    out = {}
    for sid in STEPS:
        times = [r[sid]["elapsedTime"] for r in runs if sid in r and r[sid]["elapsedTime"] is not None]
        passes = [r[sid]["passCnt"] for r in runs if sid in r and r[sid]["passCnt"] is not None]
        med = statistics.median(times) if times else None
        out[sid] = (med, min(times) if times else None, max(times) if times else None,
                    min(passes) if passes else None)
    return out


def fmt_time(med, lo, hi):
    if med is None:
        return None
    text = f"{med:,.0f}"
    if lo is not None and hi is not None and lo != hi:
        text += f" [{lo:,}–{hi:,}]"
    return text


def fmt_speedup(before, after):
    if not before or not after:
        return "–"
    ratio = before / after
    return f"**{ratio:.1f}×**" if ratio >= 1 else f"{ratio:.2f}×"


def table(before, after, n_before, n_after, lang):
    h = HEADINGS[lang]
    lines = [
        f"| {h['step']} | {h['before']} (n={n_before}) | {h['after']} (n={n_after}) | {h['speedup']} | {h['pass']} |",
        "|---|---:|---:|---:|---:|",
    ]
    sum_before = sum_after = 0
    pass_before = pass_after = 0
    for sid, (en, zh, total) in STEPS.items():
        b, a = before[sid], after[sid]
        name = f"{sid} {en if lang == 'en' else zh}"
        tb, ta = fmt_time(*b[:3]), fmt_time(*a[:3])
        if sid >= 2 and b[0] is not None and a[0] is not None:
            sum_before += b[0]
            sum_after += a[0]
        if b[3] is not None:
            pass_before += b[3]
        if a[3] is not None:
            pass_after += a[3]
        passed = "–" if total is None else f"{b[3]} → {a[3]} / {total}"
        lines.append(
            f"| {name} | {tb or h['untimed']} | {ta or h['untimed']} | {fmt_speedup(b[0], a[0])} | {passed} |")
    lines.append(
        f"| **{h['sum']}** | **{sum_before:,.0f}** | **{sum_after:,.0f}** | {fmt_speedup(sum_before, sum_after)} | |")
    lines.append(f"| **{h['cases']}** | | | | **{pass_before} → {pass_after} / {TOTAL_CASES}** |")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs",
                                                      "benchmark", "round1"))
    parser.add_argument("--write", nargs="*", default=[], metavar="FILE")
    args = parser.parse_args()

    before_files, before_runs = load_group(args.dir, "baseline")
    after_files, after_runs = load_group(args.dir, "optimized")
    before, after = summarize(before_runs), summarize(after_runs)

    if not args.write:
        print(table(before, after, len(before_files), len(after_files), "en"))
        return
    for path in args.write:
        lang = "zh" if "zh" in os.path.basename(path) else "en"
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        block = f"{START}\n{table(before, after, len(before_files), len(after_files), lang)}\n{END}"
        new, count = re.subn(re.escape(START) + r".*?" + re.escape(END), lambda _: block, text, flags=re.S)
        if count != 1:
            sys.exit(f"{path}: expected exactly one {START} ... {END} block, found {count}")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(new)
        print(f"updated {path}")


if __name__ == "__main__":
    main()
