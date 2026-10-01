import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}
val localArm64 = (findProperty("localArm64") as? String).equals("true", ignoreCase = true)

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = libs.versions.app.version.appId.get()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.libs.versions.app.version.versionName.get()
        versionCode = project.libs.versions.app.version.versionCode.get().toInt()
        ndk {
            if (!localArm64) {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
        externalNativeBuild {
            cmake {
        arguments += listOf("-DANDROID_STL=none", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            //applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("licensing")
    productFlavors {
        register("foss")
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    splits {
        abi {
            isEnable = true
            reset()
            if (localArm64) {
                include("arm64-v8a")
                isUniversalApk = false
            } else {
                include("arm64-v8a", "armeabi-v7a", "x86_64")
                isUniversalApk = true
            }
        }
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get().toString())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    namespace = libs.versions.app.version.appId.get()

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "META-INF/library_release.kotlin_module"
        }
    }
}

base {
    archivesName.set("gallery-${libs.versions.app.version.versionCode.get()}")
}

tasks.configureEach {
    if (name != "assembleFossRelease") {
        return@configureEach
    }
    doLast {
        val version = libs.versions.app.version.versionName.get()
        val dir = layout.buildDirectory.dir("outputs/apk/foss/release").get().asFile
        val abis = if (localArm64) {
            listOf("arm64-v8a")
        } else {
            listOf("arm64-v8a", "armeabi-v7a", "x86_64", "universal")
        }
        abis.forEach { abi ->
            val dest = dir.resolve("Tomato-Gallery_${version}-FOSS-${abi}.apk")
            val src = dir.listFiles()
                ?.filter { it.isFile && it.extension == "apk" && it.name.contains(abi) }
                ?.minByOrNull { if (it.name == dest.name) 0 else 1 }
                ?: error("No $abi APK in $dir")
            if (src.canonicalFile != dest.canonicalFile) {
                src.copyTo(dest, overwrite = true)
                src.delete()
            }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(libs.simple.tools.commons)
    implementation(libs.android.image.cropper)
    implementation(libs.exif)
    implementation(libs.android.gif.drawable)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.print)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.sanselan)
    implementation(libs.imagefilters)
    implementation(libs.androidsvg.aar)
    implementation(libs.gestureviews)
    implementation(libs.subsamplingscaleimageview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.awebp)
    implementation(libs.apng)
    implementation(libs.avif)
    implementation(libs.avif.integration)
    implementation(libs.jxl.coder)
    implementation(libs.jxl.integration) {
        exclude(group = "com.github.bumptech.glide")
    }
    implementation(libs.okio)
    implementation(libs.ncnn.ppocr)
    implementation(libs.picasso) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    compileOnly(libs.okhttp)

    ksp(libs.glide.compiler)
    implementation(libs.zjupure.webpdecoder)

    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
    testImplementation("junit:junit:4.13.2")
}
