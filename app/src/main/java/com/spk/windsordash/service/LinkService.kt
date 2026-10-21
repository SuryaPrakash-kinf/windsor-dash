package com.spk.windsordash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.spk.windsordash.R
import com.spk.windsordash.data.Dash
import com.spk.windsordash.data.LinkStatus
import com.spk.windsordash.obd.AdapterRef
import com.spk.windsordash.obd.Elm327
import com.spk.windsordash.obd.ObdTransport
import com.spk.windsordash.obd.WindsorProfile
import com.spk.windsordash.obd.openTransport
import com.spk.windsordash.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Foreground service that owns the OBD link and feeds [Dash]. */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var notifJob: Job? = null
    private var transport: ObdTransport? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Dash.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLink()
            Dash.setStatus(LinkStatus.IDLE, "Disconnected")
            stopSelf()
            return START_NOT_STICKY
        }
        val ref = Dash.savedAdapter
        if (ref == null) {
            stopSelf(); return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Connecting to ${ref.name}…"), type)
        startLink(ref)
        return START_STICKY
    }

    private fun startLink(ref: AdapterRef) {
        stopLink()
        @Suppress("WakelockTimeout")
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WindsorDash:link").apply { acquire() }

        job = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    if (ref.kind == AdapterRef.Kind.DEMO) {
                        Dash.setStatus(LinkStatus.DEMO, "Demo mode — simulated Windsor")
                        WindsorProfile.demo { Dash.onSample(it) }
                    } else {
                        Dash.setStatus(if (attempt == 0) LinkStatus.CONNECTING else LinkStatus.RETRYING, "Connecting to ${ref.name}…")
                        val t = ref.openTransport(this@LinkService)
                        transport = t
                        t.connect()
                        val elm = Elm327(t)
                        val id = elm.init()
                        Dash.setStatus(LinkStatus.CONNECTING, "Adapter ok (${id.ifBlank { "ELM327" }}). Waking the car…")
                        if (!WindsorProfile.probe(elm)) error("No reply from the Windsor BMS — is the car switched on?")
                        attempt = 0
                        Dash.setStatus(LinkStatus.LIVE, "Live · ${ref.name}")
                        WindsorProfile.poll(elm) { Dash.onSample(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Dash.onDisconnected()
                    attempt++
                    val wait = minOf(30_000L, 3_000L * attempt)
                    Dash.setStatus(LinkStatus.RETRYING, "${e.message ?: "Link error"} · retrying in ${wait / 1000}s")
                    delay(wait)
                } finally {
                    transport?.close()
                    transport = null
                }
            }
        }

        notifJob = scope.launch {
            while (isActive) {
                delay(5_000)
                val st = Dash.state.value
                val text = if (st.hasData) {
                    val soc = st.sample.socPct?.roundToInt()?.let { "$it%" } ?: "–"
                    val kw = st.powerKw?.let { "%.1f kW".format(abs(it)) } ?: "–"
                    val rng = st.rangeKm?.let { "$it km" } ?: "–"
                    if (st.charging) "Charging $kw · $soc" else "$soc · $rng · $kw"
                } else st.statusText
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
            }
        }
    }

    private fun stopLink() {
        job?.cancel(); job = null
        notifJob?.cancel(); notifJob = null
        transport?.close(); transport = null
        Dash.onDisconnected()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    override fun onDestroy() {
        stopLink()
        scope.cancel()
        if (Dash.state.value.status != LinkStatus.IDLE) Dash.setStatus(LinkStatus.IDLE, "Disconnected")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Live link", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Windsor Dash")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "stop"

        /** Returns false if Android refused to start the service from the background. */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LinkService::class.java).setAction(ACTION_STOP))
        }
    }
}
