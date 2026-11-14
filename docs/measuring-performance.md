# Measuring performance

Judge smoothness by **dropped frames** from `tools/bench.py` on the real box, not by "slow frames"
and not by feel alone. Run every comparison as an A/B on the same build.

## The numbers analytics reports

Each `slow-frame` event (a frame longer than 1.5 × 16.7 ms = 25 ms) carries Android's
`FrameMetrics` split into phases, in ms:

| Field | What took the time |
| --- | --- |
| `delay` | The main thread was busy before the frame started (often the previous slow frame). |
| `input` | Key handling. |
| `anim` | Compose composition (Choreographer animation callbacks). |
| `layout`, `draw` | Compose measure, layout and drawing into display lists. Lazy lists compose new items during measure, so first-time items show up here. |
| `sync` | Copying display lists and uploading textures to the RenderThread. |
| `cmd`, `swap` | RenderThread issuing GPU commands, and waiting for the GPU. High `cmd` means GPU-bound. |

Each also has `cause`: the last key, screen change or `hero:swap` within 600 ms.

**Slow is not the same as visible.** Android overlaps the main thread and the RenderThread, so a
frame can take 27 ms end to end and still reach the screen on time. Grid scrolling once showed
11% slow frames but only 0.3% dropped. `frames` events therefore also count **`dropped`**: vsyncs
skipped between two frames when the previous frame ran long or the main thread was busy, which is
the stutter you can see. Gaps after a light frame are idle time and do not count.

## Running a benchmark

`mise run receiver` must be running and Dooplex open on any screen. Then for example:

```sh
python3 tools/bench.py --pattern home                # Home rows, 3 passes
python3 tools/bench.py --pattern deep --cold disk    # visit new Home rows with empty caches
python3 tools/bench.py --pattern movie               # grid: single steps and held-down bursts
python3 tools/bench.py --pattern switch --passes 2   # Movies, Home, TV Shows, Home tabs
python3 tools/bench.py --pattern movie --flags name=0       # A/B an Analytics.flag("name")
```

Each run wakes the box, applies the flags, resets the app to the same state with a command, drives
keys through the control API (0.6 s steps, 0.08 s for held bursts) and prints frames, dropped,
slow, the worst frames with their cause, and poster timings. Results also go to
`analytics/bench.log`. `--cold memory` clears decoded posters, `--cold disk` also the disk cache.

Pitfalls:

- Never upload a build while a benchmark runs; the install kills the app mid-run.
- Start both sides of an A/B the same way (for example `--cold memory` on both); a warm baseline
  against a cold experiment measures the cache, not the change.
- Single runs swing a lot (the same build gave 0.9% and 0.0%). To compare two builds, build both
  APKs and install them in turn, A, B, A, B, running the same pattern after each install.
- A fresh install has no compiled code: Android runs it interpreted and JIT-compiled until its
  idle-time dexopt compiles it from a profile, and every reinstall starts over. So a benchmark right
  after `mise run upload` measures the JIT state, which may differ from an install that sat
  overnight. The `session` event reports `odexBytes` (compiled code on disk) and
  `compiledProfileBytes` (0 until dexopt has used a profile); compare runs only when they match.
  A fresh install on the box has an 82,920 B odex for 3 MB of dex and no profile, so nothing is
  compiled (2026-11-14; the emulator shows the same). A build installed two weeks earlier dropped
  as many frames as the same code freshly installed (below), but it could not report whether it
  had been compiled, so what compiled code is worth here is still unmeasured.
- Two builds that ask for different poster sizes push each other's files out of the disk cache
  when installed in turn, so the first run after each install downloads posters. Discard it.
- A shell loop over option strings needs `${=a}` in zsh, or every run gets one bad argument.
- The bench waits for the app's next 4 s analytics upload to see each command; that is expected.

## Baselines (Zidoo Z9X 8K, 2026-10-26, builds up to `8678a32`)

| Run | Dropped | Slow | Notes |
| --- | --- | --- | --- |
| Home, warm | 0.0% | 0.0% | 1,866 frames |
| Home, cold disk | 0.0% | 0.2% | ready 0.6 s after the start screen |
| Deep Home tour | 0.0% | 0.0% | |
| Movies grid, warm | 0.0% | 0.7% | posters downloading during the run |
| Movies grid, cold disk | 0.0% | 0.2% | |
| TV Shows grid | 0.1% | 0.4% | |
| Tab switches | 2.0% | 5.0% | with kept screens (since removed); see [known-issues.md](known-issues.md) |
| Tab switches, rebuilt with page dip (2026-10-27) | 13.8% | 17.4% | two 50–95 ms rebuild frames per switch, at the dip's dark point |

## `perf/review-findings` against main (Zidoo Z9X 8K, 2026-11-14)

Reporting builds of `a8c95b3` (main) and the branch, installed in turn: main three times plus the
copy that had been installed for two weeks, the branch four times (`c55d432` the last two). One
discarded warm-up pass after each install, then the runs below. Dropped frames per run:

| Run | main | branch before `c55d432` | branch at `c55d432` |
| --- | --- | --- | --- |
| Movies grid, warm, 3 passes | 14–64 of 1,520 (0.9–4.0%), 8 runs | 18–33 of 1,756 (1.0–1.8%), 4 runs | 5–8 of 1,978 (0.3–0.4%), 4 runs |
| Movies grid, cold memory | 14–15 of 506 (2.7–2.9%), 4 runs | 3–4 of 585 (0.5–0.7%), 2 runs | 3–4 of 660 (0.5–0.6%), 2 runs |
| TV Shows grid | 0, 0, 12, 38 of 1,460 | 19, 22 of 1,700, while downloading posters | 1, 3 of 1,695 |
| Tab switches, 2 passes | 24–32 of 230 (9.5–12.0%), 6 runs | 18–28 (7.1–10.4%), 4 runs | 17, 32 (6.7%, 11.9%) |
| Home, warm | 0, 0, 17 of 1,490 | 0, 0 | 0 |

- **Grids:** the branch drops fewer frames. A warm Movies run on main waits for 103–124 posters
  (its 96 MiB of decoded posters does not hold the 150 the pattern visits at 384 px); on the
  branch it waits for none.
- **The frame counts differ on purpose.** On main bring-into-view cut every single row step short,
  so a step drew 19 frames; on the branch the whole glide runs and a step draws 37
  ([focus-and-scrolling.md](focus-and-scrolling.md)). The dropped counts are therefore not over
  the same animation.
- **Tab switches:** no difference outside the run-to-run swing. The worst rebuild frames were
  95–136 ms on main and 64–98 ms on the branch.
- **Not separated:** these are whole-branch numbers. No single change was A/B'd on its own.
- Main's Movies grid dropped 0.2–0.3% in October with the same code and 0.9–4.0% today; the cause
  is not known, so compare only runs from the same session.

Before the October work, Home stutters were RenderThread-bound: the main thread waited 47–70 ms in
`nSyncAndDrawFrame` while `cmd` took 60–80 ms. What changed since is in
[performance-decisions.md](performance-decisions.md).
