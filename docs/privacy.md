# Privacy

The app has no network permission. Nothing it reads leaves the phone.

## Permissions

| Permission | When | Why |
|---|---|---|
| `SET_WALLPAPER` | install | write the wallpaper |
| `RECEIVE_BOOT_COMPLETED` | install | re-arm time-of-day alarms after a reboot |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED` (Android 13+) | first time a described group looks for photos | read the library so the description can be matched |
| `READ_EXTERNAL_STORAGE` (Android 9 to 12) | same | same |

Picked photos need no permission: the system photo picker grants read access
per photo, and the app keeps that grant with `takePersistableUriPermission`.
Android keeps at most 512 such grants per app and drops the oldest.

## What is stored

- Group names, descriptions, photo URIs, schedule, screen saver choice: the
  DataStore file `settings`.
- ML Kit labels per photo and Gemini Nano yes/no verdicts per description:
  `photo_index.db`.

Both are in the app's private storage and removed with the app. No photo is
copied; the app reads from the URI each time it needs pixels.

## On-device models

ML Kit image labeling runs from a bundled model. Gemini Nano runs through the
ML Kit GenAI Prompt API in Google AICore, a system component; the photo and a
short prompt go to AICore on the phone and the answer is one word. AICore may
refuse some photos on policy grounds; the app treats a refusal as "could not
look" and never as a match.

## Screen saver

The screen saver reads the same groups. It runs only when the user has chosen
Feels Engine photos under Settings > Display > Screen saver.
