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
// AdMob: debug builds always use Google's TEST IDs. Release builds use git-ignored ads.properties;
// without it they use test IDs for everything, so real and test IDs are never mixed.
object TestAds {
    const val APP = "ca-app-pub-3940256099942544~3347511713"
    const val BANNER = "ca-app-pub-3940256099942544/9214589741"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
}
val adsProps = Properties().apply {
    val f = rootProject.file("ads.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun adsProp(k: String) = adsProps.getProperty(k)?.trim().orEmpty()
val releaseAds: Triple<String, String, String> = run {
    val app = adsProp("admob.appId")
    if (app.isEmpty()) Triple(TestAds.APP, TestAds.BANNER, "")
    else {
        val banner = adsProp("admob.bannerId")
        val inter = adsProp("admob.interstitialId")
        val pub = app.substringBefore("~")
        require(!app.startsWith("ca-app-pub-3940256099942544")) { "ads.properties: admob.appId is Google's test ID" }
        require(banner.startsWith("$pub/")) { "ads.properties: admob.bannerId must be a real unit of $pub" }
        require(inter.isEmpty() || inter.startsWith("$pub/")) { "ads.properties: admob.interstitialId must be blank or a real unit of $pub" }
        Triple(app, banner, inter)
    }
}
fun str(v: String) = "\"" + v.replace("\"", "") + "\""

android {
    namespace = "com.serenvox.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.serenvox.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.3"
        buildConfigField("boolean", "TEST_BUILD", "false")
        buildConfigField("String", "ADS_TEST_DEVICE_IDS", str(adsProp("admob.testDeviceIds")))
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
            manifestPlaceholders["admobAppId"] = releaseAds.first
            buildConfigField("String", "BANNER_ID", str(releaseAds.second))
            buildConfigField("String", "INTERSTITIAL_ID", str(releaseAds.third))
        }
        // Sideload QA: release code/signing/applicationId (installs over store builds), Google TEST ads.
        create("qa") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            manifestPlaceholders["admobAppId"] = TestAds.APP
            buildConfigField("String", "BANNER_ID", str(TestAds.BANNER))
            buildConfigField("String", "INTERSTITIAL_ID", str(TestAds.INTERSTITIAL))
            buildConfigField("boolean", "TEST_BUILD", "true")
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["admobAppId"] = TestAds.APP
            buildConfigField("String", "BANNER_ID", str(TestAds.BANNER))
            buildConfigField("String", "INTERSTITIAL_ID", str(TestAds.INTERSTITIAL))
        }
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
    implementation("androidx.work:work-runtime-ktx:2.10.0")
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
