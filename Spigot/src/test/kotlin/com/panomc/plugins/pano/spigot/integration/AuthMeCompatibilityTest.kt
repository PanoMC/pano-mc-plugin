package com.panomc.plugins.pano.spigot.integration

import com.panomc.plugins.pano.spigot.SpigotMain
import fr.xephi.authme.datasource.DataSource
import fr.xephi.authme.events.LoginEvent
import fr.xephi.authme.events.LogoutEvent
import fr.xephi.authme.events.PasswordEncryptionEvent
import fr.xephi.authme.events.RegisterEvent
import fr.xephi.authme.security.HashAlgorithm
import fr.xephi.authme.security.PasswordSecurity
import fr.xephi.authme.security.crypts.HashedPassword
import fr.xephi.authme.settings.Settings
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.Warning
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.plugin.java.JavaPluginLoader
import org.junit.jupiter.api.Tag
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.lang.reflect.Modifier
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Runs once per supported AuthMe release (Spigot's testAuthMe<version> tasks put that release's real
// jar on the classpath, see Spigot/build.gradle.kts). Everything here exercises AuthMe's own code from
// that jar, not the API jar the plugin compiles against.
@Tag("authme")
class AuthMeCompatibilityTest {

    private val expectedVersion: String = System.getProperty("authme.version")
        ?: error("Run through a testAuthMe<version> Gradle task, not the plain test task.")

    private val loader: ClassLoader = javaClass.classLoader

    @Test
    fun `the AuthMe jar on the classpath is the release under test`() {
        val authMeDescriptor = loader.getResources("plugin.yml").toList()
            .map { it.readText() }
            .singleOrNull { Regex("(?m)^name:\\s*AuthMe\\s*$").containsMatchIn(it) }

        assertNotNull(authMeDescriptor, "exactly one AuthMe plugin.yml should be on the classpath")

        val version = Regex("(?m)^version:\\s*(\\S+)").find(authMeDescriptor)?.groupValues?.get(1)

        assertTrue(version?.startsWith("$expectedVersion-") == true, "expected AuthMe $expectedVersion, got $version")
    }

    // Every AuthMe class, constructor, method and field the compiled integration references must
    // exist with the same descriptor in this release, and no Pano class implementing an AuthMe type
    // may leave one of its abstract methods unimplemented - otherwise the server would only find out
    // with a NoSuchMethodError / AbstractMethodError at login time.
    @Test
    fun `every AuthMe symbol the plugin links against exists in this release`() {
        val classesDir = File(AuthMeIntegration::class.java.protectionDomain.codeSource.location.toURI())
        val references = collectAuthMeReferences(classesDir)

        assertTrue(references.classes.isNotEmpty(), "no AuthMe references found under $classesDir")

        val problems = mutableListOf<String>()

        references.classes.forEach { internalName ->
            if (loadOrNull(internalName) == null) problems += "missing class $internalName"
        }

        references.methods.forEach { (owner, name, descriptor) ->
            val ownerClass = loadOrNull(owner) ?: return@forEach

            val found = if (name == "<init>") {
                ownerClass.declaredConstructors.any { Type.getConstructorDescriptor(it) == descriptor }
            } else {
                hierarchy(ownerClass).any { type ->
                    type.declaredMethods.any { it.name == name && Type.getMethodDescriptor(it) == descriptor }
                }
            }

            if (!found) problems += "missing method $owner.$name$descriptor"
        }

        references.fields.forEach { (owner, name, descriptor) ->
            val ownerClass = loadOrNull(owner) ?: return@forEach

            val found = hierarchy(ownerClass).any { type ->
                type.declaredFields.any { it.name == name && Type.getDescriptor(it.type) == descriptor }
            }

            if (!found) problems += "missing field $owner.$name:$descriptor"
        }

        references.implementors.forEach { internalName ->
            val implementor = Class.forName(internalName.replace('/', '.'), false, loader)

            if (!Modifier.isAbstract(implementor.modifiers)) {
                implementor.methods.filter { Modifier.isAbstract(it.modifiers) }.forEach {
                    problems += "${implementor.name} does not implement ${it.declaringClass.name}.${it.name}${Type.getMethodDescriptor(it)}"
                }
            }
        }

        assertTrue(problems.isEmpty(), "AuthMe $expectedVersion is not link-compatible:\n" + problems.joinToString("\n"))
    }

    // forceConfig()/isConfigCompatible() write and compare these paths in AuthMe's config.yml.
    @Test
    fun `the settings Pano forces exist in this release with values it accepts`() {
        val properties = listOf(
            "fr.xephi.authme.settings.properties.SecuritySettings",
            "fr.xephi.authme.settings.properties.RegistrationSettings",
            "fr.xephi.authme.settings.properties.RestrictionSettings",
        ).flatMap { className ->
            Class.forName(className, true, loader).fields
                .filter { Modifier.isStatic(it.modifiers) }
                .mapNotNull { field -> field.get(null)?.let { property -> propertyPath(property)?.let { it to property } } }
        }.toMap()

        AuthMeIntegration.requiredConfigSettings.forEach { setting ->
            val property = properties[setting.path]

            assertNotNull(property, "AuthMe $expectedVersion has no setting ${setting.path}")

            when (setting.path) {
                "settings.security.passwordHash" ->
                    assertEquals(HashAlgorithm.CUSTOM, HashAlgorithm.valueOf(setting.expectedValue as String))

                "settings.registration.type" ->
                    Class.forName("fr.xephi.authme.process.register.RegistrationType", true, loader)
                        .enumConstants.single { (it as Enum<*>).name == setting.expectedValue }
            }
        }
    }

    // The command guards must know every label AuthMe registers for the commands they intercept,
    // or an alias would bypass them.
    @Test
    fun `the command guards cover every AuthMe label for the commands they intercept`() {
        val initializer = Class.forName("fr.xephi.authme.command.CommandInitializer", true, loader)
            .getDeclaredConstructor().newInstance()

        @Suppress("UNCHECKED_CAST")
        val commands = initializer.javaClass.getMethod("getCommands").invoke(initializer) as List<Any>

        fun labels(description: Any) = (description.javaClass.getMethod("getLabels").invoke(description) as List<*>).map { it as String }
        fun executable(description: Any) = (description.javaClass.getMethod("getExecutableCommand").invoke(description) as Class<*>).simpleName

        @Suppress("UNCHECKED_CAST")
        fun children(description: Any) = description.javaClass.getMethod("getChildren").invoke(description) as List<Any>

        val selfUnregister = commands.single { executable(it) == "UnregisterCommand" }
        assertEquals(labels(selfUnregister).toSet(), AuthMeCommandMatcher.SELF_UNREGISTER_LABELS)

        val authMe = commands.single { "authme" in labels(it) }
        val adminLabels = mapOf(
            "UnregisterAdminCommand" to AuthMeCommandMatcher.ADMIN_UNREGISTER_LABELS,
            "RegisterAdminCommand" to AuthMeCommandMatcher.ADMIN_REGISTER_LABELS,
            "ReloadCommand" to AuthMeCommandMatcher.RELOAD_LABELS,
        )

        adminLabels.forEach { (executableName, guarded) ->
            val child = children(authMe).single { executable(it) == executableName }

            assertEquals(labels(child).toSet(), guarded, "labels of /authme ${labels(child).first()}")
        }
    }

    // Real Bukkit event dispatch into the real AuthMeIntegration listener, triggered by this release's
    // real PasswordSecurity: proves the CUSTOM hash hook is picked up end to end.
    @Test
    fun `AuthMe resolves passwordHash CUSTOM to Pano's encryption method through the real listener`() {
        val server = mock(Server::class.java)
        val pluginManager = SimplePluginManager(server, null)
        doReturn(pluginManager).`when`(server).pluginManager
        doReturn(Logger.getLogger("test-server")).`when`(server).logger
        doReturn(Warning.WarningState.DEFAULT).`when`(server).warningState
        // Spigot's event timings read the plugin manager through the static Bukkit.getServer().
        if (Bukkit.getServer() == null) {
            Bukkit.setServer(server)
        }
        doReturn(pluginManager).`when`(Bukkit.getServer()).pluginManager

        val panoPlugin = mock(SpigotMain::class.java)
        doReturn(true).`when`(panoPlugin).isEnabled
        doReturn(JavaPluginLoader(server)).`when`(panoPlugin).pluginLoader
        doReturn(Logger.getLogger("test-pano")).`when`(panoPlugin).logger
        doReturn(PluginDescriptionFile("Pano", "test", SpigotMain::class.java.name)).`when`(panoPlugin).description

        val integration = AuthMeIntegration(panoPlugin)

        try {
            pluginManager.registerEvents(integration, panoPlugin)

            // JavaPluginLoader swallows a NoClassDefFoundError while reflecting the listener and
            // registers nothing, so check every AuthMe event handler actually got registered.
            listOf(
                PasswordEncryptionEvent.getHandlerList(),
                LoginEvent.getHandlerList(),
                LogoutEvent.getHandlerList(),
                RegisterEvent.getHandlerList(),
            ).forEach { handlers ->
                assertTrue(handlers.registeredListeners.any { it.listener === integration })
            }

            val passwordSecurity = newPasswordSecurity(pluginManager, dataSourceReturning(null))

            assertIs<PanoAuthMeEncryptionMethod>(readField(passwordSecurity, "encryptionMethod"))
        } finally {
            HandlerList.unregisterAll(integration)
        }
    }

    @Test
    fun `hashing goes to the platform and AuthMe only ever stores the placeholder`() {
        val hashed = mutableListOf<Pair<String, String>>()
        val passwordSecurity = newPasswordSecurity(
            pluginManagerInstalling(PanoAuthMeEncryptionMethod({ name, password -> hashed += name to password }, { _, _ -> false })),
            dataSourceReturning(null)
        )

        val stored = passwordSecurity.computeHash("s3cret-Pass", "Steve")

        assertEquals(PanoAuthMeEncryptionMethod.MANAGED_PASSWORD_PLACEHOLDER, stored.hash)
        // AuthMe lowercases the name before hashing in every supported release.
        assertEquals(listOf("steve" to "s3cret-Pass"), hashed)
    }

    @Test
    fun `password checks are answered by the platform`() {
        val checked = mutableListOf<Pair<String, String>>()
        val passwordSecurity = newPasswordSecurity(
            pluginManagerInstalling(PanoAuthMeEncryptionMethod({ _, _ -> }, { name, password ->
                checked += name to password
                password == "right"
            })),
            dataSourceReturning(HashedPassword(PanoAuthMeEncryptionMethod.MANAGED_PASSWORD_PLACEHOLDER))
        )

        assertTrue(passwordSecurity.comparePassword("right", "Steve"))
        assertFalse(passwordSecurity.comparePassword("wrong", "Steve"))
        assertEquals(listOf("steve" to "right", "steve" to "wrong"), checked)
    }

    @Test
    fun `a player without an AuthMe row is rejected without asking the platform`() {
        var asked = false
        val passwordSecurity = newPasswordSecurity(
            pluginManagerInstalling(PanoAuthMeEncryptionMethod({ _, _ -> }, { _, _ -> asked = true; true })),
            dataSourceReturning(null)
        )

        assertFalse(passwordSecurity.comparePassword("anything", "Steve"))
        assertFalse(asked)
    }

    private fun newPasswordSecurity(pluginManager: PluginManager, dataSource: DataSource): PasswordSecurity {
        // Answer by property path so this works whether ConfigMe is relocated (5.6.x shades it) or not.
        val settings = mock(Settings::class.java, withSettings().defaultAnswer { invocation ->
            when (invocation.method.name) {
                "getProperty" -> when (val path = propertyPath(invocation.arguments[0])) {
                    "settings.security.passwordHash" -> HashAlgorithm.CUSTOM
                    "settings.security.legacyHashes" -> emptySet<HashAlgorithm>()
                    else -> error("unexpected setting $path")
                }

                else -> null
            }
        })

        return PasswordSecurity().also {
            writeField(it, "settings", settings)
            writeField(it, "dataSource", dataSource)
            writeField(it, "pluginManager", pluginManager)
            it.reload()
        }
    }

    // Stands in for Bukkit dispatching PasswordEncryptionEvent to AuthMeIntegration's listener.
    private fun pluginManagerInstalling(method: PanoAuthMeEncryptionMethod): PluginManager {
        val pluginManager = mock(PluginManager::class.java)

        doAnswer { invocation ->
            (invocation.arguments[0] as? PasswordEncryptionEvent)?.method = method
            null
        }.`when`(pluginManager).callEvent(any(Event::class.java))

        return pluginManager
    }

    private fun dataSourceReturning(password: HashedPassword?): DataSource =
        mock(DataSource::class.java, withSettings().defaultAnswer { invocation ->
            if (invocation.method.name == "getPassword") password else null
        })

    private fun propertyPath(property: Any): String? =
        runCatching { property.javaClass.getMethod("getPath").invoke(property) as String }.getOrNull()

    private fun readField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)

    private fun writeField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)
    }

    private fun loadOrNull(internalName: String): Class<*>? =
        try {
            Class.forName(internalName.replace('/', '.'), false, loader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        }

    private fun hierarchy(type: Class<*>): Sequence<Class<*>> = sequence {
        val seen = mutableSetOf<Class<*>>()
        val queue = ArrayDeque(listOf(type))

        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()

            if (seen.add(next)) {
                yield(next)
                next.superclass?.let(queue::add)
                queue.addAll(next.interfaces)
            }
        }
    }

    private data class MemberRef(val owner: String, val name: String, val descriptor: String)

    private class AuthMeReferences {
        val classes = sortedSetOf<String>()
        val methods = mutableSetOf<MemberRef>()
        val fields = mutableSetOf<MemberRef>()

        // Pano classes that extend or implement an AuthMe type.
        val implementors = sortedSetOf<String>()
    }

    private fun collectAuthMeReferences(classesDir: File): AuthMeReferences {
        val references = AuthMeReferences()
        val authMePrefix = "fr/xephi/authme/"

        fun addType(type: Type) {
            when (type.sort) {
                Type.ARRAY -> addType(type.elementType)
                Type.OBJECT -> if (type.internalName.startsWith(authMePrefix)) references.classes += type.internalName
                Type.METHOD -> {
                    addType(type.returnType)
                    type.argumentTypes.forEach(::addType)
                }
            }
        }

        fun addInternalName(internalName: String) = addType(Type.getObjectType(internalName))

        fun addConstant(value: Any?) {
            when (value) {
                is Type -> addType(value)
                is Handle -> if (value.owner.startsWith(authMePrefix)) {
                    references.methods += MemberRef(value.owner, value.name, value.desc)
                }
            }
        }

        classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
            val bytes = file.readBytes()

            if (!String(bytes, Charsets.ISO_8859_1).contains(authMePrefix)) {
                return@forEach
            }

            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    val parents = listOfNotNull(superName) + interfaces.orEmpty()

                    if (parents.any { it.startsWith(authMePrefix) }) references.implementors += name
                    parents.forEach(::addInternalName)
                }

                override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                    addType(Type.getType(descriptor))
                    return null
                }

                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    addType(Type.getMethodType(descriptor))

                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            addType(Type.getMethodType(descriptor))

                            if (owner.startsWith(authMePrefix)) {
                                addInternalName(owner)
                                references.methods += MemberRef(owner, name, descriptor)
                            }
                        }

                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                            addType(Type.getType(descriptor))

                            if (owner.startsWith(authMePrefix)) {
                                addInternalName(owner)
                                references.fields += MemberRef(owner, name, descriptor)
                            }
                        }

                        override fun visitTypeInsn(opcode: Int, type: String) = addInternalName(type)

                        override fun visitLdcInsn(value: Any?) = addConstant(value)

                        override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any?) {
                            addType(Type.getMethodType(descriptor))
                            bootstrapMethodArguments.forEach(::addConstant)
                        }

                        override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) {
                            type?.let(::addInternalName)
                        }
                    }
                }
            }, ClassReader.SKIP_FRAMES)
        }

        return references
    }
}
