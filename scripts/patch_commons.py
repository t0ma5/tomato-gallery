#!/usr/bin/env python3
"""Apply fork-only patches to the CI checkout of Simple-Commons."""
from pathlib import Path
import re
import sys

ROOT = Path("Simple-Commons")
DOMAIN_RE = re.compile(
    r"(?:https?://(?:www\.)?simplemobiletools\.com[^\s\"'<>]*)"
    r"|(?:mailto:hello@simplemobiletools\.com)"
    r"|(?:hello@simplemobiletools\.com)"
    r"|(?:www\.simplemobiletools\.com)"
    r"|(?<![\w.])simplemobiletools\.com",
    re.IGNORECASE,
)


def patch(rel: str, replacements: list[tuple[str, str]]) -> None:
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    for old, new in replacements:
        if old not in text:
            raise SystemExit(f"patch failed in {rel}: {old[:90]!r} not found")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")
    print(f"patched {rel}")


def patch_gradle_for_gradle9() -> None:
    """Make the pinned Commons checkout configure under Gradle 9.1 / Kotlin 2.2."""
    compose_options = (
        "    composeOptions {\n"
        "        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()\n"
        "    }\n\n"
    )
    kotlin_options_compose = (
        "    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {\n"
        "        kotlinOptions.jvmTarget = project.libs.versions.app.build.kotlinJVMTarget.get()\n"
        "        kotlinOptions.freeCompilerArgs = listOf(\n"
        "            \"-opt-in=kotlin.RequiresOptIn\",\n"
        "            \"-opt-in=androidx.compose.material3.ExperimentalMaterial3Api\",\n"
        "            \"-opt-in=androidx.compose.material.ExperimentalMaterialApi\",\n"
        "            \"-opt-in=androidx.compose.foundation.ExperimentalFoundationApi\",\n"
        "            \"-Xcontext-receivers\"\n"
        "        )\n"
        "    }\n"
    )
    kotlin_options_samples_short = (
        "    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {\n"
        "        kotlinOptions.jvmTarget = project.libs.versions.app.build.kotlinJVMTarget.get()\n"
        "        kotlinOptions.freeCompilerArgs = listOf(\n"
        "            \"-opt-in=kotlin.RequiresOptIn\",\n"
        "            \"-Xcontext-receivers\"\n"
        "        )\n"
        "    }\n"
    )
    compiler_options_compose = (
        "    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {\n"
        "        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)\n"
        "        compilerOptions.freeCompilerArgs.addAll(\n"
        "            \"-opt-in=kotlin.RequiresOptIn\",\n"
        "            \"-opt-in=androidx.compose.material3.ExperimentalMaterial3Api\",\n"
        "            \"-opt-in=androidx.compose.material.ExperimentalMaterialApi\",\n"
        "            \"-opt-in=androidx.compose.foundation.ExperimentalFoundationApi\",\n"
        "            \"-Xcontext-receivers\"\n"
        "        )\n"
        "    }\n"
    )
    compiler_options_samples_short = (
        "    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {\n"
        "        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)\n"
        "        compilerOptions.freeCompilerArgs.addAll(\n"
        "            \"-opt-in=kotlin.RequiresOptIn\",\n"
        "            \"-Xcontext-receivers\"\n"
        "        )\n"
        "    }\n"
    )
    patch(
        "build.gradle.kts",
        [
            (
                "    alias(libs.plugins.kotlinAndroid).apply(false)\n",
                "    alias(libs.plugins.kotlinAndroid).apply(false)\n"
                "    alias(libs.plugins.kotlinCompose).apply(false)\n",
            ),
        ],
    )
    patch(
        "commons/build.gradle.kts",
        [
            (
                "    alias(libs.plugins.kotlinAndroid)\n",
                "    alias(libs.plugins.kotlinAndroid)\n"
                "    alias(libs.plugins.kotlinCompose)\n",
            ),
            (compose_options, ""),
            (kotlin_options_compose, compiler_options_compose),
        ],
    )
    patch(
        "samples/build.gradle.kts",
        [
            (
                "    alias(libs.plugins.kotlinAndroid)\n",
                "    alias(libs.plugins.kotlinAndroid)\n"
                "    alias(libs.plugins.kotlinCompose)\n",
            ),
            (compose_options, ""),
            (kotlin_options_samples_short, compiler_options_samples_short),
            (kotlin_options_compose, compiler_options_compose),
        ],
    )


def rewrite_deprecated_string_apis() -> None:
    """Kotlin 2.2 treats String.toLowerCase/toUpperCase as errors."""
    count = 0
    for path in ROOT.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        new = text.replace(".toLowerCase(", ".lowercase(").replace(
            ".toUpperCase(", ".uppercase("
        )
        if new == text:
            continue
        path.write_text(new, encoding="utf-8")
        count += 1
        print(f"rewrote string case APIs in {path.relative_to(ROOT)}")
    print(f"rewrote string case APIs in {count} files")


def scrub_simplemobiletools_com() -> None:
    """Strip leftover website/email mentions from Commons sources and strings."""
    count = 0
    for path in ROOT.rglob("*"):
        if path.suffix.lower() not in {".kt", ".xml", ".java"}:
            continue
        text = path.read_text(encoding="utf-8")
        # Package names like com.simplemobiletools.commons contain the
        # substring "simplemobiletools.com"; only rewrite actual website/email hits.
        if DOMAIN_RE.search(text) is None:
            continue
        new = DOMAIN_RE.sub("", text)
        if DOMAIN_RE.search(new):
            raise SystemExit(f"domain still present after scrub in {path}")
        path.write_text(new, encoding="utf-8")
        count += 1
        print(f"scrubbed simplemobiletools.com from {path.relative_to(ROOT)}")
    print(f"scrubbed {count} Commons files")


def main() -> None:
    if not ROOT.exists():
        raise SystemExit("Simple-Commons/ is missing")

    patch_gradle_for_gradle9()
    rewrite_deprecated_string_apis()

    fake = (
        "You are using a fake version of the app. For your own safety download the original "
        "one from www.simplemobiletools.com. Thanks"
    )

    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/activities/BaseSimpleActivity.kt",
        [
            (
                '        if (!packageName.startsWith("com.simplemobiletools.", true)) {\n'
                "            if ((0..50).random() == 10 || baseConfig.appRunCount % 100 == 0) {\n"
                f'                val label = "{fake}"\n'
                "                ConfirmationDialog(this, label, positive = R.string.ok, negative = 0) {\n"
                "                    launchViewIntent(DEVELOPER_PLAY_STORE_URL)\n"
                "                }\n"
                "            }\n"
                "        }\n",
                "",
            ),
            (
                '        if (!packageName.contains("slootelibomelpmis".reversed(), true)) {\n'
                "            if (baseConfig.appRunCount > 100) {\n"
                f'                val label = "{fake}"\n'
                "                ConfirmationDialog(this, label, positive = R.string.ok, negative = 0) {\n"
                "                    launchViewIntent(DEVELOPER_PLAY_STORE_URL)\n"
                "                }\n"
                "                return\n"
                "            }\n"
                "        }\n\n",
                "",
            ),
        ],
    )

    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/extensions/Activity.kt",
        [
            (
                "fun Activity.checkAppSideloading(): Boolean {\n"
                "    val isSideloaded = when (baseConfig.appSideloadingStatus) {\n"
                "        SIDELOADING_TRUE -> true\n"
                "        SIDELOADING_FALSE -> false\n"
                "        else -> isAppSideloaded()\n"
                "    }\n"
                "\n"
                "    baseConfig.appSideloadingStatus = if (isSideloaded) SIDELOADING_TRUE else SIDELOADING_FALSE\n"
                "    if (isSideloaded) {\n"
                "        showSideloadingDialog()\n"
                "    }\n"
                "\n"
                "    return isSideloaded\n"
                "}\n",
                "fun Activity.checkAppSideloading(): Boolean {\n"
                "    return false\n"
                "}\n",
            ),
        ],
    )

    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/activities/AboutActivity.kt",
        [
            (
                'launchViewIntent("https://github.com/SimpleMobileTools")',
                'launchViewIntent("https://github.com/t0ma5/tomato-gallery")',
            ),
            (
                'launchViewIntent("https://simplemobiletools.com/")',
                'launchViewIntent("https://github.com/t0ma5/tomato-gallery")',
            ),
            (
                """    private fun onVersionClick() {
        if (firstVersionClickTS == 0L) {
            firstVersionClickTS = System.currentTimeMillis()
            Handler(Looper.getMainLooper()).postDelayed({
                firstVersionClickTS = 0L
                clicksSinceFirstClick = 0
            }, EASTER_EGG_TIME_LIMIT)
        }

        clicksSinceFirstClick++
        if (clicksSinceFirstClick >= EASTER_EGG_REQUIRED_CLICKS) {
            toast(R.string.hello)
            firstVersionClickTS = 0L
            clicksSinceFirstClick = 0
        }
    }
""",
                """    private fun onVersionClick() {
        launchViewIntent("https://github.com/t0ma5/tomato-gallery/releases")
    }
""",
            ),
            (
                "showPrivacyPolicy = showExternalLinks,",
                "showPrivacyPolicy = false,",
            ),
            (
                "                    helpUsSection = {\n"
                "                        val showHelpUsSection =\n"
                "                            remember { showGoogleRelations || !showExternalLinks }\n"
                "                        HelpUsSection(\n"
                "                            onRateUsClick = {\n"
                "                                onRateUsClick(\n"
                "                                    showConfirmationAdvancedDialog = onRateUsClickAlertDialogState::show,\n"
                "                                    showRateStarsDialog = rateStarsAlertDialogState::show\n"
                "                                )\n"
                "                            },\n"
                "                            onInviteClick = ::onInviteClick,\n"
                "                            onContributorsClick = ::onContributorsClick,\n"
                "                            showDonate = resources.getBoolean(R.bool.show_donate_in_about) && showExternalLinks,\n"
                "                            onDonateClick = ::onDonateClick,\n"
                "                            showInvite = showHelpUsSection,\n"
                "                            showRateUs = showHelpUsSection\n"
                "                        )\n"
                "                    },\n",
                "                    helpUsSection = {},\n",
            ),
            (
                "                    aboutSection = {\n"
                "                        val setupFAQ = rememberFAQ()\n"
                "                        if (!showExternalLinks || setupFAQ) {\n"
                "                            AboutSection(setupFAQ = setupFAQ, onFAQClick = ::launchFAQActivity, onEmailClick = {\n"
                "                                onEmailClick(onEmailClickAlertDialogState::show)\n"
                "                            })\n"
                "                        }\n"
                "                    },\n",
                "                    aboutSection = {},\n",
            ),
        ],
    )

    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/compose/screens/AboutScreen.kt",
        [
            (
                "import androidx.compose.ui.res.stringResource\n",
                "import androidx.compose.ui.res.colorResource\n"
                "import androidx.compose.ui.res.stringResource\n",
            ),
            (
                "    SimpleColumnScaffold(title = stringResource(id = R.string.about), goBack = goBack) {\n"
                "        aboutSection()\n"
                "        helpUsSection()\n"
                "        socialSection()\n"
                "        otherSection()\n"
                "        SettingsListItem(text = stringResource(id = R.string.about_footer))\n"
                "    }\n",
                "    SimpleColumnScaffold(title = stringResource(id = R.string.about), goBack = goBack) {\n"
                "        HistorySection()\n"
                "        aboutSection()\n"
                "        helpUsSection()\n"
                "        socialSection()\n"
                "        otherSection()\n"
                "    }\n",
            ),
            (
                "}\n\n@Composable\ninternal fun HelpUsSection(\n",
                "}\n\n@Composable\ninternal fun HistorySection() {\n"
                "    SettingsGroup(title = {\n"
                "        SettingsTitleTextComponent(\n"
                "            text = stringResource(id = R.string.history),\n"
                "            modifier = startingTitlePadding,\n"
                "            color = colorResource(id = R.color.color_primary)\n"
                "        )\n"
                "    }) {\n"
                "        SettingsListItem(\n"
                "            tint = SimpleTheme.colorScheme.onSurface,\n"
                "            text = stringResource(id = R.string.about_history_text),\n"
                "        )\n"
                "        SettingsHorizontalDivider()\n"
                "    }\n"
                "}\n\n@Composable\ninternal fun HelpUsSection(\n",
            ),
            (
                "        SettingsTitleTextComponent(text = stringResource(id = R.string.other), modifier = startingTitlePadding)",
                "        SettingsTitleTextComponent(\n"
                "            text = stringResource(id = R.string.other),\n"
                "            modifier = startingTitlePadding,\n"
                "            color = colorResource(id = R.color.color_primary)\n"
                "        )",
            ),
            (
                "        SettingsTitleTextComponent(text = stringResource(id = R.string.social), modifier = startingTitlePadding)",
                "        SettingsTitleTextComponent(\n"
                "            text = stringResource(id = R.string.website),\n"
                "            modifier = startingTitlePadding,\n"
                "            color = colorResource(id = R.color.color_primary)\n"
                "        )",
            ),
            (
                "        SocialText(\n"
                "            click = onFacebookClick,\n"
                "            text = stringResource(id = R.string.facebook),\n"
                "            icon = R.drawable.ic_facebook_vector,\n"
                "        )\n"
                "        SocialText(\n"
                "            click = onGithubClick,\n"
                "            text = stringResource(id = R.string.github),\n"
                "            icon = R.drawable.ic_github_vector,\n"
                "            tint = SimpleTheme.colorScheme.onSurface\n"
                "        )\n"
                "        SocialText(\n"
                "            click = onRedditClick,\n"
                "            text = stringResource(id = R.string.reddit),\n"
                "            icon = R.drawable.ic_reddit_vector,\n"
                "        )\n"
                "        SocialText(\n"
                "            click = onTelegramClick,\n"
                "            text = stringResource(id = R.string.telegram),\n"
                "            icon = R.drawable.ic_telegram_vector,\n"
                "        )\n",
                "        SocialText(\n"
                "            click = onGithubClick,\n"
                "            text = stringResource(id = R.string.github),\n"
                "            icon = R.drawable.ic_github_vector,\n"
                "            tint = SimpleTheme.colorScheme.onSurface\n"
                "        )\n",
            ),
        ],
    )

    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/extensions/Context-storage.kt",
        [
            (
                "val Context.recycleBinPath: String get() = filesDir.absolutePath\n",
                "val Context.recycleBinPath: String get() = java.io.File(filesDir, \"recycle_bin\").absolutePath\n",
            ),
            # Fossify Gallery #1038 / commons #339: never build content://media/.../0 uris.
            (
                "                val id = cursor.getLongValue(Images.Media._ID)\n"
                "                if (id != 0L) {\n"
                "                    val path = cursor.getStringValue(Images.Media.DATA)\n"
                "                    ids[path] = id\n"
                "                }\n",
                "                val id = cursor.getLongValue(Images.Media._ID)\n"
                "                val path = cursor.getStringValueOrNull(Images.Media.DATA)\n"
                "                if (id > 0L && path != null) {\n"
                "                    ids[path] = id\n"
                "                }\n",
            ),
            (
                "fun Context.getFileUrisFromFileDirItems(fileDirItems: List<FileDirItem>): List<Uri> {\n"
                "    val fileUris = getUrisPathsFromFileDirItems(fileDirItems).second\n"
                "    if (fileUris.isEmpty()) {\n"
                "        fileDirItems.map { fileDirItem ->\n"
                "            fileUris.add(fileDirItem.assembleContentUri())\n"
                "        }\n"
                "    }\n",
                "// Files that are not indexed by MediaStore yield no uri. Never fall back to id 0, since\n"
                "// MediaStore.createWriteRequest() rejects content://media/external/images/media/0.\n"
                "fun Context.getFileUrisFromFileDirItems(fileDirItems: List<FileDirItem>): List<Uri> {\n"
                "    val fileUris = getUrisPathsFromFileDirItems(fileDirItems).second\n"
                "    if (fileUris.isEmpty()) {\n"
                "        fileDirItems.forEach { fileDirItem ->\n"
                "            fileDirItem.assembleContentUri()?.let { fileUris.add(it) }\n"
                "        }\n"
                "    }\n",
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/models/FileDirItem.kt",
        [
            (
                "import android.content.Context\n",
                "import android.content.ContentUris\nimport android.content.Context\n",
            ),
            (
                "    fun assembleContentUri(): Uri {\n"
                "        val uri = when {\n",
                "    fun assembleContentUri(): Uri? {\n"
                "        if (mediaStoreId <= 0) {\n"
                "            return null\n"
                "        }\n\n"
                "        val uri = when {\n",
            ),
            (
                "        return Uri.withAppendedPath(uri, mediaStoreId.toString())\n",
                "        return ContentUris.withAppendedId(uri, mediaStoreId)\n",
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/activities/BaseSimpleActivity.kt",
        [
            (
                "    fun updateSDK30Uris(uris: List<Uri>, callback: (success: Boolean) -> Unit) {\n"
                "        hideKeyboard()\n"
                "        if (isRPlus()) {\n"
                "            funAfterUpdate30File = callback\n",
                "    fun updateSDK30Uris(uris: List<Uri>, callback: (success: Boolean) -> Unit) {\n"
                "        hideKeyboard()\n"
                "        if (isRPlus()) {\n"
                "            if (uris.isEmpty()) {\n"
                "                // nothing is indexed by MediaStore, so there is no grant to ask for; let the caller try the write\n"
                "                callback(true)\n"
                "                return\n"
                "            }\n\n"
                "            funAfterUpdate30File = callback\n",
            ),
        ],
    )

    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/helpers/Constants.kt",
        [
            (
                "const val LICENSE_ZIP4J = 8589934592L\n",
                "const val LICENSE_ZIP4J = 8589934592L\nconst val LICENSE_JPEGOPTIM = 17179869184L\nconst val LICENSE_PADDLEOCR = 34359738368L\n",
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/activities/LicenseActivity.kt",
        [
            (
                "        License(LICENSE_ZIP4J, R.string.zip4j_title, R.string.zip4j_text, R.string.zip4j_url)\n",
                "        License(LICENSE_ZIP4J, R.string.zip4j_title, R.string.zip4j_text, R.string.zip4j_url),\n"
                "        License(LICENSE_JPEGOPTIM, R.string.jpegoptim_title, R.string.jpegoptim_text, R.string.jpegoptim_url),\n"
                "        License(LICENSE_PADDLEOCR, R.string.paddleocr_title, R.string.paddleocr_text, R.string.paddleocr_url),\n",
            ),
        ],
    )
    patch(
        "commons/src/main/res/values/strings.xml",
        [
            (
                '    <string name="zip4j_title">Zip4j (ZIP compression and decompression)</string>\n',
                '    <string name="zip4j_title">Zip4j (ZIP compression and decompression)</string>\n'
                '    <string name="jpegoptim_title">jpegoptim (lossless JPEG optimization)</string>\n'
                '    <string name="paddleocr_title">PaddleOCR (on-device text recognition)</string>\n',
            ),
            (
                '    <string name="disclaimer">Disclaimer</string>\n',
                '    <string name="disclaimer">Disclaimer</string>\n'
                '    <string name="history">History</string>\n'
                '    <string name="about_history_text">Fully open source gallery with no ads, tracking or unnecessary permissions. Tons of cool features and customizable colors, no internet access required, made with privacy and simplicity in mind. Simple-Gallery (GPL-3.0) was my favorite FOSS gallery app until the project was sold to a shady company named ZipoApps in 2023. I forked it to keep it alive, FOSS and updated.</string>\n',
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/helpers/BaseConfig.kt",
        [
            (
                "        get() = prefs.getBoolean(IS_USING_SYSTEM_THEME, isSPlus())\n",
                "        get() = prefs.getBoolean(IS_USING_SYSTEM_THEME, false)\n",
            ),
            (
                "        get() = prefs.getBoolean(USE_24_HOUR_FORMAT, DateFormat.is24HourFormat(context))\n",
                "        get() = prefs.getBoolean(USE_24_HOUR_FORMAT, true)\n",
            ),
            (
                "        get() = prefs.getString(DATE_FORMAT, getDefaultDateFormat())!!\n",
                "        get() = prefs.getString(DATE_FORMAT, DATE_FORMAT_TWO)!!\n",
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/dialogs/LineColorPickerDialog.kt",
        [
            (
                "    private val DEFAULT_PRIMARY_COLOR_INDEX = 14\n",
                "    private val DEFAULT_PRIMARY_COLOR_INDEX = 0\n",
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/compose/theme/Colors.kt",
        [
            (
                "val color_primary = Color(0xFFF57C00)\n"
                "val color_primary_dark = Color(0xFFD76D00)\n",
                "val color_primary = Color(0xFFD3332F)\n"
                "val color_primary_dark = Color(0xFFB71C1C)\n",
            ),
            (
                "val md_grey_800_dark = Color(0xFF2D2D2D)\n",
                "val md_grey_800_dark = Color(0xFF2D2D2D)\n"
                "val md_grey_900_darker = Color(0xFF111111)\n",
            ),
            (
                "val theme_dark_background_color = md_grey_800_dark\n",
                "val theme_dark_background_color = md_grey_900_darker\n",
            ),
        ],
    )
    patch(
        "commons/src/main/res/values/colors.xml",
        [
            (
                '    <color name="color_primary">#FFF57C00</color>\n'
                '    <color name="color_primary_dark">#FFD76D00</color>\n',
                '    <color name="color_primary">#FFD3332F</color>\n'
                '    <color name="color_primary_dark">#FFB71C1C</color>\n',
            ),
            (
                '    <color name="md_grey_800_dark">#2D2D2D</color>\n',
                '    <color name="md_grey_800_dark">#2D2D2D</color>\n'
                '    <color name="md_grey_900_darker">#111111</color>\n',
            ),
            (
                '    <color name="theme_dark_background_color">@color/md_grey_800_dark</color>\n',
                '    <color name="theme_dark_background_color">@color/md_grey_900_darker</color>\n',
            ),
        ],
    )
    patch(
        "commons/src/main/res/values/styles.xml",
        [
            (
                '        <item name="colorAccent">@color/color_accent</item>\n',
                '        <item name="colorAccent">@color/color_accent</item>\n'
                '        <item name="colorControlActivated">@color/color_primary</item>\n'
                '        <item name="android:colorControlActivated">@color/color_primary</item>\n',
            ),
            (
                '    <style name="TopPopupMenu.Overflow.Light" parent="@style/Widget.MaterialComponents.PopupMenu.Overflow">\n'
                '        <item name="android:popupBackground">@drawable/top_popup_menu_bg_light</item>\n'
                '    </style>\n\n'
                '    <style name="TopPopupMenu.Overflow.Dark" parent="@style/Widget.MaterialComponents.PopupMenu.Overflow">\n'
                '        <item name="android:popupBackground">@drawable/top_popup_menu_bg_dark</item>\n'
                '    </style>\n',
                '    <style name="TopPopupMenu.Overflow.Light" parent="@style/Widget.MaterialComponents.PopupMenu.Overflow">\n'
                '        <item name="android:popupBackground">@drawable/top_popup_menu_bg_light</item>\n'
                '        <item name="colorControlActivated">@color/color_primary</item>\n'
                '        <item name="android:colorControlActivated">@color/color_primary</item>\n'
                '    </style>\n\n'
                '    <style name="TopPopupMenu.Overflow.Dark" parent="@style/Widget.MaterialComponents.PopupMenu.Overflow">\n'
                '        <item name="android:popupBackground">@drawable/top_popup_menu_bg_dark</item>\n'
                '        <item name="colorControlActivated">@color/color_primary</item>\n'
                '        <item name="android:colorControlActivated">@color/color_primary</item>\n'
                '    </style>\n',
            ),
            (
                '    <style name="TopPopupMenuYou" parent="@style/Widget.AppCompat.ActionButton.Overflow">\n'
                '        <item name="android:popupBackground">@drawable/dialog_you_background</item>\n'
                '        <item name="android:dropDownHorizontalOffset">-10dp</item>\n'
                '        <item name="android:popupElevation">@dimen/popup_menu_elevation</item>\n'
                '    </style>\n',
                '    <style name="TopPopupMenuYou" parent="@style/Widget.AppCompat.ActionButton.Overflow">\n'
                '        <item name="android:popupBackground">@drawable/dialog_you_background</item>\n'
                '        <item name="android:dropDownHorizontalOffset">-10dp</item>\n'
                '        <item name="android:popupElevation">@dimen/popup_menu_elevation</item>\n'
                '        <item name="colorControlActivated">@color/color_primary</item>\n'
                '        <item name="android:colorControlActivated">@color/color_primary</item>\n'
                '    </style>\n',
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/compose/theme/DynamicTheme.kt",
        [
            (
                "                else -> md_orange_700\n",
                "                else -> md_red_700\n",
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/extensions/Activity-themes.kt",
        [
            (
                "            else -> R.style.AppTheme_Orange_700_core\n",
                "            -2936017 -> R.style.AppTheme_Red_700_core\n"
                "            else -> R.style.AppTheme_Red_700_core\n",
            ),
            (
                "            else -> R.style.AppTheme_Orange_700\n",
                "            -2936017 -> R.style.AppTheme_Red_700\n"
                "            else -> R.style.AppTheme_Red_700\n",
            ),
        ],
    )
    patch(
        "commons/src/main/kotlin/com/simplemobiletools/commons/activities/CustomizationActivity.kt",
        [
            (
                '        if (!packageName.startsWith("com.simplemobiletools.", true) && baseConfig.appRunCount > 50) {\n'
                "            finish()\n"
                "            return\n"
                "        }\n\n"
                "        curPrimaryLineColorPicker = LineColorPickerDialog",
                "        curPrimaryLineColorPicker = LineColorPickerDialog",
            ),
        ],
    )
    patch(
        "commons/src/main/res/values/donottranslate.xml",
        [
            (
                '    <string name="zip4j_url">https://github.com/srikanth-lingala/zip4j</string>\n',
                '    <string name="zip4j_url">https://github.com/srikanth-lingala/zip4j</string>\n'
                '    <string name="jpegoptim_text">jpegoptim 1.5.6 by Timo Kokkonen, linked with MozJPEG.\\n\\nCopyright (C) 1996-2025 Timo Kokkonen\\n\\nThis is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.\\n\\nThis software is based in part on the work of the Independent JPEG Group and MozJPEG (libjpeg-turbo).</string>\n'
                '    <string name="jpegoptim_url">https://github.com/tjko/jpegoptim</string>\n'
                '    <string name="paddleocr_text">PP-OCRv5 mobile models for reading printed text and numbers on a photo. Inference runs on-device through the ncnn Android port. Nothing is uploaded.\\n\\nPaddleOCR is licensed under the Apache License, Version 2.0. ncnn is licensed under the BSD 3-Clause License.</string>\n'
                '    <string name="paddleocr_url">https://github.com/PaddlePaddle/PaddleOCR</string>\n',
            ),
        ],
    )

    scrub_simplemobiletools_com()
    print("commons fork patches applied")


if __name__ == "__main__":
    try:
        main()
    except SystemExit as e:
        print(e, file=sys.stderr)
        raise
