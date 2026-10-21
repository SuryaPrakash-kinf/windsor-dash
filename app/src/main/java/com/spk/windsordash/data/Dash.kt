package com.spk.windsordash.data

import android.content.Context
import android.content.SharedPreferences
import com.spk.windsordash.obd.AdapterRef
import com.spk.windsordash.obd.Sample
import com.spk.windsordash.obd.WindsorProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LinkStatus { IDLE, CONNECTING, LIVE, RETRYING, DEMO }

data class Trip(
    val startedAt: Long,
    val startOdoKm: Int?,
    val km: Int = 0,
    val netWh: Double = 0.0,       // energy used minus regen
    val regenWh: Double = 0.0,
) {
    val whPerKm: Double? get() = if (km >= 1) netWh / km else null
    val kwhPer100: Double? get() = whPerKm?.let { it / 10 }
}

data class ChargeSession(
    val startedAt: Long,
    val startSoc: Double?,
    val addedKwh: Double = 0.0,
    val peakKw: Double = 0.0,
    val endedAt: Long? = null,
    val endSoc: Double? = null,
) {
    val active get() = endedAt == null
}

data class DashState(
    val status: LinkStatus = LinkStatus.IDLE,
    val statusText: String = "Not connected",
    val adapter: AdapterRef? = null,
    val sample: Sample = Sample(at = 0),
    val powerKw: Double? = null,      // + discharging, − charging
    val charging: Boolean = false,
    val rangeKm: Int? = null,
    val effWhKm: Double? = null,      // the figure range is based on
    val effSource: String = "default",
    val trip: Trip? = null,
    val charge: ChargeSession? = null,
    val targetSoc: Int = 100,
) {
    val hasData get() = sample.at > 0 && (status == LinkStatus.LIVE || status == LinkStatus.DEMO)
    val cellDeltaMv get() = sample.maxCellMv?.let { mx -> sample.minCellMv?.let { mx - it } }

    /** Minutes until [targetSoc] at the current charge rate. */
    val minutesToTarget: Int?
        get() {
            val soc = sample.socPct ?: return null
            val kw = powerKw?.let { -it } ?: return null
            if (!charging || kw < 0.5 || soc >= targetSoc) return null
            val usable = WindsorProfile.RATED_KWH * ((sample.sohPct ?: 100.0) / 100)
            val kwh = (targetSoc - soc) / 100 * usable
            // LFP tapers above ~90 %: add a crude 30 % slowdown for that slice.
            val taper = if (targetSoc > 90) ((targetSoc - maxOf(soc, 90.0)) / 100 * usable) * 0.3 else 0.0
            return (((kwh + taper) / kw) * 60).toInt()
        }
}

/** Single source of truth shared by the phone UI, the service and Android Auto. */
object Dash {
    private const val DEFAULT_WH_KM = 130.0
    private val _state = MutableStateFlow(DashState())
    val state: StateFlow<DashState> = _state.asStateFlow()

    private lateinit var prefs: SharedPreferences
    private var lastAt = 0L
    private var negSince = 0L
    private var posSince = 0L
    private var lastOdo: Int? = null
    private var whSinceOdo = 0.0

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("dash", Context.MODE_PRIVATE)
        _state.update {
            it.copy(
                adapter = AdapterRef.decode(prefs.getString("adapter", null)),
                targetSoc = prefs.getInt("target", 100),
            )
        }
    }

    var savedAdapter: AdapterRef?
        get() = AdapterRef.decode(prefs.getString("adapter", null))
        set(v) {
            prefs.edit().putString("adapter", v?.encode()).apply()
            _state.update { it.copy(adapter = v) }
        }

    fun setTarget(pct: Int) {
        prefs.edit().putInt("target", pct).apply()
        _state.update { it.copy(targetSoc = pct) }
    }

    fun setStatus(s: LinkStatus, text: String) = _state.update { it.copy(status = s, statusText = text) }

    fun resetTrip() = _state.update {
        it.copy(trip = Trip(System.currentTimeMillis(), it.sample.odometerKm))
    }

    /** Lifetime average used for range when the current trip is too short. */
    private fun lifetimeWhKm(): Double? {
        val km = prefs.getFloat("lt_km", 0f)
        val wh = prefs.getFloat("lt_wh", 0f)
        return if (km >= 20f) (wh / km).toDouble() else null
    }

    fun onSample(s: Sample) {
        val prev = _state.value
        val now = s.at
        val dtH = if (lastAt == 0L) 0.0 else ((now - lastAt).coerceIn(0, 5000)) / 3_600_000.0
        lastAt = now

        val kw = if (s.packV != null && s.currentA != null) s.packV * s.currentA / 1000 else null

        // Charging = current flowing in for 20 s straight (regen bursts are shorter).
        var charging = prev.charging
        if (kw != null) {
            if (kw < -0.3) { if (negSince == 0L) negSince = now; posSince = 0 }
            else if (kw > 0.3) { if (posSince == 0L) posSince = now; negSince = 0 }
            if (!charging && negSince > 0 && now - negSince > 20_000) charging = true
            if (charging && posSince > 0 && now - posSince > 10_000) charging = false
        }

        // Trip
        var trip = prev.trip ?: Trip(now, s.odometerKm)
        if (trip.startOdoKm == null && s.odometerKm != null) trip = trip.copy(startOdoKm = s.odometerKm)
        if (kw != null && !charging) {
            val wh = kw * 1000 * dtH
            trip = trip.copy(netWh = trip.netWh + wh, regenWh = trip.regenWh + if (wh < 0) -wh else 0.0)
            whSinceOdo += wh
        }
        val odoNow = s.odometerKm
        val odoStart = trip.startOdoKm
        if (odoNow != null && odoStart != null) trip = trip.copy(km = odoNow - odoStart)

        // Lifetime efficiency, updated whenever the odometer ticks
        if (s.odometerKm != null) {
            val lo = lastOdo
            if (lo != null && s.odometerKm > lo && s.odometerKm - lo < 50 && whSinceOdo > 0) {
                prefs.edit()
                    .putFloat("lt_km", prefs.getFloat("lt_km", 0f) + (s.odometerKm - lo))
                    .putFloat("lt_wh", prefs.getFloat("lt_wh", 0f) + whSinceOdo.toFloat())
                    .apply()
            }
            if (lo == null || s.odometerKm != lo) whSinceOdo = 0.0
            lastOdo = s.odometerKm
        }

        // Charge session
        val old = prev.charge
        val charge: ChargeSession? = if (charging) {
            val base = if (old == null || !old.active) ChargeSession(now, s.socPct) else old
            if (kw != null) base.copy(addedKwh = base.addedKwh + (-kw) * dtH, peakKw = maxOf(base.peakKw, -kw)) else base
        } else if (old != null && old.active) {
            old.copy(endedAt = now, endSoc = s.socPct)
        } else old

        // Range
        val tripEff = trip.whPerKm
        val lifeEff = lifetimeWhKm()
        val (eff, src) = when {
            trip.km >= 5 && tripEff != null && tripEff > 50 -> tripEff to "this trip"
            lifeEff != null -> lifeEff to "your average"
            else -> DEFAULT_WH_KM to "typical"
        }
        val energy = s.energyLeftKwh
            ?: s.socPct?.let { it / 100 * WindsorProfile.RATED_KWH * ((s.sohPct ?: 100.0) / 100) }
        val range = energy?.let { (it * 1000 / eff).toInt() }

        _state.value = prev.copy(
            sample = s, powerKw = kw, charging = charging, trip = trip, charge = charge,
            rangeKm = range, effWhKm = eff, effSource = src,
        )
    }

    fun onDisconnected() {
        lastAt = 0; negSince = 0; posSince = 0
        _state.update { st ->
            val c = st.charge
            st.copy(charge = if (c != null && c.active) c.copy(endedAt = System.currentTimeMillis()) else c, charging = false)
        }
    }
}
