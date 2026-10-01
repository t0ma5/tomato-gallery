### Reporting

Open an issue on this repository: https://github.com/t0ma5/tomato-gallery/issues

Include the app version (Settings → About), Android version, and steps to reproduce. Do not attach photos that contain private information.

### Contributing as a developer

1. Fork [t0ma5/tomato-gallery](https://github.com/t0ma5/tomato-gallery) and open a pull request against `master`.
2. Keep changes focused. Commons (`Simple-Commons`) is patched only through `scripts/patch_commons.py`.
3. Match existing Kotlin style. Do not mass-reformat unrelated files.
4. User-visible features need README, CHANGELOG, fastlane changelog, and the GitHub Release body in `.github/workflows/build.yml`.
5. The launcher name stays `Gallery`. Build the FOSS flavor (`assembleFossRelease` for signed APKs). Release APKs are named `Tomato-Gallery_<version>-FOSS-arm64-v8a.apk`, `...-armeabi-v7a.apk`, `...-x86_64.apk`, and `...-FOSS-universal.apk`.

### Translations

Edit `app/src/main/res/values-<locale>/strings.xml` and send a pull request. Keep `font_*` keys untranslated (`translatable="false"`).
