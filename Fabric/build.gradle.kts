val buildType: String by rootProject.extra

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
    id("fabric-loom") version "1.9.2"
}

group = "com.panomc.plugins.pano"
version = rootProject.version

repositories {
    maven("https://maven.fabricmc.net/")
}

loom {
    serverOnlyMinecraftJar()
}

// Custom configuration for dependencies that should be shaded
val shade: Configuration by configurations.creating {
    // Make shade deps also available on compileClasspath and runtimeClasspath
    configurations.getByName("implementation").extendsFrom(this)
}

dependencies {
    testImplementation(kotlin("test"))

    // Minecraft – provided by Fabric Loom
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:1.21.1+build.3:v2")

    // Fabric Loader – provided at runtime
    modCompileOnly("net.fabricmc:fabric-loader:0.16.10")

    // Fabric API – provided at runtime by fabric-api mod
    modCompileOnly("net.fabricmc.fabric-api:fabric-api:0.116.2+1.21.1")

    // Core module – will be shaded into the final JAR
    shade(project(path = ":Core", configuration = "shadow"))

    // LuckPerms API – provided at runtime by LuckPerms Fabric mod
    compileOnly("net.luckperms:api:5.5")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to version))
    }
}

tasks {
    jar {
        archiveClassifier.set("slim")
    }

    shadowJar {
        // Only shade dependencies from the custom "shade" configuration – NOT Minecraft/Fabric
        configurations = listOf(shade)

        manifest {
            val attrMap = mutableMapOf<String, String>()

            if (project.gradle.startParameter.taskNames.contains("buildDev"))
                attrMap["MODE"] = "DEVELOPMENT"

            attrMap["VERSION"] = version.toString()
            attrMap["BUILD_TYPE"] = buildType

            attributes(attrMap)
        }
        mergeServiceFiles()
        relocate("com.fasterxml.jackson", "com.panomc.shadow.jackson")
        relocate("io.netty", "vertx.io.netty")

        archiveClassifier.set("dev-all")
    }

    remapJar {
        dependsOn(shadowJar)
        inputFile.set(shadowJar.get().archiveFile)

        archiveFileName.set("${rootProject.name}-fabric-${version}.jar")

        if (project.gradle.startParameter.taskNames.contains("publish")) {
            archiveFileName.set(archiveFileName.get().lowercase())
        }
    }

    build {
        dependsOn(remapJar)
        doLast {
            // Clean up intermediate JARs, keep only the final remapped JAR
            shadowJar.get().archiveFile.get().asFile.delete()
            jar.get().archiveFile.get().asFile.delete()
        }
    }

    register("buildDev") {
        dependsOn(build)
    }

    register("buildPluginDev") {
        dependsOn(build)
        doLast {
            if (project.gradle.startParameter.taskNames.contains("buildPluginDev")) {
                copy {
                    from(remapJar.get().archiveFile.get().asFile.absolutePath)
                    into("../../minecraft test servers/Fabric/mods")
                }
            }
        }
    }
}
