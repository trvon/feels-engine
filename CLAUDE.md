# Feels Engine: Claude Code project instructions

Feels Engine (package `dev.fengine.wallpaper`): Kotlin Android app (Jetpack
Compose, Material 3, WorkManager, DataStore) that rotates the home and lock
screen wallpaper, and plays a screen saver, from groups of photos: picked with
the system photo picker or found on the device from a description.

`AGENTS.md` holds the full workflow, platform contract and device loop. This
file holds what Claude needs on every turn.

## Build and test

```bash
./gradlew :app:testDebugUnitTest      # JVM tests: examples + jqwik invariant properties
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew :app:lintDebug              # lint before every PR
source docs/local/devices.sh && adb -s $PHONE install -r -t app/build/outputs/apk/debug/app-debug.apk   # ask first
```

No standalone `gradle`, `kotlinc` or Android Studio on this machine. Use the
wrapper. JDK 25 is the default `java`; JDK 17 and 21 are installed if Gradle
or AGP refuse 25.

## Rules that come up most

- `SET_WALLPAPER` writes wallpapers. Picked groups need no media permission
  (picker + persisted grants). `READ_MEDIA_IMAGES` is requested only when a
  described group looks for photos (owner decision 2026-10-13). Never add a
  network permission; labeling and Gemini Nano run on the phone.
- Scheduling is inexact by design (WorkManager periodic, `setAndAllowWhileIdle`).
  Do not add `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`.
- Pure scheduling logic lives in `schedule/RotationPlan.kt` and stays free of
  Android types so it is JVM-testable.
- Owner priorities: foldables first-class, Apple-like motion. The static
  `setBitmap` path is v1; motion and per-posture crops need the live wallpaper
  engine (audit doc section 4). Do not add those features to the static path.
- Samsung foldables need the display bits (4 inner, 16 cover) on `which` or
  only the active screen changes. `SamsungDisplays.expand` handles it; keep it.
- Changing a device's wallpaper is visible and cannot be undone by the app
  (third-party apps cannot read the current wallpaper back on Android 14+).
  Ask before running a rotation on a device. Device serials live in
  `docs/local/devices.sh` (ignored by git).

## Key paths

```
app/src/main/kotlin/dev/fengine/wallpaper/
  MainActivity.kt            two screens, one nav state (home <-> group)
  ui/HomeScreen.kt           editorial home: status sentence, group covers, rhythm, one action
  ui/GroupScreen.kt          group: rename headline, shows-on chips, thumbnail grid
  ui/design/Tokens.kt, Components.kt, Layout.kt   the design language (docs/design-system.md)
  ui/Theme.kt, ui/Format.kt  Material You + serif headlines; schedule/status copy
  ui/MainViewModel.kt        group CRUD, assignment, persisted URI grants, scheduler sync
  data/Group.kt              Group(id, name, photos, cursor) with JSON codec
  data/PhotoId.kt            one identity for picker and library URIs of the same photo (tested)
  data/Settings.kt           groups, homeGroupId, lockGroupId, Mode, schedule
  data/SettingsStore.kt      DataStore file; data/SettingsCodec.kt is the pure prefs mapping (property-tested)
  smart/                     described groups: MediaLibrary, Labeler (ML Kit), PhotoIndex (SQLite,
                             labels once per photo + verdicts), IndexLibraryWorker (whole library, sliced),
                             DescriptionMatcher (NanoMatcher, KeywordMatcher), SmartGroupWorker (I/O),
                             ScanPlan (pure candidate order, budgeted pass, note; property-tested)
  wallpaper/WallpaperApplier.kt   decode + per-display crop + WallpaperManager.setBitmap(which)
  screensaver/GroupDreamService.kt   Android Screen saver (DreamService) playing a group; KenBurns.kt and
                             DreamPlan.kt are the pure drift maths and play order (property-tested)
  wallpaper/WallpaperCrop.kt, Displays.kt   pure crop maths (property-tested), panel sizes
  wallpaper/SamsungDisplays.kt    One UI display bits (inner 4, cover 16), tested
  ui/PhotoThumbnail.kt            thumbnail grid tile via ContentResolver.loadThumbnail
  schedule/RotationPlan.kt   steps(home, lock) + next-index / next-time arithmetic (tested)
  schedule/RotationScheduler.kt   WorkManager periodic vs AlarmManager inexact
  schedule/RotateWallpaperWorker.kt, TimeOfDayAlarmReceiver.kt, BootReceiver.kt
docs/research/android-wallpaper-audit.md   availability survey + API audit
docs/product/2026-10-13-product-review.md  graded evidence, ranked backlog, research ledger
.claude/agents/              android-platform-researcher, device-verifier
.claude/skills/              kotlin-kiss, wallpaper-platform-contract, device-loop
```
