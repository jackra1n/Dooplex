# The Zidoo box

Dooplex targets the Zidoo Z9X 8K: an Android 11 media player whose firmware gives us a native
video player and a local HTTP control service, but no working adb. Everything below was found on
our box (firmware as of October 2026).

## The device

| Fact | Value |
| --- | --- |
| Android | 11 (API 30), a `userdebug` build, bootloader unlocked (orange state) |
| Output | 1920 × 1080 at 60 Hz, density 1.5, so **1280 × 720 dp** for layout |
| Inputs | two USB-A ports, no USB-C |
| System font | `sans-serif` is Roboto (`/system/etc/fonts.xml`) |

Developer options are hidden in Settings, and adb does not respond even with USB debugging on
(see [known-issues.md](known-issues.md)), so development works through the services below.

## Services we use

| Port | Service | Notes |
| --- | --- | --- |
| 18888 | Web installer | `POST` a multipart APK. It answers 400 ("next chunk does not start with boundary") even when it installed; `mise run upload` accepts that. |
| 9529 | Control center API | `GET /ZidooControlCenter/RemoteControl/sendkey?key=Key.Right` presses a key; `Apps/getApps` lists apps; `Apps/openApp?packageName=…` launches one (`startApp` does not exist). Answers `{"status":200}`. |
| 9529 | Player status | `GET /ZidooVideoPlay/getPlayStatus` (some firmware: `/VideoPlay/…`), used during playback; see [native-player.md](native-player.md). |
| 8022 | Termux sshd | Optional: a shell on the box, if Termux and its `openssh` are installed. |
| 5555 | adbd | Accepts connections, never answers. Unusable. |

## Working without adb

- **Install:** `mise run upload` posts to the web installer.
- **Launch:** `Apps/openApp?packageName=com.jackra1n.dooplex`. An install kills the running app, so
  launch it again after every upload.
- **Drive the UI:** `sendkey` through the control API. `tools/bench.py` does this.
- **Logs and crashes:** development builds send them to the PC (see [dev-workflow.md](dev-workflow.md)).
- **Shell:** Termux over SSH runs as an ordinary app user, so it cannot take screenshots or read
  other apps' data. Termux has no writable `/tmp`; use `$PREFIX/tmp`. Its `adb` needs
  `pkg install libc++` first (and still hits the stuck adbd).

## Quirks worth knowing

- When the box is idle it stops drawing our window until a key is pressed.
- The emulator was not a usable substitute: it crashed or stayed offline on this PC, and its GPU
  is nothing like the box's anyway. All measurements come from the box itself.
