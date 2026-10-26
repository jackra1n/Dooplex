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
| Tab switches | 2.0% | 5.0% | all from the first Movies visit; see [known-issues.md](known-issues.md) |

Before the October work, Home stutters were RenderThread-bound: the main thread waited 47–70 ms in
`nSyncAndDrawFrame` while `cmd` took 60–80 ms. What changed since is in
[performance-decisions.md](performance-decisions.md).
