package com.spk.tfholdings.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A price and the previous close it is compared with for "today". */
data class Quote(val price: Double, val prev: Double?, val at: Long)

/**
 * Live prices, the same routes the laptop app uses:
 *  - a numeric code is an AMFI mutual-fund scheme -> mfapi.in (latest and previous NAV)
 *  - SGBs -> Yahoo BSE first, then NSE
 *  - everything else -> Yahoo NSE first, then BSE
 */
object Prices {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) TFHoldings/1.0"

    fun isFund(ticker: String) = ticker.isNotEmpty() && ticker.all { it.isDigit() }

    fun yahooSymbols(ticker: String): List<String> {
        val s = ticker.uppercase().removePrefix("NSE:").removePrefix("BSE:").trim()
        return if (s.startsWith("SGB")) listOf("$s.BO", "$s.NS") else listOf("$s.NS", "$s.BO")
    }

    fun parseYahoo(body: String, now: Long = System.currentTimeMillis()): Quote? = try {
        val meta = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        var prev = meta.optDouble("chartPreviousClose", Double.NaN)
        if (prev.isNaN() || prev <= 0) prev = meta.optDouble("previousClose", Double.NaN)
        if (price.isNaN() || price <= 0) null else Quote(price, prev.takeIf { !it.isNaN() && it > 0 }, now)
    } catch (e: Exception) {
        null
    }

    fun parseMfapi(body: String, now: Long = System.currentTimeMillis()): Quote? = try {
        val data = JSONObject(body).getJSONArray("data")
        val nav = data.getJSONObject(0).optString("nav").toDoubleOrNull()
        val prev = if (data.length() > 1) data.getJSONObject(1).optString("nav").toDoubleOrNull() else null
        if (nav == null || nav <= 0) null else Quote(nav, prev, now)
    } catch (e: Exception) {
        null
    }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    fun fetch(ticker: String): Quote? {
        if (isFund(ticker)) {
            return get("https://api.mfapi.in/mf/$ticker")?.let { parseMfapi(it) }
        }
        for (sym in yahooSymbols(ticker)) {
            val enc = URLEncoder.encode(sym, "UTF-8")
            for (host in listOf("query1", "query2")) {
                val body = get("https://$host.finance.yahoo.com/v8/finance/chart/$enc?range=1d&interval=1d") ?: continue
                parseYahoo(body)?.let { return it }
            }
        }
        return null
    }

    /** Fetches every ticker (a few at a time). Missing entries had no price. */
    suspend fun fetchAll(tickers: Collection<String>): Map<String, Quote> = withContext(Dispatchers.IO) {
        val gate = Semaphore(6)
        coroutineScope {
            tickers.distinct().map { t -> async { gate.withPermit { t to fetch(t) } } }.awaitAll()
        }.mapNotNull { (t, q) -> q?.let { t to it } }.toMap()
    }
}
