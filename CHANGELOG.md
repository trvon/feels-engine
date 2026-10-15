# Changelog

## 0.2.0 (2026-10-14)

- Live wallpaper engine: motion over your photos. Cross-fade on change,
  parallax with the home screen pages, the system zoom on transitions, a
  fresh crop per posture on foldables. Zero frames at rest; no decode while
  the screen is off. The static path stays for screens the engine does not
  draw. Measured in `docs/perf.md`: idle 0 frames, a fade 40 to 55 frames in
  about 0.9 s, two wake-ups per 30 minutes with the screen off, no wake locks.
- Look: Still or Motion, read from what the system reports. Motion opens the
  system chooser; Still writes the current photos over the engine.
- Set active replaces Change now: pick a group and its cover goes up on both
  screens. Next photo advances by hand. Tap a photo in a group for Show now
  or Make the cover.
- Foldables: a group can take the cover screen on its own (Cover screen switch
  on the group page, Samsung display bits). Two UX lanes: choices are a bottom
  sheet on phones and a centred card on an unfolded phone or tablet.
- One UI: the live state is read per panel. The chooser puts the engine on
  whichever panel is active when you tap Set, and the app writes still photos
  only to the panels the engine does not draw. Before this a rotation on a
  closed Fold could replace the engine with a still photo (issue #1).
- Rotation waits on a low battery. Group names are trimmed.
- Debug builds take `dev.fengine.wallpaper.ROTATE_NOW` and `PROBE` from adb
  for the device loop.
- The engine's event rules, the placement maths and the rotation decision are
  pure and property tested; the SQLite index and DataStore have on-device
  tests.

## 0.1.0 (2026-10-14)

First tagged release: github.com/trvon/feels-engine/releases/tag/v0.1.0.

- Website and privacy policy on GitHub Pages (`site/`, Pages workflow).
- CI on every push (tests, lint, debug APK) and signed APKs attached to
  tagged GitHub Releases (`docs/release.md`).
- Android 9: thumbnails fall back to a small decode (lint NewApi).
- README screenshots in `docs/screenshots`.
- Dependencies: AGP 9.4.1, Gradle 9.6, Compose BOM 2026.09.00, lifecycle
  2.11, WorkManager 2.12, DataStore 1.2.1, JUnit 6.1, jqwik 1.10.
- Rename to Feels Engine; Apache License 2.0.
- Screen saver: a group plays under Settings > Display > Screen saver, with
  cross-fades and a slow drift (`screensaver/GroupDreamService`).
- Photo index moved to SQLite; the whole library is labeled once in
  background slices (`smart/PhotoIndex`, `smart/IndexLibraryWorker`).
- Samsung foldables: inner and cover panels each get their own crop, also
  while the phone is closed.
- Described groups: a Gemini Nano policy refusal is no longer counted as a
  match; people words ("family", "friends") reach the label vocabulary;
  picked and found copies of one photo are shown once.
- Group page: photos before settings, visible pool, honest scan notes.

Earlier in the same version, 2026-10-13:

- Picked groups, home and lock assignment, interval and time-of-day
  rotation, shuffle.
- Described groups with ML Kit labels and Gemini Nano judging.
- Samsung display bits for both foldable screens.
