# Known issues

Check here before investigating something from scratch. Each entry: what you see, why, and where
it stands. Add one whenever a problem took a session to understand.

## The first Movies visit after a start stutters for about 250 ms

**Symptom:** the first switch to Movies after the app (or Home) starts shows three or four frames
whose GPU part (`cmd`) takes 50–80 ms. Every later switch is clean (measured 2026-10-26: 7 of 8
switches with no slow frame).

**Cause:** unknown. It is GPU work on the first real showing of that screen. Drawing the libraries
once under the start screen (`KeptScreen(warm = splash)`) did not remove it. Our guess is that the
GPU drops some cached textures before the visit, but without adb we cannot trace the RenderThread.

**Status:** open. See [kept-screens.md](kept-screens.md).

## adb does not work on the Zidoo

**Symptom:** `adb connect` to port 5555 connects and then hangs; nothing ever answers.

**Cause:** `adbd` runs (uid 2000) and accepts connections, but its main loop never replies to the
`CNXN` handshake, even from the box itself through Termux. USB debugging shows as enabled.

**Status:** unsolved; we work without it. See [zidoo-box.md](zidoo-box.md) for what we use instead.

## The web installer answers HTTP 400 even when it installed the app

**Symptom:** uploading to `http://<box>:18888` returns 400 with
`next chunk does not start with boundary`, in a browser too, yet the APK installs.

**Cause:** the firmware's multipart parser fails after it has already installed the file.

**Status:** worked around: `mise run upload` treats any reply containing that message as success.

## The web installer and control API have no authentication

**Symptom:** anything on the network can install an APK through port 18888 and press remote keys
through port 9529.

**Cause:** Zidoo's firmware ships them that way.

**Status:** cannot be fixed without root or adb. Keep the box on a trusted network.

## The box stops drawing while idle

**Symptom:** an app opened remotely (or left alone) draws nothing until a key is pressed; analytics
showed "first frame after 62433 ms" (2026-10-26).

**Cause:** the box's idle or screensaver state stops the window from drawing.

**Status:** `tools/bench.py` presses Right before every run to wake it. Remember it when an
automated test sees no frames.

## The launcher kept the old icon after an update

**Symptom:** after installing the build with the new icon (2026-10-27), the Zidoo launcher still
showed the old one.

**Cause:** probably the launcher's icon cache. The TV banner also still points at the square icon
(`android:banner="@mipmap/ic_launcher"`).

**Status:** open. A proper 640 × 360 banner is still to be made (see [design.md](design.md)).

## Memory use is about 350–370 MB

**Symptom:** analytics reports a PSS of 346–373 MB (2026-10-26), up from about 120 MB.

**Cause:** three screens kept alive, plus regular bitmaps that keep a CPU copy of every poster next
to its GPU texture. Both were chosen for smoothness; see
[performance-decisions.md](performance-decisions.md).

**Status:** accepted for now. Watch it if Android starts killing Dooplex while the player runs.
