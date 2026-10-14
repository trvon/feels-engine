# Build

Single Gradle module. AGP 9.4.1 with its built-in Kotlin, Compose plugin
2.4.21, compileSdk 37, minSdk 28, Gradle wrapper 9.6.0. JDK 17 or newer; the
wrapper is the only supported entry point.

```bash
./gradlew :app:testDebugUnitTest   # JUnit 4 examples + jqwik properties (JUnit platform)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
```

The debug APK is about 82 MB because the ML Kit labeling model and the Prompt
API client are bundled.

## Install on a device

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

Installing does not change the wallpaper. Running a rotation does, and the
app cannot put the previous wallpaper back (Android 14+ does not let apps read
it). Use a test device or expect to reset the wallpaper by hand.

## Tests

`app/src/test/kotlin/dev/fengine/wallpaper/`:

- `schedule/`, `smart/`, `wallpaper/`, `ui/`: example tests.
- `invariants/`: jqwik properties for crop maths, rotation arithmetic, codecs,
  layout, the settings file, the scan pass, photo identity and the screen
  saver drift. A failing property prints a shrunk sample and a seed; replay
  with `@Property(seed = "...")`.
- `ui/DesignLanguageTest`: fails on raw dp values in screens and on
  thumbnail loading outside `ui/design/`.

Android classes (`SettingsStore`, workers, `WallpaperApplier`,
`GroupDreamService`) are thin over the pure cores and are verified on
devices, not in JVM tests.

## Device loop

Drive the UI from `uiautomator dump` bounds, never guessed coordinates:

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml
adb shell input tap <x> <y>
adb logcat -s WallpaperApplier RotateWallpaperWorker SmartGroupWorker IndexLibraryWorker NanoMatcher GroupDreamService
adb shell dumpsys wallpaper | grep -E "mWhich|mCropHint"
```

A rotation logs one line per `setBitmap` call with the target panel size and
the crop; `dumpsys wallpaper` echoes the crop the system kept.

## Samsung foldables

Samsung adds display bits to `which`: 4 inner, 16 cover. Without them only the
active screen changes. `SamsungDisplays.expand` adds them; the applier writes
twice, one crop per panel. While the phone is closed the inner panel is only
reachable through `DisplayManager.getDisplay(1)`; `Displays` probes ids and
remembers panel sizes.

Screenshots need the physical display id (`dumpsys display | grep uniqueId`;
keep yours in `docs/local/devices.sh`):

```bash
source docs/local/devices.sh
adb -s $FOLD exec-out screencap -p -d $FOLD_COVER_DISPLAY > cover.png
adb -s $FOLD exec-out screencap -p -d $FOLD_INNER_DISPLAY > inner.png
adb -s $FOLD shell cmd device_state print-state   # 0 closed
```

Samsung Auto Blocker must be off for adb installs.

## Screen saver

`cmd dreams start-dreaming` needs root. Use the Settings preview instead:

```bash
adb shell settings put secure screensaver_components dev.fengine.wallpaper/.screensaver.GroupDreamService
adb shell settings put secure screensaver_enabled 1
adb shell am start -a android.settings.DREAM_SETTINGS   # then tap Preview
adb shell dumpsys dreams | grep mCurrentDream
```

Delete the two settings afterwards to restore the previous screen saver.

## Test photos

Both development phones keep their libraries in Google Photos cloud, so the
local MediaStore is nearly empty. Push synthetic images and scan them:

```bash
python3 scripts/make-test-photos.py out/ 1500
adb push out/. /sdcard/Pictures/fengine-bulk/
adb shell 'for f in /sdcard/Pictures/fengine-bulk/*.jpg; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f"; done'
```

On the Pixel 8 that library indexed at about 90 ms per photo.
