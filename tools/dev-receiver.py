#!/usr/bin/env python3
"""Receives crash reports (/crash) and analytics (/events) from Dooplex development builds.

Start it with `mise run receiver`. Crash reports land in crashes/, one file each. Analytics are JSON
lines appended to analytics/YYYY-MM-DD.jsonl; this prints a short digest of the interesting ones.
"""
import datetime
import http.server
import json
import pathlib
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8797
LIMIT = 4 * 1024 * 1024
ROOT = pathlib.Path(__file__).resolve().parent.parent
CRASHES = ROOT / "crashes"
ANALYTICS = ROOT / "analytics"
SLOW_MS = 50


def digest(event):
    kind = event.get("event")
    where = event.get("screen") or "-"
    if kind == "session":
        return f"session {event.get('device')} render {event.get('render')} display {event.get('display')}"
    if kind == "first-frame":
        return f"first frame after {event.get('msSinceStart')} ms"
    if kind == "slow-frame" and event.get("ms", 0) >= SLOW_MS:
        parts = " ".join(f"{k} {event[k]}" for k in ("anim", "draw", "sync", "cmd", "swap", "delay") if event.get(k, 0) >= 5)
        return f"{event['ms']:6.1f} ms frame on {where} after {event.get('cause') or '?'}: {parts}"
    if kind == "stats":
        values = event.get("values", {})
        return "stats " + ", ".join(f"{k} n={v['n']} mean={v['mean']} max={v['max']}" for k, v in values.items())
    if kind == "memory":
        return f"memory java {event.get('javaMb')} MB, native {event.get('nativeMb')} MB, pss {event.get('pssMb')} MB"
    return None


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if self.path not in ("/crash", "/events") or not 0 < length <= LIMIT:
            self.send_error(400)
            return
        body = self.rfile.read(length).decode("utf-8", "replace")
        if self.path == "/crash":
            self.crash(body)
        else:
            self.events(body)
        self.send_response(204)
        self.end_headers()

    def crash(self, report):
        CRASHES.mkdir(exist_ok=True)
        path = CRASHES / (datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".txt")
        path.write_text(report)
        summary = report.split("\nRecent log:", 1)[0].rstrip()
        print(f"\n=== crash from {self.client_address[0]} · {path} ===\n{summary}", flush=True)

    def events(self, body):
        ANALYTICS.mkdir(exist_ok=True)
        with open(ANALYTICS / f"{datetime.date.today()}.jsonl", "a") as out:
            out.write(body.rstrip("\n") + "\n")
        for line in body.splitlines():
            try:
                text = digest(json.loads(line))
            except (ValueError, KeyError, TypeError):
                continue
            if text:
                print(f"{datetime.datetime.now():%H:%M:%S} {text}", flush=True)

    def log_message(self, *args):
        pass


print(f"Receiving on port {PORT}: crashes to {CRASHES}, analytics to {ANALYTICS}", flush=True)
http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
