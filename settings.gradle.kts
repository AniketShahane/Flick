pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-casting"

include(":sender")
include(":receiver")

// One com.android.test module can name exactly one targetProjectPath, so the
// two apps need one producer each. Neither is reachable from `assemble*`.
include(":baselineprofile:sender")
include(":baselineprofile:receiver")
