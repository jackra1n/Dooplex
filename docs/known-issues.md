# Known issues

Check here before investigating something from scratch. Each entry: what you see, why, and where
it stands. Add one whenever a problem took a session to understand.

## Tab switches hitch for 40–100 ms

**Symptom:** switching tabs drops a few frames while the new screen is built and drawn. The first
Movies visit after a start is worst: three or four frames whose GPU part (`cmd`) takes 50–80 ms.

**Cause:** every switch rebuilds the screen. Keeping screens alive removed the repeat-switch hitch
but cost about 100 MB, and even drawing the libraries once under the start screen did not fix the
first Movies visit. Our guess for that one is that the GPU drops some cached textures before the
visit, but without adb we cannot trace the RenderThread.

**Status:** accepted; see [performance-decisions.md](performance-decisions.md).

## adb does not work on the Zidoo

**Symptom:** `adb connect` to port 5555 connects and then hangs; nothing ever answers.

**Cause:** `adbd` runs (uid 2000) and accepts connections, but its main loop never replies to the
`CNXN` handshake, even from the box itself through Termux. USB debugging shows as enabled.

**Status:** unsolved; we work without it. See [zidoo-box.md](zidoo-box.md) for what we use instead.

## The box stops drawing while idle

**Symptom:** an app opened remotely (or left alone) draws nothing until a key is pressed; analytics
showed "first frame after 62433 ms" (2026-10-26).

**Cause:** the box's idle or screensaver state stops the window from drawing.

**Status:** `tools/bench.py` presses Right before every run to wake it. Remember it when an
automated test sees no frames.

## Memory use was about 350–370 MB

**Symptom:** analytics reported a PSS of 346–373 MB (2026-10-26), up from about 120 MB.

**Cause:** about 100 MB came from keeping screens alive, about 100 MB from regular bitmaps that keep
a CPU copy of every poster next to its GPU texture. Screens are no longer kept (2026-10-27); the
bitmaps stay for smooth scrolling. See [performance-decisions.md](performance-decisions.md).

**Status:** measure again after the kept-screen removal. Watch it if Android starts killing Dooplex while the player runs.
