package com.spk.windsordash.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.spk.windsordash.R
import com.spk.windsordash.ui.MainActivity

/** Charging notifications. Switchable in the app; on by default. */
object Alerts {
    private const val CHANNEL = "alerts"
    private lateinit var ctx: Context
    var enabled = true
        private set

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c
        enabled = c.getSharedPreferences("dash", Context.MODE_PRIVATE).getBoolean("alerts", true)
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Charging alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).edit().putBoolean("alerts", on).apply()
    }

    fun targetReached(soc: Double, target: Int) =
        post(101, "Reached ${soc.toInt()}%", "Your charge target ($target%) is reached. You can unplug.")

    fun chargeFinished(c: ChargeSession) {
        val mins = ((c.endedAt ?: System.currentTimeMillis()) - c.startedAt) / 60_000
        post(
            102, "Charging finished at ${c.endSoc?.toInt() ?: "?"}%",
            "%s → %s%% · %.1f kWh into the battery · %d min".format(
                c.startSoc?.toInt()?.toString() ?: "?", c.endSoc?.toInt()?.toString() ?: "?", c.addedKwh, mins
            ) + (if (c.gapNote != null) " (part filled from the battery's counter)" else "")
        )
    }

    fun linkPausedWhileCharging() = post(
        103, "Car stopped answering while charging",
        "Probably locked or asleep. Charging continues; the totals are filled in from the battery when you reconnect."
    )

    private fun post(id: Int, title: String, text: String) {
        if (!::ctx.isInitialized || !enabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}
