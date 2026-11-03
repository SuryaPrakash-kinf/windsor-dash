package com.spk.tfholdings.data

import org.json.JSONArray
import org.json.JSONObject

/** One stock, ETF, fund or SGB line exported by the TechnoFunda laptop app ("Export for phone"). */
data class Holding(
    val ticker: String,
    val name: String,
    val bucket: String,
    val broker: String,
    val sector: String,
    val qty: Double,
    val avgCost: Double,
    val invested: Double,
    val lastPrice: Double,
    val prevClose: Double,
    val tranche: String,
    val stop: Double,
)

data class CashLine(val broker: String, val amount: Double)

data class OtherAsset(
    val name: String,
    val assetClass: String,
    val value: Double,
    val maturityDate: String?,
    val kind: String,
)

data class Snapshot(
    val generatedAt: String,
    val holdings: List<Holding>,
    val cash: List<CashLine>,
    val others: List<OtherAsset>,
)

object SnapshotParser {
    /** Parses tf_holdings.json; throws IllegalArgumentException with a readable message if it isn't one. */
    fun parse(text: String): Snapshot {
        val o = try {
            JSONObject(text.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("This file is not a TF Holdings export (not JSON).")
        }
        val hs = o.optJSONArray("holdings")
            ?: throw IllegalArgumentException("This file is not a TF Holdings export (no holdings list).")
        return Snapshot(
            generatedAt = o.optString("generated_at", ""),
            holdings = objects(hs).map {
                Holding(
                    ticker = it.optString("ticker").uppercase(),
                    name = it.optString("name", it.optString("ticker")),
                    bucket = it.optString("bucket", "Other"),
                    broker = it.optString("broker", ""),
                    sector = it.optString("sector", ""),
                    qty = it.optDouble("qty", 0.0),
                    avgCost = it.optDouble("avg_cost", 0.0),
                    invested = it.optDouble("invested", 0.0),
                    lastPrice = it.optDouble("last_price", 0.0),
                    prevClose = it.optDouble("prev_close", 0.0),
                    tranche = it.optString("tranche", ""),
                    stop = it.optDouble("stop", 0.0),
                )
            }.filter { it.ticker.isNotBlank() && it.qty > 0 },
            cash = objects(o.optJSONArray("cash")).map {
                CashLine(it.optString("broker"), it.optDouble("amount", 0.0))
            },
            others = objects(o.optJSONArray("other_assets")).map {
                OtherAsset(
                    name = it.optString("name"),
                    assetClass = it.optString("asset_class", ""),
                    value = it.optDouble("value", 0.0),
                    maturityDate = if (it.isNull("maturity_date")) null else it.optString("maturity_date").ifBlank { null },
                    kind = it.optString("kind", ""),
                )
            },
        )
    }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
