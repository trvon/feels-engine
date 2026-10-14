# Release

Releases are GitHub Releases with a signed APK attached, until the Play
listing is live. Pushing a tag does everything:

```bash
git tag v0.2.0
git push origin v0.2.0
```

`.github/workflows/release.yml` runs the tests, builds `assembleRelease` and
`bundleRelease` signed with the keystore from the repository secrets, and
attaches `feels-engine-v0.2.0.apk` (sideload), `feels-engine-v0.2.0.aab` (the
file Play Console takes) and their SHA-256 to the release. `ci.yml` runs
tests, lint and a debug build on every push and pull request.

## Signing

The release build reads its signing from environment variables, or from
`docs/local/signing.properties` on a developer machine (ignored by git):

| Variable | Property | Meaning |
|---|---|---|
| `FENGINE_KEYSTORE` | `storeFile` | path to the `.jks` |
| `FENGINE_KEYSTORE_PASSWORD` | `storePassword` | keystore password |
| `FENGINE_KEY_ALIAS` | `keyAlias` | key alias |
| `FENGINE_KEY_PASSWORD` | `keyPassword` | key password |

Without either, `assembleRelease` produces an unsigned APK and says so.

Create a keystore once and keep it out of the repo:

```bash
keytool -genkeypair -v -keystore docs/local/release.jks -alias feels-engine \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository secrets: `KEYSTORE_BASE64` (`base64 -i
docs/local/release.jks | pbcopy`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. Back the keystore up somewhere that is not this machine.
Losing it means every later release is a different app to Android.

When the Play listing goes live, enroll in Play App Signing and use this
keystore as the upload key, so sideloaded and Play installs stay the same app.

## Versioning

`versionCode` and `versionName` are set by hand in `app/build.gradle.kts`.
Bump both before tagging; `versionName` should equal the tag without the `v`.
