# Privacy

The app has no network permission and no photo library permission. Nothing it
reads leaves the phone. This is
the developer's view; the policy users and the store see is `site/privacy.html`,
published at https://trvon.github.io/feels-engine/privacy.html.

## Permissions

| Permission | When | Why |
|---|---|---|
| `SET_WALLPAPER` | install | write the wallpaper |
| `RECEIVE_BOOT_COMPLETED` | install | re-arm time-of-day alarms after a reboot |
Every photo arrives through the system photo picker, including the ones a
description looks through (Find photos). There is no library permission
(removed 2026-10-15 after Play's photo policy rejected two submissions).

`INTERNET` is removed from the merged manifest. ML Kit's
`vision-internal-vkp` and Google's `datatransport` usage-logging library add
it; releases up to and including 0.2.1 shipped with it, so ML Kit could send
its usage logs to Google. From 0.3.0 it cannot. `ManifestContractTest` and the
CI step "Merged manifest promises" fail the build if it returns.
`ACCESS_NETWORK_STATE` (from WorkManager) stays; it reads connectivity and
cannot send anything.

Picked photos need no permission. The system photo picker grants read access
per photo and the app keeps the grant with `takePersistableUriPermission`.
Android keeps at most 512 such grants per app and drops the oldest.

## What is stored

Group names, descriptions, photo URIs, the schedule and the screen saver
choice live in the DataStore file `settings`. ML Kit labels per photo and
Gemini Nano yes/no verdicts per description live in `photo_index.db`. Both
sit in the app's private storage and go away with the app. No photo is
copied; the app reads from the URI each time it needs pixels.

## On-device models

ML Kit image labeling runs from a bundled model. Gemini Nano runs inside
Google AICore, a system component, through the ML Kit GenAI Prompt API: the
photo and a short prompt go to AICore on the phone and one word comes back.
AICore refuses some photos on policy grounds. The app treats a refusal as
"could not look", never as a match.

## Screen saver

The screen saver reads the same groups. It runs only after you choose Feels
Engine photos under Settings > Display > Screen saver.

## Local log

The app keeps a log of what it did, in its private storage on the phone:
when the wallpaper changed and why, how long the live wallpaper took to draw
after the screen was shown, and whether the screen saver played. It holds no
photos, file names or group names. It is capped at 512 KB, never sent
anywhere, and leaves the phone only when you tap Export log on Home and pick
where to save it. Uninstalling the app deletes it.

