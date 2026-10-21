package com.spk.windsordash.obd

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

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
    val battTempC: Int? = null,
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
    private const val IPK_TX = "744"; private const val IPK_RX = "74C"

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

    /** Polls forever, emitting a merged [Sample] after every cycle. */
    suspend fun poll(elm: Elm327, emit: (Sample) -> Unit) {
        var last = Sample()
        var cycle = 0
        var misses = 0
        while (currentCoroutineContext().isActive) {
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

            if (cycle % 15 == 2) {
                elm.target(THERM_TX, THERM_RX)
                elm.readDid("A021", 1)?.get(0)?.let { if (it in 0..80) s = s.copy(battTempC = it) }
                elm.voltage12V()?.let { if (it in 6.0..16.0) s = s.copy(aux12V = it) }
            }

            if (cycle % 30 == 3) {
                elm.target(IPK_TX, IPK_RX)
                elm.readDid("B921", 3, 600)?.u24()?.let { if (it in 1..1_999_999) s = s.copy(odometerKm = it) }
            }

            if (gotAny) misses = 0 else misses++
            if (misses >= 8) throw IllegalStateException("Car stopped answering (ignition off?)")

            last = s
            emit(s)
            cycle++
            delay(150)
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
                    maxCellId = 41, minCellId = 77, battTempC = (29 + 4 * sin(phase / 60 * PI)).toInt(),
                    aux12V = if (charging) 14.1 else 13.8, odometerKm = odo.toInt(),
                )
            )
            delay(700)
        }
    }
}
