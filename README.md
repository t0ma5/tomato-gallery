# Tomato Gallery

<img alt="Logo" src="graphics/icon.png" width="120" />

Fully open source gallery with no ads, tracking or unnecessary permissions. Tons of cool features and customizable colors, no internet access required and with privacy and simplicity in mind.

[Simple-Gallery](https://github.com/SimpleMobileTools/Simple-Gallery) (GPL-3.0) was my favorite FOSS gallery app until the project was sold to a shady company named ZipoApps in 2023. I forked it to keep it alive, FOSS and updated. This fork is not affiliated with Simple Mobile Tools, Fossify, or ZipoApps. Code contributions are very welcome :)

APKs are published on [GitHub Releases](https://github.com/t0ma5/tomato-gallery/releases)
- `Tomato-Gallery_<version>-FOSS-arm64-v8a.apk` (most phones)
- `Tomato-Gallery_<version>-FOSS-armeabi-v7a.apk`
- `Tomato-Gallery_<version>-FOSS-x86_64.apk` (emulators)
- `Tomato-Gallery_<version>-FOSS-universal.apk` (all included)

## New features in this fork

The original Simple Gallery Pro workflow, plus a new completely FOSS editor, OCR, AVIF/JXL, motion photos, 360° panoramas, and much more. Still offline, still FOSS and GPL-3.0

**Edit**
- **Copy text (OCR)** — Viewer overflow reads printed text and numbers on a photo and copies them. On-device PaddleOCR (PP-OCRv5) engine and model files. Nothing is uploaded. This adds ~20MB extra to the apk file but it's a handy feature I wanted to have.
- **FOSS photo editor** — Crop, rotate, resize, draw, filters, tone sliders, and text/emoji stickers. Tools stack in one session. Undo last tool (up to 3). No proprietary SDK.
- **Editor stickers** — 6-column emoji grid (arrows, markers, faces) plus More for the system emoji picker.
- **Lossless JPEG rotate** — Viewer rotate/save and editor rotate-only keep original quality and size. Save as no longer crashes on EXIF write.
- **JPEG optimize** — Native jpegoptim: lossless Huffman or lossy (default 85). EXIF kept; file replaced only if smaller. Batch from the grid.
- **Video trim** — Cut a clip to a start/end range and save it next to the original. Toolbar sits below the status bar.
- **Save frame** — Grab the current video frame as a JPEG from the viewer or in-app player.
- **Collages** — CAB on 2–4 photos: side by side, stacked, or 2×2.

**Formats**
- **AVIF, JPEG XL, Ultra HDR** — Grid, viewer, and “open with”. Animated JXL plays in the viewer. Wide-color display with a settings toggle.
- **Motion photos** — Play Google Motion Photos, Samsung Live Photos, and Micro Videos; save the clip as an MP4.
- **360° and video** — OpenGL photosphere viewer (touch, pinch, gyroscope). Toolbar sits below the status bar. In-app player with speed, mute, long-press 2×, and exact seek.
- **EXIF orientation** — Viewer and region zoom honour the embedded orientation tag.
- **WebP pinch-zoom** — Animated and still WebP zoom in the viewer.

**Organize**
- **Thumbnail stays with its file** — A recycled grid cell no longer shows another file's thumbnail while the real one is still loading.
- **Stacks** — Optional RAW+JPEG, bursts, and edited copies in the grid, with a strip in the viewer.
- **Folder tree** — Grid, List, or Tree. Show hidden and Show excluded stay on until you turn them off.
- **Scroll-back** — Closing the viewer returns the grid to the photo you were on.
- **Instant favorite star** — Tap the star on a thumbnail or in the viewer; the overlay updates without a reload.
- **Show filenames** — Off by default. Settings → Thumbnails, or the label icon in a folder.
- **Clean thumbnails** — The favorite star overlay is off by default. Turn it back on in Settings → Thumbnails → File thumbnail style → Mark favorite items.
- **Darker dark theme** — Dark and Dark red backgrounds are `#111111` instead of `#2D2D2D`. Existing installs are moved over automatically.
- **Favorites only** — Overflow toggle to hide everything that is not a favorite. It is session-only, so other folders keep their photos.
- **Properties** — Works on the Favorites and Recycle Bin folders.
- **Tags** — Add or remove tags from the grid or viewer (tap × on a chip). Commas make separate tags. Search matches tag names.
- **Duplicate finder** — Scan a folder (or all) and delete extra copies. Overflow item sits above Settings.
- **Android Trash** — Optional MediaStore trash on Android 11+ instead of the app Recycle Bin. Favorites also write `IS_FAVORITE` when the system allows it.

**Private**
- **Locked folders** stay hidden in Favorites, Recycle Bin, and widgets until you unlock.
- **Strip metadata** — Remove GPS or all EXIF from photos, or when sharing.

**No nags**
- No donation prompts.

## Build

Release APKs come from `assembleFossRelease` (local and GitHub Actions). Names:

- `Tomato-Gallery_<version>-FOSS-arm64-v8a.apk`
- `Tomato-Gallery_<version>-FOSS-armeabi-v7a.apk`
- `Tomato-Gallery_<version>-FOSS-x86_64.apk`
- `Tomato-Gallery_<version>-FOSS-universal.apk`

| Item | Value |
| --- | --- |
| App version | 1.0 (versionCode 100) |
| minSdk | 26 |
| targetSdk / compileSdk | 36 (Android 16) |
| JVM bytecode | 17 |
| Gradle | 9.1.0 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / KSP | 2.2.10 |
| Room | 2.8.4 |
| Local JDK | 25 (do not point Gradle at JDK 17) |
| CI JDK | Temurin 17 |
| Commons | `SimpleMobileTools/Simple-Commons` @ `9e60e2479`, patched by `python scripts/patch_commons.py` |

Checkout Simple-Commons next to the app (gitignored `Simple-Commons/`), run `python scripts/patch_commons.py`, then `./gradlew assembleFossRelease`. When that directory exists, Gradle `includeBuild`s it instead of the JitPack AAR. Sign with gitignored `keystore.properties` and `app/keystore.jks` (GitHub secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

<div style="display:flex; gap:8px; flex-wrap:wrap;">
<img alt="Albums" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.jpeg" width="22%">
<img alt="Editor" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.jpeg" width="22%">
<img alt="Viewer" src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_en-US.jpeg" width="22%">
<img alt="Lock" src="fastlane/metadata/android/en-US/images/phoneScreenshots/5_en-US.jpeg" width="22%">
</div>
