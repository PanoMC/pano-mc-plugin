package com.panomc.plugins.pano.core.schedule

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The node daemon's `ScheduleTimingTest` vectors, against the plugin's own cron reader.
 *
 * Two implementations of one contract: Pano validates a cron expression with `cron-utils`, the
 * node fires it with `cron-utils`, and this fires it with five sets of integers. They have to
 * agree about the minute, or a panel would promise a 04:00 backup that happens at 03:00 or not at
 * all - so the vectors are shared and the extra ones here cover the syntax a hand-written cron
 * actually uses.
 */
class ScheduleTimingTest {
    private fun at(zone: String, text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `agrees with Pano on what a valid cron is`() {
        assertTrue(CronSchedules.isValid("0 4 * * *"))
        assertFalse(CronSchedules.isValid("0 4 * *"))
        assertFalse(CronSchedules.isValid(null))
    }

    @Test
    fun `fires on the due minute regardless of where in it the tick lands`() {
        val zone = "Europe/Istanbul"

        assertTrue(CronSchedules.firesAt("30 3 * * *", zone, at(zone, "2026-06-01T03:30:00")))
        assertTrue(CronSchedules.firesAt("30 3 * * *", zone, at(zone, "2026-06-01T03:30:00") + 59_000))
        assertFalse(CronSchedules.firesAt("30 3 * * *", zone, at(zone, "2026-06-01T03:31:00")))
    }

    @Test
    fun `falls back to the local zone for an unknown one`() {
        assertEquals(ZoneId.systemDefault(), CronSchedules.zoneOf("Mars/Olympus"))
        assertEquals(ZoneId.of("UTC"), CronSchedules.zoneOf("UTC"))
    }

    @Test
    fun `warns on the same schedule Pano would`() {
        assertEquals(listOf(15, 5, 1), ScheduleWarnings.offsetsFor(15))
        assertEquals(listOf(5, 1), ScheduleWarnings.offsetsFor(5))
        assertTrue(ScheduleWarnings.offsetsFor(0).isEmpty())
        assertEquals("say Server restarts in 1 minute", ScheduleWarnings.message(1, restarting = true))
        assertEquals("say Server stops in 5 minutes", ScheduleWarnings.message(5, restarting = false))
    }

    @Test
    fun `reads the same minute in two zones as two different instants`() {
        assertTrue(CronSchedules.firesAt("0 4 * * *", "UTC", at("UTC", "2026-06-01T04:00:00")))
        assertFalse(CronSchedules.firesAt("0 4 * * *", "Europe/Istanbul", at("UTC", "2026-06-01T04:00:00")))
        assertTrue(CronSchedules.firesAt("0 4 * * *", "Europe/Istanbul", at("Europe/Istanbul", "2026-06-01T04:00:00")))
    }

    @Test
    fun `understands steps, ranges and lists`() {
        val zone = "UTC"

        assertTrue(CronSchedules.firesAt("*/15 * * * *", zone, at(zone, "2026-06-01T09:30:00")))
        assertFalse(CronSchedules.firesAt("*/15 * * * *", zone, at(zone, "2026-06-01T09:31:00")))

        assertTrue(CronSchedules.firesAt("0 9-17 * * *", zone, at(zone, "2026-06-01T17:00:00")))
        assertFalse(CronSchedules.firesAt("0 9-17 * * *", zone, at(zone, "2026-06-01T18:00:00")))

        assertTrue(CronSchedules.firesAt("0 0,12 * * *", zone, at(zone, "2026-06-01T12:00:00")))
        assertFalse(CronSchedules.firesAt("0 0,12 * * *", zone, at(zone, "2026-06-01T13:00:00")))

        assertTrue(CronSchedules.firesAt("0 0 1 */3 *", zone, at(zone, "2026-07-01T00:00:00")))
        assertFalse(CronSchedules.firesAt("0 0 1 */3 *", zone, at(zone, "2026-06-01T00:00:00")))
    }

    @Test
    fun `understands month and day names, and sunday twice over`() {
        val zone = "UTC"

        // 2026-06-01 is a Monday; 2026-06-07 is a Sunday.
        assertTrue(CronSchedules.firesAt("0 4 * * MON", zone, at(zone, "2026-06-01T04:00:00")))
        assertTrue(CronSchedules.firesAt("0 4 * JUN *", zone, at(zone, "2026-06-01T04:00:00")))
        assertTrue(CronSchedules.firesAt("0 4 * * 0", zone, at(zone, "2026-06-07T04:00:00")))
        assertTrue(CronSchedules.firesAt("0 4 * * 7", zone, at(zone, "2026-06-07T04:00:00")))
        assertFalse(CronSchedules.firesAt("0 4 * * 0", zone, at(zone, "2026-06-01T04:00:00")))
    }

    @Test
    fun `applies the vixie rule when both day fields are restricted`() {
        val zone = "UTC"

        // "Every Friday and every 13th", not "Friday the 13th": 2026-06-05 is a Friday and
        // 2026-06-13 is a Saturday, and both must fire.
        assertTrue(CronSchedules.firesAt("0 4 13 * FRI", zone, at(zone, "2026-06-05T04:00:00")))
        assertTrue(CronSchedules.firesAt("0 4 13 * FRI", zone, at(zone, "2026-06-13T04:00:00")))
        assertFalse(CronSchedules.firesAt("0 4 13 * FRI", zone, at(zone, "2026-06-14T04:00:00")))

        // With only one of them restricted, that one has to match on its own.
        assertFalse(CronSchedules.firesAt("0 4 13 * *", zone, at(zone, "2026-06-05T04:00:00")))
    }

    @Test
    fun `never fires an expression it cannot read`() {
        val zone = "UTC"

        listOf("", "  ", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "0 4 0 * *", "0 4 * 13 *", "x * * * *", "*/0 * * * *", "5-1 * * * *")
            .forEach { expression ->
                assertFalse(CronSchedules.isValid(expression), "\"$expression\" was accepted")
                assertFalse(CronSchedules.firesAt(expression, zone, at(zone, "2026-06-01T04:00:00")))
            }
    }
}
