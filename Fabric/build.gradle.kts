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

        // spring-beans ships META-INF/spring.factories (BeanInfoFactory=ExtendedBeanInfoFactory)
        // plus spring.handlers/spring.schemas/spring.tooling (XML namespace-handler lookup for
        // <context:.../>, <task:.../> etc.). None of these sit under META-INF/services, so
        // mergeServiceFiles()'s relocation-aware rewriting (below the org.springframework
        // relocate) never touches them and their CONTENT still names the pre-relocation classes
        // verbatim, e.g. spring.factories' value `org.springframework.beans.ExtendedBeanInfoFactory`
        // no longer exists at that name once relocated -- it only exists at
        // com.panomc.shadow.springframework.beans.ExtendedBeanInfoFactory. Verified against
        // Fabric/build/libs/pano-fabric-local-build.jar: confirmed present, unrewritten, and
        // pointing at classes that only exist under the relocated package. Loading spring.factories
        // as-is would make SpringFactoriesLoader.instantiateFactory() throw
        // ClassNotFoundException/IllegalArgumentException, crashing CachedIntrospectionResults'
        // static initializer the first time Spring introspects a bean's JavaBean properties.
        // SpringConfig (Core) only uses AnnotationConfigApplicationContext with @Configuration/@Bean
        // factory methods -- no XML bean definitions, no <context:.../>-style namespace tags -- so
        // none of these four files are read by anything this app actually does; excluding them
        // removes the dangling references instead of leaving them to fire the first time some
        // future Spring code path (or a Spring internals change) does touch one.
        exclude("META-INF/spring.factories")
        exclude("META-INF/spring.handlers")
        exclude("META-INF/spring.schemas")
        exclude("META-INF/spring.tooling")

        relocate("com.fasterxml.jackson", "com.panomc.shadow.jackson")
        relocate("io.netty", "vertx.io.netty")
        // Fabric's Knot classloader is flat across every mod jar (unlike Bukkit/Bungee/Velocity,
        // which give each plugin its own classloader), so an unrelocated kotlin/kotlinx/gson/
        // spring/vertx/handlebars here collides with any other mod bundling the same libraries —
        // whichever mod's classes Knot resolves first wins for everyone (core-misc-10).
        relocate("kotlin.", "com.panomc.shadow.kotlin.")
        relocate("kotlinx.", "com.panomc.shadow.kotlinx.")
        relocate("com.google.gson", "com.panomc.shadow.gson")
        relocate("org.springframework", "com.panomc.shadow.springframework")
        relocate("io.vertx", "com.panomc.shadow.vertx")
        relocate("com.github.jknack", "com.panomc.shadow.handlebars")

        // core-misc-10 follow-up: unzipping Fabric/build/libs/pano-fabric-local-build.jar found
        // several more third-party packages that were still shipping unrelocated under the exact
        // collision-prone names above -- exactly the class the relocations above exist to prevent.
        // spring-jcl's commons-logging shim (Spring's own internal logging, never referenced by our
        // code -- verified via a repo-wide grep for "commons.logging"/"commons-logging"): 17 class
        // files (confirmed by unzipping) plus a
        // META-INF/services/org.apache.commons.logging.LogFactory entry that mergeServiceFiles()
        // (declared above) rewrites consistently with this relocation, per the
        // exclude("META-INF/spring.factories") comment above.
        relocate("org.apache.commons.logging", "com.panomc.shadow.commonslogging")
        // AOP Alliance interfaces, a transitive dependency pulled in by Spring (not used directly by
        // any code in this tree -- no AOP proxying/interception is done here). Pure internal-only
        // interfaces, safe to relocate the same way as org.springframework itself.
        relocate("org.aopalliance", "com.panomc.shadow.aopalliance")
        // JetBrains/IntelliJ CLASS-retention annotations (Nullable/NotNull/etc.), pulled in
        // transitively. Never looked up reflectively by name anywhere in this tree (verified via
        // grep) -- purely tooling metadata baked into other libraries' bytecode -- so relocating
        // them alongside kotlin./kotlinx. above is safe.
        relocate("org.jetbrains.annotations", "com.panomc.shadow.jetbrainsannotations")
        relocate("org.intellij.lang.annotations", "com.panomc.shadow.intellijannotations")
        // Guava/error-prone's annotation-only support library, transitive, referenced only by type
        // (never Class.forName/ServiceLoader -- verified via grep), so relocating it alongside
        // com.google.gson above is safe. NOTE: com.google.gson itself is the only other com.google.*
        // subtree this jar bundles (confirmed by unzipping) -- no separate Guava relocation needed.
        relocate("com.google.errorprone", "com.panomc.shadow.errorprone")
        // Typesafe/Lightbend Config (HOCON) -- ConfigManager.kt (Core) imports com.typesafe.config.*
        // directly to parse config.conf. Real, actively used, ~180-class library; a second mod
        // bundling its own copy under the same unrelocated name is a realistic collision. Relocating
        // does not disturb ConfigFactory's `reference.conf` discovery: that lookup is by a fixed
        // resource filename, not tied to the com.typesafe.* package path being relocated.
        relocate("com.typesafe", "com.panomc.shadow.typesafeconfig")

        // org/slf4j is deliberately EXCLUDED below rather than relocated, and is the one package
        // this pass leaves unrelocated on purpose: FabricLogger.kt (this module, not owned by this
        // round's Fabric changes) imports `org.slf4j.LoggerFactory` directly by that exact,
        // unrelocated name specifically to bind Pano's logs to Fabric/Minecraft's OWN ambient SLF4J
        // binding (so Pano logs render in Fabric's native [HH:MM:SS] format) -- FabricEventListener.kt
        // and FabricPreLoginHandler.kt do the same via `org.slf4j.LoggerFactory.getLogger("Pano")`.
        // Core's LoggerUtil.kt separately does `Class.forName("org.slf4j.LoggerFactory")` (a string
        // literal a package relocation cannot rewrite) for its Logback level-adjustment path. If
        // org.slf4j were relocated, ShadowJar's remapper would rewrite those bytecode references too
        // (relocate() applies across the WHOLE merged jar, not just the "shade" dependencies), which
        // would either break the LoggerUtil string lookup outright or -- worse -- silently point
        // FabricLogger at a private, provider-less copy of slf4j-api bundled here (this jar ships
        // slf4j-api classes only, no `org/slf4j/impl` binder and no
        // META-INF/services/org.slf4j.spi.SLF4JServiceProvider -- confirmed by unzipping), which logs
        // nowhere instead of Fabric's console. Excluding it outright (rather than merely leaving it
        // shaded-but-unrelocated) also removes the Knot flat-classloader risk of OUR possibly
        // version-mismatched slf4j-api classes winning over Fabric's own and breaking its real
        // binding; Minecraft/Fabric-loader environments already ship their own slf4j-api at runtime,
        // so nothing here is actually lost by not bundling a second copy.
        exclude("org/slf4j/**")

        // Reviewed and left as-is (not a Java package `relocate()` can meaningfully act on):
        // - `_COROUTINE/**` (4 classes: _CREATION, _BOUNDARY, CoroutineDebuggingKt,
        //   ArtificialStackFrames) is kotlinx-coroutines' own well-known, intentionally-unshaded
        //   debug/stack-trace-recovery marker package -- by upstream Kotlin convention this is left
        //   unrelocated by every shading setup that bundles kotlinx-coroutines, so an identical copy
        //   is expected to coexist harmlessly with any other mod's.
        // - `json-schema.org/**` (JSON Schema draft-04/07/2019-09/2020-12 meta-schema resource
        //   files, not classes -- most likely bundled transitively via a Vert.x config/validation
        //   module) is a plain resource path with no Java package structure for relocate() to
        //   rewrite; fixing a potential resource-path collision here would need a custom Shadow
        //   content transformer, which is out of scope for this pass. Left unaddressed and flagged
        //   rather than silently dropped or guessed at.

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
