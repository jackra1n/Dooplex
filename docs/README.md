# Dooplex docs

What we learned building Dooplex: the tricks, workarounds and decisions the code alone does not
explain. Read the topic before changing the code it covers; most of these rules were paid for with
a measured stutter, a crash, or an evening on the Zidoo.

| File | What it answers |
| --- | --- |
| [known-issues.md](known-issues.md) | Problems we know about, why they happen, and their status. Check here first. |
| [zidoo-box.md](zidoo-box.md) | The Zidoo Z9X 8K: its services, what is locked, how we reach it. |
| [dev-workflow.md](dev-workflow.md) | Building, installing, crash reports, analytics, traces. |
| [measuring-performance.md](measuring-performance.md) | How we measure smoothness, what the numbers mean, current baselines. |
| [performance-decisions.md](performance-decisions.md) | What we changed for smoothness, what we tried that did not matter, and why. |
| [focus-and-scrolling.md](focus-and-scrolling.md) | D-pad focus and scroll rules, and the bugs each one fixed. |
| [poster-pipeline.md](poster-pipeline.md) | How artwork is fetched, cached and handed to the GPU. |
| [r8-release-builds.md](r8-release-builds.md) | The R8 launch crash and the guards that keep it from returning. |
| [native-player.md](native-player.md) | Handing playback to Zidoo's player (or another one) and reporting it to Plex. |
| [plex-api.md](plex-api.md) | The Plex endpoints we use and their quirks. |
| [design.md](design.md) | Look and feel decisions: palette, glass, top bar, motion, icon. |

## Where the code lives

Packages under `app/src/main/java/com/jackra1n/dooplex/`, one per feature:

| Package | Contents |
| --- | --- |
| *(root)* | `MainActivity` (sign-in, server choice, start screen, playback launch, benchmark commands), `Browser` (one connected server's tabs and moving between them), `BrowserScreen` (draws the current tab) and `DooplexApplication`. |
| `home` | The Home screen and its state. |
| `library` | Movies and TV Shows grids, a show's seasons and episodes, and their states. |
| `details` | Movie details, and the loader search and watchlist use to open them. |
| `search` | The search page and its state. |
| `watchlist` | The watchlist page and its state. |
| `setup` | Sign-in, server and library pickers, the start screen. |
| `playback` | Handing off to Zidoo's player (or another player) and reporting progress to Plex. |
| `artwork` | Poster loading and caching, artwork painters, the background wash. |
| `plex` | Plex clients, saved account and navigation, models. |
| `ui` | Shared components: top bar, buttons, glass, poster card, grid scroller; `ui/theme` holds colours and type. |
| `diagnostics` | Analytics and crash reporting for development builds. |

## How to write these docs

- **One topic per file**, kebab-case name, listed in the table above.
- **The first sentence is the rule or the answer**, not an introduction.
- **Give the reason with its evidence**: the measured number, its unit, the date and the build or
  device. A decision without its reason gets undone by the next person who does not know it.
- **Point at the code** (`File.kt`, function or constant names) instead of copying it.
- **Keep a file under about 150 lines.** Split it when it grows; raw logs and long measurement
  tables go in `docs/measurements/`.
- **Update the doc in the same commit as the code it describes.** When a doc turns out wrong,
  correct it in place and say what changed; never leave two statements that contradict each other.
- **Add a known issue** whenever something took a session to figure out, so nobody has to again.
