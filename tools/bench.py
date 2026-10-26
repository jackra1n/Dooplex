#!/usr/bin/env python3
"""Drives a fixed key pattern on Dooplex's Home through the Zidoo's remote-control API and reports
how smoothly the development build drew it, from its analytics.

    python3 tools/bench.py [--flags heromask=0,wash=1] [--seconds 40] [--label name]

Needs `mise run receiver` and the app open on Home with focus on the hero; the pattern returns there.
The switches go to analytics/flags, and the run starts only once the app reports them, so every frame
in the window was drawn under the requested experiment. Results also append to analytics/bench.log.
"""
import argparse
import glob
import json
import os
import pathlib
import statistics
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANALYTICS = ROOT / "analytics"
ZIDOO = os.environ.get("ZIDOO_HOST", "<zidoo-ip>")
PATTERN = ["Down"] + ["Right"] * 5 + ["Left"] * 5 + ["Down"] + ["Right"] * 5 + ["Left"] * 5 + ["Up", "Up"]
STEP_S = 0.6
FRAME_MS = 1000 / 60


def send(key):
    url = f"http://{ZIDOO}:9529/ZidooControlCenter/RemoteControl/sendkey?key=Key.{key}"
    urllib.request.urlopen(url, timeout=3).read()


def events(since_ms):
    found = []
    for path in sorted(glob.glob(str(ANALYTICS / "*.jsonl"))):
        for line in open(path):
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if event.get("t", 0) >= since_ms:
                found.append(event)
    return found


def now_ms():
    return int(time.time() * 1000)


def wait_for_flags(wanted, timeout_s=15):
    start = now_ms()
    while now_ms() - start < timeout_s * 1000:
        reports = [e for e in events(start) if e.get("event") == "frames"]
        if reports and reports[-1].get("flags", "") == wanted:
            return True
        time.sleep(1)
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--flags", default="")
    parser.add_argument("--seconds", type=int, default=40)
    parser.add_argument("--label", default="")
    args = parser.parse_args()
    ANALYTICS.mkdir(exist_ok=True)
    (ANALYTICS / "flags").write_text(args.flags)
    if not wait_for_flags(args.flags):
        raise SystemExit("The app did not report the requested flags; is it open and `mise run receiver` running?")
    begin = now_ms()
    steps = 0
    while now_ms() - begin < args.seconds * 1000:
        send(PATTERN[steps % len(PATTERN)])
        steps += 1
        time.sleep(STEP_S)
    end = now_ms()
    time.sleep(9)
    window = [e for e in events(begin) if e.get("t", 0) <= end + 4_500]
    frames = sum(e["count"] for e in window if e.get("event") == "frames")
    slow_total = sum(e["slow"] for e in window if e.get("event") == "frames")
    slow = [e for e in window if e.get("event") == "slow-frame" and e["t"] <= end]
    keys = sum(1 for e in window if e.get("event") == "key" and e["t"] <= end)
    times = sorted(e["ms"] for e in slow)
    over = sum(ms - FRAME_MS for ms in times)

    def median(name):
        return statistics.median([e.get(name, 0) for e in slow]) if slow else 0

    def pct(p):
        return times[min(len(times) - 1, int(len(times) * p))] if times else 0

    label = args.label or args.flags or "defaults"
    lines = [
        f"{time.strftime('%H:%M:%S')} {label}: {frames} frames, {slow_total} slow ({100 * slow_total / max(frames, 1):.1f}%), "
        f"{over:.0f} ms over budget, keys {keys}/{steps}",
        f"  slow frames p50 {pct(.5):.1f} p90 {pct(.9):.1f} max {pct(1):.1f} ms; "
        f"median anim {median('anim')} draw {median('draw')} sync {median('sync')} cmd {median('cmd')} swap {median('swap')}",
    ]
    print("\n".join(lines))
    with open(ANALYTICS / "bench.log", "a") as log:
        log.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
