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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spk.windsordash.data.ChargeCurve
import com.spk.windsordash.data.ChargePlanner
import com.spk.windsordash.data.DashState
import com.spk.windsordash.data.PlanInput
import com.spk.windsordash.data.PlanOption
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
                Field("SoC now %", socOverride, Modifier.weight(1f), hint = soc?.roundToInt()?.toString() ?: "") { socOverride = it }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Wh/km", wh, Modifier.weight(1f), hint = autoWh.roundToInt().toString()) { wh = it }
                Field("Arrive with %", reserve, Modifier.weight(1f)) { reserve = it }
            }
            Text("Wh/km from $whSrc unless you type one. Highway runs have been 120–147 Wh/km for you.", color = Faint, fontSize = 11.sp)
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
            opts.forEachIndexed { i, o -> OptionCard(o, best = i == 0 && opts.size > 1) }
            Text(
                "Charge speed: " + if (ChargeCurve.learnedBands >= 8) "from your own DC charges." else
                    "Windsor defaults (≈55 kW to 80 %, slowing above) until more of your DC charges are logged — times marked est.",
                color = Faint, fontSize = 11.sp,
            )
            Text("Stops are placed where SoC would reach your minimum; pick the charger at or before that km.", color = Faint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OptionCard(o: PlanOption, best: Boolean) {
    Panel(accent = if (best) Teal else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (best) Text("FASTEST", color = Teal, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Teal.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        val est = if (o.usesDefaultCurve) " est." else ""
        Tiles {
            Tile("Charging", "${ChargePlanner.mins(o.chargeMin)}", "min$est")
            Tile("Incl. stops", "${ChargePlanner.mins(o.totalMin)}", "min")
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
    }
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
