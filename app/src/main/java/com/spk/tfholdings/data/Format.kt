package com.spk.tfholdings.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object Fmt {
    /** ₹ with Indian digit grouping: 12,34,567. */
    fun inr(x: Double, decimals: Int = 0, sign: Boolean = false): String {
        val neg = x < 0
        val a = abs(x)
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val units = (a * scale).roundToLong()
        val whole = units / scale
        val digits = whole.toString()
        val grouped = if (digits.length <= 3) digits else {
            val last3 = digits.takeLast(3)
            val rest = digits.dropLast(3)
            rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
        }
        val frac = if (decimals > 0) "." + (units % scale).toString().padStart(decimals, '0') else ""
        val s = when {
            neg -> "−"
            sign && x > 0 -> "+"
            else -> ""
        }
        return "$s₹$grouped$frac"
    }

    /** ₹1.78 Cr / ₹12.34 L / ₹56,789 */
    fun compact(x: Double, sign: Boolean = false): String {
        val a = abs(x)
        val s = when {
            x < 0 -> "−"
            sign && x > 0 -> "+"
            else -> ""
        }
        return when {
            a >= 1e7 -> s + "₹" + String.format(Locale.US, "%.2f", a / 1e7) + " Cr"
            a >= 1e5 -> s + "₹" + String.format(Locale.US, "%.2f", a / 1e5) + " L"
            else -> s + inr(a)
        }
    }

    fun pct(x: Double?, sign: Boolean = true): String = when {
        x == null -> "–"
        sign && x > 0 -> "+" + String.format(Locale.US, "%.2f", x) + "%"
        x < 0 -> "−" + String.format(Locale.US, "%.2f", abs(x)) + "%"
        else -> String.format(Locale.US, "%.2f", x) + "%"
    }

    fun price(x: Double): String = inr(x, 2)

    /** 23,646.20 (Indian grouping, no ₹), for index levels. */
    fun level(x: Double, sign: Boolean = false): String = inr(x, 2, sign).replace("₹", "")

    fun qty(x: Double): String =
        if (x == Math.floor(x)) String.format(Locale.US, "%,.0f", x) else String.format(Locale.US, "%,.3f", x)

    /** "3 Nov, 09:30" from the export's ISO time. */
    fun when_(iso: String): String = try {
        LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH))
    } catch (e: Exception) {
        iso
    }
}
