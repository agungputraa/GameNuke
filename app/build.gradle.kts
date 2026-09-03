import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localReleaseProperties = Properties().also { props ->
    val source = rootProject.file("release.properties")
    if (source.isFile) source.inputStream().use { stream -> props.load(stream) }
}
val releaseStoreFile = providers.gradleProperty("NUKE_RELEASE_STORE_FILE").orNull
    ?: localReleaseProperties.getProperty("storeFile")?.let { rootProject.file(it).absolutePath }
val releaseStorePassword = providers.gradleProperty("NUKE_RELEASE_STORE_PASSWORD").orNull
    ?: localReleaseProperties.getProperty("storePassword")
val releaseKeyAlias = providers.gradleProperty("NUKE_RELEASE_KEY_ALIAS").orNull
    ?: localReleaseProperties.getProperty("keyAlias")
val releaseKeyPassword = providers.gradleProperty("NUKE_RELEASE_KEY_PASSWORD").orNull
    ?: localReleaseProperties.getProperty("keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.neon.gametweak"

    compileSdk = 36

    defaultConfig {
        applicationId = "com.neon.gametweak"
        minSdk = 30
        targetSdk = 36
        versionCode = 15
        versionName = "2.2.0-prem"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
//        ndk {
//            abiFilters.add("arm64-v8a")
//            abiFilters.add("armeabi-v7a")
//        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile?.let { file(it) }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // Production must never use the Android debug key.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            buildConfigField("String", "VUNGLE_APP_ID",          "\"6a8dd7d372786c9ba4de1adc\"")
            buildConfigField("String", "VUNGLE_BANNER_ID",       "\"BANNER-6621365\"")
            buildConfigField("String", "VUNGLE_INTERSTITIAL_ID", "\"INTERSTITIAL-4603533\"")
            buildConfigField("String", "VUNGLE_APP_OPEN_ID",     "\"APPOPEN-3877865\"")
            buildConfigField("String", "VUNGLE_REWARD_ID",       "\"REWARD-1196275\"")
            buildConfigField("boolean", "USE_TEST_ADS", "false")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true

            // Debug build uses real placement IDs too; Vungle serves test ads automatically
            // in debug/test-device environments when USE_TEST_ADS = true.
            buildConfigField("String", "VUNGLE_APP_ID",          "\"6a8dd7d372786c9ba4de1adc\"")
            buildConfigField("String", "VUNGLE_BANNER_ID",       "\"BANNER-6621365\"")
            buildConfigField("String", "VUNGLE_INTERSTITIAL_ID", "\"INTERSTITIAL-4603533\"")
            buildConfigField("String", "VUNGLE_APP_OPEN_ID",     "\"APPOPEN-3877865\"")
            buildConfigField("String", "VUNGLE_REWARD_ID",       "\"REWARD-1196275\"")
            buildConfigField("boolean", "USE_TEST_ADS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    lint {
        disable += setOf(
            "InvalidFragmentVersionForActivityResult",
            "MissingTranslation",
            "ExtraTranslation",
        )
        abortOnError = true
        checkReleaseBuilds = true
    }

    androidResources {
        noCompress += "mp4"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // ── Liftoff Monetize (Vungle) SDK ─────────────────────────────────────────
    implementation("com.vungle:vungle-ads:7.7.7")

    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // libadb pairing requires TLS 1.3 exporter support. Bundle Conscrypt instead of
    // reflecting into OEM/system Conscrypt, whose API surface differs between ROMs.
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    // ── Shizuku API (Privileged shell via Shizuku server) ──────────────────
    // The binder persists across WiFi-off, app restart, and clear-recents.
    // v13.1.5 is the latest stable release on Maven Central.
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // ── iAdb API (Lightweight Shizuku fork — Android 11+ only) ────────────
    // AARs are downloaded from https://github.com/FileContainer/iAdb-api/tree/main/release
    // and stored locally in app/libs/ (not available on Maven Central).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:review:2.0.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val copyReleaseAab by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/bundle/release"))
    include("*.aab")
    into(rootProject.layout.projectDirectory.dir("release-aab"))
}

val copyReleaseApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    into(rootProject.layout.projectDirectory.dir("release-apk"))
    rename { "GameNuke-Premium-v2.2.0.apk" }
}

tasks.configureEach {
    when (name) {
        "bundleRelease" -> finalizedBy(copyReleaseAab)
        "assembleRelease" -> finalizedBy("bundleRelease", copyReleaseApk)
    }
}

tasks.register("productionAab") {
    dependsOn("bundleRelease")
}

tasks.register("aabRelease") {
    dependsOn("bundleRelease")
}
