package com.panomc.plugins.pano.core.platform

import io.vertx.core.json.JsonObject
import java.net.URI

/**
 * The store links of a `MARKET_CONFIG` answer.
 *
 * Pano's front-end URL map decides where the store, a product and the register page live, so the
 * payload carries two optional, additive fields next to `storeUrl`:
 *
 * - `productUrlTemplate` - an absolute URL with a `{slug}` placeholder (`https://shop.example/p/{slug}`);
 * - `registerUrl` - the absolute register page.
 *
 * A Pano that sends neither (older market builds) keeps working: the links are then built by
 * appending `/store/<slug>` and `/register` to `storeUrl`, exactly as before.
 */
class StoreLinks(
    storeUrl: String?,
    productUrlTemplate: String? = null,
    registerUrl: String? = null
) {
    private val base: String? = storeUrl?.trim()?.takeIf { isWebUrl(it) }?.trimEnd('/')

    private val template: String? = productUrlTemplate?.trim()?.takeIf { isWebUrl(it) && it.contains(SLUG) }

    private val register: String? = registerUrl?.trim()?.takeIf { isWebUrl(it) }

    /**
     * The product page for [slug]; the store itself when there is no usable slug. `null` when Pano
     * sent no address to build anything from.
     */
    fun productUrl(slug: String?): String? {
        val safe = slug?.takeIf(::isSafeSlug)

        if (safe != null) {
            template?.let { return it.replace(SLUG, safe) }

            return base?.let { "$it/store/$safe" }
        }

        return base?.let { "$it/store" }
    }

    /** The register page: Pano's own address when it sent one, `storeUrl/register` otherwise. */
    fun registerUrl(): String? = register ?: base?.let { "$it/register" }

    companion object {
        const val SLUG = "{slug}"

        /** Reads the three optional fields of a `MARKET_CONFIG` object; unknown fields and wrong types are ignored. */
        fun fromPayload(payload: JsonObject): StoreLinks =
            StoreLinks(
                storeUrl = payload.stringOrNull("storeUrl"),
                productUrlTemplate = payload.stringOrNull("productUrlTemplate"),
                registerUrl = payload.stringOrNull("registerUrl")
            )

        private fun JsonObject.stringOrNull(key: String): String? = getValue(key) as? String

        private fun isSafeSlug(slug: String) =
            slug.isNotBlank() && slug.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

        private fun isWebUrl(value: String): Boolean =
            try {
                val uri = URI(value.replace(SLUG, "x"))

                (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
            } catch (_: Exception) {
                false
            }
    }
}
