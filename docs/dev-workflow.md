# Development workflow

`mise run upload` builds a development APK, installs it on the Zidoo and points its crash reports
and analytics at this PC; `mise run receiver` collects them. Normal builds send nothing.

## Tasks

| Command | What it does |
| --- | --- |
| `mise run build` | Release build signed with the debug key, then `tools/check-dex-access.py` (see [r8-release-builds.md](r8-release-builds.md)). Crash reports and analytics **off**. |
| `mise run upload` | Same build with both reporting URLs set to this PC, posted to the box's web installer (`ZIDOO_URL` overrides the box). |
| `mise run receiver` | Starts `tools/dev-receiver.py` on port **8797**. |
| `mise run trace` | Asks the running app for a CPU trace (`TRACE_SECONDS`, default 10). |

Port 8797, not 8787: another local tool already used 8787.

After an upload, the install kills the app. Launch it again with
`curl "http://<box>:9529/ZidooControlCenter/Apps/openApp?packageName=com.jackra1n.dooplex"`
and press a key if the box was idle ([zidoo-box.md](zidoo-box.md)).

## Reporting is opt-in at build time

`DOOPLEX_CRASH_URL` and `DOOPLEX_ANALYTICS_URL` become `BuildConfig` fields; empty means off, and
both are empty unless the build environment sets them. Only `mise run upload` sets them. **Decision
(2026-10):** Dooplex will be open source, so nobody who builds or installs it may send reports
without having chosen to. Keep it that way: never give these a default.

## Crash reports

`CrashReporter` installs in `DooplexApplication.attachBaseContext`, before content providers start,
so it sees crashes during startup too. On an uncaught exception it first writes
`pending-crash.txt`, then sends the stack trace plus the last 400 logcat lines from a helper thread
(the main thread may not use the network) and waits up to 5 s. The next start resends a pending
file and reports earlier abnormal exits from `ApplicationExitInfo`. logcat runs into a file with a
deadline, because a stuck logcat once blocked the whole report.

Reports land in `crashes/`, one file each.

## Analytics

`Analytics` batches JSON events and posts them every 4 s to `/events`; they land in
`analytics/YYYY-MM-DD.jsonl` and the receiver prints a digest. Events: `session`, `first-frame`,
`screen`, `key`, `slow-frame`, `frames` (sent even when idle), `stats` (timings such as
`poster.wait.ms`, `poster.disk.ms`, `poster.network.ms`, `home.load.ms`, `wash.render.ms`),
`memory` (every 32 s), `command`, `ready`, `flags`, and `trace-start` / `trace-done` /
`trace-failed`. What the frame numbers mean is in
[measuring-performance.md](measuring-performance.md).

Every reply from the receiver can carry instructions back to the app:

| Reply line | Source | Effect |
| --- | --- | --- |
| `flags a=0,b=1` | `analytics/flags` | Experiment switches, read with `Analytics.flag(name)`. |
| `do <command>` | `analytics/commands` | Benchmark commands (see below); the file is consumed. |
| `trace <seconds>` | `analytics/trace-request` | Starts a sampling CPU trace, uploaded to `traces/`. |

Commands (`MainActivity.runCommand`): `clear-memory`, `clear-disk`, `home` (rebuild Home behind the
start screen), `open:movie` / `open:show` (reopen a library fresh), `focus` (back to the first
item), `tab:home` / `tab:movie` / `tab:show` (same as confirming that tab). They do nothing during
playback or a busy action.

## CPU traces

`TRACE_SECONDS=45 mise run trace`, reproduce the problem, then `python3 tools/trace-report.py`. It
reads ART's sampling trace (1 ms), maps R8-renamed library names back with `mapping.txt`, and
prints main-thread self time overall and for the 6 worst slow frames (`--frames`). It cannot see
RenderThread or GPU work; for those, look at the frame's `sync`, `cmd` and `swap` numbers instead.

`mapping.txt` must come from the build that produced the trace. Names R8 merged can still come out
misleading (one trace blamed 375 ms on `FontWeight$Companion.e`); treat a surprising leaf name as a
hint, not a fact.
