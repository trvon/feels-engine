# Performance

Measured with `scripts/profile-live.sh` (process CPU time from `/proc/<pid>/stat`,
frame counts from the engine's own log, memory from `dumpsys meminfo`) in three
windows: idle, launcher page swipes, and photo changes.

## Live engine, Pixel 8 emulator, API 36, host GPU emulation (2026-10-14)

| Window | CPU | Frames |
|---|---|---|
| idle, 30 s, launcher visible | 0 ms | 0 |
| 20 page swipes | 40 ms | one frame per offset event |
| 5 photo changes (worker + decode + 900 ms cross-fade) | 1,340 ms total, 268 ms each | 8 to 35 frames per fade |

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

## Library indexing, Pixel 8 (2026-10-14)

1,503 synthetic 640x480 photos labeled in three slices, about 90 ms per photo,
index 287 KB. Real camera photos decode slower.
