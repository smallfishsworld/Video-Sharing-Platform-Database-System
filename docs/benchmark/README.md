# Raw benchmark files

The raw reports, logs and monitor files of every recorded run, by round. The rounds are defined in the main README's [methodology](../../README.md#methodology): round 1 to round 3 are the three measured iterations of the 2026 rebuild (`978fe40`, `a330c0c`, `ac3a0dc`), and round 4 is the WriteBench re-run on the final code. The files are kept as the tools wrote them, except for the environment strings below.

| Directory | Contents |
|---|---|
| `round1/` | the first runs of both versions, one after the other (not interleaved); `dev/`: single development runs |
| `round2/` | interleaved series `c2` (`plain/`), the `settle/` and `analyze/` variants, `writebench/`, the early-return experiment `b1/`, EXPLAIN inputs in `queries/` |
| `round3/` | the interleaved series `c6` that the README quotes (`plain/`), the stopped `1717da9` series, `writebench/` |
| `round4/` | `writebench/`: the deletion group and the stress mode re-run on the final code |
| `original-result.json`, `reference-standard.json` | 2023 files from another machine, not used |

## Environment strings

- **Paths under `/home/user`.** The machine's working directories appear in many files (about 250): `/home/user/work/Video-Sharing-Platform-Database-System` is this repository, `/home/user/work/vs-baseline` a worktree of the local branch `bench/baseline-clock` (the 2023 implementation with the clock offset, `3796695`), `/home/user/work/bench6` the directory of the harness that `scripts/run_bench.sh` and `scripts/compare.py` were copied from, and `/home/user/work/bench.lock` the lock that kept timed runs apart. They are left as written.
- **`<scratch>/`** stands for the sandbox's temporary directory, which held copies of earlier commits (for example `<scratch>/r3/r2`, the jar and SQL of `a330c0c`) and the Gradle clone of the CI proof. The real path is removed.
- **`Picked up JAVA_TOOL_OPTIONS` and `Picked up _JAVA_OPTIONS`.** Every JVM on the machine echoed two variables set by the sandbox. The first held proxy and trust-store settings (`-Djavax.net.ssl.*`, `-Dhttps.proxy*`, `-Dhttp.nonProxyHosts`), the second the same two diagnostic flags in all 114 logs that print it, `-XX:+UnlockDiagnosticVMOptions -XX:DisableIntrinsic=_base64_encodeBlock,_base64_decodeBlock`, for both versions alike. Both lines are replaced by a placeholder.
- **`AuthInfo(mid=…, password=…, qq=…, wechat=…)`** in the logs of the 2023 implementation (13 logs) is the framework's debug output for the cases it answered wrongly. The values are the course's test data (the bundled `.ser` files), not credentials of real people.
- **Run metadata.** Each series run's `.log` ends with the commit, the number of uncommitted tracked files, the jar's sha256 and the boot time (`scripts/bench_instrumented.py`; until round 3 recorded before the run waited for the lock, from round 4 on once it holds it, together with the boot time after the run). From round 4 on, each WriteBench JSON records under `options` git HEAD and `git status --porcelain` of the checkout (`?? docs/benchmark/round4/` is the run's own output directory) and the SHA-256 and repository HEAD of every SQL file it applied; `code_sha` is a label chosen by the caller.
