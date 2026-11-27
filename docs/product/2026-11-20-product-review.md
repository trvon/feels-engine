# Feels Engine product review (product-manager pass, 2026-11-20)

## Decision

After 0.6.0, what should the next cycle fix or build for the owner and people like
the owner, who have a Pixel and a Galaxy Fold and keep most photos in Google Photos?
Success means photos that keep showing, failures the app reports instead of hiding,
and setup that works without help.

| Item | Value |
|---|---|
| Segment | Owner and owner-like users: Pixel 8 (labels only) and Galaxy Z Fold 8 (Gemini Nano). Both libraries are mostly in Google Photos cloud, and local MediaStore is nearly empty (`docs/local/devices.md`) |
| Job | Family photos on home, lock, cover and screen saver, without managing the app |
| Signal this week | (1) Gemini Nano setup stalled on the Fold while AICore's own update was pending, and "Find photos" waited on it. (2) Google Photos "Free up space" removed originals, and picked photos stopped reading. Both come from something outside the app's control, and both failed silently |
| Scope | Working tree on `feat/photo-copies` at 2026-11-20. It has screen-sized copies, a find that no longer waits, and `smart/Outside.kt` timeouts, all in progress while this was written. UI walk, smart/, schedule/, wallpaper/live/, screensaver/, foldable handling. No device was touched |
| Decision date | 2026-12-04 (owner) |

## What shipped from earlier reviews

| Review, item | Status | Evidence |
|---|---|---|
| 10-13 #1 / 10-15 #2 local ledger with engine health | shipped | 7d2d795, ff15b0d; `data/Ledger.kt` |
| 10-13 #2 Next photo outside the app | shipped | bc90919, a tile and a 1x1 widget |
| 10-13 #3 clock-safe photos | shipped | e44bf00 (verdict, skip) and 25f08a0 (faces kept out from under the clock) |
| 10-13 #4 time-of-day groups | not built | no per-slot group in `schedule/` |
| 10-13 #5 automatic groups | shipped as descriptions, now picker only | 63c187b (no photo permission) |
| 10-15 #1 Play review | passed, with the picker-only fallback | 63c187b, releases 0.3.0 to 0.6.0 |
| 10-15 #3 screen saver "wireless or dock" by default | shipped | ab442e6 |
| 10-15 #4 engine glue into the state machine | shipped | 7d2d795, `EngineRules.kt`, `LiveEngineState.kt` |
| 10-25 release review: open items | partly | The cursor-by-photo item was fixed in 6803a6e. One UI "double tap to sleep" also changing the photo is still unverified on the Fold 8. The rewrite of the whole settings file on every rotation is still open |
| 10-25 settings review #1-5 | shipped | a7296ce: segmented choices, a time picker, the week's screen-saver skips, Appearance |

Settled; not reopened here: no network, no photo permission, no Google Photos API,
no exact alarms, the screen saver's car veto and its wireless default, and segmented
settings.

## Evidence, graded

### Own data: the owner's log export (B for counts, n=1 device)

`docs/local/feels-engine-log-2026-11.jsonl` has 475 lines. **Despite its name, it
covers 2026-10-16 to 2026-10-25**, the same window the 10-25 settings review used.
It has no `run`, `bucket`, `late_ms` or `reason` fields and no `alarm` source, so it
was written by a build from before 3af11b0 and ff15b0d (both 2026-10-25). No log
exists for 0.5.0 or 0.6.0, so none of the measures set by earlier reviews can be
graded yet.

| Measure | Value | Note |
|---|---|---|
| Lines | 366 screen saver (358 `SKIP_WIRED`, 8 `PLAY`, all 8 plugged=4 wireless) and 109 rotation | Already acted on (10-25 settings review) |
| Rotation runs (lines within 2 min merged) | 72: 69 scheduled, 4 by hand (`set-active`) | 109 lines, because one change wrote several lines before ff15b0d |
| Scheduled cadence | median gap 62 min (interval 1 h). 13 of 68 gaps over 3 h, 7 over 6 h, 4 over 12 h (15.8 h and 14.2 h on weekdays) | From 10-19 13:49 to 10-25 13:07 (143 h), 67 scheduled runs, about 47% of hourly slots. This is the standby stall that 3af11b0 (chained alarms) was meant to fix. Unmeasured since |
| Unreadable | 3 of 72 runs (4%), each with 4 unreadable lines in one run (all hops spent), at 15:29, 06:57 and 03:57 | Four photos unreadable in a row means a block of originals went missing at once, which fits "Free up space" or offline cloud items. Each such run left the screen unchanged |
| Engine health | no `first_frame`, `no_frame`, `exit` or `motion_lost` lines | Either Still was in use or the build predates the events; the log cannot tell which |

The export is grade B for what it shows. It says nothing about the code shipped
since.

### Code reading (B: observed in source; behaviour not reproduced on a device)

| Finding | Where |
|---|---|
| Motion: a photo that fails to decode is dropped without a trace. The engine keeps the old frame (or none), the cursor has already moved, and the ledger records `live` as a change | `wallpaper/live/PhotoWallpaperService.kt:350`, `:386-398`; `schedule/Rotation.kt:221-224` |
| Screen saver: a failed decode is skipped. It goes black when all fail, and nothing is recorded | `screensaver/GroupDreamService.kt:156-176` |
| Only the static path marks a photo unreadable, shows "Can't be read" and offers it back. With Motion on, none of that runs | `Rotation.kt:247-276`, `GroupScreen.kt:265`, `MainViewModel.kt:73-98` |
| Scan: the decode before `judge` is outside any catch. One unreadable photo handed over for a description fails the whole worker every time Gemini Nano is available, and the scan note stays stale | `smart/SmartGroupWorker.kt:90-96`; `ScanPlan.kt:74` |
| A label failure (now including the `Outside.LABEL_MS` timeout) stores empty labels. `unlabeled()` treats any row as done, so the photo is never labeled again | `SmartGroupWorker.kt:43-46`, `IndexLibraryWorker.kt:39-41`, `PhotoIndex.kt:85-90` |
| "Find photos" persists a grant for every photo handed over. Grants are released only when the group is deleted. Five passes of 100 reach the 512 ceiling, after which Android silently drops the oldest grants, which are the first photos picked | `MainViewModel.kt:368-391`, `:271-277`; `data/Platform.kt` grantCeiling |
| The grant warning tells people to "keep the photos on this phone and describe them instead: the description looks through all of them". That stopped being true at 63c187b. The store listing says the same twice | `GroupScreen.kt:290`; `docs/local/store/listing.md:34`, `:54` |
| The Home caption "Changed at 14:02" has no date, so a change from three days ago reads as current. The headline states the schedule, not what happened | `Rotation.kt:125-130`; `Format.kt:56-68`; `HomeScreen.kt:112` |
| First run: `enabled` defaults to false, and "Set active" does not turn rotation on. A new user sees their photo once, then "Paused." on a schedule they never started | `data/Settings.kt:23`; `MainViewModel.kt:566-582`; `Format.kt:62` |
| Copies are saved when the app opens and after "Add photos", not after a scan finds photos | `MainViewModel.kt:95`, `:317`; `SmartGroupWorker.kt:108-113` |
| The AI status (including the new stall text) replaces the scan note on every described group's screen until it clears | `GroupScreen.kt:341-345`; `MainViewModel.kt:204-230` |
| The cover screen is handled only where Samsung display bits exist. Other foldables get one crop | `MainViewModel.kt` hasCoverScreen; `SamsungDisplays.kt` |

### Web: reported need (D) and advertised capability (C)

| Item | Source | Grade |
|---|---|---|
| Rotation stops silently: 6 distinct threads across Paperize (#93, #280, #207) and Muzei (#190, #654), plus one forum thread | github.com/Anthonyy232/Paperize/issues/93, /280, /207; github.com/muzei/muzei/issues/190, /654 | D, reported |
| Live wallpaper unset after a reboot on Pixel 10 / Android 17 (16 comments, 2026-07-01). OEMs hand the user back to the system picker | github.com/muzei/muzei/issues/874, /839 | D, reported; direct for Motion |
| The photo set shrinks over days (Samsung lock screen). Paperize pruned deleted and `.trashed` files on 2026-11-17 and 11-18 | eu.community.samsung.com (S24 lock screen photos); Paperize PR #654, #656 | D, reported; C, a competitor fix |
| A change made while folded crops for the cover screen, so the inner screen is blurry (Paperize #542, 2026-07-20) | github.com/Anthonyy232/Paperize/issues/542 | D; our per-panel crop already avoids it |
| "Free up space" deletes device copies of backed-up items older than 30 days. Those items stay viewable only in Google Photos | support.google.com/photos/answer/6128843 | A, official doc; the owner's case matches |
| Gemini Nano download stuck at "Downloading" after a storage error (ML Kit #1080, 2026-10-12, open). AICore blocks backgrounded apps (`BACKGROUND_USE_BLOCKED`, #1069). BUSY after about 40 requests (#1070) | github.com/googlesamples/mlkit/issues/1080, /1069, /1070 | D, developer reports; #1069 matters for our worker |
| Pixel "Photo Shuffle" over own albums (hourly, daily, sunrise/sunset, tap) found in Android 17 QPR1 beta 5 code. Not shipped as of 2026-11-20 | androidauthority.com/google-pixel-wallpaper-photo-shuffle-apk-teardown-3681326 (2026-06-25) | C, unreleased; a platform threat to watch |
| Battery: no complaints found in these trackers this round | same searches | absence, not evidence |

Play reviews could not be read in this pass; counts are GitHub and forum threads, each counted once.

### Platform (A where a source is cited)

The server sources are AOSP `main`, fetched for this pass. OEM builds may differ.

| Claim | Source | Grade | Applicability |
|---|---|---|---|
| Hibernation (API 31+) force-stops the app and clears its cache. Alarms and jobs are lost and not rescheduled until `BOOT_COMPLETED` on waking | AppHibernationService.java:467-468; developer.android.com/topic/performance/app-hibernation | A | Still mode depends on exactly those alarms and jobs |
| PermissionController exempts the **selected home wallpaper** service and enabled dream services. The dream check reads `enabled_notification_listeners`, so in `main` it probably never applies | HibernationPolicy.kt:584-586; SelectedWallpaperServiceLiveData.kt:43-44; EnabledDreamServicesLiveData.kt:42,64 | A for the code; unverified on Pixel and One UI | Motion on home protects the app. **Still mode, or a lock-only engine, does not** |
| App standby buckets have no wallpaper or dream exemption. A bound wallpaper service is not standby "usage" | AppStandbyController.java:1148-1155, 1445-1530 | A | Motion users still drift to RARE; the alarm chain (3af11b0) is the right answer |
| `PackageManagerCompat.getUnusedAppRestrictionsStatus` and `IntentCompat.createManageUnusedAppRestrictionsIntent` are public (androidx) | hibernation doc | A | Item 8 needs no permission |
| Grant ceiling 512. Pruning drops the oldest by `persistedCreateTime`. **Taking a grant again resets that time.** There is no callback on eviction | UriGrantsManagerService.java:127, 606-631; UriPermission.java:172-173; ContentResolver.java:3054-3056 (API 37) | A | Re-taking the grants of shown photos protects them from eviction (item 3) |
| Android 16: JobScheduler runtime quota applies in every bucket, including ACTIVE. `WallpaperDescription`/`WallpaperInstance` exist, but setting a component with one is `@SystemApi`. An embedded photo picker arrives | developer.android.com/about/versions/16/behavior-changes-all, /16/features; WallpaperManager.java:3095-3148 (API 37) | A | The quota is one more reason for the alarm path. The embedded picker is a later UX option, not a need |
| Android 17: nothing found on DreamService, lock-screen live wallpaper, picker grants or standby. Memory limiter for all apps | developer.android.com/about/versions/17 | A for what is listed | Exits already go to the ledger (`memory_limiter`) |

## Ranked backlog

Rule, unchanged: impact × evidence grade ÷ cost, then the guardrails (no network,
no photo permission, no exact alarms, 48 dp targets, screen-reader names). Items 1
to 4 share one shape: something outside the app fails, and the app says nothing.

### 1. Motion and the screen saver handle an unreadable photo the way the static path does

- Problem: with Motion on (the owner's chosen look), a photo that cannot be read
  leaves the old frame up or a black surface. The ledger counts it as a change.
  The photo is never marked, badged or offered back. The screen saver skips it
  without a line.
- Evidence: code (B): `PhotoWallpaperService.kt:350`, `Rotation.kt:221-224`,
  `GroupDreamService.kt:166-176`. The log (B, n=1): 3 of 72 runs hit four
  unreadable photos in a row. "Free up space" deletes originals (A, Google doc).
  Muzei #874 shows how bad a blank live wallpaper looks to users (D).
- Change: the engine and the screen saver report a decode failure through the same
  path as the static applier (`RotationPlan.settle` marks it unreadable). The engine
  then asks for the next readable photo and records
  `rotation{source:"engine", result:"unreadable", reason}`. `LiveEngineState` gets a
  rule: a failed decode never replaces a shown frame with nothing.
- Acceptance: a property that no sequence of decode failures leaves `shown` null
  while an earlier bitmap exists. A device check on the Fold with copies off and one
  grant released: the next photo appears within one change, the grid shows "Can't
  be read", and the ledger has the line. Guardrail: 0 frames at rest is unchanged
  (`docs/perf.md` method).

### 2. A described-group scan survives one bad photo and never labels a photo as "nothing"

- Problem: when Gemini Nano is ready (the Fold), one unreadable photo handed over
  for a description fails the scan worker on every run. A label failure or timeout
  stores empty labels forever. Both look like "found nothing" to the user.
- Evidence: code (B): `SmartGroupWorker.kt:43-46`, `:92`; `PhotoIndex.kt:85-90`. The
  owner's cloud-first library makes unreadable photos handed over for a description
  likely, for the same reason as item 1. The new `Outside` timeouts make empty
  labels more common, not less.
- Change: catch the decode inside the judge lambda and return null. Null already
  falls back to labels and is not cached, as `ScanPlan.pass` requires. Store no
  labels row on failure: keep a failure count, and leave the photo out after 3
  tries. The worker ends with a note even when a group throws.
- Acceptance: a ScanPlan property where `judge` throws for arbitrary keys and the
  pass still returns every other verdict. A worker test with a fake index: a failed
  label leaves the photo in `unlabeled()`. A device check: a scan with one deleted
  photo completes and updates the note.

### 3. "Find photos" stops using up the grants that keep picked photos readable

- Problem: every photo handed over for a description keeps a persisted grant.
  Five 100-photo passes reach Android's 512 ceiling. Android then silently drops
  the oldest grants, which belong to the family photos picked first. The warning
  card recommends the very path that uses up grants.
- Evidence: code (B): `MainViewModel.kt:368-391`, `:271-277`, `GroupScreen.kt:284-293`.
  Ceiling and silent eviction: `Platform.grantCeiling`, AGENTS.md platform contract
  (A).
- Change: once a photo handed over for a description has a settled verdict (Nano,
  or labels where Nano is absent) and did not match, release its grant. Its labels
  and verdict stay in `PhotoIndex` by identity. Keep the grants of matches and
  copies. Above 80% of the ceiling, release the oldest non-matches first. Android
  prunes by `persistedCreateTime`, and taking a grant again resets it
  (UriPermission.java:172-173, A). So once a day, `PhotoCopyWorker` takes the
  grants of every photo in `allPhotos` again, and handed-over photos are always
  evicted first. Rewrite
  the warning: "N of 512 used. Picked photos past the limit stop showing unless a
  copy is kept."
- Acceptance: grants after a 100-photo pass are at most picked + matched + still
  unjudged. A property: release never names a URI in `photos`, `matched` or a cover.
  The text no longer mentions "on this phone" (UI test).

### 4. Home says what actually happened, with a date and one fix

- Problem: the headline restates the schedule, and "Changed at 14:02" has no date.
  A rotation stalled for days, a run of unreadable photos, Motion showing old
  frames, and a stalled AI setup all look healthy from Home.
- Evidence: code (B): `Rotation.kt:125-130`, `Format.kt:56-68`. The log (B): 13 of
  68 scheduled gaps over 3 h on an hourly rhythm before 3af11b0, with no way to
  see that in the app. Competitor trackers: rotation that stops silently is the
  most-reported theme (6 threads, D).
- Change: one line under the headline, computed from the ledger (pure, in
  `Ledger`):
  - nothing when healthy;
  - otherwise the worst single fact, with its fix: "Last change 2 days ago:
    Android is holding the app back" (fix: open the app's battery settings, see
    item 8), "4 photos could not be read, 3 shown from copies" (fix: the group),
    or "Motion stopped on 12 Nov" (shipped).
  The caption carries a date when it is not today.
- Acceptance: a property that the line appears exactly when the last visible change
  is older than 3 × the interval (or a missed set time), or unreadable > 0. On the
  Fold over 14 days, the share of scheduled changes more than 2 × interval late
  stays under 5%, read from `late_ms`.

### 5. Export a log from 0.6 and put the new outside dependencies in it

- Problem: the only export predates 0.5, so the 3af11b0 stall fix, the
  screen-saver default, Motion health and this week's two failures are all
  unmeasured. The ledger has no event for AI setup, scan outcomes, copy fallback,
  grant count or engine decode failures.
- Evidence: the export (B): no `run`, `bucket` or `late_ms` fields, last line
  2026-10-25. `Ledger.kt:12-57`.
- Change: new events, with no URIs and no text:
  - `ai{state: ready|downloading|stalled|failed, code}`
  - `scan{found, judged, failed}`
  - `copy{saved, missing, used}`, where `used` is a read served from a copy
  - `grants{n}` once a day
  - the engine decode failures from item 1

  The owner exports after 14 days on each device.
- Acceptance: one export per device by 2026-12-04, with every event type present
  at least once on the Fold. The earlier reviews' measures (late share, first-frame
  p95, wired vetoes after Any charger) are graded in the next review.

### 6. Finish screen-sized copies: cover found photos, measure the cost, recover photos already lost

- Problem: copies are the right answer to "Free up space" (it deletes local
  originals of backed-up items; Google doc, A). But found photos wait for the next
  app open, the storage cost on the Fold is not known, and photos that went missing
  before this build have no copy and no way back except removing them.
- Evidence: code (B): `SmartGroupWorker.kt:108-113` does not enqueue
  `PhotoCopyWorker`, which runs only with storage not low (`PhotoCopyWorker.kt:62`).
  Copy size by estimate (C): the Fold inner panel × 1.08 × 1.06 is about 5.5 MP, so
  about 1.5 to 2.5 MB at q90 and about 1 GB for 512 photos. That needs measuring.
- Change:
  - Enqueue the copy worker after a scan adds matches.
  - Record per-photo bytes and show the total, which the working tree already does.
  - On a "Can't be read" photo sheet, add "Pick it again". It opens the picker, and
    the new URI replaces the old one in place, keeping the cursor and cover. A
    Google Photos cloud item re-picked through the picker reads again without a
    network permission in this app.
- Acceptance: MB per 100 photos on the Fold and Pixel, in `docs/perf.md`. Copies
  exist for 100% of `allPhotos` within 24 h of being added (from `copy` events). A
  re-pick restores a photo with its position kept (property on `Group`). Guardrail:
  the copies directory stays under 1.5 GB at 512 photos, or quality drops to 85.

### 7. First run ends with rotation on, and Motion offered once

- Problem: a new user makes a group, adds photos and taps Set active. They see one
  photo, then "Paused.", because `enabled` defaults to false and Set active does
  not turn rotation on. Motion, the owner's headline feature, is reachable only
  from Settings > Look.
- Evidence: code (B): `Settings.kt:23`, `MainViewModel.kt:566-582`, `Format.kt:62`.
  Owner priority: Apple-like motion (A for this product, from AGENTS.md).
- Change: the first Set active on an install that has never rotated also turns
  rotation on. "Paused." becomes "Not started." until then. After that first
  change, Home shows one dismissible line, "Make it move: Motion", which opens the
  chooser (`LiveWallpaper.chooserIntent`).
- Acceptance: a UI test where a fresh install goes New group, add, Set active, and
  ends with `enabled` true and an alarm armed. Ledger share of installs with Motion
  on after 7 days (owner devices now; Play users if a voluntary export ever exists).
  Guardrail: no wallpaper change before the person taps Set active.

### 8. Notice when Android puts the app to sleep, and point to the one setting

- Problem: Still mode on the Fold and the cover screen depend on alarms and jobs.
  Standby buckets, Android's "pause app activity if unused", and One UI's sleeping
  apps can stop them. The Settings caption says only "Android may delay a change".
- Evidence: the log (B): before 3af11b0, 47% of hourly slots delivered. Platform (A): a hibernated app loses
  its alarms and jobs. Only the selected home live wallpaper is exempt, so Still
  users are exposed. Standby has no wallpaper exemption. Competitors: 6 "stopped silently" threads (D).
- Change: read `PackageManagerCompat.getUnusedAppRestrictionsStatus` and the standby
  bucket into the ledger. When a change is late (item 4), the fix opens
  `IntentCompat.createManageUnusedAppRestrictionsIntent`, or the app's details page
  on One UI. No new permission. No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- Acceptance: the restriction status and bucket appear in each export. On both
  devices, the late share from item 4 is at most 5% after the owner follows the fix
  once.

### 9. Make the store listing and in-app text match the picker-only app

- Problem: the listing says the phone "finds matching photos in your library" and
  "for a big collection, keep the photos on the phone and describe them". The app
  has looked only at photos handed over through the picker since 63c187b. The
  grant card repeats it.
- Evidence: `docs/local/store/listing.md:34`, `:54`; `GroupScreen.kt:290`,
  `:344-357` (B). The live Play listing was not checked in this pass; Play holds
  listings to what the app does (metadata policy; not re-read this pass, C).
- Change: rewrite three sentences. Descriptions look through photos you hand over,
  up to 100 per pick. Screen-sized copies keep photos showing after "Free up space".
  Remove the "on this phone" index copy, which is dead without the library
  permission (`IndexLibraryWorker.kt:26`).
- Acceptance: listing and app text agree, checked by hand against the build. A
  grep finds no "library" or "on this phone" claims in UI strings.

### 10. On-device AI status lives in its card and outlives the screen

- Problem: the stall fix reports through a view-model flow. Leaving the app ends
  the watch, and while it is set it hides each described group's scan result. AICore
  also blocks background use (`BACKGROUND_USE_BLOCKED`, ML Kit #1069, D), so a
  6-hourly scan on the Fold can fall back to labels without saying so.
- Evidence: `MainViewModel.kt:204-230`, `GroupScreen.kt:341-345` (B); ML Kit
  #1069, #1080 (D).
- Change: put the AI line inside the readiness card, and keep the scan note visible.
  Persist the last AI state with a time, as in item 5. When a scan ran labels-only
  because AICore refused background use, the note says so, and the next scan
  started from the foreground re-judges.
- Acceptance: a UI test where a stalled state and a scan note show together. A
  device check on the Fold: a background scan with AICore refusing leaves a note
  that names labels, and a foreground "Find photos" re-judges.

## Silent failure / outside dependency audit

| Depends on | Fails how | What the person sees today | In the ledger | Status |
|---|---|---|---|---|
| Google Photos originals ("Free up space", offline cloud item) | read throws | Still: badge, "Can't be read", offered back. Motion: old frame or black. Screen saver: skipped | Still: yes. Motion and screen saver: no | Copies in the working tree; items 1, 6 |
| Persisted grant ceiling (512) | oldest grant dropped silently | nothing until a read fails; a card at 410 on the group screen only | no | item 3 |
| AICore model download | accepted, never starts | "Preparing" forever, now a stall line after 90 s (working tree) | no | fixed in tree; items 5, 10 |
| AICore prompt and status | hang, BUSY, background blocked | was a stalled worker; now a timeout and labels (working tree) | no | `Outside.kt` in tree; item 10 |
| ML Kit labeler (Play services) | throws or times out | photo silently never matches | no | item 2 |
| A photo handed over that cannot be decoded during a Nano scan | worker fails | stale note, "Looking..." ends | no | item 2 |
| Standby bucket, hibernation, sleeping apps | alarms and jobs late or stopped | "Changed at 14:02" with no date | `bucket`, `late_ms` since 0.5, not shown | items 4, 8 |
| Android reverting Motion (force stop, reinstall, two crashes) | engine unset | Home notice | `motion_lost` | shipped (d74451a) |
| `setBitmap` returns 0 | write refused | "Problem at ..." | `failed` | shipped |
| Screen saver vetoed on a cable | does not play | week count in Settings | `screen_saver` | shipped (a7296ce) |
| Copy worker | storage low, or original already gone | count and size in Settings only | no | items 5, 6 |
| One UI display bits / panel probe | cover unknown, one crop | no cover row | no | watch; non-Samsung foldables get one crop |

## Do not build

| Idea | Why not |
|---|---|
| Fetch cloud originals, or use the Google Photos API, to recover from "Free up space" | No network and no API access for a user's library (settled 10-13). Copies and re-picking cover it |
| READ_MEDIA_IMAGES to find photos again after deletion | Play refused it twice (settled 10-15). A picker re-pick (item 6) is enough |
| A foreground service, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, to beat standby | A wallpaper change justifies neither a persistent notification nor a Play-restricted permission. Point to the user setting instead (item 8) |
| Polling AICore or retrying the download in a loop | AICore throttles (BUSY after about 40 requests, #1070) and blocks background use (#1069). One request, one stall message, a check-again action |
| Full-resolution copies, or copying photos handed over for a description that did not match | Storage, and a copy of a photo the person did not choose to show. Screen-sized copies of shown photos only |
| The TinyCLIP embedding fallback (roadmap "Next") before items 2 and 3 | A better matcher on a scan that dies on one photo, and that spends picked photos' grants, gains nothing |
| Time-of-day groups (10-13 #4) this cycle | The only evidence is still PhotoClock (B, 12 participants), with no owner ask. Reliability comes first |
| Copying Pixel Photo Shuffle's "sunrise/sunset" before it ships | It is unreleased code (C). Watch the next QPR |

## Research ledger

| Date | Hypothesis | Evidence | Change | Measure | Decide by |
|---|---|---|---|---|---|
| 2026-11-20 | Unreadable photos under Motion cause stale or blank screens the owner cannot explain | B code, B log (3/72 runs), A Google doc | item 1 | engine `unreadable` lines; blank surfaces = 0 | 2026-12-04 |
| 2026-11-20 | One bad photo handed over for a description blocks scans on Nano devices | B code | item 2 | scans completed / started on the Fold | 2026-12-04 |
| 2026-11-20 | Find photos evicts picked photos through the grant ceiling | B code, A platform | item 3 | grants after a pass; evictions = 0 | 2026-12-04 |
| 2026-11-20 | A dated health line catches stalls the owner now finds by eye | B log, D trackers | items 4, 8 | late share ≤ 5% over 14 days | 2026-12-18 |
| 2026-11-20 | Copies keep cloud-first libraries showing at an acceptable storage cost | A doc, C estimate | item 6 | MB / 100 photos; copy coverage 100% in 24 h | 2026-12-04 |
| 2026-11-20 | New users end setup with rotation on and try Motion | B code, owner priority | item 7 | UI test; Motion on at day 7 | 2026-12-18 |
| 2026-11-27 | Big groups need bulk import past the picker's 100 per pick; a followed folder and a findable Google Photos Share guide cover it without a photo permission | owner ask (A for this product); picker cap and 512-grant ceiling (A, platform contract) | folder source (`FolderSync`, `FolderSyncWorker`), Share guide card on the group page | photos per group added by folder or Share vs picker; folder syncs that fail (ledger, next export) | 2026-12-18 |
| 2026-11-27 | Release-only R8 failures reach production because every check runs the debug build | B: 0.9.0 and 0.9.1 shipped `Generation.getClient()` NPE, reproduced only with the release APK | `scripts/release-probe.sh` and CI job `release-probe` | release regressions caught before tag = all | ongoing |

## Follow-ups

- Grade items 1 to 5 against the two 0.6 exports in the next review.
- The open 10-25 device check (One UI double tap to sleep also changing the photo)
  still needs ten minutes on the Fold 8.
- `sage suggest feedback outcome product-manager --status passed` after the owner
  decides.
