package com.spk.windsordash.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.spk.windsordash.data.OdoSource
import com.spk.windsordash.obd.OdoFinder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.spk.windsordash.data.LocalLog
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spk.windsordash.data.Dash
import com.spk.windsordash.data.DashState
import com.spk.windsordash.data.LinkStatus
import com.spk.windsordash.obd.AdapterRef
import com.spk.windsordash.obd.WindsorProfile
import com.spk.windsordash.service.LinkService
import kotlin.math.abs
import kotlin.math.roundToInt

internal val Bg = Color(0xFF0B0F14)
internal val Card = Color(0xFF141B24)
internal val Line = Color(0xFF232E3B)
internal val Dim = Color(0xFF8A97A8)
internal val Teal = Color(0xFF2DD4BF)
internal val Amber = Color(0xFFFBBF24)
internal val Red = Color(0xFFF87171)
internal val Green = Color(0xFF4ADE80)

class MainActivity : ComponentActivity() {
    private val scanner = AdapterScanner()
    private var afterPermission: (() -> Unit)? = null

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Thread {
                LocalLog.useFolder(uri)
                runOnUiThread { Toast.makeText(this, "Logs now go to ${LocalLog.destination()}", Toast.LENGTH_LONG).show() }
            }.start()
        }
    }

    /** Opens the system folder picker (Documents/WindsorDash is a good choice). */
    fun chooseLogFolder() = folderLauncher.launch(null)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermission?.invoke(); afterPermission = null
    }

    private fun withPermissions(block: () -> Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // GPS trip distance (and Bluetooth scanning on Android 11 and older)
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        afterPermission = block
        permLauncher.launch(perms.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dash.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Bg, surface = Card)) {
                val st by Dash.state.collectAsStateWithLifecycle()
                var picking by remember { mutableStateOf(false) }
                var page by remember { mutableStateOf("home") }
                androidx.activity.compose.BackHandler(enabled = page != "home") { page = "home" }
                when (page) {
                    "report" -> ReportScreen(onBack = { page = "home" })
                    "charges" -> ChargesScreen(onBack = { page = "home" })
                    else -> Screen(
                    st,
                    onConnect = {
                        if (st.adapter == null) withPermissions { picking = true }
                        else withPermissions { LinkService.start(this) }
                    },
                    onDisconnect = { LinkService.stop(this) },
                    onPick = { withPermissions { picking = true } },
                    onReport = { page = "report" },
                    onCharges = { page = "charges" },
                )
                }
                if (picking) PickerDialog(
                    scanner,
                    onDismiss = { picking = false },
                    onChosen = { ref ->
                        picking = false
                        Dash.savedAdapter = ref
                        LinkService.start(this)
                    },
                )
            }
        }
    }
}

@Composable
private fun Screen(
    st: DashState, onConnect: () -> Unit, onDisconnect: () -> Unit, onPick: () -> Unit,
    onReport: () -> Unit = {}, onCharges: () -> Unit = {},
) {
    val s = st.sample
    val running = st.status != LinkStatus.IDLE
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Windsor Dash", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dot = when (st.status) {
                        LinkStatus.LIVE -> Green; LinkStatus.DEMO -> Amber
                        LinkStatus.CONNECTING, LinkStatus.RETRYING -> Amber; LinkStatus.IDLE -> Dim
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.size(6.dp))
                    Text(st.statusText, color = Dim, fontSize = 13.sp, maxLines = 2)
                }
            }
            if (running) OutlinedButton(onClick = onDisconnect) { Text("Stop") }
            else Button(onClick = onConnect) { Text("Connect") }
        }
        Text(
            "Adapter: ${st.adapter?.name ?: "none chosen"}  ·  change",
            color = Teal, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onPick)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReport) { Text("Report") }
            OutlinedButton(onClick = onCharges) { Text("Charging log") }
        }

        // Not live: say so, so old numbers aren't mistaken for current ones
        if (!st.hasData && s.at > 0) {
            Text(
                "Not live — showing the last reading from " +
                    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.at)),
                color = Amber, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x22FBBF24)).padding(10.dp)
            )
        }

        // Hero: SoC + range
        Panel {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.socPct?.let { "${it.roundToInt()}" } ?: "–", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("%", fontSize = 24.sp, color = Dim, modifier = Modifier.padding(bottom = 12.dp, start = 2.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(st.rangeKm?.let { "$it km" } ?: "– km", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Teal)
                    Text("est. range at ${st.effWhKm?.roundToInt() ?: "–"} Wh/km (${st.effSource})", fontSize = 11.sp, color = Dim)
                }
            }
            LinearProgressIndicator(
                progress = { ((s.socPct ?: 0.0) / 100).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if ((s.socPct ?: 100.0) < 20) Red else Teal, trackColor = Line,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                s.energyLeftKwh?.let { "%.1f kWh left of %.1f".format(it, WindsorProfile.RATED_KWH) } ?: "",
                color = Dim, fontSize = 13.sp
            )
        }

        // Power
        Panel {
            val kw = if (st.hasData) st.powerKw else null   // power is instantaneous: blank when not live
            val label = when {
                kw == null -> "Power"
                st.charging -> "Charging"
                kw < -0.2 && st.parked -> "Charging (starting)"
                kw < -0.2 -> "Regen"
                else -> "Power"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = Dim, fontSize = 13.sp)
                    Text(kw?.let { "%.1f kW".format(abs(it)) } ?: "–", fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                        color = if (kw != null && kw < 0) Green else Color.White)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.packV?.let { "%.0f V".format(it) } ?: "– V", color = Color.White, fontFamily = FontFamily.Monospace)
                    Text(s.currentA?.takeIf { st.hasData }?.let { "%.1f A".format(it) } ?: "– A", color = Color.White, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Charging
        st.charge?.let { c ->
            Panel {
                Text(if (c.active) "Charging session" else "Last charge", color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Grid(
                    "Added" to "%.2f kWh".format(c.addedKwh),
                    "Peak" to "%.1f kW".format(c.peakKw),
                    "SoC" to "${c.startSoc?.roundToInt() ?: "–"} → ${(c.endSoc ?: s.socPct)?.roundToInt() ?: "–"} %",
                    "Time" to minutes(((c.endedAt ?: System.currentTimeMillis()) - c.startedAt) / 60_000),
                )
                if (c.active) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("To", color = Dim)
                        for (t in listOf(80, 90, 100)) FilterChip(
                            selected = st.targetSoc == t, onClick = { Dash.setTarget(t) }, label = { Text("$t%") })
                        Spacer(Modifier.weight(1f))
                        Text(st.minutesToTarget?.let { "est. " + minutes(it.toLong()) } ?: "–", color = Teal, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Trip
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trip", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Dash.resetTrip() }) { Text("Reset") }
            }
            val t = st.trip
            Grid(
                "Distance" to (t?.let { "%.1f km (%s)".format(it.km, it.distSource) } ?: "–"),
                "Efficiency" to (t?.whPerKm?.let { "${it.roundToInt()} Wh/km" } ?: "–"),
                "Energy used" to (t?.let { "%.2f kWh".format(it.netWh / 1000) } ?: "–"),
                "Regen" to (t?.let { "%.2f kWh".format(it.regenWh / 1000) } ?: "–"),
                "Avg speed (moving)" to (t?.avgMovingKmh?.let { "%.0f km/h".format(it) } ?: "–"),
                "Avg incl. stops" to (t?.avgOverallKmh()?.let { "%.0f km/h".format(it) } ?: "–"),
                "Moving time" to (t?.let { minutes(it.movingMs / 60_000) } ?: "–"),
                "Trip time" to (t?.let { minutes((System.currentTimeMillis() - it.startedAt) / 60_000) } ?: "–"),
            )
        }

        // Battery health
        Panel {
            Text("Battery", color = Dim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            val delta = st.cellDeltaMv
            Grid(
                "Health (SoH)" to (s.sohPct?.let { "${it.roundToInt()} %" } ?: "–"),
                "Highest cell" to (s.maxCellMv?.let { "$it mV" + (s.maxCellId?.let { id -> " · #$id" } ?: "") } ?: "–"),
                "Lowest cell" to (s.minCellMv?.let { "$it mV" + (s.minCellId?.let { id -> " · #$id" } ?: "") } ?: "–"),
                "Cell spread" to (delta?.let { "$it mV" } ?: "–"),
                "12 V battery" to (s.aux12V?.let { "%.1f V".format(it) } ?: "–"),
            )
            if (delta != null && delta > 50) {
                Spacer(Modifier.height(6.dp))
                Text("Cell spread above 50 mV — worth watching. LFP spread reads largest near empty and full.", color = Amber, fontSize = 12.sp)
            }
        }

        RunsPanel(st)
        OdometerPanel(st)
        LogsPanel()

        Text(
            "All battery and cell figures are the BMS's own readings, shown as reported — nothing is " +
                "interpolated or guessed. Only range and charge time are estimates (marked est.).",
            color = Dim, fontSize = 12.sp
        )
        Text(
            "Android Auto: open the Windsor Dash tile in the car's app launcher. Sideloaded apps need " +
                "Android Auto → Settings → tap Version 10× → ⋮ Developer settings → Unknown sources.",
            color = Dim, fontSize = 12.sp
        )
    }
}

@Composable
private fun OdometerPanel(st: DashState) {
    val finder by OdoFinder.state.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf<String?>(null) }   // "set" or "find"
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Odometer", color = Dim, fontSize = 13.sp)
                Text(
                    st.odoKm?.let { "%,.0f km".format(it) } ?: "–",
                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold
                )
            }
            val (tag, col) = when (st.odoSource) {
                OdoSource.CAR -> "from car" to Green
                OdoSource.GPS -> "GPS-tracked" to Amber
                OdoSource.NONE -> "not set" to Dim
            }
            Text(tag, color = col, fontSize = 12.sp)
        }
        Text(st.odoNote, color = Dim, fontSize = 12.sp)
        if (!st.gpsOn && st.status == LinkStatus.LIVE) {
            Text("GPS is off — allow Location for Windsor Dash to count distance.", color = Amber, fontSize = 12.sp)
        }
        st.odoSpec?.let { Text("Car odometer location: ${it.describe()}", color = Dim, fontSize = 11.sp) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ask = "set" }) { Text("Correct") }
            OutlinedButton(
                onClick = { ask = "find" },
                enabled = !finder.running,
            ) { Text("Find in car") }
            if (st.odoSpec != null) TextButton(onClick = { Dash.saveOdoSpec(null) }) { Text("Forget", color = Red) }
        }

        // Finder progress / results
        if (finder.running || finder.finished) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Line)
            Spacer(Modifier.height(8.dp))
            Text(finder.text, color = Color.White, fontSize = 13.sp)
            if (finder.running) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { finder.progress }, modifier = Modifier.fillMaxWidth(),
                    color = Teal, trackColor = Line,
                )
                Text(
                    "Runs in the background while connected — live readings carry on. Expect about an hour of " +
                        "driving or charging in total; progress is kept between trips. A match is only trusted after " +
                        "it rises with the GPS distance.",
                    color = Dim, fontSize = 11.sp
                )
            }
            if (finder.ecus.isNotEmpty()) Text("ECUs answering: ${finder.ecus.joinToString(", ")}", color = Dim, fontSize = 11.sp)
            for (m in finder.matches) {
                val saved = st.odoSpec == m.spec
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "%.1f km · %s".format(
                                m.latestKm,
                                when {
                                    m.confirmed -> "confirmed by driving"
                                    m.latestOdoKm - m.atOdoKm >= 0.5 -> "checking (+%.1f vs GPS +%.1f km)".format(m.latestKm - m.valueKm, m.latestOdoKm - m.atOdoKm)
                                    else -> "candidate — drive 2 km to check"
                                }
                            ),
                            color = if (m.confirmed) Green else Amber, fontSize = 13.sp
                        )
                        Text(m.spec.describe(), color = Dim, fontSize = 11.sp)
                    }
                    if (saved) Text("in use", color = Green, fontSize = 12.sp)
                    else TextButton(onClick = { Dash.saveOdoSpec(m.spec) }) { Text("Use") }
                }
            }
            Row {
                if (finder.running) TextButton(onClick = { OdoFinder.cancel() }) { Text("Stop search", color = Red) }
                else TextButton(onClick = { OdoFinder.dismiss() }) { Text("Close") }
            }
        }
    }

    ask?.let { mode ->
        var text by remember { mutableStateOf(st.odoKm?.toInt()?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { ask = null },
            containerColor = Card,
            title = { Text(if (mode == "set") "Dashboard odometer" else "Find the odometer") },
            text = {
                Column {
                    Text(
                        if (mode == "set") "Type the km shown on the instrument cluster. GPS distance counts on from here."
                        else "Type the km shown on the cluster right now. The app then searches the car's computers for that " +
                            "number in the background (read-only requests) while you drive normally.",
                        color = Dim, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { v -> text = v.filter { it.isDigit() }.take(7) },
                        singleLine = true, label = { Text("km") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val km = text.toIntOrNull()
                    if (km != null && km > 0) {
                        Dash.setOdometer(km)
                        if (mode == "find") OdoFinder.start()
                    }
                    ask = null
                }) { Text(if (mode == "set") "Save" else "Start search") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RunsPanel(st: DashState) {
    val ctx = LocalContext.current
    var runs by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    val current = st.run
    // Reload when a run finishes (run goes back to null) and every 30 s.
    LaunchedEffect(current == null) {
        while (true) {
            runs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LocalLog.readRuns(15) }
            delay(30_000)
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Runs", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val uri = LocalLog.runsUri(ctx)
                if (uri == null) Toast.makeText(ctx, "No runs yet", Toast.LENGTH_SHORT).show()
                else ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share runs report"))
            }) { Text("Share") }
        }
        if (current != null) {
            Text(
                "Driving now · %.1f km · top %.0f km/h · SoC %s→%s%%".format(
                    current.km, current.topKmh, current.socStart?.roundToInt() ?: "–", current.socEnd?.roundToInt() ?: "–"
                ) + (if (current.socStart != null && current.socEnd != null)
                    " · used %d%%".format((current.socStart - current.socEnd).roundToInt()) else ""),
                color = Teal, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (runs.isEmpty() && current == null) {
            Text("Each drive is added here when it ends (5 min parked, a charge, or the link dropping).", color = Dim, fontSize = 12.sp)
        }
        // columns: start,end,minutes,soc_start,soc_end,km,top_kmh,avg_moving_kmh,avg_kmh,kwh_used,wh_per_km,regen_kwh
        for (r in runs) {
            val c = { i: Int -> r.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "–" }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                val used = r.getOrNull(3)?.toIntOrNull()?.let { a -> r.getOrNull(4)?.toIntOrNull()?.let { a - it } }
                Text(
                    "${c(0)} → ${c(1).substringAfter(' ')}  ·  ${c(3)}→${c(4)}%" + (used?.let { "  ·  used $it%" } ?: ""),
                    color = Color.White, fontSize = 14.sp
                )
                Text(
                    "${c(5)} km · top ${c(6)} km/h · avg ${c(7)} moving / ${c(8)} overall · ${c(10)} Wh/km",
                    color = Dim, fontSize = 12.sp
                )
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable
private fun LogsPanel() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(LocalLog.enabled) }
    var bytes by remember { mutableLongStateOf(LocalLog.totalBytes()) }
    var dest by remember { mutableStateOf(LocalLog.destination()) }
    var inFolder by remember { mutableStateOf(LocalLog.folderUri != null) }
    var err by remember { mutableStateOf(LocalLog.folderError) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            bytes = LocalLog.totalBytes(); dest = LocalLog.destination()
            inFolder = LocalLog.folderUri != null; err = LocalLog.folderError
            delay(2000)
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Local logs", color = Color.White, fontWeight = FontWeight.Medium)
                Text("Writing to $dest · never uploaded", color = Dim, fontSize = 12.sp)
            }
            Switch(checked = on, onCheckedChange = { on = it; LocalLog.setEnabled(ctx, it) })
        }
        err?.let {
            Text("Couldn't write to the folder ($it) — saving to app storage meanwhile.", color = Amber, fontSize = 12.sp)
        }
        var keep by remember { mutableStateOf(LocalLog.keepDays) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp)) {
            Text("Keep logs", color = Dim, fontSize = 12.sp)
            for ((d, label) in listOf(30 to "30 days", 90 to "90 days", 0 to "Forever")) FilterChip(
                selected = keep == d, onClick = { keep = d; LocalLog.setKeepDays(d) }, label = { Text(label, fontSize = 12.sp) })
        }
        Text("Older samples/raw files are deleted daily (also in your chosen folder). runs.csv and events.csv are kept.",
            color = Dim, fontSize = 11.sp)
        var research by remember { mutableStateOf(LocalLog.research) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Research capture", color = Color.White, fontSize = 14.sp)
                Text(
                    "Also records raw battery-computer bytes to raw-<date>.csv, to find the real pack " +
                        "temperature. Switch on before a DC charge or a long drive.",
                    color = Dim, fontSize = 12.sp
                )
            }
            Switch(checked = research, onCheckedChange = { research = it; LocalLog.setResearch(it) })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { (ctx as? MainActivity)?.chooseLogFolder() }) {
                Text(if (inFolder) "Change folder" else "Choose folder")
            }
            if (inFolder) TextButton(onClick = {
                LocalLog.useAppStorage(); inFolder = false; dest = LocalLog.destination()
            }) { Text("Use app storage") }
        }
        if (!inFolder) {
            Text(
                "${LocalLog.files().size} files · ${"%.1f".format(bytes / 1024.0)} KB in app storage",
                color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val n = runCatching { LocalLog.saveToDownloads(ctx) }.getOrDefault(0)
                    val msg = when {
                        n < 0 -> "Needs Android 10+ — use Share instead"
                        n == 0 -> "No logs yet"
                        else -> "Saved $n files to Downloads/WindsorDash"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }) { Text("Save to Downloads") }
                OutlinedButton(onClick = {
                    LocalLog.flush()
                    val files = LocalLog.files()
                    if (files.isEmpty()) {
                        Toast.makeText(ctx, "No logs yet", Toast.LENGTH_SHORT).show()
                    } else {
                        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".logs", it) })
                        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "text/csv"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share logs"))
                    }
                }) { Text("Share") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Red) }
            }
        } else {
            Text(
                "New rows are added to the files in that folder every few seconds. Open, copy or delete them with any file manager.",
                color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            "samples-<date>.csv: one row per reading · events.csv: connects, trips, charge sessions",
            color = Dim, fontSize = 11.sp
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Card,
        title = { Text("Delete app-storage logs?") },
        text = { Text("Copies already saved to Downloads or a chosen folder are not touched.") },
        confirmButton = {
            TextButton(onClick = {
                LocalLog.deleteAll(); bytes = 0; confirmDelete = false
            }) { Text("Delete", color = Red) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

internal fun minutes(m: Long): String = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"

@Composable
internal fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp)
    ) { content() }
}

@Composable
private fun Grid(vararg cells: Pair<String, String>) {
    cells.toList().chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            row.forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    Text(k, color = Dim, fontSize = 12.sp)
                    Text(v, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PickerDialog(scanner: AdapterScanner, onDismiss: () -> Unit, onChosen: (AdapterRef) -> Unit) {
    val found by scanner.found.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        scanner.start()
        onDispose { scanner.stop() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Choose OBD adapter") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    PickRow(AdapterRef.WIFI_DEFAULT.name, "Wi-Fi dongle — join its Wi-Fi first") { onChosen(AdapterRef.WIFI_DEFAULT) }
                    PickRow(AdapterRef.DEMO.name, "Try the app and Android Auto without the car") { onChosen(AdapterRef.DEMO) }
                    HorizontalDivider(color = Line)
                    if (scanning) Text("Scanning Bluetooth…", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                items(found, key = { it.ref.kind.name + it.ref.address }) { f ->
                    PickRow(
                        f.ref.name,
                        (if (f.ref.kind == AdapterRef.Kind.BLE) "Bluetooth LE" else "Bluetooth classic") +
                            (if (f.likelyObd) " · looks like OBD" else "") + (f.rssi?.let { " · $it dBm" } ?: ""),
                        highlight = f.likelyObd,
                    ) { onChosen(f.ref) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickRow(title: String, sub: String, highlight: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, color = if (highlight) Teal else Color.White, fontWeight = FontWeight.Medium)
        Text(sub, color = Dim, fontSize = 12.sp)
    }
}
