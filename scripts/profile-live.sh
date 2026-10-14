#!/bin/zsh
# profile-live.sh <serial> [window-seconds]: CPU time, frames and memory of the live engine in three
# windows (idle, launcher swipes, photo changes). Needs a debug build (ROTATE_NOW receiver) and the
# live engine set as the wallpaper. Numbers go in docs/perf.md.
S=$1; W=${2:-30}
pid(){ adb -s $S shell pidof dev.fengine.wallpaper | tr -d '\r' | awk '{print $1}'; }
cpu(){ adb -s $S shell cat /proc/$(pid)/stat | awk '{print $14+$15}'; }   # utime+stime in clock ticks (10 ms)
frames(){ adb -s $S logcat -d -s PhotoWallpaper | grep -c "frame" ; }
W_=$(adb -s $S shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); WX=${W_%x*}; WY=${W_#*x}
echo "pid $(pid) screen $W_"
adb -s $S shell input keyevent KEYCODE_HOME; sleep 2
# 1. idle: screen on, launcher visible, no input
adb -s $S logcat -c; c0=$(cpu); sleep $W; c1=$(cpu)
echo "idle ${W}s: cpu $(( (c1-c0)*10 )) ms, frames $(adb -s $S logcat -d -s PhotoWallpaper | grep -c 'frames')"
# 2. parallax: swipe launcher pages back and forth
adb -s $S logcat -c; c0=$(cpu); for i in $(seq 1 10); do adb -s $S shell input swipe $((WX*4/5)) $((WY/2)) $((WX/5)) $((WY/2)) 300; sleep 0.7; adb -s $S shell input swipe $((WX/5)) $((WY/2)) $((WX*4/5)) $((WY/2)) 300; sleep 0.7; done; c1=$(cpu)
echo "parallax 20 swipes: cpu $(( (c1-c0)*10 )) ms"
# 3. cross-fades: advance the photo through the app's worker 5 times
adb -s $S logcat -c; c0=$(cpu); for i in 1 2 3 4 5; do adb -s $S shell am broadcast -a dev.fengine.wallpaper.ROTATE_NOW -p dev.fengine.wallpaper >/dev/null 2>&1; sleep 4; done; c1=$(cpu)
echo "5 fades: cpu $(( (c1-c0)*10 )) ms"; adb -s $S logcat -d -s PhotoWallpaper | grep -E "fade|surface" | tail -6 | cut -c1-120
adb -s $S shell dumpsys meminfo dev.fengine.wallpaper | grep -E "TOTAL PSS|Graphics|Native Heap|Java Heap" | head -4
