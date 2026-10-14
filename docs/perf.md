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

## Library indexing, Pixel 8 (2026-10-14)

1,503 synthetic 640x480 photos labeled in three slices, about 90 ms per photo,
index 287 KB. Real camera photos decode slower.
