package com.spk.windsordash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.spk.windsordash.data.ChargeSession
import com.spk.windsordash.data.DashState
import com.spk.windsordash.data.LinkStatus
import com.spk.windsordash.data.OdoSource
import com.spk.windsordash.data.Trip
import com.spk.windsordash.obd.Sample
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screen with made-up readings so the design can be checked from CI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1500dp-xxhdpi")
class ScreenshotTest {
    private val now = System.currentTimeMillis()
    private val sample = Sample(
        at = now, packV = 331.0, currentA = 42.0, socPct = 72.0, sohPct = 99.0, energyLeftKwh = 36.8,
        maxCellMv = 3331, minCellMv = 3326, maxCellId = 63, minCellId = 9, aux12V = 13.9,
        d02bRaw = 84, d02cRaw = 85, d02dRaw = 83, d265Raw = 70, d266Raw = 78,
    )
    private val driving = DashState(
        status = LinkStatus.LIVE, statusText = "Live · MODAXE OBDII", sample = sample, powerKw = 13.9,
        rangeKm = 291, effWhKm = 126.0, effSource = "last 10 min", odoKm = 24763.6, odoSource = OdoSource.CAR,
        speedKmh = 82.0, gpsOn = true,
        trip = Trip(now - 95 * 60_000L, 24660, gpsKm = 103.6, netWh = 13050.0, regenWh = 1420.0, movingMs = 88 * 60_000L, gpsWh = 13050.0),
    )

    private fun shot(name: String, st: DashState) = captureRoboImage("build/screens/$name.png") {
        MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Bg, surface = Card)) {
            Screen(st, {}, {}, {}, showHousekeeping = false)
        }
    }

    @Test fun driving() = shot("1-driving", driving)

    @Test fun charging() = shot(
        "2-charging",
        driving.copy(
            sample = sample.copy(currentA = -172.6, socPct = 59.0, energyLeftKwh = 30.2, d02cRaw = 88, d265Raw = 50),
            powerKw = -57.8, charging = true, parked = true, speedKmh = 0.0, targetSoc = 80,
            charge = ChargeSession(now - 14 * 60_000L, 33.0, addedKwh = 13.6, peakKw = 58.7),
        ),
    )

    @Test fun offline() = shot("3-not-live", driving.copy(status = LinkStatus.IDLE, statusText = "Not connected", sample = sample.copy(at = now - 3_600_000L), powerKw = null))
}
