package il.salzol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Roadmap #4 — `ProductIndex.bestPairSplit`: the best pair of stores for a whole cart.
 *
 * The synthetic catalogs below are built so every scenario is exact in agorot:
 * store totals, the pair total, and the saving versus the cheapest single store.
 */
class PairSplitTest {

    private val now: Instant = Instant.parse("2026-11-13T10:00:00Z")

    private fun store(id: String, lat: Double, lon: Double) =
        Store(id = id, chain = id.substringBefore(":"), branch = "סניף", lat = lat, lon = lon)

    private fun row(code: String, prices: List<Int>) =
        CatalogProductRow(code = code, name = "מוצר $code", cat = "pantry", weighted = 0, prices = prices)

    private fun index(stores: List<Store>, rows: List<CatalogProductRow>): ProductIndex {
        val cat = Catalog(
            priceDate = "20261113",
            generatedAt = "20261113",
            skipped = emptyList(),
            areas = emptyList(),
            categories = CategoryNames,
            stores = stores,
            products = rows,
            promos = List(stores.size) { emptyList() },
            promoStats = List(stores.size) { PromoStats() },
            promoNotes = List(stores.size) { emptyList() },
            images = emptyList()
        )
        return ProductIndex.fromCatalog(cat, emptyMap())
    }

    private fun cart(vararg codes: String): Map<String, Double> = codes.associateWith { 1.0 }

    // ------------------------------------------------------------------ tests

    @Test
    fun splitsComplementaryCarts_andReportsExactSaving() {
        // A close to B, C a bit farther and expensive: A=3800, B=4000, C=8000 (complete singles)
        val idx = index(
            listOf(store("a:1", 32.0, 34.0), store("b:1", 32.01, 34.0), store("c:1", 32.2, 34.0)),
            listOf(
                row("p1", listOf(1000, 1200, 2000)),
                row("p2", listOf(1000, 1200, 2000)),
                row("p3", listOf(900, 800, 2000)),
                row("p4", listOf(900, 800, 2000))
            )
        )
        val c = cart("p1", "p2", "p3", "p4")
        val active = listOf(0, 1, 2)
        val cmp = idx.compare(c, active, emptyMap(), now)
        val pair = idx.bestPairSplit(c, active, emptyMap(), now, cmp)

        assertNotNull(pair)
        pair!!
        // baseline = cheapest complete single = A at 3800
        assertEquals(0, pair.baselineStore)
        assertEquals(3800, pair.baselineTotal)
        // pair buys p1,p2 at A (2000) and p3,p4 at B (1600) = 3600, saving exactly 200
        assertEquals(setOf(0, 1), setOf(pair.a, pair.b))
        assertEquals(3600, pair.total)
        assertEquals(200, pair.saving)
        assertEquals(2000, pair.totalA)
        assertEquals(1600, pair.totalB)
        assertEquals(2, pair.itemsA)
        assertEquals(2, pair.itemsB)
        assertTrue(pair.missing.isEmpty())
        // the stores are ~1.1 km apart
        assertTrue("km=${pair.km}", pair.km in 1.0..1.3)
        // per-line assignment
        val where = pair.lines.associate { it.p.code to it.store }
        assertEquals(0, where["p1"])
        assertEquals(0, where["p2"])
        assertEquals(1, where["p3"])
        assertEquals(1, where["p4"])
        // totals are consistent with the lines
        assertEquals(pair.total, pair.lines.sumOf { it.cost }.toInt())
    }

    @Test
    fun returnsNull_whenNoSecondStopPaysForItself() {
        // A cheapest everywhere (1000 all), B=2000, C=1500: every pair collapses onto A
        val idx = index(
            listOf(store("a:1", 32.0, 34.0), store("b:1", 32.005, 34.0), store("c:1", 32.01, 34.0)),
            listOf(
                row("p1", listOf(1000, 2000, 1500)),
                row("p2", listOf(1000, 2000, 1500)),
                row("p3", listOf(1000, 2000, 1500)),
                row("p4", listOf(1000, 2000, 1500))
            )
        )
        val c = cart("p1", "p2", "p3", "p4")
        val active = listOf(0, 1, 2)
        val cmp = idx.compare(c, active, emptyMap(), now)
        assertNull(idx.bestPairSplit(c, active, emptyMap(), now, cmp))
    }

    @Test
    fun prefersNearbyPair_overCheaperPairAcrossTown() {
        // A and B close and complementary → 3600 (saves 200 vs A=3800).
        // C would make A+C and B+C cost 3300, but C is ~22 km away — must NOT be picked.
        val idx = index(
            listOf(store("a:1", 32.0, 34.0), store("b:1", 32.01, 34.0), store("c:1", 32.2, 34.0)),
            listOf(
                row("p1", listOf(1000, 1200, 500)),
                row("p2", listOf(1000, 1200, 1500)),
                row("p3", listOf(900, 800, 1500)),
                row("p4", listOf(900, 800, 1400))
            )
        )
        val c = cart("p1", "p2", "p3", "p4")
        val active = listOf(0, 1, 2)
        val cmp = idx.compare(c, active, emptyMap(), now)
        val pair = idx.bestPairSplit(c, active, emptyMap(), now, cmp)

        assertNotNull(pair)
        pair!!
        assertEquals(setOf(0, 1), setOf(pair.a, pair.b))
        assertEquals(3600, pair.total)
        assertTrue("km=${pair.km}", pair.km in 0.0..5.0)
    }

    @Test
    fun fallsBackToFartherPair_whenNoNearbyPairQualifies() {
        // B never beats A (4400 vs 4000) → A+B degenerates to one store.
        // C ~22 km away is complementary to A: A+C = 3800 (saves 200) and must be offered.
        val idx = index(
            listOf(store("a:1", 32.0, 34.0), store("b:1", 32.01, 34.0), store("c:1", 32.2, 34.0)),
            listOf(
                row("p1", listOf(1000, 1100, 800)),
                row("p2", listOf(1000, 1100, 1300)),
                row("p3", listOf(1000, 1100, 1300)),
                row("p4", listOf(1000, 1100, 1300))
            )
        )
        val c = cart("p1", "p2", "p3", "p4")
        val active = listOf(0, 1, 2)
        val cmp = idx.compare(c, active, emptyMap(), now)
        val pair = idx.bestPairSplit(c, active, emptyMap(), now, cmp)

        assertNotNull(pair)
        pair!!
        assertEquals(setOf(0, 2), setOf(pair.a, pair.b))
        assertEquals(3800, pair.total)
        assertEquals(200, pair.saving)
        assertTrue("km=${pair.km}", pair.km > 5.0)
    }

    @Test
    fun rejectsPairThatLeavesMoreItemsThanTheBaselineStore() {
        // A sells p1,p2 · B sells p3,p4 · only the far single store C sells everything.
        // The close pair would cost 4200 (vs C's 13000) but lacks p4 that C has → rejected,
        // and C is ~190 km away, beyond the hard 25 km cap → no split at all.
        val idx = index(
            listOf(store("a:1", 32.0, 34.0), store("b:1", 32.01, 34.0), store("c:1", 33.5, 35.0)),
            listOf(
                row("p1", listOf(100, 4000, 4000)),
                row("p2", listOf(4000, 100, 4000)),
                row("p3", listOf(4000, 4000, 4000)),
                row("p4", listOf(-1, -1, 1000))
            )
        )
        val c = cart("p1", "p2", "p3", "p4")
        val active = listOf(0, 1, 2)
        val cmp = idx.compare(c, active, emptyMap(), now)
        assertNull(idx.bestPairSplit(c, active, emptyMap(), now, cmp))
    }
}
