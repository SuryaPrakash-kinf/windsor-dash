package com.spk.windsordash.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

    /* ---------- auto-connect (Android 12+) ---------- */

    private var pendingAutoAddr: String? = null
    private val cdmLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val a = pendingAutoAddr
        if (res.resultCode == RESULT_OK && a != null) observeAuto(a)
        else android.widget.Toast.makeText(this, "Auto-connect not set up", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun prefs() = getSharedPreferences("dash", MODE_PRIVATE)
    fun autoConnectName(): String? = prefs().getString("auto_name", null)

    @android.annotation.SuppressLint("MissingPermission")
    fun setupAutoConnect(address: String, name: String) {
        if (Build.VERSION.SDK_INT < 31) {
            android.widget.Toast.makeText(this, "Auto-connect needs Android 12 or newer", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val cdm = getSystemService(android.companion.CompanionDeviceManager::class.java)
        pendingAutoAddr = address
        prefs().edit().putString("auto_name_pending", name).apply()
        @Suppress("DEPRECATION")
        val known = cdm.associations.any { it.equals(address, true) }
        if (known) { observeAuto(address); return }
        val req = android.companion.AssociationRequest.Builder()
            .addDeviceFilter(android.companion.BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true).build()
        val cb = object : android.companion.CompanionDeviceManager.Callback() {
            @Deprecated("Deprecated in Java")
            override fun onDeviceFound(sender: android.content.IntentSender) {
                cdmLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
            }
            override fun onAssociationPending(sender: android.content.IntentSender) {
                cdmLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
            }
            override fun onAssociationCreated(info: android.companion.AssociationInfo) { observeAuto(address) }
            override fun onFailure(error: CharSequence?) {
                runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Couldn't set up: $error", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(req, mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(req, cb, null)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun observeAuto(address: String) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            @Suppress("DEPRECATION")
            getSystemService(android.companion.CompanionDeviceManager::class.java).startObservingDevicePresence(address)
        }.onSuccess {
            val name = prefs().getString("auto_name_pending", address)
            prefs().edit().putString("auto_addr", address).putString("auto_name", name).apply()
            android.widget.Toast.makeText(this, "Auto-connect on: $name", android.widget.Toast.LENGTH_LONG).show()
        }.onFailure {
            android.widget.Toast.makeText(this, "Couldn't watch the device: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun turnOffAutoConnect() {
        val a = prefs().getString("auto_addr", null) ?: return
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            val cdm = getSystemService(android.companion.CompanionDeviceManager::class.java)
            @Suppress("DEPRECATION") cdm.stopObservingDevicePresence(a)
            @Suppress("DEPRECATION") cdm.disassociate(a)
        }
        prefs().edit().remove("auto_addr").remove("auto_name").apply()
    }

    override fun onResume() {
        super.onResume()
        if (Dash.state.value.status != LinkStatus.IDLE) LinkService.refresh(this)
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
internal fun Screen(
    st: DashState, onConnect: () -> Unit, onDisconnect: () -> Unit, onPick: () -> Unit,
    onReport: () -> Unit = {}, onCharges: () -> Unit = {},
    showHousekeeping: Boolean = true,
) {
    val s = st.sample
    val running = st.status != LinkStatus.IDLE
    Column(
        Modifier.fillMaxSize().background(Bg)
            .background(Brush.radialGradient(listOf(Teal.copy(alpha = 0.10f), Color.Transparent), center = Offset(900f, -100f), radius = 1100f))
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── Header ─────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WINDSOR", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("DASH", color = Teal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 6.sp)
            }
            if (running) PillButton("Stop", onClick = onDisconnect)
            else PillButton("Connect", filled = true, onClick = onConnect)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = when (st.status) {
                LinkStatus.LIVE -> Green; LinkStatus.DEMO -> Amber
                LinkStatus.CONNECTING, LinkStatus.RETRYING -> Amber; LinkStatus.IDLE -> Dim
            }
            Box(Modifier.weight(1f, fill = false)) { StatusPill(st.statusText, dot) }
            Spacer(Modifier.width(8.dp))
            st.odoKm?.let {
                Text("%,d km".format(it.roundToInt()) + if (st.odoSource == OdoSource.CAR) "" else " · gps",
                    color = Dim, fontSize = 12.sp, style = Num)
            }
        }
        if (showHousekeeping) AutoConnectLine()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Report", onClick = onReport)
            PillButton("Charging log", onClick = onCharges)
            PillButton(st.adapter?.name?.take(14) ?: "Adapter", onClick = onPick)
        }

        // Not live: say so, so old numbers aren't mistaken for current ones
        if (!st.hasData && s.at > 0) {
            Text(
                "Not live — last reading " +
                    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.at)),
                color = Amber, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Amber.copy(alpha = 0.10f))
                    .border(1.dp, Amber.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(12.dp)
            )
        }

        // ── Hero: SoC ring + range ─────────────────────────────
        Panel(accent = socColor(s.socPct)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SocRing(s.socPct, st.charging)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Label("Range est.")
                    Value(st.rangeKm?.toString() ?: "–", "km", size = 38.sp, color = Teal, weight = FontWeight.Light)
                    Text("at ${st.effWhKm?.roundToInt() ?: "–"} Wh/km · ${st.effSource}", color = Faint, fontSize = 11.sp)
                    Spacer(Modifier.height(14.dp))
                    Label("Energy left")
                    Value(s.energyLeftKwh?.let { "%.1f".format(it) } ?: "–", "kWh", size = 22.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column { Label("SoH"); Value(s.sohPct?.roundToInt()?.toString() ?: "–", "%", size = 17.sp) }
                        Column { Label("Pack*"); Value(s.packTempMaxC?.toString() ?: "–", "°C", size = 17.sp, color = tempColor(s.packTempMaxC)) }
                    }
                }
            }
        }

        // ── Power ──────────────────────────────────────────────
        Panel {
            val kw = if (st.hasData) st.powerKw else null   // power is instantaneous: blank when not live
            val label = when {
                kw == null -> "Power"
                st.charging -> "Charging"
                kw < -0.2 && st.parked -> "Charging (starting)"
                kw < -0.2 -> "Regen"
                else -> "Drive"
            }
            val col = when { kw == null -> Ink; kw < -0.2 -> Green; kw > 30 -> Orange; else -> Ink }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Label(label, color = if (kw != null && kw < -0.2) Green else Dim)
                    Value(kw?.let { "%.1f".format(abs(it)) } ?: "–", "kW", size = 40.sp, color = col, weight = FontWeight.Light)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Value(s.packV?.let { "%.0f".format(it) } ?: "–", "V", size = 16.sp, weight = FontWeight.Medium)
                    Value(s.currentA?.takeIf { st.hasData }?.let { "%.1f".format(it) } ?: "–", "A", size = 16.sp, weight = FontWeight.Medium)
                    st.speedKmh?.takeIf { st.hasData }?.let { Value("%.0f".format(it), "km/h", size = 16.sp, weight = FontWeight.Medium, color = Dim) }
                }
            }
            Spacer(Modifier.height(14.dp))
            PowerMeter(kw)
        }

        // ── Charging ───────────────────────────────────────────
        st.charge?.let { c ->
            Panel(accent = if (c.active) Green else null) {
                Heading(
                    when {
                        c.active && st.status != LinkStatus.LIVE -> "Charging · car not answering"
                        c.active -> "Charging session"
                        else -> "Last charge"
                    },
                    dot = if (c.active) Green else Dim,
                )
                c.gapNote?.let { Text(it, color = Dim, fontSize = 11.sp); Spacer(Modifier.height(8.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("Added", "%.1f".format(c.addedKwh), "kWh", color = Green)
                    Tile("Peak", "%.0f".format(c.peakKw), "kW")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("SoC", "${c.startSoc?.roundToInt() ?: "–"}→${(c.endSoc ?: s.socPct)?.roundToInt() ?: "–"}", "%")
                    Tile("Time", minutes(((c.endedAt ?: System.currentTimeMillis()) - c.startedAt) / 60_000))
                }
                if (c.active) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("To")
                        for (t in listOf(80, 90, 100)) FilterChip(
                            selected = st.targetSoc == t, onClick = { Dash.setTarget(t) }, label = { Text("$t%") })
                        Spacer(Modifier.weight(1f))
                        Text(st.minutesToTarget?.let { "est. " + minutes(it.toLong()) } ?: "–", color = Teal, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ── Trip ───────────────────────────────────────────────
        Panel {
            val t = st.trip
            Heading("Trip", dot = Cyan) {
                Text("Reset", color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { Dash.resetTrip() }.padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Distance", t?.let { "%.1f".format(it.km) } ?: "–", "km", sub = t?.distSource)
                Tile("Efficiency", t?.whPerKm?.roundToInt()?.toString() ?: "–", "Wh/km", color = Cyan)
            }
            Spacer(Modifier.height(6.dp))
            Grid(
                "Energy used" to (t?.let { "%.2f kWh".format(it.netWh / 1000) } ?: "–"),
                "Regen" to (t?.let { "%.2f kWh".format(it.regenWh / 1000) } ?: "–"),
                "Avg speed (moving)" to (t?.avgMovingKmh?.let { "%.0f km/h".format(it) } ?: "–"),
                "Avg incl. stops" to (t?.avgOverallKmh()?.let { "%.0f km/h".format(it) } ?: "–"),
                "Moving time" to (t?.let { minutes(it.movingMs / 60_000) } ?: "–"),
                "Trip time" to (t?.let { minutes((System.currentTimeMillis() - it.startedAt) / 60_000) } ?: "–"),
            )
        }

        // ── Battery ────────────────────────────────────────────
        Panel {
            val delta = st.cellDeltaMv
            Heading("Battery", dot = Green)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Pack temp*", s.packTempMaxC?.toString() ?: "–", "°C", color = tempColor(s.packTempMaxC),
                    sub = s.packTempMinC?.let { "low $it °C" })
                Tile("Coolant in*", s.coolantInC?.toString() ?: "–", "°C", color = tempColor(s.coolantInC),
                    sub = s.coolantOutC?.let { "out $it °C" })
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Label("Cell spread")
                    Value(delta?.toString() ?: "–", "mV", size = 22.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.maxCellMv?.let { "▲ $it mV" + (s.maxCellId?.let { id -> "  #$id" } ?: "") } ?: "", color = Dim, fontSize = 12.sp, style = Num)
                    Text(s.minCellMv?.let { "▼ $it mV" + (s.minCellId?.let { id -> "  #$id" } ?: "") } ?: "", color = Dim, fontSize = 12.sp, style = Num)
                }
            }
            Spacer(Modifier.height(8.dp))
            SpreadBar(delta)
            Spacer(Modifier.height(6.dp))
            Grid(
                "Health (SoH)" to (s.sohPct?.let { "${it.roundToInt()} %" } ?: "–"),
                "Capacity (calc.)" to (st.capacityKwh?.let { "%.1f kWh".format(it) } ?: "at ≥80 % SoC"),
                "12 V battery" to (s.aux12V?.let { "%.1f V".format(it) } ?: "–"),
                "Pack range" to (s.packTempMinC?.let { lo -> "$lo–${s.packTempMaxC ?: "–"} °C" } ?: "–"),
            )
            Text("* Temperatures unconfirmed until checked against a cold car.", color = Faint, fontSize = 11.sp)
            if (delta != null && delta > 50) {
                Spacer(Modifier.height(6.dp))
                Text("Cell spread above 50 mV — worth watching. LFP spread reads largest near empty and full.", color = Amber, fontSize = 12.sp)
            }
        }

        if (showHousekeeping) {
            RunsPanel(st)
            Expander("Cells", "All 98 cell voltages and which ones lead or lag") {
                AllCellsPanel(st.sample)
                CellWatchPanel()
            }
            Expander("Odometer & logs", "Car odometer, local CSV logs, research capture") {
                OdometerPanel(st)
                LogsPanel()
            }
        }

        Text(
            "Battery and cell figures are the BMS's own readings, shown as reported. " +
                "Only range and charge time are estimates (marked est.).",
            color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
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
        st.offlineDriveKwh?.let { kwh ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "The battery used %.1f kWh (≈ %.0f km) while the app wasn't connected — update the odometer?"
                        .format(kwh, kwh * 1000 / (st.effWhKm ?: 130.0)),
                    color = Amber, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { Dash.dismissOfflineDrive() }) { Text("Dismiss") }
            }
        }
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
        var alerts by remember { mutableStateOf(com.spk.windsordash.data.Alerts.enabled) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Charging alerts", color = Color.White, fontSize = 14.sp)
                Text("Target reached, charge finished, car stopped answering mid-charge.", color = Dim, fontSize = 12.sp)
            }
            Switch(checked = alerts, onCheckedChange = { alerts = it; com.spk.windsordash.data.Alerts.setEnabled(it) })
        }
        var research by remember { mutableStateOf(LocalLog.research) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Research capture", color = Color.White, fontSize = 14.sp)
                Text(
                    "Also records raw battery-computer bytes to raw-<date>.csv, to find the real pack " +
                        "temperature. While driving it also listens to the car's own broadcast traffic (to find the odometer). " +
                        "Switch on before a DC charge or a long drive.",
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


/* ---------------- auto-connect ---------------- */

@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun AutoConnectLine() {
    val ctx = LocalContext.current
    val act = ctx as? MainActivity ?: return
    var name by remember { mutableStateOf(act.autoConnectName()) }
    var picking by remember { mutableStateOf(false) }
    Text(
        if (name != null) "Auto-connect: when “$name” connects  ·  change" else "Auto-connect: off  ·  set up",
        color = Teal, fontSize = 13.sp, modifier = Modifier.clickable { picking = true }
    )
    if (picking) {
        val bonded = remember {
            runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().toList() }
                .getOrDefault(emptyList()).sortedBy { it.name ?: it.address }
        }
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Card,
            title = { Text("Auto-connect") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Pick your car's Bluetooth — the one calls / Android Auto use. When the car connects to " +
                            "your phone, Windsor Dash starts and connects to the OBD dongle by itself. " +
                            "(A Bluetooth LE dongle can be picked instead.) Android will ask you to confirm.",
                        color = Dim, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    for (d in bonded) {
                        val n = d.name ?: d.address
                        Column(Modifier.fillMaxWidth().clickable {
                            picking = false
                            act.setupAutoConnect(d.address, n)
                        }.padding(vertical = 10.dp)) {
                            Text(n, color = Color.White)
                            Text(d.address, color = Dim, fontSize = 11.sp)
                        }
                    }
                    if (bonded.isEmpty()) Text("No paired Bluetooth devices found.", color = Amber)
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            dismissButton = {
                if (name != null) TextButton(onClick = { act.turnOffAutoConnect(); name = null; picking = false }) {
                    Text("Turn off", color = Red)
                }
            },
        )
    }
    LaunchedEffect(picking) { if (!picking) { delay(1500); name = act.autoConnectName() } }
}

/* ---------------- all 98 cells ---------------- */

@Composable
private fun AllCellsPanel(s: com.spk.windsordash.obd.Sample) {
    val cells = s.cellsMv
    val at = s.cellsAt
    Panel {
        Text("All 98 cells", color = Dim, fontSize = 13.sp)
        if (cells == null || at == null) {
            Text("Listening for the battery's cell broadcast… (needs the car on; a few adapters can't listen).", color = Dim, fontSize = 12.sp)
            return@Panel
        }
        val mx = cells.max(); val mn = cells.min()
        val hi = cells.indexOf(mx) + 1; val lo = cells.indexOf(mn) + 1
        val avg = cells.average()
        val ageS = (System.currentTimeMillis() - at) / 1000
        Text(
            "Spread ${mx - mn} mV  ·  high #$hi $mx  ·  low #$lo $mn  ·  avg ${avg.roundToInt()} mV",
            color = Color.White, fontSize = 13.sp
        )
        val top = mx + 2; val bottom = mn - 2
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 6.dp)) {
            val w = size.width / cells.size
            val range = (top - bottom).coerceAtLeast(4).toFloat()
            cells.forEachIndexed { i, mv ->
                val h = (mv - bottom) / range * size.height
                drawRect(
                    color = when (mv) { mx -> Amber; mn -> Red; else -> Teal },
                    topLeft = androidx.compose.ui.geometry.Offset(i * w + w * 0.1f, size.height - h),
                    size = androidx.compose.ui.geometry.Size(w * 0.8f, h),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("#1", color = Dim, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("$bottom–$top mV scale", color = Dim, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("#98", color = Dim, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Text(
            "Measured: the car broadcasts every cell; nothing estimated. " +
                (if (ageS < 90) "Updated ${ageS}s ago." else "Last heard ${ageS / 60} min ago."),
            color = Dim, fontSize = 11.sp
        )
    }
}

/* ---------------- cell watch ---------------- */

@Composable
private fun CellWatchPanel() {
    val snap by com.spk.windsordash.data.CellWatch.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cell watch", color = Dim, fontSize = 13.sp)
                Text(
                    if (snap.since > 0) "Which cells the battery reports as lowest / highest, since " +
                        java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(snap.since))
                    else "Fills in as you drive and charge",
                    color = Dim, fontSize = 11.sp
                )
            }
            TextButton(onClick = { confirm = true }) { Text("Reset") }
        }
        for (c in com.spk.windsordash.data.CellWatch.Cond.entries) {
            val top = snap.top(c)
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(c.label, color = Color.White, fontSize = 13.sp)
                Text(
                    if (top.isEmpty()) "no readings yet"
                    else top.joinToString("   ") { (id, pct) -> "#$id ${pct.roundToInt()}%" } + "   (${snap.total(c)} readings)",
                    color = if (top.firstOrNull()?.second ?: 0.0 >= 50) Amber else Dim, fontSize = 12.sp
                )
            }
        }
        val full by com.spk.windsordash.data.CellWatch.fullHistory.collectAsStateWithLifecycle()
        Text("Spread at full charge (at rest)", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (full.isEmpty()) Text("Recorded after your next charge to 99–100 % (dongle connected before you drive off).", color = Dim, fontSize = 12.sp)
        for (f in full.takeLast(8).reversed()) Text(
            java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.at)) +
                "  ·  ${f.spreadMv} mV  ·  high #${f.maxId ?: "?"} ${f.maxMv}  ·  low #${f.minId ?: "?"} ${f.minMv}",
            color = Dim, fontSize = 12.sp
        )
        if (full.size >= 3) {
            val first = full.first().spreadMv; val last = full.last().spreadMv
            Text(
                when {
                    last < first * 0.8 -> "Shrinking ($first → $last mV): balancing is evening the cells out."
                    last > first * 1.2 -> "Growing ($first → $last mV): worth keeping an eye on."
                    else -> "Steady (about $last mV)."
                }, color = Teal, fontSize = 12.sp
            )
        }
        Text(
            "A cell that's lowest most of the time under load, or highest when full, is the one to watch over months. " +
                "Small spreads (under ~20 mV) make this a curiosity, not a problem.",
            color = Dim, fontSize = 11.sp
        )
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Card,
        title = { Text("Reset cell watch?") },
        confirmButton = { TextButton(onClick = { com.spk.windsordash.data.CellWatch.reset(); confirm = false }) { Text("Reset", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
