#!/usr/bin/env bash
# smoke-matrix.sh AVD [AVD...]
#
# Boots each emulator headless, installs the debug build and walks the app's core paths:
# seed a group from the library, rotate (static setBitmap), label + describe-scan, live engine,
# lock-screen rule, screen saver, crash buffer, then the on-device tests. One line per check.
# Needs: ./gradlew :app:assembleDebug first; AVDs from docs/build.md ("Version matrix").
set -u
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
EMU=$SDK/emulator/emulator
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=dev.fengine.wallpaper
OUT=${SMOKE_OUT:-build/smoke}; mkdir -p "$OUT"
port=5600

pass() { printf '%-8s %-14s PASS %s\n' "$avd" "$1" "${2:-}" | tee -a "$OUT/summary.txt"; }
fail() { printf '%-8s %-14s FAIL %s\n' "$avd" "$1" "${2:-}" | tee -a "$OUT/summary.txt"; }
# wait_log <seconds> <regex>: true once the regex shows up in the app's log.
wait_log() { local end=$((SECONDS + $1)); while [ $SECONDS -lt $end ]; do adb -s "$S" logcat -d 2>/dev/null | grep -qE "$2" && return 0; sleep 2; done; return 1; }
# -f 32 = FLAG_INCLUDE_STOPPED_PACKAGES: a fresh install is "stopped" until first opened.
bc() { adb -s "$S" shell am broadcast -f 32 -a "$PKG.$1" -p $PKG >/dev/null 2>&1; }

for avd in "$@"; do
  S=emulator-$port
  "$EMU" -avd "$avd" -port $port -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$OUT/$avd-emulator.log" 2>&1 &
  emu_pid=$!
  # Fail fast when the emulator dies (stale lock, port in use) instead of waiting forever.
  booted=0
  for i in $(seq 1 100); do
    kill -0 $emu_pid 2>/dev/null || break
    [ "$(adb -s "$S" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
    sleep 3
  done
  if [ $booted = 0 ]; then
    fail boot "$(grep -E 'FATAL|ERROR' "$OUT/$avd-emulator.log" | tail -1)"; kill $emu_pid 2>/dev/null; port=$((port + 2)); continue
  fi
  sdk=$(adb -s "$S" shell getprop ro.build.version.sdk | tr -d '\r')
  echo "== $avd (API $sdk) on $S" | tee -a "$OUT/summary.txt"
  adb -s "$S" shell settings put global window_animation_scale 0
  adb -s "$S" install -r -g "$APK" >/dev/null && pass install "API $sdk" || { fail install; kill $emu_pid; port=$((port+2)); continue; }
  # Open the app once, as a person would; it also leaves the "stopped" state.
  adb -s "$S" shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 2
  if adb -s "$S" shell dumpsys activity activities 2>/dev/null | grep -qE "(mResumedActivity|topResumedActivity).*$PKG/.MainActivity"; then pass open; else fail open "$(adb -s "$S" logcat -d -b crash | grep -E 'Exception' | head -1)"; fi
  adb -s "$S" shell input keyevent KEYCODE_HOME

  # Photos in the library: generate, push, ask the media scanner for each file.
  tmp=$(mktemp -d); python3 -I scripts/make-test-photos.py "$tmp" 8 >/dev/null
  adb -s "$S" shell mkdir -p /sdcard/Pictures/smoke
  for f in "$tmp"/*.jpg; do adb -s "$S" push "$f" /sdcard/Pictures/smoke/ >/dev/null
    adb -s "$S" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Pictures/smoke/$(basename "$f")" >/dev/null 2>&1; done
  rm -rf "$tmp"
  # The media scanner is asynchronous and slow right after boot: wait until all 8 are indexed.
  for i in $(seq 1 30); do
    n=$(adb -s "$S" shell content query --uri content://media/external/images/media --projection _id 2>/dev/null | grep -c '^Row')
    [ "$n" -ge 8 ] && break
    # Android 11 ignores the scan-file broadcast from the shell; MediaProvider's scan_volume works
    # from Android 10 on. Android 9 has no scan_volume and honours the broadcast.
    [ $((i % 5)) = 1 ] && adb -s "$S" shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
    sleep 2
  done
  adb -s "$S" logcat -c

  bc SEED
  if wait_log 20 "Smoke *: seeded [1-9]"; then pass seed "$(adb -s "$S" logcat -d -s Smoke | grep -oE 'seeded [0-9]+ photos')"; else fail seed "$(adb -s "$S" logcat -d -s Smoke | tail -1)"; fi

  # Open the app with data and photo access: the home page, index progress and group covers load.
  adb -s "$S" shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 5
  if adb -s "$S" shell dumpsys activity activities 2>/dev/null | grep -qE "(mResumedActivity|topResumedActivity).*$PKG/.MainActivity" \
     && [ "$(adb -s "$S" logcat -d -b crash 2>/dev/null | grep -c "$PKG")" = 0 ]; then pass ui
  else fail ui "$(adb -s "$S" logcat -d -b crash 2>/dev/null | grep -E 'Exception|at dev.fengine' | head -2 | tr '\n' ' ')"; fi
  adb -s "$S" shell input keyevent KEYCODE_HOME

  bc ROTATE_NOW
  if wait_log 40 "WallpaperApplier.*-> id=[1-9]"; then pass rotate; else fail rotate "$(adb -s "$S" logcat -d -s RotateWallpaperWorker WallpaperApplier | tail -2 | tr '\n' ' ')"; fi

  bc SCAN
  if wait_log 120 "SmartGroupWorker.*Smoke: (\+[0-9]|rule)"; then pass scan "$(adb -s "$S" logcat -d -s IndexLibraryWorker | grep -oE 'labeled [0-9]+' | tail -1)"
  else fail scan "$(adb -s "$S" logcat -d | grep -E 'IndexLibraryWorker|SmartGroupWorker|Invalid column|IllegalArgument' | tail -2 | tr '\n' ' ')"; fi

  scripts/set-live.sh "$S" >"$OUT/$avd-setlive.log" 2>&1
  if wait_log 20 "PhotoWallpaper.*engine [0-9]+ created" && wait_log 20 "PhotoWallpaper.*(frame [0-9]+|fade:)"; then pass live
  else fail live "$(tail -3 "$OUT/$avd-setlive.log" | tr '\n' ' ')"; fi

  bc PROBE; sleep 2
  pass probe "$(adb -s "$S" logcat -d -s Probe | grep -oE 'activeOnHome=[a-z]+ activeOnLock=[a-z]+' | tail -1)"
  adb -s "$S" logcat -c; bc ROTATE_NOW; sleep 8
  writes=$(adb -s "$S" logcat -d -s RotateWallpaperWorker | grep -oE 'which=[0-9]+' | tr '\n' ' ')
  live=$(adb -s "$S" logcat -d -s RotateWallpaperWorker | grep -oE 'live: State\([^)]*\)' | tail -1)
  # With the engine on both screens, a rotation must not write over it.
  if [ -z "$writes" ]; then pass live-rotate "no static writes; $live"; else fail live-rotate "wrote $writes; $live"; fi

  adb -s "$S" shell settings put secure screensaver_components $PKG/.screensaver.GroupDreamService
  adb -s "$S" shell settings put secure screensaver_enabled 1
  # Dream shell commands need root (google_apis images allow adb root). Older releases have no
  # `cmd dreams`; there IDreamManager.dream() is binder transaction 1.
  adb -s "$S" root >/dev/null 2>&1; sleep 2; adb -s "$S" wait-for-device
  out=$(adb -s "$S" shell cmd dreams start-dreaming 2>&1)
  echo "$out" | grep -qiE "unknown|can't find|not found|No shell command" && adb -s "$S" shell service call dreams 1 >/dev/null 2>&1
  if wait_log 20 "GroupDreamService.*(playing|not playing)"; then pass dream "$(adb -s "$S" logcat -d -s GroupDreamService | grep -oE '(not )?playing[^:]*' | tail -1)"
  else fail dream "no screen saver log"; fi
  adb -s "$S" shell cmd dreams stop-dreaming >/dev/null 2>&1 || adb -s "$S" shell service call dreams 2 >/dev/null 2>&1
  adb -s "$S" unroot >/dev/null 2>&1; sleep 2; adb -s "$S" wait-for-device; adb -s "$S" shell input keyevent KEYCODE_HOME

  crashes=$(adb -s "$S" logcat -d -b crash 2>/dev/null | grep -c "$PKG")
  if [ "$crashes" = 0 ]; then pass crashes; else fail crashes "$(adb -s "$S" logcat -d -b crash | grep -A3 "$PKG" | head -6 | tr '\n' ' ')"; fi

  if ANDROID_SERIAL=$S ./gradlew :app:connectedDebugAndroidTest --no-daemon -q >"$OUT/$avd-androidTest.log" 2>&1; then pass androidTest
  else fail androidTest "$(grep -E 'FAILED|Exception' "$OUT/$avd-androidTest.log" | head -2 | tr '\n' ' ')"; fi
  adb -s "$S" logcat -d > "$OUT/$avd-logcat.txt" 2>/dev/null

  adb -s "$S" emu kill >/dev/null 2>&1; wait $emu_pid 2>/dev/null
  port=$((port + 2))
done
