# Performance

Measured with `scripts/profile-live.sh` (process CPU time from `/proc/<pid>/stat`,
frame counts from the engine's own log, memory from `dumpsys meminfo`) in three
windows: idle, launcher page swipes, and photo changes.

## Live engine, Pixel 8 emulator, API 36, host GPU emulation (2026-10-14)

Two rounds. The first drew on the main thread; a hardware canvas post stalled
for nine seconds in the emulator's GPU path during a fade, WorkManager's job
could not start, and the system killed the process (ANR "No response to
onStartJob"). Every state change and draw now runs on a dedicated render
thread, and after two canvas posts over 500 ms the engine switches itself to
the software canvas. Second round, same windows, zero ANRs; the emulator still
stalled two fades (6.4 s and 7.0 s) and that cost frames and nothing else.
Third round with the fallback in place: five fades at 909 to 931 ms, 38 to 42
frames each, no stalls, no ANRs. Idle at steady state is 0 ms of CPU and 0
frames over 30 s; the one second of CPU seen right after an install is
WorkManager rescheduling and JIT, not the engine. Thread totals over the whole
run: the engine's render thread 30 ms, HWUI's own render thread 960 ms, the
garbage collector 1,030 ms (bitmap churn, which hardware bitmaps avoid on
phones).

| Window | CPU | Frames |
|---|---|---|
| idle, 30 s, launcher visible | 0 ms | 0 |
| 20 page swipes | 40 ms | one frame per offset event |
| 5 photo changes (worker + decode + 900 ms cross-fade) | 1,230 ms total, 246 ms each (round 3) | 38 to 42 frames per fade |

Memory after the run: native heap 25 MB (two decoded photos alive during a
fade, one otherwise), Java heap 5 MB.

What the numbers say:

- At rest the engine costs nothing. There is no render loop; the only frames are
  responses to launcher offsets, system zoom, a photo change or a surface change.
- A photo change is dominated by the decode and the settings write in the
  rotation worker, not by the fade. It happens once per rhythm tick, so 268 ms
  of CPU per hour at the default rhythm.
- Fade frame counts vary with the emulator's load; the fade is driven by the
  display's frame callbacks and stops the moment it ends or the wallpaper
  becomes invisible.

Not measured yet: a real phone (the Pixel was locked during this pass), battery
per hour against the static path, and hardware-bitmap memory, which the
emulator reports as zero graphics memory because it decodes to software.

## Live engine, Galaxy Z Fold 8, inner screen 2448x1848 at 120 Hz (2026-10-14)

Real cloud photos from Google Photos, 12 MP originals, the owner's Family
group, engine applied through Samsung's chooser.

| Window | Round 1 | Round 2 (exact-size decode, 60 fps fade cap) |
|---|---|---|
| idle, 30 s | 0 ms, 0 frames | 40 ms, 0 frames |
| 20 page swipes | 90 ms | 90 ms |
| 5 photo changes | 684 ms each, 110 frames per fade | 418 ms each, 55 frames per fade |
| graphics memory after | 461 MB | 219 MB |

What the Fold taught: a power-of-two sample size left 12 MP photos at full
size, 49 MB per hardware bitmap, and Samsung keeps several engine instances
alive, so memory multiplied. Decoding to exactly the frame's needs halved it;
the remaining 219 MB is the instance count, which the engine now logs so the
next round can say how many there are and whether invisible ones should drop
their bitmaps. The 120 Hz panel doubled fade frames for no visible gain; the
fade is capped at 60 per second.

Samsung's lock screen does not show a live wallpaper. One UI keeps a static
image there (`mWhich=11` stays `ImageWallpaper`), the app detects that the
engine does not own the lock screen, and the rotation keeps writing static
photos to it. The home screen is the engine's. The cover screen keeps the
static image too; the chooser applies the engine to the active display only.

## Wake-ups with the screen off, Pixel 8 emulator (2026-10-14)

`scripts/profile-wake.sh <serial> 20`: device "on battery", battery stats
reset, screen off for the window, rhythm set to 15 minutes, live engine on
both screens.

| What | Result |
|---|---|
| Wake-ups of the process | 2, the rotation worker at 15:18:55 and 15:33:55, 15 minutes apart to the second |
| CPU over 20 minutes | 140 ms, about 70 ms per rotation (cursor advance and the settings write; no decode) |
| Wake locks held by the app | none |
| Alarms | none in interval mode; time-of-day mode arms one inexact alarm |
| Frames drawn | 0 |

Deferred decode, verified after this run: three rotations with the screen
off produced no decode and no frame; waking the screen produced exactly one
fade, to the latest photo. The engine's state machine proves the rule in the
JVM suite (never decodes while invisible, owes exactly one decode when visible
again); this is the on-device confirmation.

What a phone adds: Doze. WorkManager's periodic work is deferred into Doze
maintenance windows, so overnight the two-per-half-hour count above becomes a
handful per night, and the rotation now requires the battery not to be low.

## Wake-ups with the screen off, Pixel 8, Android 17 (2026-10-14)

Same script, 20 minutes, phone on battery, live engine on both screens,
rhythm 15 minutes.

| What | Result |
|---|---|
| CPU over 20 minutes | 970 ms for the whole process |
| Context switches | about 530 |
| Wake locks held by the app | none; only system wake locks appeared |
| Estimated drain (batterystats) | 0 mAh attributed to the app |
| Frames drawn | 0 |

The phone spends more CPU than the emulator on the same two rotations:
WorkManager's own database and the DataStore write cost more on a real
kernel with Doze transitions. It is still under a second per 20 minutes.

## Library indexing, Pixel 8 (2026-10-14)

1,503 synthetic 640x480 photos labeled in three slices, about 90 ms per photo,
index 287 KB. Real camera photos decode slower.

## Settings navigation, phone emulator, API 36, debug (2026-10-17)

`emulator-5580`, arm64, 1080×2400 px at 420 dpi, font scale 1.0, light mode,
Skia/OpenGL. Warm running activity; one Smoke group with eight synthetic photos,
rotation paused. Ten Home → Settings → Back cycles, 20 transitions. Taps used
bounds from a fresh UI XML dump at each step. UIAutomator waited between taps;
this is not a continuous scrolling or live-wallpaper workload.

Reset `dumpsys gfxinfo dev.fengine.wallpaper` before the cycles, then collected
`framestats` and `dumpsys meminfo`. Local script and raw files are under
`build/settings-device-check/`: `profile-ui.py`, `profile-gfxinfo.txt`,
`profile-memory-before.txt`, `profile-memory-after.txt`, `profile-summary.json`.

| Measure | Result |
|---|---|
| Completed frames in sample | 40 |
| HWUI-reported janky frames | 21 / 40 (52.5%) |
| HWUI histogram p50 / p95 | 32 / 65 ms |
| `FrameCompleted − IntendedVsync` p50 / p95 | 17.56 / 66.91 ms, nearest-rank |
| GPU histogram p95 | 17 ms |
| Whole-process PSS, before / after | 101,109 / 104,633 kB |
| Native-heap PSS, before / after | 19,941 / 13,331 kB |
| Reported graphics memory | 0 kB; not representative of phone hardware bitmaps |

The HWUI histogram and raw end-to-end frame timestamps are different metrics;
do not treat their medians as interchangeable. The launch command delivered
an intent to an already running activity; its 0 ms result is not startup time.

This is a debug/emulator baseline, not a before/after optimization result.
Reinstall/instrumentation had already detached the live engine from Home;
this run does not reproduce the live-engine profiles above. Process memory includes prior preview state and caches.
Two endpoint samples do not establish a leak or steady-state memory cost.

Next: record release-equivalent traces on a disposable emulator and the Fold,
with matching data and warm-up. Separate Compose/layout, group thumbnail decode
and GPU submission before changing code. The small jank sample warrants that
trace, not an inferred decode bottleneck or a performance rewrite.
