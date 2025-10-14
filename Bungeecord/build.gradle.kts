val buildType: String by rootProject.extra

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

group = "com.panomc.plugins.pano"
version = rootProject.version

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(project(":Core"))

    // bungeecord
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
}

kotlin {
    jvmToolchain(8)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("jar").configure {
    enabled = false
}

tasks.processResources {
    filesMatching("bungee.yml") {
        expand(mapOf("version" to version))
    }
}
tasks {
    build {
        dependsOn(shadowJar)
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
                    into("../../minecraft test servers/Bungeecord/plugins")
                }
            }
        }
    }

    shadowJar {
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

        archiveFileName.set("${rootProject.name}-bungeecord-${version}.jar")

        if (project.gradle.startParameter.taskNames.contains("publish")) {
            archiveFileName.set(archiveFileName.get().lowercase())
        }
    }
}