package com.spk.windsordash.obd

/**
 * The Windsor's BMS broadcasts every cell voltage on its own, without being asked:
 * CAN ids 0x5A1–0x5B8 carry 4 cells each (cells 1–96) and 0x5BB carries cells 97–98.
 * Each cell is a 16-bit word: top 4 bits are a slot tag (2, 4, 6, 8), low 12 bits are mV.
 * We only listen (ELM "ATMA"), so this is a direct, measured reading of all 98 cells.
 */
object CellBroadcast {
    const val MASK = "7E0"
    const val FILTER = "5A0"
    private val TAGS = intArrayOf(2, 4, 6, 8)
    private val FRAMES: List<Pair<Int, Int>> = (0x5A1..0x5B8).map { it to 4 } + listOf(0x5BB to 2)

    /** Returns 98 cell voltages in mV (cell 1 first), or null if any frame is missing or invalid. */
    fun decode(frames: List<Pair<String, String>>, packV: Double?): List<Int>? {
        val latest = HashMap<Int, String>()
        for ((id, hex) in frames) {
            val n = id.toIntOrNull(16) ?: continue
            if (hex.length >= 16) latest[n] = hex
        }
        val cells = ArrayList<Int>(WindsorProfile.CELLS)
        for ((id, count) in FRAMES) {
            val p = latest[id] ?: return null
            for (slot in 0 until count) {
                val w = p.substring(slot * 4, slot * 4 + 4).toIntOrNull(16) ?: return null
                if (w shr 12 != TAGS[slot]) return null
                val mv = w and 0xFFF
                if (mv !in 2000..3800) return null
                cells += mv
            }
        }
        if (cells.size != WindsorProfile.CELLS) return null
        // Cross-check against the pack voltage when it's real (contactors closed).
        if (packV != null && packV > 200) {
            val sum = cells.sum() / 1000.0
            if (kotlin.math.abs(sum - packV) / packV > 0.03) return null
        }
        return cells
    }
}
