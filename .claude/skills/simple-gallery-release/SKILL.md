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
- Local builds are **arm64-v8a only**: one APK in `app/build/outputs/apk/foss/release/` named
  `Tomato-Gallery_<version>-FOSS-arm64-v8a.apk`. Do not assemble the other ABIs locally.
- GitHub CI builds all four: `...-arm64-v8a.apk`, `...-armeabi-v7a.apk`, `...-x86_64.apk`,
  and `...-FOSS-universal.apk`.
- Key files (gitignored): `keystore.properties`, `app/keystore.jks`.
- Keep copies: `D:\WEBSITES\PUTTY\simple-gallery-release.jks`, `.properties`, `.jks.b64`.
- GitHub secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` (`gallery`).
- JDK 25 + `GRADLE_USER_HOME=D:\software\gradle-home`. Do not download JDK 17.

## CI: one build per release, never two

`.github/workflows/build.yml` builds on master pushes and on `v*` tags. Keep it to one build per release:

- The release commit message starts with `Release ` (e.g. `Release 1.3 with ...`). The build job skips a master push whose head commit starts with `Release `; the `v*` tag push builds and publishes it.
- Push the release commit and its tag together: `git push origin master v<version>`. Never push the tag later as a separate step, and never push the release commit without its tag (then nothing builds).
- Docs-only pushes (`**.md`, `fastlane/**`, `.claude/**`, `graphics/**`) don't build (`paths-ignore`).
- Commits that only touch CI or other non-app files and must not build: add `[skip ci]` to the message.
- Version bumps: patch (`1.2.1`) for fixes only; minor (`1.3`) when users get anything new.
- No agent attribution in commits, tags or releases.

## README and GitHub Release

When you add a user-visible feature:

1. Add a bullet under **New features in this fork** in `README.md` (keep groups short: Edit, Formats, Organize, Private, No nags).
2. Add the same item to the `body:` of Publish GitHub Release in `.github/workflows/build.yml`.
3. Add it to `CHANGELOG.md` and `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

Every GitHub Release **must** include a user-facing change list in `body:`, in the same numbered style as 6.30. Do not ship a tag with only auto-generated git notes. Keep `generate_release_notes: true` if you want the commit log underneath, but the numbered list is required.

The `body:` starts directly with `## New in <version>`. No preamble above it: no APK/ABI list, no applicationId, no "Version x (versionCode y)" line, no tomato.simple.gallery uninstall warning, no "Notes below roll up" line. Add the new section on top of the older `## New in ...` sections:

```
## New in <version>

1. **Short title** — One or two sentences of what changed and why it matters.
2. **Short title** — ...
```

Group related work under one number. Match the tone of the previous release: bold title, em dash, concrete behavior.

Launcher name is `Gallery` (`app_launcher_name`). Debug source used to override that to `Gallery_debug` — do not bring that back. The debug APK filename `gallery-*-foss-debug.apk` is not the launcher label. GitHub release APKs are `Tomato-Gallery_<version>-FOSS-arm64-v8a.apk`, `...-armeabi-v7a.apk`, `...-x86_64.apk`, and `...-FOSS-universal.apk`.
