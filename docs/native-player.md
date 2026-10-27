# Native player

Dooplex never decodes video itself: it hands Plex's original file URL to a player app. On a Zidoo
that is always Zidoo's own player, which keeps every codec, HDR format and audio passthrough the
box supports; that is why Dooplex exists. On other devices it falls back to whatever video player
Android opens (decision 2026-10-27), which then has to decode the file itself.

## Starting playback (`ExternalPlayer.createLaunch`)

An `ACTION_VIEW` intent with the stream URL and `video/*`. It goes explicitly to the first of these
that exists, is enabled and is exported:

1. `com.zidoo.player/.activity.PlayerActivity`
2. `com.android.gallery3d/.app.MovieActivity` (the firmware's alias)

With neither, the intent has no target, so Android opens the default video player or asks which
one to use; with no player at all, Dooplex says to install one.

| Extra | Value |
| --- | --- |
| `title` | Shown by the player. |
| `position` | Resume point in ms, as an `Int` (so at most 2,147,483,647 ms). Only when above 0. |
| `from_start` | `true` when starting from 0. |
| `return_result` | `true`, so the player returns its final position. |

Zidoo's player, VLC, MX Player and Just Player all read these same extras (VLC reads `position` as a
`Long` but falls back to an `Int`; checked in their sources, October 2026).

The URL must be an absolute HTTP(S) Plex original-media URL. The activity is started for a result
(`MainActivity.nativePlayer`), and `ExternalPlayer.readResult` takes the position from whichever
extra the player sends:

| Player | Result extra |
| --- | --- |
| Zidoo, MX Player, Just Player | `position` (`Int`; Zidoo may send a `Long`) |
| VLC | `extra_position` (`Long`) |
| MX Player, Just Player at the end | `end_by` = `playback_completion` and no position: the full duration is used |

0 is a real position; a missing extra is not.

## Reporting to Plex (`PlaybackService`)

A foreground service runs while the player is open and reports only what the player proves. Live
progress exists only with Zidoo's player; any other player's position reaches Plex once, when it
closes, so Plex shows no "now playing" for it. With Zidoo's player:

- Every 3 s it reads `http://127.0.0.1:9529/ZidooVideoPlay/getPlayStatus`, and tries the firmware
  alias `/VideoPlay/…` when the first answers 404 or shows nothing playing (`ExternalPlayer.readStatus`;
  the JSON is checked by `NativeTelemetryDecoder`).
- `status` 806 or a missing `video.path` means nothing is playing yet: keep sampling, report
  nothing. `video.status` 0 is paused and 1 is playing; `currentPosition` and `duration` are ms.
- The reported path must match the URL we started (`NativeMediaIdentity`); a different file is an
  error, never progress for our item.
- Each verified sample goes to Plex's `/:/timeline`. When the player returns, the final position
  (from the result, else the last verified sample) is sent as `stopped`. Without a verified position
  nothing is sent: no invented progress.

`PlaybackMonitor.state` carries the current phase for the UI; while its `running` flag is set,
screens disable their controls and focus requests.
