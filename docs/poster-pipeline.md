# Poster pipeline

`PosterRepository` serves the newest request first, never throws away a download it started, and
keeps two caches: decoded bitmaps in memory and compressed files on disk. Cards ask for artwork
with `rememberMovieArtworkPainter` (`MovieArtwork.kt`).

## Requests

- **Newest first.** Requests wait in a LIFO queue drained by 4 workers. During fast scrolling the
  posters now on screen matter more than the ones already scrolled past.
- **Started requests finish.** A queued request disappears when its last card leaves; one already
  downloading completes and fills both caches even if nobody waits any more. The first version
  cancelled downloads on scroll and also skipped loading while a list was scrolling, so posters
  never caught up during fast scrolling.
- **One request per key.** Cards asking for the same artwork at the same size share it.
- **Keep-alive HTTP.** A fully read response returns its connection to the pool; only failures
  disconnect.
- **Decoding is one at a time** (`decodeSlot`), off the main thread, with size and pixel limits.
- **Workers give way to the UI.** They run on four threads of their own, one step above
  background priority (as Glide's do), not on `Dispatchers.IO` at normal priority, and the colour
  extraction runs on the same thread instead of hopping to `Dispatchers.Default`. Added 2026-11-13
  and not yet measured: A/B it with `--pattern movie --cold memory` and watch `poster.wait.ms`,
  since lower priority can also make posters arrive later.
- **Lookahead.** Grids warm the rows ahead of the scroll direction one image at a time, and only
  when no visible request is waiting (`awaitIdle`).

## Sizes

Artwork is requested from Plex's photo transcoder at the size it is drawn, rounded up to a bucket
(`artworkBucket`: 192, 256, 288, 384 or 512 px wide). On the box, Home's shelves (7 cards across)
use 256 × 384 px, about 393 KB decoded; the 6-column library grids use 288 × 432 px, about 486 KB.

The 288 bucket exists for those grids (2026-11-13): their cards are 267 px wide, 288 px when
focused (× 1.08), and used to round up to 384 × 576 px, about 885 KB each for pixels never shown.
Posters cached on disk at the old size are downloaded once more and age out of the disk cache.

## Caches

| Cache | Limit | Notes |
| --- | --- | --- |
| Memory | 96 MiB of decoded bitmaps | LRU. Bitmaps are never recycled on eviction, because a card may still draw one. |
| Disk | 256 MiB, 8,192 files, 8 MiB per image | `cacheDir/posters-v1`. One directory scan builds an in-memory LRU index, shared by every repository in the process; reads and writes then touch only their own file. Writes go to a temporary file and are renamed into place. |

Disk names are SHA-256 hashes that include the server and account, so the token never appears in a
file name and two accounts never share artwork.

## Handing bitmaps to the GPU

After decoding, the repository extracts the poster's colours for the background wash (off the main
thread, so a focus change can blend at once) and calls `prepareToDraw()` so the texture uploads
between frames. Not `HARDWARE` bitmaps: see [performance-decisions.md](performance-decisions.md).

## Showing them

- A memory hit draws immediately, fully opaque.
- Artwork that arrives after its card appeared fades in over 220 ms (`rememberArtworkReveal`), and
  the placeholder is only painted until the poster is opaque.
- In the grids, cards outside the artwork range (the visible rows plus one on each side) request
  nothing.

## Measuring it

Analytics `stats` report `poster.wait.ms` (how long a card showed its placeholder),
`poster.disk.ms`, `poster.network.ms` and `poster.memory` hits. In October 2026 cards waited
20–30 ms on average, 60–120 ms at worst, even with an empty disk cache.
