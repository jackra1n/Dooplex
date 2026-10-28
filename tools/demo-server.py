#!/usr/bin/env python3
"""A fake Plex server, plex.tv and Discover in one, serving demo/library.json for screenshots. Any
sign-in code is approved at once, and the account has this one server.

    python3 tools/demo-server.py [port]    # default 8798

Build with DOOPLEX_DEMO_URL=http://<this PC as the device sees it>:<port> to point the app here.
"""
import hashlib
import json
import pathlib
import sys
import urllib.parse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parent.parent
LIBRARY = json.loads((ROOT / "demo" / "library.json").read_text())
ART = ROOT / "demo" / "art"
NOW = LIBRARY["now"]
TOKEN = "demo-token"
SERVER_ID = "demo-library"
WATCHLIST = ["sprite-fright", "his-girl-friday", "coffee-run", "hubblecast", "the-general"]

items = {}
images = {}


def guid(kind, name):
    return f"plex://{kind}/{hashlib.md5(name.encode()).hexdigest()[:24]}"


def tags(names):
    return [{"tag": name} for name in names or []]


def media(key, year, minutes):
    if year >= 2015:
        video = {"codec": "hevc", "width": 3840, "height": 2160, "colorTrc": "smpte2084", "DOVIPresent": True,
                 "DOVIProfile": 8, "DOVIBLCompatID": 1, "displayTitle": "4K DoVi/HDR10 (HEVC Main 10)"}
        audio = {"codec": "truehd", "channels": 8, "audioChannelLayout": "7.1",
                 "displayTitle": "English (TRUEHD 7.1)", "extendedDisplayTitle": "English (Dolby TrueHD Atmos 7.1)"}
        resolution = "4k"
    elif year >= 2006:
        video = {"codec": "h264", "width": 1920, "height": 1080, "displayTitle": "1080p (H.264)"}
        audio = {"codec": "dca", "profile": "ma", "channels": 6, "audioChannelLayout": "5.1",
                 "displayTitle": "English (DTS-HD MA 5.1)"}
        resolution = "1080"
    else:
        video = {"codec": "h264", "width": 1440, "height": 1080, "displayTitle": "1080p (H.264)"}
        audio = {"codec": "flac", "channels": 1, "displayTitle": "English (FLAC Mono)"}
        resolution = "1080"
    duration = int(minutes * 60_000)
    return [{
        "id": int(key), "duration": duration, "videoResolution": resolution, "container": "mkv",
        "videoCodec": video["codec"], "audioCodec": audio["codec"], "audioChannels": audio["channels"],
        "Part": [{
            "id": int(key), "key": f"/library/parts/{key}/1/file.mkv", "duration": duration, "container": "mkv",
            "file": f"/media/{key}.mkv", "size": duration * 2_000,
            "Stream": [{"streamType": 1, "default": True, **video},
                       {"streamType": 2, "default": True, "selected": True, "language": "English", **audio}],
        }],
    }]


def released(entry):
    return entry.get("originallyAvailableAt") or entry.get("date") or f"{entry.get('year', 2000)}-01-01"


def common(entry, key, kind, section):
    added = LIBRARY["added"].index(entry["id"]) if entry["id"] in LIBRARY["added"] else len(LIBRARY["added"])
    thumb, art = f"/library/metadata/{key}/thumb/1", f"/library/metadata/{key}/art/1"
    images[thumb] = f"{entry['id']}-poster.jpg"
    images[art] = f"{entry['id']}-art.jpg"
    return {
        "ratingKey": key, "key": f"/library/metadata/{key}", "guid": guid(kind, entry["id"]), "type": kind,
        "title": entry["title"], "titleSort": entry.get("titleSort", entry["title"]), "year": entry.get("year"),
        "originallyAvailableAt": released(entry), "summary": entry.get("summary", ""),
        "tagline": entry.get("tagline", ""), "studio": entry.get("studio", ""), "thumb": thumb, "art": art,
        "librarySectionID": section, "addedAt": NOW - added * 86_400,
        "Genre": tags(entry.get("genres")), "Director": tags(entry.get("directors")),
        "Writer": tags(entry.get("writers")),
        "Role": [{"tag": person["name"], "role": person.get("role", "")} for person in entry.get("cast", [])],
    }


def build():
    counter = iter(range(100, 100_000))
    for entry in LIBRARY["movies"]:
        key = str(next(counter))
        item = common(entry, key, "movie", "1")
        item.update(duration=int(entry["minutes"] * 60_000), Media=media(key, entry["year"], entry["minutes"]))
        items[entry["id"]] = item
    for entry in LIBRARY["shows"]:
        key = str(next(counter))
        show = common(entry, key, "show", "2")
        show["key"] = f"/library/metadata/{key}/children"
        items[entry["id"]] = show
        for season_entry in entry["seasons"]:
            season_key = str(next(counter))
            number = season_entry["index"]
            season = {
                "ratingKey": season_key, "key": f"/library/metadata/{season_key}/children", "type": "season",
                "title": f"Season {number}", "index": number, "parentRatingKey": key, "parentTitle": show["title"],
                "parentThumb": show["thumb"], "thumb": show["thumb"], "art": show["art"], "librarySectionID": "2",
                "addedAt": show["addedAt"],
            }
            items[f"{entry['id']}/{number}"] = season
            for episode_entry in season_entry["episodes"]:
                episode_key = str(next(counter))
                name = f"{entry['id']}/{number}/{episode_entry['index']}"
                thumb = f"/library/metadata/{episode_key}/thumb/1"
                images[thumb] = f"{entry['id']}-s{number}e{episode_entry['index']}.jpg"
                date = released(episode_entry)
                items[name] = {
                    "ratingKey": episode_key, "key": f"/library/metadata/{episode_key}", "type": "episode",
                    "guid": guid("episode", name), "title": episode_entry["title"],
                    "index": episode_entry["index"], "parentIndex": number, "year": int(date[:4]),
                    "originallyAvailableAt": date, "summary": episode_entry.get("summary", ""),
                    "duration": int(episode_entry["minutes"] * 60_000),
                    "parentRatingKey": season_key, "parentTitle": season["title"], "parentThumb": show["thumb"],
                    "grandparentRatingKey": key, "grandparentTitle": show["title"],
                    "grandparentThumb": show["thumb"], "grandparentArt": show["art"], "thumb": thumb,
                    "librarySectionID": "2",
                    "addedAt": show["addedAt"] - (len(season_entry["episodes"]) - episode_entry["index"]) * 3_600,
                    "Media": media(episode_key, int(date[:4]), episode_entry["minutes"]),
                }
    for rank, name in enumerate(LIBRARY["watched"]):
        items[name].update(viewCount=1, lastViewedAt=NOW - (rank + 10) * 86_400)
    for rank, entry in enumerate(LIBRARY["continueWatching"]):
        item = items[entry["item"]]
        item.update(viewOffset=int(item["duration"] * entry["progress"]), lastViewedAt=NOW - rank * 3_600)
    for name, show in items.items():
        if show["type"] in ("show", "season"):
            episodes = [items[e] for e in items if e.startswith(name + "/") and items[e]["type"] == "episode"]
            show.update(leafCount=len(episodes), viewedLeafCount=sum(1 for e in episodes if e.get("viewCount")))
            if show["type"] == "show":
                show["childCount"] = len({e["parentIndex"] for e in episodes})


def by_key(key):
    return next((item for item in items.values() if item["ratingKey"] == key), None)


def of_type(kind):
    return [item for item in items.values() if item["type"] == kind]


def children(parent):
    return [item for item in items.values() if item.get("parentRatingKey") == parent["ratingKey"]]


def unwatched(item):
    return item.get("viewedLeafCount", 0) < item.get("leafCount", 0) if item["type"] == "show" else not item.get("viewCount")


def next_episode(show):
    episodes = [item for item in of_type("episode") if item["grandparentRatingKey"] == show["ratingKey"]]
    return next((e for e in episodes if e.get("viewOffset")), None) or next((e for e in episodes if unwatched(e)), None)


def sort_key(field):
    if field == "titleSort":
        return lambda item: item["titleSort"].lower()
    return lambda item: item.get(field) or 0


def container(metadata, **extra):
    return {"MediaContainer": {"size": len(metadata), "Metadata": metadata, **extra}}


def hubs(query):
    count = int(query.get("count", ["24"])[0])
    result = []
    for hub in LIBRARY["hubs"]:
        if hub.get("recent"):
            entries = sorted(of_type(hub["recent"]), key=sort_key("addedAt"), reverse=True)
        else:
            order = LIBRARY["collectionOrder"].get(hub["collection"])
            members = [m["id"] for m in LIBRARY["movies"] if hub["collection"] in m.get("collections", [])]
            entries = [items[name] for name in (order or members)]
        result.append({"hubIdentifier": hub["hubIdentifier"], "title": hub["title"], "type": hub["type"],
                       "size": min(count, len(entries)), "Metadata": entries[:count]})
    return {"MediaContainer": {"size": len(result), "Hub": result}}


def continue_watching():
    entries = [items[entry["item"]] for entry in LIBRARY["continueWatching"]]
    hub = {"hubIdentifier": "home.continue", "title": "Continue Watching", "type": "mixed",
           "size": len(entries), "Metadata": entries}
    return {"MediaContainer": {"size": 1, "Hub": [hub]}}


def search(query):
    text = query.get("query", [""])[0].lower()
    result = []
    for kind, title in (("movie", "Movies"), ("show", "Shows"), ("episode", "Episodes")):
        found = [item for item in of_type(kind) if text and text in item["title"].lower()]
        if found:
            result.append({"hubIdentifier": kind, "title": title, "type": kind, "size": len(found), "Metadata": found})
    return {"MediaContainer": {"size": len(result), "Hub": result}}


def section_items(section, query):
    kind = "movie" if section == "1" else "show"
    entries = of_type(kind)
    if query.get("unwatched") == ["1"]:
        entries = [item for item in entries if unwatched(item)]
    field, _, direction = query.get("sort", ["titleSort:asc"])[0].partition(":")
    return sorted(entries, key=sort_key(field), reverse=direction == "desc")


def first_characters(entries):
    buckets = []
    for item in entries:
        letter = item["titleSort"][:1].upper()
        letter = letter if letter.isalpha() else "#"
        if buckets and buckets[-1]["key"] == letter:
            buckets[-1]["size"] += 1
        else:
            buckets.append({"key": letter, "title": letter, "size": 1})
    return {"MediaContainer": {"size": len(buckets), "Directory": buckets}}


def watchlist():
    entries = [{key: items[name][key] for key in ("guid", "type", "title", "year", "summary")} | {"thumb": ""}
               for name in WATCHLIST]
    return container(entries, totalSize=len(entries), offset=0)


def answer(method, path, query, host):
    if method == "POST" and path == "/api/v2/pins":
        expires = datetime.now(timezone.utc) + timedelta(minutes=15)
        return {"id": 1, "code": "DEMO", "expiresAt": expires.strftime("%Y-%m-%dT%H:%M:%SZ"), "qr": ""}
    if path.startswith("/api/v2/pins/"):
        return {"id": 1, "code": "DEMO", "authToken": TOKEN}
    if path == "/api/v2/user":
        return {"friendlyName": "Demo"}
    if path == "/api/v2/resources":
        return [{"name": LIBRARY["server"], "provides": "server", "clientIdentifier": SERVER_ID,
                 "accessToken": TOKEN, "connections": [{"uri": f"http://{host}", "local": True}]}]
    if path == "/identity":
        return {"MediaContainer": {"machineIdentifier": SERVER_ID}}
    if path == "/library/sections":
        return {"MediaContainer": {"size": 2, "Directory": [
            {"key": "1", "type": "movie", "title": "Movies"}, {"key": "2", "type": "show", "title": "TV Shows"}]}}
    if path == "/library/sections/watchlist/all":
        return watchlist()
    parts = path.strip("/").split("/")
    if parts[:2] == ["library", "sections"] and len(parts) == 4:
        entries = section_items(parts[2], query)
        if parts[3] == "firstCharacter":
            return first_characters(entries)
        start = int(query.get("X-Plex-Container-Start", ["0"])[0])
        size = int(query.get("X-Plex-Container-Size", ["50"])[0])
        return container(entries[start:start + size], totalSize=len(entries), offset=start)
    if parts[:2] == ["library", "metadata"] and len(parts) in (3, 4):
        item = by_key(parts[2])
        if item is None:
            return None
        if len(parts) == 4:
            return container(children(item), totalSize=len(children(item)), offset=0)
        if item["type"] == "show" and query.get("includeOnDeck") == ["1"]:
            upcoming = next_episode(item)
            return container([item | ({"OnDeck": {"Metadata": [upcoming]}} if upcoming else {})])
        return container([item])
    if path == "/library/all":
        wanted = set(",".join(query.get("guid", [])).split(","))
        return container([item for item in items.values() if item.get("guid") in wanted])
    if path == "/hubs":
        return hubs(query)
    if path == "/hubs/continueWatching":
        return continue_watching()
    if path == "/hubs/search":
        return search(query)
    if path.startswith("/actions/") or path in ("/:/scrobble", "/:/unscrobble", "/:/timeline"):
        return {}
    return None


class Handler(BaseHTTPRequestHandler):
    def handle_request(self, method):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query)
        if url.path == "/photo/:/transcode":
            file = images.get(query.get("url", [""])[0])
            if file and (ART / file).exists():
                return self.reply(200, (ART / file).read_bytes(), "image/jpeg")
            return self.reply(404, b"", "text/plain")
        body = answer(method, url.path, query, self.headers.get("Host", ""))
        if body is None:
            return self.reply(404, b"", "text/plain")
        self.reply(200, json.dumps(body).encode(), "application/json")

    def reply(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def do_PUT(self):
        self.handle_request("PUT")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8798
    build()
    print(f"Demo Plex server on port {port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
