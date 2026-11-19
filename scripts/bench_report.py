#!/usr/bin/env python3
"""Reports what scripts/bench_instrumented.py recorded around the runs of scripts/bench_series.sh.

    scripts/bench_report.py DIR [DIR...]

DIR holds <prefix>-B<i> (the original implementation) and <prefix>-O<i> (this version) runs, each
as .json (the course benchmark's report) and .monitor.json. Per DIR it prints the run order, the
boots, commits and jars; per run the sums of the read, write and all 21 timed steps; the WAL that
each write step generated (pg_waldump over the step's LSN range, median [min-max] over the runs);
the checkpoints that the server logged around the write steps; what the --settle or --analyze
pause did; and the planner's view of the follow graph before step 8. The per-step medians are in
scripts/bench_table.py.
"""
import glob
import json
import os
import statistics
import sys

READ = [2, 3, 4, 5, 6, 7, 8, 9, 11, 20]
WRITE = [10, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23]
NAMES = {10: "danmu send", 13: "coin", 14: "like", 15: "collect", 16: "post video", 17: "register",
         18: "update video", 19: "review", 21: "delete video", 22: "delete account", 23: "follow"}


def load(directory):
    runs = []
    for f in glob.glob(os.path.join(directory, "*.monitor.json")):
        with open(f, encoding="utf-8") as fh:
            m = json.load(fh)
        m["times"] = {r["id"]: r["elapsedTime"] for r in m.get("report", [])}
        runs.append(m)
    runs.sort(key=lambda r: r["before"]["epoch"])
    return runs


def cell(values):
    if not values:
        return "-"
    m = statistics.median(values)
    return f"{m:,.0f}" if min(values) == max(values) else f"{m:,.0f} [{min(values):,.0f}-{max(values):,.0f}]"


def kind(run):
    return run["meta"]["label"].rsplit("-", 1)[1][0]


def report(directory):
    runs = load(directory)
    if not runs:
        print(f"no runs in {directory}")
        return
    print(f"## {directory}\n")
    boots = sorted({r["meta"][k] for r in runs for k in ("boot", "boot_after")})
    print("boots (before and after each run):", ", ".join(boots))
    for k in "BO":
        ids = sorted({(r["meta"]["code_sha"][:7], r["meta"]["jar_sha256"][:16], r["meta"]["porcelain_lines"])
                      for r in runs if kind(r) == k})
        print(f"{k}: commit, jar sha256 prefix, uncommitted tracked paths: {ids}")
    print("order:", ", ".join(r["meta"]["label"] for r in runs))
    print()
    print("| run | read steps ms | write steps ms | all 21 timed steps ms | cases passed |")
    print("|---|---:|---:|---:|---:|")
    for r in runs:
        t = r["times"]
        passed = sum(x["passCnt"] or 0 for x in r.get("report", []))
        print(f"| {r['meta']['label']} | {sum(t[i] for i in READ):,} | {sum(t[i] for i in WRITE):,} | "
              f"{sum(t[i] for i in READ + WRITE):,} | {passed} |")
    print()
    print("WAL per write step, kB (pg_waldump: all records; FPI: the full-page images among them)\n")
    print("| step | B: WAL | B: FPI | O: WAL | O: FPI |")
    print("|---|---:|---:|---:|---:|")
    for sid in WRITE + ["all"]:
        cells = []
        for k in "BO":
            wal, fpi = [], []
            for r in runs:
                if kind(r) != k:
                    continue
                ws = [r["steps"][str(s)]["wal"] for s in (WRITE if sid == "all" else [sid])]
                if all("bytes" in w for w in ws):
                    wal.append(sum(w["bytes"] for w in ws) / 1024)
                    fpi.append(sum(w["fpi_bytes"] for w in ws) / 1024)
            cells += [cell(wal), cell(fpi)]
        name = "all write steps" if sid == "all" else f"{sid} {NAMES[sid]}"
        print(f"| {name} | " + " | ".join(cells) + " |")
    print()
    print("Checkpoints in the server log, seconds relative to the start of step 10 (write steps end at the "
          "second given in brackets); Dirty from /proc/meminfo at the start of step 10\n")
    for r in runs:
        st = r["steps"]
        t10, t23 = st["10"]["log_start_epoch"], st["23"]["log_end_epoch"]
        marks = []
        for c in r["checkpoints"]:
            rel = c["epoch"] - t10
            if -300 <= rel <= t23 - t10 + 60:
                if c["line"].startswith("checkpoint starting"):
                    marks.append(f"start {rel:+.0f} ({c['line'].split(':', 1)[1].strip()})")
                elif c["line"].startswith("checkpoint complete"):
                    marks.append(f"end {rel:+.0f}")
        ev = {(e["kind"], e["step"]): e for e in r["events"]}
        c10, c23 = ev[("start", 10)].get("counters"), ev[("end", 23)].get("counters")
        fpi = c23["pg_stat_wal"]["wal_fpi"] - c10["pg_stat_wal"]["wal_fpi"] if c10 and c23 else None
        print(f"- {r['meta']['label']}: write steps 0 to {t23 - t10:.1f} s; {'; '.join(marks) or 'no checkpoint'}; "
              f"Dirty {st['10']['dirty_kb_start']:,} kB; pg_stat_wal wal_fpi delta over the write steps {fpi}")
    pauses = [(r["meta"]["label"], r["pause"]) for r in runs if r.get("pause")]
    if pauses:
        print("\nPause after step 1:")
        for label, p in pauses:
            print(f"- {label}: {json.dumps(p)[:400]}")
    print("\nPlanner before step 8 (pg_stats n_distinct of followings, last autoanalyze, plan's top join):")
    for r in runs:
        p = r["planner"].get("before_step_8", {})
        plan = p.get("friend_plan") or []
        joins = [line.strip() for line in plan if "Join" in line or "Nested Loop" in line][:1]
        print(f"- {r['meta']['label']}: {p.get('followings_stats')}; table {p.get('followings_table')}; {joins}")
    print()


if __name__ == "__main__":
    for d in sys.argv[1:]:
        report(d)
