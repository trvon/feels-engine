# Changelog

## [0.3.0](https://github.com/trvon/feels-engine/compare/feels-engine-v0.2.1...feels-engine-v0.3.0) (2026-10-15)


### Features

* **icon:** adaptive launcher icon ([ded36b2](https://github.com/trvon/feels-engine/commit/ded36b26c6fc782f17ed92bf19f5756646e4e498))
* **live:** wallpaper engine with cross-fade, parallax, zoom and per-posture decode ([75579bc](https://github.com/trvon/feels-engine/commit/75579bc3d16e2dc85b86f37d4b923c8f9ea3a1e5))
* **site:** landing page and privacy policy on GitHub Pages ([7fc191f](https://github.com/trvon/feels-engine/commit/7fc191fb8c9af5307ef48758d0da33b69c67c79c))


### Fixes

* **live:** a lost surface gets its frame back ([e9ab27d](https://github.com/trvon/feels-engine/commit/e9ab27d463c1b618349954f6783d2f1af74bde1a))
* **live:** a redraw request counts as visibility; a refused draw stays owed ([fc18f79](https://github.com/trvon/feels-engine/commit/fc18f7911ae47284ec58fbc3d4312401832b1041))
* **live:** render on a dedicated thread; fall back to software after two stalled canvas posts ([e2c22c2](https://github.com/trvon/feels-engine/commit/e2c22c2e776ab53ee6bfa03c42758de2f69eaeb1))


### Performance

* **live:** decode at exact size, cap fades at 60 fps, log engine instances ([41caabc](https://github.com/trvon/feels-engine/commit/41caabc5dab2ab031b828c1a1bda1a95bfb991f3))
* **live:** no decode for a screen nobody sees; rotation waits on a low battery ([0226b89](https://github.com/trvon/feels-engine/commit/0226b89d20a36e1a59af38eb34728964f84b469d))

## 0.2.1 (2026-10-15)

- Fold fix: the inner screen no longer comes back black after the phone was
  closed. One UI does not always tell a live wallpaper it is visible again;
  the engine now treats the system's redraw request as visibility and always
  answers it with a frame (`LiveEngineState.onRedrawNeeded`). A surface that
  was destroyed is redrawn when it returns; a draw the surface refuses stays
  owed. Three new properties cover these rules.
- Keyboard goes away on Done and on a tap outside the field.
- Described groups: the library permission is explained in the app's own
  words before the system dialog, with the photo picker offered as the other
  way (Play photo permissions policy).
- Look row reads both panels of a foldable and says where motion is on.

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
