# Architecture

One module, package `dev.fengine.wallpaper`. The rule that shapes it: Android
classes do I/O and stay thin, and every decision lives in plain Kotlin with a
jqwik property next to it. That is what lets a wallpaper app have a real test
suite.

## Layers

```
ui/            MainActivity, HomeScreen, GroupScreen, MainViewModel, design/ (tokens, components, layout)
data/          Settings, Group, PhotoId, SettingsCodec (pure), SettingsStore (DataStore)
schedule/      RotationPlan (pure), RotationScheduler, RotateWallpaperWorker, alarm + boot receivers
wallpaper/     WallpaperCrop (pure), Displays, SamsungDisplays (pure), WallpaperApplier
smart/         MediaLibrary, Labeler (ML Kit), PhotoIndex (SQLite), DescriptionMatcher (Nano, keywords),
               ScanPlan (pure), SmartGroupWorker, IndexLibraryWorker, AiReadiness
screensaver/   KenBurns (pure), DreamPlan (pure), GroupDreamService
```

## Data

`Settings` is one DataStore preferences file: groups as a JSON string (order
matters), home and lock group ids, screen saver group id, mode, interval,
times of day, shuffle, enabled, last result. `SettingsCodec` maps it both
ways and never throws on a bad value; dangling group ids are dropped on read.

`Group` holds picked photo URIs, a description, matched URIs, exclusions, the
pool of picker photos a description may search, the cover and a cursor.
`allPhotos` is picked then found, one entry per photo: `PhotoId.identity`
collapses the picker's and the library's URI for the same file.

`PhotoIndex` (`photo_index.db`) keeps ML Kit labels per photo identity and
Gemini Nano verdicts per rule key. Labels are computed once per photo.

## Rotation

`RotationScheduler` arms WorkManager periodic work (interval mode, 15 minute
floor) or an inexact `AlarmManager` alarm (time-of-day mode). The worker takes
the next photo per screen from `RotationPlan`, and `WallpaperApplier` decodes
it once at the largest panel's size, computes a crop per target panel with
`WallpaperCrop.fit`, and calls `WallpaperManager.setBitmap` once per expanded
`which`. On Samsung foldables that is two calls, inner and cover, each with
its own crop.

## Described groups

1. `IndexLibraryWorker` labels the library newest first in slices and
   re-enqueues itself until every photo has a row.
2. `SmartGroupWorker` labels a group's pool, asks the matcher which labels
   relate to the description, orders candidates by relevance
   (`ScanPlan.ordered`) and runs a budgeted pass (`ScanPlan.pass`): cached
   verdicts first, then Gemini Nano on the pixels, labels only when no model
   can look. A Nano refusal falls back to the labels for that photo and is not
   cached. Matches are appended to the group; a note explains what happened.
3. Both repeat on a schedule; the scan note and an index progress line keep
   the user informed.

## Screen saver

`GroupDreamService` is the system Screen saver. It reads the chosen group,
orders it with `DreamPlan`, decodes each photo at the view's size, cross-fades
two `ImageView`s and drives a `KenBurns.frame` matrix animation. The frame
maths is proven never to show an edge.

## Invariants

`app/src/test/kotlin/dev/fengine/wallpaper/invariants/` holds the properties:
crop inside, non-empty, maximal, aspect-exact and focal; rotation index in
range and no repeat; time-of-day always in the future and on a slot; codec
round trips and never-crash on garbage; layout monotonic in width; settings
file round trip, dangling ids dropped, v0.1 migration; scan pass returns only
candidates, never excluded or seen photos, judge calls within budget, cached
verdicts verbatim; photo identity unifies picker and library URIs only;
drift covers the view and is continuous; play order is a permutation.
