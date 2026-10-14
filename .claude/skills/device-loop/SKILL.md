---
name: device-loop
description: How to prove a fengine change on the attached Pixel 8 (install, launch, trigger a rotation, read dumpsys wallpaper and logcat, screenshot). Load when a change touches WallpaperApplier, the scheduler, receivers or the manifest and needs on-device evidence. Installing and rotating are ask-first actions.
---

# Device loop

Pixel 8 `$PHONE` and Galaxy Z Fold 8 `$FOLD`, both Android 17
(API 37), app id `dev.fengine.wallpaper`. On the Fold: Auto Blocker must be off
(owner toggles it, 30 minute window), screenshots need the physical display id
(`-d $FOLD_COVER_DISPLAY` cover, `-d $FOLD_INNER_DISPLAY` inner), and the
hinge state is `adb shell cmd device_state print-state` (0 closed, 3 open).
Ask before installing. Ask separately before running a rotation: the app cannot
put the owner's previous wallpaper back.

```bash
D=$PHONE
./gradlew :app:assembleDebug :app:installDebug
adb -s $D shell am start -n dev.fengine.wallpaper/.MainActivity
adb -s $D shell dumpsys wallpaper | grep -E 'mWhich|id='        # before
adb -s $D logcat -c
# ... drive the UI or trigger the worker ...
adb -s $D shell dumpsys wallpaper | grep -E 'mWhich|id='        # after: id changes
adb -s $D logcat -d -s RotateWallpaperWorker WallpaperApplier WallpaperManagerService
adb -s $D exec-out screencap -p > /tmp/fengine.png
```

Tapping reliably:

```bash
adb -s $D shell uiautomator dump /sdcard/ui.xml && adb -s $D pull /sdcard/ui.xml /tmp/ui.xml
grep -o 'text="Change now"[^>]*bounds="[^"]*"' /tmp/ui.xml   # then tap the centre of the bounds
```

Triggering without waiting:

- Periodic worker: `adb shell dumpsys jobscheduler | grep -A3 dev.fengine.wallpaper`
  to find the job id, then `adb shell cmd jobscheduler run -f dev.fengine.wallpaper <id>`.
- Time-of-day alarm: set a slot one or two minutes ahead in the UI, or
  `adb shell dumpsys alarm | grep -A4 dev.fengine.wallpaper` to confirm it is armed.
- "Change now" button in the UI runs the same worker as a one-shot.

Report observations with the exact output lines. A screenshot of the lock screen
needs `adb shell input keyevent KEYCODE_SLEEP` then `KEYCODE_WAKEUP` first.
