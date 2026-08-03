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
 * The OpenSubtitles "API Consumer" key, which identifies the APP and not the user:
 * quota attaches to whichever account is signed in, so this value is an app
 * identifier rather than a credential of anyone's. It ships inside the APK and is
 * extractable from it, exactly as it is in every other OpenSubtitles client — the
 * reason it must not be committed is that THIS REPOSITORY IS PUBLIC, not that
 * packaging could hide it.
 *
 * Put it in `local.properties` (already gitignored) as `opensubtitles.apiKey=...`,
 * or in the `OPENSUBTITLES_API_KEY` environment variable for CI. The default is
 * empty and that is the normal state of a clone: every path in the app has to
 * compile, run and degrade honestly with no key at all. Nothing here prints the
 * value — a key echoed into build output is a key published to CI logs.
 */
val openSubtitlesApiKey: String = run {
    val local = rootProject.file("local.properties")
    val fromProperties = if (local.isFile) {
        Properties()
            .apply { local.inputStream().use { load(it) } }
            .getProperty("opensubtitles.apiKey")
    } else {
        null
    }
    (fromProperties ?: System.getenv("OPENSUBTITLES_API_KEY")).orEmpty().trim()
}

/**
 * Public Stripe-hosted checkout URLs. A clone deliberately has no fallback links:
 * support is offered only when every tier is configured and validated at runtime.
 * Values are never printed because build logs are a poor place for deployment config.
 */
fun supportCheckoutUrl(propertyName: String, environmentName: String): String {
    val local = rootProject.file("local.properties")
    val fromProperties = if (local.isFile) {
        Properties()
            .apply { local.inputStream().use { load(it) } }
            .getProperty(propertyName)
    } else {
        null
    }
    return (fromProperties ?: System.getenv(environmentName)).orEmpty().trim()
}

val supportStripe3Url = supportCheckoutUrl("support.stripe3Url", "FLICK_SUPPORT_STRIPE_3_URL")
val supportStripe8Url = supportCheckoutUrl("support.stripe8Url", "FLICK_SUPPORT_STRIPE_8_URL")
val supportStripe15Url = supportCheckoutUrl("support.stripe15Url", "FLICK_SUPPORT_STRIPE_15_URL")

/** Java string-literal escaping: BuildConfig is generated source, not a resource. */
fun javaStringLiteral(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "")
    .replace("\r", "")

android {
    namespace = "com.flick.sender"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.flick.sender"
        minSdk = 26
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "OPENSUBTITLES_API_KEY",
            "\"${javaStringLiteral(openSubtitlesApiKey)}\"",
        )
        buildConfigField(
            "String",
            "SUPPORT_STRIPE_3_URL",
            "\"${javaStringLiteral(supportStripe3Url)}\"",
        )
        buildConfigField(
            "String",
            "SUPPORT_STRIPE_8_URL",
            "\"${javaStringLiteral(supportStripe8Url)}\"",
        )
        buildConfigField(
            "String",
            "SUPPORT_STRIPE_15_URL",
            "\"${javaStringLiteral(supportStripe15Url)}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Local-testing signing identity only: reusing the debug keystore is
            // what makes `installRelease` possible on a developer machine. It is
            // NOT a distribution identity and no keystore/credential is stored
            // in this repository.
            signingConfig = signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        // AGP does not generate BuildConfig unless this is opted in; FlickLog
        // gates its verbose/debug diagnostics on BuildConfig.DEBUG.
        buildConfig = true
    }

    packaging {
        resources {
            // Ktor + kotlinx-io + SLF4J each ship license/notice metadata under
            // META-INF; drop the duplicates so packaging does not fail on merge.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.kotlin_module",
            )
        }
    }
}

baselineProfile {
    from(project(":baselineprofile:sender"))
    // Generation needs a connected phone, so it must stay off the assemble path:
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
    // Baseline (from the shared version catalog).
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    // Installs the packaged baseline profile into ART on first run.
    implementation(libs.androidx.profileinstaller)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Common Compose artifacts use the BOM; Expressive Material3 is pinned below
    // because its alpha requires the newer Compose 1.12 family.
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation(libs.androidx.material3.expressive)
    implementation(libs.androidx.activity.compose)
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    implementation("androidx.browser:browser:1.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Embedded LAN HTTP media server (Ktor 3.x, CIO engine — no Netty).
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-cio:3.1.3")
    // Control channel: outbound WebSocket client to the paired TV (Ktor CIO).
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.ktor:ktor-client-websockets:3.1.3")
    // In-app pairing scanner: CameraX preview + frame analysis, decoded by ML Kit. The
    // bundled artifact — not play-services-mlkit-barcode-scanning — because the model
    // then ships in the APK and the first scan works offline, with no module download.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Filmic video-frame stills for the gallery + the scrub preview loader.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    // Lightweight SLF4J binding so Ktor's logging initialises cleanly on device.
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
