#!/bin/zsh
# profile-wake.sh <serial> [minutes]: what wakes the app while the screen is off, and what it costs.
# Puts the device "on battery" (dumpsys battery unplug), resets battery stats, sleeps the screen for
# the window, then reports per-app CPU, wakelocks, jobs, alarms and scheduler wake-ups. Restores the
# charger state afterwards. Works on the emulator; absolute numbers there are not phone numbers,
# but the counts (how many wake-ups, which jobs) are.
S=$1; MIN=${2:-10}; PKG=dev.fengine.wallpaper
pid(){ adb -s $S shell pidof $PKG | tr -d '\r' | awk '{print $1}'; }
sched(){ adb -s $S shell cat /proc/$(pid)/sched 2>/dev/null | awk -F: '/nr_wakeups\b|nr_switches|nr_voluntary_switches|nr_involuntary_switches/{gsub(/ /,"",$1); printf "%s=%s ", $1, $2+0}'; echo; }
cpu(){ adb -s $S shell cat /proc/$(pid)/stat | awk '{print $14+$15}'; }
echo "window ${MIN} min, pid $(pid)"
adb -s $S shell dumpsys battery unplug >/dev/null; adb -s $S shell dumpsys batterystats --reset >/dev/null
adb -s $S shell input keyevent KEYCODE_SLEEP; sleep 2
echo "before: $(sched)"; c0=$(cpu)
sleep $((MIN*60))
c1=$(cpu); echo "after:  $(sched)"
echo "cpu while asleep: $(( (c1-c0)*10 )) ms over ${MIN} min"
echo "---- batterystats for $PKG"
adb -s $S shell dumpsys batterystats $PKG | grep -E "Wake lock|Job |Job:|Alarm|Wakeup alarm|Foreground|Cpu|CPU|user=|Total cpu|Sync|Sensor" | sed 's/^ *//' | head -30
echo "---- jobs"
adb -s $S shell dumpsys jobscheduler | grep -E "^\s*JOB #u0a[0-9]+/[0-9]+: .*$PKG|Periodic:|Required|Minimum latency|Run time|Last run" | grep -B1 -A4 "$PKG" | grep -vE "^--$" | sed 's/^ *//' | head -40
echo "---- alarms"
adb -s $S shell dumpsys alarm | grep -B2 -A3 "$PKG" | sed 's/^ *//' | head -20
adb -s $S shell input keyevent KEYCODE_WAKEUP; adb -s $S shell wm dismiss-keyguard >/dev/null 2>&1
adb -s $S shell dumpsys battery reset >/dev/null
