package com.spk.windsordash.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a self-contained HTML report from runs.csv and charges.csv. Works offline. */
object Report {
    private val day = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val month = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    private val parse = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private data class RunRow(
        val start: Long, val end: Long, val socStart: Int?, val socEnd: Int?, val km: Double,
        val top: Int?, val avgMoving: Int?, val avg: Int?, val kwh: Double?, val whKm: Int?, val regen: Double?,
    ) {
        val socUsed get() = if (socStart != null && socEnd != null) socStart - socEnd else null
        val kmPerPct get() = socUsed?.takeIf { it > 0 }?.let { km / it }
    }

    private fun runs(): List<RunRow> {
        val text = LocalLog.readText("runs.csv") ?: return emptyList()
        // start,end,minutes,soc_start,soc_end,km,top_kmh,avg_moving_kmh,avg_kmh,kwh_used,wh_per_km,regen_kwh
        return text.lines().drop(1).filter { it.isNotBlank() }.mapNotNull { l ->
            runCatching {
                val c = l.split(',')
                RunRow(
                    parse.parse(c[0])!!.time, parse.parse(c[1])!!.time, c[3].toIntOrNull(), c[4].toIntOrNull(),
                    c[5].toDouble(), c[6].toIntOrNull(), c[7].toIntOrNull(), c[8].toIntOrNull(),
                    c[9].toDoubleOrNull(), c[10].toIntOrNull(), c[11].toDoubleOrNull(),
                )
            }.getOrNull()
        }.sortedByDescending { it.start }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun f(v: Double?, dp: Int = 1) = v?.let { "%.${dp}f".format(it) } ?: "–"
    private fun i(v: Int?) = v?.toString() ?: "–"
    private fun mins(m: Long?) = m?.let { if (it >= 60) "${it / 60} h ${it % 60} m" else "$it m" } ?: "–"

    fun html(): String {
        val runs = runs()
        val charges = ChargeLog.load()
        val totKm = runs.sumOf { it.km }
        val totKwh = runs.sumOf { it.kwh ?: 0.0 }
        val totSoc = runs.sumOf { it.socUsed ?: 0 }
        val totCost = charges.sumOf { it.costInr ?: 0.0 }
        val costKwh = charges.sumOf { if (it.costInr != null) (it.kwhBilled ?: it.kwhBattery ?: 0.0) else 0.0 }

        val sb = StringBuilder()
        sb.append(
            """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Windsor report</title><style>
:root{--bg:#0b0f14;--card:#141b24;--line:#232e3b;--dim:#8a97a8;--fg:#e8edf3;--teal:#2dd4bf;--amber:#fbbf24;--green:#4ade80}
@media (prefers-color-scheme: light){:root{--bg:#f6f7f9;--card:#fff;--line:#e3e7ec;--dim:#5b6676;--fg:#111827;--teal:#0f766e;--amber:#b45309;--green:#15803d}}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,-apple-system,Roboto,sans-serif;padding:16px}
h1{font-size:20px;margin:0 0 4px} h2{font-size:15px;margin:24px 0 8px;color:var(--dim);font-weight:600}
.sub{color:var(--dim);font-size:12px;margin-bottom:12px}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:8px}
.k{background:var(--card);border-radius:12px;padding:10px 12px}.k b{display:block;font-size:20px}.k span{color:var(--dim);font-size:12px}
.wrap{overflow-x:auto;background:var(--card);border-radius:12px}
table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums;white-space:nowrap}
th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:right}th{color:var(--dim);font-weight:600;font-size:12px}
td:first-child,th:first-child{text-align:left;position:sticky;left:0;background:var(--card)}
tr:last-child td{border-bottom:0}.soc{color:var(--teal);font-weight:600}.dim{color:var(--dim)}
.note{color:var(--dim);font-size:12px;margin-top:8px}
</style></head><body>"""
        )
        sb.append("<h1>Windsor EV report</h1><div class=sub>Generated ${esc(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date()))} · from your local logs</div>")

        sb.append("<div class=kpis>")
        sb.append("<div class=k><b>${f(totKm, 0)} km</b><span>${runs.size} runs</span></div>")
        sb.append("<div class=k><b>$totSoc %</b><span>SoC used driving</span></div>")
        sb.append("<div class=k><b>${if (totSoc > 0) f(totKm / totSoc) else "–"} km</b><span>per 1 % SoC</span></div>")
        sb.append("<div class=k><b>${if (totKm > 0) f(totKwh * 1000 / totKm, 0) else "–"} Wh/km</b><span>${f(totKwh)} kWh used</span></div>")
        sb.append("<div class=k><b>₹${f(totCost, 0)}</b><span>${charges.size} charges logged</span></div>")
        sb.append("<div class=k><b>${if (costKwh > 0) "₹" + f(totCost / costKwh, 2) else "–"}</b><span>avg per kWh</span></div>")
        sb.append("</div>")

        // Monthly
        val byMonth = runs.groupBy { month.format(Date(it.start)) }
        if (byMonth.isNotEmpty()) {
            sb.append("<h2>By month</h2><div class=wrap><table><tr><th>Month</th><th>Runs</th><th>km</th><th>SoC used</th><th>km / %</th><th>Wh/km</th><th>Charging ₹</th></tr>")
            for ((m, rs) in byMonth) {
                val km = rs.sumOf { it.km }; val soc = rs.sumOf { it.socUsed ?: 0 }; val kwh = rs.sumOf { it.kwh ?: 0.0 }
                val cost = charges.filter { month.format(Date(it.start)) == m }.sumOf { it.costInr ?: 0.0 }
                sb.append("<tr><td>${esc(m)}</td><td>${rs.size}</td><td>${f(km, 0)}</td><td>$soc %</td><td>${if (soc > 0) f(km / soc) else "–"}</td><td>${if (km > 0) f(kwh * 1000 / km, 0) else "–"}</td><td>${if (cost > 0) "₹" + f(cost, 0) else "–"}</td></tr>")
            }
            sb.append("</table></div>")
        }

        // Runs
        sb.append("<h2>Runs</h2>")
        if (runs.isEmpty()) sb.append("<div class=note>No runs yet — they're added when each drive ends.</div>")
        else {
            sb.append("<div class=wrap><table><tr><th>Date</th><th>Time</th><th>SoC</th><th>SoC used</th><th>km</th><th>km / %</th><th>Top</th><th>Avg moving</th><th>Avg</th><th>Wh/km</th><th>kWh</th><th>Regen</th></tr>")
            for (r in runs) {
                sb.append("<tr><td>${esc(day.format(Date(r.start)))}</td><td>${hm.format(Date(r.start))}–${hm.format(Date(r.end))}</td>")
                sb.append("<td>${i(r.socStart)}→${i(r.socEnd)} %</td><td class=soc>${r.socUsed?.let { "$it %" } ?: "–"}</td>")
                sb.append("<td>${f(r.km)}</td><td>${f(r.kmPerPct)}</td><td>${i(r.top)}</td><td>${i(r.avgMoving)}</td><td>${i(r.avg)}</td>")
                sb.append("<td>${i(r.whKm)}</td><td>${f(r.kwh, 2)}</td><td>${f(r.regen, 2)}</td></tr>")
            }
            sb.append("</table></div><div class=note>Speeds in km/h. “km / %” = distance per 1 % of battery — at your pack size 1 % ≈ 0.53 kWh.</div>")
        }

        // Charges
        sb.append("<h2>Charging</h2>")
        if (charges.isEmpty()) sb.append("<div class=note>No charges logged yet.</div>")
        else {
            sb.append("<div class=wrap><table><tr><th>Date</th><th>Type</th><th>SoC</th><th>Added</th><th>Into battery</th><th>Billed</th><th>Efficiency</th><th>Cost</th><th>₹/kWh</th><th>Peak</th><th>Time</th><th>Station</th></tr>")
            for (c in charges) {
                sb.append("<tr><td>${esc(day.format(Date(c.start)))} ${hm.format(Date(c.start))}</td><td>${esc(c.type.label)}</td>")
                sb.append("<td>${i(c.socStart)}→${i(c.socEnd)} %</td><td class=soc>${c.socAdded?.let { "+$it %" } ?: "–"}</td>")
                sb.append("<td>${c.kwhBattery?.let { f(it, 2) + " kWh" } ?: "–"}</td><td>${c.kwhBilled?.let { f(it, 2) + " kWh" } ?: "–"}</td>")
                sb.append("<td>${c.efficiencyPct?.let { f(it, 0) + " %" } ?: "–"}</td><td>${c.costInr?.let { "₹" + f(it, 0) } ?: "–"}</td>")
                sb.append("<td>${c.inrPerKwh?.let { "₹" + f(it, 2) } ?: "–"}</td><td>${c.peakKw?.let { f(it) + " kW" } ?: "–"}</td>")
                sb.append("<td>${mins(c.minutes)}</td><td class=dim>${esc(c.station)}</td></tr>")
            }
            sb.append("</table></div><div class=note>“Into battery” is measured by the car; “Billed” is what you entered from the charger or bill.</div>")
        }
        val full = CellWatch.fullHistory.value
        if (full.isNotEmpty()) {
            sb.append("<h2>Cell spread at full charge</h2><div class=wrap><table><tr><th>Date</th><th>SoC</th><th>Spread</th><th>Highest cell</th><th>Lowest cell</th></tr>")
            for (f in full.reversed()) sb.append("<tr><td>${esc(SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(f.at)))}</td><td>${f.soc} %</td><td class=soc>${f.spreadMv} mV</td><td>#${f.maxId ?: "?"} · ${f.maxMv} mV</td><td>#${f.minId ?: "?"} · ${f.minMv} mV</td></tr>")
            sb.append("</table></div><div class=note>Taken at rest at 99–100 %. If balancing works, the spread shrinks over successive full charges. Near full, LFP voltage rises steeply, so spreads of 50–150 mV here are normal and fall to a few mV once driving.</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }
}
