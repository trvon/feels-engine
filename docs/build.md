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
SERIAL=emulator-5580  # explicitly select the approved target
adb -s "$SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk
```

Installing an update force-stops the package and can detach an active live
wallpaper. Instrumentation also force-stops it at start and finish. On the
API 36 emulator, reinstall/instrumentation left Home on system ImageWallpaper
and the app's engine on Lock only.
A rotation changes the photo too. Android 14+ prevents the app from reading
and restoring the previous wallpaper. Use a disposable emulator; ask before
installing or instrumenting on a user's active-wallpaper device.

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
`GroupDreamService`, `PhotoWallpaperService`) are thin over the pure cores.
`app/src/androidTest/` covers the stores and Settings navigation/system bars.
Store tests replace groups; run the full suite only on disposable test data:

```bash
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest
```

To exclude store writes, select only the Settings cases:

```bash
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.fengine.wallpaper.SettingsUiTest
```

Class selection does not prevent the runner's wallpaper-affecting force-stop.

### Lifecycle states

`LifecycleFlowTest` and `LifecycleFlowProperties` exercise settings, rotation
and rendering together. They cover cursor reconstruction, hidden updates,
selected versus running engines, lost surfaces and interrupted fades.
Preference round trips are in memory; Android process death is not simulated.

The emulator checks remain separate:

| State | Record |
| --- | --- |
| UI foreground/background | Resumed activity, selected wallpaper component, engine visibility |
| Process death/restart | PID change, persisted cursor, service recreation and first frame |
| Force-stop/relaunch | Package stopped flag, wallpaper selection and trigger registrations before relaunch |
| Screen off/on, lock/unlock | Display power and lock state, visibility/surface callbacks and restored frame |
| Doze, low battery, background restriction | System state and periodic job constraints, then delivery after restrictions lift |
| Reboot | Saved settings, periodic work and time-of-day alarm registration |
| Static/live selection | Per-screen component and static writes; a PID alone does not establish ownership |

Use an approved disposable emulator and synthetic photos. Pass its verified
serial to every device command. Installing, seeding and selecting wallpaper
change its state. Record initial power/restriction settings and restore them.

`am kill` can leave a bound wallpaper process alive: require an observed PID
change before calling a case process death. Do not launch the app, send a debug
broadcast or run instrumentation before recording an unstimulated restart or
force-stop state. Those actions can start or stop the process being measured.

Manual Next uses unconstrained one-shot work; automatic interval rotation
requires battery-not-low. A successful manual rotation or forced job does not
prove automatic work runs under battery constraints or in Doze. Scheduling is
inexact; registration and actual delivery are separate observations.

### Coverage

```bash
./gradlew :app:createDebugUnitTestCoverageReport
open app/build/reports/coverage/test/debug/index.html
```

Measured 2026-10-15: 18% of lines overall. The pure modules are at or near
100% (`LiveEngineState`, `LiveFrame`, `RotationPlan`, `SettingsCodec`,
`Group`, `ScanPlan`, `KenBurns`, `WallpaperCrop`, `Screens`, `DreamGate`);
everything that calls Android is at 0% (services, workers, view model,
screens). Both engine bugs shipped in 0.2.0 sat in that gap: the state
machine was fully covered, but One UI delivered callbacks the model did not
include (no visibility on open, a recreated surface). Rules that follow:

- A decision goes in a pure object with a property; the Android class only
  reads inputs and performs the answer (`DreamGate`, `Screens`,
  `RotationPlan`, `LiveEngineState`).
- A framework behaviour seen on a device becomes an event in the property
  harness (`Lost`, `Redraw` in `LiveEngineStateProperties`), and the property
  is checked to fail without the fix before it is committed.
- Line coverage is a map of where to look, not a score: it says nothing about
  events the model leaves out.

### Version matrix

Old phones take paths new ones never do. Four emulators cover minSdk to
current: Android 9 (28), 11 (30), 13 (33), 14 (34), plus the API 36 `fe_phone`.

```bash
sdkmanager "system-images;android-28;google_apis;arm64-v8a"   # and 30, 33, 34
avdmanager create avd -n fe_api28 -k "system-images;android-28;google_apis;arm64-v8a" -d pixel_5
./gradlew :app:assembleDebug
scripts/smoke-matrix.sh fe_api28 fe_api30 fe_api33 fe_api34   # results in build/smoke/summary.txt
```

Each run boots the emulator headless, opens the app, seeds a group from the
library (debug `SEED` broadcast), opens the app again with data, rotates,
labels and scans, applies the live engine, rotates over it, starts the screen
saver, checks the crash buffer and runs the on-device tests. About ten minutes
per version. `.github/workflows/device-tests.yml` runs the on-device tests on
API 28, 30, 33 and 36 weekly. `docs/research/api-inventory.md` lists every
framework API, its first version and the fallback.

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

## Live engine

```bash
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER \
  --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT dev.fengine.wallpaper/.wallpaper.live.PhotoWallpaperService
adb shell dumpsys wallpaper | grep -E "mWallpaperComponent|mWhich="
adb shell am broadcast -a dev.fengine.wallpaper.ROTATE_NOW -p dev.fengine.wallpaper   # debug builds: one rotation
adb logcat -s PhotoWallpaper   # one line per frame, one per fade
scripts/profile-live.sh <serial> 30
```

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
