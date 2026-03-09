pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "pano"


include(":Core")
include(":Bungeecord")
include(":Fabric")
include(":Velocity")
include(":Spigot")