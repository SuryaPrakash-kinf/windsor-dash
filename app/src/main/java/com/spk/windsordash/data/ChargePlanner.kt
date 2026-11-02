package com.spk.windsordash.data

import android.content.Context
import android.content.SharedPreferences
import com.spk.windsordash.obd.WindsorProfile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * DC charging speed by SoC, in 5 % bands (0–5, 5–10 … 95–100).
 * Learned from your own DC sessions (live power while charging above 15 kW); bands without
 * enough of your data use Windsor defaults. Every figure that uses a default is marked so.
 */
object ChargeCurve {
    private lateinit var prefs: SharedPreferences
    private val sum = DoubleArray(20)
    private val n = IntArray(20)
    private const val MIN_SAMPLES = 20

    /** Windsor 52.9 kWh LFP on a 60 kW DC charger: flat to ~80 %, then tapering. Starting point only. */
    private val DEFAULT_KW = DoubleArray(20) { b ->
        when {
            b < 16 -> 55.0      // to 80 %
            b == 16 -> 40.0     // 80–85
            b == 17 -> 32.0     // 85–90
            b == 18 -> 22.0     // 90–95
            else -> 10.0        // 95–100
        }
    }

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("charge_curve", Context.MODE_PRIVATE)
        for (b in 0 until 20) { sum[b] = prefs.getFloat("s$b", 0f).toDouble(); n[b] = prefs.getInt("n$b", 0) }
    }

    /** Called with every live reading while DC charging. */
    fun record(socPct: Double?, kw: Double) {
        if (!::prefs.isInitialized || socPct == null || kw < 15 || kw > 200) return
        val b = band(socPct)
        sum[b] += kw; n[b]++
        if (n[b] % 10 == 0) prefs.edit().putFloat("s$b", sum[b].toFloat()).putInt("n$b", n[b]).apply()
    }

    private fun band(soc: Double) = (soc / 5).toInt().coerceIn(0, 19)
    fun learned(b: Int) = n[b] >= MIN_SAMPLES
    fun kw(b: Int): Double = if (learned(b)) sum[b] / n[b] else DEFAULT_KW[b]
    val learnedBands get() = (0 until 20).count { learned(it) }

    /** Minutes to charge from [from] to [to] % with [usableKwh] in the pack; also whether any default band was used. */
    fun minutes(from: Double, to: Double, usableKwh: Double): Pair<Double, Boolean> {
        var t = 0.0; var usedDefault = false
        var s = from
        while (s < to - 1e-6) {
            val b = band(s)
            val bandEnd = min(to, (b + 1) * 5.0)
            val kwh = (bandEnd - s) / 100 * usableKwh
            t += kwh / kw(b) * 60
            if (!learned(b)) usedDefault = true
            s = bandEnd
        }
        return t to usedDefault
    }
}

/** One way of covering the distance. */
data class PlanOption(
    val title: String,
    val stops: List<PlanStop>,
    val chargeMin: Double,
    val stopMin: Double,       // parking / plugging overhead
    val arrivalPct: Double,
    val usesDefaultCurve: Boolean,
    val note: String? = null,
) {
    val totalMin get() = chargeMin + stopMin
}

data class PlanStop(val atKm: Double, val fromPct: Double, val toPct: Double, val minutes: Double)

data class PlanInput(
    val km: Double,             // distance still to go
    val socPct: Double,         // now
    val whPerKm: Double,
    val reservePct: Double = 10.0,     // wanted on arrival
    val minAtChargerPct: Double = 15.0,// lowest you're happy to arrive at a charger with
    val stopOverheadMin: Double = 10.0,// pulling in, plugging, paying
    val atChargerNow: Boolean = false, // first stop is right here
    val sohPct: Double? = null,
)

/**
 * Plans charging stops. Distances and SoC come from your inputs and the car; energy per km is
 * your figure (trip or recent driving); charge speed is your DC curve. Stops are placed where
 * SoC would reach [PlanInput.minAtChargerPct] — real chargers won't be exactly there, so treat
 * the km as "by about here".
 */
object ChargePlanner {
    fun plan(p: PlanInput): List<PlanOption> {
        val usable = WindsorProfile.RATED_KWH * ((p.sohPct ?: 100.0) / 100)
        val pctPerKm = p.whPerKm / 1000 / usable * 100
        val need = p.km * pctPerKm
        val out = mutableListOf<PlanOption>()

        val direct = p.socPct - need
        if (direct >= p.reservePct) {
            out += PlanOption("No stop", emptyList(), 0.0, 0.0, direct, false,
                note = "You'll arrive with about ${direct.toInt()} %.")
            return out
        }
        val add = need + p.reservePct - p.socPct     // % to put in, in total
        for (k in 1..3) {
            val each = add / k
            val stops = mutableListOf<PlanStop>()
            var km = 0.0
            var soc = p.socPct
            var mins = 0.0
            var usedDefault = false
            var ok = true
            for (i in 0 until k) {
                val from = if (i == 0 && p.atChargerNow) soc else p.minAtChargerPct
                if (!(i == 0 && p.atChargerNow)) {
                    if (soc < p.minAtChargerPct) { ok = false; break }       // can't reach the first stop
                    km += (soc - p.minAtChargerPct) / pctPerKm
                }
                val to = from + each
                if (to > 100.0 + 1e-6) { ok = false; break }
                val (m, d) = ChargeCurve.minutes(from, to, usable)
                mins += m; usedDefault = usedDefault || d
                stops += PlanStop(km, from, to, m)
                soc = to
            }
            if (!ok) continue
            val arrival = soc - (p.km - km) * pctPerKm
            out += PlanOption(
                title = if (k == 1) "1 stop" else "$k stops",
                stops = stops, chargeMin = mins, stopMin = k * p.stopOverheadMin,
                arrivalPct = arrival, usesDefaultCurve = usedDefault,
            )
        }
        // The "fill it up" alternative people often do: one stop to 95 %, regardless of need.
        if (p.atChargerNow && p.socPct < 95) {
            val (m, d) = ChargeCurve.minutes(p.socPct, 95.0, usable)
            val arrival = 95.0 - need
            if (arrival >= 0) out += PlanOption(
                "Fill to 95 % now", listOf(PlanStop(0.0, p.socPct, 95.0, m)), m, p.stopOverheadMin, arrival, d,
                note = "Arrives with more than you need; the last % above 80 charge slowly.",
            )
        }
        return out.sortedBy { it.totalMin }
    }

    /** Rounded minutes for display. */
    fun mins(m: Double) = ceil(max(0.0, m)).toInt()
}
