---
name: wallpaper-platform-contract
description: The verified Android facts fengine depends on (WallpaperManager flags and permissions, photo picker grants, WorkManager and AlarmManager limits, Android 14+ restrictions). Load before touching WallpaperApplier, RotationScheduler, the manifest, or when a reviewer asks "is this allowed on Android". Points to the evidence; does not replace it.
---

# Wallpaper platform contract

Verified 2026-10-13 against `~/Library/Android/sdk/sources/android-37.2/android/app/WallpaperManager.java`
and the official pages listed in `docs/research/android-wallpaper-audit.md`.
When in doubt, re-grep the source; do not answer from memory.

## Setting

| Fact | Where |
|---|---|
| `FLAG_SYSTEM = 1`, `FLAG_LOCK = 2`, combinable | WallpaperManager.java:444-449 |
| `setBitmap(Bitmap, Rect, boolean, int which)` requires `SET_WALLPAPER` (normal permission) | :2462-2466 |
| Returns new wallpaper id, 0 on failure | javadoc at :2455 |
| `clear(FLAG_LOCK)` makes home show on lock; `clear(FLAG_SYSTEM)` leaves lock alone | :3350-3368 |
| `isWallpaperSupported()`, `isSetWallpaperAllowed()` (false under `DISALLOW_SET_WALLPAPER`) | :3277, :3297 |
| `getDrawable`/`getBitmap` throw `SecurityException` for third-party apps on Android 14+ | :1039-1057 |
| `setWallpaperDimAmount`, `setWallpaperComponent` are system API | :3016-3027 |

## Foldables and motion

| Fact | Where |
|---|---|
| `setBitmapWithCrops`, `setStreamWithCrops`, `getBitmapCrops`, `getOrientation`, `ORIENTATION_SQUARE_*` are `@TestApi`/`@FlaggedApi(FLAG_MULTI_CROP)`; not callable by apps | WallpaperManager.java:371-408, :1653, :2498 |
| `setBitmapWithDescription` is `@SystemApi` | :2520-2523 |
| Public static path: one `visibleCropHint`, system default crop for other display sizes | :2486-2496 |
| Live engine public hooks: `onSurfaceChanged` :907, `onOffsetsChanged` :842, `onZoomChanged` :952, `onWallpaperFlagsChanged` :941 (API 34) | WallpaperService.java |
| `shouldZoomOutWallpaper` is `@Hide`; handle zoom in `onZoomChanged` | :685 |

## Samsung One UI (verified on Galaxy Z Fold 8, One UI 9)

| Fact | Where |
|---|---|
| `which` display bits: `FLAG_DISPLAY_PHONE=4` inner, `FLAG_DISPLAY_SUB=16` cover, `FLAG_DISPLAY_DEX=8`, `FLAG_MODE_MASK=60` | decompiled `android.app.WallpaperManager` from the device, audit section 5 |
| No bits -> service picks the active screen from the hinge (`SubDisplayMode.getFolderStateBasedWhich`) | decompiled services.jar |
| Explicit bits are honoured for third-party callers; `which=7` and `which=19` both accepted | logcat `WALLPAPER_SVC` lines, audit section 5 |
| Flip cover (watchface) rejects lock-only writes | `SemWallpaperManagerService.isSupportingMode` |

## Reading photos

- Photo picker: no permission. Grants are temporary until
  `takePersistableUriPermission`; 5,000 persisted grants max, oldest evicted.
- Never add `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED` or
  `READ_EXTERNAL_STORAGE` to this app. Folder tracking is a separate decision.

## Triggering

- WorkManager periodic: 15 minute floor, inexact, survives reboot.
- `AlarmManager.setAndAllowWhileIdle`: inexact, fires in Doze, dies on reboot;
  `BootReceiver` re-arms. No permission.
- Exact alarms need `SCHEDULE_EXACT_ALARM` (user-granted, revocable) or
  `USE_EXACT_ALARM` (alarm/calendar apps by Play policy). Not used.
- `ACTION_USER_PRESENT` is not delivered to manifest receivers of a background
  app. On-unlock rotation would need a foreground service or live wallpaper.

## Decode budget

Downsample on decode so the short edge covers the display's long edge
(`ImageDecoder.setTargetSampleSize`). A full 12 MP decode is ~50 MB and will be
killed in a worker on low-memory devices.
