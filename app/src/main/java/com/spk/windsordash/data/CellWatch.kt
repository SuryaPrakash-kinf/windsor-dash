package com.spk.windsordash.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which cells the BMS reports as lowest / highest, counted over time under different conditions.
 * Pure tallies of the car's own min/max cell numbers — no modelling.
 */
object CellWatch {
    enum class Cond(val label: String, val lowest: Boolean) {
        REST_LOW("Lowest cell at rest", true),
        LOAD_LOW("Lowest cell under load (>10 kW)", true),
        CHARGE_HIGH("Highest cell while charging", false),
        FULL_HIGH("Highest cell when full (≥98 %)", false),
    }

    data class Snapshot(val since: Long, val counts: Map<Cond, Map<Int, Int>>) {
        fun top(c: Cond, n: Int = 3): List<Pair<Int, Double>> {
            val m = counts[c].orEmpty()
            val total = m.values.sum().toDouble()
            if (total <= 0) return emptyList()
            return m.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value / total * 100 }
        }
        fun total(c: Cond) = counts[c].orEmpty().values.sum()
    }

    private lateinit var prefs: SharedPreferences
    private val counts = Cond.entries.associateWith { mutableMapOf<Int, Int>() }
    private var since = 0L
    private var lastSave = 0L
    private val _state = MutableStateFlow(Snapshot(0, emptyMap()))
    val state: StateFlow<Snapshot> = _state

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("cellwatch", Context.MODE_PRIVATE)
        since = prefs.getLong("since", 0)
        for (c in Cond.entries) {
            prefs.getString(c.name, "").orEmpty().split(',').filter { it.contains('=') }.forEach {
                val (k, v) = it.split('=')
                counts.getValue(c)[k.toInt()] = v.toInt()
            }
        }
        publish()
    }

    @Synchronized
    fun record(maxId: Int?, minId: Int?, kw: Double?, charging: Boolean, soc: Double?) {
        if (!::prefs.isInitialized) return
        if (since == 0L) since = System.currentTimeMillis()
        fun add(c: Cond, id: Int?) { if (id != null && id > 0) counts.getValue(c).merge(id, 1, Int::plus) }
        if (charging) add(Cond.CHARGE_HIGH, maxId)
        else if (kw != null && kotlin.math.abs(kw) < 1) add(Cond.REST_LOW, minId)
        else if (kw != null && kw > 10) add(Cond.LOAD_LOW, minId)
        if (soc != null && soc >= 98) add(Cond.FULL_HIGH, maxId)
        val now = System.currentTimeMillis()
        if (now - lastSave > 60_000) { lastSave = now; save(); publish() }
    }

    fun reset() {
        counts.values.forEach { it.clear() }
        since = System.currentTimeMillis()
        save(); publish()
    }

    private fun save() {
        prefs.edit().putLong("since", since).apply {
            for (c in Cond.entries) putString(c.name, counts.getValue(c).entries.joinToString(",") { "${it.key}=${it.value}" })
        }.apply()
    }

    private fun publish() {
        _state.value = Snapshot(since, counts.mapValues { HashMap(it.value) })
    }
}
