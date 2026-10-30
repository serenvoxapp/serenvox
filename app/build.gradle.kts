// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Real AdMob IDs live in git-ignored ads.properties; otherwise fall back to Google's test IDs.
val adsProps = Properties().apply {
    setProperty("admob.appId", "ca-app-pub-3940256099942544~3347511713")
    setProperty("admob.bannerId", "ca-app-pub-3940256099942544/9214589741")
    setProperty("admob.interstitialId", "ca-app-pub-3940256099942544/1033173712")
    val f = rootProject.file("ads.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.serenvox.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.serenvox.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.2"
        manifestPlaceholders["admobAppId"] = adsProps.getProperty("admob.appId")
        buildConfigField("String", "BANNER_ID", "\"${adsProps.getProperty("admob.bannerId")}\"")
        buildConfigField("String", "INTERSTITIAL_ID", "\"${adsProps.getProperty("admob.interstitialId")}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug { applicationIdSuffix = ".debug" }
    }

    // Per-ABI APKs for sideloading (the AAB is split by Play automatically).
    splits {
        abi {
            // Enable with -PsplitApks for per-ABI sideload APKs (cannot be combined with bundleRelease).
            isEnable = project.hasProperty("splitApks")
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests {
        isIncludeAndroidResources = true
        all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("screenshots.dir", rootProject.file("screenshots").absolutePath)
            it.maxHeapSize = "1536m"
            System.getenv("SERENVOX_NET_TESTS")?.let { v -> it.environment("SERENVOX_NET_TESTS", v) }
        }
    } }
    packaging { jniLibs { useLegacyPackaging = false } }
    lint { abortOnError = true; checkReleaseBuilds = true; disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "UseKtx") }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("com.google.mlkit:text-recognition:16.0.1") // bundled Latin OCR model, offline
    implementation("org.apache.commons:commons-compress:1.27.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.android.gms:play-services-ads:24.4.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
