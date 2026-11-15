package com.spk.windsordash.data

import kotlin.math.abs
import kotlin.math.ceil

/** Predicted SoC at a point along the route, for the elevation/SoC chart. */
data class RouteSocPoint(val distanceM: Double, val socPercent: Double, val elevationM: Double)

data class RouteStop(
    val charger: ChargerPoi,
    val arriveSoc: Double,
    val departSoc: Double,
    val minutes: Double,
)

sealed interface RoutePlanOutcome {
    data class Ok(val stops: List<RouteStop>, val arrivalSoc: Double) : RoutePlanOutcome
    data class Unreachable(val stops: List<RouteStop>, val strandedAtM: Double, val reason: String) : RoutePlanOutcome
}

data class RoutePlan(
    val outcome: RoutePlanOutcome,
    val distanceM: Double,
    val socTrace: List<RouteSocPoint>,
    /** % of the pack the climbs cost beyond what flat driving at this Wh/km would (negative if net downhill). */
    val terrainPercent: Double,
) {
    val chargeMinutes: Double get() = when (outcome) {
        is RoutePlanOutcome.Ok -> outcome.stops.sumOf { it.minutes }
        is RoutePlanOutcome.Unreachable -> outcome.stops.sumOf { it.minutes }
    }
}

/**
 * Adds terrain to your flat Wh/km: climbing costs extra (mass x g x height / drive efficiency),
 * descending gives some back through regen. [driveEfficiency]/[regenEfficiency] are rough,
 * Windsor-shaped assumptions (not measured from your car) — terrain is usually a small correction
 * next to your actual Wh/km, so this mainly matters on routes with real ghat sections.
 * Small height wiggles (GPS/DEM noise) under [NOISE_M] are ignored so flat roads don't register as climbing.
 */
private object Terrain {
    const val MASS_KG = 1_750.0        // Windsor kerb weight + driver/luggage, rough
    const val DRIVE_EFFICIENCY = 0.85
    const val REGEN_EFFICIENCY = 0.60
    const val NOISE_M = 2.0
    private const val G = 9.81
    private const val JOULES_PER_KWH = 3.6e6

    fun kwh(dhM: Double) = MASS_KG * G * dhM / JOULES_PER_KWH
}

/**
 * Route-aware stop planner: walks the elevation-corrected SoC down the route and places a stop at
 * the furthest real charger ([ChargerPoi]) still reachable before the buffer, charging just enough
 * (via your learned [ChargeCurve]) to reach the next one or the destination. Mirrors the logic of
 * the simple [ChargePlanner], but the distance-to-SoC conversion follows the real route profile
 * (elevation) instead of a straight Wh/km line, and stops are real places, not an abstract km mark.
 */
class RoutePlanner(private val usableKwh: Double, private val flatWhPerKm: Double) {

    /** SoC at every route point, starting from [startSoc]; may run below 0 to show the shortfall. */
    fun simulate(points: List<RoutePoint>, startSoc: Double, capSoc: Double = 100.0): List<RouteSocPoint> {
        if (points.isEmpty()) return emptyList()
        val perPercent = usableKwh / 100.0
        var soc = startSoc
        var pendingDh = 0.0
        val out = ArrayList<RouteSocPoint>(points.size)
        out += RouteSocPoint(points[0].distanceM, soc, points[0].elevationM)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            var kwh = (b.distanceM - a.distanceM) / 1000.0 * flatWhPerKm / 1000.0
            pendingDh += b.elevationM - a.elevationM
            if (abs(pendingDh) >= Terrain.NOISE_M) {
                kwh += if (pendingDh > 0) Terrain.kwh(pendingDh) / Terrain.DRIVE_EFFICIENCY
                else -Terrain.kwh(-pendingDh) * Terrain.REGEN_EFFICIENCY
                pendingDh = 0.0
            }
            soc = minOf(capSoc, soc - kwh / perPercent)
            out += RouteSocPoint(b.distanceM, soc, b.elevationM)
        }
        return out
    }

    /** Net terrain cost as a % of the pack over the whole route (0 if flat; can be negative net-downhill). */
    fun terrainPercent(points: List<RoutePoint>): Double {
        if (points.size < 2) return 0.0
        val flatOnly = simulate(points.map { it.copy(elevationM = points.first().elevationM) }, 100.0, capSoc = 1_000.0)
        val real = simulate(points, 100.0, capSoc = 1_000.0)
        return (flatOnly.last().socPercent - real.last().socPercent)
    }

    fun plan(
        points: List<RoutePoint>,
        startSoc: Double,
        chargers: List<ChargerPoi>,
        minArrivalAtChargerPct: Double,
        minArrivalAtDestinationPct: Double,
        maxChargeTargetPct: Double,
        maxDetourM: Double,
    ): RoutePlan {
        val total = points.last().distanceM
        val candidates = chargers.filter { it.alongRouteM in 1.0..<total && it.detourM <= maxDetourM }
        val trace = ArrayList<RouteSocPoint>()
        val stops = ArrayList<RouteStop>()
        var fromIndex = 0
        var soc = startSoc
        var guard = 0
        while (true) {
            guard++
            val leg = points.subList(fromIndex, points.size)
            val sim = simulate(leg, soc)
            val arrival = sim.last().socPercent
            if (arrival >= minArrivalAtDestinationPct || guard > 20) {
                trace += sim
                val outcome = if (guard > 20) RoutePlanOutcome.Unreachable(stops, total, "Planner did not converge")
                else RoutePlanOutcome.Ok(stops, arrival)
                return RoutePlan(outcome, total, trace, terrainPercent(points))
            }
            val limitM = sim.firstOrNull { it.socPercent < minArrivalAtChargerPct }?.distanceM ?: total
            val reachable = candidates.filter { c ->
                c.alongRouteM > sim.first().distanceM + 1 && c.alongRouteM <= limitM &&
                    arrivalAt(sim, c) >= minArrivalAtChargerPct
            }
            val pick = reachable.maxByOrNull { it.alongRouteM }
            if (pick == null) {
                trace += sim.takeWhile { it.distanceM <= limitM }
                return RoutePlan(
                    RoutePlanOutcome.Unreachable(stops, limitM, "No charger found within ${(maxDetourM / 1000).toInt()} km of the route before the battery reaches $minArrivalAtChargerPct %"),
                    total, trace, terrainPercent(points),
                )
            }
            val arrive = arrivalAt(sim, pick)
            val idx = points.indexOfFirst { it.distanceM >= pick.alongRouteM }.coerceAtLeast(fromIndex + 1)
            val dropToEnd = arrive - arrivalFromHere(points, idx, arrive)
            val needToFinish = dropToEnd + minArrivalAtDestinationPct + FINISH_MARGIN
            val depart = minOf(maxChargeTargetPct, maxOf(arrive + 5.0, roundUp5(needToFinish))).coerceAtMost(100.0)
            if (depart <= arrive + 0.5) {
                trace += sim
                return RoutePlan(
                    RoutePlanOutcome.Unreachable(stops, limitM, "Charge target $maxChargeTargetPct % is too low to continue"),
                    total, trace, terrainPercent(points),
                )
            }
            val (mins, _) = ChargeCurve.minutes(arrive, depart, usableKwh)
            stops += RouteStop(pick, arrive, depart, mins)
            trace += sim.takeWhile { it.distanceM < pick.alongRouteM }
            trace += RouteSocPoint(points[idx].distanceM, arrive, points[idx].elevationM)
            soc = depart
            fromIndex = idx
        }
    }

    private fun arrivalAt(sim: List<RouteSocPoint>, c: ChargerPoi): Double {
        val atExit = sim.lastOrNull { it.distanceM <= c.alongRouteM }?.socPercent ?: sim.first().socPercent
        val detourKwh = c.detourM / 1000.0 * flatWhPerKm / 1000.0 // round trip off-route, at flat efficiency
        return atExit - detourKwh / usableKwh * 100.0
    }

    private fun arrivalFromHere(points: List<RoutePoint>, idx: Int, soc: Double): Double =
        simulate(points.subList(idx, points.size), soc, capSoc = 1_000.0).last().socPercent

    private fun roundUp5(x: Double) = ceil(x / 5.0) * 5.0

    private companion object {
        const val FINISH_MARGIN = 3.0
    }
}
