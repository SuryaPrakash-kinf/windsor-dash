package com.spk.tfholdings.data

/** A holding valued at its live price (or, when no live price came back, the price in the export). */
data class Row(val h: Holding, val price: Double, val prev: Double?, val live: Boolean) {
    /** SGBs trade rarely: their "previous close" can be days or weeks old, so a daily change would be fake. */
    val illiquid: Boolean get() = h.ticker.startsWith("SGB")
    private val hasDay: Boolean get() = live && !illiquid && prev != null && prev > 0
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

        fun label(bucket: String) = when (bucket) {
            "Gold/MF" -> "Gold & funds"
            else -> bucket
        }

        fun build(s: Snapshot, quotes: Map<String, Quote>): Portfolio {
            val rows = s.holdings.map { h ->
                val q = quotes[h.ticker]
                if (q != null) Row(h, q.price, q.prev, live = true)
                else Row(h, if (h.lastPrice > 0) h.lastPrice else h.avgCost, h.prevClose.takeIf { it > 0 }, live = false)
            }
            val groups = rows.groupBy { it.h.bucket.ifBlank { "Other" } }
                .map { (b, rs) -> Group(label(b), rs.sortedByDescending { it.value }) }
                .sortedWith(compareBy<Group>({ rank(it.name) }, { it.name }))
            return Portfolio(
                groups = groups,
                cash = s.cash.sortedByDescending { it.amount },
                others = s.others.sortedByDescending { it.value },
                missing = rows.filter { !it.live }.map { it.h.ticker }.distinct(),
            )
        }
    }
}
