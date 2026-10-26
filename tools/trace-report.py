#!/usr/bin/env python3
"""Explains slow frames with a CPU trace from a Dooplex development build.

    python3 tools/trace-report.py [traces/<file>.trace] [--frames N] [--top N]

Reads ART's sampling method trace (header text, then binary records), rebuilds each thread's call
stack over time, and reports what the main thread spent its time on: overall, and inside every slow
frame that analytics recorded while the trace ran (matched by the frame's vsync timestamp, which is
on the trace's monotonic clock). R8-renamed library names are mapped back with the release build's
mapping.txt. Native work (RenderThread, GPU) is not in an ART trace; analytics' sync/cmd/swap cover it.
"""
import argparse
import collections
import glob
import json
import pathlib
import re
import struct

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAGIC = 0x574F4C53


def parse_trace(path):
    data = path.read_bytes()
    end = data.index(b"*end\n") + len(b"*end\n")
    header, binary = data[:end].decode("utf-8", "replace"), data[end:]
    threads, methods, section, props = {}, {}, None, {}
    for line in header.splitlines():
        if line.startswith("*"):
            section = line[1:]
            continue
        if section == "version" and "=" in line:
            key, value = line.split("=", 1)
            props[key] = value
        elif section == "threads" and "\t" in line:
            tid, name = line.split("\t", 1)
            threads[int(tid)] = name
        elif section == "methods" and line.startswith("0x"):
            fields = line.split("\t")
            methods[int(fields[0], 16)] = (fields[1], fields[2] if len(fields) > 2 else "?")
    magic, version, offset, start = struct.unpack_from("<IHHQ", binary, 0)
    if magic != MAGIC:
        raise SystemExit(f"{path} is not an ART method trace")
    record = struct.unpack_from("<H", binary, 16)[0] if version >= 3 else 10
    dual = record == 14
    records = []
    for pos in range(offset, len(binary) - record + 1, record):
        if dual:
            tid, value, _, wall = struct.unpack_from("<HIII", binary, pos)
        else:
            tid, value, wall = struct.unpack_from("<HII", binary, pos)
        records.append((tid, value & ~3, value & 3, wall))
    return threads, methods, start, records, props


def load_mapping():
    path = ROOT / "app/build/outputs/mapping/release/mapping.txt"
    classes, members = {}, collections.defaultdict(set)
    if not path.exists():
        return classes, members
    current = None
    for line in path.read_text().splitlines():
        if line.startswith("#"):
            continue
        if not line.startswith(" ") and line.endswith(":") and " -> " in line:
            original, obfuscated = line[:-1].split(" -> ")
            classes[obfuscated] = original
            current = (original, obfuscated)
        elif current and " -> " in line and "(" in line:
            signature, obfuscated = line.strip().rsplit(" -> ", 1)
            name = signature.split("(")[0].split()[-1].split(":")[-1]
            members[(current[1], obfuscated)].add(name)
    return classes, members


def namer(methods, mapping):
    classes, members = mapping
    cache = {}

    def name(mid):
        if mid not in cache:
            klass, method = methods.get(mid, ("?", f"0x{mid:x}"))
            klass = klass.strip("L;").replace("/", ".")
            original = classes.get(klass, klass)
            names = members.get((klass, method))
            method = "/".join(sorted(names)) if names else method
            cache[mid] = f"{original.rsplit('.', 1)[-1]}.{method}"
        return cache[mid]

    return name


def slices(records, tid):
    """(start_us, end_us, stack) for every stretch the thread spent under one stack."""
    stack, last = [], None
    for record_tid, mid, action, wall in records:
        if record_tid != tid:
            continue
        if last is not None and stack and wall > last:
            yield last, wall, tuple(stack)
        if action == 0:
            stack.append(mid)
        elif mid in stack:
            while stack and stack.pop() != mid:
                pass
        last = wall


def slow_frames(start_us, duration_us):
    frames = []
    for path in sorted(glob.glob(str(ROOT / "analytics/*.jsonl"))):
        for line in open(path):
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if event.get("event") == "slow-frame" and "vsyncUs" in event:
                begin = event["vsyncUs"] - start_us
                if 0 <= begin <= duration_us:
                    frames.append((begin, begin + event["ms"] * 1000, event))
    return frames


def report(title, spans, name, top):
    inclusive, exclusive = collections.Counter(), collections.Counter()
    for duration, stack in spans:
        exclusive[stack[-1]] += duration
        for mid in set(stack):
            inclusive[mid] += duration
    print(f"\n{title}")
    print("  self      in-ms   function")
    for mid, spent in inclusive.most_common(top):
        print(f"  {exclusive[mid] / 1000:6.1f}  {spent / 1000:8.1f}   {name(mid)}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", nargs="?")
    parser.add_argument("--frames", type=int, default=6)
    parser.add_argument("--top", type=int, default=25)
    args = parser.parse_args()
    path = pathlib.Path(args.trace) if args.trace else max((ROOT / "traces").glob("*.trace"), key=lambda p: p.stat().st_mtime)
    threads, methods, start, records, props = parse_trace(path)
    name = namer(methods, load_mapping())
    duration = max((wall for *_, wall in records), default=0)
    main_tid = next((tid for tid, thread in threads.items() if thread == "main"), None)
    print(f"{path.name}: {duration / 1e6:.1f} s, {len(records)} records, {len(threads)} threads, clock={props.get('clock')}")
    main_slices = list(slices(records, main_tid))
    report("Main thread, whole trace", [(end - begin, stack) for begin, end, stack in main_slices], name, args.top)
    frames = slow_frames(start, duration)
    if not frames:
        print("\nNo slow frames from analytics fell inside this trace.")
        return
    print(f"\n{len(frames)} slow frames during the trace")
    for begin, end, event in sorted(frames, key=lambda f: -f[2]["ms"])[:args.frames]:
        spans = [(min(e, end) - max(b, begin), stack) for b, e, stack in main_slices if b < end and e > begin]
        parts = " ".join(f"{k} {event[k]}" for k in ("anim", "draw", "sync", "cmd", "swap", "delay") if event.get(k, 0) >= 3)
        report(f"{event['ms']} ms frame on {event.get('screen')} after {event.get('cause') or '?'} ({parts})", spans, name, 12)


if __name__ == "__main__":
    main()
