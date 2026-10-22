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

/** One drive: from first movement until 5 min parked, a charge, or the link dropping. */
data class Run(
    val startedAt: Long,
    val socStart: Double?,
    val lastMoveAt: Long,
    val socEnd: Double? = socStart,
    val km: Double = 0.0,
    val movingMs: Long = 0,
    val topKmh: Double = 0.0,
    val netWh: Double = 0.0,
    val regenWh: Double = 0.0,
)

data class ChargeSession(
    val startedAt: Long,
    val startSoc: Double?,
    val addedKwh: Double = 0.0,
    val peakKw: Double = 0.0,
    val endedAt: Long? = null,
    val endSoc: Double? = null,
    /** Set when the link dropped mid-charge and the missing part came from the battery's own counters. */
    val gapNote: String? = null,
) {
    val active get() = endedAt == null
}

/** A charge that was in progress when the car stopped answering (e.g. locked while charging). */
private data class PausedCharge(
    val session: ChargeSession, val lossAt: Long, val socAtLoss: Double?, val energyAtLoss: Double?,
) {
    fun encode() = listOf(session.startedAt, session.startSoc ?: "", session.addedKwh, session.peakKw,
        lossAt, socAtLoss ?: "", energyAtLoss ?: "").joinToString("|")

    companion object {
        fun decode(s: String?): PausedCharge? = runCatching {
            val p = s!!.split('|')
            PausedCharge(
                ChargeSession(p[0].toLong(), p[1].toDoubleOrNull(), p[2].toDouble(), p[3].toDouble()),
                p[4].toLong(), p[5].toDoubleOrNull(), p[6].toDoubleOrNull(),
            )
        }.getOrNull()
    }
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
    val parked: Boolean = false,      // GPS reports ~0 speed right now
    val speedKmh: Double? = null,     // GPS speed (good fixes only)
    val run: Run? = null,             // drive in progress
    /** kWh the battery lost while the app wasn't connected (likely a drive without the dongle). */
    val offlineDriveKwh: Double? = null,
) {
    val hasData get() = sample.at > 0 && (status == LinkStatus.LIVE || status == LinkStatus.DEMO)
    /** Energy left ÷ SoC, only at ≥ 80 % SoC where the 1 % rounding matters least. Calculated, not a BMS value. */
    val capacityKwh: Double?
        get() {
            val soc = sample.socPct ?: return null
            val e = sample.energyLeftKwh ?: return null
            return if (soc >= 80) e / (soc / 100) else null
        }
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
    private var lastFixAt = 0L
    private var lastFixSpeed: Float? = null
    private var inflowWh = 0.0          // energy flowing in before we decided it's a charge
    private var paused: PausedCharge? = null
    private var offlineChecked = false
    private var lastSeenSaved = 0L
    private var targetAlerted = false
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var lastOdo: Int? = null
    private var whSinceOdo = 0.0
    private var lastGpsAt = 0L
    private var gpsUnsaved = 0.0
    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("dash", Context.MODE_PRIVATE)
        LocalLog.init(ctx.applicationContext)
        com.spk.windsordash.obd.OdoFinder.init(ctx.applicationContext)
        _state.update {
            it.copy(
                adapter = AdapterRef.decode(prefs.getString("adapter", null)),
                targetSoc = prefs.getInt("target", 100),
                odoSpec = OdoSpec.decodeSpec(prefs.getString("odo_spec", null)),
            )
        }
        refreshOdo(null)
        paused = PausedCharge.decode(prefs.getString("paused_charge", null))
        if (!prefs.contains("home_tariff")) prefs.edit().putFloat("home_tariff", 0f).apply()   // solar at home
        Alerts.init(ctx.applicationContext)
        CellWatch.init(ctx.applicationContext)
    }

    /** The battery's state the last time we could read it — compared on the next connection. */
    private fun saveLastSeen(s: Sample) {
        val soc = s.socPct ?: return
        val e = s.energyLeftKwh ?: return
        lastSeenSaved = s.at
        prefs.edit().putLong("ls_at", s.at).putFloat("ls_soc", soc.toFloat()).putFloat("ls_e", e.toFloat()).apply()
    }

    /**
     * First reading after connecting: did the battery change while we weren't watching?
     * Up by ≥ 2 % and ≥ 1 kWh → a charge happened (logged as "found after the fact").
     * Down by ≥ 1 kWh → probably a drive without the dongle (odometer hint).
     */
    private fun checkWhileAway(s: Sample) {
        val soc = s.socPct ?: return
        val e = s.energyLeftKwh ?: return
        offlineChecked = true
        val at = prefs.getLong("ls_at", 0L)
        if (at == 0L || s.at - at < 5 * 60_000L) return
        val soc0 = prefs.getFloat("ls_soc", -1f).toDouble()
        val e0 = prefs.getFloat("ls_e", -1f).toDouble()
        if (soc0 < 0 || e0 < 0) return
        val dE = e - e0
        val dSoc = soc - soc0
        if (dE >= 1.0 && dSoc >= 2) {
            val tariff = homeTariff
            val entry = ChargeEntry(
                id = at + 1, start = at, end = s.at, type = ChargerType.HOME_AC,
                socStart = soc0.toInt(), socEnd = soc.toInt(), kwhBattery = dE,
                costInr = tariff?.let { it * dE }, odometerKm = _state.value.odoKm?.toInt(), auto = true,
                notes = "Found after the fact: charged while the dongle was disconnected, between " +
                    "${hm.format(Date(at))} and ${hm.format(Date(s.at))}. kWh is the battery's own energy counter; " +
                    "if you also drove in between, the real charge was larger." +
                    (tariff?.let { " Cost calculated at home tariff ₹%.2f/kWh.".format(it) } ?: ""),
            )
            Thread { runCatching { ChargeLog.upsert(entry) } }.start()
            LocalLog.event("charge found while disconnected: %.0f→%.0f%%, +%.1f kWh".format(soc0, soc, dE))
            Alerts.offlineCharge(soc0, soc, dE)
        } else if (dE <= -1.0) {
            _state.update { it.copy(offlineDriveKwh = -dE) }
            LocalLog.event("battery used while disconnected: %.1f kWh (%.0f→%.0f%%)".format(-dE, soc0, soc))
        }
    }

    /** Home charging cost per kWh (null = not set). Solar at home → 0. */
    var homeTariff: Double?
        get() = prefs.getFloat("home_tariff", -1f).takeIf { it >= 0 }?.toDouble()
        set(v) { prefs.edit().apply { if (v == null) remove("home_tariff") else putFloat("home_tariff", v.toFloat()) }.apply() }

    fun dismissOfflineDrive() = _state.update { it.copy(offlineDriveKwh = null) }

    private fun setPaused(p: PausedCharge?) {
        paused = p
        prefs.edit().apply { if (p == null) remove("paused_charge") else putString("paused_charge", p.encode()) }.apply()
    }

    /** Records a finished charge (log line, charges.csv, notification). */
    private fun recordEnded(c: ChargeSession, why: String, odo: Int?) {
        lastRealCharge = c
        logCharge(c, why)
        Thread { runCatching { ChargeLog.addAuto(c, odo) } }.start()
        Alerts.chargeFinished(c)
    }

    /**
     * After reconnecting: finish (or resume) a charge that was cut off when the car stopped answering.
     * Missing energy = battery energy-left now − at the moment the link dropped (both read from the BMS).
     */
    private fun resolvePaused(s: Sample, kw: Double?, now: Long) {
        val p = paused ?: return
        val soc = s.socPct ?: return
        if (p.energyAtLoss != null && s.energyLeftKwh == null) return   // wait for the energy reading
        setPaused(null)
        val odo = _state.value.odoKm?.toInt()
        val socGain = p.socAtLoss?.let { soc - it } ?: 0.0
        val eNow = s.energyLeftKwh
        val gapKwh = if (p.energyAtLoss != null && eNow != null) (eNow - p.energyAtLoss).coerceAtLeast(0.0) else 0.0
        val stale = now - p.lossAt > 16 * 3_600_000L
        if (stale || (socGain < 1 && gapKwh < 0.2)) {
            // Nothing charged while we were away: end it where the link dropped.
            val ended = p.session.copy(endedAt = p.lossAt, endSoc = p.socAtLoss)
            if (ended.addedKwh >= 0.1 && p.lossAt - ended.startedAt >= 60_000) {
                recordEnded(ended, "charging ended (link lost)", odo)
                _state.update { it.copy(charge = ended) }
            } else _state.update { it.copy(charge = lastRealCharge) }
            return
        }
        val note = "link lost ${hm.format(Date(p.lossAt))}–${hm.format(Date(now))}; " +
            "%.1f kWh added meanwhile, from the battery's energy counter".format(gapKwh)
        val merged = p.session.copy(addedKwh = p.session.addedKwh + gapKwh, gapNote = note)
        LocalLog.event("charge gap filled: $note")
        if (kw != null && kw < -0.3) {
            // Still charging: carry on with the same session.
            negSince = now - 30_000; posSince = 0; inflowWh = 0.0
            _state.update { it.copy(charge = merged, charging = true) }
        } else {
            val ended = merged.copy(endedAt = now, endSoc = soc)
            recordEnded(ended, "charging ended (filled after link loss)", odo)
            _state.update { it.copy(charge = ended, charging = false) }
        }
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
        _state.update { it.copy(offlineDriveKwh = null) }
        refreshOdo(null)
    }

    fun saveOdoSpec(spec: OdoSpec?) {
        prefs.edit().putString("odo_spec", spec?.encode()).apply()
        _state.update { it.copy(odoSpec = spec) }
        LocalLog.event(if (spec == null) "odometer location cleared" else "odometer location saved: ${spec.describe()}")
    }

    /** Every usable GPS fix: tells us whether the car is standing still. */
    private val recentSpeeds = ArrayDeque<Double>()

    @Synchronized
    fun onGpsFix(speedMps: Float?, accuracyM: Float = 0f) {
        lastFixAt = System.currentTimeMillis()
        lastFixSpeed = speedMps
        if (speedMps == null || accuracyM > 25f) return
        val kmh = speedMps * 3.6
        // Top speed = the lowest of the last 3 good fixes, so one GPS spike can't set a record.
        recentSpeeds.addLast(kmh); while (recentSpeeds.size > 3) recentSpeeds.removeFirst()
        val robust = if (recentSpeeds.size == 3) recentSpeeds.min() else 0.0
        _state.update { st ->
            val r = st.run
            st.copy(speedKmh = kmh, run = if (r != null && robust > r.topKmh) r.copy(topKmh = robust) else r)
        }
    }

    /** Writes the finished run to runs.csv (ignores tiny ones). */
    private fun finishRun(r: Run?) {
        if (r == null) return
        _state.update { it.copy(run = null) }
        if (r.km < 0.5 || _state.value.status == LinkStatus.DEMO) return
        LocalLog.run(r)
        LocalLog.event("run: %.1f km, SoC %s→%s, top %.0f km/h".format(
            r.km, r.socStart?.toInt() ?: "?", r.socEnd?.toInt() ?: "?", r.topKmh))
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
        st.run?.let { r -> _state.update { it.copy(run = it.run?.copy(km = (it.run?.km ?: r.km) + km)) } }
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
        if (_state.value.status == LinkStatus.LIVE) {
            // A charge cut off mid-way is handled by the gap fill instead — don't count it twice.
            if (!offlineChecked) { if (paused == null) checkWhileAway(s) else offlineChecked = true }
            if (s.at - lastSeenSaved > 60_000) saveLastSeen(s)
        }
        if (paused != null && _state.value.status != LinkStatus.DEMO) {
            val k = if (s.packV != null && s.currentA != null) s.packV * s.currentA / 1000 else null
            resolvePaused(s, k, s.at)
        }
        val prev = _state.value
        val now = s.at
        val dtH = if (lastAt == 0L) 0.0 else ((now - lastAt).coerceIn(0, 5000)) / 3_600_000.0
        lastAt = now

        val kw = if (s.packV != null && s.currentA != null) s.packV * s.currentA / 1000 else null

        // Charging = current flowing in while the car is parked. Regen needs the car moving.
        //  - GPS shows ~0 speed (fresh fix):        4 s of inflow is enough
        //  - GPS on, no movement counted for 30 s:  20 s
        //  - no GPS at all:                         90 s (long downhill regen can last 20+ s)
        val gpsOn = prev.gpsOn
        val movingNow = now - lastGpsAt < 30_000
        val parked = now - lastFixAt < 10_000 && (lastFixSpeed ?: 99f) < 0.5f && now - lastGpsAt > 15_000
        var charging = prev.charging
        if (kw != null) {
            if (kw < -0.3) { if (negSince == 0L) negSince = now; posSince = 0 }
            else if (kw > 0.3) { if (posSince == 0L) posSince = now; negSince = 0; inflowWh = 0.0 }
            val inflowFor = if (negSince > 0) now - negSince else 0L
            if (!charging && negSince > 0) {
                val need = when {
                    parked -> 4_000L
                    gpsOn && !movingNow -> 20_000L
                    !gpsOn -> 90_000L
                    else -> Long.MAX_VALUE        // GPS says we're moving: it's regen
                }
                if (inflowFor > need) charging = true
            }
            if (charging && posSince > 0 && now - posSince > 10_000) charging = false
            if (charging && movingNow) charging = false
        }
        val justStarted = charging && !prev.charging
        if (!charging && kw != null && kw < -0.3) inflowWh += -kw * 1000 * dtH

        // Trip
        var trip = prev.trip ?: Trip(now, s.odometerKm)
        if (justStarted && inflowWh > 0) {
            // The seconds before we recognised the charge were booked as regen: move them back.
            trip = trip.copy(netWh = trip.netWh + inflowWh, regenWh = maxOf(0.0, trip.regenWh - inflowWh))
            whSinceOdo += inflowWh
        }
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
            val base = if (old == null || !old.active) {
                ChargeSession(if (negSince > 0) negSince else now, s.socPct, addedKwh = inflowWh / 1000)
            } else old
            if (justStarted) inflowWh = 0.0
            if (kw != null && kw < 0) base.copy(addedKwh = base.addedKwh + (-kw) * dtH, peakKw = maxOf(base.peakKw, -kw)) else base
        } else if (old != null && old.active && paused == null) {
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
            rangeKm = range, effWhKm = eff, effSource = src, parked = parked,
        )
        // Demo data must never touch the real odometer.
        if (prev.status != LinkStatus.DEMO && s.odometerKm != null &&
            (s.odometerKm != prev.sample.odometerKm || prev.odoSource != OdoSource.CAR)
        ) {
            refreshOdo(s.odometerKm)
        }

        // Runs: start on GPS movement, end after 5 min parked or when charging starts.
        if (prev.status == LinkStatus.LIVE) {
            val movingRun = now - lastGpsAt < 5_000
            var r = _state.value.run
            if (charging && r != null) { finishRun(r); r = null }
            else if (r != null && now - r.lastMoveAt > 5 * 60_000L) { finishRun(r); r = null }
            if (!charging) {
                if (r == null && movingRun) r = Run(now, s.socPct, now)
                if (r != null) {
                    val wh = (kw ?: 0.0) * 1000 * dtH
                    r = r.copy(
                        lastMoveAt = if (movingRun) now else r.lastMoveAt,
                        movingMs = r.movingMs + if (movingRun) (dtH * 3_600_000).toLong() else 0L,
                        netWh = r.netWh + wh, regenWh = r.regenWh + if (wh < 0) -wh else 0.0,
                        socEnd = s.socPct ?: r.socEnd,
                    )
                    _state.update { it.copy(run = r) }
                }
            }
        }

        if (prev.status == LinkStatus.LIVE && s.maxCellId != null) {
            CellWatch.record(s.maxCellId, s.minCellId, kw, charging, s.socPct)
        }
        if (prev.status != LinkStatus.DEMO) {
            LocalLog.sample(s, prev.status, kw, _state.value)
            if (charge != null && charge.active && (old == null || !old.active)) LocalLog.event("charging started at ${s.socPct?.toInt() ?: "?"}%")
            chargeEnded?.let { c -> recordEnded(c, "charging ended", _state.value.odoKm?.toInt()) }
            // Target reached: notify once per session.
            val cs = _state.value.charge
            if (charging && cs != null && cs.active) {
                val soc = s.socPct
                if (soc != null && soc >= prev.targetSoc && !targetAlerted) { targetAlerted = true; Alerts.targetReached(soc, prev.targetSoc) }
            } else if (!charging) targetAlerted = false
            if (chargeDiscarded) LocalLog.event("ignored a very short inflow (regen, not a charge)")
        }
    }

    fun onDisconnected() {
        finishRun(_state.value.run)
        if (_state.value.status == LinkStatus.LIVE) saveLastSeen(_state.value.sample)
        offlineChecked = false
        lastAt = 0; negSince = 0; posSince = 0
        val st0 = _state.value
        val c0 = st0.charge
        var keep: ChargeSession? = c0
        if (c0 != null && c0.active) {
            val now = System.currentTimeMillis()
            if (st0.status == LinkStatus.DEMO) {
                keep = lastRealCharge
            } else {
                // Don't close it yet: the car may simply have gone to sleep (locked while charging).
                // It's finished with the battery's own counters when the car answers again.
                if (paused == null) {   // first loss only; retries mustn't move the loss time
                    setPaused(PausedCharge(c0, now, st0.sample.socPct, st0.sample.energyLeftKwh))
                    LocalLog.event("charging: link lost at ${st0.sample.socPct?.toInt() ?: "?"}% — will fill in on reconnect")
                    Alerts.linkPausedWhileCharging()
                }
                keep = c0
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
