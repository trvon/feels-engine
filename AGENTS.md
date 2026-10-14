# Agent workflow

How any agent (or person) works in this repo: rules, layout, the platform
contract, the device loop. `CLAUDE.md` is the short version Claude reads every
turn. `docs/research/android-wallpaper-audit.md` is the evidence behind the
design; `docs/architecture.md` is the map.

## Rules

Do without asking:

- Read and search files, run unit tests, lint and `assembleDebug`.
- Read the local SDK source (`$ANDROID_HOME/sources/android-*/`) and
  `adb shell dumpsys wallpaper` to answer platform questions.
- Add or update tests next to the code they cover.

Ask first:

- Installing on a device, and anything that changes its wallpaper. The app
  cannot restore the previous wallpaper (Android 14+ blocks reading it back).
- Adding a dependency or a permission.
- Deleting user data on a device.
- Changing a user's screen saver setting (`screensaver_components`).

Never:

- Add a network permission or send photos off the device. Labeling and Gemini
  Nano run on the phone only.
- Widen media access. Picked groups use the photo picker plus
  `takePersistableUriPermission` and need no permission; `READ_MEDIA_IMAGES`
  (and the Android 14 partial grant) is requested only when a described group
  looks for photos.
- Add exact-alarm permissions. Timing is inexact by design.
- Call Gemini Nano per photo without the label ordering, or outside the
  budgets (`IndexLibraryWorker.SLICE`, `TIME_BUDGET_MS`,
  `SmartGroupWorker.LABEL_BUDGET`, `JUDGE_BUDGET`) that keep a run inside
  WorkManager's limit.
- Count a Gemini Nano refusal as a match. `ScanPlan.pass` hands it to the
  labels and does not cache it.
- Commit `local.properties`, keystores, signing config or `docs/local/`.

Commits follow Conventional Commits with a scope (`feat(schedule): ...`). The
body says why.

## Layout

```
app/src/main/kotlin/dev/fengine/wallpaper/
  ui/           screens, view model, design/ (tokens, components, layout)
  data/         Settings, Group, PhotoId, SettingsCodec (pure), SettingsStore
  schedule/     RotationPlan (pure), RotationScheduler, workers, receivers
  wallpaper/    WallpaperCrop (pure), Displays, SamsungDisplays (pure), WallpaperApplier
  smart/        MediaLibrary, Labeler, PhotoIndex, DescriptionMatcher, ScanPlan (pure),
                SmartGroupWorker, IndexLibraryWorker, AiReadiness
  screensaver/  KenBurns (pure), DreamPlan (pure), GroupDreamService
app/src/test/kotlin/dev/fengine/wallpaper/   examples + invariants/ (jqwik)
docs/           architecture, build, privacy, design system, roadmap, research, product
docs/local/     your devices and notes; ignored except the README and examples
scripts/        make-test-photos.py
```

Pure logic (no Android imports) lives in the files marked pure and gets a
jqwik property in `invariants/`. The Android classes around them do I/O and
stay thin. UI uses `ui/design/` tokens and components; `DesignLanguageTest`
fails the build on raw dp values in screens. Build details: `docs/build.md`.

## Platform contract

Verified against API 37 sources and on devices; file and line references are
in the audit.

- `WallpaperManager.setBitmap(bitmap, cropHint, false, which)` with
  `FLAG_SYSTEM | FLAG_LOCK` sets both screens. Needs `SET_WALLPAPER` (normal
  permission). Returns 0 on failure. Third-party apps cannot read the current
  wallpaper back (Android 14+) and cannot dim it.
- Per-display crop APIs (`setBitmapWithCrops`, `ORIENTATION_SQUARE_*`) are
  test or system API. The public path gets the system's crop per display; the
  app computes its own crop hint per panel instead.
- Samsung One UI adds display bits to `which`: 4 inner, 16 cover (8 DeX, 32
  virtual). Without them only the active screen changes.
  `SamsungDisplays.expand` sends both; the applier writes one crop per panel.
  While a Samsung foldable is closed the inner panel is reachable only through
  `DisplayManager.getDisplay(id)`; `Displays` probes ids and remembers panels.
- Photo picker: 100 photos per pick (`MediaStore.getPickImagesMaxLimit`),
  512 persisted grants per app, oldest dropped. Cloud items the picker shows
  are not enumerable by apps. A large library must be on the phone and read
  with `READ_MEDIA_IMAGES`, which has no cap.
- `ContentResolver.loadThumbnail` fails for Google Photos cloud picker URIs;
  decode a small copy instead (`PhotoImage` does).
- WorkManager periodic floor: 15 minutes. Inexact alarms fire in Doze within
  the batching window. Alarms die on reboot; `BootReceiver` re-arms them.
- Gemini Nano (ML Kit GenAI Prompt API) needs a listed device and Google
  AICore from Play; an OEM stub reports UNAVAILABLE. AICore refuses some
  photos on policy grounds with `GenAiException [ErrorCode 4]`.
- Screen saver: `DreamService`, public since API 17. `cmd dreams` needs root;
  test through Settings > Screen saver > Preview.
- Live wallpaper engine hooks that are public: `onSurfaceChanged`,
  `onOffsetsChanged`, `onZoomChanged`, `onWallpaperFlagsChanged` (API 34).
  Motion and per-posture crops belong there, not on the static path.

## Product direction

Foldables are first-class. Motion where the platform allows. Described groups
on device. Editorial UI: one sentence of status, photos over controls, one
primary action per screen, no ids or file names in copy. Order of work:
features and UX, then the invariant suite, then profiling. The ranked backlog
and research ledger are in `docs/product/`; add a ledger row before a feature.

## Device loop

Put serials and display ids in `docs/local/devices.sh` and source it. Always
pass `adb -s`; `installDebug` without a serial targets every attached device.
Find buttons with `uiautomator dump` and parse bounds; never guess
coordinates.

```bash
source docs/local/devices.sh
adb -s $PHONE install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s $PHONE shell am start -n dev.fengine.wallpaper/.MainActivity
adb -s $PHONE logcat -s WallpaperApplier RotateWallpaperWorker SmartGroupWorker IndexLibraryWorker NanoMatcher GroupDreamService
adb -s $PHONE shell dumpsys wallpaper | grep -E 'mWhich|mCropHint'
adb -s $FOLD exec-out screencap -p -d $FOLD_COVER_DISPLAY > cover.png
```

Report what was observed (log lines, wallpaper ids, crop hints, screenshots),
never what should have happened. `docs/build.md` has the rest, including
Samsung Auto Blocker, the screen saver preview and test photos.

## Memory (optional)

If `yams` indexes this repo, search it before broad exploration and add
decisions worth keeping. Nothing waits on it.
