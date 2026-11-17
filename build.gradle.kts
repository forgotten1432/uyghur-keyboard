// Top-level build file for the Uyghur Keyboard project.
// This file declares plugins used across the project but does NOT apply them here.
// Each module (e.g., :app) applies the plugins it needs in its own build.gradle.kts.

plugins {
    // Android Gradle Plugin — manages compiling, packaging, and signing .apk/.aab files.
    // "apply false" means: download this plugin but don't activate it at the root level.
    id("com.android.application") version "8.7.3" apply false

    // Kotlin Android plugin — compiles .kt files and integrates with the Android build pipeline.
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // Screenshot tests of the keyboard view (renders views on the JVM, no phone needed)
    id("app.cash.paparazzi") version "1.3.4" apply false
}
