plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.flick.receiver"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.flick.receiver"
        minSdk = 26
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Media3's @UnstableApi player-internals (LoadControl, DataSource,
    // AnalyticsListener, PlayerView, ...) are used throughout this spike.
    // Disable the lint opt-in gate module-wide so `./gradlew build` (lint with
    // abortOnError=true) does not fail with UnsafeOptInUsageError.
    lint {
        disable += "UnsafeOptInUsageError"
    }

    buildFeatures {
        compose = true
        // AGP defaults this to false, so no BuildConfig class is generated at
        // all. FlickLog gates its verbose/debug logcat output on
        // BuildConfig.DEBUG, so the class must exist for the module to compile.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        // Media3's @UnstableApi is an Android Lint marker handled below, while
        // these Compose annotations are genuine Kotlin opt-ins.
        freeCompilerArgs.addAll(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    // --- Baseline (from Foundation's version catalog; not module-owned) ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // --- Compose (BOM-aligned; explicit coordinates owned by this module) ---
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    // material3 supplies the single OutlinedTextField used for URL entry
    // (Compose-for-TV intentionally ships no text field).
    implementation("androidx.compose.material3:material3")
    // Lifecycle-aware Compose helpers (LocalLifecycleOwner, etc.).
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Compose for TV (leanback-focused components) ---
    implementation(libs.androidx.tv.material)

    // --- Media3 / ExoPlayer, pinned by contract to 1.10.1 ---
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")
    implementation("androidx.media3:media3-datasource:1.10.1")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // --- Downloadable Google Fonts (Space Grotesk / Roboto Mono), BOM-aligned.
    //     Loading is best-effort: the families fall back to the platform default
    //     if the provider is unavailable, so nothing hard-fails on a device
    //     without Google Play Services. ---
    implementation("androidx.compose.ui:ui-text-google-fonts")

    // --- TV control server (control-channel.md): Ktor CIO + WebSockets. This is
    //     the SECOND server on the TV — control-only, pairing-gated, LAN-bound;
    //     it carries no media and no file access. The media path stays direct-play
    //     on the phone. slf4j-simple is Ktor's logging backend. ---
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-cio:3.1.3")
    implementation("io.ktor:ktor-server-websockets:3.1.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // --- QR bitmap generation for first-run pairing (rendered to a Compose
    //     Canvas; no camera needed to display). ---
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
