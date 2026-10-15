# Feels Engine design language

Editorial, calm, photos first. A page reads top to bottom like a magazine
spread: an eyebrow, one serif headline that states what is happening, sections
with small-caps eyebrows, generous vertical rhythm, one filled button at most.

## Where it lives

| What | File |
|---|---|
| Tokens: spacing, radii, grid, sizes | `app/src/main/kotlin/dev/fengine/wallpaper/ui/design/Tokens.kt` |
| Components | `app/src/main/kotlin/dev/fengine/wallpaper/ui/design/Components.kt` |
| Theme: Material You colours, serif display type | `app/src/main/kotlin/dev/fengine/wallpaper/ui/Theme.kt` |
| Copy helpers | `app/src/main/kotlin/dev/fengine/wallpaper/ui/Format.kt` |
| Responsive rules (columns, cover width, max content width by window width) | `app/src/main/kotlin/dev/fengine/wallpaper/ui/design/Layout.kt` |
| Enforcement | `app/src/test/kotlin/dev/fengine/wallpaper/ui/DesignLanguageTest.kt`, `invariants/LayoutSpecProperties.kt` |

## Small phones, tablets, foldables

`LayoutSpec.forWidth(windowWidthDp)` is the only place that reacts to size.
Compact widths keep 3 columns and 128 to 150 dp covers; medium (unfolded
foldables, small tablets) goes to 4 columns and 170 dp; expanded (tablets) to
6 columns and 190 dp. Content is capped at 600, 720 or 840 dp and centred so
text lines stay readable on wide screens. Chip rows wrap instead of clipping.
`Page` provides the spec through `LocalLayout`; components read it, screens
never branch on width themselves.

Wallpaper placement is separate from UI layout: `WallpaperCrop.fit` computes an
explicit crop per target display (phone, tablet, cover and inner panels on a
foldable) and `Displays` supplies the sizes, so the system never guesses.

## Rules

- Screens compose `Page`, `Section`, `Headline`, `Eyebrow`, `Caption`,
  `BodyMuted`, `ChipRow`, `StatusCard`, `PhotoGrid`, `PhotoTile`, `CoverCard`,
  `NewCoverCard`, `ActionBar`, `TextAction`, `SwitchRow`. Material 3 controls appear inside
  those components, or as the page's single `Button`, switches and text fields.
- No raw dp in screens. Every dimension is a token from `Space`, `Radius`,
  `Grid` or `Size`. The test fails the build otherwise.
- One headline per page, serif (`displaySmall`). Eyebrows are `labelLarge`
  upper case in the secondary colour. Body copy in muted `bodyLarge`/`bodySmall`.
- Photos: square tiles in explicit rows of `LayoutSpec.columns` (3 on phones),
  8 dp gap, 12 dp radius; the last row is padded so every tile is the same
  width. Never a flow layout: it breaks rows on a thumbnail's intrinsic width.
  Covers: portrait 0.8, 20 dp radius, 150 dp wide in a horizontal rail. Badges
  are white on a scrim so they read on any photo. Remove is a 26 dp disc inside
  a 44 dp tap target in the top-right.
- A group page shows its photos before its machinery: name, screens, photo
  grid, then "Looks for", where it looks (one caption and a text action, not a
  card) and the on-device AI card last.
- One photo, one tile: the picker and the library name the same file with
  different URIs; `PhotoId.identity` unifies them (`data/PhotoId.kt`).
- One filled button per page ("Set active"). Secondary actions are tonal
  buttons inside a section header or text actions. Destructive actions are text.
- Two UX lanes (`LayoutSpec.lane`). COMPACT (under 600 dp: phones, cover
  screens) puts a transient choice in a bottom sheet, under the thumb. WIDE
  (unfolded foldables, tablets) puts the same choice in a centred card at most
  440 dp wide, because a full-width sheet stretches across the hinge and sits
  far from either hand. `Sheet` in `ui/design/Components.kt` picks the lane;
  screens never call `ModalBottomSheet` or `Dialog` directly.
- Choices that change the wallpaper are explicit and immediate. "Set active"
  lists the groups and puts the chosen group's cover up on both screens. A
  photo's sheet offers "Show now" (that photo, now, rotation continues from it)
  and "Make the cover". The Look row (Still, Motion) reflects what the system
  reports, not a stored preference: Motion opens the system chooser, Still
  writes the current photos as still wallpapers, which replaces the engine.
- Status and guidance are `StatusCard`s: title, body, at most one text action.
  Tone: primary container when ready, secondary container when something is in
  progress or available, surface variant otherwise.
- Copy: sentences, not labels. Say what the app is doing ("Every hour, from
  Family."). Never ids, file names or raw error strings in the UI.
- Accessibility: tap targets at least 48 dp including padding, every image has
  a content description or is explicitly decorative, system text scaling is
  never suppressed, colours come from the Material scheme so dark mode and
  dynamic colour just work.
