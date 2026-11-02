package com.spk.windsordash.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure maths check (no Android): the 1 Nov return-trip case. Prints the options to the test log. */
class ChargePlannerTest {
    @Test fun returnTrip() {
        val opts = ChargePlanner.plan(PlanInput(km = 300.0, socPct = 35.0, whPerKm = 135.0, atChargerNow = true, sohPct = 99.0))
        opts.forEach { o ->
            println("PLAN ${o.title}: charge ${o.chargeMin.toInt()} min, total ${o.totalMin.toInt()} min, arrive ${o.arrivalPct.toInt()}% " +
                o.stops.joinToString { "km ${it.atKm.toInt()} ${it.fromPct.toInt()}→${it.toPct.toInt()} (${it.minutes.toInt()} min)" })
        }
        assertTrue(opts.isNotEmpty())
        opts.filter { !it.title.startsWith("Fill") }.forEach { assertTrue(it.arrivalPct >= 9.99) }
        val one = opts.first { it.title == "1 stop" }
        assertTrue(one.stops.single().toPct in 85.0..90.0)   // 35 + 10 % reserve + ~77 % needed − 35 ≈ 87 %
    }
}
