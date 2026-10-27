# Design

Dooplex has a modern design: dark, artwork-led, with thin glass controls and backgrounds tinted by
the focused poster.

## Colour

| Token (`ui/theme/Color.kt`) | Value | Use |
| --- | --- | --- |
| `Canvas` | `#101012` | Page background, icon background |
| `Panel` | `#1C1C1F` | Card placeholders, panels |
| `Foreground` | `#F5F5F7` | Text |
| `Muted` | `#B7B7BE` | Secondary text |
| `Accent` | `#E1E3E8` | The wordmark, highlights |

The sign-in and start screens use a four-corner gradient (`CornerGround`, `CornerColors.Welcome`):
warm grey `#4A4640` top left, charcoal `#18181A` top right, umber `#5E3E24`
bottom right, ash `#42424A` bottom left. It is drawn from a 32 × 18 px bitmap stretched to the
screen, which is cheaper than drawing a full-screen gradient.

Library and Home pages wash the background with three colours taken from the focused poster
(`extractMovieAtmosphere`), cross-fading over 300 ms
([performance-decisions.md](performance-decisions.md)).

## Glass, without blur

Controls imitate glass without a live blur, which this GPU cannot afford (`Glass.kt`):

- **The tab track and account bubble** (`glassTrack`): a dark translucent scrim, black 20% at the
  top to 36% at the bottom, and a 1 px rim, white 14%, brightening to 28% along the top edge and
  fading round the corners.
- **Buttons** (`CinemaButton`): a grey gradient (`ControlTop` to `ControlBottom`) with a 22% rim.
- The current tab sits on a white 14% plate; the focused one gets a light pill (`#F7FAFC`) with dark
  ink (`#08080A`).

## The top bar

One layout on every page: a 5% safe margin at the sides and top (96 px and
54 px on a 1920 × 1080 screen), with sizes scaled by `screenWidthDp / 1920`. The account bubble sits
at the left margin and the tab track is centred on the screen, never on the page content.

Each page draws its own copy, so the copies must line up. Home gives the bar the full width. Grid
pages put it in their header and widen it back to the full screen with `Modifier.bleed` (the Movies
grid stops short of the alphabet rail). Before this (fixed 2026-10-27) the bar jumped about 21 dp
sideways when switching between Home and Movies.

## Motion

| What | Motion |
| --- | --- |
| Home page and library grids, up and down | Spring, damping 1, stiffness 170 |
| Home shelves, sideways | Compose's default bring-into-view motion |
| Tab capsule | Spring, damping 1: stiffness 240 to move, 300 to fade |
| Buttons | Spring, damping 1, stiffness 320; focused buttons grow to 1.07× with a 10 dp shadow |
| Poster cards | Grow to 1.035× in 160 ms; a soft top highlight and lit rim fade in over 140 ms |
| New artwork | Fades in over 220 ms; artwork already in memory appears at once |

Critically damped springs (damping 1) never overshoot, and a new key press retargets them while
keeping their speed.

## Wordmark and icon

- **Wordmark:** "DOOPLEX" in Roboto Bold, uppercase, widely spaced (30 sp with 7 sp tracking on
  the start screen). Roboto is the box's system font, so nothing extra is bundled. The owner likes
  this wordmark as it is; keep it.
- **App icon (2026-10-27):** two overlapping glowing amber discs with a play triangle, made with an
  AI image generator from the icon design brief; check that generator's terms before the first
  public release. It is an adaptive icon (`mipmap-anydpi-v26`) with an opaque foreground on a
  `#101012` background. The source image was re-centred on its discs with a wider dark margin so
  the discs fit inside the launcher's safe circle. The triangle in the artwork sits a few pixels
  below the discs' centre; we left it.
- **TV banner:** still to do. Android TV launchers want a 640 × 360 px banner with the symbol and
  the wordmark; the manifest currently reuses the square icon.
