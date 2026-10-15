# Android API inventory

Every framework API Feels Engine depends on, the first Android version that
has it, and what the app does on older phones. minSdk is 28 (Android 9).
Levels come from the SDK's `platforms/android-37/data/api-versions.xml`, not
memory. Lint (`NewApi`) blocks unguarded calls; this file covers what lint
cannot: behaviour, fallbacks, and how each one is checked.

Regenerate the "newer than minSdk" list with
`python3 -I scripts/api-inventory.py` (heuristic; overloads such as
`getWallpaperInfo(int)` need the version branch list below).

## Wallpaper

| API | From | Used in | Older phones | Checked by |
|---|---|---|---|---|
| `WallpaperManager.setBitmap(bitmap, crop, backup, which)` | 24 | `WallpaperApplier` | always present | smoke `rotate` |
| `WallpaperManager.isWallpaperSupported`, `isSetWallpaperAllowed` | 23, 24 | `WallpaperApplier` | always present | smoke `rotate` |
| `WallpaperManager.FLAG_LOCK`, `getWallpaperId(which)` | 24 | `LiveWallpaper.state` | always present | `PlatformProperties.lockScreenRule` |
| `WallpaperManager.getWallpaperInfo()` | 7 | `LiveWallpaper.state` (home) | before Android 14 it needs `QUERY_ALL_PACKAGES` (javadoc). Android 9 and 11 answer anyway; Android 13 returns null even for the app's own engine, so the rotation wrote a still photo over it and Look showed Still. Below 14 a running, non-preview engine in this process also counts as live (`LiveEngines`, `Platform.homeIsLive`); `QUERY_ALL_PACKAGES` is not requested (Play restricts it) | `PlatformProperties.homeScreenRule`, smoke `live-rotate` on API 33 |
| `WallpaperManager.getWallpaperInfo(which)` (per screen) | 34 | `LiveWallpaper.state` | not asked; lock is live when home is live and the lock screen has no still wallpaper of its own (`Platform.lockIsLive`) | `PlatformProperties.lockScreenRule`, smoke `live-rotate` |
| Samsung display bits on `which` (4 inner, 16 cover) | One UI | `SamsungDisplays`, `LiveWallpaper.state` | gated on `hasDisplayBits()`; AOSP throws on them | `SamsungDisplaysTest`, Fold 8 |
| `ACTION_CHANGE_LIVE_WALLPAPER` + component extra | 16 | Look: Motion | always present; picker UI differs per version | smoke `live` |
| `WallpaperService.Engine` visibility, surface, offsets | 7-15 | `PhotoWallpaperService` | always present | `LiveEngineStateProperties` |
| `Engine.onSurfaceRedrawNeeded` | 9 | `PhotoWallpaperService` | always present; treated as visibility (One UI may skip `onVisibilityChanged`) | `redrawRequestAlwaysEndsInAFrame` |
| `Engine.onZoomChanged` | 30 | `PhotoWallpaperService` | never called; zoom stays 0 | `LiveFrameProperties` (zoom 0 covers the screen) |
| `Engine.getWallpaperFlags`, `onWallpaperFlagsChanged` | 34 | `PhotoWallpaperService.photoFor` | treated as home; one engine covers both screens | smoke `live` |
| `Engine.getDisplayContext` (+ `Context.getDisplay`) | 29 (30) | engine log line only | omitted | lint |
| `SurfaceHolder.lockHardwareCanvas` | 26 | `PhotoWallpaperService.draw` | always present; software canvas after two stalls | device profile |

## Photos

| API | From | Used in | Older phones | Checked by |
|---|---|---|---|---|
| Photo picker (`PickVisualMedia`) | 33; 19-32 via Play services backport | Add photos | backport declared in the manifest (`ModuleDependencies`); without Play services it opens the document picker | smoke on 28/30 is `google_apis` (no Play Store): document picker path |
| `SQLiteOpenHelper` as `AutoCloseable` | 29 | `PhotoIndex(...).use { }` | `PhotoIndex` implements `java.io.Closeable` itself; before that, Android 9 threw ClassCastException (found by the version matrix, lint does not check it) | `OldAndroidContractsTest`, `androidTest` on API 28 |
| `takePersistableUriPermission` | 19 | `MainViewModel.addPhotos` | always present | `androidTest` |
| Persisted grant cap | 128 below 30, 512 from 30 | grant warning | `Platform.grantCeiling` | `PlatformProperties.grantWarningComesBeforeTheCeiling` |
| `MediaStore.getPickImagesMaxLimit` | 33 | picker size | 100 | lint |
| `ImageDecoder` (`createSource`, `setTargetSize`) | 28 | every decode | always present at minSdk | smoke `rotate`, `live` |
| `ContentResolver.loadThumbnail` | 29 | thumbnails | small decode instead | lint, Android 9 smoke |
| `MediaStore` `RELATIVE_PATH` column | 29 | `MediaLibrary.newest` | `_data` instead (`Platform.folderColumn`); naming `relative_path` on Android 9 fails the query | `PlatformProperties.queriesOnlyColumnsThatExist`, smoke `scan` on API 28 |
| `READ_MEDIA_IMAGES` | 33 | descriptions | `READ_EXTERNAL_STORAGE` (maxSdkVersion 32) | smoke `scan` |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 34 | descriptions | not requested | smoke on 34 |

## Scheduling, screen saver, system

| API | From | Used in | Older phones | Checked by |
|---|---|---|---|---|
| WorkManager periodic + `setRequiresBatteryNotLow` | library (14+) | `RotationScheduler` | same | `RotationPlanProperties`, wake profile |
| `AlarmManager.setAndAllowWhileIdle` | 23 | time-of-day rhythm | always present | `RotationPlanTest` |
| `DreamService` (`finish`, `setInteractive`) | 17 | `GroupDreamService` | always present; `dreamCategory` meta-data ignored below 33 | smoke `dream` |
| `cmd dreams start-dreaming` (test only) | shell, newer releases | smoke script | `Somnambulator` activity fallback | smoke `dream` |
| `UiModeManager.getCurrentModeType` (car) | 8 | `DreamGate` | always present | `DreamGateProperties` |
| `BatteryManager.EXTRA_PLUGGED`, `BATTERY_PLUGGED_WIRELESS` | 5, 17 | `DreamGate` | always present | `DreamGateProperties` |
| `BatteryManager.BATTERY_PLUGGED_DOCK` (8) | 33 | `DreamGate` | docks report as AC/USB below 33, so "wireless or dock only" means wireless only there | documented |
| `DisplayManager.getDisplays`, `getDisplay(id)` | 17 | `Displays` | always present | Fold 8 |
| Dynamic colour (Material You) | 31 | `Theme` | static scheme | lint |
| `enableOnBackInvokedCallback` | 33 | manifest | ignored | lint warning |
| Gemini Nano (ML Kit GenAI Prompt, AICore) | device list, recent phones | descriptions | ML Kit labels only (`AiReadiness`) | `AiReadinessTest`, smoke `scan` |
| ML Kit image labeling | 21 | descriptions | always present | smoke `scan` |

## Version branches in the code

Each `Build.VERSION.SDK_INT` check is a path old phones take. Decisions that
depend on the version live in `data/Platform.kt` and are property-tested for
every level from 28 to 37.

```bash
grep -rn "SDK_INT" app/src/main
```

## Version matrix results (2026-10-15)

`scripts/smoke-matrix.sh` on `google_apis` arm64 emulators, debug build.
Every step passed on every version after the fixes below.

| Step | 9 (28) | 11 (30) | 13 (33) | 14 (34) |
|---|---|---|---|---|
| open, seed, open with data | pass | pass | pass | pass |
| rotate (static) | pass | pass | pass | pass |
| label + describe scan | pass | pass | pass | pass |
| live engine applied | pass | pass | pass | pass |
| rotation leaves the engine alone | pass | pass | pass | pass |
| screen saver plays | pass | pass | pass | pass |
| no crashes | pass | pass | pass | pass |
| on-device tests (5) | pass | pass | pass | pass |

Found by the matrix and fixed:

- Android 9: the library query named `relative_path` (API 29); the provider
  answered "no such column" and descriptions found nothing. Now
  `Platform.folderColumn`.
- Android 9: `PhotoIndex(...).use { }` threw ClassCastException
  (`SQLiteOpenHelper` is `AutoCloseable` only from API 29); it ran when the
  app showed labeling progress. `PhotoIndex` now implements `Closeable`.
- Android 13: `getWallpaperInfo()` returned null for our own running engine
  (it needs `QUERY_ALL_PACKAGES` before 14), so the first rotation wrote a
  still photo over the engine and Look showed Still. Running engines now
  count (`LiveEngines`).

Harness lessons, now in the script: a fresh install is "stopped" until
opened (broadcasts need `-f 32`); dream shell commands need `adb root`;
Android 11 ignores the shell's scan-file broadcast (use MediaProvider
`scan_volume`); a stale emulator lock makes boot fail, so boot has a timeout.
Not settled: on Android 11 the seed sometimes saw fewer than the 8 pushed
photos while the scanner was still working; the app behaved correctly with
what the library held.

