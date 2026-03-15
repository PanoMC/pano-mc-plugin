plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("kapt") version "2.2.0" apply false
    id("com.gradleup.shadow") version "8.3.8" apply false
    `maven-publish`
}

group = "com.panomc.plugins"
version =
    (if (project.hasProperty("version") && project.findProperty("version") != "unspecified") project.findProperty("version") else "local-build")!!

val buildType by extra { project.findProperty("buildType") as String? ?: "alpha" }
val timeStamp: String by project
val buildDir by extra { file("${rootProject.layout.buildDirectory.get()}/libs") }

allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    if (name != "Fabric") {
        apply(plugin = "maven-publish")

        afterEvaluate {
            extensions.findByType<PublishingExtension>()?.apply {
                publications {
                    create<MavenPublication>("maven") {
                        artifact(tasks.named("shadowJar"))
                    }
                }
            }
        }
    }
}

tasks.withType<Jar> {
    enabled = false
}

tasks {
    register("buildDev") {
        dependsOn(subprojects.map { "${it.path}:buildDev" })
    }

    // This task builds and copys jar into server folders for test
    register("buildPluginDev") {
        dependsOn(subprojects.filter { it.name != "Core" }.map { "${it.path}:buildPluginDev" })
    }
}

