# Android wallpaper rotation: availability and API audit

Date: 2026-10-13. Device used for verification: Pixel 8 (`$PHONE`),
Android 17 (API 37), wallpaper picker `com.google.android.apps.wallpaper` v17.
SDK source consulted: `~/Library/Android/sdk/sources/android-37.2/android/app/WallpaperManager.java`.

## 1. Does this already exist?

| Where | Own photos? | Lock + home? | Schedule | Status |
|---|---|---|---|---|
| Pixel "Wallpaper & style" Daily wallpaper | No. Google's help page: "Daily wallpaper doesn't work with your personal photos or live wallpapers." | n/a | daily | shipped |
| Pixel "Photo shuffle" | Yes (albums of people/pets, maybe more) | unclear | hourly, daily, sunrise/sunset, on tap | found behind flags in Android 17 QPR1 beta (June 2026). Not in the picker on this device: `aapt2 dump strings` on the installed picker APK has no "shuffle" string. |
| Google Photos live wallpaper (2020) | Whole library at random, no album choice | home, or home+lock | on screen off/on | shipped, limited |
| Samsung One UI, Settings > Wallpaper > Gallery | Yes, up to 15 images | lock only rotates; home takes one image | per unlock | shipped on Galaxy only |
| Samsung Dynamic Lock screen | No, Samsung packs | lock | every two weeks | shipped on Galaxy only |
| Paperize (GPL-3, Kotlin/Compose, Room, Hilt, Coil) | Yes, albums and folders | separate albums per screen | interval | shipped, open source, minSdk 31 |
| Muzei (Apache-2, live wallpaper) | Yes, gallery source | live wallpaper covers both | 15 min to 3 days | shipped, open source |
| Casualis, Walpy, Wallpaper Changer, Tapet | varies | varies | interval | Play Store |

Conclusion: no stock Android feature does this with the user's own photos on
both screens today. Pixel is about to ship something close (Photo shuffle),
which is a market-timing risk, not a technical blocker. Third-party apps prove
the approach works. Paperize is the closest reference and worth reading before
designing albums or per-screen schedules.

## 2. Can a third-party app do it? Yes.

### Setting the wallpaper

Verified in the API 37 source:

- `WallpaperManager.FLAG_SYSTEM = 1`, `FLAG_LOCK = 2` (both since API 24).
- `setBitmap(Bitmap, Rect cropHint, boolean allowBackup, int which)` and
  `setStream(InputStream, Rect, boolean, int which)` carry
  `@RequiresPermission(SET_WALLPAPER)`. `SET_WALLPAPER` is a normal permission:
  declared in the manifest, granted at install, no runtime dialog.
- `which = FLAG_SYSTEM | FLAG_LOCK` writes both screens in one call. Separate
  calls with different bitmaps give different lock and home images.
- Return value is the new wallpaper id, or 0 on failure.
- `clear(which)` semantics: clearing lock makes the home image show on lock;
  clearing home leaves lock alone.
- `isWallpaperSupported()` and `isSetWallpaperAllowed()` (false under the
  `DISALLOW_SET_WALLPAPER` user restriction) are the preflight checks.
- Not available to apps: `setWallpaperDimAmount` (`@SystemApi`),
  `setWallpaperComponent` (system), `getDrawable`/`getBitmap` on Android 14+
  throw `SecurityException` for third-party apps. So the app cannot read the
  current wallpaper back to restore it; it must keep its own history.
- `dumpsys wallpaper` on the Pixel shows the static image path is
  `com.android.systemui.wallpapers.ImageWallpaper` with `mWhich=3`
  (shared home+lock), i.e. exactly what `setBitmap(..., FLAG_SYSTEM|FLAG_LOCK)` produces.

### Reading the user's photos without storage permission

- Android photo picker (`PickMultipleVisualMedia`, androidx.activity 1.7+)
  needs no permission. Native on Android 11+, backported through Play services
  down to API 19, falls back to `ACTION_OPEN_DOCUMENT`.
- Grants are temporary by default (until reboot or process end). Call
  `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` per URI
  for background use. Cap: 512 persisted grants per app
  (`UriGrantsManagerService.MAX_PERSISTED_URI_GRANTS`, oldest evicted by
  create time; corrected 2026-10-14, see section 6). The 5,000 figure in the
  picker docs is the Android 14 partial-access `media_grants` table, a different
  mechanism that `ACTION_PICK_IMAGES` never writes to.
- Android 14 partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) exists only for
  apps that build their own gallery. The picker is the recommended path and is
  what this app uses.
- Whole-album or whole-folder tracking (new photos appear automatically) is
  not possible through the picker. It needs `READ_MEDIA_IMAGES` plus a
  MediaStore query, or a SAF folder grant (`ACTION_OPEN_DOCUMENT_TREE`). Paperize
  takes the folder route. Deferred.

### Triggering the change

| Mode | API | Precision | Permission |
|---|---|---|---|
| Every N minutes | WorkManager `PeriodicWorkRequest` | 15 min floor, inexact, batched in Doze | none |
| At clock times | `AlarmManager.setAndAllowWhileIdle` | inexact, fires in Doze within the batching window | none |
| At exact clock times | `setExactAndAllowWhileIdle` | exact | `SCHEDULE_EXACT_ALARM`, not pre-granted on API 33+ targets, user can revoke; `USE_EXACT_ALARM` is for alarm/calendar apps by Play policy |
| On unlock | `ACTION_USER_PRESENT` | immediate | not deliverable to manifest receivers for a background app; needs a foreground service or a live wallpaper engine |
| Sunrise/sunset | computed locally from a stored lat/long, then an inexact alarm | inexact | location permission only if auto-detected |

Choice: inexact everywhere. A wallpaper does not need exact timing, and exact
alarms cost a user-facing permission plus Play policy review.

### Placement and scaling (added 2026-10-14)

The public `setBitmap` takes one `visibleCropHint`. The app now computes it
with `WallpaperCrop.fit`: the largest rectangle with the target display's
aspect that fits the photo, placed around a focal point (centre today, a
detected subject later). On Samsung foldables the two writes get two crops,
one per panel size from `DisplayManager`. Decode sampling never drops below the
largest display's resolution, so small phones get a sharp image and tablets do
not get an upscaled one. Properties in `invariants/WallpaperCropProperties.kt`
hold for any photo and display size: inside the image, non-empty, maximal,
aspect within one pixel of rounding, focal point contained. Owner direction:
tablets and small phones are explicit test targets; device verification on a
tablet is still pending (none attached).

### Other constraints

- Background decode: a 12 MP photo is ~50 MB as ARGB_8888. The worker
  downsamples on decode (`ImageDecoder.setTargetSampleSize`) to the display's
  long edge.
- Reboot: WorkManager periodic work survives; alarms do not. `BOOT_COMPLETED`,
  `TIME_SET`, `TIMEZONE_CHANGED` receivers re-arm.
- Battery: on Pixel, each rotation is one decode and one binder call. OEM
  battery managers (some Chinese OEMs) may kill WorkManager; out of scope for
  the Pixel-first target.
- Lost grants: when a photo is deleted or its grant evicted the decode throws
  `SecurityException` or `FileNotFoundException`. The worker records the failure
  and the UI should offer to drop the entry.

## 3. What the scaffold in this repo already does

- Groups: named photo sets from the picker (persisted grants), each feeding
  home, lock, both or neither; per-group cursor; migration from the v0.1 list.
- Editorial UI: status sentence, group covers, rhythm chips, one action.
- Mode: interval (WorkManager) or times of day (inexact alarm + boot re-arm).
- Sequential or shuffle order, "Change now", last-result line.
- Unit tests for the scheduling arithmetic (`RotationPlan`).

Not done: thumbnails, per-screen albums, folder tracking, sunrise/sunset,
on-unlock, crop editor, release signing.

## 4. Foldables and Apple-style motion (owner priority, added 2026-10-13)

Owner direction: if this gets built, foldables are a first-class citizen and
Apple-like wallpaper motion (depth, parallax, transitions) should be supported
where the platform allows. This changes the architecture decision.

### What the static path (`setBitmap`) can and cannot do

| Capability | Static bitmap path | Evidence |
|---|---|---|
| Different crop for inner vs outer display | No. `setBitmapWithCrops(Map<Point, Rect>)`, `getBitmapCrops`, `getOrientation(Point)` and `ORIENTATION_SQUARE_*` ("the inner screen of a foldable") are all `@TestApi`/`@FlaggedApi(FLAG_MULTI_CROP)`; `setBitmapWithDescription` is `@SystemApi`. Only the single `visibleCropHint` overload is public, and the javadoc says the system "will apply a default strategy to position the wallpaper for any unspecified screen dimensions". | WallpaperManager.java:371-408, :1653-1655, :2498-2501, :2520-2523 |
| Any animation (crossfade on change, Ken Burns, parallax, depth) | No. A static wallpaper is a bitmap rendered by SystemUI's `ImageWallpaper`; apps cannot animate it. | `dumpsys wallpaper` -> `ImageWallpaper` |
| Works on a foldable at all | Yes, with the system's default center-crop per display. Fine for a v1, not "first-class". | javadoc at :2486-2496 |

### What the live wallpaper path (`WallpaperService.Engine`) offers (all public)

| Hook | What it gives us | Evidence |
|---|---|---|
| `onSurfaceChanged(holder, format, w, h)` | New surface size on fold/unfold and rotation; we re-crop and re-render the right region per posture | WallpaperService.java:907 |
| `onOffsetsChanged(xOffset, yOffset, ...)` | Launcher page offsets: horizontal parallax like iOS home screen | :842 |
| `onZoomChanged(zoom 0..1)` | System-driven zoom on lock<->home transition and app-drawer open: the depth/zoom effect Pixel launchers already drive | :952 |
| `onWallpaperFlagsChanged(which)` + `getWallpaperFlags()` (API 34) | Know whether this engine instance is drawing the lock screen or home; render different photos per screen from one service | :941 |
| `onVisibilityChanged`, `onTouchEvent`, `notifyColorsChanged` | Pause rendering off-screen, optional tap-to-advance, tell SystemUI the palette changed for Material You | :810, :832, :990 |
| `shouldZoomOutWallpaper()` | `@Hide` (Wear only); zoom must be handled manually in `onZoomChanged` | :685 |

Rendering is ours (Canvas or OpenGL/Vulkan on the engine's surface), so crossfade
between photos, slow Ken Burns pan, parallax on offsets, and depth zoom are all
implementable. iOS-style subject-aware depth (text behind the subject) needs
segmentation (ML Kit subject segmentation runs on-device) and is a later step.

Costs of the live path: the engine owns rendering and battery, the user must
pick the app as a live wallpaper (system chooser via
`ACTION_CHANGE_LIVE_WALLPAPER`), lock-screen-only live wallpaper needs Android 14+
(earlier versions mirror home), and Samsung's lock screen can replace live
wallpapers with its own. Muzei is the reference implementation of this path.

### Foldable-aware app UI (independent of the wallpaper path)

Jetpack WindowManager (`androidx.window:window` 1.5.0 is in the local Gradle
cache) exposes `FoldingFeature` (posture, hinge bounds) and `WindowSizeClass`.
The picker and a future crop editor should lay out for compact/expanded width
and tabletop posture. The crop editor must show both the outer and inner
display frames of the current device (sizes from `WindowMetricsCalculator` per
display; on a foldable the app can only measure the display it is on, so store
both as the user folds/unfolds, or ship a small device table).

### Decision

Keep the static path as the working v1 (it exists, it is cheap, and it proves
photo access and scheduling). Treat the live wallpaper engine as the v2
architecture that unlocks the owner's two priorities. Design v1 so the engine
can be dropped in: the album, scheduler and per-screen selection already sit
behind `RotationScheduler`/`WallpaperApplier`; the engine replaces
`WallpaperApplier` with a renderer that reads the same `Settings`.
No test device for the foldable posture is attached (Pixel 8 is a slab);
foldable verification needs a Pixel Fold or the Android Studio foldable AVD.

## 5. Samsung foldables: verified on a Galaxy Z Fold 8 (2026-10-13)

Device: SM-F971U, Android 17 (API 37), One UI 9.0, serial `$FOLD`. Inner
display 2448x1848, cover display 1248x1972. Samsung's framework was pulled from
the device and decompiled with jadx (`/system/framework/framework.jar`,
`/system/framework/services.jar`).

### Finding

A plain `setBitmap(bitmap, null, false, FLAG_SYSTEM | FLAG_LOCK)` changed only
the cover screen while the phone was folded. The owner saw it; `dumpsys
wallpaper` showed only the entry tagged `mWhich=0x13` get a new id while the
inner-screen entry (`mWhich=7`, Samsung's FoldInteractive live wallpaper) was
untouched.

### Why: Samsung extends `which` with display-mode bits

From `android.app.WallpaperManager` on the device:

| Constant | Value | Meaning |
|---|---|---|
| `FLAG_SYSTEM`, `FLAG_LOCK` | 1, 2 | AOSP type bits (`FLAG_TYPE_MASK = 3`) |
| `FLAG_DISPLAY_PHONE` | 4 | inner (main) screen |
| `FLAG_DISPLAY_DEX` | 8 | DeX |
| `FLAG_DISPLAY_SUB` | 16 | cover screen |
| `FLAG_DISPLAY_VIRTUAL` | 32 | virtual display |
| `FLAG_MODE_MASK` | 60 | the display bits |

Routing in `WallpaperManagerService.setWallpaper` (services.jar):

1. `checkPermission(SET_WALLPAPER)`; extra bundles are only honoured from
   platform-signed callers, but the `which` value is taken as given.
2. `SemWallpaperManagerService.isSupportingMode(which)`: mode 16 is allowed when
   the device supports a sub display, except type lock-only on cover-watchface
   devices (Flip family), which returns null and the app sees id 0.
3. For every caller except Samsung's theme center and SystemUI,
   `SubDisplayMode.getFolderStateBasedWhich(which)`: only when no mode bits
   are present, it ORs in 16 if the hinge is closed, else 4.
4. `getModeEnsuredWhich` does the same from the lid state. So an app that passes
   no display bits always writes the screen that is currently active, and an app
   that passes them explicitly writes the display it named.

### Fix and proof

`SamsungDisplays.expand(which)` issues two calls on Samsung hardware:
`which | 4` then `which | 16`. Logcat from the Fold after "Change now":

```
WallpaperApplier: setBitmap which=7 -> id=11
WallpaperApplier: setBitmap which=19 -> id=12
WALLPAPER_SVC: setWallpaper: callingPackage = dev.fengine.wallpaper ... which = 0x7
WALLPAPER_SVC: setWallpaper: callingPackage = dev.fengine.wallpaper ... which = 0x13
```

`dumpsys wallpaper` afterwards: inner entry `mWhich=7` now `ImageWallpaper`
id 11 (was FoldInteractive id 2); cover entry `mWhich=19` id 12. Both screens
changed with the phone still folded.

### Consequences

- Per-display crops become possible on Samsung through two calls with
  different bitmaps (one per display), which AOSP's public API cannot do.
  That is the static-path answer to "foldables first-class" on Galaxy devices.
- On Galaxy Z Flip devices the cover is a watchface surface: write
  `FLAG_SYSTEM | 16` only, never lock-only.
- Pixel Fold and other OEMs do not have these bits; they get the AOSP default
  crop per display. The live wallpaper engine remains the vendor-neutral answer.
- Samsung Auto Blocker blocks adb commands and sideloads; the owner disables it
  for 30 minutes at a time. Plan device sessions accordingly.
- Screenshots on the Fold need the physical display id:
  `screencap -d $FOLD_COVER_DISPLAY` (cover) or `-d $FOLD_INNER_DISPLAY` (inner).
- While the Fold is closed, the inner panel (2448x1848, display id 1,
  FLAG_PRESENTATION) is absent from `DisplayManager.getDisplays()` and from
  `getDisplays(DISPLAY_CATEGORY_PRESENTATION)`, but `getDisplay(1)` returns it
  (verified 2026-10-14, One UI 9). `Displays` probes ids 0..3 and remembers every
  panel it has seen, so a closed phone still gets a 1848x2448 inner crop and a
  1248x1972 cover crop in one rotation (ids 37/38, crop hints echoed verbatim by
  `dumpsys wallpaper`).
- `ContentResolver.loadThumbnail` fails for Google Photos cloud picker URIs
  (`content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/...`)
  while `ImageDecoder` on the same URI works; the UI falls back to a small decode.

## 6. Automatic groups ("family photos") and on-device ML (owner direction, 2026-10-13)

Owner asks: pick groups such as "family" automatically and keep them updating
in the background, with an editorial, clean and simple UX.

### Google Photos API cannot do it

Since 2025-03-31 the Google Photos Library API only reads media the app itself
created (`photoslibrary.readonly` and `photoslibrary.sharing` scopes were
removed; calls return 403). The replacement Picker API lets the user pick
items or albums in a one-off session and hands back the selection. There is no
API for people or face groups, and no way to subscribe to an album so new photos
flow in. Google Photos still shows up inside the Android photo picker as a cloud
media provider (seen on the Fold), which is how users reach it today.

### What is possible

| Need | Route | Access it needs |
|---|---|---|
| Keep a group fresh as new photos arrive | MediaStore query + `ContentObserver` or `MediaStore.getGeneration` polled by the periodic worker | `READ_MEDIA_IMAGES` (full) or `READ_MEDIA_VISUAL_USER_SELECTED` (partial, user re-selects) |
| Build "people" groups on device | Face detection (ML Kit face detection, free, on device) plus a face-embedding model (MobileFaceNet or similar TFLite/LiteRT) and clustering; user names a cluster once | photos readable as above, no network |
| "Family" without naming anyone | cluster co-occurrence: faces that appear together often across the library | same |
| Subject or scene groups (pets, landscapes, food) | ML Kit image labeling or a CLIP-class embedder; score photos against a few fixed prompts | same |
| Wallpaper-worthy filter | sharpness, exposure, face size and position relative to the lock-screen clock area | pure image stats |

Owner decision 2026-10-13: build it as the hybrid below. Picked groups stay
permission-free; described groups request `READ_MEDIA_IMAGES` when the user
taps "Find photos".

### Implemented: the hybrid (2026-10-13)

1. The user describes a group in words.
2. ML Kit base image labeling (bundled, 430 labels) labels every library photo
   once; labels are cached per MediaStore id.
3. Gemini Nano through the ML Kit GenAI Prompt API (`genai-prompt` 1.0.0-beta4)
   maps the description to relevant labels (one text prompt) and then judges
   each candidate photo with an image prompt ("Does this photo match ...? YES
   or NO"). Verdicts are cached per rule.
4. Where Gemini Nano is unavailable (Pixel 8 is not on the device list), a
   keyword matcher over label names accepts candidates without judging.
   A bundled CLIP-style embedder remains the planned better fallback.

On-device result (2026-10-14, Galaxy Z Fold 8, One UI 9): the full pipeline ran
after the user granted photo access. ML Kit labeled the library (synthetic test
images came back as Pattern, Flag, Sports), the prefilter selected "Pattern"
for "green checkerboard pattern", and two matches appeared as thumbnails in the
group. Gemini Nano returned `FeatureStatus.UNAVAILABLE` (0) immediately with no
AICore binding attempt. The Fold's `com.google.android.aicore` is an OEM stub
(`0.stub.oem.aicore_stub_for_android_B`, built for Android 16, no installer,
`/product/priv-app/AICore`) while the phone runs Android 17; Samsung ships its
own `com.samsung.android.aicore` 3.0. Until Google's AICore is delivered to this
device through Play, the app uses the labels-only fallback. The device-list
entry for Fold 8 is therefore not yet usable on this unit.

Follow-up the same night: the Play Store delivered the real AICore
(`0.release.qc.prod_aicore_20260917`) to the Fold, AICore pulled the Gemini
Nano models (over 1 GB), and `checkStatus` flipped to AVAILABLE. The app now
shows an "On-device AI" readiness card (listed device, AICore placeholder,
downloadable, downloading, ready) with the one matching action.

Pool by default (owner direction 2026-10-14): a described group looks through
the whole phone library (cap 10,000, labeled across passes) plus any photos the
user hands it from Google Photos through the system picker. The picker is the
only road to cloud photos for a third-party app and caps a selection at
`MediaStore.getPickImagesMaxLimit()` (100 on most devices), so cloud photos are
added in batches. Users remove from the pool or from the matches with one tap;
removed matches stay excluded on later scans. The Fold's own MediaStore held
only the three synthetic test images, which is why "small children" found
nothing: the owner's photos live in Google Photos cloud.

Device support for the Prompt API (developers.google.com/ml-kit/genai):
Galaxy Z Fold8, Fold8 Ultra, Flip8 (nano-v4); Fold7, TriFold (nano-v2);
Pixel 9 and 10 families, Galaxy S26 (nano-v3); Pixel 11 (nano-v4). Pixel 8 is
not listed. Google Photos also appears inside the Android photo picker as a
cloud provider, which stays the route for hand-picked cloud photos.

### Gemini Nano on real photos (verified on the Fold 8, 2026-10-14)

With AICore `0.release.qc.prod_aicore_20260917` the Prompt API reports
AVAILABLE and judges photos at roughly one per second. Two findings:

- Policy refusals. For some portraits AICore throws
  `GenAiException [ErrorCode 4] Request doesn't pass certain policy check`
  (`INFERENCE_ERROR / REQUEST_PROCESSING_ERROR`). The first refusal was a
  portrait. A refusal is not a verdict: the scan hands that one
  photo to the labels (`ScanPlan.pass`), does not cache it, and the next pass
  asks again. Counting a refusal as a match was a bug found on device and fixed.
- The verdicts hold up. Screenshots, a landscape and the synthetic test
  images were all rejected for "kids eating ice cream". The refusal rate on
  photos of children decides how much a family group can rely on Nano; the
  bundled embedding fallback stays on the list for that reason.

### Scale: labels once per photo, whole-library indexing (2026-10-14)

Owner direction: label a large section of the library once, cache it, and
reach 20k+ photos. What changed:

- `PhotoIndex` is SQLite (`photo_index.db`): `photos(identity, uri, labels,
  labeled_at)` and `verdicts(rule_key, identity, yes)`, keyed by
  `PhotoId.identity` so the picker's and the library's URI for one file share a
  row. The v0.2 JSON file is imported once and renamed. Labels are computed
  once per photo, ever; a scan over 20k indexed photos is one table read.
- `IndexLibraryWorker` labels the reachable library newest first in slices
  (`SLICE` photos or `TIME_BUDGET_MS`, whichever first), re-enqueues itself
  until done, runs a described-group scan after each slice so matches appear
  while the index fills, and repeats twice a day for new photos. The group
  screen shows "Labeled N of M photos on this phone".
- `SmartGroupWorker` labels only a group's pool itself and hands the library to
  the indexer. `MAX_SCAN` is 100k.

Ceilings that are the platform's, not ours (verified 2026-10-14 against
android-37 SDK sources, AOSP main photopicker/MediaProvider and
`dumpsys activity permissions` on the Pixel; decoded sources in the session
scratchpad `aosp/`):

- One picker pick is capped at `MediaStore.PICK_IMAGES_MAX_LIMIT = 100`
  (`MediaStore.java:586`); `EXTRA_PICK_IMAGES_MAX` above it is RESULT_CANCELED
  and androidx `PickMultipleVisualMedia` refuses it client-side.
- Picker URIs survive only as persisted URI grants, 512 per app
  (`UriGrantsManagerService.java:127`, `maybePrunePersistedUriGrantsLocked`
  drops the oldest). So at most 512 picked photos keep working, ever; the app
  now warns from 400 and must never rely on picks for a library.
- Partial access (`ACTION_USER_SELECT_IMAGES_FOR_APP`) is 100 per session,
  local MediaStore only (`CloudMediaFeature.kt:71-74` disables cloud there),
  5,000 grants. It cannot carry cloud photos at all.
- Google Photos ships no DocumentsProvider; its `ACTION_GET_CONTENT` handler
  gives non-persistable grants. The picker's cloud provider is system-only
  (`MANAGE_CLOUD_MEDIA_PROVIDERS`; `PickerUriResolver.checkUriPermission`).
- MediaStore with `READ_MEDIA_IMAGES` has no result cap; 20k rows page through
  2 MB cursor windows, `date_added` is not indexed (`generation_modified` is).

The road to 20k is therefore photos that live on the phone: Google Photos
download / keep originals, or a Takeout import, then `READ_MEDIA_IMAGES` and
no per-item grant. The app cannot fetch them itself without a network
permission, which stays out by design. Measured on the Pixel 8 with 1,503
synthetic 640x480 JPEGs: 1,112 photos labeled in 92 s across two slices
(~85 ms per photo; real camera photos decode slower), index 287 KB.

### Native screen saver (asked 2026-10-14)

Android's "Screen saver" (Settings > Display) is `android.service.dreams.DreamService`,
public since API 17, on Pixel and One UI. What it takes: one exported service
with `android.permission.BIND_DREAM_SERVICE`, an intent filter for
`android.service.dreams.DreamService`, a `<meta-data android:name="android.service.dream">`
XML (settings activity, preview image, Android 13+ `dreamCategory`), and a
full-screen view the engine draws into while the phone charges or docks. It
reads the same `SettingsStore` groups, so "the Family group as a screen saver"
is a renderer over existing data: crossfade and slow Ken Burns pan are plain
View animations there, which makes the dream the cheapest place to ship the
Apple-like motion the owner wants before the live wallpaper engine exists. No
new runtime permissions. Built 2026-10-14 as `screensaver/GroupDreamService`
(two MATRIX ImageViews, cross-fade, `KenBurns.frame` drift proven never to show
an edge, `DreamPlan.order` continuing after the wallpaper cursor); the app
reads `screensaver_enabled` / `screensaver_components` to say whether it has
been chosen and deep-links to `Settings.ACTION_DREAM_SETTINGS`. Verified with
`cmd dreams start-dreaming` on the Pixel 8 and on the Fold 8's cover screen.

### UX direction

Editorial, clean, simple: one screen that reads like a magazine spread. Photos
first (large thumbnails, no ids or file names), one primary action, groups shown
as named covers, schedule as a sentence ("Every hour", "At 7:00 and 19:00").
Settings density stays low; anything diagnostic goes to a secondary screen.

## Sources

- https://developer.android.com/training/data-storage/shared/photopicker
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- https://developer.android.com/develop/background-work/services/alarms/schedule
- https://support.google.com/pixelphone/answer/7289143
- https://9to5google.com/2026/06/29/google-pixel-phones-may-soon-let-you-shuffle-your-favorite-wallpapers/
- https://www.samsung.com/us/support/answer/ANS10001593/
- https://github.com/Anthonyy232/Paperize
- https://github.com/muzei/muzei
- https://developers.googleblog.com/en/google-photos-picker-api-launch-and-library-api-updates/
- https://developers.google.cn/photos/support/updates
- `~/Library/Android/sdk/sources/android-37.2/android/service/wallpaper/WallpaperService.java`
- https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables
