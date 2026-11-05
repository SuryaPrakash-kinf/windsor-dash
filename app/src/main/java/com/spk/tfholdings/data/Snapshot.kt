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

data class PlanItem(
    val kind: String,
    val ticker: String,
    val title: String,
    val detail: String,
    val amount: Double?,
    val price: Double?,
    val broker: String,
    val confirm: String,
    val urgent: Boolean,
)

data class PlanSection(val key: String, val title: String, val items: List<PlanItem>)

/** Action plan built by the laptop agent (plan_agent.py). Information only: the phone never places orders. */
data class Plan(
    val generatedAt: String,
    val summary: String,
    val regime: String,
    val regimeDetail: String,
    val sections: List<PlanSection>,
)

data class Snapshot(
    val generatedAt: String,
    val holdings: List<Holding>,
    val cash: List<CashLine>,
    val others: List<OtherAsset>,
    val plan: Plan? = null,
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
            plan = o.optJSONObject("plan")?.let { parsePlan(it) },
        )
    }

    private fun num(o: JSONObject, k: String): Double? = if (o.isNull(k)) null else o.optDouble(k, Double.NaN).takeIf { !it.isNaN() }

    private fun parsePlan(p: JSONObject): Plan {
        val reg = p.optJSONObject("regime")
        return Plan(
            generatedAt = p.optString("generated_at", ""),
            summary = p.optString("summary", ""),
            regime = reg?.optString("state", "") ?: "",
            regimeDetail = reg?.optString("detail", "") ?: "",
            sections = objects(p.optJSONArray("sections")).map { s ->
                PlanSection(
                    key = s.optString("key"),
                    title = s.optString("title"),
                    items = objects(s.optJSONArray("items")).map { i ->
                        PlanItem(
                            kind = i.optString("kind"),
                            ticker = i.optString("ticker"),
                            title = i.optString("title"),
                            detail = i.optString("detail"),
                            amount = num(i, "amount"),
                            price = num(i, "price"),
                            broker = i.optString("broker"),
                            confirm = i.optString("confirm"),
                            urgent = i.optBoolean("urgent", false),
                        )
                    },
                )
            }.filter { it.items.isNotEmpty() },
        )
    }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
