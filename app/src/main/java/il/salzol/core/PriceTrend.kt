package il.salzol.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** One point of the daily cheapest-shelf series: [price] (agorot) on [day] (yyyy-MM-dd). */
data class PricePoint(val day: String, val price: Int)

/**
 * Summary of a price series: range, current position and overall direction
 * (first→last with a ±3% deadband so noise doesn't read as a trend).
 */
data class TrendSummary(
    val min: Int,
    val max: Int,
    val first: Int,
    val last: Int,
    val cheapestNow: Boolean,
    val direction: Int // -1 falling, 0 flat, +1 rising
)

/** A shelf price raised [pct]% on [day], within the hike window before the promo at [promoDay]. */
data class HikeAlert(
    val day: String,
    val from: Int,
    val to: Int,
    val pct: Int,
    val promoDay: String // yyyyMMdd (promo start, first 8 digits)
)

/**
 * Roadmap #3 — price history analysis: reconstructs the daily cheapest-shelf series from the
 * 30-day `price_changes` transitions, summarises the trend ("cheapest in N days"), and flags
 * shelf prices raised artificially shortly before a promo is announced (fake-deal detection).
 * Pure functions over plain data — no Android, no Room.
 */
object PriceTrend {
    /** Chart/lookup window: how far back the series reaches (data itself is capped at 30 days). */
    const val WINDOW_DAYS = 90
    /** A hike counts as "before the promo" when it lands within this many days before start. */
    const val HIKE_WINDOW_DAYS = 14
    /** Minimum shelf-price increase, in percent, to count as artificial. */
    const val MIN_HIKE_PCT = 10

    /** Accepts ISO `yyyy-MM-dd` (price_changes) and `yyyyMMdd[HHmm]` (promo start/end). */
    fun parseDay(s: String): LocalDate? = runCatching {
        when {
            s.length >= 10 && s[4] == '-' -> LocalDate.parse(s.substring(0, 10))
            s.length >= 8 && s.take(8).all { it.isDigit() } ->
                LocalDate.parse(s.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
            else -> null
        }
    }.getOrNull()

    /**
     * Reconstructs the daily cheapest shelf price for one product:
     * [currentByStore] = storeId → today's price (stores currently selling it), [changes] =
     * price transitions (day, storeId, from→to). Days without a change keep the previous price
     * (step function). The series runs from the earliest in-window transition to [today], one
     * point per calendar day; stores that only appear in transitions contribute their historical
     * price (a `from <= 0` listing transition hides the store before that day).
     */
    fun dailyMinSeries(changes: List<Change>, currentByStore: Map<String, Int>, today: String): List<PricePoint> {
        val todayD = parseDay(today) ?: return emptyList()
        val oldest = todayD.minusDays((WINDOW_DAYS - 1).toLong())
        val perStore = HashMap<String, MutableList<Change>>()
        for (c in changes) {
            val d = parseDay(c.day) ?: continue
            if (d.isBefore(oldest) || d.isAfter(todayD)) continue
            perStore.getOrPut(c.store) { mutableListOf() }.add(c)
        }
        // start one day before the earliest transition so its `from` price shows as a point too
        val earliest = perStore.values.flatten().minOfOrNull { parseDay(it.day)!! }
            ?.minusDays(1)?.coerceAtLeast(oldest) ?: todayD
        val days = mutableListOf<LocalDate>()
        var d = earliest
        while (!d.isAfter(todayD)) {
            days.add(d)
            d = d.plusDays(1)
        }
        fun priceOn(store: String, day: LocalDate): Int? {
            val tr = perStore[store]?.sortedBy { it.day }
            if (tr != null) {
                var latest: Int? = null
                for (c in tr) {
                    val cd = parseDay(c.day) ?: continue
                    if (!cd.isAfter(day)) latest = c.to else break
                }
                if (latest != null) return latest.takeIf { it > 0 }
                // before the first transition: its `from` (a listing, from<=0, means absent)
                return tr.first().from.takeIf { it > 0 }
            }
            return currentByStore[store]?.takeIf { it > 0 }
        }
        val stores = currentByStore.keys + perStore.keys
        if (stores.isEmpty()) return emptyList()
        val out = ArrayList<PricePoint>(days.size)
        for (day in days) {
            var best = Int.MAX_VALUE
            for (store in stores) {
                priceOn(store, day)?.let { if (it < best) best = it }
            }
            if (best != Int.MAX_VALUE) out.add(PricePoint(day.toString(), best))
        }
        return out
    }

    /** Range + current position + direction of [points] (empty → null). */
    fun summarize(points: List<PricePoint>): TrendSummary? {
        if (points.isEmpty()) return null
        val first = points.first().price
        val last = points.last().price
        val min = points.minOf { it.price }
        val max = points.maxOf { it.price }
        val pct = if (first > 0) (last.toDouble() / first - 1.0) * 100.0 else 0.0
        val direction = if (pct >= 3.0) 1 else if (pct <= -3.0) -1 else 0
        return TrendSummary(min, max, first, last, last <= min, direction)
    }

    /**
     * Fake-deal detection: shelf raises of at least [minPct]% for [code] that land within
     * [windowDays] days *before* a promo (with a parseable start date) including the product —
     * i.e. the price went up just before the "deal" was announced. Biggest hikes first.
     */
    fun hikeBeforePromo(
        code: String,
        changes: List<Change>,
        promos: List<Promo>,
        windowDays: Int = HIKE_WINDOW_DAYS,
        minPct: Int = MIN_HIKE_PCT
    ): List<HikeAlert> {
        val out = mutableListOf<HikeAlert>()
        for (pr in promos) {
            if (code !in pr.codes) continue
            val start = parseDay(pr.start) ?: continue
            val limit = start.minusDays(windowDays.toLong())
            for (c in changes) {
                if (c.code != code || c.from <= 0 || c.to <= c.from) continue
                val d = parseDay(c.day) ?: continue
                if (d.isBefore(limit) || !d.isBefore(start)) continue
                val pct = ((c.to.toDouble() / c.from - 1.0) * 100.0).roundToInt()
                if (pct < minPct) continue
                out.add(HikeAlert(c.day, c.from, c.to, pct, pr.start.take(8)))
            }
        }
        return out.sortedByDescending { it.pct }.distinctBy { "${it.day}|${it.promoDay}|${it.from}|${it.to}" }
    }
}
