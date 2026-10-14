# Privacy

The app has no network permission. Nothing it reads leaves the phone. This is
the developer's view; the policy users and the store see is `site/privacy.html`,
published at https://trvon.github.io/feels-engine/privacy.html.

## Permissions

| Permission | When | Why |
|---|---|---|
| `SET_WALLPAPER` | install | write the wallpaper |
| `RECEIVE_BOOT_COMPLETED` | install | re-arm time-of-day alarms after a reboot |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED` (Android 13+) | the first time a described group looks for photos | read the library so the description can be matched |
| `READ_EXTERNAL_STORAGE` (Android 9 to 12) | same | same |

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
