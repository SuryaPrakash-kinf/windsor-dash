package com.spk.windsordash.data

import android.content.SharedPreferences

/**
 * Simple, periodic tips for the Windsor's LFP battery, which wants different habits from phones and
 * older EVs: a full charge about once a week keeps its % reading accurate (LFP voltage barely moves in
 * the middle, so the car needs to see "full" now and then), 80-90% is fine day to day, and it shouldn't
 * sit very low, or fully charged and idle, for days.
 *
 * Call [record] on every live sample to track state (cheap, just a few SharedPreferences writes when
 * something changes), and [tip] + [maybeNotify] whenever you want to check — each kind of tip is
 * posted at most once a day, so checking on every sample is fine.
 *
 * Uses the same `prefs` ("dash") SharedPreferences instance as the rest of Dash, under a "bc_" prefix.
 */
object BatteryCare {
    data class Tip(val key: String, val title: String, val text: String)

    private const val DAY = 86_400_000L
    const val FULL_EVERY_DAYS = 7
    private const val FULL_SITTING_DAYS = 2

    /** Remembers when the battery was last full, and since when it has been low or sitting full. */
    fun record(prefs: SharedPreferences, socPct: Double?, charging: Boolean, now: Long) {
        if (socPct == null) return
        val e = prefs.edit()
        if (!prefs.contains("bc_watching_since")) e.putLong("bc_watching_since", now)
        if (socPct >= 99) e.putLong("bc_last_full", now)
        if (socPct < 20 && !charging) {
            if (!prefs.contains("bc_low_since")) e.putLong("bc_low_since", now)
        } else {
            e.remove("bc_low_since")
        }
        if (socPct >= 98 && !charging) {
            if (!prefs.contains("bc_full_since")) e.putLong("bc_full_since", now)
        } else {
            e.remove("bc_full_since")
        }
        e.apply()
    }

    /** The one tip worth showing now, or null. */
    fun tip(prefs: SharedPreferences, socPct: Double?, now: Long): Tip? {
        if (socPct == null) return null

        prefs.getLong("bc_low_since", 0).takeIf { it > 0 && now - it > DAY }?.let {
            return Tip(
                "low", "Charge soon",
                "It's been under 20% for over a day. Plug in when you can — LFP batteries don't like sitting low."
            )
        }
        prefs.getLong("bc_full_since", 0).takeIf { it > 0 && now - it > FULL_SITTING_DAYS * DAY }?.let {
            val days = (now - it) / DAY
            return Tip(
                "full", "Sitting full for a while",
                "It's been at 100% for $days day${if (days == 1L) "" else "s"}. Fine now and then, but if you're not driving soon, charge to 90% next time instead."
            )
        }
        val lastFull = prefs.getLong("bc_last_full", 0)
        val since = maxOf(lastFull, prefs.getLong("bc_watching_since", now))
        if (now - since > FULL_EVERY_DAYS * DAY) {
            val ago = if (lastFull > 0) " Last full: ${(now - lastFull) / DAY} days ago." else ""
            return Tip(
                "weekly_full", "Time for a full charge",
                "Charge to 100% once this week so the SoC reading stays accurate.$ago"
            )
        }
        return null
    }

    /** Sends the current tip as a notification, each kind at most once a day. */
    fun maybeNotify(prefs: SharedPreferences, tip: Tip?, now: Long) {
        if (tip == null) return
        val day = now / DAY
        val key = "bc_told_${tip.key}"
        if (prefs.getLong(key, -1) == day) return
        prefs.edit().putLong(key, day).apply()
        Alerts.batteryCareTip(tip.title, tip.text)
    }
}
