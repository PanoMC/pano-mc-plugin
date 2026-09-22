package com.panomc.plugins.pano.core.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reading five-field Unix cron, in a time zone, inside a game server.
 *
 * Deliberately the same rules as Pano's and the node daemon's `CronSchedules`, so a schedule saved
 * in the panel and the schedule this plugin fires cannot disagree - but written out here rather
 * than pulled in from `cron-utils`, because a plugin jar is shaded into every server that installs
 * it and a whole cron library is a lot of weight for five fields and one question. The question is
 * only ever "does this fire in the minute I am in", which needs matching, not scheduling.
 *
 * Supported, which is what `cron-utils`' UNIX definition supports and what a panel's cron field
 * offers: `*`, `a`, `a-b`, `*&#47;n`, `a-b/n`, `a/n`, comma-separated lists of any of those, month
 * and day names, and `7` as another way to write Sunday. The Vixie day rule is implemented too: a
 * cron that restricts *both* the day of the month and the day of the week fires when either
 * matches, which is the difference between "every Friday and every 13th" and "Friday the 13th".
 *
 * Nothing throws. An expression that somehow arrived unparsable simply never fires, because the
 * alternative is one bad schedule stopping the tick that runs all the others.
 */
object CronSchedules {
    fun isValid(expression: String?): Boolean = parse(expression) != null

    /** [id] as a zone, falling back to this JVM's own for anything unusable. */
    fun zoneOf(id: String?): ZoneId {
        val value = id?.trim().orEmpty()

        if (value.isEmpty()) {
            return ZoneId.systemDefault()
        }

        return try {
            ZoneId.of(value)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
    }

    /**
     * Whether [expression] fires in the minute [at] falls in.
     *
     * The minute rather than the instant, because the tick that asks this runs every sixty seconds
     * but never on the same millisecond twice.
     */
    fun firesAt(expression: String?, zoneId: String?, at: Long): Boolean {
        val cron = parse(expression) ?: return false

        val moment = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zoneOf(zoneId))

        return cron.matches(moment)
    }

    private fun parse(expression: String?): Cron? {
        val value = expression?.trim().orEmpty()

        if (value.isEmpty() || value.length > MAX_CRON_LENGTH) {
            return null
        }

        val fields = value.split(WHITESPACE)

        if (fields.size != 5) {
            return null
        }

        val minutes = field(fields[0], 0, 59) ?: return null
        val hours = field(fields[1], 0, 23) ?: return null
        val daysOfMonth = field(fields[2], 1, 31) ?: return null
        val months = field(fields[3], 1, 12, MONTH_NAMES) ?: return null
        val daysOfWeek = field(fields[4], 0, 7, DAY_NAMES)?.map { if (it == 7) 0 else it }?.toSet() ?: return null

        return Cron(
            minutes = minutes,
            hours = hours,
            daysOfMonth = daysOfMonth,
            months = months,
            daysOfWeek = daysOfWeek,
            dayOfMonthRestricted = !isUnrestricted(fields[2]),
            dayOfWeekRestricted = !isUnrestricted(fields[4])
        )
    }

    /** Whether a field says "every", which is what decides the Vixie day rule. */
    private fun isUnrestricted(field: String): Boolean = field.trim() == "*"

    /** One field as the set of values it allows, or null when it is not a field at all. */
    private fun field(raw: String, min: Int, max: Int, names: Map<String, Int> = emptyMap()): Set<Int>? {
        val values = mutableSetOf<Int>()

        raw.split(',').forEach { part ->
            val trimmed = part.trim()

            if (trimmed.isEmpty()) {
                return null
            }

            val step: Int
            val range: String

            if (trimmed.contains('/')) {
                val halves = trimmed.split('/')

                if (halves.size != 2) {
                    return null
                }

                step = halves[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                range = halves[0].trim()
            } else {
                step = 1
                range = trimmed
            }

            val from: Int
            val to: Int

            when {
                range == "*" -> {
                    from = min
                    to = max
                }

                range.contains('-') -> {
                    val bounds = range.split('-')

                    if (bounds.size != 2) {
                        return null
                    }

                    from = number(bounds[0], names) ?: return null
                    to = number(bounds[1], names) ?: return null
                }

                else -> {
                    from = number(range, names) ?: return null
                    // `5/15` means "from 5 onwards, every 15", while a plain `5` is just 5.
                    to = if (step > 1) max else from
                }
            }

            if (from < min || to > max || from > to) {
                return null
            }

            var value = from

            while (value <= to) {
                values.add(value)

                value += step
            }
        }

        return values.takeIf { it.isNotEmpty() }
    }

    private fun number(raw: String, names: Map<String, Int>): Int? {
        val trimmed = raw.trim()

        return trimmed.toIntOrNull() ?: names[trimmed.lowercase()]
    }

    /** One parsed expression, as the five sets of values it allows. */
    private data class Cron(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val daysOfMonth: Set<Int>,
        val months: Set<Int>,
        val daysOfWeek: Set<Int>,
        val dayOfMonthRestricted: Boolean,
        val dayOfWeekRestricted: Boolean
    ) {
        fun matches(moment: ZonedDateTime): Boolean {
            if (moment.minute !in minutes || moment.hour !in hours || moment.monthValue !in months) {
                return false
            }

            val dayOfMonth = moment.dayOfMonth in daysOfMonth
            // java.time counts Monday as 1 and Sunday as 7; cron counts Sunday as 0.
            val dayOfWeek = (moment.dayOfWeek.value % 7) in daysOfWeek

            return when {
                dayOfMonthRestricted && dayOfWeekRestricted -> dayOfMonth || dayOfWeek
                dayOfMonthRestricted -> dayOfMonth
                dayOfWeekRestricted -> dayOfWeek
                else -> true
            }
        }
    }

    private const val MAX_CRON_LENGTH = 100

    private val WHITESPACE = Regex("\\s+")

    private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .withIndex()
        .associate { (index, name) -> name to index + 1 }

    private val DAY_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
        .withIndex()
        .associate { (index, name) -> name to index }
}
