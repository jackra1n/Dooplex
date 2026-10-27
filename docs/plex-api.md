# Plex API notes

The endpoints Dooplex uses and the details that were not obvious. Account calls are in
`PlexAccountClient.kt`, server calls in `PlexClient.kt`.

## Linking an account (plex.tv)

- **`POST https://plex.tv/api/v2/pins` with `strong=false`** gives the short code people type at
  plex.tv/link. A strong PIN is too long for that.
- **The response's `qr` field** is the address of a ready-made QR code PNG for the link page. We use
  it only if it is on a plex.tv host (`plexHosted`), and fall back to
  `https://plex.tv/api/v2/pins/qr/<code>`.
- **The QR image is white on transparent.** Drawn as is on a white card it is invisible, so the sign-in
  screen tints it with `CinemaColors.OnAccent` (`#191A1D`) through `ColorFilter.tint`.
- Poll `GET /api/v2/pins/<id>?code=<code>` once a second until it returns a token. A code expires;
  `MainActivity.linkAccount` makes up to 4 codes in turn, then shows an error with a "Try again"
  button, so an unattended sign-in screen stops polling eventually.

## Home

- `GET /hubs/continueWatching?count=24` gives the Continue Watching row.
- `GET /hubs?count=24&excludeContinueWatching=1` gives the other rows, without repeating Continue
  Watching. Hub items arrive embedded in the response.
- Episodes in a hub are shown with their show's poster, and seasons fall back to it when they have
  none (`PlexClient.homeItem`), so a row keeps one card shape.
- The hero uses up to 8 movies and shows with background art, recent rows first (`HomeState`), and
  loads each one's details (`/library/metadata/<id>`) for its logo and summary.

## Search

- `GET /hubs/search?query=<text>&limit=24` returns one hub per kind. Dooplex keeps `movie`, `show`
  and `episode` hubs in that fixed order, so rows never move under someone typing.
- A single character returns nothing, so searching starts at two (`SearchState.MIN_QUERY`), 300 ms
  after the last keystroke.

## Watchlist

- The watchlist is on plex.tv's Discover service, not on the server:
  `GET https://discover.provider.plex.tv/library/sections/watchlist/all?sort=watchlistedAt:desc`
  with the account token, paged 100 at a time (`X-Plex-Container-Start` / `-Size`; above 100
  answers 400).
- Entries are identified by `plex://movie/…` or `plex://show/…` ids. The server's copies are found
  with `GET /library/all?type=1|2&guid=<ids>`, at most 40 ids per request, each id encoded on its
  own and joined with a literal comma (`%2C` or repeating `guid=` does not work). Ids the server
  lacks are simply absent.
- Every entry is shown in watchlist order. Titles the server lacks use Discover's own data (title,
  year, summary, poster) and open details without Play. Their posters are on
  `metadata-static.plex.tv` and, like hosted logos, are fetched through the server's image proxy.
- Adding and removing: `PUT https://discover.provider.plex.tv/actions/addToWatchlist?ratingKey=<id>`
  (or `removeFromWatchlist`), where `<id>` is the last part of the title's `plex://` id. A title
  matched by an old agent has no `plex://` id, so its details page shows no watchlist button.
- The watchlist loads when a server connects, so every details page knows whether its title is on
  it (`LocalWatchlist`).

## Title menu

- Media info is the title's `/library/metadata/<id>`: every `Media` version, its `Part` files, and
  each part's `Stream`s (`streamType` 1 video, 2 audio, 3 subtitles), shown as label/value
  sections (`PlexMediaInfo`).
- Mark as watched or unwatched is `GET /:/scrobble` or `/:/unscrobble` with
  `identifier=com.plexapp.plugins.library&key=<id>`; on a season or show it covers every episode.

## Libraries and artwork

- Libraries load 96 items per page, seasons and episodes 40 (`PlexClient` caps any page at 200);
  responses are capped at 4 MiB.
- Artwork comes from the server's photo transcoder (`/photo/:/transcode`) at its drawn size rounded
  up to a bucket, at most 2,048 px, instead of the full-size original; see
  [poster-pipeline.md](poster-pipeline.md).
- Show and season children are `/library/metadata/<id>/children`.

## Playback

- `/library/metadata/<id>` provides the media parts; the original file's URL goes to the Zidoo
  player ([native-player.md](native-player.md)).
- Progress goes to `/:/timeline` with one session id per playback.
