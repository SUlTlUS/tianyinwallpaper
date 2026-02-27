plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// buildscript and allprojects are no longer recommended in root build.gradle.kts when using version catalogs correctly.

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
