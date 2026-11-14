package il.salzol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap #3 — PriceTrend: daily cheapest-shelf reconstruction from price changes,
 * trend summaries (cheapest-now, direction) and hike-before-promo fake-deal detection.
 */
class PriceTrendTest {

    private fun change(day: String, store: String, from: Int, to: Int, code: String = "x") =
        Change(day = day, code = code, store = store, from = from, to = to)

    private fun promo(start: String, vararg codes: String) = Promo(
        id = "p1", codes = codes.toList(), minQty = 1, kind = "price", value = 10.0, start = start
    )

    // ------------------------------------------------------------------ parseDay

    @Test
    fun parsesIsoAndCompactDates() {
        assertEquals(java.time.LocalDate.of(2026, 11, 13), PriceTrend.parseDay("2026-11-13"))
        assertEquals(java.time.LocalDate.of(2026, 11, 13), PriceTrend.parseDay("20261113"))
        assertEquals(java.time.LocalDate.of(2026, 11, 13), PriceTrend.parseDay("202611130900"))
        assertNull(PriceTrend.parseDay(""))
        assertNull(PriceTrend.parseDay("not-a-day"))
    }

    // ------------------------------------------------------------------ summarize

    @Test
    fun summarize_reportsRangeDirectionAndCheapestNow() {
        val s = PriceTrend.summarize(listOf(
            PricePoint("2026-11-01", 500), PricePoint("2026-11-05", 520),
            PricePoint("2026-11-10", 480), PricePoint("2026-11-13", 480)
        ))!!
        assertEquals(480, s.min); assertEquals(520, s.max)
        assertEquals(500, s.first); assertEquals(480, s.last)
        assertTrue(s.cheapestNow)          // last == min
        assertEquals(-1, s.direction)      // −4% first→last
        assertNull(PriceTrend.summarize(emptyList()))
    }

    @Test
    fun summarize_directionHasADeadband() {
        val flat = PriceTrend.summarize(listOf(PricePoint("2026-11-01", 500), PricePoint("2026-11-13", 500)))!!
        assertEquals(0, flat.direction)
        assertTrue(flat.cheapestNow)
        // +2.4% stays flat; +12% is up
        val wobble = PriceTrend.summarize(listOf(PricePoint("2026-11-01", 500), PricePoint("2026-11-13", 512)))!!
        assertEquals(0, wobble.direction)
        assertFalse(wobble.cheapestNow)
        val up = PriceTrend.summarize(listOf(PricePoint("2026-11-01", 500), PricePoint("2026-11-13", 560)))!!
        assertEquals(1, up.direction)
    }

    // ------------------------------------------------------------------ dailyMinSeries

    @Test
    fun series_stepsAcrossStores_andShowsTheFromPriceBeforeTheFirstChange() {
        // store a raises 1000→1200 on 11-12; store b stays 1100 the whole time
        val series = PriceTrend.dailyMinSeries(
            listOf(change("2026-11-12", "a", 1000, 1200)),
            mapOf("a" to 1200, "b" to 1100),
            today = "2026-11-13"
        )
        // starts one day before the change: from-price 1000 wins, then b's 1100 takes over
        assertEquals(
            listOf(PricePoint("2026-11-11", 1000), PricePoint("2026-11-12", 1100), PricePoint("2026-11-13", 1100)),
            series
        )
    }

    @Test
    fun series_listingWithZeroFromHidesTheStoreBeforeItsFirstDay() {
        // store a listed on 11-12 (from=0): before that only store b is on the shelf
        val series = PriceTrend.dailyMinSeries(
            listOf(change("2026-11-12", "a", 0, 900)),
            mapOf("a" to 900, "b" to 1100),
            today = "2026-11-13"
        )
        assertEquals(PricePoint("2026-11-11", 1100), series.first())
        assertEquals(PricePoint("2026-11-12", 900), series[1])
        assertEquals(PricePoint("2026-11-13", 900), series.last())
    }

    @Test
    fun series_capsAtTheWindow_andFallsBackToTodayWithoutChanges() {
        // a change older than the 90-day window is dropped → only today remains
        val old = java.time.LocalDate.now().minusDays(120).toString()
        val series = PriceTrend.dailyMinSeries(
            listOf(change(old, "a", 1000, 1200)),
            mapOf("a" to 1200),
            today = java.time.LocalDate.now().toString()
        )
        assertEquals(1, series.size)
        assertEquals(java.time.LocalDate.now().toString(), series[0].day)
        // no changes at all → a single point at the current price
        val none = PriceTrend.dailyMinSeries(emptyList(), mapOf("a" to 700), today = "2026-11-13")
        assertEquals(listOf(PricePoint("2026-11-13", 700)), none)
        // nowhere selling and no history → empty
        assertTrue(PriceTrend.dailyMinSeries(emptyList(), emptyMap(), today = "2026-11-13").isEmpty())
    }

    // ------------------------------------------------------------------ hikeBeforePromo

    @Test
    fun hike_flagsRaiseWithinWindowBeforePromoStart() {
        // +15% five days before a promo starting 2026-11-20 → alert
        val alerts = PriceTrend.hikeBeforePromo(
            "x",
            listOf(change("2026-11-15", "a", 1000, 1150)),
            listOf(promo("20261120", "x"))
        )
        assertEquals(1, alerts.size)
        val h = alerts[0]
        assertEquals("2026-11-15", h.day)
        assertEquals(1000, h.from); assertEquals(1150, h.to)
        assertEquals(15, h.pct)
        assertEquals("20261120", h.promoDay)
    }

    @Test
    fun hike_ignoresTooOldTooSmallTooLateOrUnrelatedRaises() {
        val promos = listOf(promo("20261120", "x"))
        // 19 days before the promo → outside the 14-day window
        assertTrue(PriceTrend.hikeBeforePromo("x", listOf(change("2026-11-01", "a", 1000, 1200)), promos).isEmpty())
        // +5% → below the 10% threshold
        assertTrue(PriceTrend.hikeBeforePromo("x", listOf(change("2026-11-15", "a", 1000, 1050)), promos).isEmpty())
        // on/after the promo start → not "before"
        assertTrue(PriceTrend.hikeBeforePromo("x", listOf(change("2026-11-20", "a", 1000, 1200)), promos).isEmpty())
        assertTrue(PriceTrend.hikeBeforePromo("x", listOf(change("2026-11-22", "a", 1000, 1200)), promos).isEmpty())
        // different product's promo / no promo / price drop / listing
        assertTrue(PriceTrend.hikeBeforePromo("x", listOf(change("2026-11-15", "a", 1000, 1200)), listOf(promo("20261120", "y"))).isEmpty())
        assertTrue(PriceTrend.hikeBeforePromo("x", listOf(change("2026-11-15", "a", 1000, 1200)), emptyList()).isEmpty())
        assertTrue(PriceTrend.hikeBeforePromo("x", listOf(change("2026-11-15", "a", 1200, 1000)), promos).isEmpty())
        assertTrue(PriceTrend.hikeBeforePromo("x", listOf(change("2026-11-15", "a", 0, 1200)), promos).isEmpty())
    }

    @Test
    fun hike_acceptsIsoPromoDates_andSortsBySeverity() {
        val alerts = PriceTrend.hikeBeforePromo(
            "x",
            listOf(
                change("2026-11-14", "a", 1000, 1100),  // +10% — exactly at the threshold
                change("2026-11-16", "a", 1000, 1300)   // +30%
            ),
            listOf(promo("2026-11-20", "x"))           // ISO start also accepted
        )
        assertEquals(listOf(30, 10), alerts.map { it.pct })
        assertEquals("20261120", alerts[0].promoDay) // normalized to yyyyMMdd
    }
}
