package com.panomc.plugins.pano.core.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The same vectors the node daemon's `PathSafetyTest` runs, against the plugin's copy.
 *
 * Two implementations of one contract is the arrangement the server-management protocol already
 * uses for crypto and for the console; this is what stops the two sandboxes from drifting apart,
 * because Pano sends both of them the same paths (AGENT.md 2.4.17 C).
 */
class PathSafetyTest {
    private lateinit var root: File

    @BeforeTest
    fun createRoot() {
        root = Files.createTempDirectory("pano-path-safety").toFile()
    }

    @AfterTest
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun `accepts a plain uuid`() {
        assertTrue(PathSafety.isSafeSegment("6f1b1f0e-0d2a-4a3b-9c0d-1d2e3f4a5b6c"))
    }

    @Test
    fun `rejects traversal, separators and blanks`() {
        assertFalse(PathSafety.isSafeSegment(".."))
        assertFalse(PathSafety.isSafeSegment("../etc"))
        assertFalse(PathSafety.isSafeSegment("a/b"))
        assertFalse(PathSafety.isSafeSegment("a\\b"))
        assertFalse(PathSafety.isSafeSegment(""))
        assertFalse(PathSafety.isSafeSegment(null))
    }

    @Test
    fun `resolves inside the root`() {
        assertEquals(File(root, "world").canonicalPath, PathSafety.resolveUnder(root, "world").canonicalPath)
    }

    @Test
    fun `refuses to resolve a segment that climbs out`() {
        assertFailsWith<IllegalArgumentException> { PathSafety.resolveUnder(root, "../outside") }
    }

    @Test
    fun `refuses a symlink pointing outside the root`() {
        val outside = File(root.parentFile, "outside-${System.nanoTime()}")

        outside.mkdirs()

        try {
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        } catch (_: Exception) {
            // A filesystem without symlink support cannot be tricked this way either.
            return
        }

        assertFailsWith<IllegalArgumentException> { PathSafety.resolveUnder(root, "escape") }

        outside.deleteRecursively()
    }

    @Test
    fun `resolves a multi-segment path under the root`() {
        assertEquals(
            File(root, "plugins/LuckPerms/config.yml").canonicalPath,
            PathSafety.resolveRelative(root, "plugins/LuckPerms/config.yml").canonicalPath
        )
    }

    @Test
    fun `treats a blank relative path as the root itself`() {
        assertEquals(root.canonicalFile.canonicalPath, PathSafety.resolveRelative(root, "").canonicalPath)
        assertEquals(root.canonicalFile.canonicalPath, PathSafety.resolveRelative(root, null).canonicalPath)
    }

    @Test
    fun `refuses traversal anywhere in a relative path`() {
        listOf("../etc/passwd", "plugins/../../etc", "a/b/../../../c", "a/../..").forEach { path ->
            assertFailsWith<IllegalArgumentException>("$path was allowed") {
                PathSafety.resolveRelative(root, path)
            }
        }
    }

    @Test
    fun `treats a rooted path as relative rather than as an escape`() {
        // A leading slash is a common way to write "from the top of this server", and it is what
        // zip entries carry; anchoring it to the sandbox is the safe reading, not an escape.
        assertEquals(
            File(root, "etc/passwd").canonicalPath,
            PathSafety.resolveRelative(root, "/etc/passwd").canonicalPath
        )
    }

    @Test
    fun `normalises separators and dot segments`() {
        assertEquals(File(root, "a/b").canonicalPath, PathSafety.resolveRelative(root, "./a/./b").canonicalPath)
        assertEquals(File(root, "a/b").canonicalPath, PathSafety.resolveRelative(root, "a\\b").canonicalPath)
    }

    @Test
    fun `refuses a relative path that walks through a symlink out of the root`() {
        val outside = File(root.parentFile, "outside-rel-${System.nanoTime()}")

        outside.mkdirs()

        File(outside, "secret.txt").writeText("secret")

        try {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        } catch (_: Exception) {
            return
        }

        assertFailsWith<IllegalArgumentException> { PathSafety.resolveRelative(root, "link/secret.txt") }

        outside.deleteRecursively()
    }

    @Test
    fun `spots traversal anywhere in a spec`() {
        assertTrue(PathSafety.hasTraversal(listOf("paper", "../../etc/passwd")))
        assertFalse(PathSafety.hasTraversal(listOf("paper", "1.21.8", null)))
    }
}
