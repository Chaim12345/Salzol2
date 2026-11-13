package il.salzol.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeSet
import kotlin.math.*

private val israelZone: ZoneId = runCatching { ZoneId.of("Asia/Jerusalem") }.getOrElse { ZoneId.systemDefault() }
private val stamp12Fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

const val STALE_DAYS = 30

class ShopProduct(
    val code: String,
    val name: String,
    var cat: String,
    val weighted: Boolean,
    val prices: IntArray, // -1 = missing
    val picks: Array<String>?,
    val img: String,
    val maker: String,
    val il: Boolean,
    val stale: Map<Int, Int>,
    val chg: Map<Int, Int>,
    val np: List<Int>,
    val odd: List<Pair<Int, Int>>,
    val rawName: String,
    val statedSize: StatedSize?,
    val statedAgree: Int,
    var ai: AiRecord? = null,
    var searchKey: String = "",
    var sizeDone: Boolean = false,
    var cachedSize: Size? = null
) {
    fun isStale(storeIdx: Int): Boolean = (stale[storeIdx] ?: 0) >= STALE_DAYS

    fun priceInfo(active: List<Int>): PriceInfo {
        var lo = -1; var hi = -1; var at = -1; var n = 0
        var slo = -1; var shi = -1; var sat = -1
        for (i in active) {
            val v = prices.getOrElse(i) { -1 }
            if (v < 0) continue
            n++
            if (isStale(i)) {
                if (slo < 0 || v < slo) { slo = v; sat = i }
                if (v > shi) shi = v
                continue
            }
            if (lo < 0 || v < lo) { lo = v; at = i }
            if (v > hi) hi = v
        }
        if (lo < 0) {
            lo = slo; hi = shi; at = sat
        }
        return PriceInfo(lo, hi, at, n)
    }

    fun listedAt(active: List<Int>): Int {
        for (i in active) {
            if (np.contains(i)) return i
        }
        return np.firstOrNull() ?: -1
    }
}

data class PriceInfo(val lo: Int, val hi: Int, val at: Int, val n: Int)

data class Hit(val p: ShopProduct, val pi: PriceInfo) {
    fun unit(): UnitPrice? {
        if (pi.lo <= 0) return null
        val z = ShopUnits.sizeOf(p) ?: return null
        return ShopUnits.unitPriceOf(z, pi.lo.toDouble())
    }
}

data class SearchOpts(
    val cat: String = "",
    val sort: String = "",
    val keep: ((ShopProduct) -> Boolean)? = null,
    val active: List<Int> = emptyList()
)

data class Priced(val cost: Double, val shelf: Double, val promo: Promo? = null)
data class BestDealResult(val store: Int, val promo: Promo, val unit: Double, val shelf: Double)

data class StoreTotal(
    val store: Int,
    val total: Int,
    val shelf: Int,
    val fair: Int,
    val fairShelf: Int,
    val deals: Int,
    val missing: List<String>,
    val doubt: List<String>,
    val missingCodes: List<String> = emptyList()
)

data class CartComparisonLine(
    val p: ShopProduct,
    val q: Double,
    val store: Int,
    val cost: Double,
    val shelf: Double,
    val promo: Promo?
)

data class CartComparison(
    val lines: List<CartComparisonLine>,
    val rows: List<StoreTotal>,
    val common: Int,
    val fairLow: Int,
    val split: Int,
    val splitShelf: Int,
    val splitStores: Int
)

/** One line of the 2-store split (roadmap #4): bought at [store], one of the chosen pair. */
data class PairSplitLine(
    val p: ShopProduct,
    val q: Double,
    val store: Int,
    val cost: Double,
    val shelf: Double,
    val promo: Promo?
)

/** Roadmap #2 (Smart Swaps): a substitute offered instead of a missing cart line at one store. */
data class SwapCandidate(
    val p: ShopProduct,
    val cost: Double,   // promo-aware cost for the requested qty at that store (agorot)
    val shelf: Double,  // cost without promotions (agorot)
    val score: Double,  // type/category/unit-price ranking, higher = better fit
    val promo: Promo?
)

/**
 * Roadmap #4 — the best *pair* of stores for the whole cart. Every line goes to whichever of the
 * two chosen stores sells it cheaper, the pair beats the cheapest single store by [saving] agorot
 * without leaving more items unsold than [baselineStore] did, and [km] is the drive between the
 * two stops (< 0 when either coordinates are unknown).
 */
data class PairSplit(
    val a: Int,
    val b: Int,
    val total: Int,
    val shelf: Int,
    val totalA: Int,
    val totalB: Int,
    val itemsA: Int,
    val itemsB: Int,
    val saving: Int,
    val baselineStore: Int,
    val baselineTotal: Int,
    val km: Double,
    val missing: List<String>,
    val lines: List<PairSplitLine>
)

data class Deal(
    val id: String,
    val p: ShopProduct,
    val chain: String,
    val chainLabel: String,
    val promo: Promo,
    val stores: MutableList<Int>,
    var rep: Int,
    val qty: Int,
    var unit: Double,
    var shelf: Double,
    val end: Instant?,
    var savePct: Double = 0.0,
    var saveAgorot: Int = 0,
    var cheapest: Double = 0.0,
    var cheapestAt: Int = -1,
    var compared: Int = 0,
    var realPct: Double = 0.0,
    var realAgorot: Int = 0,
    var verdict: String = "alone", // "real", "notCheapest", "alone"
    var confidence: String = "ok", // "ok", "check"
    var checkKind: String = "",
    var why: String = "",
    var ai: String = "" // "", "yes", "no", "unclear"
) {
    fun endingSoon(now: Instant): Boolean =
        end != null && !end.isBefore(now) && java.time.Duration.between(now, end).toHours() <= 48

    fun endLabel(now: Instant): String {
        if (end == null) return ""
        val e = end.atZone(israelZone)
        val n = now.atZone(israelZone)
        if (e.dayOfYear == n.dayOfYear && e.year == n.year) return "עד היום"
        return "עד ${e.dayOfMonth}.${e.monthValue}"
    }

    fun score(mine: Set<String>, now: Instant): Double {
        if (verdict == "notCheapest") return 0.0
        val save = if (verdict == "real") realAgorot.toDouble() else saveAgorot * 0.5
        var s = ln(1.0 + max(0.0, save) / 100.0) * (1.0 + max(0.0, realPct) / 100.0)
        if (mine.contains(p.code)) s *= 2.0
        if (confidence == "check") s *= 0.5
        if (qty >= 3 && perishableCats.contains(p.cat)) s *= 0.7
        if (endingSoon(now)) s *= 1.15
        return s
    }

    companion object {
        private val perishableCats = setOf("dairy", "meat", "bakery", "produce")
        val stockUpCats = setOf("pantry", "cleaning", "care", "drinks", "snacks", "pets")
    }
}

data class DealFilter(
    val cat: String = "",
    val chain: String = "",
    val onlyReal: Boolean = false,
    val minSave: Int = 0,
    val sort: String = "useful",
    val mine: Set<String> = emptySet()
)

class ProductIndex(
    val cat: Catalog,
    val products: List<ShopProduct>,
    val byCode: Map<String, ShopProduct>,
    val stores: List<Store>,
    val chains: List<String>,
    private val promosByStore: List<Map<String, List<Promo>>>,
    private val notesByStore: List<Map<String, List<PromoNote>>>,
    private val words: Map<String, IntArray>,
    private val vocab: List<String>
) {
    companion object {
        fun norm(s: String): String {
            return s.replace("\"", "").replace("'", "").replace("׳", "").replace("״", "").replace("`", "")
                .trim().replace(Regex("""\s+"""), " ").lowercase()
        }

        fun fromCatalog(c: Catalog, recs: Map<String, AiRecord>): ProductIndex {
            val validCats = c.categories.map { it.id }.toHashSet()
            val nStores = c.stores.size
            val chains = c.stores.map { it.id.substringBefore(":") }
            val byCode = HashMap<String, ShopProduct>(c.products.size)
            val products = ArrayList<ShopProduct>(c.products.size)

            for (row in c.products) {
                val prices = IntArray(nStores) { idx -> row.prices.getOrNull(idx) ?: -1 }
                val picks = row.picks?.let { pk -> Array(nStores) { idx -> pk.getOrNull(idx) ?: "" } }
                val m = row.meta
                val statedSize = if (m?.sizeAmount != null && m.sizeUnit != null) {
                    val k = when (m.sizeUnit) {
                        "g" -> UnitKind.Weight
                        "ml" -> UnitKind.Volume
                        else -> UnitKind.Count
                    }
                    StatedSize(k, m.sizeAmount)
                } else null
                val rec = recs[row.code]
                val effectiveCat = if (rec != null && rec.c.isNotEmpty() && validCats.contains(rec.c)) rec.c else row.cat
                val p = ShopProduct(
                    code = row.code,
                    name = row.name,
                    cat = effectiveCat,
                    weighted = row.weighted == 1,
                    prices = prices,
                    picks = picks,
                    img = row.img ?: "",
                    maker = m?.maker ?: "",
                    il = m?.il ?: false,
                    stale = m?.stale?.toMap() ?: emptyMap(),
                    chg = m?.chg?.toMap() ?: emptyMap(),
                    np = m?.np ?: emptyList(),
                    odd = m?.odd ?: emptyList(),
                    rawName = m?.raw ?: "",
                    statedSize = statedSize,
                    statedAgree = m?.sizeAgree ?: 0,
                    ai = rec
                )
                p.searchKey = buildSearchKey(p)
                byCode[p.code] = p
                products.add(p)
            }

            val wordLists = HashMap<String, MutableList<Int>>()
            products.forEachIndexed { i, p ->
                val seen = HashSet<String>()
                for (w in p.searchKey.split(" ")) {
                    if (w.isNotEmpty() && seen.add(w)) {
                        wordLists.getOrPut(w) { ArrayList() }.add(i)
                    }
                }
            }
            val words = wordLists.mapValues { it.value.toIntArray() }
            val vocab = words.keys.sorted()

            val promosByStore = c.stores.indices.map { i ->
                val map = HashMap<String, MutableList<Promo>>()
                c.promos.getOrNull(i)?.forEach { pr ->
                    pr.codes.forEach { code -> map.getOrPut(code) { mutableListOf() }.add(pr) }
                }
                map
            }
            val notesByStore = c.stores.indices.map { i ->
                val map = HashMap<String, MutableList<PromoNote>>()
                c.promoNotes.getOrNull(i)?.forEach { pn ->
                    pn.codes.forEach { code -> map.getOrPut(code) { mutableListOf() }.add(pn) }
                }
                map
            }

            return ProductIndex(c, products, byCode, c.stores, chains, promosByStore, notesByStore, words, vocab)
        }

        private fun buildSearchKey(p: ShopProduct): String {
            val parts = mutableListOf(p.name)
            if (p.maker.isNotEmpty() && !norm(p.name).contains(norm(p.maker))) {
                parts.add(p.maker)
            }
            if (p.rawName.isNotEmpty()) parts.add(p.rawName)
            p.ai?.let { r ->
                if (r.t.isNotEmpty()) parts.add(r.t)
                if (r.b.isNotEmpty()) parts.add(r.b)
                if (r.l.isNotEmpty()) parts.add(r.l)
                if (r.v.isNotEmpty()) parts.add(r.v)
                r.d.forEach { d -> DietLabel[d]?.let { parts.add(it) } }
            }
            return norm(parts.joinToString(" "))
        }

        fun parseStampInstant(s: String): Instant? {
            if (s.length < 12) return null
            return runCatching {
                LocalDateTime.parse(s.substring(0, 12), stamp12Fmt).atZone(israelZone).toInstant()
            }.getOrNull()
        }

        fun promoCost(pr: Promo, unit: Double, q: Double): Double {
            val cp = if (pr.max > 0) min(q, pr.max.toDouble()) else q
            val rest = (q - cp) * unit
            return when (pr.kind) {
                "price" -> rest + cp * pr.value
                "percent" -> rest + cp * unit * (1.0 - pr.value / 100.0)
                else -> {
                    val minQ = maxOf(1, pr.minQty).toDouble()
                    val n = floor(cp / minQ)
                    rest + n * pr.value + (cp - n * minQ) * unit
                }
            }
        }

        fun dealUnit(pr: Promo, shelf: Double): Double = when (pr.kind) {
            "price" -> pr.value
            "percent" -> shelf * (1.0 - pr.value / 100.0)
            else -> pr.value / maxOf(1, pr.minQty).toDouble()
        }

        fun dealText(pr: Promo): String = when (pr.kind) {
            "bundle" -> "${pr.minQty} ב־${ShopUnits.money(pr.value)} ₪"
            "price" -> "${ShopUnits.money(pr.value)} ₪"
            else -> {
                val p = if (pr.value == floor(pr.value)) pr.value.toLong().toString() else pr.value.toString()
                "$p% הנחה"
            }
        }

        private val genericWords = setOf(
            "מבצע", "מבצעים", "הנחה", "מחיר", "מוצרים", "מוצר", "מגוון", "בטעמים", "טעמים",
            "שונים", "סוגים", "במבחר", "מבחר", "יחידות", "ליחידה", "כולל", "פקדון", "פיקדון",
            "מועדון", "חברי", "לקוחות", "קנה", "קבל", "מתנה", "בלבד"
        )

        private fun wordsOf(s: String): List<String> {
            val clean = s.map { if (it in "\"'׳״`.,()-+/*") ' ' else it }.joinToString("")
            return clean.split(Regex("""\s+""")).mapNotNull { w ->
                if (w.length < 3 || w.any { it.isDigit() } || genericWords.contains(w)) null
                else if (w.length >= 4 && w[0] in "והבלמש") w.substring(1) else w
            }
        }

        fun namesProduct(desc: String, name: String): Boolean {
            val d = wordsOf(desc)
            if (d.isEmpty()) return true
            val n = wordsOf(name)
            for (w in d) {
                for (x in n) {
                    if (x.contains(w) || w.contains(x) || (w.length >= 4 && x.length >= 4 && w.take(4) == x.take(4))) {
                        return true
                    }
                }
            }
            return false
        }

        fun onePerChainProduct(all: List<Deal>): List<Deal> {
            val best = LinkedHashMap<String, Deal>()
            for (d in all) {
                val k = "${d.chain}|${d.p.code}"
                val prev = best[k]
                if (prev == null || d.unit < prev.unit - 1e-9) best[k] = d
            }
            return best.values.toList()
        }

        fun filterDeals(all: List<Deal>, f: DealFilter, now: Instant): List<Deal> {
            val out = onePerChainProduct(all).filter { d ->
                if (d.ai == "no") return@filter false
                if (f.cat.isNotEmpty() && f.cat != "all" && d.p.cat != f.cat) return@filter false
                if (f.chain.isNotEmpty() && f.chain != "all" && d.chain != f.chain) return@filter false
                if (f.onlyReal && d.verdict != "real") return@filter false
                val save = if (d.verdict == "real") d.realAgorot else d.saveAgorot
                save >= f.minSave
            }
            return when (f.sort) {
                "save" -> out.sortedByDescending { it.realAgorot }
                "pct" -> out.sortedByDescending { it.realPct }
                "ending" -> out.sortedWith(compareBy<Deal> { it.end == null }.thenBy { it.end })
                else -> out.sortedByDescending { it.score(f.mine, now) }
            }
        }

        fun bestOfWeek(all: List<Deal>, n: Int, perCat: Int): List<Deal> {
            val pool = onePerChainProduct(all)
                .filter { it.verdict == "real" && it.confidence == "ok" && it.ai != "no" && it.realAgorot >= 100 }
                .sortedByDescending { it.realPct }
            val seen = HashSet<String>()
            val promos = HashSet<String>()
            val cats = HashMap<String, Int>()
            val out = mutableListOf<Deal>()
            for (d in pool) {
                val pk = "${d.chain}|${d.promo.id}"
                if (seen.contains(d.p.code) || promos.contains(pk) || (cats[d.p.cat] ?: 0) >= perCat) continue
                seen.add(d.p.code); promos.add(pk)
                cats[d.p.cat] = (cats[d.p.cat] ?: 0) + 1
                out.add(d)
                if (out.size >= n) break
            }
            return out
        }

        fun stockUp(all: List<Deal>, n: Int): List<Deal> {
            val pool = onePerChainProduct(all)
                .filter { it.verdict == "real" && it.confidence == "ok" && it.ai != "no" && Deal.stockUpCats.contains(it.p.cat) && it.realPct >= 20 && it.realAgorot >= 300 }
                .sortedByDescending { it.realAgorot }
            val seen = HashSet<String>()
            val out = mutableListOf<Deal>()
            for (d in pool) {
                if (seen.add(d.p.code)) {
                    out.add(d)
                    if (out.size >= n) break
                }
            }
            return out
        }
    }

    fun storeName(i: Int): String {
        if (i !in stores.indices) return ""
        val s = stores[i]
        return if (s.branch.isEmpty()) s.chain else "${s.chain} · ${s.branch}"
    }

    fun promos(storeIdx: Int, code: String, now: Instant): List<Promo> {
        val list = promosByStore.getOrNull(storeIdx)?.get(code) ?: return emptyList()
        return list.filter { pr ->
            val end = parseStampInstant(pr.end)
            end == null || !end.isBefore(now)
        }
    }

    fun notes(storeIdx: Int, code: String, now: Instant): List<PromoNote> {
        val list = notesByStore.getOrNull(storeIdx)?.get(code) ?: return emptyList()
        return list.filter { n ->
            val end = parseStampInstant(n.end)
            end == null || !end.isBefore(now)
        }
    }

    fun usable(storeIdx: Int, code: String, club: Map<String, Boolean>, now: Instant): List<Promo> {
        val chain = chains.getOrNull(storeIdx) ?: ""
        return promos(storeIdx, code, now).filter { !it.club || club[chain] == true }
    }

    fun search(query: String, opts: SearchOpts): List<Hit> {
        val act = opts.active.ifEmpty { stores.indices.toList() }
        val terms = norm(query).split(" ").filter { it.isNotEmpty() }
        val candidates: List<Int> = if (terms.isEmpty()) {
            products.indices.toList()
        } else {
            val sets = terms.map { t ->
                val matches = TreeSet<Int>()
                for (w in vocab) {
                    if (w.contains(t)) {
                        words[w]?.forEach { matches.add(it) }
                    }
                }
                matches
            }
            val smallest = sets.minByOrNull { it.size } ?: TreeSet()
            smallest.filter { idx -> sets.all { it.contains(idx) } }
        }

        val hits = ArrayList<Hit>(candidates.size)
        for (idx in candidates) {
            val p = products[idx]
            if (opts.cat.isNotEmpty() && opts.cat != "all" && p.cat != opts.cat) continue
            if (opts.keep != null && !opts.keep.invoke(p)) continue
            val pi = p.priceInfo(act)
            if (pi.n == 0 && p.np.none { act.contains(it) }) continue
            hits.add(Hit(p, pi))
        }

        when (opts.sort) {
            "cheap" -> hits.sortWith(compareBy<Hit> { it.pi.lo < 0 }.thenBy { if (it.pi.lo < 0) Int.MAX_VALUE else it.pi.lo })
            "dear" -> hits.sortWith(compareBy<Hit> { it.pi.lo < 0 }.thenByDescending { it.pi.lo })
            "gap" -> hits.sortWith(compareByDescending<Hit> { if (it.pi.lo > 0 && it.pi.hi > it.pi.lo) (it.pi.hi.toDouble() / it.pi.lo - 1.0) else -1.0 })
            "unit" -> hits.sortWith(compareBy<Hit> { it.unit() == null }.thenBy { it.unit()?.agorot ?: Double.MAX_VALUE })
            "name" -> hits.sortBy { it.p.name }
            else -> {
                if (terms.isNotEmpty()) {
                    hits.sortWith(compareBy<Hit> { it.pi.lo < 0 }.thenByDescending { it.pi.n }.thenBy { it.p.name })
                }
            }
        }
        return hits
    }

    fun priceAt(p: ShopProduct, storeIdx: Int, q: Double, club: Map<String, Boolean>, now: Instant): Priced? {
        val unit = p.prices.getOrElse(storeIdx) { -1 }
        if (unit < 0) return null
        val shelf = unit * q
        var best = Priced(shelf, shelf, null)
        if (p.weighted) return best
        for (pr in usable(storeIdx, p.code, club, now)) {
            val c = promoCost(pr, unit.toDouble(), q)
            if (c < best.cost - 1e-9) {
                best = Priced(c, shelf, pr)
            }
        }
        return best
    }

    fun bestDeal(p: ShopProduct, active: List<Int>, club: Map<String, Boolean>, now: Instant): BestDealResult? {
        if (p.weighted) return null
        var best: BestDealResult? = null
        for (i in active) {
            val shelf = p.prices.getOrElse(i) { -1 }
            if (shelf < 0) continue
            for (pr in usable(i, p.code, club, now)) {
                val u = dealUnit(pr, shelf.toDouble())
                if (u < shelf.toDouble() && (best == null || u < best.unit)) {
                    best = BestDealResult(i, pr, u, shelf.toDouble())
                }
            }
        }
        return best
    }

    fun compare(cart: Map<String, Double>, active: List<Int>, club: Map<String, Boolean>, now: Instant): CartComparison {
        val items = cart.keys.sorted().mapNotNull { c ->
            val p = byCode[c]
            val q = cart[c] ?: 0.0
            if (p != null && q > 0) p to q else null
        }
        val common = items.filter { (p, _) -> active.all { i -> p.prices.getOrElse(i) { -1 } >= 0 } }
        val rows = active.map { i ->
            var total = 0.0; var shelf = 0.0; var fair = 0.0; var fairShelf = 0.0; var deals = 0
            val missing = mutableListOf<String>()
            val missingCodes = mutableListOf<String>()
            val doubt = mutableListOf<String>()
            for ((p, q) in items) {
                val pr = priceAt(p, i, q, club, now)
                if (pr == null) {
                    missing.add(p.name)
                    missingCodes.add(p.code)
                    continue
                }
                total += pr.cost
                shelf += pr.shelf
                if (pr.promo != null) deals++
                if (p.isStale(i)) doubt.add(p.name)
            }
            for ((p, q) in common) {
                priceAt(p, i, q, club, now)?.let { pr ->
                    fair += pr.cost
                    fairShelf += pr.shelf
                }
            }
            StoreTotal(i, total.roundToInt(), shelf.roundToInt(), fair.roundToInt(), fairShelf.roundToInt(), deals, missing, doubt, missingCodes)
        }.sortedWith(compareBy<StoreTotal> { it.missing.size }.thenBy { it.total })

        val usedStores = HashSet<Int>()
        var split = 0.0; var splitShelf = 0.0
        val lines = items.map { (p, q) ->
            var bestStore = -1; var bestCost = 0.0; var bestShelf = 0.0; var bestPromo: Promo? = null
            var lowShelf = -1.0
            for (i in active) {
                val pr = priceAt(p, i, q, club, now) ?: continue
                if (bestStore < 0 || pr.cost < bestCost) {
                    bestStore = i; bestCost = pr.cost; bestShelf = pr.shelf; bestPromo = pr.promo
                }
                if (lowShelf < 0 || pr.shelf < lowShelf) lowShelf = pr.shelf
            }
            if (bestStore >= 0) {
                split += bestCost
                splitShelf += lowShelf
                usedStores.add(bestStore)
            }
            CartComparisonLine(p, q, bestStore, bestCost, bestShelf, bestPromo)
        }
        val fairLow = rows.minOfOrNull { it.fair } ?: 0
        return CartComparison(lines, rows, common.size, fairLow, split.roundToInt(), splitShelf.roundToInt(), usedStores.size)
    }

    /**
     * Roadmap #4 — best pair of stores for this cart, optimised for "two nearby stops" instead of
     * the theoretical [CartComparison.split] that can scatter the basket over every branch:
     * every line is bought at the cheaper of the two stores, and the pair must beat the cheapest
     * single store of [cmp] by at least [minSaving] agorot while losing nothing that store sold.
     *
     * Pairs farther apart than [maxKm] are only considered when no closer pair qualifies, and
     * never beyond [farKm]. Returns null when a second stop doesn't pay for itself.
     */
    fun bestPairSplit(
        cart: Map<String, Double>,
        active: List<Int>,
        club: Map<String, Boolean>,
        now: Instant,
        cmp: CartComparison,
        maxKm: Double = 5.0,
        farKm: Double = 25.0,
        minSaving: Int = 100
    ): PairSplit? {
        if (active.size < 2 || cart.isEmpty()) return null
        val items = cart.keys.sorted().mapNotNull { c ->
            val p = byCode[c]
            val q = cart[c] ?: 0.0
            if (p != null && q > 0) p to q else null
        }
        if (items.isEmpty()) return null
        // cheapest complete store; when none is complete, the one with fewest gaps then cheapest
        val baseline = cmp.rows.firstOrNull { it.missing.isEmpty() } ?: cmp.rows.firstOrNull() ?: return null

        // cost of every line at every active store, -1 when not sold there
        val n = active.size
        val m = items.size
        val cost = Array(m) { j ->
            val (p, q) = items[j]
            DoubleArray(n) { si -> priceAt(p, active[si], q, club, now)?.cost ?: -1.0 }
        }
        fun pairKm(x: Int, y: Int): Double {
            val sa = stores.getOrNull(active[x])
            val sb = stores.getOrNull(active[y])
            if (sa?.lat == null || sa.lon == null || sb?.lat == null || sb.lon == null) return -1.0
            return (kmBetween(sa.lat, sa.lon, sb.lat, sb.lon) * 10.0).roundToInt() / 10.0
        }

        var bestX = -1; var bestY = -1
        var bestTotal = 0; var bestKm = Double.MAX_VALUE

        fun consider(x: Int, y: Int, limitKm: Double) {
            val d = pairKm(x, y)
            if (d >= 0 && d > limitKm) return
            var tA = 0.0; var tB = 0.0
            var cA = 0; var cB = 0; var miss = 0
            for (j in 0 until m) {
                val ca = cost[j][x]; val cb = cost[j][y]
                when {
                    ca < 0 && cb < 0 -> miss++
                    ca < 0 -> { tB += cb; cB++ }
                    cb < 0 -> { tA += ca; cA++ }
                    ca <= cb -> { tA += ca; cA++ }
                    else -> { tB += cb; cB++ }
                }
            }
            if (cA == 0 || cB == 0) return                 // one of the two stops would be pointless
            if (miss > baseline.missing.size) return       // worse coverage than the single baseline
            val total = tA.roundToInt() + tB.roundToInt()
            if (total >= baseline.total - minSaving) return // not worth a second stop
            val dKey = if (d >= 0) d else Double.MAX_VALUE
            if (bestX < 0 || total < bestTotal || (total == bestTotal && dKey < bestKm)) {
                bestX = x; bestY = y; bestTotal = total; bestKm = dKey
            }
        }

        // pass 1: only nearby pairs; pass 2 (only if pass 1 found nothing): any pair within farKm
        for (pass in 0..1) {
            val limit = if (pass == 0) maxKm else farKm
            for (x in 0 until n - 1) for (y in x + 1 until n) consider(x, y, limit)
            if (bestX >= 0) break
        }
        if (bestX < 0) return null

        // re-run the winning assignment so the returned lines carry each product's promo/price
        val a = active[bestX]; val b = active[bestY]
        val lines = ArrayList<PairSplitLine>(m)
        val missing = mutableListOf<String>()
        var sumA = 0.0; var sumB = 0.0; var shelfSum = 0.0
        var itemsA = 0; var itemsB = 0
        for ((p, q) in items) {
            val prA = priceAt(p, a, q, club, now)
            val prB = priceAt(p, b, q, club, now)
            val pr: Priced?
            val store: Int
            when {
                prA == null && prB == null -> { pr = null; store = -1 }
                prB == null -> { pr = prA; store = a }
                prA == null -> { pr = prB; store = b }
                prA.cost <= prB.cost -> { pr = prA; store = a }
                else -> { pr = prB; store = b }
            }
            if (pr == null) { missing.add(p.name); continue }
            lines.add(PairSplitLine(p, q, store, pr.cost, pr.shelf, pr.promo))
            if (store == a) { sumA += pr.cost; itemsA++ } else { sumB += pr.cost; itemsB++ }
            shelfSum += pr.shelf
        }
        val totalA = sumA.roundToInt(); val totalB = sumB.roundToInt()
        return PairSplit(
            a = a, b = b,
            total = totalA + totalB,
            shelf = shelfSum.roundToInt(),
            totalA = totalA, totalB = totalB,
            itemsA = itemsA, itemsB = itemsB,
            saving = baseline.total - (totalA + totalB),
            baselineStore = baseline.store, baselineTotal = baseline.total,
            km = if (bestKm == Double.MAX_VALUE) -1.0 else bestKm,
            missing = missing, lines = lines
        )
    }

    /**
     * Roadmap #2 — Smart Swaps: substitutes for cart line [p] (qty [q]) at store [at], where the
     * store doesn't sell it. Candidates must share the AI product type or the category, and are
     * ranked by type match, then category, unit-price proximity via [ShopUnits] (pack sizes) and
     * shelf price; absurdly expensive items (>3× the original's cheapest unit price) are dropped
     * and [exclude] keeps the rest of the cart out of the suggestions.
     */
    fun swapsFor(
        p: ShopProduct,
        q: Double,
        at: Int,
        club: Map<String, Boolean>,
        now: Instant,
        exclude: Set<String> = emptySet(),
        limit: Int = 3
    ): List<SwapCandidate> {
        if (at !in stores.indices || q <= 0) return emptyList()
        // reference: the original's cheapest shelf unit price across stores (−1 ⇒ no reference)
        val refShelf = p.prices.filter { it >= 0 }.minOrNull() ?: -1
        val refUnit = if (refShelf > 0) ShopUnits.sizeOf(p)?.let { ShopUnits.unitPriceOf(it, refShelf.toDouble()) }?.agorot else null
        val aiType = p.ai?.t?.takeIf { it.isNotEmpty() }
        val out = ArrayList<SwapCandidate>()
        for (c in products) {
            if (c.code == p.code || c.code in exclude) continue
            val sameType = aiType != null && c.ai?.t == aiType
            val sameCat = c.cat == p.cat
            if (!sameType && !sameCat) continue
            val shelfUnit = c.prices.getOrElse(at) { -1 }
            if (shelfUnit < 0 || c.isStale(at)) continue
            if (refShelf > 0 && shelfUnit > refShelf * 3) continue
            val pr = priceAt(c, at, q, club, now) ?: continue
            var score = 0.0
            if (sameType) score += 100.0
            if (sameCat) score += 40.0
            if (c.weighted == p.weighted) score += 10.0
            val candUnit = ShopUnits.sizeOf(c)?.let { ShopUnits.unitPriceOf(it, shelfUnit.toDouble()) }?.agorot
            if (refUnit != null && refUnit > 0 && candUnit != null) {
                score -= abs(candUnit / refUnit - 1.0) * 30.0
            }
            if (refShelf > 0) score -= (shelfUnit.toDouble() / refShelf) * 5.0
            out.add(SwapCandidate(c, pr.cost, pr.shelf, score, pr.promo))
        }
        return out.sortedWith(compareByDescending<SwapCandidate> { it.score }.thenBy { it.cost }).take(limit)
    }

    fun buildDeals(active: List<Int>, club: Map<String, Boolean>, now: Instant): List<Deal> {
        val out = LinkedHashMap<String, Deal>()
        for (i in active) {
            val ck = chains.getOrNull(i) ?: ""
            val label = if (ck == "shufersal") "שופרסל" else stores[i].chain
            for (pr in promosByStore.getOrNull(i)?.values?.flatten()?.distinct() ?: emptyList()) {
                if (pr.club && club[ck] != true) continue
                val end = parseStampInstant(pr.end)
                if (end != null && end.isBefore(now)) continue
                val start = parseStampInstant(pr.start)
                if (start != null && start.isAfter(now)) continue
                val qty = if (pr.kind == "bundle") maxOf(1, pr.minQty) else 1
                var spread = -1.0
                for (code in pr.codes) {
                    val p = byCode[code] ?: continue
                    if (p.weighted) continue
                    val shelfAg = p.prices.getOrElse(i) { -1 }
                    if (shelfAg < 0) continue
                    val shelf = shelfAg.toDouble()
                    val unit = dealUnit(pr, shelf)
                    if (unit >= shelf - 1e-9 || unit <= 0.0) continue
                    val id = listOf(ck, code, pr.kind, pr.value.toString(), pr.minQty.toString(), if (pr.club) "1" else "0", pr.max.toString(), pr.end).joinToString("|")
                    val prev = out[id]
                    if (prev != null) {
                        prev.stores.add(i)
                        if (unit < prev.unit - 1e-9) {
                            prev.rep = i; prev.unit = unit; prev.shelf = shelf
                        }
                        continue
                    }
                    if (spread < 0) spread = promoSpread(pr, i)
                    val d = Deal(id, p, ck, label, pr, mutableListOf(i), i, qty, unit, shelf, end)
                    if (spread >= 2.5) d.why = "spread"
                    out[id] = d
                }
            }
        }

        val items = out.values.toList()
        for (d in items) {
            val pi = d.p.priceInfo(active)
            d.cheapest = pi.lo.toDouble()
            d.cheapestAt = pi.at
            d.compared = pi.n
            d.savePct = (1.0 - d.unit / d.shelf) * 100.0
            d.saveAgorot = ((d.shelf - d.unit) * d.qty).roundToInt()
            if (d.cheapest > 0) {
                d.realPct = (1.0 - d.unit / d.cheapest) * 100.0
                d.realAgorot = ((d.cheapest - d.unit) * d.qty).roundToInt()
            }
            d.verdict = when {
                d.compared < 2 -> "alone"
                d.unit < d.cheapest - 1e-9 -> "real"
                else -> "notCheapest"
            }
            when {
                d.why == "spread" -> {
                    d.confidence = "check"; d.checkKind = "spread"
                    d.why = "המבצע רשום על כמה מוצרים במחירים שונים מאוד"
                }
                !namesProduct(d.promo.desc, d.p.name) && d.promo.codes.size >= 2 -> {
                    d.confidence = "check"; d.checkKind = "words"
                    d.why = "נוסח המבצע: ״${d.promo.desc}״"
                }
                d.realPct > 60 -> {
                    d.confidence = "check"; d.checkKind = "deep"
                    d.why = "הנחה חריגה במיוחד"
                }
                else -> d.why = ""
            }
        }
        return items
    }

    private fun promoSpread(pr: Promo, i: Int): Double {
        if (pr.codes.size < 2) return 1.0
        var lo = Double.POSITIVE_INFINITY
        var hi = 0.0
        for (code in pr.codes) {
            val v = byCode[code]?.prices?.getOrElse(i) { -1 } ?: -1
            if (v >= 0) {
                lo = min(lo, v.toDouble())
                hi = max(hi, v.toDouble())
            }
        }
        return if (lo.isInfinite() || lo <= 0.0) 1.0 else hi / lo
    }
}
