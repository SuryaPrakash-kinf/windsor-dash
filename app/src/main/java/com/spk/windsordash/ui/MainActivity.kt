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

private val Bg = Color(0xFF0B0F14)
private val Card = Color(0xFF141B24)
private val Line = Color(0xFF232E3B)
private val Dim = Color(0xFF8A97A8)
private val Teal = Color(0xFF2DD4BF)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)
private val Green = Color(0xFF4ADE80)

class MainActivity : ComponentActivity() {
    private val scanner = AdapterScanner()
    private var afterPermission: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermission?.invoke(); afterPermission = null
    }

    private fun withPermissions(block: () -> Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
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
                Screen(
                    st,
                    onConnect = {
                        if (st.adapter == null) withPermissions { picking = true }
                        else withPermissions { LinkService.start(this) }
                    },
                    onDisconnect = { LinkService.stop(this) },
                    onPick = { withPermissions { picking = true } },
                )
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
private fun Screen(st: DashState, onConnect: () -> Unit, onDisconnect: () -> Unit, onPick: () -> Unit) {
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
            val kw = st.powerKw
            val label = when {
                kw == null -> "Power"
                st.charging -> "Charging"
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
                    Text(s.currentA?.let { "%.1f A".format(it) } ?: "– A", color = Color.White, fontFamily = FontFamily.Monospace)
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
                "Distance" to (t?.let { "${it.km} km" } ?: "–"),
                "Efficiency" to (t?.whPerKm?.let { "${it.roundToInt()} Wh/km" } ?: "–"),
                "Energy used" to (t?.let { "%.2f kWh".format(it.netWh / 1000) } ?: "–"),
                "Regen" to (t?.let { "%.2f kWh".format(it.regenWh / 1000) } ?: "–"),
            )
        }

        // Battery health
        Panel {
            Text("Battery", color = Dim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            val delta = st.cellDeltaMv
            Grid(
                "Health (SoH)" to (s.sohPct?.let { "${it.roundToInt()} %" } ?: "–"),
                "Pack temp" to (s.battTempC?.let { "$it °C" } ?: "–"),
                "Highest cell" to (s.maxCellMv?.let { "$it mV" + (s.maxCellId?.let { id -> " · #$id" } ?: "") } ?: "–"),
                "Lowest cell" to (s.minCellMv?.let { "$it mV" + (s.minCellId?.let { id -> " · #$id" } ?: "") } ?: "–"),
                "Cell spread" to (delta?.let { "$it mV" } ?: "–"),
                "12 V battery" to (s.aux12V?.let { "%.1f V".format(it) } ?: "–"),
                "Odometer" to (s.odometerKm?.let { "$it km" } ?: "–"),
            )
            if (delta != null && delta > 50) {
                Spacer(Modifier.height(6.dp))
                Text("Cell spread above 50 mV — worth watching. LFP spread reads largest near empty and full.", color = Amber, fontSize = 12.sp)
            }
        }

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
private fun LogsPanel() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(LocalLog.enabled) }
    var bytes by remember { mutableLongStateOf(LocalLog.totalBytes()) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { bytes = LocalLog.totalBytes(); delay(5000) } }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Local logs", color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    "${LocalLog.files().size} files · ${"%.1f".format(bytes / 1024.0)} KB · kept on this phone only",
                    color = Dim, fontSize = 12.sp
                )
            }
            Switch(checked = on, onCheckedChange = { on = it; LocalLog.setEnabled(ctx, it) })
        }
        Spacer(Modifier.height(8.dp))
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
        Text(
            "samples-<date>.csv: one row per reading · events.csv: connects, trips, charge sessions",
            color = Dim, fontSize = 11.sp
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Card,
        title = { Text("Delete all logs?") },
        text = { Text("Copies already saved to Downloads are not touched.") },
        confirmButton = {
            TextButton(onClick = {
                LocalLog.deleteAll(); bytes = 0; confirmDelete = false
            }) { Text("Delete", color = Red) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

private fun minutes(m: Long): String = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"

@Composable
private fun Panel(content: @Composable () -> Unit) {
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
