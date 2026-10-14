---
name: kotlin-kiss
description: Refine and simplify fengine Kotlin without speculative feature growth. Use when planning, implementing, refactoring or reviewing Kotlin in this repository. Require connected behavior, tests and concrete value; keep useful abstractions.
---

# Kotlin KISS

Refine and sand the existing app. Code growth is not progress. Reduce the number
of concepts needed to understand it, not just line count.

Follow `AGENTS.md` for rules, the platform contract and the device loop. This
skill adds a decision rule, not another workflow.

## Value before expansion

Before changing code, name the problem, the real caller, the concrete value and
how the behavior will be validated. Add a feature only for a demonstrated need
within the requested scope. If need or value is unproven, keep it a proposal.

Specific to this app:

- No Hilt, Room, Coil or navigation libraries until a screen needs them. One
  Activity, one ViewModel, one DataStore is the current shape.
- No new permissions. Every permission in the manifest has a comment saying why.
- No exact alarms, foreground services or live wallpaper engines without an
  owner decision recorded in `docs/research/`.

## Useful abstractions

`Target` mapping onto `WallpaperManager` flags, `RotationPlan` as pure
arithmetic, and `RotationScheduler` owning both trigger mechanisms are
deliberate seams. Keep them. Do not add managers, factories or config knobs for
hypothetical consumers.

## Connected and tested

- Trace the change from the UI (or receiver) through `SettingsStore`,
  `RotationScheduler` and `WallpaperApplier` to the `WallpaperManager` call.
- Pure logic gets a JUnit test in `app/src/test/`. Android-bound code gets the
  device loop in `AGENTS.md`.
- Build and unit tests pass (`./gradlew :app:testDebugUnitTest :app:assembleDebug`)
  before a change is called done.
