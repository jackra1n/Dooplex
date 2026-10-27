# Focus and scrolling

Every lazy list and grid on the Home, library and show screens sets its own
`LocalBringIntoViewSpec`; do the same for new ones. The TV default (`PivotBringIntoViewSpec`)
scrolls the focused item to 30% of the container, which caused most of the scroll bugs below.
`LocalBringIntoViewSpec` is experimental, so its users need `@OptIn(ExperimentalFoundationApi::class)`.

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
  row rests (just under the header), on one critically damped spring (stiffness 170). A new key
  retargets the running motion and keeps its speed, so a held D-pad glides instead of stepping. It
  also handles the header and the end of the list.
- **While the scroller drives, bring-into-view is off** (`directionalFocus` makes the grid's spec
  return 0), so the focus system does not start a second scroll that fights the first.
- **Posters' own Up and Down are handled in `onPreviewKeyEvent`**, moving focus by one row
  (`movePosterFocus`), instead of Compose's geometric focus search.

## Tabs and the top bar

- **Moving up into the bar lands on the current page's tab**, never on the account just because it
  sits above the content: the bar is a focus group whose `onEnter` redirects Up to the active tab.
- **Left from the first tab reaches the account**; the tabs' own Left and Right handling stops at
  the ends, so this is explicit (`CollectionFocusState.account`).

- **Confirming a tab lands on the first poster**: the bar calls
  `CollectionFocusState.enterContent(type)`, and the screen focuses its first item and scrolls to
  the top. Home focuses the hero.
- **That entry waits for the grid's first layout** before focusing, and the restore logic is
  skipped while an entry is pending (`focus.contentType == state.library.type`). Bug fixed in
  October 2026: switching Movies to TV Shows restored the old position before layout and scrolled
  the header with the top bar out of view.

## The show page

- **It opens on Play** for the next episode. Down goes to the selected season's tab, then to the
  episode row, which opens on the next episode (else the first unwatched one). Up retraces.
- **Moving into the seasons or episodes lifts the whole page** by the height that does not fit, on
  one spring (stiffness 170), so the hero stays partly visible above the row. It is a
  `graphicsLayer` translation, not a scroll, so bring-into-view never fights it.
- **Season tabs select on focus** after a 250 ms pause, so passing over a tab loads nothing.
- **Both rows use `focusRestorer`**, so returning to a row finds the card it left.

## Focus must never be dropped

When the focused element disappears or becomes unfocusable, Android hands focus to the first
focusable element, the Home tab, which also marks the top bar as being navigated; the page then pulls
focus back there. So:

- **Disabled cards stay focusable** and ignore presses (`CollectionPosterCard`, `EpisodeCard`).
  Opening details disables the cards underneath, which used to drop the clicked card's focus.
- **Closing details moves focus first:** the poster (or its grid, with `focusRestorer`) takes focus
  while details still covers it, then details closes.

## Back

Back behaves like other TV apps (decision 2026-10-27):

- **Home:** first returns focus to the hero; on the hero it leaves for the launcher with
  `moveTaskToBack`, so the app resumes instantly instead of starting over (Android 12+ does the same
  for root activities).
- **Movies and TV Shows:** closes the sort dialog or the alphabet rail first, then goes to Home, the
  same as choosing the Home tab.
- **A show page:** back to the TV Shows grid, or to Home when it was opened from Home, Search or
  Watchlist. An episode's details page closes back to its card.
- **Sign-in and server picker:** back to Home if one is open, otherwise out to the launcher.

There used to be a "Libraries" list behind Home with Servers, Back and Disconnect buttons; it was a
leftover developer screen and is gone. Switching servers and signing out live in the account menu.

## Prefetch

`rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(…))` composes rows ahead in idle time:
one screen ahead and half behind on the Home page, half a screen ahead in each shelf. Without it,
the first Down into a new shelf composed the whole shelf inside the frame.
