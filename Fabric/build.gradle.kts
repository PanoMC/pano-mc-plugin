import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val buildType: String by rootProject.extra

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
    // 26.x: unobfuscated game; Loom 1.16+ (see fabric-example-mod/26.1.2)
    id("net.fabricmc.fabric-loom") version "1.16.1"
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

    // Minecraft 26.1.2 (Mojang "26.1" release line) – unobfuscated; no Yarn mappings
    minecraft("com.mojang:minecraft:26.1.2")

    // Loader / API – at runtime (Loom 1.16+ unobfuscated: use compileOnly, not modCompileOnly)
    compileOnly("net.fabricmc:fabric-loader:0.19.2")
    compileOnly("net.fabricmc.fabric-api:fabric-api:0.146.1+26.1.2")

    // Core module – will be shaded into the final JAR
    shade(project(path = ":Core", configuration = "shadow"))

    // LuckPerms API – provided at runtime by LuckPerms Fabric mod
    compileOnly("net.luckperms:api:5.5")
}

kotlin {
    jvmToolchain(25)
}

// Kotlin 2.2: JVM_25 target not available yet; match Java byte level to 24
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 24
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

            if (project.gradle.startParameter.taskNames.contains("buildDev")) {
                attrMap["MODE"] = "DEVELOPMENT"
            }

            attrMap["VERSION"] = version.toString()
            attrMap["BUILD_TYPE"] = buildType

            attributes(attrMap)
        }
        mergeServiceFiles()
        relocate("com.fasterxml.jackson", "com.panomc.shadow.jackson")
        relocate("io.netty", "vertx.io.netty")

        archiveClassifier.set("")
        archiveFileName.set("${rootProject.name}-fabric-${version}.jar")
        if (project.gradle.startParameter.taskNames.contains("publish")) {
            archiveFileName.set(archiveFileName.get().lowercase())
        }
    }

    build {
        dependsOn(shadowJar)
        doLast {
            // Drop slim jar; the shaded JAR is the only distributable
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
                    from(shadowJar.get().archiveFile.get().asFile.absolutePath)
                    into("../../minecraft test servers/Fabric/mods")
                }
            }
        }
    }
}
