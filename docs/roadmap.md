# Roadmap

0.1.0 is tagged. In order from here:

1. Polish. Widen the invariants to the store and the rotation worker with a
   fake clock and resolver. Profile labeling and rotation on a 1,500-photo
   library. Fuzz the codecs and the scan pass harder.
2. A product audit against store readiness, graded with the ledger in
   `product/2026-10-13-product-review.md`, then the Play listing.
3. 0.2.0: motion over your photos. A live wallpaper engine
   (`WallpaperService.Engine`, audit section 4) that renders the current group
   itself: cross-fade on change, slow drift like the screen saver, parallax
   from launcher page offsets, depth zoom on the lock-to-home transition, and a
   crop per posture on foldables from `onSurfaceChanged`. Photos stay the
   content; no scene or video files. The static `setBitmap` path stays as the
   fallback for phones where a live wallpaper cannot reach the lock screen.
   Measures: battery per hour with the engine active against the static path,
   and how many users keep the engine after a week.

Done since: the live engine (0.2.0), Next photo as a Quick Settings tile and
a 1x1 widget (0.3.0), faces kept out from under the lock screen clock
(`wallpaper/ClockSafe.kt`, framework `FaceDetector`; 0.6.0). Screen-sized
copies of every photo in groups (`wallpaper/PhotoCopies.kt`), so photos keep
showing after Google Photos' "Free up space" deletes the originals; Settings >
Photos turns them off. Finding photos no longer waits on a Gemini Nano
download that AICore never starts (seen on the Fold 8, 2026-11-20, while
AICore's own data package update sat at DOWNLOADED_PENDING_ACTIVATION).
Product review 2026-11-20 (`docs/product/2026-11-20-product-review.md`), items
1-10 in code: Motion and the screen saver set aside unreadable photos like the
static path (`wallpaper/LostPhotos.kt`); scans survive a bad photo and retry
failed labels; picker grants kept for shown photos, look-through grants
released near the ceiling (`data/GrantPlan.kt`); a dated Home caption and a
health line only when something is wrong (`data/Health.kt`); ledger events for
AI, scans, copies and grants; "Pick it again"; the first Set active starts the
rotation and Motion is offered once; Android's unused-app restriction read and
linked (`schedule/AppSleep.kt`); store and in-app text match the picker-only
app; every call into AICore and ML Kit has a deadline (`smart/Outside.kt`).
Fuzzed: `OutsideProperties` (virtual time), `PhotoLifecycleProperties`
(model-based), `CopyPlanProperties`, `HealthAndGrantProperties`.
Still owed by the owner: a 14-day log export from each phone, and copy storage
per 100 photos on the Fold and Pixel (review items 5, 6).

Next:

- A bundled image-text embedding as the fallback when Gemini Nano is absent
  or refuses a photo. Labels-only matches words, not meaning. Findings
  (2026-11-19):
  - MobileCLIP and MobileCLIP2 weights are research-only (Apple ML Research
    licence); third-party "Apache-2.0" exports are still bound by it. Out.
  - TinyCLIP ViT-40M/32 Text-19M (LAION-400M), MIT code and weights, 59.8 %
    ImageNet zero-shot, is the pick. No LiteRT file exists; convert with
    litert-torch. Expect about 60 MB int8. The 8M/16 variant is about 25 MB
    but 41.1 %.
  - Runtime LiteRT 2.3.2: about 5.6 MB arm64, no permissions. ONNX Runtime
    1.32.0 is about 36 MB arm64 and adds INTERNET, ACCESS_NETWORK_STATE and a
    telemetry provider. Out.
  - Ship the model as an install-time asset pack (no Play library, no
    permission); check that sideloaded APKs still carry it.
  - Store L2-normalised int8 vectors in `PhotoIndex` (version 2); score each
    photo against the description minus a neutral prompt, and keep the photos
    about 2 standard deviations above the library mean, capped at top k.
  - Decide on size after a Pixel 8 measure: model size, ms per photo, and
    matches against labels-only on the owner's groups.
- Clock-safe, round two: whether Pixel builds enable SystemUI's region
  sampling, and where One UI puts its clock (users can move it). Measure the
  share of lock screens with a face crossing the top third, before and after.

Decided against: online photo sources, Google Photos API access (no longer
possible for a user's library) and exact alarms. The product review says why.
