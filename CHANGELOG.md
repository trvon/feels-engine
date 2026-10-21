# Changelog

## [0.4.0](https://github.com/trvon/feels-engine/compare/v0.3.0...v0.4.0) (2026-10-21)


### Features

* **settings:** separate preferences from photo actions ([70269a3](https://github.com/trvon/feels-engine/commit/70269a33f255c845d9dd317e9bf6a5802642310b))

## [0.3.0](https://github.com/trvon/feels-engine/compare/v0.2.1...v0.3.0) (2026-10-16)


### Features

* **groups:** clock-safe photos: verdict on the photo sheet, skip from rotation ([e44bf00](https://github.com/trvon/feels-engine/commit/e44bf005269b933886e018482b5c69f02cfcefc8))
* **groups:** one kind of group: add photos, describe, or both ([1a95c3e](https://github.com/trvon/feels-engine/commit/1a95c3ed85bc9eb5e35aea7a1e33b185571287ee))
* **log:** local ledger with engine health; engine decisions move into tested rules ([7d2d795](https://github.com/trvon/feels-engine/commit/7d2d7954f8e38b38dad311ae5234c86f9e743d6d))
* **screensaver:** wireless charging or a dock only by default on new installs ([ab442e6](https://github.com/trvon/feels-engine/commit/ab442e61dee6c7faaf0afb9736c050be6be1ed81))
* **screens:** each screen picks its group; screen saver stays out of the car ([6d37e2b](https://github.com/trvon/feels-engine/commit/6d37e2b14c345ce52727247c8330b1aa7af055c5))
* **shortcuts:** Next photo as a Quick Settings tile and a 1x1 widget ([bc90919](https://github.com/trvon/feels-engine/commit/bc90919c8a357f81216079f8b8d387945e250fb7))


### Fixes

* Android 9-13 found by a version matrix; API inventory ([f6d0724](https://github.com/trvon/feels-engine/commit/f6d07244dc25bc36afd2c48227a3d0be7ee5d3c5))
* **groups:** drop photos the app can no longer read instead of failing on them ([fd5d197](https://github.com/trvon/feels-engine/commit/fd5d1972d16f627ba2d4bfdce4d4ea8478da2a20))
* **live:** never rest on a half-faded frame; Show now applies once ([69fc0e2](https://github.com/trvon/feels-engine/commit/69fc0e28b5b44cab494ff3cb2b5421fe6cfd18df))
* **privacy:** no photo permission and no network permission ([63c187b](https://github.com/trvon/feels-engine/commit/63c187ba82c8f52503c0261e593c0bdb9b0583c9))

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
