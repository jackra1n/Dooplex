#!/usr/bin/env bash
# Screenshots Dooplex with the demo library on the Android TV emulator: tools/screenshot.sh [name] [KEY...]
# KEY is an Android key event name without KEYCODE_, e.g. DPAD_RIGHT, DPAD_CENTER, BACK.
set -euo pipefail

name="${1:-home}"
shift || true
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="$sdk/platform-tools/adb"
avd="${SCREENSHOT_AVD:-Television_1080p}"
port=8798
apk="app/build/outputs/apk/release/dooplex-movies.apk"
app="com.jackra1n.dooplex"
out="docs/screenshots/$name.png"
frame=$(mktemp)
server=""
emulator=""

cleanup() {
  rm -f "$frame"
  [[ -z "$server" ]] || kill "$server" 2> /dev/null || true
  [[ -z "$emulator" ]] || "$adb" -s "$serial" emu kill > /dev/null 2>&1 || kill "$emulator" 2> /dev/null || true
}
trap cleanup EXIT

running() {
  for serial in $("$adb" devices | awk '/^emulator-.*\tdevice$/ { print $1 }'); do
    if [[ "$("$adb" -s "$serial" emu avd name | head -1 | tr -d '\r')" == "$avd" ]]; then
      echo "$serial"
      return
    fi
  done
}

# Waits until three captures in a row are identical, so posters and animations have finished.
settle() {
  local last="" same=0
  for _ in $(seq 60); do
    "$adb" -s "$serial" exec-out screencap -p > "$frame"
    local sum
    sum=$(md5sum < "$frame")
    if [[ "$sum" == "$last" ]]; then
      (( ++same < 2 )) || return 0
    else
      same=0
      last="$sum"
    fi
    sleep 0.5
  done
  echo "The screen kept changing; saving the last frame." >&2
}

python3 tools/demo-art.py
mkdir -p build
python3 tools/demo-server.py "$port" > build/demo-server.log 2>&1 &
server=$!
# The emulator reaches this PC at 10.0.2.2.
DOOPLEX_DEMO_URL="http://10.0.2.2:$port" mise run build

serial=$(running)
if [[ -z "$serial" ]]; then
  printf 'Booting %s...\n' "$avd"
  "$sdk/emulator/emulator" -avd "$avd" -port 5584 -no-window -no-audio -no-boot-anim \
    -no-snapshot-save -gpu host > /dev/null 2>&1 &
  emulator=$!
  serial=emulator-5584
  until [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" == 1 ]]; do
    kill -0 "$emulator" 2> /dev/null || { echo "The emulator exited while booting." >&2; exit 1; }
    sleep 2
  done
  until [[ "$("$adb" -s "$serial" shell dumpsys connectivity)" == *VALIDATED* ]]; do sleep 2; done
fi

# Density 240 gives the Zidoo's 1280 x 720 dp layout on a 1920 x 1080 screen.
"$adb" -s "$serial" shell wm density 240
"$adb" -s "$serial" install -r --no-incremental "$apk" > /dev/null
"$adb" -s "$serial" shell pm clear "$app" > /dev/null
"$adb" -s "$serial" shell am start -W -n "$app/.MainActivity" > /dev/null
settle

for key in "$@"; do
  "$adb" -s "$serial" shell input keyevent "KEYCODE_$key"
  sleep 0.6
done
(( $# == 0 )) || settle

mkdir -p "$(dirname "$out")"
cp "$frame" "$out"
printf 'Saved %s\n' "$out"
