package com.spk.windsordash.obd

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Minimal ELM327 command/response driver. */
class Elm327(private val t: ObdTransport) {
    private val lock = Mutex()
    private var currentHeader: String? = null

    /** Sends [cmd] and returns everything up to the '>' prompt (trimmed, prompt removed). */
    suspend fun query(cmd: String, timeoutMs: Long = 600): String = lock.withLock {
        // Drop late bytes from a previous timed-out command.
        while (t.incoming.tryReceive().isSuccess) { /* drain */ }
        t.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val chunk = try {
                    t.incoming.receive()
                } catch (e: ClosedReceiveChannelException) {
                    throw IllegalStateException("Adapter link closed")
                }
                sb.append(String(chunk, Charsets.US_ASCII))
                if (sb.indexOf(">") >= 0) break
            }
        }
        sb.toString().replace(">", "").replace("\u0000", "").trim()
    }

    suspend fun init(): String {
        query("ATZ", 2500)          // reset; returns "ELM327 v1.5" etc.
        delay(300)
        val id = query("ATI", 800)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6")) query(c, 800)
        currentHeader = null
        return id
    }

    /** Forget which ECU is targeted (after raw commands changed headers/filters). */
    fun forgetTarget() { currentHeader = null }

    /** Points requests at an ECU: request id [tx], response filter [rx] (11-bit CAN). */
    suspend fun target(tx: String, rx: String) {
        if (currentHeader == tx) return
        query("ATSH$tx", 400)
        query("ATCRA$rx", 400)
        currentHeader = tx
    }

    /** UDS ReadDataByIdentifier (service 0x22). Returns the data bytes, or null. */
    suspend fun readDid(did: String, bytes: Int, timeoutMs: Long = 400): IntArray? {
        val resp = query("22$did", timeoutMs)
        return parseDid(resp, did, bytes)
    }

    suspend fun voltage12V(): Double? {
        val r = query("ATRV", 400)
        return Regex("""(\d+(?:\.\d+)?)\s*V""", RegexOption.IGNORE_CASE).find(r)
            ?.groupValues?.get(1)?.toDoubleOrNull()
    }

    companion object {
        /** Finds "62<DID><data>" in a raw ELM reply (with or without spaces/line numbers). */
        fun parseDid(resp: String, did: String, bytes: Int): IntArray? {
            val all = payloadAfter(resp, "62" + did.uppercase()) ?: return null
            if (all.size < bytes) return null
            return all.copyOf(bytes)
        }

        /** Every data byte following [key] (hex, e.g. "62DD01"), joining ELM multi-frame lines. */
        fun payloadAfter(resp: String, key: String): IntArray? {
            val clean = joinFrames(resp)
            val at = clean.indexOf(key.uppercase())
            if (at < 0) return null
            val hex = clean.substring(at + key.length).let { it.substring(0, it.length - it.length % 2) }
            return IntArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
        }

        /**
         * ELM327 (CAF on, headers off) prints long ISO-TP replies as
         * "014\r0: 62DD01...\r1: ...". Drop the byte-count line and "n:" prefixes.
         */
        fun joinFrames(resp: String): String {
            val lines = resp.uppercase().split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            val multi = lines.any { Regex("^[0-9A-F]:").containsMatchIn(it) }
            return lines.mapNotNull { l ->
                val m = Regex("^([0-9A-F]):\\s*(.*)$").find(l)
                when {
                    m != null -> m.groupValues[2]
                    multi && Regex("^[0-9A-F]{3}$").matches(l) -> null
                    else -> l
                }
            }.joinToString("").replace(Regex("[^0-9A-F]"), "")
        }
    }
}

fun IntArray.u16() = (this[0] shl 8) or this[1]
fun IntArray.u24() = (this[0] shl 16) or (this[1] shl 8) or this[2]
