package com.spk.windsordash.obd

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** One raw research read: ECU request id, DID, payload hex (or NRC:xx). */
data class RawRead(val tx: String, val did: String, val hex: String, val sweep: Boolean)

/** One reading from the car. Null = not read (yet) this cycle. */
data class Sample(
    val at: Long = System.currentTimeMillis(),
    val packV: Double? = null,
    val currentA: Double? = null,      // + discharge, − charge
    val socPct: Double? = null,
    val sohPct: Double? = null,
    val energyLeftKwh: Double? = null,
    val maxCellMv: Int? = null,
    val minCellMv: Int? = null,
    val maxCellId: Int? = null,
    val minCellId: Int? = null,
    /** Thermal-module DID A021. NOT the pack temperature (stuck at 31 °C all day) — kept for research only. */
    val a021Raw: Int? = null,
    /** Raw single bytes being identified as temperatures (raw − 50 looks like °C; unconfirmed). */
    val d02bRaw: Int? = null,   // pack temperature candidate
    val d02eRaw: Int? = null,   // fast-rising: motor/inverter?
    val d016Raw: Int? = null,   // tracks outside air: coolant/inlet?
    val d036Raw: Int? = null,   // fast-rising: drive unit?
    /** Being confirmed: D064 looks like the car's minutes-to-full, D065 its charge power limit (W). */
    val d064Raw: Int? = null,
    val d065Raw: Int? = null,
    val aux12V: Double? = null,
    val odometerKm: Int? = null,
)

/**
 * MG Windsor EV (52.9 kWh, 98S LFP) over UDS on CAN 500k/11-bit.
 * BMS: request 0x742, response 0x74A. Thermal module 0x745/0x74D. Cluster 0x744/0x74C.
 */
object WindsorProfile {
    const val RATED_KWH = 52.92
    const val CELLS = 98

    private const val BMS_TX = "742"; private const val BMS_RX = "74A"
    private const val THERM_TX = "745"; private const val THERM_RX = "74D"

    /** Cheap check that the car on the other end is a Windsor (BMS answers D038). */
    suspend fun probe(elm: Elm327): Boolean {
        elm.target(BMS_TX, BMS_RX)
        repeat(3) {
            val v = elm.readDid("D038", 2, 800)
            if (v != null) return true
            delay(400)
        }
        return false
    }

    /**
     * Polls forever, emitting a merged [Sample] after every cycle.
     * [odoSpec] says where to read the odometer (null = try the ZS EV guess, then give up).
     * [between] runs between cycles (used by the odometer finder).
     */
    suspend fun poll(
        elm: Elm327,
        odoSpec: () -> OdoSpec?,
        between: suspend () -> Unit = {},
        research: () -> Boolean = { false },
        onRaw: (List<RawRead>) -> Unit = {},
        emit: (Sample) -> Unit,
    ) {
        var lastSweepAt = 0L
        val sweepQueue = ArrayDeque<Pair<String, String>>()
        var last = Sample()
        var cycle = 0
        var misses = 0
        var odoFails = 0
        while (currentCoroutineContext().isActive) {
            between()
            var s = last.copy(at = System.currentTimeMillis())
            var gotAny = false

            elm.target(BMS_TX, BMS_RX)
            elm.readDid("D038", 2)?.u16()?.let { if (it in 200..500) { s = s.copy(packV = it.toDouble()); gotAny = true } }
            elm.readDid("D027", 2)?.u16()?.let { raw ->
                val a = (raw - 10000) / 10.0
                if (kotlin.math.abs(a) <= 600) { s = s.copy(currentA = a); gotAny = true }
            }

            if (cycle % 5 == 0) {
                elm.readDid("D062", 1)?.get(0)?.let { if (it in 1..100) s = s.copy(socPct = it.toDouble()) }
                elm.readDid("D061", 1)?.get(0)?.let { if (it in 50..100) s = s.copy(sohPct = it.toDouble()) }
                elm.readDid("D060", 2)?.u16()?.let { val k = it / 10.0; if (k > 0 && k <= 60) s = s.copy(energyLeftKwh = k) }
            }

            if (cycle % 3 == 0) {
                val mx = elm.readDid("D06A", 2)?.u16()
                val mn = elm.readDid("D06C", 2)?.u16()
                if (mx != null && mn != null && mx >= mn && mn > 2500 && mx < 4500) {
                    s = s.copy(
                        maxCellMv = mx, minCellMv = mn,
                        maxCellId = elm.readDid("D06B", 1)?.get(0)?.takeIf { it in 1..CELLS },
                        minCellId = elm.readDid("D06D", 1)?.get(0)?.takeIf { it in 1..CELLS },
                    )
                }
            }

            if (cycle % 15 == 9) {
                elm.target(BMS_TX, BMS_RX)
                s = s.copy(
                    d02bRaw = elm.readDid("D02B", 1)?.get(0) ?: s.d02bRaw,
                    d02eRaw = elm.readDid("D02E", 1)?.get(0) ?: s.d02eRaw,
                    d016Raw = elm.readDid("D016", 1)?.get(0) ?: s.d016Raw,
                    d036Raw = elm.readDid("D036", 1)?.get(0) ?: s.d036Raw,
                    d064Raw = elm.readDid("D064", 2)?.u16() ?: s.d064Raw,
                    d065Raw = elm.readDid("D065", 2)?.u16() ?: s.d065Raw,
                )
            }

            if (cycle % 15 == 2) {
                elm.target(THERM_TX, THERM_RX)
                elm.readDid("A021", 1)?.get(0)?.let { s = s.copy(a021Raw = it) }
                elm.voltage12V()?.let { if (it in 6.0..16.0) s = s.copy(aux12V = it) }
            }

            // Odometer: saved location if the finder found one; otherwise the ZS EV guess,
            // abandoned after 3 failures (retried every ~10 min).
            val spec = odoSpec() ?: OdoSpec.ZS_EV_GUESS.takeIf { odoFails < 3 || cycle % 600 == 3 }
            if (spec != null && cycle % 30 == 3) {
                val km = readOdometer(elm, spec)
                if (km != null && km >= 1 && km < 2_000_000) {
                    s = s.copy(odometerKm = km.toInt()); odoFails = 0
                } else {
                    odoFails++
                    s = s.copy(odometerKm = null)   // never show a stale value as if it were current
                }
            }

            // Research capture: raw bytes from candidate DIDs so the real pack temperature
            // (and more) can be identified from a log. Off unless switched on.
            if (research() && gotAny) {
                val now = System.currentTimeMillis()
                if (cycle % 15 == 7) onRaw(readRawSet(elm, BMS_TX, BMS_RX, RESEARCH_BMS) + readRawSet(elm, THERM_TX, THERM_RX, RESEARCH_THERM))
                // Full sweep of D0xx (BMS) and A0xx (thermal) every 10 min, 8 DIDs per cycle so
                // live readings and energy totals keep flowing (~70 s per sweep).
                // Near the top of a charge (where LFP balancing happens) sweep every 5 min, else every 10.
                val topOfCharge = (s.socPct ?: 0.0) >= 97 || (s.currentA ?: 0.0) < -1
                val every = if (topOfCharge) 5 * 60_000L else 10 * 60_000L
                if (sweepQueue.isEmpty() && now - lastSweepAt > every) {
                    lastSweepAt = now
                    (0xD000..0xD2FF).forEach { sweepQueue.add(BMS_TX to "%04X".format(it)) }
                    (0xA000..0xA0FF).forEach { sweepQueue.add(THERM_TX to "%04X".format(it)) }
                }
                if (sweepQueue.isNotEmpty()) {
                    val batch = List(minOf(8, sweepQueue.size)) { sweepQueue.removeFirst() }
                    for ((tx, group) in batch.groupBy { it.first }) {
                        val rx = if (tx == BMS_TX) BMS_RX else THERM_RX
                        onRaw(readRawSet(elm, tx, rx, group.map { it.second }, sweep = true))
                    }
                }
            }

            if (gotAny) misses = 0 else misses++
            if (misses >= 8) throw IllegalStateException("Car stopped answering (ignition off?)")

            last = s
            emit(s)
            cycle++
            delay(150)
        }
    }

    private val RESEARCH_BMS = listOf("D01D", "D014", "D01F", "D016", "D030", "D031", "D032", "D033",
        "D034", "D035", "D036", "D037", "D02B", "D101")
    private val RESEARCH_THERM = listOf("A021", "A035", "A017")

    private suspend fun readRawSet(elm: Elm327, tx: String, rx: String, dids: List<String>, sweep: Boolean = false): List<RawRead> {
        elm.target(tx, rx)
        val out = mutableListOf<RawRead>()
        for (d in dids) {
            val v = elm.readRaw(d, if (sweep) 200 else 300) ?: continue
            if (sweep && v.startsWith("NRC")) continue      // sweeps keep only DIDs that answer with data
            out += RawRead(tx, d, v, sweep)
        }
        return out
    }

    suspend fun readOdometer(elm: Elm327, spec: OdoSpec): Double? {
        return if (spec.isObd) {
            elm.query("ATSH7DF", 300); elm.query("ATCRA${spec.rx}", 300); elm.forgetTarget()
            Elm327.payloadAfter(elm.query("01A6", 500), "41A6")?.let { spec.decode(it) }
        } else {
            elm.target(spec.tx, spec.rx)
            Elm327.payloadAfter(elm.query("22${spec.did}", 600), "62${spec.did}")?.let { spec.decode(it) }
        }
    }

    /** Simulated Windsor: a 25-minute drive, then a DC fast charge, looping. */
    suspend fun demo(emit: (Sample) -> Unit) {
        val t0 = System.currentTimeMillis()
        var soc = 72.0
        var odo = 18_420.0
        var energy = soc / 100 * RATED_KWH * 0.97
        var last = t0
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val dt = (now - last) / 1000.0
            last = now
            val phase = ((now - t0) / 1000.0) % 600.0      // 10 min loop (sped up)
            val charging = phase > 420
            val kw = if (charging) {
                -(38.0 + 6 * sin(phase / 9))                 // ~40 kW DC
            } else {
                val wave = sin(phase / 7) * 14 + sin(phase / 2.3) * 6 + 9
                if (sin(phase / 11) < -0.75) -wave * 0.4 else max(wave, 1.2)   // occasional regen
            }
            val v = 310.0 + soc * 0.25 - kw * 0.15
            val a = kw * 1000 / v
            energy -= kw * dt / 3600 * 8                     // 8x time-lapse so numbers move
            energy = energy.coerceIn(2.0, RATED_KWH * 0.97)
            soc = energy / (RATED_KWH * 0.97) * 100
            if (!charging && kw > 0) odo += (kw * dt / 3600 * 8) / 0.135   // ~135 Wh/km
            val cellAvg = (v / CELLS * 1000).toInt()
            emit(
                Sample(
                    at = now, packV = v, currentA = a, socPct = soc.toInt().toDouble(), sohPct = 97.0,
                    energyLeftKwh = energy, maxCellMv = cellAvg + 6, minCellMv = cellAvg - 5,
                    maxCellId = 41, minCellId = 77,
                    aux12V = if (charging) 14.1 else 13.8, odometerKm = odo.toInt(),
                )
            )
            delay(700)
        }
    }
}
