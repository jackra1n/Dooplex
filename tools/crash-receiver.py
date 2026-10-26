#!/usr/bin/env python3
"""Receives crash reports from Dooplex development builds. Start it with `mise run crashes`."""
import datetime
import http.server
import pathlib
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8797
LIMIT = 4 * 1024 * 1024
OUT = pathlib.Path(__file__).resolve().parent.parent / "crashes"


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if self.path != "/crash" or not 0 < length <= LIMIT:
            self.send_error(400)
            return
        report = self.rfile.read(length).decode("utf-8", "replace")
        OUT.mkdir(exist_ok=True)
        path = OUT / (datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".txt")
        path.write_text(report)
        summary = report.split("\nRecent log:", 1)[0].rstrip()
        print(f"\n=== {self.client_address[0]} · {path} ===\n{summary}", flush=True)
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


print(f"Waiting for crash reports on port {PORT}; full reports go to {OUT}", flush=True)
http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
