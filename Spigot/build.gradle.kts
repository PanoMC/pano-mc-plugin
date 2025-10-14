val buildType: String by rootProject.extra

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

group = "com.panomc.plugins.pano"
version = "local-build"

dependencies {
    testImplementation(kotlin("test"))

    implementation(project(":Core"))

//     paper / spigot20
    compileOnly("io.papermc.paper:paper-api:1.17-R0.1-SNAPSHOT")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("jar").configure {
    enabled = false
}

tasks.processResources {
    filesMatching("plugin.yml") {
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
                    into("../../minecraft test servers/Folia/plugins")
                }
                copy {
                    from(shadowJar.get().archiveFile.get().asFile.absolutePath)
                    into("../../minecraft test servers/Spigot/plugins")
                }
                copy {
                    from(shadowJar.get().archiveFile.get().asFile.absolutePath)
                    into("../../minecraft test servers/Paper/plugins")
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

        archiveFileName.set("${rootProject.name}-spigot-${version}.jar")

        if (project.gradle.startParameter.taskNames.contains("publish")) {
            archiveFileName.set(archiveFileName.get().lowercase())
        }
    }
}