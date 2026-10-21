# Contributing

Issues and pull requests are welcome. Small, tested changes land fastest.

## Git hooks

Requires Python 3.11+, Java, Gitleaks and OpenGrep on `PATH`. Gradle uses
`gradle/gradle-daemon-jvm.properties`; cache that JDK too. Tested with Gitleaks
8.30.1 and OpenGrep 1.25.0. Build once to cache the wrapper, SDK and dependencies,
then install:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
python3 scripts/dev-checks.py install
```

Installation downloads ktfmt 0.64 from Maven Central, verifies its pinned
SHA-256 and sets this checkout's `core.hooksPath` to `.githooks`. It refuses
to replace existing hooks. No global Git configuration or app dependency changes.

- Pre-commit scans complete staged blobs with Gitleaks and checks changed
  Kotlin files with ktfmt's Kotlin style. Unstaged replacements cannot hide a
  staged secret. Signing keys and private/local artifacts are rejected.
- Pre-push scans outgoing history for secrets, including secrets later removed,
  checks changed Kotlin files, scans tracked app code with local OpenGrep rules,
  then reruns all JVM tests/properties and lint. It requires a clean checkout
  at the pushed tip; other branch tips are rejected. Ref deletions skip tests.
- Checks never reformat or stage files. Missing tools and findings fail closed.
  Hooks do not download scanners/ktfmt; Gradle runs offline and requires its
  wrapper cache. No device commands run.

Apply formatting explicitly, then stage the result again:

```bash
python3 scripts/dev-checks.py format --write path/to/File.kt
python3 scripts/dev-checks.py rule-tests
python3 -m unittest discover -s scripts/tests -v
python3 scripts/dev-checks.py security  # tracked HEAD, not unstaged edits
python3 scripts/dev-checks.py fuzz
```

Existing formatting debt is not rewritten during setup. OpenGrep currently
checks AES/ECB use and always-accepting TLS hostname verifiers in Kotlin;
manifest/privacy contracts run in the JVM suite. The fuzz gate forces fresh
jqwik randomized trials and shrinking, not coverage-guided fuzzing. Failures
print replay seeds. Local hooks can be bypassed; they do not replace CI or
review. Uninstall with `git config --local --unset core.hooksPath`.

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
- No photo or media permissions. All photos come through the system picker
  with persisted URI grants.
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
