pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "pano"


include(":Core")
include(":Bungeecord")
include(":Fabric")
include(":Velocity")
include(":Spigot")