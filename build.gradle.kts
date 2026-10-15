// AGP 9 ships built-in Kotlin with an older KGP at runtime; raise it to the project's Kotlin version.
// https://developer.android.com/build/releases/agp-9-0-0-release-notes
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

// OneDrive locks files under build/ while syncing, which makes Gradle fail with AccessDeniedException.
// When the project sits in a OneDrive folder, keep build outputs in %LOCALAPPDATA%\roki-build instead.
val localAppData: String? = System.getenv("LOCALAPPDATA")
if (localAppData != null && rootDir.absolutePath.contains("OneDrive", ignoreCase = true)) {
    allprojects {
        layout.buildDirectory.set(File(localAppData, "roki-build/" + path.trim(':').ifEmpty { "root" }))
    }
}
