val vertxVersion: String by rootProject
val gsonVersion: String by rootProject
val buildType: String by rootProject.extra

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

group = "com.panomc.plugins.pano"
version = rootProject.version

repositories {
    maven("https://oss.sonatype.org/content/repositories/iovertx-3720/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

dependencies {
    testImplementation(kotlin("test"))
    api(kotlin("stdlib-jdk8"))

    api("io.vertx:vertx-core:${vertxVersion}")
    api("io.vertx:vertx-web-client:${vertxVersion}")
    api("io.vertx:vertx-lang-kotlin:${vertxVersion}")
    api("io.vertx:vertx-lang-kotlin-coroutines:${vertxVersion}")
    api("io.vertx:vertx-config:${vertxVersion}")
    api("io.vertx:vertx-config-hocon:${vertxVersion}")
    api("io.vertx:vertx-json-schema:${vertxVersion}")

    api("org.springframework:spring-context:5.3.39")

    api("com.fasterxml.jackson.core:jackson-core:2.19.2")
    api("com.fasterxml.jackson.core:jackson-databind:2.19.2")

    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    api("com.google.code.gson:gson:${gsonVersion}")

    // Handlebars for template rendering in translations
    api("com.github.jknack:handlebars:4.3.1")

    // LuckPerms API must NOT be shaded into our jar. LuckPerms provides it at runtime.
    compileOnly("net.luckperms:api:5.5")
}

kotlin {
    jvmToolchain(8)
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    register("buildDev") {
        dependsOn(build)
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
    }
}