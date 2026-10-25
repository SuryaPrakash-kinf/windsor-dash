package com.spk.windsordash.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChargerType(val label: String) {
    HOME_AC("Home AC"), PUBLIC_AC("Public AC"), DC_FAST("DC fast"), UNKNOWN("Not set");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: UNKNOWN
    }
}

/**
 * One charging session. Measured fields come from the car (auto sessions); the rest
 * (type, billed kWh, cost, station, notes) are yours to fill in.
 */
data class ChargeEntry(
    val id: Long,                    // start time in ms (unique enough)
    val start: Long,
    val end: Long?,
    val type: ChargerType = ChargerType.UNKNOWN,
    val socStart: Int? = null,
    val socEnd: Int? = null,
    val kwhBattery: Double? = null,  // measured going into the pack (auto sessions)
    val peakKw: Double? = null,
    val kwhBilled: Double? = null,   // from the charger / app / meter
    val costInr: Double? = null,
    val station: String = "",
    val odometerKm: Int? = null,
    val notes: String = "",
    val auto: Boolean = false,
) {
    val minutes: Long? get() = end?.let { (it - start) / 60_000 }
    val socAdded: Int? get() = if (socStart != null && socEnd != null) socEnd - socStart else null
    val inrPerKwh: Double? get() = if (costInr != null && (kwhBilled ?: kwhBattery ?: 0.0) > 0) costInr / (kwhBilled ?: kwhBattery!!) else null
    /** Share of billed energy that reached the battery. */
    val efficiencyPct: Double? get() = if (kwhBattery != null && kwhBilled != null && kwhBilled > 0) kwhBattery / kwhBilled * 100 else null
}

/** charges.csv in the log folder (or app storage). Small: read and rewritten whole. */
object ChargeLog {
    private const val FILE = "charges.csv"
    private const val HEADER = "id,start,end,type,soc_start,soc_end,kwh_battery,peak_kw,kwh_billed,cost_inr,station,odometer_km,notes,source"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun load(): List<ChargeEntry> {
        val text = LocalLog.readText(FILE) ?: return emptyList()
        return text.lines().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val c = parseCsvLine(line)
                ChargeEntry(
                    id = c[0].toLong(),
                    start = fmt.parse(c[1])!!.time,
                    end = c[2].takeIf { it.isNotBlank() }?.let { fmt.parse(it)!!.time },
                    type = ChargerType.of(c[3]),
                    socStart = c[4].toIntOrNull(), socEnd = c[5].toIntOrNull(),
                    kwhBattery = c[6].toDoubleOrNull(), peakKw = c[7].toDoubleOrNull(),
                    kwhBilled = c[8].toDoubleOrNull(), costInr = c[9].toDoubleOrNull(),
                    station = c[10], odometerKm = c[11].toIntOrNull(), notes = c[12],
                    auto = c.getOrNull(13) == "auto",
                )
            }.getOrNull()
        }.sortedByDescending { it.start }
    }

    private fun save(list: List<ChargeEntry>) {
        val sb = StringBuilder(HEADER).append('\n')
        for (e in list.sortedBy { it.start }) {
            sb.append(
                listOf(
                    e.id.toString(), fmt.format(Date(e.start)), e.end?.let { fmt.format(Date(it)) } ?: "",
                    e.type.name, e.socStart?.toString() ?: "", e.socEnd?.toString() ?: "",
                    e.kwhBattery?.let { "%.2f".format(Locale.US, it) } ?: "", e.peakKw?.let { "%.1f".format(Locale.US, it) } ?: "",
                    e.kwhBilled?.let { "%.2f".format(Locale.US, it) } ?: "", e.costInr?.let { "%.2f".format(Locale.US, it) } ?: "",
                    e.station, e.odometerKm?.toString() ?: "", e.notes, if (e.auto) "auto" else "manual",
                ).joinToString(",") { csv(it) }
            ).append('\n')
        }
        LocalLog.writeText(FILE, sb.toString())
    }

    fun upsert(e: ChargeEntry) = save(load().filterNot { it.id == e.id } + e)

    fun delete(id: Long) = save(load().filterNot { it.id == id })

    /** Called when the app detects a finished charge. */
    fun addAuto(s: ChargeSession, odometerKm: Int?) {
        val e = ChargeEntry(
            id = s.startedAt, start = s.startedAt, end = s.endedAt,
            type = if (s.peakKw >= 12) ChargerType.DC_FAST else ChargerType.UNKNOWN,
            socStart = s.startSoc?.toInt(), socEnd = s.endSoc?.toInt(),
            kwhBattery = s.addedKwh, peakKw = s.peakKw, odometerKm = odometerKm, auto = true,
            notes = s.gapNote ?: "",
        )
        // Keep anything you already typed for this session.
        val old = load().firstOrNull { it.id == e.id }
        upsert(
            if (old == null) e else e.copy(
                type = if (old.type != ChargerType.UNKNOWN) old.type else e.type,
                kwhBilled = old.kwhBilled,
                // Home charging at your tariff: keep the cost in step with the (possibly merged) kWh.
                costInr = if (old.type == ChargerType.HOME_AC && old.kwhBilled == null && old.costInr != null)
                    (Dash.homeTariff ?: 0.0) * e.kwhBattery!! else old.costInr,
                station = old.station,
                notes = old.notes.ifBlank { e.notes },
            )
        )
    }

    private fun csv(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\"" else s

    fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
