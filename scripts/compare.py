#!/usr/bin/env python3
"""Summarize sustc benchmark JSON files: per-step median time and pass counts.

usage: compare.py label1=file1.json,file2.json label2=file3.json ...

Vendored from /home/user/work/bench6/compare.py, the summary of the recorded runs. One change:
wider columns, so that a time with its range ("103387 [101273-106993]") no longer runs into the
next column. The figures are computed as before.
"""
import json, statistics, sys

TOTALS = {2: 20, 3: 61, 4: 56, 5: 43, 6: 11, 7: 27, 8: 142, 9: 382, 10: 1016, 11: 500, 12: 500,
          13: 511, 14: 686, 15: 644, 16: 184, 17: 273, 18: 184, 19: 35, 20: 6, 21: 55, 22: 199, 23: 87}
NAMES = {1: "import", 2: "search", 3: "view rate", 4: "hotspot", 5: "similar videos", 6: "general rec",
         7: "user rec", 8: "friend rec", 9: "danmu display", 10: "danmu send", 11: "user info",
         12: "danmu like", 13: "coin", 14: "like", 15: "collect", 16: "post video", 17: "register",
         18: "update video", 19: "review", 20: "search after update", 21: "delete video",
         22: "delete account", 23: "follow"}
TIME_W, PASS_W = 26, 14

def load(spec):
    label, files = spec.split("=", 1)
    runs = [{r["id"]: r for r in json.load(open(f))} for f in files.split(",")]
    return label, runs

def main():
    groups = [load(a) for a in sys.argv[1:]]
    hdr = f"{'step':<23}" + "".join(f"{' ' + l + ' ms':>{TIME_W}}{' ' + l + ' pass':>{PASS_W}}" for l, _ in groups)
    print(hdr)
    tot_q = {l: 0 for l, _ in groups}
    tot_pass = {l: 0 for l, _ in groups}
    for sid in range(1, 24):
        row = f"{sid:>2} {NAMES[sid]:<20}"
        for l, runs in groups:
            ts = [r[sid]["elapsedTime"] for r in runs if sid in r and r[sid]["elapsedTime"] is not None]
            ps = [r[sid]["passCnt"] for r in runs if sid in r and r[sid]["passCnt"] is not None]
            med = statistics.median(ts) if ts else None
            if med is not None and sid >= 2:
                tot_q[l] += med
            p = min(ps) if ps else None
            if p is not None:
                tot_pass[l] += p
            tstr = "-" if med is None else (f"{med:.0f}" + (f" [{min(ts)}-{max(ts)}]" if len(ts) > 1 else ""))
            pstr = "-" if p is None else f"{p}/{TOTALS.get(sid, '?')}"
            row += f"{tstr:>{TIME_W}}{pstr:>{PASS_W}}"
        print(row)
    print("sum of median query-step times (steps 2-23, ms):", {l: round(v) for l, v in tot_q.items()})
    print("total passed cases (min over runs):", tot_pass, "of", sum(TOTALS.values()))

if __name__ == "__main__":
    main()
