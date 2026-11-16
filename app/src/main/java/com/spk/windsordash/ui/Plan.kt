package com.spk.windsordash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spk.windsordash.data.ChargeCurve
import com.spk.windsordash.data.ChargePlanner
import com.spk.windsordash.data.ChargerPoi
import com.spk.windsordash.data.Dash
import com.spk.windsordash.data.DashState
import com.spk.windsordash.data.GoogleMaps
import com.spk.windsordash.data.MapsException
import com.spk.windsordash.data.PlanInput
import com.spk.windsordash.data.PlanOption
import com.spk.windsordash.data.QuickLocation
import com.spk.windsordash.data.RoutePlan
import com.spk.windsordash.data.RoutePlanOutcome
import com.spk.windsordash.data.RoutePlanner
import com.spk.windsordash.obd.WindsorProfile
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The Wh/km the planner starts from: this trip once it's long enough, else recent driving, else 135. */
internal fun planWhPerKm(st: DashState): Pair<Double, String> {
    val t = st.trip
    val tw = t?.whPerKm
    return when {
        tw != null && t.km >= 20 -> tw to "this trip"
        st.effWhKm != null -> st.effWhKm to st.effSource
        else -> 135.0 to "default"
    }
}

@Composable
fun PlanScreen(st: DashState, onBack: () -> Unit, initialKm: String = "", initialAtCharger: Boolean = false, initialWh: String = "") {
    var km by rememberSaveable { mutableStateOf(initialKm) }
    var wh by rememberSaveable { mutableStateOf(initialWh) }
    var reserve by rememberSaveable { mutableStateOf("10") }
    var minAt by rememberSaveable { mutableStateOf("15") }
    var overhead by rememberSaveable { mutableStateOf("10") }
    var atCharger by rememberSaveable { mutableStateOf(initialAtCharger) }
    var socOverride by rememberSaveable { mutableStateOf("") }

    val (autoWh, whSrc) = planWhPerKm(st)
    val soc = socOverride.toDoubleOrNull() ?: st.sample.socPct
    val whUsed = wh.toDoubleOrNull() ?: autoWh

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Teal, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Plan charging", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Fewest minutes to get there with your reserve", color = Faint, fontSize = 12.sp)
            }
        }

        Panel(accent = Cyan) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Km to go", km, Modifier.weight(1f)) { km = it }
                Field("SoC %" + (st.sample.socPct?.let { " · car ${it.roundToInt()}" } ?: ""), socOverride, Modifier.weight(1f)) { socOverride = it }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Wh/km · ${whSrc.take(12)} ${autoWh.roundToInt()}", wh, Modifier.weight(1f)) { wh = it }
                Field("Arrive with %", reserve, Modifier.weight(1f)) { reserve = it }
            }
            Text("Leave SoC and Wh/km blank to use the car and your driving. Your highway runs have been 120–147 Wh/km.", color = Faint, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Reach charger at ≥ %", minAt, Modifier.weight(1f)) { minAt = it }
                Field("Min per stop", overhead, Modifier.weight(1f)) { overhead = it }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("I'm at a charger now", color = Ink, fontSize = 14.sp)
                    Text("First stop is here, before driving", color = Faint, fontSize = 11.sp)
                }
                Switch(checked = atCharger, onCheckedChange = { atCharger = it })
            }
            Spacer(Modifier.height(10.dp))
            val watching = st.planWatchWhKm != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alert if I run above this", color = Ink, fontSize = 14.sp)
                    Text(
                        if (watching) "Watching · ${st.planWatchWhKm!!.roundToInt()} Wh/km"
                        else "Notify once driving averages over ${(whUsed * 1.1).roundToInt()} Wh/km",
                        color = Faint, fontSize = 11.sp,
                    )
                }
                Switch(checked = watching, onCheckedChange = {
                    com.spk.windsordash.data.Dash.setPlanWatch(if (it) whUsed else null)
                })
            }
        }

        val d = km.toDoubleOrNull()
        if (d == null || d <= 0 || soc == null) {
            Text(if (soc == null) "Connect to the car, or type your SoC, then the km to go." else "Type the km still to go.",
                color = Dim, fontSize = 13.sp)
        } else {
            val opts = ChargePlanner.plan(
                PlanInput(
                    km = d, socPct = soc, whPerKm = whUsed,
                    reservePct = reserve.toDoubleOrNull() ?: 10.0, minAtChargerPct = minAt.toDoubleOrNull() ?: 15.0,
                    stopOverheadMin = overhead.toDoubleOrNull() ?: 10.0, atChargerNow = atCharger, sohPct = st.sample.sohPct,
                )
            )
            val needKwh = d * whUsed / 1000
            Text("Needs about %.1f kWh for %.0f km at %.0f Wh/km.".format(needKwh, d, whUsed), color = Dim, fontSize = 13.sp)
            if (opts.isEmpty()) Text("No plan fits — check the numbers (a stop can't charge past 100 %).", color = Amber, fontSize = 13.sp)
            val oneStopCharge = opts.firstOrNull { it.title == "1 stop" }?.chargeMin
            opts.forEachIndexed { i, o -> OptionCard(o, best = i == 0 && opts.size > 1, oneStopCharge = oneStopCharge) }
            Text(
                "Charge speed: " + if (ChargeCurve.learnedBands >= 8) "from your own DC charges." else
                    "Windsor defaults (≈55 kW to 80 %, slowing above) until more of your DC charges are logged — times marked est.",
                color = Faint, fontSize = 11.sp,
            )
            Text("Stops are placed where SoC would reach your minimum; pick the charger at or before that km.", color = Faint, fontSize = 11.sp)
        }

        Spacer(Modifier.height(4.dp))
        RoutePlanSection(st, soc, whUsed, reserve.toDoubleOrNull() ?: 10.0, minAt.toDoubleOrNull() ?: 15.0)
    }
}

/** Current state of the route fetch + plan, driven by the "Plan route" button. */
private sealed interface RouteUi {
    object Idle : RouteUi
    object Loading : RouteUi
    data class Error(val message: String) : RouteUi
    data class Ready(val summary: com.spk.windsordash.data.RouteSummary, val plan: RoutePlan) : RouteUi
}

/**
 * Real-route charging plan: geocodes origin/destination, pulls the route + elevation from Google
 * Directions/Elevation, finds real DC chargers near it via Places, then runs [RoutePlanner] —
 * the same greedy stop placement as the plain planner above, but following the actual road and
 * terrain instead of a straight Wh/km line, with real places instead of an abstract km mark.
 * Needs your own Google Maps Platform API key (Directions, Elevation, Places, Geocoding enabled).
 */
@Composable
private fun RoutePlanSection(st: DashState, soc: Double?, whUsed: Double, reservePct: Double, minAtChargerPct: Double) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(Dash.mapsApiKey) }
    var origin by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var ui by remember { mutableStateOf<RouteUi>(RouteUi.Idle) }

    Panel(accent = Teal) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { expanded = !expanded }) {
            Column(Modifier.weight(1f)) {
                Text("Plan a real route", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Real roads, real chargers — needs your Google Maps key", color = Faint, fontSize = 11.sp)
            }
            Text(if (expanded) "▲" else "▼", color = Teal, fontSize = 13.sp)
        }
        if (expanded) {
        Spacer(Modifier.height(10.dp))

        AddressField("Google Maps API key", apiKey, "Directions, Elevation, Places, Geocoding") {
            apiKey = it; Dash.mapsApiKey = it
        }
        if (apiKey.isNotBlank()) {
            Text("Saved (${apiKey.take(6)}…${apiKey.takeLast(4)})", color = Faint, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))

        AddressField("From", origin, "Blank = your phone's current location") { origin = it }
        Spacer(Modifier.height(8.dp))
        AddressField("To", destination, "City, address, or place name") { destination = it }
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                val key = apiKey
                val dest = destination
                val startSoc = soc
                ui = RouteUi.Loading
                scope.launch {
                    ui = try {
                        if (key.isBlank()) throw MapsException("Paste your Google Maps API key above first.")
                        if (dest.isBlank()) throw MapsException("Type where you're going.")
                        if (startSoc == null) throw MapsException("Connect to the car, or type a SoC above, first.")
                        val originLatLng = if (origin.isBlank()) {
                            val loc = QuickLocation.lastKnown(ctx)
                                ?: throw MapsException("No current location — allow location access, or type a starting address.")
                            com.spk.windsordash.data.LatLng(loc.latitude, loc.longitude)
                        } else {
                            GoogleMaps.geocode(origin, key)
                        }
                        val destLatLng = GoogleMaps.geocode(dest, key)
                        val route = GoogleMaps.directions(originLatLng, destLatLng, key)
                        val withElev = GoogleMaps.withElevations(route.points, key)
                        val chargers = GoogleMaps.chargersAlongRoute(withElev, key)
                        val usableKwh = WindsorProfile.RATED_KWH * ((st.sample.sohPct ?: 100.0) / 100)
                        val plan = RoutePlanner(usableKwh, whUsed).plan(
                            points = withElev, startSoc = startSoc, chargers = chargers,
                            minArrivalAtChargerPct = minAtChargerPct, minArrivalAtDestinationPct = reservePct,
                            maxChargeTargetPct = 95.0, maxDetourM = 5_000.0,
                        )
                        RouteUi.Ready(route.copy(points = withElev), plan)
                    } catch (e: MapsException) {
                        RouteUi.Error(e.message ?: "Something went wrong.")
                    } catch (e: Exception) {
                        RouteUi.Error("Couldn't plan the route: ${e.message ?: e::class.simpleName}")
                    }
                }
            },
            enabled = ui !is RouteUi.Loading,
            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Bg),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (ui is RouteUi.Loading) "Planning…" else "Plan route") }

        when (val u = ui) {
            is RouteUi.Idle -> {}
            is RouteUi.Loading -> {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fetching route, elevation and nearby chargers…", color = Dim, fontSize = 12.sp)
                }
            }
            is RouteUi.Error -> {
                Spacer(Modifier.height(10.dp))
                Text(u.message, color = Amber, fontSize = 13.sp)
            }
            is RouteUi.Ready -> {
                Spacer(Modifier.height(12.dp))
                RouteResult(u.summary, u.plan)
            }
        }
        }
    }
}

@Composable
private fun RouteResult(summary: com.spk.windsordash.data.RouteSummary, plan: RoutePlan) {
    val km = summary.distanceM / 1000.0
    Tiles {
        Tile("Distance", "${km.roundToInt()}", "km")
        Tile("Drive", "${summary.durationMin.roundToInt()}", "min")
        Tile("Charge", "${ChargePlanner.mins(plan.chargeMinutes)}", "min")
    }
    if (kotlin.math.abs(plan.terrainPercent) >= 1.0) {
        Spacer(Modifier.height(8.dp))
        Text(
            if (plan.terrainPercent > 0) "The climbs on this route cost about %.0f %% more battery than flat driving at this Wh/km would.".format(plan.terrainPercent)
            else "This route is net downhill — regen should give back about %.0f %% versus flat driving.".format(-plan.terrainPercent),
            color = Faint, fontSize = 11.sp,
        )
    }
    Spacer(Modifier.height(10.dp))
    when (val o = plan.outcome) {
        is RoutePlanOutcome.Ok -> {
            if (o.stops.isEmpty()) {
                Text("No stop needed — you'll arrive with about ${o.arrivalSoc.roundToInt()} %.", color = Green, fontSize = 13.sp)
            } else {
                o.stops.forEachIndexed { i, s -> ChargerStopRow(i, s.charger, s.arriveSoc, s.departSoc, s.minutes) }
                Spacer(Modifier.height(6.dp))
                Text("Arrive with about ${o.arrivalSoc.roundToInt()} %.", color = Green, fontSize = 13.sp)
            }
        }
        is RoutePlanOutcome.Unreachable -> {
            o.stops.forEachIndexed { i, s -> ChargerStopRow(i, s.charger, s.arriveSoc, s.departSoc, s.minutes) }
            Spacer(Modifier.height(6.dp))
            Text(o.reason, color = Amber, fontSize = 13.sp)
        }
    }
    Text(
        "Chargers come from Google Places — it doesn't know live power or availability, so charge time uses your own DC curve regardless of which one you pick. Check the charger's actually open before relying on it.",
        color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ChargerStopRow(i: Int, charger: ChargerPoi, arriveSoc: Double, departSoc: Double, minutes: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Cyan.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text("${i + 1}", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(charger.name, color = Ink, fontSize = 14.sp)
            Text(
                (charger.address?.take(40) ?: "near km ${(charger.alongRouteM / 1000).roundToInt()}") +
                    " · ${arriveSoc.roundToInt()} → ${departSoc.roundToInt()} %",
                color = Faint, fontSize = 11.sp,
            )
        }
        Text("${ChargePlanner.mins(minutes)} min", color = Dim, fontSize = 13.sp, style = Num)
    }
}

@Composable
private fun OptionCard(o: PlanOption, best: Boolean, oneStopCharge: Double? = null) {
    Panel(accent = if (best) Teal else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (best) Text("FASTEST", color = Teal, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Teal.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        val est = if (o.usesDefaultCurve) " est." else ""
        Tiles {
            Tile("Charge", "${ChargePlanner.mins(o.chargeMin)}", "min$est")
            Tile("Total", "${ChargePlanner.mins(o.totalMin)}", "min", sub = "with stops")
            Tile("Arrive", "${o.arrivalPct.roundToInt()}", "%", color = if (o.arrivalPct < 10) Amber else Green)
        }
        if (o.stops.isNotEmpty()) Spacer(Modifier.height(12.dp))
        o.stops.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Cyan.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (s.atKm < 1) "Here" else "By km ${s.atKm.roundToInt()}") +
                        " · ${s.fromPct.roundToInt()} → ${s.toPct.roundToInt()} %",
                    color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                Text("${ChargePlanner.mins(s.minutes)} min", color = Dim, fontSize = 13.sp, style = Num)
            }
        }
        o.note?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Faint, fontSize = 12.sp) }
        if (oneStopCharge != null && o.stops.size > 1 && o.chargeMin <= oneStopCharge + 3) {
            Spacer(Modifier.height(6.dp))
            Text("Charging time is no more than 1 stop (${ChargePlanner.mins(o.chargeMin)} vs ${ChargePlanner.mins(oneStopCharge)} min). " +
                "If you'd stop for a break anyway, this costs nothing extra — and keeps each stop short.", color = Green.copy(alpha = 0.85f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun AddressField(label: String, value: String, hint: String = "", onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) }, placeholder = { if (hint.isNotEmpty()) Text(hint, color = Faint, fontSize = 12.sp) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Teal, unfocusedBorderColor = Line, focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedLabelColor = Teal, unfocusedLabelColor = Dim, cursorColor = Teal,
        ),
    )
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier, hint: String = "", onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(6)) },
        label = { Text(label, fontSize = 12.sp) }, placeholder = { if (hint.isNotEmpty()) Text(hint, color = Faint) },
        singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Teal, unfocusedBorderColor = Line, focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedLabelColor = Teal, unfocusedLabelColor = Dim, cursorColor = Teal,
        ),
    )
}
