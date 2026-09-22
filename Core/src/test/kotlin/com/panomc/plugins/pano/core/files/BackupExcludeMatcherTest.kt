package com.panomc.plugins.pano.core.files

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The node daemon's `BackupExcludeMatcherTest` vectors, plus the rule only a plugin needs: its
 * backups live inside the directory it is backing up.
 */
class BackupExcludeMatcherTest {
    @Test
    fun `excludes the default noise`() {
        val matcher = BackupExcludeMatcher(null)

        assertTrue(matcher.matches("logs"))
        assertTrue(matcher.matches("logs/latest.log"))
        assertTrue(matcher.matches("cache/mojang_1.21.8.jar"))
        assertTrue(matcher.matches("paper-1.21.8.jar.tmp"))
        assertTrue(matcher.matches("plugins/update/x.jar.tmp"))
    }

    @Test
    fun `never lets a backup swallow the other backups`() {
        val matcher = BackupExcludeMatcher(null)

        assertTrue(matcher.matches("backups"))
        assertTrue(matcher.matches("backups/1a2b.zip"))
    }

    @Test
    fun `keeps everything worth restoring`() {
        val matcher = BackupExcludeMatcher(null)

        assertFalse(matcher.matches("world/level.dat"))
        assertFalse(matcher.matches("server.properties"))
        assertFalse(matcher.matches("plugins/LuckPerms/config.yml"))
        assertFalse(matcher.matches("server.jar"))
        // A directory pattern must not match something that merely starts with the same letters.
        assertFalse(matcher.matches("logsomething/a.txt"))
    }

    @Test
    fun `an empty list means the defaults, not "exclude nothing"`() {
        assertTrue(BackupExcludeMatcher(emptyList()).matches("logs/latest.log"))
    }

    @Test
    fun `takes an exact relative path`() {
        val matcher = BackupExcludeMatcher(listOf("world_nether"))

        assertTrue(matcher.matches("world_nether"))
        assertFalse(matcher.matches("world_nether/level.dat"))
        assertFalse(matcher.matches("a/world_nether"))
    }

    @Test
    fun `globs only the file name`() {
        val matcher = BackupExcludeMatcher(listOf("*.log"))

        assertTrue(matcher.matches("logs/latest.log"))
        assertTrue(matcher.matches("latest.log"))
        assertFalse(matcher.matches("latest.log.gz"))
    }

    @Test
    fun `never matches the server directory itself`() {
        assertFalse(BackupExcludeMatcher(listOf("logs/")).matches(""))
    }
}
