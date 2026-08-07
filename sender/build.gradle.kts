// Imported rather than written as `java.util.Properties`: inside a Kotlin build script
// `java` resolves to Gradle's own java extension, not to the package root, so the
// qualified name does not compile here.
import java.net.URI
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

/**
 * The same shape `SupportCatalog.validatedCheckoutUrl` demands at runtime. Kept in step with
 * it by hand because a build script cannot see the app's own classes, and deliberately no
 * looser: a link this accepts and that one rejects would be a build that passed and a tier
 * that vanished.
 *
 * [URI] is imported rather than written qualified for the reason the file header gives about
 * `Properties`: `java` names Gradle's own extension inside a build script, so `java.net.URI`
 * does not resolve here.
 */
fun malformedCheckoutUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return true
    val path = uri.rawPath.orEmpty()
    return !(
        uri.scheme == "https" && uri.host == "buy.stripe.com" && uri.rawUserInfo == null &&
            uri.port == -1 && path.startsWith('/') && path.drop(1).isNotBlank() &&
            uri.rawQuery == null && uri.rawFragment == null
        )
}

/**
 * Configured wrongly must fail the build; configured not at all must not.
 *
 * `SupportCatalog` is all-or-nothing on purpose — one bad tier would otherwise present a dead
 * checkout — but that atomicity is silent: a link with a tracking query on it, or two tiers
 * filled in and the third forgotten, compiles clean and ships an app whose support sheet
 * simply never opens. Nothing logs it, because nothing went wrong as far as the app is
 * concerned. This is the only place that difference is still cheap to notice.
 *
 * An unconfigured clone stays buildable, which the empty default exists for; every path in the
 * app already degrades honestly with no catalog. The failure text names the property and never
 * the value — a build log is the wrong place for deployment config, whether or not the value
 * happens to be public.
 */
run {
    val tiers = listOf(
        "support.stripe3Url" to supportStripe3Url,
        "support.stripe8Url" to supportStripe8Url,
        "support.stripe15Url" to supportStripe15Url,
    )
    val configured = tiers.filter { it.second.isNotEmpty() }
    if (configured.isNotEmpty()) {
        val missing = tiers.filter { it.second.isEmpty() }.map { it.first }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Support checkout is all-or-nothing: ${missing.joinToString()} " +
                    "${if (missing.size == 1) "is" else "are"} unset while the others are. " +
                    "Set all three or none.",
            )
        }
        val malformed = configured.filter { malformedCheckoutUrl(it.second) }.map { it.first }
        if (malformed.isNotEmpty()) {
            throw GradleException(
                "Support checkout link rejected: ${malformed.joinToString()}. " +
                    "Must be https://buy.stripe.com/<path> with no query, fragment, port or " +
                    "userinfo — a link copied with a tracking parameter fails here.",
            )
        }
    }
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
 * All-or-nothing for the same reason the support catalog is: three fields set and
 * the fourth forgotten would otherwise fall silently back to debug and produce an
 * `bundleRelease` that Play rejects only after the upload.
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
        versionCode = 4
        versionName = "1.0.0"

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

    // The phone's media notification. media3-session owns the platform MediaSession the
    // Android media controls and the media buttons talk to; media3-common supplies
    // SimpleBasePlayer, which exists for a controller whose playback is on another device.
    // No ExoPlayer: this app never decodes anything. Pinned to the same 1.10.1 the
    // receiver uses, so one Media3 line is validated for the pair.
    implementation("androidx.media3:media3-common:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")

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
    // The real org.json, ahead of the stub `android.jar` AGP appends to the unit-test
    // classpath. Without it every platform JSON call throws "Stub!", so a test of the bytes
    // this phone puts on the control wire could only restate `JSONObject`'s own type
    // dispatch instead of running it — and a change to a frame builder would keep passing
    // while the wire broke. Upstream of the platform's own copy, and unit-test scope only:
    // nothing here reaches an APK, where the platform class is the one that runs.
    testImplementation("org.json:json:20250107")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
