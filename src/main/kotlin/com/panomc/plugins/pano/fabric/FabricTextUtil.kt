package com.panomc.plugins.pano.fabric

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.MappingResolver

/**
 * Utility for translating legacy color codes and creating Minecraft Text
 * instances without compile-time Minecraft dependencies.
 */
object FabricTextUtil {
    private val resolver: MappingResolver = FabricLoader.getInstance().mappingResolver

    private val textClass: Class<*> by lazy {
        Class.forName(resolver.mapClassName("intermediary", "net.minecraft.class_2561"))
    }

    private val ofMethod by lazy {
        val name = resolver.mapMethodName(
            "intermediary",
            "net.minecraft.class_2561",
            "method_30163",
            "(Ljava/lang/String;)Lnet/minecraft/class_2561;",
        )
        textClass.getMethod(name, String::class.java)
    }

    private val formattingClass: Class<*> by lazy {
        Class.forName(resolver.mapClassName("intermediary", "net.minecraft.class_124"))
    }

    @Suppress("UNCHECKED_CAST")
    private val formattingEnum: Class<out Enum<*>>
        get() = formattingClass as Class<out Enum<*>>

    private fun enumConst(name: String): Enum<*> = java.lang.Enum.valueOf(formattingEnum, name)

    private val colorMap = mapOf(
        '0' to enumConst("BLACK"),
        '1' to enumConst("DARK_BLUE"),
        '2' to enumConst("DARK_GREEN"),
        '3' to enumConst("DARK_AQUA"),
        '4' to enumConst("DARK_RED"),
        '5' to enumConst("DARK_PURPLE"),
        '6' to enumConst("GOLD"),
        '7' to enumConst("GRAY"),
        '8' to enumConst("DARK_GRAY"),
        '9' to enumConst("BLUE"),
        'a' to enumConst("GREEN"),
        'b' to enumConst("AQUA"),
        'c' to enumConst("RED"),
        'd' to enumConst("LIGHT_PURPLE"),
        'e' to enumConst("YELLOW"),
        'f' to enumConst("WHITE"),
    )

    private val formatMap = mapOf(
        'k' to enumConst("OBFUSCATED"),
        'l' to enumConst("BOLD"),
        'm' to enumConst("STRIKETHROUGH"),
        'n' to enumConst("UNDERLINE"),
        'o' to enumConst("ITALIC"),
    )

    private val formatChars = (colorMap.keys + formatMap.keys + 'r').joinToString("")

    /**
     * Converts alternate color codes (using '&') to section sign codes.
     */
    fun translateColorCodes(text: String): String {
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&' && i + 1 < text.length && text[i + 1].lowercaseChar() in formatChars) {
                builder.append('§').append(text[i + 1])
                i += 2
            } else {
                builder.append(c)
                i++
            }
        }
        return builder.toString()
    }

    /**
     * Creates a Minecraft Text instance from the provided string,
     * translating legacy color codes and applying formatting.
     */
    fun toText(text: String): Any {
        val input = translateColorCodes(text)
        var color: Enum<*>? = null
        val formats = linkedSetOf<Enum<*>>()
        var result: Any? = null
        var i = 0
        while (i < input.length) {
            if (input[i] == '§' && i + 1 < input.length) {
                val code = input[i + 1].lowercaseChar()
                when {
                    colorMap.containsKey(code) -> {
                        color = colorMap[code]
                        formats.clear()
                    }
                    formatMap.containsKey(code) -> formats.add(formatMap[code]!!)
                    code == 'r' -> {
                        color = null
                        formats.clear()
                    }
                }
                i += 2
                continue
            }

            val start = i
            while (i < input.length && input[i] != '§') i++
            val segmentText = input.substring(start, i)
            var segment = ofMethod.invoke(null, segmentText)

            val applied = mutableListOf<Enum<*>>()
            color?.let { applied.add(it) }
            applied.addAll(formats)

            if (applied.isNotEmpty()) {
                val array = java.lang.reflect.Array.newInstance(formattingClass, applied.size) as Array<Any>
                for (index in applied.indices) array[index] = applied[index]

                val formattedMethod = segment.javaClass.methods.firstOrNull {
                    it.parameterCount == 1 &&
                        it.parameterTypes[0].isArray &&
                        it.parameterTypes[0].componentType == formattingClass
                }
                // Some implementations of formatted() return a new instance
                // rather than mutating the original text. Capture the return
                // value to ensure styles are applied regardless of the
                // underlying behavior.
                formattedMethod?.let { method ->
                    val formatted = method.invoke(segment, array)
                    if (formatted != null) segment = formatted
                }
            }

            result = if (result == null) {
                segment
            } else {
                val appendMethod = result.javaClass.methods.firstOrNull {
                    it.parameterCount == 1 &&
                        it.parameterTypes[0] == textClass
                }
                val appended = appendMethod?.invoke(result, segment)
                appended ?: result
            }
        }

        return result ?: ofMethod.invoke(null, "")
    }

    /** Exposes the resolved Text class for reflective calls. */
    val TEXT_CLASS: Class<*> get() = textClass
}

