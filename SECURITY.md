# Security

Report vulnerabilities to me@trevon.dev. Do not open a public issue for a
vulnerability. You will get a reply within seven days.

## Scope

The app reads photos the user picked or, for described groups, the device's
image library. It writes the wallpaper and runs as a screen saver. It has no
network permission, no accounts and no server. Data it keeps:

- `settings` (DataStore): group names, descriptions, photo URIs, schedule.
- `photo_index.db` (SQLite): ML Kit labels and Gemini Nano verdicts per photo.
- `label_index.json.migrated`: the pre-0.2 index, kept once after import.

All of it lives in the app's private storage and goes away with the app.

## Out of scope

Behaviour of Google AICore, ML Kit and the system photo picker.
