package com.spk.tfholdings

import com.spk.tfholdings.data.Fmt
import com.spk.tfholdings.data.Portfolio
import com.spk.tfholdings.data.Prices
import com.spk.tfholdings.data.SnapshotParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortfolioTest {
    @Test fun parsesExportAndValuesLive() {
        val s = SnapshotParser.parse(Samples.JSON)
        assertEquals(7, s.holdings.size)
        assertEquals("2027-01-08", s.others[1].maturityDate)
        assertNull(s.others[0].maturityDate)
        val p = Portfolio.build(s, Samples.quotes)
        assertEquals(listOf("Core", "Satellite", "Gold & funds"), p.groups.map { it.name })
        val rel = p.groups[0].rows.first { it.h.ticker == "RELIANCE" }
        assertEquals(40 * 1312.4, rel.value, 1e-6)
        assertEquals(40 * 12.4, rel.dayChange!!, 1e-6)
        // M&M had no live price: export price, no "today" figure, listed as missing
        val mm = p.groups[1].rows.first { it.h.ticker == "M&M" }
        assertEquals(15 * 3100.0, mm.value, 1e-6)
        assertNull(mm.dayChange)
        assertEquals(listOf("M&M"), p.missing)
        // SGB: live price used for value, but no daily change
        val sgb = p.groups[2].rows.first { it.h.ticker == "SGBTEST" }
        assertEquals(158000.0, sgb.value, 1e-6)
        assertNull(sgb.dayChange)
        assertEquals(p.groups.sumOf { g -> g.rows.sumOf { it.dayChange ?: 0.0 } }, p.dayChange, 1e-6)
        assertEquals(400000.0, p.cashTotal, 1e-6)
        assertEquals(p.holdingsValue + 400000.0 + 2100000.0, p.netWorth, 1e-6)
    }

    @Test fun rejectsOtherFiles() {
        val e = runCatching { SnapshotParser.parse("{\"hello\": 1}") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun yahooAndAmfiParsing() {
        val y = """{"chart":{"result":[{"meta":{"regularMarketPrice":1312.4,"chartPreviousClose":1300.0}}]}}"""
        assertEquals(1312.4, Prices.parseYahoo(y)!!.price, 1e-9)
        assertEquals(1300.0, Prices.parseYahoo(y)!!.prev!!, 1e-9)
        val m = """{"data":[{"date":"03-11-2026","nav":"92.80"},{"date":"31-10-2026","nav":"92.50"}]}"""
        assertEquals(92.8, Prices.parseMfapi(m)!!.price, 1e-9)
        assertEquals(listOf("SGBX.BO", "SGBX.NS"), Prices.yahooSymbols("SGBX"))
        assertTrue(Prices.isFund("120503"))
    }

    @Test fun indianFormatting() {
        assertEquals("₹12,34,567", Fmt.inr(1234567.0))
        assertEquals("−₹1,000.50", Fmt.inr(-1000.5, 2))
        assertEquals("+₹500", Fmt.inr(500.0, sign = true))
        assertEquals("₹1.78 Cr", Fmt.compact(17800000.0))
        assertEquals("₹12.34 L", Fmt.compact(1234000.0))
        assertEquals("+1.25%", Fmt.pct(1.25))
        assertEquals("−0.50%", Fmt.pct(-0.5))
    }
}
