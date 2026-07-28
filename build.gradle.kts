// Top-level build file. Shared plugins are declared here with `apply false`
// so each module can apply them via `alias(libs.plugins.*)` in its own
// build.gradle.kts. Versions are centralized in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Gradle resolves a plugin marker and its whole classpath at configuration
    // time even under `apply false`, so these two put the com.android.test and
    // benchmark chains ahead of every invocation — `:sender:assembleDebug`
    // included. None of that chain ships in a stock Gradle cache, so the first
    // build on a machine must be online; CLAUDE.md lists the coordinates.
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}
