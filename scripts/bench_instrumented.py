#!/usr/bin/env python3
"""One run of the course benchmark through the shared harness, with server-side measurements.

    scripts/bench_instrumented.py --repo DIR --label LABEL --out DIR [--settle | --analyze]
        [--friend-sql FILE] [--harness FILE] [--bench-out DIR] [--lock FILE] SQL_FILE...

The run itself is `HARNESS DIR LABEL SQL_FILE...` while this script holds an exclusive flock on
LOCK. HARNESS (--harness, or $BENCH_HARNESS) defaults to scripts/run_bench.sh, the vendored copy
of /home/user/work/bench6/run_bench.sh (same JVM flags, same schema reset); it writes LABEL.log
and LABEL.json to --bench-out ($BENCH_OUT, default /home/user/work/bench6). LOCK (--lock, or
$BENCH_LOCK) defaults to /home/user/work/bench.lock. The lock is taken before anything about the
run is recorded and released after the WAL has been read. Alongside the run this script follows the
runner's log and records, at every step boundary, the server's WAL insert position and the
kernel's Dirty figure. After the run it reads, for every step, the WAL between those positions
with pg_waldump (records, record bytes, full-page-image bytes), and the server log's checkpoint
lines during the run. It also records pg_stat_wal and pg_stat_bgwriter before the run, at the
start of step 10 and the end of step 23 (the write steps are 10 and 12-23 apart from 20) and after
the run, the boot time before and after the run, and the git commit, working-tree state and jar
hash of DIR, taken once the lock is held.

--settle pauses the runner (SIGSTOP) as soon as step 1 (the import) has reported, runs CHECKPOINT
as postgres, waits for Dirty to fall below 64 MB, and lets it continue. --analyze does the same
with VACUUM ANALYZE as the table owner. Either is a variant, not the official procedure; the JSON
says whether the pause began before step 2 had started.

--friend-sql names a file with the friend-recommendation query of DIR's version (":mid" for the
user); its plan is recorded with EXPLAIN (no ANALYZE) when step 7 reports, together with the
planner statistics of followings and the table's last (auto)analyze times.

The raw report and log are copied to --out as LABEL.json and LABEL.log, the measurements are
written to LABEL.monitor.json. Only the local server is used.
"""
import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.realpath(__file__))
PG_LOG = "/var/log/postgresql/postgresql-16-main.log"
WALDUMP = "/usr/lib/postgresql/16/bin/pg_waldump"
PG_WAL = "/var/lib/postgresql/16/main/pg_wal"
STEP_START = re.compile(r"^(\S+ \S+)\s.*BenchmarkRunner\s+: Step (\d+):")
STEP_END = re.compile(r"^(\S+ \S+)\s.*BenchmarkResult\(id=(\d+),")
LOCAL = dt.timezone(dt.timedelta(hours=8))  # the runner runs with -Duser.timezone=Asia/Shanghai


class Psql:
    """One persistent psql session (the monitor needs answers within a few milliseconds)."""

    def __init__(self):
        env = dict(os.environ, PGPASSWORD=os.environ.get("PGPASSWORD", "sustc"))
        self.p = subprocess.Popen(
            ["psql", "-h", "localhost", "-U", "sustc", "-d", "sustc", "-X", "-At", "-q", "-F", "\t"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)

    def rows(self, sql):
        self.p.stdin.write(sql.rstrip().rstrip(";") + ";\n\\echo __end__\n")
        self.p.stdin.flush()
        out = []
        for line in self.p.stdout:
            line = line.rstrip("\n")
            if line == "__end__":
                return out
            out.append(line.split("\t"))
        raise RuntimeError("psql ended")

    def value(self, sql):
        r = self.rows(sql)
        return r[0][0] if r and r[0] else None

    def close(self):
        self.p.stdin.close()
        self.p.wait()


def dirty_kb():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("Dirty:"):
                return int(line.split()[1])
    return None


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout.strip()


def counters(db):
    wal = db.rows("select wal_records, wal_fpi, wal_bytes, wal_write, wal_sync from pg_stat_wal")[0]
    bg = db.rows("select checkpoints_timed, checkpoints_req, buffers_checkpoint, buffers_clean,"
                 " buffers_backend, buffers_backend_fsync from pg_stat_bgwriter")[0]
    keys_w = ["wal_records", "wal_fpi", "wal_bytes", "wal_write", "wal_sync"]
    keys_b = ["checkpoints_timed", "checkpoints_req", "buffers_checkpoint", "buffers_clean",
              "buffers_backend", "buffers_backend_fsync"]
    return {"pg_stat_wal": dict(zip(keys_w, map(float, wal))),
            "pg_stat_bgwriter": dict(zip(keys_b, map(int, bg))),
            "lsn": db.value("select pg_current_wal_insert_lsn()"),
            "dirty_kb": dirty_kb(), "epoch": time.time()}


def waldump(start, end):
    if start == end:
        return {"records": 0, "record_bytes": 0, "fpi_bytes": 0, "bytes": 0}
    out = subprocess.run(["sudo", "-n", "-u", "postgres", WALDUMP, "--stats", "-s", start, "-e", end, "-p", PG_WAL],
                         capture_output=True, text=True)
    for line in out.stdout.splitlines():
        if line.startswith("Total"):
            f = re.sub(r"\[[^]]*\]", " ", line).split()
            return {"records": int(f[1]), "record_bytes": int(f[2]), "fpi_bytes": int(f[3]), "bytes": int(f[4])}
    return {"error": (out.stdout + out.stderr)[:300]}


def descendants(pid):
    kids = sh("pgrep", "-P", str(pid)).split()
    out = []
    for k in kids:
        out.append(int(k))
        out.extend(descendants(int(k)))
    return out


def runner_pid(root):
    for p in descendants(root):
        try:
            with open(f"/proc/{p}/cmdline", "rb") as f:
                cmd = f.read().split(b"\0")
        except OSError:
            continue
        if cmd and cmd[0].endswith(b"java") and b"--spring.profiles.active=benchmark" in cmd:
            return p
    return None


def server_log_lines(since_epoch, until_epoch):
    text = subprocess.run(["sudo", "-n", "tail", "-n", "4000", PG_LOG], capture_output=True, text=True).stdout
    keep = []
    for line in text.splitlines():
        m = re.match(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+) UTC .*LOG:\s+(checkpoint (starting|complete).*)", line)
        if not m:
            continue
        t = dt.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=dt.timezone.utc).timestamp()
        if since_epoch - 600 <= t <= until_epoch + 5:
            keep.append({"epoch": t, "utc": m.group(1), "line": m.group(2)[:400]})
    return keep


def pause(pid, log_path, action, db):
    """SIGSTOP the runner, settle or analyze, SIGCONT; report whether step 2 had begun."""
    os.kill(pid, signal.SIGSTOP)
    t0 = time.time()
    time.sleep(0.05)
    with open(log_path) as f:
        step2_started = any(STEP_START.match(l) and STEP_START.match(l).group(2) == "2" for l in f)
    info = {"stopped_epoch": t0, "step2_started_before_pause": step2_started, "dirty_kb_before": dirty_kb()}
    if action == "settle":
        r = subprocess.run(["sudo", "-n", "-u", "postgres", "psql", "-Atqc", "checkpoint"], capture_output=True, text=True)
        info["checkpoint_exit"] = r.returncode
        deadline = time.time() + 120
        while dirty_kb() > 65536 and time.time() < deadline:
            time.sleep(0.5)
    else:
        info["vacuum_analyze_exit"] = subprocess.run(
            ["psql", "-h", "localhost", "-U", "sustc", "-d", "sustc", "-Atqc", "vacuum analyze"],
            capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="sustc")).returncode
        info["followings_last_analyze"] = db.value(
            "select last_analyze from pg_stat_user_tables where relname = 'followings'")
    info["dirty_kb_after"] = dirty_kb()
    info["pause_s"] = round(time.time() - t0, 3)
    os.kill(pid, signal.SIGCONT)
    return info


def planner_state(db, friend_sql, mid):
    st = {"followings_stats": db.rows(
        "select attname, n_distinct, null_frac, correlation from pg_stats"
        " where schemaname = 'public' and tablename = 'followings' order by attname"),
        "followings_table": db.rows(
        "select n_live_tup, n_mod_since_analyze, last_analyze, last_autoanalyze, autoanalyze_count"
        " from pg_stat_user_tables where relname = 'followings'")}
    if friend_sql:
        q = open(friend_sql).read().replace(":mid", str(mid))
        st["friend_plan"] = [r[0] for r in db.rows("explain " + q)]
    return st


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--out", required=True)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--settle", action="store_true")
    g.add_argument("--analyze", action="store_true")
    ap.add_argument("--friend-sql")
    ap.add_argument("--friend-mid", type=int, default=1)
    ap.add_argument("--harness", default=os.environ.get("BENCH_HARNESS", os.path.join(HERE, "run_bench.sh")))
    ap.add_argument("--bench-out", default=os.environ.get("BENCH_OUT", "/home/user/work/bench6"))
    ap.add_argument("--lock", default=os.environ.get("BENCH_LOCK", "/home/user/work/bench.lock"))
    ap.add_argument("sql", nargs="+")
    a = ap.parse_args()
    bench, harness = os.path.realpath(a.bench_out), os.path.realpath(a.harness)
    if not re.fullmatch(r"[A-Za-z0-9._-]+", a.label):
        sys.exit("bad label")
    log_path, report = f"{bench}/{a.label}.log", f"{bench}/{a.label}.json"
    if os.path.exists(log_path) or os.path.exists(report):
        sys.exit(f"{log_path} or {report} exists; use a new label")
    os.makedirs(a.out, exist_ok=True)
    repo = os.path.realpath(a.repo)
    jar = f"{repo}/run/sustc-runner.jar"
    meta = {
        "label": a.label, "repo": repo,
        "sql": a.sql, "variant": "settle" if a.settle else "analyze" if a.analyze else "plain",
        "command": [harness, repo, a.label] + a.sql, "bench_out": bench, "lock": a.lock,
        "harness_sha256": hashlib.sha256(open(harness, "rb").read()).hexdigest(),
    }
    lock_fd = os.open(a.lock, os.O_RDONLY | os.O_CREAT, 0o666)
    meta["lock_wait_started_epoch"] = time.time()
    fcntl.flock(lock_fd, fcntl.LOCK_EX)  # the same lock as flock(1) takes
    meta["lock_acquired_epoch"] = time.time()
    # What the run measures, recorded while the lock is held.
    meta["code_sha"] = sh("git", "-C", repo, "rev-parse", "HEAD")
    meta["porcelain_lines"] = len([l for l in sh("git", "-C", repo, "status", "--porcelain", "--untracked-files=no").splitlines() if l])
    meta["jar_sha256"] = hashlib.sha256(open(jar, "rb").read()).hexdigest()
    meta["sql_sha256"] = {f: hashlib.sha256(open(f, "rb").read()).hexdigest() for f in a.sql}
    meta["boot"] = sh("uptime", "-s")
    db = Psql()
    meta["server"] = db.value("select version()")
    meta["java"] = [l for l in subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()
                    if not l.startswith("Picked up")][0]
    proc = subprocess.Popen(meta["command"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            env=dict(os.environ, BENCH_OUT=bench))
    events, pause_info, planner, before = [], None, {}, None
    seen = 0
    f = None
    while True:
        if f is None and os.path.exists(log_path):
            f = open(log_path)
            before = counters(db)  # the schema is reset and java has started
        if f is not None:
            for line in f.readlines():
                seen += 1
                for kind, rx in (("start", STEP_START), ("end", STEP_END)):
                    m = rx.match(line)
                    if not m:
                        continue
                    step = int(m.group(2))
                    ev = {"kind": kind, "step": step, "epoch": time.time(),
                          "log_epoch": dt.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f")
                          .replace(tzinfo=LOCAL).timestamp(),
                          "lsn": db.value("select pg_current_wal_insert_lsn()"), "dirty_kb": dirty_kb()}
                    events.append(ev)
                    if kind == "end" and step == 1 and (a.settle or a.analyze):
                        pid = runner_pid(proc.pid)
                        pause_info = pause(pid, log_path, "settle" if a.settle else "analyze", db) if pid else {
                            "error": "runner not found"}
                    if kind == "end" and step == 7:
                        planner["before_step_8"] = planner_state(db, a.friend_sql, a.friend_mid)
                    if kind == "end" and step == 8:
                        planner["after_step_8"] = planner_state(db, None, a.friend_mid)
                    if kind == "start" and step == 10:
                        ev["counters"] = counters(db)
                    if kind == "end" and step == 23:
                        ev["counters"] = counters(db)
        if proc.poll() is not None:
            break
        time.sleep(0.01)
    after = counters(db)
    db.close()
    meta["harness_output"] = proc.stdout.read()[-500:]
    meta["boot_after"] = sh("uptime", "-s")
    # WAL per step, from the LSN at its start to the LSN at its end.
    starts = {e["step"]: e for e in events if e["kind"] == "start"}
    steps = {}
    for e in events:
        if e["kind"] == "end" and e["step"] in starts:
            s = starts[e["step"]]
            steps[e["step"]] = {"lsn_start": s["lsn"], "lsn_end": e["lsn"], "wal": waldump(s["lsn"], e["lsn"]),
                                "dirty_kb_start": s["dirty_kb"], "log_start_epoch": s["log_epoch"],
                                "log_end_epoch": e["log_epoch"]}
    t_first = events[0]["log_epoch"] if events else time.time()
    out = {"meta": meta, "before": before, "after": after, "pause": pause_info, "planner": planner,
           "events": events, "steps": steps,
           "checkpoints": server_log_lines(t_first, after["epoch"])}
    os.close(lock_fd)  # releases the lock
    meta["lock_released_epoch"] = time.time()
    if os.path.exists(report):
        out["report"] = json.load(open(report))
    with open(f"{a.out}/{a.label}.monitor.json", "w") as fo:
        json.dump(out, fo, indent=1, default=str)
    for src, dst in ((report, f"{a.out}/{a.label}.json"), (log_path, f"{a.out}/{a.label}.log")):
        if os.path.exists(src):
            with open(src) as fi, open(dst, "w") as fo:
                fo.write(fi.read())
    with open(f"{a.out}/{a.label}.log", "a") as fo:
        fo.write(f"code_sha={meta['code_sha']} porcelain_lines={meta['porcelain_lines']} "
                 f"jar_sha256={meta['jar_sha256']} boot={meta['boot']} boot_after={meta['boot_after']} "
                 f"variant={meta['variant']}\n")
    print(f"{a.label}: exit {proc.returncode}, {len(steps)} steps, boot {meta['boot']} / {meta['boot_after']}")


if __name__ == "__main__":
    main()
