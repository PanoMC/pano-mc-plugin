import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask

val vertxVersion = "5.0.3"

plugins {
    kotlin("jvm") version "2.2.0"
    id("fabric-loom") version "1.7.4"
    id("com.gradleup.shadow") version "8.3.8"
    `maven-publish`
}

group = "com.panomc.plugins"
version =
    (if (project.hasProperty("version") && project.findProperty("version") != "unspecified") project.findProperty("version") else "local-build")!!

val buildType = project.findProperty("buildType") as String? ?: "alpha"
val timeStamp: String by project
val buildDir by extra { file("${rootProject.layout.buildDirectory.get()}/libs") }

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://oss.sonatype.org/content/repositories/iovertx-3720/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    minecraft("com.mojang:minecraft:1.21.4")
    mappings("net.fabricmc:yarn:1.21.4+build.2:v2")
    modCompileOnly("net.fabricmc:fabric-loader:0.16.14")
    modCompileOnly("net.fabricmc.fabric-api:fabric-api:0.115.0+1.21.4")

    // spigot
    compileOnly("org.spigotmc:spigot-api:1.19.2-R0.1-SNAPSHOT")

    // bungeecord
    compileOnly("net.md-5:bungeecord-api:1.19-R0.1-SNAPSHOT")

    // paper spigot
    compileOnly("io.papermc.paper:paper-api:1.19.2-R0.1-SNAPSHOT")

    // velocity
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-web-client:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-config:$vertxVersion")
    implementation("io.vertx:vertx-config-hocon:$vertxVersion")
    implementation("io.vertx:vertx-json-schema:$vertxVersion")

    implementation("com.fasterxml.jackson.core:jackson-core:2.19.2")

    implementation("org.springframework:spring-context:5.3.39")
}

tasks.named("jar").configure {
    enabled = false
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(mapOf("version" to version))
    }

    filesMatching("bungee.yml") {
        expand(mapOf("version" to version))
    }

    filesMatching("velocity-plugin.json") {
        expand(mapOf("version" to version))
    }

    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to version))
    }
}

tasks {
    val shadowJar by existing(ShadowJar::class)

    // Disable Loom's remapJar tasks to speed up builds
    withType<RemapJarTask>().configureEach {
        enabled = false
    }

    register("copyJar") {
        dependsOn(shadowJar)
        doLast {
            val jarFile = shadowJar.get().archiveFile.get().asFile
            copy {
                from(jarFile)
                into("../minecraft test servers/Spigot/plugins")
            }

            copy {
                from(jarFile)
                into("../minecraft test servers/Bungeecord/plugins")
            }

            copy {
                from(jarFile)
                into("../minecraft test servers/Velocity/plugins")
            }

            copy {
                from(jarFile)
                into("../minecraft test servers/Fabric/mods")
            }
        }
    }

    build {
        dependsOn(shadowJar)
    }

    register("buildDev") {
        dependsOn("build")
    }

    // This task builds and copies jar into server folders for test
    register("buildPluginDev") {
        dependsOn("buildDev")
        dependsOn("copyJar")
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

        archiveFileName.set("${rootProject.name}-${version}.jar")

        if (project.gradle.startParameter.taskNames.contains("publish")) {
            archiveFileName.set(archiveFileName.get().lowercase())
        }
        isZip64 = true
    }
}

publishing {
    repositories {
        maven {
            name = "pano-mc-plugin"
            url = uri("https://maven.pkg.github.com/panocms/pano-mc-plugin")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME_GITHUB")
                password = project.findProperty("gpr.token") as String? ?: System.getenv("TOKEN_GITHUB")
            }
        }
    }

    publications {
        create<MavenPublication>("shadow") {
            project.extensions.configure<com.github.jengelman.gradle.plugins.shadow.ShadowExtension> {
                artifactId = "pano-mc"
                component(this@create)
            }
        }
    }
}
