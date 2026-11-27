#!/usr/bin/env bash
# release-probe.sh <serial> [apk]
#
# Installs the minified release APK and exercises the paths R8 can break without any debug hook:
# open the app, ask ML Kit for Gemini Nano's status (the "find in a group" launch does that), and
# check the crash buffer. Release builds 0.9.0 and 0.9.1 failed here with a NullPointerException
# from Generation.getClient() that debug builds never showed. An emulator has no AICore, so any
# clean answer or GenAiException passes; R8-shaped errors fail.
set -u
S=${1:?serial}
APK=${2:-app/build/outputs/apk/release/app-release.apk}
PKG=dev.fengine.wallpaper
fail() { echo "FAIL $1"; exit 1; }

adb -s "$S" uninstall $PKG >/dev/null 2>&1
adb -s "$S" install -r "$APK" >/dev/null || fail "install"
adb -s "$S" logcat -c
adb -s "$S" logcat -b crash -c 2>/dev/null
adb -s "$S" shell am start -W -n $PKG/.MainActivity --es $PKG.FIND_IN probe >/dev/null 2>&1

answer=""
for _ in $(seq 1 30); do
  log=$(adb -s "$S" logcat -d -v brief 2>/dev/null)
  if echo "$log" | grep -q "NanoMatcher.*checkStatus="; then answer=status; break; fi
  if echo "$log" | grep -q "Gemini Nano status failed"; then answer=error; break; fi
  sleep 2
done
[ -n "$answer" ] || fail "no Gemini Nano status answer in 60 s"

if [ "$answer" = error ]; then
  why=$(adb -s "$S" logcat -d -v brief | grep -A1 "Gemini Nano status failed" | tail -1)
  echo "status error: $why"
  echo "$why" | grep -qE "NullPointerException|ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|AbstractMethodError|ClassCastException|ExceptionInInitializerError" \
    && fail "R8-shaped error from ML Kit: $why"
fi
echo "PASS gemini-status ($answer)"

crash=$(adb -s "$S" logcat -d -b crash 2>/dev/null | grep -A3 "Process: $PKG" | head -4)
[ -z "$crash" ] || fail "crash: $crash"
adb -s "$S" shell pidof $PKG >/dev/null || fail "process not running after launch"
echo "PASS no-crash"
