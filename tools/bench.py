#!/usr/bin/env python3
"""Drives a fixed key pattern on Dooplex through the Zidoo's remote-control API and reports how
smoothly the development build drew it, from its analytics.

    python3 tools/bench.py [--pattern home|deep|movie|show|switch] [--cold memory|disk] [--passes N]
                           [--flags heromask=0,wash=1] [--label name]

Needs `mise run receiver` and the app open on any screen. Each run starts from the same state: the
app rebuilds Home (home, deep) or reopens the library (movie, show), after dropping its poster caches
when --cold asks for it, and every further pass returns focus to the first item. "deep" visits new
rows and cards on Home, for cold runs; "switch" confirms the Movies, Home, TV Shows and Home tabs. Switches go to analytics/flags and commands to
analytics/commands; the receiver hands both to the app. Results also append to analytics/bench.log.
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
FRAME_MS = 1000 / 60
STEP = 0.6
HELD = 0.08


def keys(name, count, pause=STEP):
    return [(name, pause)] * count


PATTERNS = {
    "home": (keys("Down", 1) + keys("Right", 5) + keys("Left", 5)) * 2 + keys("Up", 2),
    "deep": (keys("Down", 1) + keys("Right", 10)) * 8,
    "grid": keys("Down", 5) + keys("Down", 20, HELD) + keys("Up", 20, HELD) + keys("Up", 5)
    + keys("Right", 3) + keys("Left", 3),
}


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


def wait_for(match, since_ms, timeout_s):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        found = [e for e in events(since_ms) if match(e)]
        if found:
            return found[-1]
        time.sleep(0.5)
    return None


def command(words, ready, timeout_s=30):
    """Sends commands through the receiver and returns (sent, ready) app events, once `ready` is."""
    start = now_ms() - 5_000
    (ANALYTICS / "commands").write_text(" ".join(words))
    sent = wait_for(lambda e: e.get("event") == "command" and e.get("name") == words[-1], start, timeout_s)
    if not sent:
        raise SystemExit("The app did not pick up the command; is it open and `mise run receiver` running?")
    done = wait_for(lambda e: e.get("event") == "ready" and e.get("what") == ready and e["t"] >= sent["t"],
                    sent["t"], timeout_s)
    if not done:
        raise SystemExit(f"The app took over {timeout_s} s to become ready after {' '.join(words)}.")
    time.sleep(1.0)
    return sent, done


def drive(pattern):
    begin = now_ms()
    sent = 0
    for key, pause in pattern:
        started = time.time()
        send(key)
        sent += 1
        time.sleep(max(0, pause - (time.time() - started)))
    time.sleep(0.6)
    return begin, now_ms(), sent


def stat(window, name):
    parts = [e["values"][name] for e in window if e.get("event") == "stats" and name in e.get("values", {})]
    count = sum(p["n"] for p in parts)
    if not count:
        return f"{name.split('.')[1]} 0"
    mean = sum(p["n"] * p["mean"] for p in parts) / count
    return f"{name.split('.')[1]} {count} (mean {mean:.0f} ms, max {max(p['max'] for p in parts):.0f} ms)"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pattern", choices=["home", "deep", "movie", "show", "switch"], default="home")
    parser.add_argument("--cold", choices=["memory", "disk"])
    parser.add_argument("--passes", type=int)
    parser.add_argument("--flags", default="")
    parser.add_argument("--label", default="")
    args = parser.parse_args()
    passes = args.passes or (1 if args.cold or args.pattern == "deep" else 3)
    screen = args.pattern if args.pattern in ("movie", "show") else "home"
    pattern = PATTERNS.get("grid" if screen != "home" else args.pattern)
    ANALYTICS.mkdir(exist_ok=True)

    send("Right")  # wakes a box that went idle; the setup below resets focus anyway
    (ANALYTICS / "flags").write_text(args.flags)
    if not wait_for(lambda e: e.get("event") == "frames" and e.get("flags", "") == args.flags, now_ms(), 15):
        raise SystemExit("The app did not report the requested flags; is it open and `mise run receiver` running?")
    setup = ([f"clear-{args.cold}"] if args.cold else []) + ["home" if screen == "home" else f"open:{screen}"]
    sent, done = command(setup, screen)
    opened = (done["t"] - sent["t"]) / 1000

    windows, steps, switches = [], 0, []
    for index in range(passes):
        if args.pattern == "switch":
            begin = now_ms()
            for tab in ("movie", "home", "show", "home"):
                sent, done = command([f"tab:{tab}"], tab)
                switches.append(done["t"] - sent["t"])
            windows.append((begin, now_ms()))
            continue
        if index:
            command(["focus"], screen)
        begin, end, count = drive(pattern)
        windows.append((begin, end))
        steps += count
    time.sleep(9)

    found = events(windows[0][0])
    inside = [e for e in found if any(b <= e["t"] <= e_ for b, e_ in windows)]
    flushed = [e for e in found if any(b <= e["t"] <= e_ + 4_500 for b, e_ in windows)]
    frames = sum(e["count"] for e in flushed if e.get("event") == "frames")
    slow_total = sum(e["slow"] for e in flushed if e.get("event") == "frames")
    dropped = sum(e.get("dropped", 0) for e in flushed if e.get("event") == "frames")
    slow = [e for e in inside if e.get("event") == "slow-frame"]
    pressed = sum(1 for e in inside if e.get("event") == "key")
    times = sorted(e["ms"] for e in slow)
    over = sum(ms - FRAME_MS for ms in times)

    def median(name):
        return round(statistics.median([e.get(name, 0) for e in slow]), 1) if slow else 0

    def pct(p):
        return times[min(len(times) - 1, int(len(times) * p))] if times else 0

    label = args.label or " ".join(filter(None, [args.pattern, args.cold and f"cold={args.cold}", args.flags]))
    lines = [
        f"{time.strftime('%H:%M:%S')} {label}: ready {opened:.1f} s; {frames} frames, {dropped} dropped "
        f"({100 * dropped / max(frames + dropped, 1):.1f}%), {slow_total} slow ({100 * slow_total / max(frames, 1):.1f}%), "
        f"{over:.0f} ms over budget, keys {pressed}/{steps}",
        f"  slow frames p50 {pct(.5):.1f} p90 {pct(.9):.1f} max {pct(1):.1f} ms; "
        f"median anim {median('anim')} draw {median('draw')} sync {median('sync')} cmd {median('cmd')} swap {median('swap')}",
        f"  posters: {stat(flushed, 'poster.wait.ms')}; {stat(flushed, 'poster.network.ms')}; {stat(flushed, 'poster.disk.ms')}",
    ]
    for e in sorted(slow, key=lambda e: -e["ms"])[:3]:
        parts = " ".join(f"{k} {e[k]}" for k in ("delay", "input", "anim", "layout", "draw", "sync", "cmd", "swap") if e.get(k, 0) >= 3)
        lines.append(f"  {e['ms']:6.1f} ms after {e.get('cause') or '?'}: {parts}")
    if switches:
        lines.insert(1, f"  switches: {len(switches)}, ready after median {statistics.median(switches):.0f} ms, max {max(switches)} ms")
    print("\n".join(lines), flush=True)
    with open(ANALYTICS / "bench.log", "a") as log:
        log.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
