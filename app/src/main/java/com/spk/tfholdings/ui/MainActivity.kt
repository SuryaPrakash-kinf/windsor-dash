package com.spk.tfholdings.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spk.tfholdings.data.Portfolio
import com.spk.tfholdings.data.Prices
import com.spk.tfholdings.data.Quote
import com.spk.tfholdings.data.Snapshot
import com.spk.tfholdings.data.SnapshotParser
import com.spk.tfholdings.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private val state = mutableStateOf(UiState())
    private var quotes: Map<String, Quote> = emptyMap()
    private var snapshot: Snapshot? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        quotes = store.loadQuotes()
        store.loadSnapshotText()?.let { text ->
            snapshot = runCatching { SnapshotParser.parse(text) }.getOrNull()
        }
        publish(pricesAt = quotes.values.maxOfOrNull { it.at })
        handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
                Screen(
                    st = state.value,
                    onRefresh = { refresh() },
                    onImport = { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
                )
            }
        }

        // Keep prices fresh while the app is on screen: now, then every minute.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val last = state.value.pricesAt ?: 0L
                    if (snapshot != null && System.currentTimeMillis() - last > 55_000) refresh()
                    delay(60_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { importFrom(it) }
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> importFrom(uri)
                    !text.isNullOrBlank() -> importText(text)
                }
            }
        }
    }

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            if (text == null) publish(message = "Couldn't read that file.") else importText(text)
        }
    }

    private fun importText(text: String) {
        val snap = try {
            SnapshotParser.parse(text)
        } catch (e: IllegalArgumentException) {
            publish(message = e.message)
            return
        }
        store.saveSnapshotText(text)
        snapshot = snap
        publish(message = null)
        refresh()
    }

    private fun refresh() {
        val snap = snapshot ?: return
        if (state.value.refreshing) return
        state.value = state.value.copy(refreshing = true)
        lifecycleScope.launch {
            val fresh = Prices.fetchAll(snap.holdings.map { it.ticker }.filterNot { Portfolio.isManual(it) })
            if (fresh.isNotEmpty()) {
                quotes = quotes + fresh
                withContext(Dispatchers.IO) { store.saveQuotes(quotes) }
            }
            val msg = if (fresh.isEmpty() && snap.holdings.any { !Portfolio.isManual(it.ticker) }) "No prices came back. Check the internet connection and press Refresh." else null
            publish(pricesAt = if (fresh.isNotEmpty()) System.currentTimeMillis() else state.value.pricesAt, message = msg, live = fresh.keys)
        }
    }

    /** Rebuilds the screen. Prices fetched in this session count as live; cached ones from earlier don't. */
    private fun publish(pricesAt: Long? = state.value.pricesAt, message: String? = state.value.message, live: Set<String>? = null) {
        val snap = snapshot
        val usable = if (live == null) quotes else quotes.filterKeys { it in live }
        state.value = UiState(
            snapshot = snap,
            portfolio = snap?.let { Portfolio.build(it, usable) },
            refreshing = false,
            pricesAt = pricesAt,
            message = message ?: staleNote(snap),
        )
    }

    private fun staleNote(snap: Snapshot?): String? {
        snap ?: return null
        val at = runCatching { java.time.LocalDateTime.parse(snap.generatedAt.take(19)) }.getOrNull() ?: return null
        val days = java.time.Duration.between(at, java.time.LocalDateTime.now()).toDays()
        return if (days >= 7) "Holdings were exported $days days ago. Export again from TechnoFunda if you have traded since." else null
    }
}
