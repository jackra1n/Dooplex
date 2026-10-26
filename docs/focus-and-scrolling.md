# Focus and scrolling

Every lazy list and grid on the Home, library and show screens sets its own
`LocalBringIntoViewSpec`; do the same for new ones. The TV default (`PivotBringIntoViewSpec`) scrolls
the focused item to 30% of the container, which caused most of the scroll bugs below. `LocalBringIntoViewSpec` is experimental, so its users need
`@OptIn(ExperimentalFoundationApi::class)`.

## Home (`HomeScreen.kt`)

- **The page (`shelfSpec`)** rests the focused shelf at a fixed line, 152 dp from the top, on the
  shared spring (`ShelfSpring`, stiffness 170).
- **The hero scrolls the page to its very top.** The distance is the hero's height plus the shelves
  still above the screen (they share one height) plus the scroll inside the first one, recomputed
  every frame. Bug fixed 2026-10-27: it used to scroll only when the hero was already partly
  visible, so a fast burst of Up presses reached the hero while the page was still rows down, and
  the hero stayed off screen.
- **Each shelf (`rowSpec`)** scrolls sideways only when the focused card would cross the 56 dp page
  margins. With the default pivot, moving Up and Down between shelves also scrolled them sideways.
  An earlier spec that ignored `contentPadding` left the first poster cut off after scrolling right
  and back left.

## Movies and TV Shows grids

- **`GridRowScroller`** moves the grid on Down and Up itself: the focused row rests where the first
  row rests (just under the header), on one critically damped spring (stiffness 170, plx-native's
  scroll constant). A new key retargets the running motion and keeps its speed, so a held D-pad
  glides instead of stepping. It also handles the header and the end of the list.
- **While the scroller drives, bring-into-view is off** (`directionalFocus` makes the grid's spec
  return 0), so the focus system does not start a second scroll that fights the first.
- **Posters' own Up and Down are handled in `onPreviewKeyEvent`**, moving focus by one row
  (`movePosterFocus`), instead of Compose's geometric focus search.

## Tabs and the top bar

- **Confirming a tab lands on the first poster** (as in plx-native): the bar calls
  `CollectionFocusState.enterContent(type)`, and the screen focuses its first item and scrolls to
  the top. Home focuses the hero.
- **That entry waits for the grid's first layout** before focusing, and the restore logic is
  skipped while an entry is pending (`focus.contentType == state.library.type`). Bug fixed in
  October 2026: switching Movies to TV Shows restored the old position before layout and scrolled
  the header with the top bar out of view.
- **Focus targets that several screens share** (the tab `FocusRequester`s) attach only in the visible
  screen; see [kept-screens.md](kept-screens.md).

## Prefetch

`rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(…))` composes rows ahead in idle time:
one screen ahead and half behind on the Home page, half a screen ahead in each shelf. Without it,
the first Down into a new shelf composed the whole shelf inside the frame.
