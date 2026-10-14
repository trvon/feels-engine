# Contributing

Pull requests and issues are welcome. Keep changes small and tested.

## Before a pull request

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

All three must pass. Pure logic (no Android imports) goes next to its
existing home in `schedule/`, `wallpaper/`, `data/`, `smart/ScanPlan.kt` or
`screensaver/` and gets a jqwik property in
`app/src/test/kotlin/dev/fengine/wallpaper/invariants/`. UI uses the tokens and
components in `ui/design/`; `DesignLanguageTest` fails the build on raw dp
values in screens.

## Rules that do not change without a discussion

- No network permission. Labeling and Gemini Nano run on the phone.
- No exact alarms. Rotation is inexact by design.
- `READ_MEDIA_IMAGES` is asked for only when a described group looks for
  photos.
- Samsung foldables get the display bits on `which`; see
  `wallpaper/SamsungDisplays.kt`.

`AGENTS.md` has the full workflow and the device loop.

## Device testing

Say which device and Android version you tested on. A change to
`WallpaperApplier`, `Displays` or `SamsungDisplays` needs a `dumpsys wallpaper`
excerpt or a screenshot in the pull request.

## License

By contributing you agree that your contribution is licensed under the
Apache License 2.0.
