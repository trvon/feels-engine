# Settings organisation and presentation (product-manager pass, 2026-10-25)

## Decision

How should Settings group its options and show each choice, for the owner
(a parent with a Pixel and a Galaxy Fold who opens Settings rarely), measured
by fewer wrong taps in the UI tests, fewer surprises in the local log, and the
owner's report?

| Item | Value |
|---|---|
| Segment | Owner and owner-like users: set once, adjust rarely |
| Job | Change how often photos change, how they look, and when the screen saver plays, without reading paragraphs |
| Today | Settings (70269a3): radio rows for Rhythm and Look, chips for the interval, a free-text field for set times, a switch for the screen saver's charger rule, a read-only Appearance block |
| Decision date | 2026-11-08 (owner) |

## Evidence, graded

| Claim | Source | Grade | Applicability |
|---|---|---|---|
| Single-select segmented buttons are for one choice out of 2 to 5; more than five choices should use chips; labels short, never wrapping; not full width on large screens | Material 3, Segmented buttons guidelines (m3.material.io) | A (normative) | Rhythm (2), Interval (4), Look (2), Theme (3), Colours (2), Backgrounds (2), Charger (2) all fit |
| Material 3 expressive replaces segmented buttons with the connected button group | same page | A | The resolved library (material3 1.4.0) has `SegmentedButton` and no button group; switch when a stable release ships it |
| Compose `SingleChoiceSegmentedButtonRow` is the supported API | developer.android.com, Compose segmented button (updated 2026-10-22) | A | Direct |
| Touch targets at least 24 by 24 CSS px (AA), 44 (AAA); the app's own rule is 48 dp | WCAG 2.2 SC 2.5.8 / 2.5.5; docs/design-system.md | A | Segments are 48 dp tall |
| Wired screen-saver vetoes were 366 of 475 log entries over 9 days, in bursts on a desk-type charger (plug type AC) | Owner's Fold log, 2026-10-16 to 10-25 | B (counts), n=1 device | The "wireless or dock only" default surprises the owner at home; the switch's meaning is not obvious |
| Free-text time entry ("7:00, 19:00") rejects partial input and needs a Save step | Settings code, `TimeSlots.parse`; 70269a3 added explicit Save to avoid partial saves | C (heuristic) | A picker removes the parse failure mode |
| Owner asked for options as buttons and for theme controls in Settings | Owner, 2026-10-25 | D | The person this app is for |

## Ranked changes

1. **Every single choice of 2 to 5 options is a segmented button.** Rhythm
   (Every so often, Set times), Interval (15 min, Hour, 3 hours, Day), Look
   (Still, Motion), Screen saver charger (Any charger, Wireless or dock). One
   design component, `Segmented`, in `ui/design/Components.kt`.
   Measure: `SettingsUiTest` selects each segment and reads the state back.
2. **Set times are chips with a time picker.** Each time is a chip with a
   remove action; "Add time" opens Material's time picker; no free text, no
   Save step. Measure: a UI test adds and removes a time; the parse error path
   is gone.
3. **The screen saver says what it did this week.** One line from the log, for
   example "Skipped 47 times on a cable this week", under the charger choice.
   Measure: wired vetoes in the log fall after the owner picks Any charger, or
   the owner keeps Wireless and stops reporting it.
4. **Appearance becomes three choices** (Theme: System, Light, Dark; Colours:
   From photo, Phone; Backgrounds: Black, Photo colours), replacing the
   read-only block. Built in Part 3 of the plan.
5. **Order by how often each is changed:** Rotation, Look, Appearance, Screen
   saver. Home keeps daily actions (unchanged from 70269a3).

## Do not build

| Idea | Why not |
|---|---|
| Per-group schedules | No reported need; one rhythm covers the owner's use |
| Settings search, nested pages | Four groups fit one page |
| Connected button groups now | Not in the stable library; segmented buttons are the documented Compose API today |
| Chips for two-option choices | Material reserves chips for more than five or for filtering |

## Research ledger

| Date | Hypothesis | Evidence | Change | Measure | Decide by |
|---|---|---|---|---|---|
| 2026-10-25 | Segmented buttons make single choices faster to read and set | A M3 guidance, D owner | ranked change 1 | UI tests; owner report | 2026-11-08 |
| 2026-10-25 | A time picker removes failed time entries | C heuristic | ranked change 2 | no parse errors; UI test | 2026-11-08 |
| 2026-10-25 | Showing the week's screen-saver skips explains the charger rule | B log counts | ranked change 3 | wired vetoes per week | 2026-11-08 |
