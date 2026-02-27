pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://www.jitpack.io") }
        maven { url = uri("https://frontjs-static.pgyer.com/dist/sdk/pgyersdk") }
        maven { url = uri("https://raw.githubusercontent.com/Pgyer/analytics/master") }
    }
}
rootProject.name = "TianYinWallpaper"
include(":app")
