pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.7"
}

rootProject.name = "private-chests"

stonecutter {
    create(rootProject) {
        versions("1.21.11", "26.1.1", "26.2")
        vcsVersion = "26.2"
    }
}
