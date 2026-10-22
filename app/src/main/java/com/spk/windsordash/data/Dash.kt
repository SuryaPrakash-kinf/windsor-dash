package com.spk.windsordash.data

import android.content.Context
import android.content.SharedPreferences
import com.spk.windsordash.obd.AdapterRef
import com.spk.windsordash.obd.OdoSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.spk.windsordash.obd.Sample
import com.spk.windsordash.obd.WindsorProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LinkStatus { IDLE, CONNECTING, LIVE, RETRYING, DEMO }

/** CAR = read from the car just now; GPS = last known value + GPS distance since. */
enum class OdoSource { CAR, GPS, NONE }

data class Trip(
    val startedAt: Long,
    val startOdoKm: Int?,
    val odoKm: Int = 0,            // from the car odometer (1 km steps)
    val gpsKm: Double = 0.0,       // from phone GPS
    val netWh: Double = 0.0,       // energy used minus regen
    val regenWh: Double = 0.0,
    val movingMs: Long = 0,        // time actually moving (stops excluded)
) {
    /** GPS when we have it (finer), else the car's odometer. */
    val km: Double get() = if (gpsKm >= 0.2) gpsKm else odoKm.toDouble()
    val distSource: String get() = if (gpsKm >= 0.2) "GPS" else if (odoKm > 0) "odometer" else "—"
    val whPerKm: Double? get() = if (km >= 1) netWh / km else null
    val kwhPer100: Double? get() = whPerKm?.let { it / 10 }
    /** Average while moving — what most trip computers show. */
    val avgMovingKmh: Double? get() = if (km >= 0.5 && movingMs >= 60_000) km / (movingMs / 3_600_000.0) else null
    /** Average including stops, since the trip started. */
    fun avgOverallKmh(now: Long = System.currentTimeMillis()): Double? {
        val h = (now - startedAt) / 3_600_000.0
        return if (km >= 0.5 && h > 0) km / h else null
    }
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
    val odoKm: Double? = null,
    val odoSource: OdoSource = OdoSource.NONE,
    val odoNote: String = "",
    val odoSpec: OdoSpec? = null,
    val gpsOn: Boolean = false,
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
    private var lastRealCharge: ChargeSession? = null
    private var lastOdo: Int? = null
    private var whSinceOdo = 0.0
    private var lastGpsAt = 0L
    private var gpsUnsaved = 0.0
    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("dash", Context.MODE_PRIVATE)
        LocalLog.init(ctx.applicationContext)
        _state.update {
            it.copy(
                adapter = AdapterRef.decode(prefs.getString("adapter", null)),
                targetSoc = prefs.getInt("target", 100),
                odoSpec = OdoSpec.decodeSpec(prefs.getString("odo_spec", null)),
            )
        }
        refreshOdo(null)
    }

    /* ---------------- odometer ---------------- */

    private fun odoBase() = prefs.getInt("odo_base", 0)
    private fun odoGpsKm() = prefs.getFloat("odo_gps", 0f).toDouble() + gpsUnsaved

    /** Recomputes the shown odometer. A fresh car reading always wins and re-anchors the GPS count. */
    private fun refreshOdo(carKm: Int?) {
        if (carKm != null) {
            if (carKm != odoBase() || prefs.getFloat("odo_gps", 0f) != 0f || prefs.getString("odo_base_src", "") != "car") {
                prefs.edit().putInt("odo_base", carKm).putLong("odo_base_at", System.currentTimeMillis())
                    .putString("odo_base_src", "car").putFloat("odo_gps", 0f).apply()
                gpsUnsaved = 0.0
            }
            _state.update { it.copy(odoKm = carKm.toDouble(), odoSource = OdoSource.CAR, odoNote = "read from the car") }
            return
        }
        val base = odoBase()
        if (base <= 0) {
            _state.update { it.copy(odoKm = null, odoSource = OdoSource.NONE, odoNote = "not set — enter your dashboard reading") }
            return
        }
        val at = dateFmt.format(Date(prefs.getLong("odo_base_at", 0)))
        val who = if (prefs.getString("odo_base_src", "") == "car") "car reading" else "you set it"
        val gps = odoGpsKm()
        _state.update {
            it.copy(
                odoKm = base + gps, odoSource = OdoSource.GPS,
                odoNote = "$base km ($who, $at) + %.1f km by GPS".format(gps),
            )
        }
    }

    /** Owner correction from the dashboard reading. */
    @Synchronized
    fun setOdometer(km: Int) {
        prefs.edit().putInt("odo_base", km).putLong("odo_base_at", System.currentTimeMillis())
            .putString("odo_base_src", "owner").putFloat("odo_gps", 0f).apply()
        gpsUnsaved = 0.0
        LocalLog.event("odometer set by owner: $km km")
        refreshOdo(null)
    }

    fun saveOdoSpec(spec: OdoSpec?) {
        prefs.edit().putString("odo_spec", spec?.encode()).apply()
        _state.update { it.copy(odoSpec = spec) }
        LocalLog.event(if (spec == null) "odometer location cleared" else "odometer location saved: ${spec.describe()}")
    }

    fun setGpsOn(on: Boolean) = _state.update { it.copy(gpsOn = on) }

    /** Distance from the phone GPS. Only counted while the car link is live. */
    @Synchronized
    fun onGpsMeters(m: Double) {
        val st = _state.value
        if (st.status != LinkStatus.LIVE) return
        val km = m / 1000
        lastGpsAt = System.currentTimeMillis()
        // Persist the running GPS count every 100 m
        gpsUnsaved += km
        if (gpsUnsaved >= 0.1) {
            prefs.edit().putFloat("odo_gps", prefs.getFloat("odo_gps", 0f) + gpsUnsaved.toFloat()).apply()
            gpsUnsaved = 0.0
        }
        // Lifetime efficiency from GPS distance
        if (whSinceOdo > 0) {
            prefs.edit()
                .putFloat("lt_km", prefs.getFloat("lt_km", 0f) + km.toFloat())
                .putFloat("lt_wh", prefs.getFloat("lt_wh", 0f) + whSinceOdo.toFloat())
                .apply()
        }
        whSinceOdo = 0.0
        val trip = st.trip
        if (trip != null) _state.update { it.copy(trip = trip.copy(gpsKm = trip.gpsKm + km)) }
        if (st.odoSource != OdoSource.CAR) refreshOdo(null)
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

    fun setStatus(s: LinkStatus, text: String) {
        val before = _state.value.status
        _state.update { it.copy(status = s, statusText = text) }
        if (s != before || s == LinkStatus.RETRYING) LocalLog.event("${s.name.lowercase()}: $text")
    }

    fun resetTrip() {
        val t = _state.value.trip
        if (t != null) logTrip(t, "trip reset")
        _state.update { it.copy(trip = Trip(System.currentTimeMillis(), it.sample.odometerKm)) }
    }

    private fun logTrip(t: Trip, why: String) = LocalLog.event(
        "$why: %.1f km (%s), avg %s km/h moving, %.2f kWh used, %.2f kWh regen, %s Wh/km".format(
            t.km, t.distSource, t.avgMovingKmh?.let { "%.0f".format(it) } ?: "n/a", t.netWh / 1000, t.regenWh / 1000, t.whPerKm?.let { "%.0f".format(it) } ?: "n/a"
        )
    )

    private fun logCharge(c: ChargeSession, why: String) = LocalLog.event(
        "$why: SoC %s -> %s, %.2f kWh added, peak %.1f kW, %d min".format(
            c.startSoc?.toInt() ?: "?", c.endSoc?.toInt() ?: "?", c.addedKwh, c.peakKw,
            ((c.endedAt ?: System.currentTimeMillis()) - c.startedAt) / 60_000
        )
    )

    /** Lifetime average used for range when the current trip is too short. */
    private fun lifetimeWhKm(): Double? {
        val km = prefs.getFloat("lt_km", 0f)
        val wh = prefs.getFloat("lt_wh", 0f)
        return if (km >= 20f) (wh / km).toDouble() else null
    }

    @Synchronized
    fun onSample(s: Sample) {
        val prev = _state.value
        val now = s.at
        val dtH = if (lastAt == 0L) 0.0 else ((now - lastAt).coerceIn(0, 5000)) / 3_600_000.0
        lastAt = now

        val kw = if (s.packV != null && s.currentA != null) s.packV * s.currentA / 1000 else null

        // Charging = current flowing in while the car is parked.
        // With GPS: 20 s of inflow and no GPS movement for 30 s. Without GPS: 90 s of inflow
        // (long downhill regen can last 20+ s, which is what fooled v0.3.2).
        val gpsOn = prev.gpsOn
        val movingNow = now - lastGpsAt < 30_000
        var charging = prev.charging
        if (kw != null) {
            if (kw < -0.3) { if (negSince == 0L) negSince = now; posSince = 0 }
            else if (kw > 0.3) { if (posSince == 0L) posSince = now; negSince = 0 }
            val inflowFor = if (negSince > 0) now - negSince else 0L
            if (!charging && !movingNow && inflowFor > (if (gpsOn) 20_000 else 90_000)) charging = true
            if (charging && posSince > 0 && now - posSince > 10_000) charging = false
            if (charging && movingNow) charging = false
        }

        // Trip
        var trip = prev.trip ?: Trip(now, s.odometerKm)
        if (trip.startOdoKm == null && s.odometerKm != null) trip = trip.copy(startOdoKm = s.odometerKm)
        if (kw != null && !charging) {
            val wh = kw * 1000 * dtH
            trip = trip.copy(netWh = trip.netWh + wh, regenWh = trip.regenWh + if (wh < 0) -wh else 0.0)
            whSinceOdo += wh
        }
        // Moving time: GPS says we're moving (fix counted in the last 5 s);
        // without GPS, the motor drawing more than 1 kW.
        if (!charging && dtH > 0) {
            val gpsOn = now - lastGpsAt < 60_000
            val moving = if (gpsOn) now - lastGpsAt < 5_000 else (kw ?: 0.0) > 1.0
            if (moving) trip = trip.copy(movingMs = trip.movingMs + (dtH * 3_600_000).toLong())
        }
        val odoNow = s.odometerKm
        val odoStart = trip.startOdoKm
        if (odoNow != null && odoStart != null) trip = trip.copy(odoKm = odoNow - odoStart)

        // Lifetime efficiency from odometer ticks — only when GPS isn't doing it
        val gpsActive = now - lastGpsAt < 60_000
        if (s.odometerKm != null && !gpsActive) {
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
        var chargeEnded: ChargeSession? = null
        var chargeDiscarded = false
        val charge: ChargeSession? = if (charging) {
            val base = if (old == null || !old.active) ChargeSession(now, s.socPct) else old
            if (kw != null && kw < 0) base.copy(addedKwh = base.addedKwh + (-kw) * dtH, peakKw = maxOf(base.peakKw, -kw)) else base
        } else if (old != null && old.active) {
            val ended = old.copy(endedAt = now, endSoc = s.socPct)
            // Too small or too short to be a real charge: forget it, keep the previous real one.
            if (ended.addedKwh < 0.1 || now - ended.startedAt < 60_000) {
                chargeDiscarded = true
                lastRealCharge
            } else {
                chargeEnded = ended
                lastRealCharge = ended
                ended
            }
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
        // Demo data must never touch the real odometer.
        if (prev.status != LinkStatus.DEMO && s.odometerKm != null &&
            (s.odometerKm != prev.sample.odometerKm || prev.odoSource != OdoSource.CAR)
        ) {
            refreshOdo(s.odometerKm)
        }

        if (prev.status != LinkStatus.DEMO) {
            LocalLog.sample(s, prev.status, kw, _state.value)
            if (charge != null && charge.active && (old == null || !old.active)) LocalLog.event("charging started at ${s.socPct?.toInt() ?: "?"}%")
            chargeEnded?.let { logCharge(it, "charging ended") }
            if (chargeDiscarded) LocalLog.event("ignored a very short inflow (regen, not a charge)")
        }
    }

    fun onDisconnected() {
        lastAt = 0; negSince = 0; posSince = 0
        val st0 = _state.value
        val c0 = st0.charge
        var keep: ChargeSession? = c0
        if (c0 != null && c0.active) {
            val now = System.currentTimeMillis()
            val ended = c0.copy(endedAt = now, endSoc = st0.sample.socPct)
            if (ended.addedKwh < 0.1 || now - ended.startedAt < 60_000) {
                keep = lastRealCharge
            } else {
                keep = ended; lastRealCharge = ended
                if (st0.status != LinkStatus.DEMO) logCharge(ended, "charging ended (link lost)")
            }
        }
        _state.update { it.copy(charge = keep, charging = false) }
        if (gpsUnsaved > 0) {
            prefs.edit().putFloat("odo_gps", prefs.getFloat("odo_gps", 0f) + gpsUnsaved.toFloat()).apply()
            gpsUnsaved = 0.0
        }
        LocalLog.flush()
    }
}
