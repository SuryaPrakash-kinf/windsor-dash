package com.spk.tfholdings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spk.tfholdings.data.Fmt
import com.spk.tfholdings.data.Group
import com.spk.tfholdings.data.Portfolio
import com.spk.tfholdings.data.Row as HRow
import com.spk.tfholdings.data.Snapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Dark, calm, number-first. One blue accent; green/red only for gains and losses.
 * Big tabular numbers, small spaced labels.
 */
internal val Bg = Color(0xFF0A0F16)
internal val Card = Color(0xFF111826)
internal val Line = Color(0xFF1E293B)
internal val Ink = Color(0xFFE5E7EB)
internal val Muted = Color(0xFF8A99AD)
internal val Accent = Color(0xFF3B82F6)
internal val Up = Color(0xFF22C55E)
internal val Down = Color(0xFFF05252)
internal val Warn = Color(0xFFF59E0B)

private val Num = TextStyle(fontFeatureSettings = "tnum")

private fun tone(x: Double?) = when {
    x == null -> Muted
    x > 0.0005 -> Up
    x < -0.0005 -> Down
    else -> Muted
}

data class UiState(
    val snapshot: Snapshot? = null,
    val portfolio: Portfolio? = null,
    val refreshing: Boolean = false,
    val pricesAt: Long? = null,
    val message: String? = null,
)

@Composable
fun Screen(st: UiState, onRefresh: () -> Unit, onImport: () -> Unit) {
    val p = st.portfolio
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(st, onRefresh, onImport) }
        st.message?.let { msg -> item { Banner(msg, Warn) } }
        if (p == null || st.snapshot == null) {
            item { Empty(onImport) }
            return@LazyColumn
        }
        item { Hero(p) }
        item { NetWorth(p) }
        item { Status(st, p) }
        p.groups.forEach { g ->
            item(key = "h-" + g.name) { GroupHeader(g) }
            items(g.rows, key = { "r-" + g.name + it.h.ticker + it.h.broker }) { r -> HoldingRow(r) }
        }
        if (p.others.isNotEmpty()) {
            item { SectionTitle("Other assets", Fmt.compact(p.othersTotal)) }
            item { OthersCard(p) }
        }
        if (p.cash.isNotEmpty()) {
            item { SectionTitle("Cash", Fmt.compact(p.cashTotal)) }
            item { CashCard(p) }
        }
        item {
            Text(
                "Prices from Yahoo Finance (NSE/BSE) and mfapi.in (fund NAVs); they may be delayed. " +
                    "Other assets and cash are as of the export. Information only, not investment advice.",
                color = Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TopBar(st: UiState, onRefresh: () -> Unit, onImport: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("TF Holdings", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("TechnoFunda portfolio", color = Muted, fontSize = 12.sp)
        }
        Chip(if (st.refreshing) "Updating…" else "↻ Refresh", primary = true, enabled = !st.refreshing, onClick = onRefresh)
        Spacer(Modifier.width(8.dp))
        Chip("Import", primary = false, enabled = true, onClick = onImport)
    }
}

@Composable
private fun Chip(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (primary) Accent.copy(alpha = if (enabled) 0.18f else 0.08f) else Card
    Text(
        text,
        color = if (primary) Color(0xFF93C5FD) else Ink,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (primary) Accent.copy(alpha = 0.45f) else Line, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun Label(text: String) =
    Text(text.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Hero(p: Portfolio) = CardBox {
    Label("Stocks & funds · live")
    Text(Fmt.inr(p.holdingsValue), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Light, style = Num)
    Spacer(Modifier.height(6.dp))
    Row {
        Column(Modifier.weight(1f)) {
            Label("Today")
            Text(
                "${Fmt.inr(p.dayChange, sign = true)}  ${Fmt.pct(p.dayPct)}",
                color = tone(p.dayChange), fontSize = 15.sp, style = Num,
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Label("Unrealised P&L")
            Text(
                "${Fmt.compact(p.pnl, sign = true)}  ${Fmt.pct(p.pnlPct)}",
                color = tone(p.pnl), fontSize = 15.sp, style = Num,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("Invested ${Fmt.compact(p.invested)}", color = Muted, fontSize = 12.sp, style = Num)
}

@Composable
private fun NetWorth(p: Portfolio) = CardBox {
    Label("Net worth in the app")
    Text(Fmt.compact(p.netWorth), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Light, style = Num)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Stat("Stocks & funds", Fmt.compact(p.holdingsValue), Modifier.weight(1f))
        Stat("Cash", Fmt.compact(p.cashTotal), Modifier.weight(1f))
        Stat("Other assets", Fmt.compact(p.othersTotal), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label, color = Muted, fontSize = 11.sp)
    Text(value, color = Ink, fontSize = 14.sp, style = Num)
}

@Composable
private fun Status(st: UiState, p: Portfolio) {
    val snap = st.snapshot ?: return
    val prices = st.pricesAt?.let { SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).format(Date(it)) } ?: "not yet"
    Column {
        Text("Prices $prices · holdings exported ${Fmt.when_(snap.generatedAt)}", color = Muted, fontSize = 12.sp)
        if (p.missing.isNotEmpty() && st.pricesAt != null) {
            Text(
                "No live price for ${p.missing.joinToString(", ")}: using the export price.",
                color = Warn, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Banner(text: String, colour: Color) = Text(
    text,
    color = colour,
    fontSize = 13.sp,
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(colour.copy(alpha = 0.10f))
        .padding(12.dp),
)

@Composable
private fun SectionTitle(name: String, value: String) = Row(
    Modifier.fillMaxWidth().padding(top = 8.dp),
    verticalAlignment = Alignment.Bottom,
) {
    Text(name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    Text(value, color = Ink, fontSize = 15.sp, style = Num)
}

@Composable
private fun GroupHeader(g: Group) = Column(Modifier.padding(top = 8.dp)) {
    SectionTitle(g.name, Fmt.compact(g.value))
    Row(Modifier.fillMaxWidth()) {
        Text("${g.rows.size} holding${if (g.rows.size == 1) "" else "s"}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            "Today ${Fmt.inr(g.dayChange, sign = true)} ${Fmt.pct(g.dayPct)}",
            color = tone(g.dayChange), fontSize = 12.sp, style = Num,
        )
    }
}

@Composable
private fun HoldingRow(r: HRow) {
    var open by rememberSaveable(r.h.ticker + r.h.broker) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.h.ticker, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    r.h.name, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.inr(r.value), color = Ink, fontSize = 15.sp, style = Num)
                Row {
                    Text(Fmt.pct(r.dayPct), color = tone(r.dayPct), fontSize = 12.sp, style = Num)
                    Text("  ·  ", color = Muted, fontSize = 12.sp)
                    Text("P&L " + Fmt.pct(r.pnlPct), color = tone(r.pnl), fontSize = 12.sp, style = Num)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${Fmt.qty(r.h.qty)} × ${Fmt.price(r.price)}${if (r.live) "" else " (export)"} · ${r.h.broker}",
            color = Muted, fontSize = 12.sp, style = Num,
        )
        if (open) {
            Spacer(Modifier.height(8.dp))
            Detail("Average cost", Fmt.price(r.h.avgCost))
            Detail("Invested", Fmt.inr(r.h.invested))
            Detail("Unrealised P&L", Fmt.inr(r.pnl, sign = true))
            r.dayChange?.let { Detail("Today", Fmt.inr(it, sign = true)) }
            if (r.illiquid) Detail("Today", "not counted (SGBs trade rarely)")
            if (r.h.tranche.isNotBlank()) Detail("Tranche", r.h.tranche)
            if (r.h.stop > 0) Detail("Stop (GTT)", Fmt.price(r.h.stop))
            if (r.h.sector.isNotBlank()) Detail("Sector", r.h.sector)
        }
    }
}

@Composable
private fun Detail(k: String, v: String) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
    Text(v, color = Ink, fontSize = 13.sp, style = Num, textAlign = TextAlign.End)
}

@Composable
private fun OthersCard(p: Portfolio) = CardBox {
    p.others.forEachIndexed { i, o ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(o.assetClass.ifBlank { null }, o.maturityDate?.let { "matures $it" })
                Text(sub.joinToString(" · "), color = Muted, fontSize = 12.sp)
            }
            Text(Fmt.compact(o.value), color = Ink, fontSize = 14.sp, style = Num)
        }
    }
}

@Composable
private fun CashCard(p: Portfolio) = CardBox {
    p.cash.forEach { c -> Detail(c.broker, Fmt.inr(c.amount)) }
}

@Composable
private fun Empty(onImport: () -> Unit) = CardBox {
    Text("No holdings yet", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(
        "1. In TechnoFunda on the laptop, open Holdings & overview and press Export for phone.\n" +
            "2. Send tf_holdings.json to this phone (Google Drive, WhatsApp to yourself, e-mail or USB).\n" +
            "3. Open it with TF Holdings, or press Import below.\n\n" +
            "Prices then update live here. Export again after you trade.",
        color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
    )
    Spacer(Modifier.height(12.dp))
    Chip("Import tf_holdings.json", primary = true, enabled = true, onClick = onImport)
}
