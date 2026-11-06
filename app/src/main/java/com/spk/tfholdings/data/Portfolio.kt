package com.spk.tfholdings.data

import java.time.LocalDate
import java.time.ZoneId

/** A holding valued at its live price (or, when no live price came back, the price in the export). */
data class Row(val h: Holding, val price: Double, val prev: Double?, val live: Boolean) {
    /** SGBs are priced by hand in TechnoFunda: the phone uses that price and shows no daily change. */
    val manual: Boolean get() = Portfolio.isManual(h.ticker)
    private val hasDay: Boolean get() = live && !manual && prev != null && prev > 0
    val value: Double get() = h.qty * price
    val dayChange: Double? get() = if (hasDay) h.qty * (price - prev!!) else null
    val dayPct: Double? get() = if (hasDay) (price / prev!! - 1) * 100 else null
    val pnl: Double get() = value - h.invested
    val pnlPct: Double? get() = if (h.invested > 0) pnl / h.invested * 100 else null
}

data class Group(val name: String, val rows: List<Row>) {
    val value: Double get() = rows.sumOf { it.value }
    val invested: Double get() = rows.sumOf { it.h.invested }
    val dayChange: Double get() = rows.sumOf { it.dayChange ?: 0.0 }
    val dayPct: Double? get() = (value - dayChange).let { base -> if (base > 0) dayChange / base * 100 else null }
    val pnl: Double get() = value - invested
}

data class Portfolio(
    val groups: List<Group>,
    val cash: List<CashLine>,
    val others: List<OtherAsset>,
    val missing: List<String>,
    val noToday: List<String> = emptyList(),
) {
    val holdingsValue: Double get() = groups.sumOf { it.value }
    val invested: Double get() = groups.sumOf { it.invested }
    val dayChange: Double get() = groups.sumOf { it.dayChange }
    val dayPct: Double? get() = (holdingsValue - dayChange).let { b -> if (b > 0) dayChange / b * 100 else null }
    val pnl: Double get() = holdingsValue - invested
    val pnlPct: Double? get() = if (invested > 0) pnl / invested * 100 else null
    val cashTotal: Double get() = cash.sumOf { it.amount }
    val othersTotal: Double get() = others.sumOf { it.value }
    val netWorth: Double get() = holdingsValue + cashTotal + othersTotal

    companion object {
        private val ORDER = listOf("Core", "Satellite", "Gold & funds")

        private fun rank(name: String) = ORDER.indexOf(name).let { if (it < 0) 99 else it }

        /** Holdings whose price you set by hand in TechnoFunda (SGBs): never fetched live. */
        fun isManual(ticker: String) = ticker.uppercase().startsWith("SGB")

        fun label(bucket: String) = when (bucket) {
            "Gold/MF" -> "Gold & funds"
            else -> bucket
        }

        /** Previous close: the phone's own (live quote); the export's only as a same-day fallback. */
        fun build(s: Snapshot, quotes: Map<String, Quote>, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))): Portfolio {
            val exportedToday = runCatching { LocalDate.parse(s.generatedAt.take(10)) == today }.getOrDefault(false)
            val rows = s.holdings.map { h ->
                val q = if (isManual(h.ticker)) null else quotes[h.ticker]
                // The phone's own previous close (Yahoo hourly bars / AMFI) matches the laptop's, so the export is only
                // needed after a trade. The export's figure is used only when Yahoo has none and it was made today.
                val prev = q?.prev ?: if (exportedToday && h.prevClose > 0) h.prevClose else null
                if (q != null) Row(h, q.price, prev, live = true)
                else Row(h, if (h.lastPrice > 0) h.lastPrice else h.avgCost, h.prevClose.takeIf { it > 0 }, live = false)
            }
            val groups = rows.groupBy { it.h.bucket.ifBlank { "Other" } }
                .map { (b, rs) -> Group(label(b), rs.sortedByDescending { it.value }) }
                .sortedWith(compareBy<Group>({ rank(it.name) }, { it.name }))
            return Portfolio(
                groups = groups,
                cash = s.cash.sortedByDescending { it.amount },
                others = s.others.sortedByDescending { it.value },
                missing = rows.filter { !it.live && !it.manual }.map { it.h.ticker }.distinct(),
                noToday = rows.filter { it.live && !it.manual && it.dayChange == null }.map { it.h.ticker }.distinct(),
            )
        }
    }
}
