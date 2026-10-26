# Native player

Dooplex never decodes video itself: it hands Plex's original file URL to Zidoo's own player, which
keeps every codec, HDR format and audio passthrough the box supports. That is the reason the app
exists, so no fallback to a generic Android player is allowed.

## Starting playback (`ZidooPlayer.createIntent`)

An explicit `ACTION_VIEW` intent with the stream URL and `video/*`, sent to the first component
that exists, is enabled and is exported:

1. `com.zidoo.player/.activity.PlayerActivity`
2. `com.android.gallery3d/.app.MovieActivity` (the firmware's alias)

| Extra | Value |
| --- | --- |
| `title` | Shown by the player. |
| `position` | Resume point in ms, as an `Int` (so at most 2,147,483,647 ms). Only when above 0. |
| `from_start` | `true` when starting from 0. |
| `return_result` | `true`, so the player returns its final position. |

The URL must be an absolute HTTP(S) Plex original-media URL. The activity is started for a result
(`MainActivity.nativePlayer`); `ZidooPlayer.readResult` reads the `position` extra (an `Int` or
`Long`; 0 is a real position, a missing extra is not).

## Reporting to Plex (`PlaybackService`)

A foreground service runs while the player is open and reports only what the player proves:

- Every 3 s it reads `http://127.0.0.1:9529/ZidooVideoPlay/getPlayStatus`, and tries the firmware
  alias `/VideoPlay/…` when the first answers 404 or shows nothing playing (`ZidooPlayer.readStatus`;
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
