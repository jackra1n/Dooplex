#!/usr/bin/env python3
"""Downloads the demo library's artwork, checks each file's pinned sha256, crops it to the size the
demo server hands out and writes demo/CREDITS.md. Files already in demo/art/ are kept.

    python3 tools/demo-art.py
"""
import hashlib
import io
import json
import pathlib
import time
import urllib.error
import urllib.parse
import urllib.request

from PIL import Image, ImageOps

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEMO = ROOT / "demo"
ART = DEMO / "art"
LOGO = (800, 310)
SIZES = {"poster": (600, 900), "art": (1920, 1080), "still": (960, 540)}
CENTERING = {"center": (0.5, 0.5), "left": (0, 0.5), "right": (1, 0.5), "top": (0.5, 0), "bottom": (0.5, 1)}
AGENT = "Dooplex demo-art/1.0 (https://github.com/jackra1n/Dooplex)"


def images(library):
    for item in library["movies"] + library["shows"]:
        for shape in ("poster", "art", "logo"):
            if item.get(shape):
                yield f"{item['id']}-{shape}", shape, item[shape]
        for season in item.get("seasons", []):
            for episode in season["episodes"]:
                if episode.get("thumb"):
                    yield f"{item['id']}-s{season['index']}e{episode['index']}", "still", episode["thumb"]


def fetch(url, sha256):
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})
    for attempt in range(5):
        try:
            data = urllib.request.urlopen(request, timeout=120).read()
            break
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 4:
                raise
            time.sleep(int(error.headers.get("Retry-After") or 10))
    if hashlib.sha256(data).hexdigest() != sha256:
        raise ValueError(f"{url} does not match its pinned sha256")
    return data


def derive(job, assets):
    name, shape, spec = job
    target = ART / f"{name}.{'png' if shape == 'logo' else 'jpg'}"
    if target.exists():
        return
    source = assets[spec["asset"]]
    image = Image.open(io.BytesIO(fetch(source["url"], source["sha256"])))
    if shape == "logo":
        image = image.convert("RGBA")
        image = image.crop(image.getchannel("A").getbbox())
        image.thumbnail(LOGO)
        image.save(target)
        print(f"Wrote {target.relative_to(ROOT)}")
        return
    if image.mode in ("RGBA", "LA", "P"):
        image = image.convert("RGBA")
        ground = Image.new("RGBA", image.size, "black")
        image = Image.alpha_composite(ground, image)
    image = ImageOps.fit(image.convert("RGB"), SIZES[shape], Image.LANCZOS, centering=CENTERING[spec["anchor"]])
    image.save(target, quality=85)
    print(f"Wrote {target.relative_to(ROOT)}")


def credits(library):
    rows = [
        "# Demo library credits",
        "",
        "The demo library (`library.json`) behind the screenshots is adapted from",
        "[plx-native](https://github.com/GLinnik21/plx-native)'s, under GPL-3.0. Every image comes from an",
        "openly licensed or public-domain work, cropped and resized by `tools/demo-art.py` and otherwise",
        "unchanged. Written by that script; do not edit by hand.",
        "",
        "| Image | Source | Licence | Attribution |",
        "| --- | --- | --- | --- |",
    ]
    for name, asset in library["assets"].items():
        file = urllib.parse.unquote(asset["url"].rsplit("/", 1)[-1])
        page = asset.get("source_page") or asset["url"]
        attribution = asset["attribution"].replace("|", "\\|")
        rows.append(f"| {name} | [{file}]({page}) | {asset['licence']} | {attribution} |")
    (DEMO / "CREDITS.md").write_text("\n".join(rows) + "\n")


def main():
    library = json.loads((DEMO / "library.json").read_text())
    ART.mkdir(exist_ok=True)
    for job in images(library):
        derive(job, library["assets"])
    credits(library)


if __name__ == "__main__":
    main()
