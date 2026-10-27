# Performance decisions

The Zidoo's GPU and slow CPU cores make every first-time draw expensive, so Dooplex avoids doing
work inside a frame that could happen before it: upload textures early, build screens once, render
backgrounds off the main thread. Numbers are from the box; how they were taken is in
[measuring-performance.md](measuring-performance.md).

## Posters are regular bitmaps uploaded ahead of time, not hardware bitmaps

**Decision (2026-10-26):** `PosterRepository.toGpu` calls `Bitmap.prepareToDraw()` on the decoded
bitmap, which lets the RenderThread upload the texture between frames.

**Why:** a `HARDWARE` bitmap is already in graphics memory, but the GPU still imports it the first
time a frame draws it, inside that frame. Same build, A/B:

| | Hardware bitmaps | Regular bitmaps |
| --- | --- | --- |
| First tab switches after a start | ~4 frames with `cmd` 60–80 ms | 0 slow frames |
| Movies grid, held D-pad (`--cold memory`) | 1.8% dropped | 0.1% dropped (even while downloading) |
| Home rows | 0% dropped | 0% dropped |

An earlier Home A/B pointed the other way (hardware 0 slow frames, regular 23). That run changed the
flag mid-session, which cleared the poster memory, so the regular-bitmap side started colder.

**Cost:** each poster now has a CPU copy next to its texture; see the memory entry in
[known-issues.md](known-issues.md).

## Our own Text, and no tv-material Surface

**Decision (2026-10-27):** screens use `ui/Text.kt` (Compose's `BasicText` with tv-material's theme
defaults), and the app's root is a plain background box, not tv-material's `Surface`.

**Why:** updating tv-material from 1.0.0-alpha07 to 1.1.0 made the Movies grid drop frames. An A/B
of the two APKs, installed in turn on the box, gave 0.0–0.3% dropped before and 0.7–2.1% after. In
1.1, tv-material's `Text` renders every text into its own offscreen layer
(`CompositingStrategy.Offscreen`), and each poster card has two. The root `Surface` also draws a
full-screen glow pass on API 28 and newer, even with no glow. With both replaced: 0.0–0.1% dropped.

**Compose 1.12 (BOM 2026.09.00, same day):** an A/B against Compose 1.10.4 showed no visible
difference: the same dropped frames on Home, the Movies grid and tab switches, about 13% fewer
frames drawn for the same Home test, and a heavier (but hidden) rebuild behind the start screen. Its
source still releases layers of unplaced nodes, skips them in focus search, runs `onEnter` for
focus requests and defaults TV scrolling to the 30% pivot, so every workaround here still applies.

**Lesson:** a library update is a performance change. Benchmark it as an A/B before keeping it
([measuring-performance.md](measuring-performance.md)), and read the source of anything every frame
goes through.

## Visited screens stay alive

Home, Movies and TV Shows are built once and then hidden and shown, instead of being rebuilt on
every tab switch (which cost 40–100 ms each). This has enough rules of its own to need
[kept-screens.md](kept-screens.md).

## A tab switch is not a busy action

`MainActivity.selectLibrary` switches immediately and saves the choice in the background. It used
to run as a `runAction`, which set `state.busy`: every screen's `enabled` flipped off and on, so
every poster card on two screens recomposed twice per switch. A failed save only forgets the choice
for the next start.

## The background wash renders off the main thread

`MovieBackdrop` draws its colour wash once at 1/8 of the screen size on `Dispatchers.Default`,
caches the last 24, and stretches the bitmap. Before, the wash was drawn on the main thread on every
change: 461 renders averaging about 4.4 ms in one session, and a 60–100 ms hitch every time the Home
hero changed. It now costs 5–8 ms, off the main thread.

## The start screen hides the first layout

`MainActivity` keeps `StartScreen` over everything until Home reports ready: rows loaded, the hero
shown and the first row's posters in memory (at most 1.5 s), or 6 s at the latest. Keys are
swallowed while it shows. The libraries are also built and drawn once underneath it. A short
loading screen reads better than a page that visibly assembles itself.

## Things that measured as free, so the looks stay

A/B tested on 2026-10-26 with experiment flags, then the flags were removed:

- Rounded clipping of poster cards, their border and panel background.
- The poster title and subtitle text. (Text was about 40% of a screen's first build, 731 → 412 ms
  over budget across 8 switches, but kept screens made that build rare.)
- The hero image's offscreen fade mask (`CompositingStrategy.Offscreen` with `DstIn`).
- The background wash itself.

## No live blur for "liquid glass"

Real backdrop blur would need an extra full-screen GPU pass every frame on a GPU that is already
the bottleneck. Decision (2026-10): approximate it with a dark translucent scrim and a hairline rim
lit from the top ([design.md](design.md)).

## Scrolling motion

Rows move with one retargetable critically damped spring (stiffness 170) that keeps its velocity
when a new key arrives, instead of starting a new animation per key. Lazy lists prefetch with `LazyLayoutCacheWindow` so the next rows are composed in idle time.
Details are in [focus-and-scrolling.md](focus-and-scrolling.md).
