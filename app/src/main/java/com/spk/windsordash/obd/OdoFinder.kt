package com.spk.windsordash.obd

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Where the car keeps its odometer, once found. */
data class OdoSpec(
    val tx: String,          // request CAN id ("7DF" = standard OBD broadcast)
    val rx: String,          // response CAN id
    val did: String,         // UDS DID, or "01A6" for standard OBD
    val need: Int,           // bytes to read after the DID echo
    val offset: Int,
    val width: Int,
    val divisor: Int,
    val little: Boolean = false,
) {
    val isObd get() = did == "01A6"

    fun encode() = listOf(tx, rx, did, need, offset, width, divisor, if (little) 1 else 0).joinToString("|")

    fun decode(p: IntArray): Double? {
        if (p.size < offset + width) return null
        var v = 0L
        for (i in 0 until width) {
            val b = p[offset + (if (little) width - 1 - i else i)].toLong()
            v = (v shl 8) or b
        }
        return v.toDouble() / divisor
    }

    fun describe() = if (isObd) "standard OBD odometer (01 A6)"
    else "ECU $tx→$rx, DID $did, bytes ${offset + 1}–${offset + width}" +
        (if (divisor != 1) " ÷$divisor" else "") + (if (little) " (little-endian)" else "")

    companion object {
        fun decodeSpec(s: String?): OdoSpec? {
            val p = s?.split('|') ?: return null
            if (p.size < 8) return null
            return runCatching {
                OdoSpec(p[0], p[1], p[2], p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6].toInt(), p[7] == "1")
            }.getOrNull()
        }

        /** Existing guess shared with EVDS (copied from the MG ZS EV). Tried until it fails. */
        val ZS_EV_GUESS = OdoSpec("744", "74C", "B921", 3, 0, 3, 1)
    }
}

/**
 * A value that matched the odometer once. It's only trusted after it has gone up by the same
 * distance GPS measured (so a coincidental number can't be mistaken for the odometer).
 */
data class OdoMatch(
    val spec: OdoSpec,
    val valueKm: Double,          // value when first matched
    val atOdoKm: Double,          // app odometer (dashboard + GPS) at that moment
    val latestKm: Double = valueKm,
    val latestOdoKm: Double = atOdoKm,
    val confirmed: Boolean = false,
) {
    val diff get() = abs(valueKm - atOdoKm)
    val strong get() = spec.width >= 3 && diff <= 2
    fun encode() = listOf(spec.encode().replace('|', ';'), valueKm, atOdoKm, latestKm, latestOdoKm, if (confirmed) 1 else 0).joinToString("|")

    companion object {
        fun decode(s: String): OdoMatch? = runCatching {
            val p = s.split('|')
            OdoMatch(OdoSpec.decodeSpec(p[0].replace(';', '|'))!!, p[1].toDouble(), p[2].toDouble(),
                p[3].toDouble(), p[4].toDouble(), p[5] == "1")
        }.getOrNull()
    }
}

data class FinderState(
    val running: Boolean = false,
    val text: String = "",
    val progress: Float = 0f,
    val ecus: List<String> = emptyList(),
    val matches: List<OdoMatch> = emptyList(),
    val finished: Boolean = false,
)

/**
 * Background odometer search. Runs a few requests per poll cycle, so live readings continue,
 * and keeps its progress across connections. Read-only: UDS 0x3E (tester present),
 * 0x22 (read data) and OBD mode 01 only.
 *
 * Stages: 0 standard OBD 01A6 · 1 find ECUs · 2 likely DIDs on every ECU · 3 full DID ranges.
 */
object OdoFinder {
    private val _state = MutableStateFlow(FinderState())
    val state: StateFlow<FinderState> = _state
    private lateinit var prefs: SharedPreferences

    private val DID_RANGES: List<IntRange> = listOf(
        0xDD00..0xDDFF,   // common "vehicle info" block on GM / SAIC-GM-Wuling ECUs
        0xB900..0xB9FF,   // MG/SAIC ZS-era cluster block (EVDS's B921)
        0xE100..0xE1FF,
        0xD000..0xD0FF,
        0x0100..0x01FF,
        0xF100..0xF1FF,
    )
    private val QUICK_DIDS = listOf(0xDD01, 0xB921, 0xE101, 0xF1A6, 0x0101, 0x2001, 0xD001, 0xA001, 0xF190, 0x0100)
    private val ALL_DIDS: List<Int> by lazy { DID_RANGES.flatMap { it.toList() }.filterNot { it in QUICK_DIDS } }
    private val DISCOVER_IDS = (0x700..0x7F7).filter { it != 0x7DF && it !in 0x7E8..0x7EF }

    private const val PER_STEP_DIDS = 8
    private const val PER_STEP_IDS = 4

    // persisted progress
    private var active = false
    private var stage = 0
    private var discIdx = 0
    private var ecus = mutableListOf<Pair<String, String>>()
    private var ecuIdx = 0
    private var didIdx = 0
    private var cands = mutableListOf<OdoMatch>()
    private var stepCount = 0L

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("odofinder", Context.MODE_PRIVATE)
        active = prefs.getBoolean("active", false)
        stage = prefs.getInt("stage", 0)
        discIdx = prefs.getInt("disc", 0)
        ecus = prefs.getString("ecus", "").orEmpty().split(',').filter { it.contains(':') }
            .map { it.substringBefore(':') to it.substringAfter(':') }.toMutableList()
        ecuIdx = prefs.getInt("ecu", 0)
        didIdx = prefs.getInt("did", 0)
        cands = prefs.getString("cands", "").orEmpty().split('\n').mapNotNull { if (it.isBlank()) null else OdoMatch.decode(it) }.toMutableList()
        publish(if (active) "Paused — continues when the car is connected" else "")
    }

    private fun save() {
        prefs.edit().putBoolean("active", active).putInt("stage", stage).putInt("disc", discIdx)
            .putString("ecus", ecus.joinToString(",") { "${it.first}:${it.second}" })
            .putInt("ecu", ecuIdx).putInt("did", didIdx)
            .putString("cands", cands.joinToString("\n") { it.encode() }).apply()
    }

    /** Start (or restart) a search. The dashboard value must already be set as the app odometer. */
    fun start() {
        active = true; stage = 0; discIdx = 0; ecus.clear(); ecuIdx = 0; didIdx = 0; cands.clear()
        save()
        publish("Starting — runs in the background while you drive or charge")
    }

    fun cancel() {
        active = false
        save()
        _state.value = _state.value.copy(
            running = false, finished = true,
            text = if (cands.isEmpty()) "Stopped. Progress is kept until you start a new search."
            else "Search stopped. Still checking ${cands.size} candidate(s) while you drive (needs GPS: open the app).",
        )
    }

    fun dismiss() { _state.value = FinderState() }

    private fun progress(): Float {
        val perEcu = QUICK_DIDS.size + ALL_DIDS.size
        return when (stage) {
            0 -> 0f
            1 -> 0.05f * discIdx / DISCOVER_IDS.size
            2 -> 0.05f + 0.05f * ecuIdx / maxOf(1, ecus.size)
            3 -> 0.10f + 0.90f * (ecuIdx * perEcu + didIdx).toFloat() / maxOf(1, ecus.size * perEcu)
            else -> 1f
        }.coerceIn(0f, 1f)
    }

    private fun publish(text: String, finished: Boolean = false) {
        _state.value = FinderState(
            running = active, text = text, progress = progress(),
            ecus = ecus.map { "${it.first}→${it.second}" },
            matches = cands.sortedWith(compareByDescending<OdoMatch> { it.confirmed }.thenByDescending { it.strong }.thenBy { it.diff }).take(10),
            finished = finished,
        )
    }

    /**
     * One small chunk of work, called between poll cycles.
     * [odoKm] = the app's odometer right now (dashboard reading + GPS since).
     * Returns a spec once one is confirmed by driving.
     */
    suspend fun step(elm: Elm327, odoKm: Double?): OdoSpec? {
        if (odoKm == null) return null
        if (!active) {
            // Search stopped, but candidates found so far keep being checked against GPS distance
            // (a few reads every ~40 cycles) until one is confirmed or all are ruled out.
            if (stage != 4 && cands.isNotEmpty() && cands.none { it.confirmed }) {
                stepCount++
                if (stepCount % 40 == 0L) {
                    try {
                        checkCandidates(elm, odoKm)?.let { return it }
                        if (cands.isEmpty()) publish("The candidate(s) didn't rise with the distance driven — not the odometer.", finished = true)
                        else publish("Checking ${cands.size} candidate(s): " + cands.first().let {
                            "+%.1f km vs GPS +%.1f km".format(it.latestKm - it.valueKm, it.latestOdoKm - it.atOdoKm)
                        }, finished = true)
                        save()
                    } finally {
                        elm.query("ATH0", 300); elm.forgetTarget()
                    }
                }
            }
            return null
        }
        stepCount++
        try {
            // Every ~40 steps, re-read candidates to see whether they track the GPS distance.
            if (cands.isNotEmpty() && stepCount % 40 == 0L) checkCandidates(elm, odoKm)?.let { return it }

            when (stage) {
                0 -> {
                    elm.query("ATSH7DF", 300); elm.query("ATCRA7E8", 300)
                    Elm327.payloadAfter(elm.query("01A6", 500), "41A6")?.let { p ->
                        if (p.size >= 4) consider(OdoSpec("7DF", "7E8", "01A6", 4, 0, 4, 1).let { it }, p.copyOf(4), odoKm)
                    }
                    stage = 1
                    publish("Looking for the car's computers…")
                }
                1 -> {
                    discover(elm)
                    if (discIdx >= DISCOVER_IDS.size) {
                        stage = 2; ecuIdx = 0
                        if (ecus.isEmpty()) {
                            active = false
                            publish("No computers answered. Try again with the car switched on (READY).", finished = true)
                        } else publish("Found ${ecus.size} computers. Checking likely places first…")
                    } else publish("Looking for the car's computers… ${ecus.size} found so far")
                }
                2 -> {
                    val (tx, rx) = ecus[ecuIdx]
                    scanDids(elm, tx, rx, QUICK_DIDS, odoKm)
                    ecuIdx++
                    if (ecuIdx >= ecus.size) { stage = 3; ecuIdx = 0; didIdx = 0 }
                    publish(status())
                }
                3 -> {
                    val (tx, rx) = ecus[ecuIdx]
                    val end = minOf(didIdx + PER_STEP_DIDS, ALL_DIDS.size)
                    scanDids(elm, tx, rx, ALL_DIDS.subList(didIdx, end), odoKm)
                    didIdx = end
                    if (didIdx >= ALL_DIDS.size) { ecuIdx++; didIdx = 0 }
                    if (ecuIdx >= ecus.size) {
                        stage = 4
                        if (cands.isEmpty()) {
                            active = false
                            publish("Searched every computer — no odometer value found. GPS keeps tracking it.", finished = true)
                        } else publish("Search done. Waiting to confirm ${cands.size} candidate(s) — keep driving.")
                    } else if (stepCount % 4 == 0L) publish(status())
                }
                else -> if (stepCount % 20 == 0L) publish("Waiting to confirm ${cands.size} candidate(s) — keep driving.")
            }
        } finally {
            elm.query("ATH0", 300)
            elm.forgetTarget()
            if (stepCount % 10 == 0L) save()
        }
        return null
    }

    private fun status(): String {
        val ecu = ecus.getOrNull(ecuIdx)?.first ?: "–"
        val pct = (progress() * 100).toInt()
        val c = if (cands.isEmpty()) "" else " · ${cands.size} candidate(s)"
        return "Searching computer $ecu (${ecuIdx + 1}/${ecus.size}) · $pct%$c"
    }

    private suspend fun discover(elm: Elm327) {
        elm.query("ATH1", 300)
        val wildcard = !elm.query("ATCRA7XX", 300).contains("?")
        if (!wildcard) { elm.query("ATCF700", 300); elm.query("ATCM700", 300) }
        val end = minOf(discIdx + PER_STEP_IDS, DISCOVER_IDS.size)
        for (id in DISCOVER_IDS.subList(discIdx, end)) {
            val tx = "%03X".format(id)
            elm.query("ATSH$tx", 200)
            val r = elm.query("3E00", 200).uppercase()
            for (line in r.split('\r', '\n').map { it.trim().replace(" ", "") }) {
                if (line.length < 7 || !Regex("^7[0-9A-F]{2}").containsMatchIn(line)) continue
                val rxId = line.substring(0, 3)
                if (rxId == tx) continue
                if ((line.contains("7E00") || line.contains("7F3E")) && ecus.none { it.first == tx }) ecus += tx to rxId
            }
        }
        discIdx = end
    }

    private suspend fun scanDids(elm: Elm327, tx: String, rx: String, dids: List<Int>, odoKm: Double) {
        elm.query("ATSH$tx", 300)
        elm.query("ATCRA$rx", 300)
        for (d in dids) {
            val did = "%04X".format(d)
            val p = Elm327.payloadAfter(elm.query("22$did", 250), "62$did") ?: continue
            if (p.isNotEmpty()) consider(OdoSpec(tx, rx, did, p.size, 0, 0, 1), p, odoKm)
        }
    }

    /** Records every reading of [p] within 5 km of the app odometer as a candidate. */
    private fun consider(base: OdoSpec, p: IntArray, odoKm: Double) {
        for (width in 2..4) for (off in 0..p.size - width) for (little in listOf(false, true)) {
            if (width == 2 && little) continue
            for (div in intArrayOf(1, 10, 100, 1000)) {
                val spec = base.copy(offset = off, width = width, divisor = div, little = little, need = p.size)
                val v = spec.decode(p) ?: continue
                if (abs(v - odoKm) <= 5 && cands.none { it.spec == spec }) cands += OdoMatch(spec, v, odoKm)
            }
        }
        if (cands.size > 30) cands = cands.sortedBy { it.diff }.take(30).toMutableList()
    }

    /** A real odometer rises with the GPS distance; a coincidence doesn't. */
    private suspend fun checkCandidates(elm: Elm327, odoKm: Double): OdoSpec? {
        val keep = mutableListOf<OdoMatch>()
        for (c in cands.toList()) {
            val v = readSpec(elm, c.spec)
            if (v == null) { keep += c; continue }
            val driven = odoKm - c.atOdoKm
            val rose = v - c.valueKm
            val upd = c.copy(latestKm = v, latestOdoKm = odoKm)
            when {
                driven < 2 -> keep += upd                                   // not enough driving yet
                abs(rose - driven) <= maxOf(1.0, driven * 0.05) -> {        // tracks GPS within 5 %
                    val ok = upd.copy(confirmed = true)
                    cands = (listOf(ok) + keep + cands.filter { it.spec != c.spec && it !in keep }).toMutableList()
                    active = false; stage = 4; save()
                    publish("Found and confirmed: ${"%.1f".format(v)} km — ${c.spec.describe()}", finished = true)
                    return c.spec
                }
                else -> {}                                                  // didn't track: drop it
            }
        }
        cands = keep
        return null
    }

    private suspend fun readSpec(elm: Elm327, spec: OdoSpec): Double? = if (spec.isObd) {
        elm.query("ATSH7DF", 300); elm.query("ATCRA${spec.rx}", 300)
        Elm327.payloadAfter(elm.query("01A6", 500), "41A6")?.let { spec.decode(it) }
    } else {
        elm.query("ATSH${spec.tx}", 300); elm.query("ATCRA${spec.rx}", 300)
        Elm327.payloadAfter(elm.query("22${spec.did}", 400), "62${spec.did}")?.let { spec.decode(it) }
    }

    val isActive get() = active
}
