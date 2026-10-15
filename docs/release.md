# Release

Releases are cut by release-please from conventional commits on `main`.

| Commit prefix | Effect while the version is 0.x |
|---|---|
| `fix:` | patch bump, listed under Fixes |
| `feat:` | minor bump, listed under Features |
| `perf:` | patch bump, listed under Performance |
| `docs:` `test:` `chore:` `ci:` | no release, not listed |
| `feat!:` or a `BREAKING CHANGE:` footer | minor bump before 1.0 |

`.github/workflows/release-please.yml` keeps one release PR open that bumps
`.release-please-manifest.json`, the `appVersion` line in
`app/build.gradle.kts` and `CHANGELOG.md`. Merging that PR tags `vX.Y.Z`,
creates the GitHub Release, and calls `.github/workflows/release.yml` with the
tag. That workflow runs the tests, builds `assembleRelease` and
`bundleRelease` signed with the upload key from the repository secrets, and
attaches `feels-engine-vX.Y.Z.apk` (sideload), `feels-engine-vX.Y.Z.aab` (the
file Play Console takes) and their SHA-256. `ci.yml` runs tests, lint and a
debug build on every push and pull request.

Before merging a release PR:

1. `scripts/smoke-matrix.sh fe_api28 fe_api30 fe_api33 fe_api34` passes
   every step (docs/build.md, "Version matrix").
2. The release PR's CI and CodeQL are green.
3. On the Fold and the Pixel: install the build, fold and unfold, sleep and
   wake, and check "This week" on Home shows no blank screens and no
   interrupted fades.

A hand-pushed tag still builds and attaches the same files:

```bash
git tag v0.3.0 && git push origin v0.3.0
```

The repository setting "Allow GitHub Actions to create and approve pull
requests" must stay on, or release-please cannot open its PR.

A release PR opened by the github-actions bot waits for "Approve and run"
before CI runs on it, because the repository requires approval for
first-time contributors and the bot counts as one. To skip that, add a
fine-grained personal token with Contents and Pull requests write access on
this repository as the secret `RELEASE_PLEASE_TOKEN`; the workflow uses it
when present.

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

The upload key is the owner's shared Play upload key (also used by the
owner's other apps), copied to `docs/local/upload-keystore.jks` with
`docs/local/signing.properties` pointing at it. Both are ignored by git.
Certificate SHA-256 `5D:0D:19:85:5A:D6:06:34:...:FF:11:BF:64`, alias `upload`.

Releases up to and including v0.2.1 on GitHub were signed with an earlier
Feels Engine key (`E0:DB:94:0A:67:CA:...`, kept as
`docs/local/release-feels-engine-2026-10-14.jks`). Android treats the two as
different signers: a phone with a v0.2.1 GitHub APK must uninstall it before
installing v0.2.2 or later. A shared-key build of 0.2.1 for Play is in
`docs/local/store/bundles/`.

Load the secrets from the local files (GNU base64 on this machine):

```bash
base64 -w0 < docs/local/upload-keystore.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD   # then KEY_ALIAS, KEY_PASSWORD
```

Back the keystore up somewhere that is not this machine. Losing it means a
Play upload key reset and, for sideloaded installs, a different app.

When the Play listing goes live, enroll in Play App Signing and use this
keystore as the upload key, so sideloaded and Play installs stay the same app.

## Versioning

`appVersion` in `app/build.gradle.kts` is the version; release-please rewrites
it. `versionCode` is derived from it as major*10000 + minor*100 + patch, so
0.2.2 is 202 and 1.0.0 is 10000. Do not edit either by hand.
