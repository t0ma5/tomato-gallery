---
name: simple-gallery-release
description: >-
  Simple Gallery local APK signing, README new-features list, and GitHub
  Release notes. Use when building the APK, tagging, writing changelog, or
  adding a user-facing feature.
---

# Simple Gallery release notes and signing

If build locally always use the same github key, always update the readme with new features and include them in the release info of github too.

## Local builds

- Sign with the GitHub keystore, never the Android debug key: `assembleFossRelease`.
- Release APKs land in `app/build/outputs/apk/foss/release/` as:
  `Tomato-Gallery_<version>-FOSS-arm64-v8a.apk`,
  `Tomato-Gallery_<version>-FOSS-armeabi-v7a.apk`,
  `Tomato-Gallery_<version>-FOSS-x86_64.apk`,
  `Tomato-Gallery_<version>-FOSS-universal.apk`.
- Key files (gitignored): `keystore.properties`, `app/keystore.jks`.
- Keep copies: `D:\WEBSITES\PUTTY\simple-gallery-release.jks`, `.properties`, `.jks.b64`.
- GitHub secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` (`gallery`).
- JDK 25 + `GRADLE_USER_HOME=D:\software\gradle-home`. Do not download JDK 17.

## README and GitHub Release

When you add a user-visible feature:

1. Add a bullet under **New features in this fork** in `README.md` (keep groups short: Edit, Formats, Organize, Private, No nags).
2. Add the same item to the `body:` of Publish GitHub Release in `.github/workflows/build.yml`.
3. Add it to `CHANGELOG.md` and `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

Every GitHub Release **must** include a user-facing change list in `body:`, in the same numbered style as 6.30. Do not ship a tag with only auto-generated git notes. Keep `generate_release_notes: true` if you want the commit log underneath, but the numbered list is required.

Preamble (signed FOSS APKs named `Tomato-Gallery_<version>-FOSS-<abi>.apk` for arm64-v8a, armeabi-v7a, x86_64, and universal; launcher name Gallery), then:

```
## New in <version>

1. **Short title** — One or two sentences of what changed and why it matters.
2. **Short title** — ...
```

Group related work under one number. Match the tone of the previous release: bold title, em dash, concrete behavior.

Launcher name is `Gallery` (`app_launcher_name`). Debug source used to override that to `Gallery_debug` — do not bring that back. The debug APK filename `gallery-*-foss-debug.apk` is not the launcher label. Release APKs are `Tomato-Gallery_<version>-FOSS-arm64-v8a.apk`, `...-armeabi-v7a.apk`, `...-x86_64.apk`, and `...-FOSS-universal.apk`.
