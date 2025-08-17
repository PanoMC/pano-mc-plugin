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
            "(Ljava/lang/String;)Lnet/minecraft/class_2561;"
        )
        textClass.getMethod(name, String::class.java)
    }

    /**
     * Converts alternate color codes (using '&') to section sign codes.
     */
    fun translateColorCodes(text: String): String = text.replace("&", "§")

    /**
     * Creates a Minecraft Text instance from the provided string,
     * translating legacy color codes before conversion.
     */
    fun toText(text: String): Any {
        return ofMethod.invoke(null, translateColorCodes(text))
    }

    /** Exposes the resolved Text class for reflective calls. */
    val TEXT_CLASS: Class<*> get() = textClass
}
