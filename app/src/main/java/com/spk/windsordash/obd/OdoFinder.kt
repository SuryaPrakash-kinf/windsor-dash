package com.spk.windsordash.obd

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

data class OdoMatch(val spec: OdoSpec, val valueKm: Double, val dashKm: Int) {
    val diff get() = abs(valueKm - dashKm)
    /** 3–4 byte fields that match tightly are very unlikely to be coincidence. */
    val confident get() = spec.width >= 3 && diff <= 2
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
 * Searches the car for a value equal to the dashboard odometer.
 * Read-only: uses only UDS 0x3E (tester present), 0x22 (read data) and OBD mode 01.
 */
object OdoFinder {
    private val _state = MutableStateFlow(FinderState())
    val state: StateFlow<FinderState> = _state

    @Volatile private var pendingKm: Int? = null
    @Volatile var cancelled = false
        private set

    fun request(dashKm: Int) {
        cancelled = false
        pendingKm = dashKm
        _state.value = FinderState(running = true, text = "Starting — keep the car switched on and parked…")
    }

    fun cancel() {
        cancelled = true
        pendingKm = null
        if (_state.value.running) _state.value = _state.value.copy(running = false, finished = true, text = "Stopped")
    }

    fun dismiss() { _state.value = FinderState() }

    /** Called by the poll loop between cycles. Returns the best match (or null). */
    suspend fun runIfPending(elm: Elm327): OdoMatch? {
        val km = pendingKm ?: return null
        pendingKm = null
        return try {
            scan(elm, km)
        } finally {
            // Restore normal polling setup
            elm.query("ATH0", 300)
            elm.query("ATAT1", 300)
            elm.query("ATST32", 300)
            elm.forgetTarget()
        }
    }

    private fun set(f: FinderState.() -> FinderState) { _state.value = _state.value.f() }

    // DID ranges to try on each ECU (≈1,300 reads per ECU).
    private val DID_RANGES: List<IntRange> = listOf(
        0xDD00..0xDDFF,   // common "vehicle info" block on GM / SAIC-GM-Wuling ECUs
        0xB900..0xB9FF,   // MG/SAIC ZS-era cluster block (EVDS's B921)
        0xE100..0xE1FF,
        0xD000..0xD0FF,   // Windsor BMS uses D0xx
        0x0100..0x01FF,
    )
    private val QUICK_DIDS = listOf(0xDD01, 0xB921, 0xE101, 0xF1A6, 0x0101, 0x2001, 0xD001, 0xA001)

    private suspend fun scan(elm: Elm327, dashKm: Int): OdoMatch? {
        val matches = mutableListOf<OdoMatch>()
        fun addMatches(tx: String, rx: String, did: String, p: IntArray) {
            for (m in findIn(p, dashKm)) {
                val spec = m.copy(tx = tx, rx = rx, did = did, need = p.size)
                matches += OdoMatch(spec, spec.decode(p) ?: continue, dashKm)
            }
            if (matches.isNotEmpty()) set { copy(matches = matches.sortedWith(rank).take(10)) }
        }

        // Faster timeouts while scanning (25 × 4 ms = 100 ms)
        elm.query("ATAT0", 300)
        elm.query("ATST19", 300)

        // 1) Standard OBD odometer (PID A6)
        set { copy(text = "Trying the standard OBD odometer…", progress = 0.01f) }
        elm.query("ATSH7DF", 300)
        elm.query("ATCRA7E8", 300)
        Elm327.payloadAfter(elm.query("01A6", 500), "41A6")?.let { p ->
            if (p.size >= 4) addMatches("7DF", "7E8", "01A6", p.copyOf(4))
        }
        best(matches)?.takeIf { it.confident }?.let { return finish(it, matches) }

        // 2) Which ECUs answer?
        val ecus = discover(elm)
        set { copy(ecus = ecus.map { "${it.first}→${it.second}" }) }
        if (ecus.isEmpty()) {
            set { copy(running = false, finished = true, text = "No ECUs answered. Is the car switched on (READY)?") }
            return null
        }

        // Battery ECU last — least likely to hold the odometer.
        val ordered = ecus.sortedBy { if (it.first == "742") 1 else 0 }
        val perEcu = QUICK_DIDS.size + DID_RANGES.sumOf { it.count() }
        val total = (perEcu * ordered.size).toFloat()
        var done = 0

        elm.query("ATH0", 300)
        for ((tx, rx) in ordered) {
            elm.query("ATSH$tx", 300)
            elm.query("ATCRA$rx", 300)
            val dids = QUICK_DIDS + DID_RANGES.flatMap { it.toList() }.filterNot { it in QUICK_DIDS }
            for (d in dids) {
                currentCoroutineContext().ensureActive()
                if (cancelled) return finish(best(matches), matches, stopped = true)
                val did = "%04X".format(d)
                val resp = elm.query("22$did", 250)
                Elm327.payloadAfter(resp, "62$did")?.let { if (it.isNotEmpty()) addMatches(tx, rx, did, it) }
                done++
                if (done % 16 == 0) set {
                    copy(progress = 0.1f + 0.9f * done / total, text = "ECU $tx · DID $did · ${matches.size} candidate(s)")
                }
            }
            best(matches)?.takeIf { it.confident }?.let { return finish(it, matches) }
        }
        return finish(best(matches), matches)
    }

    /** Sends tester-present to every 11-bit diagnostic id and records who replies. */
    private suspend fun discover(elm: Elm327): List<Pair<String, String>> {
        val found = linkedMapOf<String, String>()
        elm.query("ATH1", 300)
        val wildcard = !elm.query("ATCRA7XX", 300).contains("?")
        if (!wildcard) { elm.query("ATCF700", 300); elm.query("ATCM700", 300) }
        val ids = (0x700..0x7F7).filter { it != 0x7DF && it !in 0x7E8..0x7EF }
        for ((i, id) in ids.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (cancelled) break
            val tx = "%03X".format(id)
            elm.query("ATSH$tx", 200)
            val r = elm.query("3E00", 200).uppercase()
            for (line in r.split('\r', '\n').map { it.trim().replace(" ", "") }) {
                if (line.length < 7 || !Regex("^7[0-9A-F]{2}").containsMatchIn(line)) continue
                val rxId = line.substring(0, 3)
                if (rxId == tx) continue
                // positive (7E00) or negative (7F3E..) reply both prove an ECU is there
                if (line.contains("7E00") || line.contains("7F3E")) found.putIfAbsent(tx, rxId)
            }
            if (i % 8 == 0) set {
                copy(progress = 0.02f + 0.08f * i / ids.size, text = "Looking for ECUs… $tx · found ${found.size}",
                    ecus = found.map { "${it.key}→${it.value}" })
            }
        }
        return found.toList()
    }

    /** Every (offset, width, divisor, endian) reading of [p] within 5 km of the dashboard figure. */
    private fun findIn(p: IntArray, dashKm: Int): List<OdoSpec> {
        val out = mutableListOf<OdoSpec>()
        for (width in 2..4) for (off in 0..p.size - width) for (little in listOf(false, true)) {
            if (width == 2 && little) continue
            for (div in intArrayOf(1, 10, 100, 1000)) {
                val s = OdoSpec("", "", "", 0, off, width, div, little)
                val v = s.decode(p) ?: continue
                if (dashKm > 0 && abs(v - dashKm) <= 5) out += s
            }
        }
        return out
    }

    private val rank = compareByDescending<OdoMatch> { it.confident }
        .thenBy { it.diff }.thenByDescending { it.spec.width }.thenBy { it.spec.divisor }

    private fun best(m: List<OdoMatch>) = m.sortedWith(rank).firstOrNull()

    private fun finish(best: OdoMatch?, all: List<OdoMatch>, stopped: Boolean = false): OdoMatch? {
        set {
            copy(
                running = false, finished = true, progress = 1f,
                matches = all.sortedWith(rank).take(10),
                text = when {
                    stopped -> "Stopped. ${all.size} candidate(s) so far."
                    best == null -> "Not found in the usual places. The app will keep using GPS distance."
                    best.confident -> "Found it: ${"%.1f".format(best.valueKm)} km — ${best.spec.describe()}"
                    else -> "Possible match (weak): ${"%.1f".format(best.valueKm)} km. Drive a few km and re-run to confirm."
                }
            )
        }
        return best
    }
}
