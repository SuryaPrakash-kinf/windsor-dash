package com.spk.windsordash.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.spk.windsordash.data.ChargeEntry
import com.spk.windsordash.data.ChargeLog
import com.spk.windsordash.data.ChargerType
import com.spk.windsordash.data.Dash
import com.spk.windsordash.data.LocalLog
import com.spk.windsordash.data.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

/* ------------------------------------------------------------------ */
/* Report: HTML page built from runs.csv + charges.csv                 */
/* ------------------------------------------------------------------ */

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var html by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { html = withContext(Dispatchers.IO) { Report.html() } }
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 8.dp)) {
        PageHeader("Report", onBack) {
            TextButton(onClick = {
                val h = html ?: return@TextButton
                val uri = LocalLog.saveReport(ctx, h)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/html"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share report"))
            }) { Text("Share") }
            TextButton(onClick = {
                val h = html ?: return@TextButton
                val uri = LocalLog.saveReport(ctx, h)
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { Toast.makeText(ctx, "No browser found — use Share", Toast.LENGTH_SHORT).show() }
            }) { Text("Browser") }
        }
        val h = html
        if (h == null) Text("Building report…", color = Dim, modifier = Modifier.padding(16.dp))
        else AndroidView(
            factory = { c -> WebView(c).apply { settings.javaScriptEnabled = false; setBackgroundColor(0xFF0B0F14.toInt()) } },
            update = { it.loadDataWithBaseURL(null, h, "text/html", "utf-8", null) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/* ------------------------------------------------------------------ */
/* Charging log                                                        */
/* ------------------------------------------------------------------ */

private val listFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
private val editFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

@Composable
fun ChargesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ChargeEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<ChargeEntry?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { list = withContext(Dispatchers.IO) { ChargeLog.load() } }

    val e = editing
    if (e != null) {
        ChargeEditor(
            entry = e,
            onCancel = { editing = null },
            onSave = { saved -> scope.launch { withContext(Dispatchers.IO) { ChargeLog.upsert(saved) }; editing = null; reload++ } },
            onDelete = { id -> scope.launch { withContext(Dispatchers.IO) { ChargeLog.delete(id) }; editing = null; reload++ } },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader("Charging log", onBack) {
            Button(onClick = {
                val now = System.currentTimeMillis()
                val st = Dash.state.value
                editing = ChargeEntry(
                    id = now, start = now, end = null,
                    socEnd = st.sample.socPct?.toInt(), odometerKm = st.odoKm?.toInt(),
                )
            }) { Text("+ Add") }
        }
        val paid = list.sumOf { it.costInr ?: 0.0 }
        val kwh = list.sumOf { if (it.costInr != null) (it.kwhBilled ?: it.kwhBattery ?: 0.0) else 0.0 }
        Text(
            "${list.size} charges · ₹%.0f spent".format(paid) + (if (kwh > 0) " · avg ₹%.2f/kWh".format(paid / kwh) else ""),
            color = Dim, fontSize = 13.sp
        )
        Text(
            "Charges the app detects are added automatically with what the car measured. Tap one to add the cost, " +
                "charger type and station. Use + Add for charges when the dongle wasn't connected.",
            color = Dim, fontSize = 12.sp
        )
        for (c in list) {
            Panel {
                Column(Modifier.fillMaxWidth().clickable { editing = c }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listFmt.format(Date(c.start)), color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(if (c.auto) "measured" else "manual", color = if (c.auto) Green else Dim, fontSize = 11.sp)
                    }
                    Text(
                        listOfNotNull(
                            c.type.takeIf { it != ChargerType.UNKNOWN }?.label,
                            if (c.socStart != null || c.socEnd != null) "${c.socStart ?: "–"}→${c.socEnd ?: "–"}%" else null,
                            c.kwhBattery?.let { "%.1f kWh in".format(it) },
                            c.kwhBilled?.let { "%.1f kWh billed".format(it) },
                            c.costInr?.let { "₹%.0f".format(it) },
                            c.inrPerKwh?.let { "₹%.2f/kWh".format(it) },
                            c.minutes?.let { minutes(it) },
                        ).joinToString(" · "),
                        color = Dim, fontSize = 13.sp
                    )
                    if (c.station.isNotBlank()) Text(c.station, color = Dim, fontSize = 12.sp)
                    if (c.auto && c.costInr == null) Text("Tap to add cost", color = Amber, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ChargeEditor(entry: ChargeEntry, onCancel: () -> Unit, onSave: (ChargeEntry) -> Unit, onDelete: (Long) -> Unit) {
    val ctx = LocalContext.current
    var start by remember { mutableStateOf(editFmt.format(Date(entry.start))) }
    var end by remember { mutableStateOf(entry.end?.let { editFmt.format(Date(it)) } ?: "") }
    var type by remember { mutableStateOf(entry.type) }
    var socStart by remember { mutableStateOf(entry.socStart?.toString() ?: "") }
    var socEnd by remember { mutableStateOf(entry.socEnd?.toString() ?: "") }
    var billed by remember { mutableStateOf(entry.kwhBilled?.let { "%.2f".format(Locale.US, it) } ?: "") }
    var cost by remember { mutableStateOf(entry.costInr?.let { "%.0f".format(Locale.US, it) } ?: "") }
    var station by remember { mutableStateOf(entry.station) }
    var odo by remember { mutableStateOf(entry.odometerKm?.toString() ?: "") }
    var notes by remember { mutableStateOf(entry.notes) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exists = remember { ChargeLog.load().any { it.id == entry.id } }

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader(if (exists) "Edit charge" else "Add charge", onCancel)

        if (entry.auto) Panel {
            Text("Measured by the car", color = Green, fontSize = 13.sp)
            Text(
                listOfNotNull(
                    entry.kwhBattery?.let { "%.2f kWh into the battery".format(it) },
                    entry.peakKw?.let { "peak %.1f kW".format(it) },
                    entry.minutes?.let { minutes(it) },
                ).joinToString(" · "),
                color = Color.White, fontSize = 14.sp
            )
        }

        Text("Charger", color = Dim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in listOf(ChargerType.HOME_AC, ChargerType.PUBLIC_AC, ChargerType.DC_FAST))
                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
        }
        Field("Start (yyyy-MM-dd HH:mm)", start) { start = it }
        Field("End (optional)", end) { end = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box1 { Field("SoC start %", socStart, number = true) { socStart = it.filter(Char::isDigit).take(3) } }
            Box1 { Field("SoC end %", socEnd, number = true) { socEnd = it.filter(Char::isDigit).take(3) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box1 { Field("kWh billed", billed, number = true) { billed = it.filter { c -> c.isDigit() || c == '.' }.take(7) } }
            Box1 { Field("Cost ₹", cost, number = true) { cost = it.filter { c -> c.isDigit() || c == '.' }.take(7) } }
        }
        Field("Station / place", station) { station = it.take(60) }
        Field("Odometer km", odo, number = true) { odo = it.filter(Char::isDigit).take(7) }
        Field("Notes", notes) { notes = it.take(200) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val s = runCatching { editFmt.parse(start)!!.time }.getOrNull()
                if (s == null) { Toast.makeText(ctx, "Start time format: 2026-10-22 18:30", Toast.LENGTH_LONG).show(); return@Button }
                val en = end.takeIf { it.isNotBlank() }?.let { runCatching { editFmt.parse(it)!!.time }.getOrNull() }
                onSave(
                    entry.copy(
                        start = s, end = en ?: entry.end, type = type,
                        socStart = socStart.toIntOrNull()?.coerceIn(0, 100), socEnd = socEnd.toIntOrNull()?.coerceIn(0, 100),
                        kwhBilled = billed.toDoubleOrNull(), costInr = cost.toDoubleOrNull(),
                        station = station.trim(), odometerKm = odo.toIntOrNull(), notes = notes.trim(),
                    )
                )
            }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            if (exists) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Red) }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Card,
        title = { Text("Delete this charge?") },
        confirmButton = { TextButton(onClick = { onDelete(entry.id) }) { Text("Delete", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Box1(content: @Composable () -> Unit) {
    Column(Modifier.weight(1f)) { content() }
}

@Composable
private fun Field(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    )
}
