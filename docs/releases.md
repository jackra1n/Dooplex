# Releases

Release by merging the release pull request; never tag or edit `version.txt` by hand.
[release-please](https://github.com/googleapis/release-please) keeps that pull request open on
`main`, collecting `feat:` and `fix:` commits into `CHANGELOG.md` and the next version. Merging it
tags the release, and `.github/workflows/release.yml` builds the APK and attaches it as
`dooplex-<tag>.apk`.

## Versions

- `version.txt` is the one source; `app/build.gradle.kts` reads it for `versionName` and turns
  `major.minor.patch` into `versionCode` as `major * 1000000 + minor * 1000 + patch`, so every
  release installs over the previous one.
- Before 1.0, a `feat:` bumps the minor version and a `fix:` the patch (`bump-minor-pre-major`).
- Config: `release-please-config.json` and `.release-please-manifest.json`.

## Signing

Release APKs are signed with the release key, development builds (`mise run build` / `upload`)
with this PC's Android debug key. Android only installs an update signed with the same key as the
installed app, so switching a box between the two needs an uninstall first.

- The key is `~/.android/dooplex-release.jks` (alias `dooplex`), its password is in
  `~/.android/dooplex-release.password`. **Back both up**: without them no release can update an
  existing install.
- The workflow reads them from the repository secrets `SIGNING_KEYSTORE` (the keystore as base64)
  and `SIGNING_PASSWORD`, and passes them to `mise run build` as `SIGNING_KEYSTORE` and
  `SIGNING_PASSWORD`.
- To sign locally with it:
  `SIGNING_KEYSTORE=~/.android/dooplex-release.jks SIGNING_PASSWORD="$(cat ~/.android/dooplex-release.password)" mise run build`.
- release-please opens pull requests with the workflow's token, which needs **Settings → Actions →
  General → Allow GitHub Actions to create and approve pull requests**.
