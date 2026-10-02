package com.telefam.usage

import com.telefam.appcache.AppCacheQueries
import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.minus

/**
 * Screen-time tracking + enforcement for Settings → Time management.
 *
 * Usage is accumulated per local calendar day in AppCache (offline by nature —
 * usage happens on-device). The daily limit and break-reminder settings are synced
 * from the backend app-settings row; enforcement is local: when the day's budget
 * is exhausted the host shows a blocking overlay until the day rolls over.
 */
object UsageTracker {
    private var cache: AppCacheQueries? = null

    /** Minutes used today; the UI observes this to render "Today's usage" and to block. */
    private val _todayMinutes = MutableStateFlow(0)
    val todayMinutes: StateFlow<Int> = _todayMinutes

    /** Set from the synced AppSettingsDto: 0 = no limit. */
    @Volatile var dailyLimitMinutes: Int = 0
    /** 0 = off; otherwise remind every N minutes of continuous use. */
    @Volatile var breakReminderMinutes: Int = 0
    @Volatile var quietModeEnabled: Boolean = false

    /** Fires when the daily limit is reached; the host app shows the blocking overlay. */
    private val _limitReached = MutableStateFlow(false)
    val limitReached: StateFlow<Boolean> = _limitReached

    /** Fires when a break reminder is due (carries the minutes used so far). */
    private val _breakReminderDue = MutableStateFlow(0)
    val breakReminderDue: StateFlow<Int> = _breakReminderDue

    private var lastBreakReminderAtMinutes = 0

    fun init(driverFactory: DatabaseDriverFactory) {
        cache = LocalDatabase.getInstance(driverFactory).appCacheQueries
        _todayMinutes.value = loadMinutes(todayKey())
    }

    private fun todayKey(): String {
        val d = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return "usage_$d"
    }

    private fun loadMinutes(key: String): Int = runCatching {
        cache?.selectValue(key)?.executeAsOneOrNull()?.cachedValue?.toIntOrNull()
    }.getOrNull() ?: 0

    private fun store(key: String, minutes: Int) = runCatching {
        cache?.upsertValue(key, minutes.toString(), Clock.System.now().toEpochMilliseconds())
    }

    /** Called by the host roughly every minute while the app is in the foreground. */
    fun tickMinute() {
        val key = todayKey()
        val newTotal = loadMinutes(key) + 1
        store(key, newTotal)
        _todayMinutes.value = newTotal
        if (dailyLimitMinutes > 0 && newTotal >= dailyLimitMinutes) {
            _limitReached.value = true
        }
        if (breakReminderMinutes > 0 && newTotal - lastBreakReminderAtMinutes >= breakReminderMinutes) {
            lastBreakReminderAtMinutes = newTotal
            _breakReminderDue.value = newTotal
        }
    }

    /** Snooze the blocking overlay for [minutes] more minutes. */
    fun snoozeLimit(minutes: Int) {
        dailyLimitMinutes = _todayMinutes.value + minutes
        _limitReached.value = false
    }

    fun acknowledgeBreakReminder() {
        _breakReminderDue.value = 0
    }

    /** Minutes used on each of the last 7 days (oldest first) — for the weekly view. */
    fun weeklyMinutes(): List<Pair<String, Int>> {
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        return (6 downTo 0).map { back ->
            val day = kotlinx.datetime.DatePeriod(days = back).let { today.minus(it) }
            "usage_$day" to loadMinutes("usage_$day")
        }.map { (key, mins) -> key.removePrefix("usage_") to mins }
    }

    /** Apply synced server settings. */
    fun applySettings(dailyLimit: Int, breakReminder: Int, quietMode: Boolean) {
        dailyLimitMinutes = dailyLimit
        breakReminderMinutes = breakReminder
        quietModeEnabled = quietMode
        if (dailyLimitMinutes > 0 && _todayMinutes.value >= dailyLimitMinutes) _limitReached.value = true
    }
}
