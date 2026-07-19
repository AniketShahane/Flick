// Top-level build file. Shared plugins are declared here with `apply false`
// so each module can apply them via `alias(libs.plugins.*)` in its own
// build.gradle.kts. Versions are centralized in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
