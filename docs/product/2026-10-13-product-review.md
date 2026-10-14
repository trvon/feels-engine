# Feels Engine product review (product-manager pass, 2026-10-13)

## Decision

Should the next engineering cycle go to (a) automatic photo groups on
device, (b) the live wallpaper engine for motion and foldables, or (c) the
unglamorous trust layer (instrumentation, legibility, manual "next"), for the
segment "Android users who want their own photos cycling on home and lock, with
foldable owners as the lead segment", measured by rotation retention and
rotation success?

| Item | Value |
|---|---|
| Segment | Android 12+ owners with a personal photo library; Galaxy Fold/Flip and Pixel owners first |
| Job | Keep both screens showing photos I care about without thinking about it |
| Workaround today | Paperize or Muzei (open source), Samsung Gallery lock-screen set (15 photos, lock only), manual changes |
| Observed failures | Samsung writes only the active screen for third-party apps (own device test, B); Pixel/Samsung built-ins do not rotate own photos on both screens (vendor pages, C); wallpaper reverting and clock legibility complaints on Samsung forums (D) |
| Candidate change | See ranked list |
| Decision date | 2026-10-27 (owner) |

## What shipped in this cycle (context, not evidence)

- Groups: a named set of photos feeds home, lock, both or neither. Same group
  on both screens is one shared write; different groups are one write each.
  Old flat photo lists migrate into a "My photos" group.
- Editorial home: a headline sentence that states what the app is doing,
  groups as covers, rhythm chips (15 min, hour, 3 hours, day, or set times),
  one primary action. Group screen: tap-to-rename headline, "shows on" chips,
  thumbnail grid. Material You colours from the wallpaper. Verified on the Fold.
- Samsung foldable fix: both displays written explicitly.

## Evidence, graded

### Literature (read before citing; applicability gap stated)

| Claim | Source | Grade | Applicability |
|---|---|---|---|
| Personalising device appearance has emotional and identity effects; users personalise to reflect themselves and to make the device familiar | Blom & Monk, "Theory of Personalization of Appearance", HCI 18(3), 2003, doi:10.1207/s15327051hci1803_1, three qualitative studies, 255 citations | B for the construct, C for applicability (pre-smartphone, Finnish teens and PC users) | Supports the product category, not any specific feature |
| Situated photo displays in the home are valued for family expression and provoke reflection between generations | Durrant, Taylor, Frohlich, Sellen, Uzzell, "Photo Displays and Intergenerational Relationships in the Family Home", BCS HCI 2009, doi:10.14236/ewic/hci2009.2, design field study, one household device | C (small n, home display, not a phone) | Supports "family" as the first automatic group and shared groups |
| Linking photos to the time of day they were taken produced reflective, well-received experiences over 8 weeks with 12 participants | Chen, Odom, Kang, Neustaedter, "PhotoClock", DIS 2023, doi:10.1145/3563657.3596020, field study | B (field study, small n) | Direct support for time-of-day rhythm and for a "taken around now" selection rule |
| Text needs 4.5:1 contrast (3:1 large) against its rendered background; background images that fail this are failure F83 | WCAG 2.2 SC 1.4.3 Understanding doc | A (normative) | The lock-screen clock and notifications sit on our photo; we control which photo and crop |

Queries run (paperbridge, OpenAlex/Crossref/Europe PMC; arXiv rate-limited,
Semantic Scholar key missing): "smartphone wallpaper personalization
attachment", "ambient photo display family photos home slideshow user study",
"lock screen glanceability text legibility over background image", "on-device
versus cloud photo processing privacy attitudes users face recognition",
"theory of personalization of appearance mobile phone Blom Monk", "digital
photo frame home ambient display family photos field study", "lock screen
wallpaper clock legibility contrast dimming", "face clustering photo
organization privacy attitudes user study on-device". No usable paper was found
on wallpaper change frequency or habituation, or on user attitudes to on-device
face clustering specifically; those remain open.

### Web: advertised capability (C) and reported need (D)

| Item | Source | Grade |
|---|---|---|
| iOS Photo Shuffle: categories People, Pets, Nature, Cities (multi-select), frequency On Tap, On Lock, Hourly, Daily, up to 50 manual photos, "Don't feature this photo", album source since iOS 17 | Apple how-to coverage (iphonelife, gadgethacks, creativetechs) | C, advertised |
| Pixel "Photo shuffle" for own albums exists behind flags in Android 17 QPR1 beta; not in the picker on the test Pixel | Android Authority / 9to5Google teardown, own APK string dump | C |
| Paperize: separate albums per screen, folder albums, foldable sizing fixes (4.0.0, 4.0.1), 1-minute live intervals | GitHub releases | C, advertised |
| Paperize top requests (reactions, counted once): folder schedule 5, change on screen off/on 4, change at a specific time 3, see the current photo's name 3, light/dark-theme variants 2, reorder 2, next-from-notification 1, widget 1, double-tap next 1, exclude certain apps 1, remove current from library 1 | GitHub issue search sorted by thumbs-up | D, reported |
| Muzei top requests: multiple active sources 16, download current image 9, 3D parallax 7, effects system 6, less blurry blur 5 | GitHub issue search | D, reported |
| Samsung forums: wallpaper reverts to the previous manual choice after editing the lock-screen clock; clock does not update over depth-effect wallpapers | Samsung Community 2024-2025, Kustom forum | D, reported |
| 1-3 star review themes across five wallpaper apps: battery drain from live wallpapers, ads, blurry crops | unstar.app review roundup, method unverified | D |

### Own data

No telemetry exists. The app is offline by design (no network permission) so
any measure has to be a local ledger the owner can export. Device findings
from this session count as observations (grade B for the fact, n=1 device):

- Samsung One UI routes a plain `setBitmap` to the active screen only; both
  screens need explicit display bits (fixed, proven by wallpaper ids 11/12).
- The system photo picker on the Fold surfaces Google Photos cloud media, so
  "my Google Photos" are reachable without any Google API.
- Thumbnail loading through the picker provider is fast enough for a 3-column grid.

## Ranked recommendations

Ranking rule: expected impact x evidence grade / cost, then guardrails (no new
permission without an owner decision, no network, 48 dp targets, screen-reader
names on every image).

### 1. Instrument rotations locally before building anything else

- Problem: every other decision here lacks a denominator. We cannot say whether
  rotations succeed, whether people keep rotation on, or whether they undo a photo.
- Evidence: own data gap (A as a prerequisite); skill rule "define the measure
  before the change ships".
- Change: a local, on-device event ledger (rotation_applied with screen and
  result, rotation_enabled on/off, group_created, photos_added count,
  app_opened, undo_within_10_min) stored in the app's DataStore, visible on a
  diagnostics screen, exportable as JSON by the owner. No network.
- Acceptance measure: 100% of rotation attempts recorded with a result; owner
  can export after 7 days.
- Experiment: none; this is the instrument.
- Risk: none to users; small engineering cost. Owner, by 2026-10-20.

### 2. "Next photo" from outside the app (tile, widget, notification action)

- Problem: people want to skip a photo they dislike without opening the app.
- Evidence: Paperize #57, #247, #418 and Muzei focus duration (D, four distinct
  reports); iOS ships "On Tap" (C). Cheap, reversible.
- Change: a Quick Settings tile and a 1x1 widget that enqueue the existing
  one-shot worker. Nothing else changes.
- Acceptance measure: manual rotations / all rotations over 14 days; target
  above 10% shows the affordance is used; guardrail: no rise in failed rotations.
- Experiment: pre/post with the ledger from item 1.

### 3. Clock-safe photos (legibility)

- Problem: the lock-screen clock and notifications render on top of our photo;
  a bright or busy top third makes them unreadable, and Samsung users report
  clock problems over image wallpapers.
- Evidence: WCAG 1.4.3 and F83 (A, normative); Samsung and Kustom forum
  reports (D); iOS "Don't feature photo" (C).
- Change, smallest: on each thumbnail, a preview with the clock region
  overlaid; a per-photo "skip" that keeps it in the group but out of
  rotation; a warning when the top third's mean luminance is high. Later:
  automatic crop that places the subject low in the frame.
- Acceptance measure: undo_within_10_min / rotation_applied falls by a third
  over 14 days; guardrail: photos marked skip stay under 20% of a group.
- Experiment: pre/post with ledger; A/B when there are enough installs.

### 4. Time-of-day groups

- Problem: one group all day ignores the strongest published signal we have:
  photos tied to the current time of day are meaningful.
- Evidence: PhotoClock field study (B); existing "At set times" mode already
  carries the mechanism.
- Change: let each time slot name a group ("7:00 from Mornings, 19:00 from
  Family"). Optional rule: prefer photos whose EXIF time is near the current time.
- Acceptance measure: adoption of set-times mode among rotation-on users above
  15% after 30 days; retention of rotation-on at day 14 no worse than interval mode.

### 5. Automatic groups on device (the owner's ask), as an opt-in experiment

- Problem: building groups by hand is the main setup cost; "family" is the
  group people want (Durrant 2009, C; owner).
- Evidence: Google Photos Library API cannot read a user's library since
  2025-03-31 (A, factual); iOS Photo Shuffle categories prove the demand shape
  (C); no paper found on acceptance of on-device face clustering (open).
- Change, smallest: an opt-in "Suggest groups" that asks for partial media
  access (`READ_MEDIA_VISUAL_USER_SELECTED`), runs ML Kit face detection plus
  an on-device embedding and clustering over the selected photos, and proposes
  named clusters the user confirms. Full-library access and background
  refresh come only if the opt-in is accepted by most users.
- Acceptance measure: opt-in rate among users with 2+ groups; suggested groups
  still assigned to a screen 7 days later above 50%; zero network use (verified
  by the absence of the INTERNET permission).
- Risks: this is the first media permission in the app and reverses the
  contract in AGENTS.md; needs the owner's explicit decision and a privacy
  line in the store listing. Model size and battery.

### 6. Live wallpaper engine (motion, per-posture crops)

- Problem: static wallpapers cannot animate and, outside Samsung, cannot be
  cropped per display.
- Evidence: Muzei parallax request 7 reactions (D); owner priority; platform
  audit section 4 (A for feasibility). Battery complaints about live
  wallpapers in reviews (D) are the counter-signal.
- Change: build after items 1 to 3 have data; gate on day-14 rotation-on
  retention from the ledger.
- Acceptance measure: battery delta under 1%/day on the Fold in a 24-hour
  test; rotation retention not lower than the static path.

## Do not build

| Idea | Why not |
|---|---|
| Google Photos album sync or "family" from Google's API | Library API only reads app-created media since 2025-03-31; Picker API is one-off. Impossible, not merely hard. |
| Online wallpaper sources | Muzei's domain-down incident (7 reactions) shows the failure mode; the app's offline promise is a feature. |
| Exact alarms | User-granted permission and Play policy review for no visible gain on a wallpaper. |
| Folder watching with full media access as a default | Pre-empts the permission decision in item 5; ship the opt-in first. |
| DeX or virtual-display wallpapers | Samsung-only bit 8, no reported demand. |

## Research ledger

| Date | Hypothesis | Segment | Evidence | Proposed change | Measure | Owner | Decide by |
|---|---|---|---|---|---|---|---|
| 2026-10-13 | Users keep rotation on when they can skip a bad photo fast | all | D x4 reports, C iOS | tile + widget | manual share, failures | owner | 2026-10-27 |
| 2026-10-13 | Clock legibility drives undo | lock-screen users | A normative, D reports | clock overlay preview, skip | undo rate | owner | 2026-10-27 |
| 2026-10-13 | Time-of-day groups feel meaningful | set-times users | B PhotoClock | per-slot group | adoption, retention | owner | 2026-11-10 |
| 2026-10-13 | People accept on-device face grouping with partial access | 2+ groups | C iOS, open | opt-in suggest groups | opt-in rate, kept groups | owner | 2026-11-10 |
| 2026-10-13 | Described groups (labels + Gemini Nano) find what people mean well enough to keep | all | C iOS categories, A Photos API gone | shipped: "Describe it" groups, media permission on first use | kept described groups after 7 days, excluded/found ratio | owner | decided, measure after ledger |
| 2026-10-13 | Motion is worth a live engine | foldable owners | D Muzei, owner; C Wallpaper Engine's Android app is animation-first but file-based, no photo grouping | decided 2026-10-14: 0.2.0 is a live engine rendering the user's own photos (cross-fade, drift, parallax, depth, per-posture crop); static path stays as fallback | battery per hour vs static, engine kept after 7 days | owner | 0.2.0 |
| 2026-10-14 | A described group over a 20k-photo library is usable when labels are computed once and the library lives in MediaStore | owner, large-library users | owner ask; platform: picker 100/pick, bounded persisted grants (audit §6) | shipped: SQLite PhotoIndex + IndexLibraryWorker; guide users to keep photos on device | index time per 1k photos, scan latency on 20k, matches kept | owner | measure on a 20k device |
| 2026-10-14 | A native screen saver (DreamService) that plays a group is the cheapest home for Apple-like motion | charging/docked users, foldable owners | A normative (public API since 17), owner ask | build DreamService over SettingsStore with crossfade + Ken Burns | screen saver enabled rate, time shown | owner | 2026-10-28 |

## Follow-ups

- Record outcome in this file after each experiment, graded the same way.
- `sage suggest feedback outcome product-manager --status passed` was recorded
  for this pass.
