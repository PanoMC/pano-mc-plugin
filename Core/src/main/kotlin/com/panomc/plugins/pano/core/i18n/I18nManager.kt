package com.panomc.plugins.pano.core.i18n

import com.github.jknack.handlebars.Handlebars
import com.panomc.plugins.pano.core.platform.message.response.GetPlayerInfoMessage
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import java.util.logging.Logger

/**
 * I18n Manager - Handles translations for Minecraft plugin.
 *
 * This manager is responsible for loading and managing translations that are received from the Pano platform.
 * Translations are cached and updated when serverSettings change.
 *
 * **How it works:**
 * 1. Translations are received from platform via GetServerSettingsMessage
 * 2. Translations are cached in translationsCache as Map<LocaleCode, Map<Key, Value>>
 * 3. When getting a translation, first checks for player-specific locale from playerInfo
 * 4. Falls back to platform locale if player locale is not available
 * 5. Returns translation from the appropriate locale's translation map
 * 6. Supports Handlebars template syntax for variable substitution
 *
 * **Usage:**
 * ```kotlin
 * // Get a translation for a player
 * val playerInfo = platformManager.sendMessageAwaitResponse<GetPlayerInfoMessage>(...)
 * val translation = i18nManager.getTranslation(playerInfo, "auth.not-verified")
 *
 * // Get a translation with variables
 * val translation = i18nManager.translate(playerInfo, "welcome.message", mapOf("username" to "PlayerName"))
 *
 * // Get a translation using platform locale directly
 * val translation = i18nManager.getTranslation(null, "auth.not-verified")
 * ```
 *
 * @property platformManager Used to access serverSettings
 */
class I18nManager(
    private val logger: Logger
) {
    // Cached translations: Map<LocaleCode, Map<Key, Value>>
    private val translationsCache = mutableMapOf<String, Map<String, String>>()
    
    // Cached platform locale
    private var platformLocale: String = "en-US"
    
    // Handlebars instance for rendering translations with variables
    private val handlebars by lazy { Handlebars() }

    /**
     * Updates the translation cache when serverSettings change.
     * This should be called whenever serverSettings are updated.
     *
     * @param serverSettings The new server settings containing translations and locale
     */
    fun updateCache(serverSettings: GetServerSettingsMessage) {
        translationsCache.clear()
        translationsCache.putAll(serverSettings.translations)
        platformLocale = serverSettings.locale
    }
    /**
     * Gets a translation by playerInfo and key.
     * If a player-specific locale exists in playerInfo, it uses that; otherwise falls back to platform locale.
     *
     * @param playerInfo Player info message (can be null to use platform locale only)
     * @param key Translation key
     * @return The translation value or null if not found
     */
    fun getTranslation(playerInfo: GetPlayerInfoMessage?, key: String): String? {
        // Determine locale to use
        val locale = playerInfo?.locale ?: platformLocale

        // Get translations for the determined locale from cache
        val translations = translationsCache[locale]

        // Return translation if found
        return translations?.get(key)
    }

    /**
     * Gets a translation using platform locale directly (without checking player locale).
     *
     * @param key Translation key
     * @return The translation value or null if not found
     */
    fun getTranslation(key: String): String? {
        val translations = translationsCache[platformLocale]
        return translations?.get(key)
    }

    /**
     * Translates a key with variable substitution using Handlebars template engine.
     * The translation string can contain Handlebars syntax like {{variableName}}.
     *
     * @param playerInfo Player info message (can be null to use platform locale only)
     * @param key Translation key
     * @param variables Map of variables to be used in the template (default: empty map)
     * @return The rendered translation string or null if translation not found
     *
     * @example
     * ```kotlin
     * // Translation: "Welcome, {{username}}!"
     * val result = i18nManager.translate(
     *     playerInfo,
     *     "welcome.message",
     *     mapOf("username" to "John")
     * )
     * // Result: "Welcome, John!"
     * ```
     */
    fun translate(
        playerInfo: GetPlayerInfoMessage?,
        key: String,
        variables: Map<String, Any> = emptyMap()
    ): String? {
        val translationTemplate = getTranslation(playerInfo, key) ?: return null

        // If no variables provided, return the translation as-is
        if (variables.isEmpty()) {
            return translationTemplate
        }

        return try {
            val template = handlebars.compileInline(translationTemplate)
            template.apply(variables)
        } catch (e: Exception) {
            logger.warning("Failed to render translation template for key: $key - ${e.message}")
            translationTemplate // Return original template on error
        }
    }

    /**
     * Translates a key with variable substitution using platform locale.
     *
     * @param key Translation key
     * @param variables Map of variables to be used in the template (default: empty map)
     * @return The rendered translation string or null if translation not found
     */
    fun translate(
        key: String,
        variables: Map<String, Any> = emptyMap()
    ): String? {
        val translationTemplate = getTranslation(key) ?: return null

        // If no variables provided, return the translation as-is
        if (variables.isEmpty()) {
            return translationTemplate
        }

        return try {
            val template = handlebars.compileInline(translationTemplate)
            template.apply(variables)
        } catch (e: Exception) {
            logger.warning("Failed to render translation template for key: $key - ${e.message}")
            translationTemplate // Return original template on error
        }
    }
}

