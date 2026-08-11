// Imported rather than written as `java.util.Properties`: inside a Kotlin build script
// `java` resolves to Gradle's own java extension, not to the package root, so the
// qualified name does not compile here.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * The Play upload identity, read from gitignored `local.properties` or from the
 * environment for CI. The keystore itself lives OUTSIDE this repository; only a
 * path to it is configured here, and no value is ever printed — a build log is the
 * wrong place for a signing password.
 *
 * Absent is the normal state of a clone and must stay buildable: `release` then
 * falls back to the debug keystore exactly as it always did. That fallback is a
 * local-testing identity so `installRelease` works on a developer machine; it is
 * not a distribution identity, and an artifact signed with it cannot be uploaded.
 *
 * Kept in step with the sender's copy by hand. The two apps are separate Play
 * listings but share one upload identity, so a divergence here would be a pair of
 * bundles Play attributes to two different developers.
 */
val uploadSigning: Map<String, String>? = run {
    val local = rootProject.file("local.properties")
    val properties = if (local.isFile) {
        Properties().apply { local.inputStream().use { load(it) } }
    } else {
        null
    }
    fun field(propertyName: String, environmentName: String): String =
        (properties?.getProperty(propertyName) ?: System.getenv(environmentName)).orEmpty().trim()

    val fields = mapOf(
        "storeFile" to field("flick.upload.storeFile", "FLICK_UPLOAD_STORE_FILE"),
        "storePassword" to field("flick.upload.storePassword", "FLICK_UPLOAD_STORE_PASSWORD"),
        "keyAlias" to field("flick.upload.keyAlias", "FLICK_UPLOAD_KEY_ALIAS"),
        "keyPassword" to field("flick.upload.keyPassword", "FLICK_UPLOAD_KEY_PASSWORD"),
    )
    val missing = fields.filterValues { it.isEmpty() }.keys
    when {
        missing.size == fields.size -> null
        missing.isNotEmpty() -> throw GradleException(
            "Upload signing is all-or-nothing: flick.upload.${missing.joinToString()} " +
                "${if (missing.size == 1) "is" else "are"} unset while the others are set.",
        )
        !File(fields.getValue("storeFile")).isFile -> throw GradleException(
            "flick.upload.storeFile does not point at a file. The upload keystore is " +
                "deliberately kept outside this repository; restore it from your backup.",
        )
        else -> fields
    }
}

android {
    namespace = "com.flick.receiver"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.flick.receiver"
        minSdk = 26
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 5
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        uploadSigning?.let { identity ->
            create("upload") {
                storeFile = File(identity.getValue("storeFile"))
                storePassword = identity.getValue("storePassword")
                keyAlias = identity.getValue("keyAlias")
                keyPassword = identity.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The Play upload identity when one is configured, and otherwise the
            // debug keystore. That fallback is a local-testing identity — it is
            // what makes `installRelease` possible on a machine with no upload
            // key — and is NOT a distribution identity. No keystore or credential
            // is stored in this repository either way; see [uploadSigning].
            signingConfig = signingConfigs.findByName("upload")
                ?: signingConfigs.getByName("debug")
        }

        // Macrobenchmark measurement target: release-shaped and non-debuggable
        // (debuggable code is never AOT-compiled, which invalidates timings) but
        // unminified, so profiles and traces map to real symbol names. The
        // baseline-profile plugin treats any `benchmark*` / `nonMinified*` name
        // as one of its own, so this type is deliberately left out of profile
        // wiring — `benchmarkRelease` is the variant that carries a profile.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
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

baselineProfile {
    from(project(":baselineprofile:receiver"))
    // Generation needs a connected TV, so it must stay off the assemble path:
    // `assembleRelease` uses whatever profile is already checked in.
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // The plugin hides its own build types from the Studio variant picker by
    // default, which would also hide the hand-written `benchmark` type.
    hideSyntheticBuildTypesInAndroidStudio = false
    // Lays startup classes contiguously in the dex. The sibling
    // `baselineProfileRulesRewrite` flag is deliberately left unset: it writes
    // the `android.experimental.art-profile-r8-rewriting` module property, which
    // AGP 9.3.0 no longer defines.
    dexLayoutOptimization = true
}

dependencies {
    // --- Baseline (from Foundation's version catalog; not module-owned) ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Installs the packaged baseline profile into ART on first run.
    implementation(libs.androidx.profileinstaller)

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
    // (Compose-for-TV intentionally ships no text field) AND the Expressive
    // LoadingIndicator family, which does not exist in the BOM's 1.4.0. Pinned to
    // the same alpha the sender uses so one Compose runtime (1.12.0-beta01) is
    // resolved for both apps rather than two that only agree by accident.
    implementation(libs.androidx.material3.expressive)
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
    implementation("androidx.media3:media3-session:1.10.1")
    // media3-effect is deliberately absent. It was here for
    // ExoPlayer.setVideoEffects, which turned the picture through a GL pass and
    // presented no frames at all on the verified hardware; the turn is now the
    // video surface's own transform. Nothing reaches androidx.media3.effect
    // without it: MediaCodecVideoRenderer.onEnabled builds its
    // PlaybackVideoGraphWrapper only when videoEffects is non-null, and
    // media3-exoplayer declares no dependency on the module.

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

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
