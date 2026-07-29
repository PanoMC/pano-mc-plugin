package com.panomc.plugins.pano.core.i18n

import com.github.jknack.handlebars.EscapingStrategy
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
    companion object {
        // Last-resort fallback locale when neither the requested locale nor the platform
        // locale has the key (e.g. a freshly added custom locale). Never fall through to null.
        private const val DEFAULT_LOCALE = "en-US"
    }

    // Cached translations: Map<LocaleCode, Map<Key, Value>>.
    // @Volatile + replaced by reference in updateCache (never clear()+putAll()) so a reader on
    // another thread always observes either the old map or the fully-populated new one, never
    // a partially cleared one (cross-cutting-13).
    @Volatile
    private var translationsCache: Map<String, Map<String, String>> = emptyMap()

    // Cached platform locale
    @Volatile
    var platformLocale: String = DEFAULT_LOCALE
        private set

    // Handlebars instance for rendering translations with variables.
    // EscapingStrategy.NOOP: output goes to Minecraft chat/kick screens, never HTML, so the
    // default HTML-entity escaping only corrupts free-form values (e.g. ban reasons) and the
    // escaped entities can then be misread as legacy colour codes downstream (core-misc-9).
    private val handlebars by lazy { Handlebars().with(EscapingStrategy.NOOP) }

    /**
     * Updates the translation cache when serverSettings change.
     * This should be called whenever serverSettings are updated.
     *
     * @param serverSettings The new server settings containing translations and locale
     */
    fun updateCache(serverSettings: GetServerSettingsMessage) {
        // Build the replacement map and assign it in one step instead of clear()+putAll() so
        // concurrent readers never observe an empty/partial map (cross-cutting-13).
        translationsCache = serverSettings.translations.toMap()
        platformLocale = serverSettings.locale
    }
    /**
     * Gets a translation by playerInfo and key.
     * If a player-specific locale exists in playerInfo, it uses that; otherwise falls back to platform locale.
     * Never returns null: cascades requested locale -> platform locale -> "en-US" -> the key
     * itself, so callers can never NPE on a missing translation (core-misc-5).
     *
     * @param playerInfo Player info message (can be null to use platform locale only)
     * @param key Translation key
     * @return The translation value, or a fallback per the cascade above
     */
    fun getTranslation(playerInfo: GetPlayerInfoMessage?, key: String): String {
        // Determine locale to use
        val locale = playerInfo?.locale ?: platformLocale

        return resolveTranslation(locale, key)
    }

    /**
     * Gets a translation using the given locale directly (without checking player locale).
     * Same never-null fallback cascade as the playerInfo overload.
     *
     * @param key Translation key
     * @return The translation value, or a fallback per the cascade above
     */
    fun getTranslation(locale: String, key: String): String {
        return resolveTranslation(locale, key)
    }

    // requested locale -> platformLocale -> DEFAULT_LOCALE -> key itself. Never null (core-misc-5):
    // a missing translation must never abort a ban kick or command reply via a call-site `!!`.
    private fun resolveTranslation(locale: String, key: String): String {
        translationsCache[locale]?.get(key)?.let { return it }

        if (locale != platformLocale) {
            translationsCache[platformLocale]?.get(key)?.let { return it }
        }

        if (locale != DEFAULT_LOCALE && platformLocale != DEFAULT_LOCALE) {
            translationsCache[DEFAULT_LOCALE]?.get(key)?.let { return it }
        }

        return key
    }

    /**
     * Translates a key with variable substitution using Handlebars template engine.
     * The translation string can contain Handlebars syntax like {{variableName}}.
     *
     * @param playerInfo Player info message (can be null to use platform locale only)
     * @param key Translation key
     * @param variables Map of variables to be used in the template (default: empty map)
     * @return The rendered translation string (never null; falls back to the key itself if no
     *   translation is found)
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
        variables: Map<String, Any?> = emptyMap()
    ): String {
        val translationTemplate = getTranslation(playerInfo, key)

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
     * @return The rendered translation string (never null; falls back to the key itself if no
     *   translation is found)
     */
    fun translate(
        locale: String?,
        key: String,
        variables: Map<String, Any?> = emptyMap()
    ): String {
        val translationTemplate = getTranslation(locale ?: platformLocale, key)

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

