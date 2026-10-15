import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val buildType: String by rootProject.extra

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

group = "com.panomc.plugins.pano"
version = rootProject.version

repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.codemc.org/repository/maven-public/")

    // The AuthMe jars servers actually run, straight from AuthMe's GitHub releases, for the
    // per-version compatibility tests below.
    ivy {
        name = "AuthMeReleases"
        url = uri("https://github.com/AuthMe/AuthMeReloaded/releases/download/")
        patternLayout {
            artifact("[revision]/[module]-[revision](-[classifier]).[ext]")
        }
        metadataSources {
            artifact()
        }
        content {
            includeGroup("authme-release")
        }
    }
}

// AuthMe releases the integration is tested against. Each gets its own test task running the
// @Tag("authme") tests with that release's real jar on the classpath, plus the libraries 5.7+ no
// longer shade and instead have the server download from plugin.yml "libraries:" (only the ones
// the tests load).
val authMeUnshadedLibraries = listOf(
    "ch.jalu:configme:1.3.1",
    "ch.jalu:injector:1.0",
    "ch.jalu:datasourcecolumns:0.1.2",
    "net.ricecode:string-similarity:1.0.0",
)
val authMeReleases = mapOf(
    "5.6.0" to listOf("authme-release:AuthMe:5.6.0@jar"),
    "5.7.0" to listOf("authme-release:AuthMe:5.7.0@jar") + authMeUnshadedLibraries,
    "6.0.1" to listOf("authme-release:AuthMe:6.0.1:Spigot-Legacy@jar") + authMeUnshadedLibraries,
)

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("org.ow2.asm:asm:9.9")
    // Compile-time AuthMe API for the tests; at runtime each testAuthMe<version> task supplies the
    // real release jar instead.
    testCompileOnly("fr.xephi:authme:5.6.0")

    implementation(project(":Core"))

//    Compile against the oldest supported Bukkit/Spigot surface; newer server APIs are reflected.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")

//    AuthMe Reloaded: compiled against the oldest supported release; 5.7.x and 6.0.x expose the
//    same API surface (checked per release by the testAuthMe<version> tasks).
    compileOnly("fr.xephi:authme:5.6.0")
}

kotlin {
    jvmToolchain(11)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("authme")
    }
    // Needs a real AuthMe jar on the classpath even to be discovered; run by testAuthMe<version>.
    filter {
        excludeTestsMatching("*AuthMeCompatibilityTest")
    }
}

val authMeTestTasks = authMeReleases.map { (version, notations) ->
    val suffix = version.replace('.', '_')
    val authMeRuntime = configurations.create("authMe${suffix}TestRuntime") {
        isTransitive = false
    }
    notations.forEach { dependencies.add(authMeRuntime.name, it) }

    tasks.register<Test>("testAuthMe$suffix") {
        description = "Runs the AuthMe compatibility tests against AuthMe $version."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath + authMeRuntime
        systemProperty("authme.version", version)
        useJUnitPlatform {
            includeTags("authme")
        }
        // AuthMe 5.7+ is Java 17 bytecode.
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        })
    }
}

tasks.check {
    dependsOn(authMeTestTasks)
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