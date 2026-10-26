# Release review, v0.4.0..HEAD (2026-10-25)

Scope: 33 files, about 2,800 lines changed since v0.4.0. Four read-only
reviews (scheduling, live engine and screen saver, data and privacy, UI),
46 findings. Each was checked against the code before a fix.

## Fixed

| Commit | Theme | Findings |
|---|---|---|
| cbe5807 | crash safety | hardware bitmap on a software canvas threw; engine, service and screen saver coroutines had no handler; screen saver retried decodes in a tight loop; alarm receiver let errors escape and skipped re-arming; crash handler could loop on a cyclic cause |
| 8c415e8 | scheduling | set times off by an hour across DST; a failing write re-armed every minute; a future timestamp stalled rotation; unreadable-photo retry moved other groups and dropped pins; removing a photo restarted the group; worker retries advanced Next again; no re-arm after a force stop; wake-up alarms when Motion draws every screen; dark-photo measuring unbounded on the alarm path; Motion check raced Still |
| ff15b0d | log and privacy | one change counted once per screen written; exits re-imported after halving; `user_requested` before Android 14 was an update; setBitmap message text in the log; one crash frame under R8; non-atomic halving; engine log writes on the render thread; export did not truncate; empty or invalid set times |
| e523af8 | UI | photo colours below 4.5:1 as text in light mode; slider truncation and percentage announcement; disabled switches hid their value and reason; segment labels cut at 360 dp; 24 h clock only; duplicate time silently ignored; picker clipped in landscape; binder calls on the main thread |
| 8435651 | screen saver, engine | a vetoed screen saver kept the screen on while charging; whole-photo saver redrew 60 fps; colours decoded again on every resize |

The property suite found one more bug while it was being written: the dark-photo
pick started one photo off when the stored cursor pointed past the group's end
(381bab7).

## Verified

- JVM: 150 tests (jqwik properties included), lint without errors.
- fe_phone (Android 16): 7 of 7 on-device tests. Vetoed screen saver on a
  simulated cable: one start, then `Asleep`, screen off, no restart (was
  `Dreaming` with the screen on for 4+ min).
- fe_api28 (Android 9): smoke matrix, every check PASS.
- fe_fold (Android 16): monkey, 25,000 events over three seeds, light and dark,
  no crash or ANR. Double tap, light/dark switch and dark photos at night checked
  by hand (see the commits).

## Open

- One UI "double tap to turn off screen" may also send the taps to the wallpaper,
  so tap-to-change would change the photo while turning the screen off. Needs a
  check on the Fold 8.
- Every rotation rewrites the whole settings file (groups included). Cursors and
  the last result could move to their own keys if large described groups make
  this costly.
- Cursors are applied by index after the write; a group edited during a write can
  shift which photo the index names. Rare; fix by storing the URI.
- The engine runs a due check on every return to the home screen. Cheap (no
  disk write when nothing changed), not throttled.
- Timing numbers from this round are not valid: the host ran at load average
  216 from other work. Frame counts hold (0 frames at rest). The Pixel 8 round
  still waits for the uninstall of 0.2.1.
