// settings.gradle.kts — Configures where Gradle finds plugins and dependencies,
// and which modules are included in the build.

pluginManagement {
    // Where Gradle looks for build plugins (AGP, Kotlin compiler plugin, etc.)
    repositories {
        google()          // Google's Maven repo — hosts the Android Gradle Plugin
        mavenCentral()    // The main public Maven repository
        gradlePluginPortal() // Gradle's own plugin registry
    }
}

dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS = if any module declares its own repository, the build fails.
    // This forces all dependency sources to be declared here, in one place.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The display name of the project (shown in Android Studio's title bar).
rootProject.name = "UyghurKeyboard"

// Include the :app module. Gradle will look for app/build.gradle.kts.
include(":app")
