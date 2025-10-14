val vertxVersion: String by rootProject

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("kapt") version "2.2.0"
}

group = "com.panomc.plugins.pano"
version = "local-build"

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
}

kotlin {
    jvmToolchain(8)
}

tasks.test {
    useJUnitPlatform()
}