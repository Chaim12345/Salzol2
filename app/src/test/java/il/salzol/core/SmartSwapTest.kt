package il.salzol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Roadmap #2 — Smart Swaps (`ProductIndex.swapsFor`): substitutes for a cart line the store
 * doesn't sell, ranked by AI product type, category and unit-price proximity (ShopUnits),
 * while excluding the rest of the cart, absurdly expensive items and unrelated products.
 *
 * Names contain no digits on purpose: a parsed pack size would skew the unit-price math.
 */
class SmartSwapTest {

    private val now: Instant = Instant.parse("2026-11-13T10:00:00Z")

    private fun store(id: String) = Store(id = id, chain = id.substringBefore(":"), branch = "סניף")

    /** Every product states a 1000 g pack so unit prices are directly comparable. */
    private fun row(code: String, name: String, cat: String, prices: List<Int?>) = CatalogProductRow(
        code = code, name = name, cat = cat, weighted = 0, prices = prices,
        meta = ProductMeta(sizeAmount = 1000.0, sizeUnit = "g")
    )

    private fun index(): ProductIndex {
        val stores = listOf(store("a:1"), store("b:1"))
        val rows = listOf(
            row("x", "חלב מלא", "pantry", listOf(1000, -1)),     // missing at store b
            row("sub1", "חלב יומי", "pantry", listOf(900, 850)),  // same type, cheaper than the original
            row("sub2", "חלב עלית", "pantry", listOf(1100, 960)), // same type, closest to the original's price
            row("bigpack", "חלב ענק", "pantry", listOf(1600, 2000)), // same type, pricey but under the 3× cap
            row("sub3", "גבינה לבנה", "pantry", listOf(800, 1400)),  // category only, no AI type
            row("pricy", "חלב פרימיום", "pantry", listOf(1000, 4000)), // same type, 4× → dropped by the cap
            row("other", "סבון כלים", "cleaning", listOf(700, 750))   // unrelated type and category
        )
        val recs = mapOf(
            "x" to AiRecord(t = "milk"),
            "sub1" to AiRecord(t = "milk"),
            "sub2" to AiRecord(t = "milk"),
            "bigpack" to AiRecord(t = "milk"),
            "pricy" to AiRecord(t = "milk"),
            "other" to AiRecord(t = "clean")
        )
        val cat = Catalog(
            priceDate = "20261113", generatedAt = "20261113",
            skipped = emptyList(), areas = emptyList(), categories = CategoryNames,
            stores = stores, products = rows,
            promos = List(2) { emptyList() }, promoStats = List(2) { PromoStats() },
            promoNotes = List(2) { emptyList() }, images = emptyList()
        )
        return ProductIndex.fromCatalog(cat, recs)
    }

    private fun cart(vararg codes: String): Map<String, Double> = codes.associateWith { 1.0 }

    // ------------------------------------------------------------------ tests

    @Test
    fun ranksByTypeThenCategoryAndDropsCapOrUnrelatedItems() {
        val idx = index()
        val x = idx.byCode.getValue("x")
        val swaps = idx.swapsFor(x, 2.0, 1, emptyMap(), now)

        // type matches lead (priced-closest first), then the category-only substitute;
        // the 4×-priced "pricy" and the unrelated "other" never appear
        assertEquals(listOf("sub2", "sub1", "bigpack", "sub3"), swaps.map { it.p.code })
        // promo-aware costs at store b: 960×2 = 1920, 850×2 = 1700
        assertEquals(1920.0, swaps[0].cost, 0.01)
        assertEquals(1700.0, swaps[1].cost, 0.01)
        assertFalse(swaps.any { it.p.code == "pricy" })
        assertFalse(swaps.any { it.p.code == "other" })
    }

    @Test
    fun honorsLimitAndExclude() {
        val idx = index()
        val x = idx.byCode.getValue("x")

        assertEquals(3, idx.swapsFor(x, 1.0, 1, emptyMap(), now, limit = 3).size)

        val excl = idx.swapsFor(x, 1.0, 1, emptyMap(), now, exclude = setOf("sub1", "sub2"))
        assertFalse(excl.any { it.p.code in setOf("sub1", "sub2") })
        assertTrue(excl.any { it.p.code == "sub3" })
    }

    @Test
    fun emptyWhenNothingSuitableAtThatStore() {
        val idx = index()
        val x = idx.byCode.getValue("x")
        // out-of-range store
        assertTrue(idx.swapsFor(x, 1.0, 99, emptyMap(), now).isEmpty())
        // "other" has no same-type or same-category relatives at all
        val other = idx.byCode.getValue("other")
        assertTrue(idx.swapsFor(other, 1.0, 0, emptyMap(), now).isEmpty())
    }

    @Test
    fun compareReportsMissingCodes_forSwapTotals() {
        val idx = index()
        val cmp = idx.compare(cart("x", "sub1"), listOf(0, 1), emptyMap(), now)

        // store b sells sub1 but not x → exactly one missing code
        val atB = cmp.rows.first { it.store == 1 }
        assertEquals(listOf("x"), atB.missingCodes)
        assertEquals(listOf("חלב מלא"), atB.missing)
        // store a sells everything
        val atA = cmp.rows.first { it.store == 0 }
        assertTrue(atA.missingCodes.isEmpty())
    }
}
