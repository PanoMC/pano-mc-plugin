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

    // velocity
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
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
    filesMatching("velocity-plugin.json") {
        expand(mapOf("version" to version))
    }
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    register("buildPluginDev") {
        dependsOn(build)
        doLast {
            if (project.gradle.startParameter.taskNames.contains("buildPluginDev")) {
                copy {
                    from(shadowJar.get().archiveFile.get().asFile.absolutePath)
                    into("../../minecraft test servers/Velocity/plugins")
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

        archiveFileName.set("${rootProject.name}-velocity-${version}.jar")

        if (project.gradle.startParameter.taskNames.contains("publish")) {
            archiveFileName.set(archiveFileName.get().lowercase())
        }
    }
}