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
        val p = Portfolio.build(s, Samples.quotes, java.time.LocalDate.of(2026, 11, 4))
        assertEquals(listOf("Core", "Satellite", "Gold & funds"), p.groups.map { it.name })
        val rel = p.groups[0].rows.first { it.h.ticker == "RELIANCE" }
        assertEquals(40 * 1312.4, rel.value, 1e-6)
        assertEquals(40 * 12.4, rel.dayChange!!, 1e-6)
        // M&M had no live price: export price, no "today" figure, listed as missing
        val mm = p.groups[1].rows.first { it.h.ticker == "M&M" }
        assertEquals(15 * 3100.0, mm.value, 1e-6)
        assertNull(mm.dayChange)
        assertEquals(listOf("M&M"), p.missing)
        // SGB: manual price from the export (a live quote is ignored), no daily change, not "missing"
        val sgb = p.groups[2].rows.first { it.h.ticker == "SGBTEST" }
        assertEquals(150000.0, sgb.value, 1e-6)
        assertNull(sgb.dayChange)
        assertTrue(sgb.manual)
        assertEquals(p.groups.sumOf { g -> g.rows.sumOf { it.dayChange ?: 0.0 } }, p.dayChange, 1e-6)
        assertEquals(400000.0, p.cashTotal, 1e-6)
        assertEquals(p.holdingsValue + 400000.0 + 2100000.0, p.netWorth, 1e-6)
    }

    @Test fun parsesTheActionPlan() {
        val plan = SnapshotParser.parse(Samples.JSON).plan!!
        assertEquals("BEAR", plan.regime)
        assertEquals(listOf("sell", "buy", "stop", "input"), plan.sections.map { it.key })   // empty sections dropped
        assertEquals(90000.0, plan.sections[1].items[0].amount!!, 1e-9)
        assertNull(plan.sections[2].items[0].amount)
        assertNull(SnapshotParser.parse("{\"holdings\": []}").plan)
    }

    @Test fun rejectsOtherFiles() {
        val e = runCatching { SnapshotParser.parse("{\"hello\": 1}") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun previousCloseComesFromTheBars() {
        // 30 Oct close 996, 2 Nov close 1029.65, 3 Nov (today, live) 1066.10; chartPreviousClose is the older 996
        val body = """{"chart":{"result":[{"meta":{"regularMarketPrice":1066.1,"chartPreviousClose":996.0},
            "timestamp":[1793331000,1793590200,1793676600],
            "indicators":{"quote":[{"close":[996.0,1029.65,1066.1]}]}}]}}"""
        val q = Prices.parseYahoo(body, 0L, java.time.LocalDate.of(2026, 11, 3))!!
        assertEquals(1066.1, q.price, 1e-9)
        assertEquals(1029.65, q.prev!!, 1e-9)
        // Before today's first bar exists, the last bar is yesterday's close
        val pre = """{"chart":{"result":[{"meta":{"regularMarketPrice":1029.65},
            "timestamp":[1793331000,1793590200],"indicators":{"quote":[{"close":[996.0,1029.65]}]}}]}}"""
        assertEquals(1029.65, Prices.parseYahoo(pre, 0L, java.time.LocalDate.of(2026, 11, 3))!!.prev!!, 1e-9)
    }

    @Test fun hourlyBarsGiveLastCloseOfThePreviousDay() {
        // 2 Nov: 10:15, 14:15, 15:15 bars (last 1029.65); 3 Nov: 09:15 bar (today)
        val body = """{"chart":{"result":[{"meta":{"regularMarketPrice":1063.2,"chartPreviousClose":994.7},
            "timestamp":[1793594700,1793609100,1793612700,1793677500],
            "indicators":{"quote":[{"close":[1010.0,1025.0,1029.65,1063.2]}]}}]}}"""
        val q = Prices.parseYahoo(body, 0L, java.time.LocalDate.of(2026, 11, 3))!!
        assertEquals(1029.65, q.prev!!, 1e-9)
        // the last bar of a day is empty: use that day's last real close
        val gapLast = body.replace("1029.65,1063.2", "null,1063.2")
        assertEquals(1025.0, Prices.parseYahoo(gapLast, 0L, java.time.LocalDate.of(2026, 11, 3))!!.prev!!, 1e-9)
    }

    @Test fun emptyYahooBarMeansNoPreviousClose() {
        // Real Yahoo reply for ACE.NS on 3 Nov 2026: the 2 Nov bar is empty, so 30 Oct (994.70) must not be used.
        val body = """{"chart":{"result":[{"meta":{"regularMarketPrice":1063.2,"chartPreviousClose":1022.8},
            "timestamp":[1793159100,1793245500,1793331900,1793591100,1793677500],
            "indicators":{"quote":[{"close":[1022.8,1027.0,994.7,null,1063.2]}]}}]}}"""
        val q = Prices.parseYahoo(body, 0L, java.time.LocalDate.of(2026, 11, 3))!!
        assertEquals(1063.2, q.price, 1e-9)
        assertNull(q.prev)
    }

    @Test fun exportedTodayUsesTheLaptopsPreviousClose() {
        val s = SnapshotParser.parse(Samples.JSON)   // exported 2026-11-03, RELIANCE prev_close 1290
        val same = Portfolio.build(s, Samples.quotes, java.time.LocalDate.of(2026, 11, 3))
        assertEquals(40 * (1312.4 - 1290.0), same.groups[0].rows.first { it.h.ticker == "RELIANCE" }.dayChange!!, 1e-6)
        val later = Portfolio.build(s, Samples.quotes, java.time.LocalDate.of(2026, 11, 4))
        assertEquals(40 * (1312.4 - 1300.0), later.groups[0].rows.first { it.h.ticker == "RELIANCE" }.dayChange!!, 1e-6)
    }

    @Test fun yahooAndAmfiParsing() {
        val y = """{"chart":{"result":[{"meta":{"regularMarketPrice":1312.4,"previousClose":1300.0}}]}}"""
        assertEquals(1312.4, Prices.parseYahoo(y)!!.price, 1e-9)
        assertEquals(1300.0, Prices.parseYahoo(y)!!.prev!!, 1e-9)
        val m = """{"data":[{"date":"03-11-2026","nav":"92.80"},{"date":"31-10-2026","nav":"92.50"}]}"""
        assertEquals(92.8, Prices.parseMfapi(m)!!.price, 1e-9)
        assertEquals(listOf("SGBX.BO", "SGBX.NS"), Prices.yahooSymbols("SGBX"))
        assertTrue(Prices.isFund("120503"))
        assertEquals(listOf("^NSEI"), Prices.yahooSymbols("^NSEI"))
        assertEquals("+205.40", Fmt.level(205.4, sign = true))
        assertEquals("23,646.20", Fmt.level(23646.2))
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
