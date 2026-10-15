#!/bin/zsh
# setlive.sh <serial>: open the system live wallpaper preview for the engine and apply it to both screens.
S=$1
dump(){ adb -s $S shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb -s $S shell cat /sdcard/ui.xml; }
find1(){ python3 -I -c "
import sys,re
x=sys.stdin.read(); pat=sys.argv[1]
m=re.search(r'(?:text|content-desc)=\"('+pat+r')\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x)
print((int(m.group(2))+int(m.group(4)))//2,(int(m.group(3))+int(m.group(5)))//2) if m else print('')" "$1"; }
tap(){ local c=""; for i in $(seq 1 10); do c=$(dump | find1 "$1"); [ -n "$c" ] && break; sleep 1; done; echo "tap /$1/ at $c"; [ -n "$c" ] && adb -s $S shell input tap $c; sleep ${2:-2}; }
adb -s $S shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT dev.fengine.wallpaper/.wallpaper.live.PhotoWallpaperService >/dev/null 2>&1; sleep 4
adb -s $S shell dumpsys activity activities | grep -oE "topResumedActivity=ActivityRecord\{[^ ]+ u0 [^ ]+" | head -1
dump | grep -oE 'text="[^"]{2,40}"' | head -12
tap "Set wallpaper|SET WALLPAPER|Apply|Set" 2
dump | grep -oE 'text="[^"]{2,60}"' | head -8
tap "Home screen and lock screen|Home and lock screens|Both|Home screen & lock screen" 3
adb -s $S shell dumpsys wallpaper | grep -E "mWallpaperComponent|mWhich=" | head -4
