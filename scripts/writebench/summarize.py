#!/usr/bin/env python3
"""Summarizes WriteBench steady-state runs: median [min-max] over rounds, per variant and operation.

    scripts/writebench/summarize.py VARIANT=FILE[,FILE...] [VARIANT=...] [--metric p50_ms ...]

Each FILE is one run (one JVM: import, settle, warm-up, measured calls). For every operation the
table shows, per variant, the median over the runs of the per-run figure, with the smallest and
largest run in brackets, and the ratio of the first variant's median to the others'.
"""
import json
import statistics
import sys

METRICS = ["throughput_per_s", "p50_ms", "p95_ms", "wal_bytes_per_call", "wal_fpi_bytes_per_call"]


def load(spec):
    name, _, files = spec.partition("=")
    runs = [json.load(open(f)) for f in files.split(",")]
    return name, runs


def fmt(values):
    if not values:
        return "-"
    m = statistics.median(values)
    return f"{m:.3f} [{min(values):.3f}-{max(values):.3f}]" if m < 100 else f"{m:.0f} [{min(values):.0f}-{max(values):.0f}]"


def main(argv):
    metrics = METRICS
    if "--metric" in argv:
        i = argv.index("--metric")
        metrics = argv[i + 1:]
        argv = argv[:i]
    variants = [load(a) for a in argv]
    ops = []
    for _, runs in variants:
        for run in runs:
            for r in run["results"]:
                if r["op"] not in ops:
                    ops.append(r["op"])
    clients = sorted({run["clients"] for _, runs in variants for run in runs})
    for c in clients:
        for metric in metrics:
            print(f"\n### {metric}, {c} client(s)\n")
            names = [n for n, _ in variants]
            print("| operation | " + " | ".join(f"{n} (n runs)" for n in names) + " | ratio |")
            print("|---|" + "---:|" * (len(names) + 1))
            for op in ops:
                cells, medians = [], []
                for _, runs in variants:
                    vals = [r[metric] for run in runs if run["clients"] == c for r in run["results"]
                            if r["op"] == op and metric in r]
                    cells.append(fmt(vals) + f" ({len(vals)})")
                    medians.append(statistics.median(vals) if vals else None)
                ratio = "-"
                if len(medians) > 1 and medians[0] and medians[1]:
                    ratio = f"{medians[0] / medians[1]:.2f}"
                print(f"| {op} | " + " | ".join(cells) + f" | {ratio} |")


if __name__ == "__main__":
    main(sys.argv[1:])
