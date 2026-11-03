package com.spk.tfholdings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.spk.tfholdings.data.Portfolio
import com.spk.tfholdings.data.SnapshotParser
import com.spk.tfholdings.ui.Accent
import com.spk.tfholdings.ui.Bg
import com.spk.tfholdings.ui.Card
import com.spk.tfholdings.ui.Screen
import com.spk.tfholdings.ui.UiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the screens with made-up holdings so the design can be checked from CI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp-xxhdpi")
class ScreenshotTest {
    private val snap = SnapshotParser.parse(Samples.JSON)

    private fun shot(name: String, st: UiState) = captureRoboImage("build/screens/$name.png") {
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
            Screen(st, {}, {})
        }
    }

    @Test fun holdings() = shot(
        "1-holdings",
        UiState(snapshot = snap, portfolio = Portfolio.build(snap, Samples.quotes), pricesAt = 1793690000000L),
    )

    @Test fun empty() = shot("2-empty", UiState())
}
