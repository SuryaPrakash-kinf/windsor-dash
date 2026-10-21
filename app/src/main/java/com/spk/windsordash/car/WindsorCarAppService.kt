package com.spk.windsordash.car

import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.spk.windsordash.data.Dash
import com.spk.windsordash.data.DashState
import com.spk.windsordash.data.LinkStatus
import com.spk.windsordash.service.LinkService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class WindsorCarAppService : CarAppService() {
    // Personal, sideloaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = WindsorSession()
}

class WindsorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        Dash.init(carContext)
        val st = Dash.state.value
        if (st.status == LinkStatus.IDLE && st.adapter != null) LinkService.start(carContext)
        return LiveScreen(carContext)
    }
}

/** Re-renders whenever [Dash] changes, at most every 1.5 s (Android Auto throttles faster refreshes). */
abstract class DashScreen(ctx: CarContext) : Screen(ctx) {
    protected var st: DashState = Dash.state.value
    private var job: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                job = lifecycleScope.launch {
                    Dash.state.collect { new ->
                        if (new != st) {
                            st = new
                            invalidate()
                            delay(1500)
                        }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel(); job = null
            }
        })
    }

    protected fun row(title: CharSequence, text: CharSequence? = null, onClick: (() -> Unit)? = null): Row {
        val b = Row.Builder().setTitle(title)
        if (!text.isNullOrEmpty()) b.addText(text)
        if (onClick != null) b.setBrowsable(true).setOnClickListener { onClick() }
        return b.build()
    }

    protected fun colored(text: String, color: CarColor): CharSequence =
        if (text.isEmpty() || color == CarColor.DEFAULT) text else SpannableString(text).apply { setSpan(ForegroundCarColorSpan.create(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    protected fun list(title: String, rows: List<Row>, strip: ActionStrip? = null, back: Boolean = false): Template {
        val items = ItemList.Builder().apply { rows.forEach { addItem(it) } }.build()
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(if (back) Action.BACK else Action.APP_ICON)
            .setSingleList(items)
            .apply { if (strip != null) setActionStrip(strip) }
            .build()
    }

    protected fun statusRows(): List<Row> = listOf(
        row(
            when (st.status) {
                LinkStatus.IDLE -> "Not connected"
                LinkStatus.CONNECTING, LinkStatus.RETRYING -> "Connecting…"
                else -> "Waiting for data…"
            },
            st.statusText,
        )
    )

    protected fun kw(v: Double?) = v?.let { "%.1f kW".format(abs(it)) } ?: "–"
    protected fun pct(v: Double?) = v?.let { "${it.roundToInt()}%" } ?: "–"
    protected fun mins(m: Long) = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

class LiveScreen(ctx: CarContext) : DashScreen(ctx) {
    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder().addAction(
            if (st.status == LinkStatus.IDLE)
                Action.Builder().setTitle("Connect").setOnClickListener {
                    if (Dash.state.value.adapter == null) {
                        CarToast.makeText(carContext, "Pick an adapter on your phone first", CarToast.LENGTH_LONG).show()
                    } else if (!LinkService.start(carContext)) {
                        CarToast.makeText(carContext, "Open Windsor Dash on your phone to connect", CarToast.LENGTH_LONG).show()
                    }
                }.build()
            else
                Action.Builder().setTitle("Stop").setOnClickListener { LinkService.stop(carContext) }.build()
        ).build()

        if (!st.hasData) return list("Windsor Dash", statusRows(), strip)

        val s = st.sample
        val p = st.powerKw
        val powerTitle = when {
            p == null -> "Power –"
            st.charging -> "Charging ${kw(p)}"
            p < -0.2 -> "Regen ${kw(p)}"
            else -> "Power ${kw(p)}"
        }
        val va = listOfNotNull(s.packV?.let { "%.0f V".format(it) }, s.currentA?.let { "%.0f A".format(it) }).joinToString(" · ")
        val powerText: CharSequence = if (p != null && p < -0.2) colored(va, CarColor.GREEN) else va
        val rows = mutableListOf(
            row(
                "${pct(s.socPct)}  ·  ${st.rangeKm?.let { "$it km" } ?: "–"} range (est.)",
                s.energyLeftKwh?.let { "%.1f kWh left · at %d Wh/km (%s)".format(it, st.effWhKm?.roundToInt() ?: 0, st.effSource) },
            ),
            row(powerTitle, powerText),
            row(
                "Trip ${st.trip?.whPerKm?.let { "${it.roundToInt()} Wh/km" } ?: "–"}",
                st.trip?.let { "%d km · %.1f kWh used".format(it.km, it.netWh / 1000) },
            ) { screenManager.push(TripScreen(carContext)) },
        )
        val c = st.charge
        if (st.charging || c != null) rows += row(
            if (st.charging) "Charging session" else "Last charge",
            colored(
                (c?.let { "+%.1f kWh · peak %.0f kW".format(it.addedKwh, it.peakKw) } ?: "") +
                    (st.minutesToTarget?.let { " · est. ${mins(it.toLong())} to ${st.targetSoc}%" } ?: ""),
                if (st.charging) CarColor.GREEN else CarColor.DEFAULT,
            ),
        ) { screenManager.push(ChargingScreen(carContext)) }
        rows += row(
            "Battery ${s.battTempC?.let { "$it °C" } ?: "–"} · SoH ${pct(s.sohPct)}",
            st.cellDeltaMv?.let { "Cell spread $it mV" + (s.aux12V?.let { v -> " · 12 V %.1f".format(v) } ?: "") },
        )
        return list("Windsor Dash", rows, strip)
    }
}

class ChargingScreen(ctx: CarContext) : DashScreen(ctx) {
    override fun onGetTemplate(): Template {
        val c = st.charge ?: return list("Charging", listOf(row("No charging session yet", "Plug in and this fills in automatically")), back = true)
        val s = st.sample
        val strip = ActionStrip.Builder().addAction(
            Action.Builder().setTitle("Target ${st.targetSoc}%").setOnClickListener {
                val next = when (st.targetSoc) { 80 -> 90; 90 -> 100; else -> 80 }
                Dash.setTarget(next)
            }.build()
        ).build()
        val elapsed = ((c.endedAt ?: System.currentTimeMillis()) - c.startedAt) / 60_000
        val rows = listOf(
            row(
                if (c.active) "Charging at ${kw(st.powerKw)}" else "Charge finished",
                colored("Peak %.1f kW".format(c.peakKw), if (c.active) CarColor.GREEN else CarColor.DEFAULT),
            ),
            row("${pct(c.startSoc)} → ${pct(c.endSoc ?: s.socPct)}", "%.2f kWh added in %s".format(c.addedKwh, mins(elapsed))),
            row(
                "Est. time to ${st.targetSoc}%: " + (st.minutesToTarget?.let { mins(it.toLong()) } ?: if (c.active) "–" else "done"),
                "Estimate slows above 90% (LFP taper)",
            ),
            row("Pack ${s.battTempC?.let { "$it °C" } ?: "–"}", st.cellDeltaMv?.let { "Cell spread $it mV" }),
        )
        return list("Charging", rows, strip, back = true)
    }
}

class TripScreen(ctx: CarContext) : DashScreen(ctx) {
    override fun onGetTemplate(): Template {
        val t = st.trip
        val strip = ActionStrip.Builder().addAction(
            Action.Builder().setTitle("Reset").setOnClickListener { Dash.resetTrip() }.build()
        ).build()
        if (t == null) return list("Trip", listOf(row("No trip yet", "Starts when the link is live")), strip, back = true)
        val elapsed = (System.currentTimeMillis() - t.startedAt) / 60_000
        val rows = listOf(
            row("Efficiency ${t.whPerKm?.let { "${it.roundToInt()} Wh/km" } ?: "– (drive 1 km)"}",
                t.kwhPer100?.let { "%.1f kWh/100 km".format(it) }),
            row("${t.km} km in ${mins(elapsed)}", "Distance from the odometer (1 km steps)"),
            row("%.2f kWh used".format(t.netWh / 1000), "%.2f kWh recovered by regen".format(t.regenWh / 1000)),
            row("Est. range ${st.rangeKm?.let { "$it km" } ?: "–"}", "Based on ${st.effSource} (${st.effWhKm?.roundToInt() ?: "–"} Wh/km)"),
        )
        return list("Trip", rows, strip, back = true)
    }
}
