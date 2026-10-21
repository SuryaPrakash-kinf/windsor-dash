package com.spk.windsordash.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.spk.windsordash.obd.Sample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * On-device CSV logs. Nothing leaves the phone unless you export or share a file yourself.
 *
 *  samples-YYYY-MM-DD.csv  one row per poll: only values the car reported (blank = not read)
 *  events.csv              connects, disconnects, charge sessions, trip resets
 */
object LocalLog {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var dir: File
    private var writer: BufferedWriter? = null
    private var writerDay: String? = null
    private var rowsSinceFlush = 0
    var enabled = true
        private set

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private const val HEADER =
        "time,status,soc_pct,soh_pct,energy_left_kwh,pack_v,current_a,power_kw," +
            "max_cell_mv,max_cell_no,min_cell_mv,min_cell_no,cell_spread_mv,batt_temp_c,aux_12v,odometer_km," +
            "odo_shown_km,odo_source,trip_km,trip_dist_source"

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        enabled = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).getBoolean("log", true)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).edit().putBoolean("log", on).apply()
        if (!on) io.execute { closeWriter() }
        event(if (on) "logging on" else "logging off", force = true)
    }

    fun sample(s: Sample, status: LinkStatus, powerKw: Double?, st: DashState) {
        if (!enabled || !::dir.isInitialized) return
        io.execute {
            runCatching {
                val w = writerFor(s.at)
                val spread = if (s.maxCellMv != null && s.minCellMv != null) s.maxCellMv - s.minCellMv else null
                w.write(
                    listOf(
                        stamp.format(Date(s.at)), status.name.lowercase(),
                        s.socPct, s.sohPct, s.energyLeftKwh?.r(1), s.packV?.r(1), s.currentA?.r(1), powerKw?.r(2),
                        s.maxCellMv, s.maxCellId, s.minCellMv, s.minCellId, spread,
                        s.battTempC, s.aux12V?.r(2), s.odometerKm,
                        st.odoKm?.r(1), st.odoSource.name.lowercase(), st.trip?.km?.r(2), st.trip?.distSource,
                    ).joinToString(",") { it?.toString() ?: "" }
                )
                w.newLine()
                if (++rowsSinceFlush >= 10) { w.flush(); rowsSinceFlush = 0 }
            }
        }
    }

    fun event(text: String, force: Boolean = false) {
        if ((!enabled && !force) || !::dir.isInitialized) return
        val now = System.currentTimeMillis()
        io.execute {
            runCatching {
                val f = File(dir, "events.csv")
                val fresh = !f.exists()
                FileWriter(f, true).use { w ->
                    if (fresh) w.write("time,event\n")
                    w.write("${stamp.format(Date(now))},\"${text.replace("\"", "'")}\"\n")
                }
            }
        }
    }

    fun flush() = io.execute { runCatching { writer?.flush() } }

    private fun writerFor(at: Long): BufferedWriter {
        val d = day.format(Date(at))
        val cur = writer
        if (cur != null && writerDay == d) return cur
        closeWriter()
        val f = File(dir, "samples-$d.csv")
        // An older app version wrote a different header: keep that file aside.
        if (f.exists() && runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull() != HEADER) {
            f.renameTo(File(dir, "samples-$d-v1.csv"))
        }
        val fresh = !f.exists()
        return BufferedWriter(FileWriter(f, true)).also {
            if (fresh) { it.write(HEADER); it.newLine() }
            writer = it; writerDay = d
        }
    }

    private fun closeWriter() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null; writerDay = null
    }

    fun files(): List<File> {
        if (!::dir.isInitialized) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()
    }

    fun totalBytes() = files().sumOf { it.length() }

    /** Copies every log into Download/WindsorDash (Android 10+). Returns files copied. */
    fun saveToDownloads(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        // Make sure buffered rows are on disk first.
        io.submit { runCatching { writer?.flush() } }.get()
        val resolver = ctx.contentResolver
        var n = 0
        val folder = Environment.DIRECTORY_DOWNLOADS + "/WindsorDash"
        for (f in files()) {
            // Replace an older copy of the same file instead of making "name (1).csv".
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(f.name, "$folder/"),
            )
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: continue
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            n++
        }
        return n
    }

    fun deleteAll() {
        io.submit {
            closeWriter()
            files().forEach { it.delete() }
        }.get()
    }

    private fun Double.r(dp: Int) = "%.${dp}f".format(Locale.US, this)
}
