# Kept screens

Home, Movies and TV Shows are composed once and stay alive; a tab switch only moves which one is
on screen. Repeat switches went from a 40–100 ms hitch to no slow frames at all (2026-10-26).
Show seasons and episodes are not kept; they are rebuilt as before.

The pieces: `KeptScreen.kt`, its use in `MainActivity.setContent`, `WhenVisible` in
`HomeScreen.kt` and `CollectionScreen.kt`, and `LocalScreenVisible` in `CollectionNavigationBar.kt`.

## How a screen is hidden: parked above the window

A hidden screen is still placed, on its own layer, four screen heights above the window
(`placeWithLayer(0, -4 × height)`). The GPU skips content outside the window, the recorded drawing
survives, and showing the screen again only moves the layer.

Two simpler ideas failed:

- **Not placing it.** Compose releases every layer of a subtree that stops being placed
  (`MeasurePassDelegate.markSubtreeAsNotPlaced` calls `releaseLayer`), so showing it again
  re-recorded every card: 33–64 ms switches, no better than rebuilding.
- **Alpha 0 in place.** Focus search would find the hidden screen's cards at the same positions as
  the visible ones and pick them.

## Keeping focus out of hidden screens

Compose's focus search only skips nodes that are not placed, and `FocusRequester.requestFocus()`
does not check placement at all. So:

- Each `KeptScreen` is a focus group whose `onEnter` cancels while hidden. Both D-pad moves and
  code calls run through that hook (`performCustomEnter` in Compose's focus transactions), so a
  hidden screen can never take focus. A move whose best target is hidden simply does not happen.
- The top bar's tab `FocusRequester`s are shared by every screen's copy of the bar. They attach only
  where `LocalScreenVisible` is true; otherwise `requestFocus` could focus the hidden copy.
- When a screen is hidden while it holds focus (the tab you just pressed), focus is cleared, and
  the screen being shown requests its own focus a frame later.
- Focus effects that could fire while a screen is hidden run inside `WhenVisible`. Home's tab
  entry effects sit outside it; they only react to the Home tab and header, which only the visible
  bar can trigger, and `onEnter` would cancel them anyway.

## Switching must not recompose the cards

The first version passed `visible` to each screen as a parameter. Changing it re-ran the whole
screen body and every visible poster card on both screens: 22–57 ms of composition per switch.

Rules that follow:

- **Never pass visibility as a screen parameter.** Read it with `WhenVisible { visible -> … }`, a
  tiny scope around the focus effects and Back handling. Only that scope recomposes.
- **Cards must not read anything that changes on a switch.** `enabled` and `playbackRunning` mean
  real playback or a busy action, never "hidden". (A switch that set `state.busy` also recomposed
  every card twice; see [performance-decisions.md](performance-decisions.md).)
- `MovieBackdrop` reads `LocalScreenVisible` itself, so a hidden screen's wash loads no artwork.

## Built and warmed behind the start screen

`MainActivity.prepareLibraries` creates the saved movie and show libraries as soon as a server
connects, so their screens are composed hidden from the start. While the start screen covers
everything, `KeptScreen(warm = splash)` places them in the window under it, so each is drawn once
before its first real visit. It must end before the start screen fades, or the libraries would
show through over Home; it ends when `splash` turns false, the frame the fade starts.

## Costs

- More memory: three screens and their posters stay resident.
- Hidden screens still recompose and re-measure when their own state changes (posters arriving,
  rows loading), and they still load the posters of their visible area.
- The first Movies visit after a start still stutters; see [known-issues.md](known-issues.md).
