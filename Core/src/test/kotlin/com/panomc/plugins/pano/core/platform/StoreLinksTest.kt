package com.panomc.plugins.pano.core.platform

import io.vertx.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The store links of a `MARKET_CONFIG` answer, with and without the two additive fields. */
class StoreLinksTest {
    private val full = JsonObject(
        """{"accepted":true,"storeUrl":"https://example.com/","productUrlTemplate":"https://shop.example.com/p/{slug}",""" +
            """"registerUrl":"https://example.com/join"}"""
    )

    private val old = JsonObject("""{"accepted":true,"storeUrl":"https://example.com/"}""")

    @Test
    fun `a payload with both fields uses them`() {
        val links = StoreLinks.fromPayload(full)

        assertEquals("https://shop.example.com/p/diamond-rank", links.productUrl("diamond-rank"))
        assertEquals("https://example.com/join", links.registerUrl())
    }

    @Test
    fun `a payload without them appends to the store url like before`() {
        val links = StoreLinks.fromPayload(old)

        assertEquals("https://example.com/store/diamond-rank", links.productUrl("diamond-rank"))
        assertEquals("https://example.com/store", links.productUrl(null))
        assertEquals("https://example.com/register", links.registerUrl())
    }

    @Test
    fun `each field falls back on its own`() {
        val onlyRegister = StoreLinks.fromPayload(JsonObject("""{"storeUrl":"https://example.com","registerUrl":"https://example.com/join"}"""))

        assertEquals("https://example.com/store/a", onlyRegister.productUrl("a"))
        assertEquals("https://example.com/join", onlyRegister.registerUrl())

        val onlyTemplate = StoreLinks.fromPayload(JsonObject("""{"storeUrl":"https://example.com","productUrlTemplate":"https://example.com/p/{slug}"}"""))

        assertEquals("https://example.com/p/a", onlyTemplate.productUrl("a"))
        assertEquals("https://example.com/register", onlyTemplate.registerUrl())
    }

    @Test
    fun `an unusable slug or field is ignored`() {
        val links = StoreLinks.fromPayload(full)

        // No slug, or one with a path in it: the store itself, never a made-up address.
        assertEquals("https://example.com/store", links.productUrl(null))
        assertEquals("https://example.com/store", links.productUrl("../admin"))

        // A template without the placeholder, a non-web scheme and a wrong type all count as absent.
        val bad = StoreLinks.fromPayload(
            JsonObject("""{"storeUrl":"https://example.com","productUrlTemplate":"https://example.com/p","registerUrl":"javascript:alert(1)"}""")
        )

        assertEquals("https://example.com/store/a", bad.productUrl("a"))
        assertEquals("https://example.com/register", bad.registerUrl())

        val typed = StoreLinks.fromPayload(JsonObject("""{"storeUrl":"https://example.com","registerUrl":42}"""))

        assertEquals("https://example.com/register", typed.registerUrl())
    }

    @Test
    fun `without any address there is no link`() {
        val links = StoreLinks.fromPayload(JsonObject("""{"accepted":false}"""))

        assertNull(links.productUrl("a"))
        assertNull(links.registerUrl())

        // Pano's own register address needs no store url.
        assertEquals("https://example.com/join", StoreLinks(null, null, "https://example.com/join").registerUrl())
    }
}
