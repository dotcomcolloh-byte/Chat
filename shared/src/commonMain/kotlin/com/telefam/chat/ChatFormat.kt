package com.telefam.chat

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object ChatFormat {
    private fun two(n: Int) = n.toString().padStart(2, '0')

    fun time(epochMillis: Long): String {
        val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${two(t.hour)}:${two(t.minute)}"
    }

    fun dayOf(epochMillis: Long): LocalDate =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault()).date

    fun today(): LocalDate = dayOf(Clock.System.now().toEpochMilliseconds())

    /** Returns TODAY / YESTERDAY / OTHER so the UI can localize the first two via string resources. */
    fun dayKind(epochMillis: Long): DayKind {
        val day = dayOf(epochMillis)
        val today = today()
        return when {
            day == today -> DayKind.TODAY
            day.toEpochDays() == today.toEpochDays() - 1 -> DayKind.YESTERDAY
            else -> DayKind.OTHER
        }
    }

    fun numericDate(epochMillis: Long): String {
        val d = dayOf(epochMillis)
        return "${two(d.dayOfMonth)}/${two(d.monthNumber)}/${d.year}"
    }
}

enum class DayKind { TODAY, YESTERDAY, OTHER }
