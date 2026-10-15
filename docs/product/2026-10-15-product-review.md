# Feels Engine product review (product-manager pass, 2026-10-15)

## Decision

What should the cycle after 0.3.0 build, for "a parent who wants their kids'
photos on home, lock and screen saver across a Pixel and a Galaxy Fold",
measured by rotations that land, engine frames that appear, and the app
reaching people through Play?

| Item | Value |
|---|---|
| Segment | Owner and owner-like users: one personal library, Pixel and Samsung foldable, Android 9 to 17 |
| Job | See the family's photos on every screen without managing the app |
| Workaround | Samsung/Pixel built-ins (lock only, or no rotation of own photos), Paperize, manual changes |
| Observed failures this cycle | see "Own data"; every one was reported by the owner or found on a device, none by a user in the field |
| Decision date | 2026-10-29 (owner) |

## Grading the previous pass (2026-10-13)

| Recommendation | Status | Outcome |
|---|---|---|
| 1. Local rotation ledger | not built | No denominators exist. Every decision below again rests on owner reports (D) and device sessions (B, n=1-2). This is the cost of skipping it |
| 2. Next photo outside the app | partly: in-app "Next photo" and "Show now" | no tile or widget; no usage data |
| 3. Clock-safe photos | not built | open |
| 4. Time-of-day groups | not built | open |
| 5. Automatic groups | shipped as descriptions (labels + Gemini Nano), now unified with picked groups | Play rejected version code 1 over READ_MEDIA_IMAGES; declaration filed |
| 6. Live engine | shipped 0.2.0 | three engine bugs reached the owner's phones (black inner screen after a fold, half-faded frame, Show now double apply); battery and wake profile met targets on the emulator and Pixel |

## Evidence, graded

### Normative and platform (A unless noted)

| Claim | Source | Grade | Applicability |
|---|---|---|---|
| Apps targeting Android 13+ may request READ_MEDIA_IMAGES only when the system picker cannot provide core functionality; the one named eligible example is gallery apps | Play policy page, answer 14115180; Play rejection of version code 1 (2026-10-15) | A | Direct. Our declaration argues whole-library search; outcome pending |
| Devices should not display "certain graphical or photographic images" while driving | NHTSA Visual-Manual Driver Distraction Guidelines, Phase 1, 78 FR 24817 (2013), section I.C | A for built-in car systems; for phones the agency asks makers to "consider these principles" until Phase 2 | A cycling photo screen saver on a phone in a car mount is exactly that display |
| Android starts the screen saver while charging, docked or upright; a wireless-only restriction exists; apps cannot change these settings | API 37 Settings javadoc, `dumpsys dreams` on the Pixel 8 | A | A car's USB charger meets "charging" |
| Before Android 14, `getWallpaperInfo` needs QUERY_ALL_PACKAGES; Play restricts that permission | WallpaperManager javadoc; Android 13 emulator returned null for our own engine | A | Fixed without the permission (running-engine count) |

### Own data (device sessions and owner reports)

No telemetry. Facts from devices are B (observed, n=1-2 devices); owner
reports are D (one person, but the target user).

| Observation | Source | Grade |
|---|---|---|
| Screen saver started in the car | owner report | D |
| Inner screen black after closing and opening the Fold | owner report, then One UI framework dump showed the engine never reported visible | B |
| Two photos superimposed / applied in turn | owner report, then state-machine trace and a failing property | B |
| Keyboard did not dismiss | owner report, reproduced on the emulator | B |
| Pick versus Describe as two group kinds limited existing groups | owner report | D |
| Bottom sheets stretch across the unfolded Fold | owner report | D |
| Android 9: descriptions found nothing (relative_path); index crash (AutoCloseable); Android 13: engine overwritten | version matrix, provider error and stack traces | B |
| JVM coverage 18% of lines; pure modules ~100%, Android glue 0%; both shipped engine bugs lived in the glue | JaCoCo report | B |

### Literature

No new paper was needed for these decisions; the 2026-10-13 ledger still
holds (PhotoClock, Durrant et al., WCAG 1.4.3). No study was found on
screen savers or ambient photo displays in vehicles; the NHTSA guideline is
the stronger source there.

## Ranked recommendations

Rule: impact x evidence grade / cost; guardrails unchanged (no network, no
QUERY_ALL_PACKAGES, 48 dp targets, screen-reader names).

### 1. Get through Play review with the picker-only fallback ready

- Problem: no one but the owner can install the app; version code 1 was rejected.
- Evidence: Play policy and rejection (A).
- Change: submit 0.3.0 (shared upload key, in-app explanation before the
  permission, 250-character declaration). Keep the fallback commit (drop the
  two permissions; descriptions search added photos only) ready.
- Measure: review outcome. Decision rule: a second rejection ships the
  fallback within 48 hours; approval keeps whole-library search.
- Owner, on merge of the release PR.

### 2. Build the local ledger, now with engine health

- Problem: three engine bugs and the car screen saver reached the owner
  before any test or measure did; nothing tells us whether a fix holds on
  other phones.
- Evidence: previous pass (A as a prerequisite), this cycle's misses (B).
- Change: an on-device ledger, exportable by the owner, no network:
  rotation result per screen; time from "surface shown" (redraw request or
  visibility) to first engine frame; frames drawn mid-fade then abandoned;
  screen saver start with plug type and verdict (played, car, wired, empty).
- Measure: 100% of rotations and screen saver starts recorded; first-frame
  latency p95 under 500 ms on the Fold over 7 days; zero abandoned blended
  frames.
- Owner, 2026-10-29.

### 3. Default the screen saver to "wireless charging or a dock only"

- Problem: a phone charging in a car shows a cycling photo display to the driver.
- Evidence: NHTSA principle (A for in-car systems, recommended for portable
  devices); owner report (D); platform fact that car chargers count as
  charging (A).
- Change: default the switch on for new installs and say why in one line;
  existing installs keep their choice. Car-mode veto stays.
- Measure (from item 2): screen saver starts by verdict; guardrail: owners
  who turn the switch off within 7 days, a signal that wired desk chargers matter.
- Cost: one line. Reversible.

### 4. Move the remaining engine glue into the state machine

- Problem: 0% coverage where the shipped bugs were.
- Evidence: coverage report and the three engine bugs (B).
- Change: the service's remaining decisions (canvas fallback, fade timing,
  bitmap lifetime) become pure and property-tested; One UI and Android 13
  behaviours stay as events in the harness. The version matrix runs before
  every release.
- Measure: zero engine bugs reported by the owner in the next release;
  matrix green on 9, 11, 13, 14 before each tag.

### 5. Next photo outside the app (tile, widget), carried over

- Evidence unchanged (D x4, C). Cheap; waits for the ledger to measure it.

### 6. Clock-safe photos, carried over

- Evidence unchanged (A normative WCAG 1.4.3). Waits behind 1-4.

## Do not build

| Idea | Why not |
|---|---|
| QUERY_ALL_PACKAGES to read the wallpaper on Android 12-13 | Play-restricted; the running-engine count already answers the question |
| Android Auto projection detection | `getActiveProjectionTypes` is a system API; car mode plus the wireless switch cover the case |
| Screen saver styles | asked once, ambiguously; no evidence of need; the Ken Burns drift is unmeasured |
| Several groups mixed on one screen | no reported need; one group per screen covers the owner's example (1 home and lock, 2 screen saver) |

## Research ledger

| Date | Hypothesis | Segment | Evidence | Proposed change | Measure | Owner | Decide by |
|---|---|---|---|---|---|---|---|
| 2026-10-15 | Play accepts whole-library search with an in-app explanation and a declaration | Play users | A policy, rejection of v1 | submit 0.3.0 with declaration | review outcome | owner | on review |
| 2026-10-15 | Engine first-frame latency and abandoned fades reveal field regressions without telemetry | all live-engine users | B this cycle's bugs | ledger engine-health events | p95 first frame, abandoned blends | owner | 2026-10-29 |
| 2026-10-15 | Wireless-or-dock-only by default prevents in-car display without hurting home use | screen saver users | A NHTSA principle, D owner | default on for new installs | starts by verdict, switch-off rate | owner | 2026-10-29 |
| 2026-10-15 | One kind of group (add photos, describe, or both) serves existing groups better than two kinds | all | D owner | shipped in 0.3.0 | groups with both photos and a description after 14 days | owner | 2026-11-12 |
| 2026-10-15 | Per-screen assignment from one Screens section is clearer than per-group chips | multi-group users | D owner | shipped in 0.3.0 | screens with distinct groups | owner | 2026-11-12 |

## Follow-ups

- Grade items 1-3 here after the review outcome and 7 days of ledger data.
