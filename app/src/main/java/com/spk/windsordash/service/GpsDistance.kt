package com.spk.windsordash.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Measures distance driven from the phone's GPS (no Google services needed).
 * Filters out poor fixes, stationary jitter and impossible jumps.
 */
class GpsDistance(
    private val ctx: Context,
    private val onFix: (speedMps: Float?) -> Unit = {},
    private val onMeters: (Double) -> Unit,
) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var last: Location? = null
    var running = false
        private set
    var lastFixAt = 0L
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = handle(loc)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) return false
        return runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            running = true
            true
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { lm.removeUpdates(listener) }
        running = false
        last = null
    }

    private fun handle(loc: Location) {
        if (!loc.hasAccuracy()) return
        // Speed is useful even from a rough fix (e.g. under a charging canopy).
        if (loc.accuracy <= 60f) onFix(if (loc.hasSpeed()) loc.speed else null)
        if (loc.accuracy > 25f) return
        lastFixAt = System.currentTimeMillis()
        val prev = last
        if (prev == null) { last = loc; return }
        val d = prev.distanceTo(loc).toDouble()
        val dt = (loc.time - prev.time) / 1000.0
        if (dt <= 0) return
        val speed = d / dt
        when {
            speed > 55 -> last = loc                               // > 200 km/h: a GPS jump, skip it
            loc.hasSpeed() && loc.speed < 1.0f && d < 15 -> {}      // parked: ignore drift, keep anchor
            d < maxOf(loc.accuracy, prev.accuracy) * 0.5 && dt < 10 -> {}  // too small to trust yet
            else -> { onMeters(d); last = loc }
        }
    }
}
