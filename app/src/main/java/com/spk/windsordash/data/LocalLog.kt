package com.spk.windsordash.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.spk.windsordash.obd.Sample
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * On-device CSV logs. Nothing leaves the phone unless you export or share a file yourself.
 *
 *  samples-YYYY-MM-DD.csv  one row per poll: only values the car reported (blank = not read)
 *  events.csv              connects, disconnects, charge sessions, trip resets
 *
 * Files go either to private app storage (default) or, once you pick one, straight into a
 * folder of your choice (e.g. Documents/WindsorDash), appended live as you drive.
 */
object LocalLog {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var appCtx: Context
    private lateinit var dir: File
    private var writer: BufferedWriter? = null
    private var writerDay: String? = null
    private var writerPfd: ParcelFileDescriptor? = null
    private var rowsSinceFlush = 0

    var enabled = true
        private set
    /** Folder chosen by the user (null = private app storage). */
    var folderUri: Uri? = null
        private set
    /** Last problem writing to the chosen folder (then we fall back to app storage). */
    @Volatile var folderError: String? = null
        private set

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private const val HEADER =
        "time,status,soc_pct,soh_pct,energy_left_kwh,pack_v,current_a,power_kw," +
            "max_cell_mv,max_cell_no,min_cell_mv,min_cell_no,cell_spread_mv,a021_unverified,aux_12v,odometer_km," +
            "odo_shown_km,odo_source,trip_km,trip_dist_source,charging,parked,gps_on,speed_kmh"
    private const val EVENTS_HEADER = "time,event"

    private fun prefs() = appCtx.getSharedPreferences("dash", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        appCtx = ctx.applicationContext
        dir = File(appCtx.filesDir, "logs").apply { mkdirs() }
        enabled = prefs().getBoolean("log", true)
        research = prefs().getBoolean("research", false)
        keepDays = prefs().getInt("keep_days", 30)
        io.execute { purge() }
        folderUri = prefs().getString("log_tree", null)?.let(Uri::parse)?.takeIf { stillAllowed(it) }
    }

    private fun stillAllowed(u: Uri) = appCtx.contentResolver.persistedUriPermissions
        .any { it.uri == u && it.isWritePermission }

    private fun tree(): DocumentFile? = folderUri?.let { DocumentFile.fromTreeUri(appCtx, it) }

    /** Human-readable name of where logs go now. */
    fun destination(): String = tree()?.name?.let { "folder “$it”" } ?: "app storage (private)"

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).edit().putBoolean("log", on).apply()
        if (!on) io.execute { closeWriter() }
        event(if (on) "logging on" else "logging off", force = true)
    }

    /**
     * Switch to writing directly into [uri] (from the system folder picker).
     * Existing app-storage logs are copied there first, so the folder holds everything.
     */
    fun useFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { appCtx.contentResolver.takePersistableUriPermission(uri, flags) }
        io.submit {
            closeWriter()
            val t = DocumentFile.fromTreeUri(appCtx, uri)
            if (t != null) for (f in files()) {
                runCatching {
                    if (t.findFile(f.name) == null) {
                        val doc = t.createFile("text/csv", f.name.removeSuffix(".csv")) ?: return@runCatching
                        appCtx.contentResolver.openOutputStream(doc.uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    }
                }
            }
            folderUri = uri
            folderError = null
            prefs().edit().putString("log_tree", uri.toString()).apply()
        }.get()
        event("logging to ${destination()}", force = true)
    }

    fun useAppStorage() {
        val old = folderUri
        io.submit {
            closeWriter()
            folderUri = null
            folderError = null
            prefs().edit().remove("log_tree").apply()
        }.get()
        old?.let {
            runCatching {
                appCtx.contentResolver.releasePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        event("logging to app storage", force = true)
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
                        s.a021Raw, s.aux12V?.r(2), s.odometerKm,
                        st.odoKm?.r(1), st.odoSource.name.lowercase(), st.trip?.km?.r(2), st.trip?.distSource,
                        if (st.charging) 1 else 0, if (st.parked) 1 else 0, if (st.gpsOn) 1 else 0, st.speedKmh?.r(0),
                    ).joinToString(",") { it?.toString() ?: "" }
                )
                w.newLine()
                // Flush every 5 rows so the file on disk is never more than a few seconds behind.
                if (++rowsSinceFlush >= 5) { w.flush(); rowsSinceFlush = 0 }
            }.onFailure {
                // Writer broke (folder removed, SD card out…): drop it; next row reopens or falls back.
                closeWriter()
            }
        }
    }

    fun event(text: String, force: Boolean = false) {
        if ((!enabled && !force) || !::dir.isInitialized) return
        val now = System.currentTimeMillis()
        val line = "${stamp.format(Date(now))},\"${text.replace("\"", "'")}\""
        io.execute {
            runCatching {
                val (w, pfd) = openAppend("events.csv", EVENTS_HEADER)
                w.use { it.write(line); it.newLine() }
                runCatching { pfd?.close() }
            }
        }
    }

    /* ---------- runs report ---------- */

    const val RUNS_HEADER = "start,end,minutes,soc_start,soc_end,km,top_kmh,avg_moving_kmh,avg_kmh,kwh_used,wh_per_km,regen_kwh"
    private val runStamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** Appends one finished drive to runs.csv (kept forever — it's tiny). */
    fun run(r: Run) {
        if (!::dir.isInitialized) return
        val end = r.lastMoveAt
        val mins = (end - r.startedAt) / 60_000.0
        val avgMoving = if (r.movingMs > 60_000) r.km / (r.movingMs / 3_600_000.0) else null
        val avgAll = if (mins > 1) r.km / (mins / 60) else null
        val line = listOf(
            runStamp.format(Date(r.startedAt)), runStamp.format(Date(end)), mins.r(0),
            r.socStart?.r(0), r.socEnd?.r(0), r.km.r(1), r.topKmh.r(0), avgMoving?.r(0), avgAll?.r(0),
            (r.netWh / 1000).r(2), if (r.km >= 1) (r.netWh / r.km).r(0) else null, (r.regenWh / 1000).r(2),
        ).joinToString(",") { it ?: "" }
        io.execute {
            runCatching {
                val (w, pfd) = openAppend("runs.csv", RUNS_HEADER)
                w.use { it.write(line); it.newLine() }
                runCatching { pfd?.close() }
            }
        }
    }

    /** Latest runs, newest first (each row split into columns). */
    fun readRuns(limit: Int = 30): List<List<String>> = io.submit<List<List<String>>> {
        runCatching {
            val text = tree()?.findFile("runs.csv")?.let { doc ->
                appCtx.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { it.readText() }
            } ?: File(dir, "runs.csv").takeIf { it.exists() }?.readText()
            text.orEmpty().lines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }.takeLast(limit).reversed()
        }.getOrDefault(emptyList())
    }.get()

    /** Shareable copy of runs.csv (from the folder or app storage). */
    fun runsUri(ctx: Context): Uri? {
        tree()?.findFile("runs.csv")?.let { return it.uri }
        val f = File(dir, "runs.csv")
        return if (f.exists()) androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".logs", f) else null
    }

    /* ---------- retention ---------- */

    /** Days to keep samples/raw files (0 = keep everything). runs.csv and events.csv are never purged. */
    var keepDays = 30
        private set

    fun setKeepDays(days: Int) {
        keepDays = days
        prefs().edit().putInt("keep_days", days).apply()
        io.execute { purge() }
    }

    private val datedName = Regex("^(samples|raw)-(\\d{4}-\\d{2}-\\d{2}).*\\.csv$")

    /** Deletes dated log files older than [keepDays], in app storage and in the chosen folder. */
    private fun purge() {
        if (keepDays <= 0 || !::dir.isInitialized) return
        val cutoff = day.format(Date(System.currentTimeMillis() - keepDays * 86_400_000L))
        fun old(name: String?) = name?.let { datedName.find(it)?.groupValues?.get(2) }?.let { it < cutoff } == true
        var n = 0
        runCatching { files().filter { old(it.name) }.forEach { if (it.delete()) n++ } }
        runCatching { tree()?.listFiles()?.filter { old(it.name) }?.forEach { if (it.delete()) n++ } }
        if (n > 0) event("purged $n log files older than $keepDays days", force = true)
    }

    /* ---------- research capture ---------- */

    var research = false
        private set

    fun setResearch(on: Boolean) {
        research = on
        prefs().edit().putBoolean("research", on).apply()
        event(if (on) "research capture on" else "research capture off", force = true)
    }

    /** Appends raw DID reads to raw-<date>.csv with the pack state at that moment. */
    fun raw(reads: List<com.spk.windsordash.obd.RawRead>, s: Sample, powerKw: Double?) {
        if (reads.isEmpty() || !::dir.isInitialized) return
        val ts = stamp.format(Date())
        val d = day.format(Date())
        val ctxCols = listOf(s.socPct, s.packV?.r(1), s.currentA?.r(1), powerKw?.r(2), s.maxCellMv, s.minCellMv)
            .joinToString(",") { it?.toString() ?: "" }
        io.execute {
            runCatching {
                val (w, pfd) = openAppend("raw-$d.csv", RAW_HEADER)
                w.use { out -> for (r in reads) { out.write("$ts,${r.tx},${r.did},${r.hex},${if (r.sweep) 1 else 0},$ctxCols"); out.newLine() } }
                runCatching { pfd?.close() }
            }
        }
    }

    private const val RAW_HEADER = "time,ecu,did,hex,sweep,soc_pct,pack_v,current_a,power_kw,max_cell_mv,min_cell_mv"

    fun flush() = io.execute { runCatching { writer?.flush() } }

    private fun writerFor(at: Long): BufferedWriter {
        val d = day.format(Date(at))
        val cur = writer
        if (cur != null && writerDay == d) return cur
        closeWriter()
        purge()   // new day: drop files past the retention period
        val (w, pfd) = openAppend("samples-$d.csv", HEADER)
        writer = w; writerPfd = pfd; writerDay = d
        return w
    }

    /**
     * Opens [name] for appending in the current destination, writing [header] if the file is new.
     * If an existing file has a different header (older app version), a "-v2" file is used instead.
     * Any folder failure falls back to app storage so no readings are lost.
     */
    private fun openAppend(name: String, header: String): Pair<BufferedWriter, ParcelFileDescriptor?> {
        val t = tree()
        if (t != null) {
            try {
                return openInFolder(t, name, header)
            } catch (e: Exception) {
                folderError = e.message ?: e.javaClass.simpleName
            }
        }
        return openInternal(name, header) to null
    }

    private fun openInFolder(t: DocumentFile, name: String, header: String): Pair<BufferedWriter, ParcelFileDescriptor?> {
        var target = name
        var doc = t.findFile(target)
        if (doc != null && doc.length() > 0) {
            val first = appCtx.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { it.readLine() }
            if (first != header) {
                target = name.removeSuffix(".csv") + "-v2.csv"
                doc = t.findFile(target)
            }
        }
        val fresh = doc == null || doc.length() == 0L
        if (doc == null) doc = t.createFile("text/csv", target.removeSuffix(".csv"))
            ?: error("Can't create $target in the chosen folder")
        val pfd = appCtx.contentResolver.openFileDescriptor(doc.uri, "wa")
            ?: error("Can't open $target for writing")
        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(pfd.fileDescriptor), Charsets.UTF_8))
        if (fresh) { w.write(header); w.newLine() }
        folderError = null
        return w to pfd
    }

    private fun openInternal(name: String, header: String): BufferedWriter {
        var f = File(dir, name)
        if (f.exists() && f.length() > 0 && runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull() != header) {
            f = File(dir, name.removeSuffix(".csv") + "-v2.csv")
        }
        val fresh = !f.exists() || f.length() == 0L
        return BufferedWriter(FileWriter(f, true)).also { if (fresh) { it.write(header); it.newLine() } }
    }

    private fun closeWriter() {
        runCatching { writer?.flush(); writer?.close() }
        runCatching { writerPfd?.close() }
        writer = null; writerDay = null; writerPfd = null
    }

    /* ---------- app-storage files (used when no folder is chosen) ---------- */

    fun files(): List<File> {
        if (!::dir.isInitialized) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()
    }

    fun totalBytes() = files().sumOf { it.length() }

    /** Copies every app-storage log into Download/WindsorDash (Android 10+). Returns files copied. */
    fun saveToDownloads(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        io.submit { runCatching { writer?.flush() } }.get()
        val resolver = ctx.contentResolver
        var n = 0
        val folder = Environment.DIRECTORY_DOWNLOADS + "/WindsorDash"
        for (f in files()) {
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

    /** Deletes app-storage logs only. Files in a chosen folder are yours to manage. */
    fun deleteAll() {
        io.submit {
            if (folderUri == null) closeWriter()
            files().forEach { it.delete() }
        }.get()
    }

    private fun Double.r(dp: Int) = "%.${dp}f".format(Locale.US, this)
}
