package com.spk.tfholdings.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Everything stays on the phone, in the app's private storage. */
class Store(context: Context) {
    private val dir = context.filesDir
    private val snapFile = File(dir, "tf_holdings.json")
    private val quoteFile = File(dir, "quotes.json")

    fun loadSnapshotText(): String? = snapFile.takeIf { it.exists() }?.readText()

    fun saveSnapshotText(text: String) {
        val tmp = File(dir, "tf_holdings.tmp")
        tmp.writeText(text)
        tmp.renameTo(snapFile)
    }

    fun loadQuotes(): Map<String, Quote> = try {
        val o = JSONObject(quoteFile.readText())
        o.keys().asSequence().associateWith { k ->
            val q = o.getJSONObject(k)
            Quote(q.getDouble("p"), if (q.isNull("v")) null else q.getDouble("v"), q.getLong("t"))
        }
    } catch (e: Exception) {
        emptyMap()
    }

    fun saveQuotes(quotes: Map<String, Quote>) {
        val o = JSONObject()
        quotes.forEach { (k, q) ->
            o.put(k, JSONObject().put("p", q.price).put("v", q.prev ?: JSONObject.NULL).put("t", q.at))
        }
        quoteFile.writeText(o.toString())
    }
}
