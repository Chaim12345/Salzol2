package il.salzol.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private val ilZone: ZoneId = runCatching { ZoneId.of("Asia/Jerusalem") }.getOrElse { ZoneId.systemDefault() }

object EngineViews {

    private fun kmFor(e: Engine, s: Store): Double {
        val p = e.st.place ?: return -1.0
        if (s.lat == null || s.lon == null) return -1.0
        return (kmBetween(p.lat, p.lon, s.lat, s.lon) * 10.0).roundToInt() / 10.0
    }

    private fun imgUrl(e: Engine, p: ShopProduct): String {
        if (!e.st.images || p.img.isEmpty()) return ""
        val imgs = e.ix?.cat?.images ?: return ""
        if (imgs.isEmpty()) return ""
        return imgs[0].replace("{code}", p.img)
    }

    private fun newestStamp(cat: Catalog): String {
        val best = cat.stores.map { it.fileTime }.maxOrNull() ?: ""
        if (best.length < 12) return ""
        val d = best.substring(6, 8).toIntOrNull() ?: return ""
        val m = best.substring(4, 6).toIntOrNull() ?: return ""
        return "$d.$m ${best.substring(8, 10)}:${best.substring(10, 12)}"
    }

    fun statusJson(e: Engine): String {
        val cur = e.ix
        val o = JSONObject().apply {
            put("ready", cur != null)
            put("building", e.building)
            e.st.place?.let { p ->
                put("place", JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("radius", p.radius))
            }
            put("syncing", e.syncing)
            put("syncMsg", e.syncMsg)
            put("syncErr", e.syncErr)
            put("cartCount", e.st.cart.size)
            put("clubOwned", JSONObject(e.st.club as Map<*, *>))
            put("aiOn", e.st.ai.usable())
            e.aiRun?.let { r ->
                put("aiRun", JSONObject().apply {
                    put("kind", r.kind)
                    put("done", r.done)
                    put("of", r.of)
                    put("added", r.added)
                    if (r.err.isNotEmpty()) put("err", r.err)
                    put("live", r.live)
                })
            }
            put("images", e.st.images)
            val storesArr = JSONArray()
            if (cur != null) {
                put("priceDate", newestStamp(cur.cat))
                put("products", cur.products.size)
                put("categories", JSONArray().apply {
                    cur.cat.categories.forEach { c ->
                        put(JSONObject().put("id", c.id).put("name", c.name))
                    }
                })
                cur.stores.forEachIndexed { i, s ->
                    val club = cur.cat.promos.getOrNull(i)?.any { it.club } == true
                    storesArr.put(JSONObject().apply {
                        put("id", s.id)
                        put("i", i)
                        put("chain", s.chain)
                        put("key", cur.chains[i])
                        put("branch", s.branch)
                        put("on", !e.st.off.contains(s.id))
                        put("km", kmFor(e, s))
                        put("club", club)
                    })
                }
            }
            put("stores", storesArr)
        }
        return o.toString()
    }

    private fun itemViewJson(e: Engine, cur: ProductIndex, h: Hit, act: List<Int>, now: Instant): JSONObject {
        val p = h.p
        var at = h.pi.at
        val o = JSONObject().apply {
            put("code", p.code)
            put("name", p.name)
            put("lo", h.pi.lo)
            put("branches", h.pi.n)
            put("qty", e.st.cart[p.code] ?: 0.0)
            put("weighted", p.weighted)
            if (h.pi.lo >= 0) {
                put("price", ShopUnits.money(h.pi.lo.toDouble()))
                if (h.pi.hi > h.pi.lo) {
                    put("gap", ((h.pi.hi.toDouble() / h.pi.lo - 1.0) * 100.0).roundToInt())
                }
                put("stale", at >= 0 && p.isStale(at))
            } else {
                at = p.listedAt(act)
            }
            put("where", cur.storeName(at))
            h.unit()?.let { put("unit", it.text()) }
            cur.bestDeal(p, act, e.st.club, now)?.let { d ->
                put("deal", "${ProductIndex.dealText(d.promo)} · ${cur.stores[d.store].chain}")
                put("dealClub", d.promo.club)
            }
            put("img", imgUrl(e, p))
            p.ai?.let { r ->
                val tags = r.d.filter { it !in setOf("kosher", "dairy", "meat", "parve") }
                    .mapNotNull { DietLabel[it] }
                    .joinToString(" · ")
                if (tags.isNotEmpty()) put("tags", tags)
            }
        }
        return o
    }

    private fun dealViewJson(e: Engine, cur: ProductIndex, d: Deal, now: Instant): JSONObject {
        val headline: String
        val was: String
        var pct = d.realPct.roundToInt()
        when (d.verdict) {
            "real" -> {
                headline = "חוסכים ${ShopUnits.money(d.realAgorot.toDouble())} ₪" +
                    if (d.qty > 1) " על ${d.qty}" else ""
                was = ShopUnits.money(d.cheapest)
            }
            "notCheapest" -> {
                headline = "זול יותר בלי מבצע ב${cur.stores.getOrNull(d.cheapestAt)?.chain ?: ""}"
                was = ShopUnits.money(d.shelf)
                pct = d.savePct.roundToInt()
            }
            else -> {
                headline = "${d.savePct.roundToInt()}% מתחת למדף"
                was = ShopUnits.money(d.shelf)
                pct = d.savePct.roundToInt()
            }
        }
        val terms = mutableListOf(ProductIndex.dealText(d.promo))
        if (d.promo.club) terms.add("מועדון")
        if (d.promo.max > 0) terms.add("עד ${d.promo.max}")

        val where = d.chainLabel + when {
            d.stores.size > 1 -> " · ${d.stores.size} סניפים"
            cur.stores.getOrNull(d.rep)?.branch?.isNotEmpty() == true -> " · ${cur.stores[d.rep].branch}"
            else -> ""
        }
        var check = ""
        if (d.confidence == "check") {
            check = "לבדוק בקופה: ${d.why}"
            if (d.ai == "unclear") check += " · גם ה־AI לא בטוח"
        }

        return JSONObject().apply {
            put("code", d.p.code)
            put("name", d.p.name)
            put("img", imgUrl(e, d.p))
            put("headline", headline)
            put("pct", pct)
            put("price", ShopUnits.money(d.unit))
            put("was", was)
            put("terms", terms.joinToString(" · "))
            put("where", where)
            put("end", d.endLabel(now))
            put("soon", d.endingSoon(now))
            put("real", d.verdict == "real")
            put("check", check)
            put("aiYes", d.ai == "yes")
            put("qty", d.qty)
            put("inCart", e.st.cart[d.p.code] ?: 0.0)
            put("mine", (e.st.freq[d.p.code] ?: 0) > 0 || e.st.watch.contains(d.p.code))
        }
    }

    private fun dietCounts(cur: ProductIndex): JSONArray {
        val counts = HashMap<String, Int>()
        for (p in cur.products) {
            p.ai?.d?.forEach { d -> counts[d] = (counts[d] ?: 0) + 1 }
        }
        val list = Diets.filter { d ->
            (counts[d] ?: 0) >= 5 && d !in setOf("kosher", "dairy", "meat", "parve")
        }.sortedByDescending { counts[it] ?: 0 }
        return JSONArray().apply {
            list.forEach { d ->
                put(JSONObject().put("id", d).put("label", DietLabel[d] ?: d).put("n", counts[d] ?: 0))
            }
        }
    }

    fun searchJson(e: Engine, reqJson: String): String {
        val cur = e.ix ?: return JSONObject().put("total", 0).put("items", JSONArray()).put("diets", JSONArray()).toString()
        val req = runCatching { JSONObject(reqJson) }.getOrElse { JSONObject() }
        val q = req.optString("q")
        val cat = req.optString("cat")
        val sort = req.optString("sort")
        val onSale = req.optBoolean("onSale")
        val il = req.optBoolean("il")
        val diet = req.optString("diet")
        val offset = req.optInt("offset", 0)
        val limit = req.optInt("limit", 40).let { if (it <= 0) 40 else it }

        val act = e.activeStores()
        val now = Instant.now()
        val keep: ((ShopProduct) -> Boolean)? = if (onSale || il || diet.isNotEmpty()) {
            { p ->
                (!il || p.il) &&
                    (diet.isEmpty() || p.ai?.d?.contains(diet) == true) &&
                    (!onSale || cur.bestDeal(p, act, e.st.club, now) != null)
            }
        } else null

        val hits = cur.search(q, SearchOpts(cat, sort, keep, act))
        val items = JSONArray()
        for (i in offset until minOf(hits.size, offset + limit)) {
            items.put(itemViewJson(e, cur, hits[i], act, now))
        }
        return JSONObject().apply {
            put("total", hits.size)
            put("items", items)
            put("diets", if (offset == 0) dietCounts(cur) else JSONArray())
        }.toString()
    }

    fun dealsJson(e: Engine, reqJson: String): String {
        val cur = e.ix ?: return JSONObject().put("total", 0).toString()
        val all = e.dealsNow()
        val req = runCatching { JSONObject(reqJson) }.getOrElse { JSONObject() }
        val cat = req.optString("cat")
        val chain = req.optString("chain")
        val onlyReal = req.optBoolean("onlyReal")
        val small = req.optBoolean("small")
        val sort = req.optString("sort", "useful")
        val offset = req.optInt("offset", 0)
        val limit = req.optInt("limit", 30).let { if (it <= 0) 30 else it }

        val now = Instant.now()
        val mine = (e.st.freq.keys + e.st.cart.keys + e.st.watch).toHashSet()
        val minSave = if (small) 0 else 100
        val list = ProductIndex.filterDeals(all, DealFilter(cat, chain, onlyReal, minSave, sort, mine), now)

        val items = JSONArray()
        for (i in offset until minOf(list.size, offset + limit)) {
            items.put(dealViewJson(e, cur, list[i], now))
        }

        val out = JSONObject().apply {
            put("total", list.size)
            put("items", items)
        }
        if (offset == 0) {
            out.put("best", JSONArray().apply { ProductIndex.bestOfWeek(all, 10, 3).forEach { put(dealViewJson(e, cur, it, now)) } })
            out.put("stockUp", JSONArray().apply { ProductIndex.stockUp(all, 10).forEach { put(dealViewJson(e, cur, it, now)) } })
            val mineDeals = JSONArray()
            val seen = HashSet<String>()
            for (d in ProductIndex.filterDeals(all, DealFilter(minSave = 0, sort = "useful", mine = mine), now)) {
                if (mine.contains(d.p.code) && seen.add(d.p.code) && d.verdict != "notCheapest") {
                    mineDeals.put(dealViewJson(e, cur, d, now))
                    if (mineDeals.length() >= 12) break
                }
            }
            out.put("mine", mineDeals)
            val chainsArr = JSONArray()
            val seenChains = HashSet<String>()
            for (i in e.activeStores()) {
                val k = cur.chains[i]
                if (seenChains.add(k)) {
                    val label = if (k == "shufersal") "שופרסל" else cur.stores[i].chain
                    chainsArr.put(JSONObject().put("id", k).put("label", label))
                }
            }
            out.put("chains", chainsArr)
            out.put("checked", all.count { it.confidence == "check" && it.verdict == "real" && it.ai != "no" })
        }
        return out.toString()
    }

    fun homeJson(e: Engine): String {
        val statusObj = JSONObject(statusJson(e))
        val out = JSONObject().put("status", statusObj)
        val cur = e.ix ?: return out.toString()
        val dealsObj = JSONObject(dealsJson(e, """{"limit":1}"""))
        out.put("best", dealsObj.optJSONArray("best") ?: JSONArray())
        out.put("mine", dealsObj.optJSONArray("mine") ?: JSONArray())

        val act = e.activeStores()
        val now = Instant.now()
        val regulars = JSONArray()
        for ((code, _) in e.st.freq.entries.sortedByDescending { it.value }) {
            val p = cur.byCode[code] ?: continue
            val pi = p.priceInfo(act)
            if (pi.lo >= 0) {
                regulars.put(itemViewJson(e, cur, Hit(p, pi), act, now))
                if (regulars.length() >= 12) break
            }
        }
        out.put("regulars", regulars)
        out.put("cheapest", priceIndexJson(e, cur, act))

        if (e.st.cart.isNotEmpty()) {
            val cmp = cur.compare(e.st.cart, act, e.st.club, now)
            if (cmp.rows.isNotEmpty()) {
                out.put("cartTotal", ShopUnits.money(cmp.rows[0].total.toDouble()))
                out.put("cartWhere", cur.storeName(cmp.rows[0].store))
            }
        }
        return out.toString()
    }

    private fun priceIndexJson(e: Engine, cur: ProductIndex, act: List<Int>): JSONArray {
        val logs = HashMap<Int, MutableList<Double>>()
        for (p in cur.products) {
            val vs = act.mapNotNull { i ->
                val v = p.prices.getOrElse(i) { -1 }
                if (v > 0 && !p.isStale(i)) v.toDouble() else null
            }
            if (vs.size < 3) continue
            val s = vs.sorted()
            val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            for (i in act) {
                val v = p.prices.getOrElse(i) { -1 }
                if (v > 0 && !p.isStale(i)) {
                    logs.getOrPut(i) { mutableListOf() }.add(ln(v.toDouble() / med))
                }
            }
        }
        val rows = act.mapNotNull { i ->
            val l = logs[i] ?: return@mapNotNull null
            if (l.size < 50) return@mapNotNull null
            val pct = ((exp(l.average()) - 1.0) * 100.0).roundToInt()
            Triple(cur.storeName(i), pct, kmFor(e, cur.stores[i]))
        }.sortedBy { it.second }

        return JSONArray().apply {
            rows.forEach { (store, pct, km) ->
                put(JSONObject().put("store", store).put("pct", pct).put("km", km))
            }
        }
    }

    fun productJson(e: Engine, code: String): String {
        val cur = e.ix ?: return JSONObject().put("code", code).put("found", false).toString()
        val p = cur.byCode[code] ?: return JSONObject().put("code", code).put("found", false).toString()
        val act = e.activeStores()
        val all = cur.stores.indices.toList()
        val now = Instant.now()
        val pi = p.priceInfo(all)

        val facts = mutableListOf<String>()
        when {
            p.weighted && p.picks != null -> facts.add("מחיר לק״ג · המוצר המקביל בכל רשת")
            p.weighted -> facts.add("מחיר לק״ג")
            p.code.contains(':') -> facts.add("פריט של הרשת (קוד פנימי)")
            else -> facts.add("ברקוד ${p.code}")
        }
        val sz = ShopUnits.sizeOf(p)
        if (sz != null && pi.lo > 0) {
            val u = ShopUnits.unitPriceOf(sz, pi.lo.toDouble())
            if (u != null) facts.add("גודל: ${sz.text()} · ${u.text()} במחיר הזול")
        }
        val subFacts = mutableListOf<String>()
        if (p.maker.isNotEmpty()) subFacts.add("יצרן: ${p.maker}")
        if (p.il) subFacts.add("תוצרת הארץ")
        if (subFacts.isNotEmpty()) facts.add(subFacts.joinToString(" · "))
        p.ai?.let { r ->
            val parts = mutableListOf(r.t)
            if (r.b.isNotEmpty()) parts.add("מותג: ${r.b}" + if (r.pl) " (פרטי)" else "")
            if (r.l.isNotEmpty()) parts.add(r.l)
            if (r.v.isNotEmpty()) parts.add(r.v)
            r.d.forEach { d -> DietLabel[d]?.let { parts.add(it) } }
            facts.add(parts.joinToString(" · ") + " (סיווג AI)")
        }
        if (p.rawName.isNotEmpty()) facts.add("השם במחירון: ${p.rawName}")

        val noteLabels = mapOf("basket_min" to "בקנייה מעל סכום", "gift" to "מתנה בקנייה", "multi_group" to "משולב")
        data class RowHolder(val obj: JSONObject, val v: Int, val stale: Boolean)
        val rows = mutableListOf<RowHolder>()
        cur.stores.forEachIndexed { i, s ->
            val price = p.prices[i]
            if (price < 0) return@forEachIndexed
            val staleDays = p.stale[i] ?: 0
            val isStale = staleDays >= STALE_DAYS
            val dealsArr = JSONArray()
            var bestUnit = Double.POSITIVE_INFINITY
            for (pr in cur.promos(i, code, now)) {
                var t = ProductIndex.dealText(pr)
                if (pr.club) {
                    t += " · מועדון"
                    if (e.st.club[cur.chains[i]] != true) t += " (דורש כרטיס)"
                }
                if (pr.desc.isNotEmpty()) t += " · ${pr.desc}"
                dealsArr.put(t)
                if (!pr.club || e.st.club[cur.chains[i]] == true) {
                    bestUnit = minOf(bestUnit, ProductIndex.dealUnit(pr, price.toDouble()))
                }
            }
            val notesArr = JSONArray()
            for (n in cur.notes(i, code, now)) {
                notesArr.put("${noteLabels[n.why] ?: n.why}: ${n.desc}")
            }
            val obj = JSONObject().apply {
                put("store", cur.storeName(i))
                put("km", kmFor(e, s))
                put("on", act.contains(i))
                put("price", ShopUnits.money(price.toDouble()))
                if (isStale) put("stale", "לא נמכר כאן $staleDays ימים: אולי לא על המדף")
                p.chg[i]?.let { d ->
                    put("chg", if (d == 0) "המחיר עודכן היום" else "המחיר עודכן לפני $d ימים")
                }
                put("deals", dealsArr)
                put("notes", notesArr)
                if (bestUnit < price.toDouble()) put("dealAt", "${ShopUnits.money(bestUnit)} ₪ במבצע")
            }
            rows.add(RowHolder(obj, price, isStale))
        }
        rows.sortWith(compareBy<RowHolder> { it.stale }.thenBy { it.v })
        val rowsArr = JSONArray()
        rows.forEachIndexed { k, r ->
            if (pi.lo >= 0 && r.v > pi.lo) r.obj.put("diff", "+${ShopUnits.money((r.v - pi.lo).toDouble())}")
            r.obj.put("cheap", k == 0 && !r.stale)
            rowsArr.put(r.obj)
        }

        return JSONObject().apply {
            put("code", code)
            put("name", p.name)
            put("found", true)
            put("weighted", p.weighted)
            put("img", imgUrl(e, p))
            put("facts", JSONArray(facts))
            if (pi.lo >= 0) {
                put("lo", ShopUnits.money(pi.lo.toDouble()))
                put("hi", ShopUnits.money(pi.hi.toDouble()))
                put("loWhere", cur.storeName(pi.at))
                if (pi.hi > pi.lo) put("gapPct", ((pi.hi.toDouble() / pi.lo - 1.0) * 100.0).roundToInt())
            }
            cur.bestDeal(p, act, e.st.club, now)?.let { d ->
                put("best", "במבצע עכשיו: ${ShopUnits.money(d.unit)} ₪ ליחידה ב${cur.storeName(d.store)} (${ProductIndex.dealText(d.promo)})")
            }
            put("rows", rowsArr)
            put("qty", e.st.cart[code] ?: 0.0)
            put("watched", e.st.watch.contains(code))
            put("listed", JSONArray(p.np.map { cur.storeName(it) }))
            put("odd", JSONArray(p.odd.map { "${cur.storeName(it.first)}: ${ShopUnits.money(it.second.toDouble())} ₪, כנראה טעות במחירון" }))
        }.toString()
    }

    fun cartJson(e: Engine): String {
        val cur = e.ix
        if (cur == null || e.st.cart.isEmpty()) {
            return JSONObject().put("count", e.st.cart.size).put("lines", JSONArray()).put("rows", JSONArray()).toString()
        }
        val act = e.activeStores()
        val cmp = cur.compare(e.st.cart, act, e.st.club, Instant.now())
        val linesArr = JSONArray()
        for (l in cmp.lines) {
            linesArr.put(JSONObject().apply {
                put("code", l.p.code)
                put("name", l.p.name)
                put("qty", l.q)
                put("weighted", l.p.weighted)
                if (l.store >= 0) {
                    put("cost", ShopUnits.money(l.cost))
                    put("where", cur.storeName(l.store))
                    l.promo?.let { put("deal", ProductIndex.dealText(it)) }
                } else {
                    put("where", "לא נמכר בסניפים שנבחרו")
                }
                put("img", imgUrl(e, l.p))
            })
        }
        val complete = mutableListOf<Int>()
        val rowsArr = JSONArray()
        cmp.rows.forEachIndexed { k, r ->
            val s = cur.stores[r.store]
            rowsArr.put(JSONObject().apply {
                put("storeId", s.id)
                put("store", cur.storeName(r.store))
                put("km", kmFor(e, s))
                put("total", ShopUnits.money(r.total.toDouble()))
                put("shelf", ShopUnits.money(r.shelf.toDouble()))
                put("deals", r.deals)
                put("missing", JSONArray(r.missing))
                put("doubt", JSONArray(r.doubt))
                put("best", k == 0)
                if (s.lat != null && s.lon != null) {
                    put("lat", s.lat); put("lon", s.lon)
                }
            })
            if (r.missing.isEmpty()) complete.add(r.total)
        }
        val saving = if (complete.size >= 2) {
            val lo = complete.min(); val hi = complete.max()
            if (hi > lo) ShopUnits.money((hi - lo).toDouble()) else ""
        } else ""

        return JSONObject().apply {
            put("count", e.st.cart.size)
            put("lines", linesArr)
            put("rows", rowsArr)
            put("split", ShopUnits.money(cmp.split.toDouble()))
            put("splitStores", cmp.splitStores)
            put("saving", saving)
            put("common", cmp.common)
        }.toString()
    }

    fun aiJson(e: Engine): String {
        val c = e.st.ai
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val used = if (e.st.usage.day == today) e.st.usage.n else 0
        val presetsArr = JSONArray()
        for (id in listOf("gemini", "openrouter", "groq", "custom")) {
            val p = AiPresets[id]!!
            presetsArr.put(JSONObject().apply {
                put("ID", id); put("Label", p.label); put("BaseURL", p.baseUrl)
                put("Model", p.model); put("KeyURL", p.keyUrl)
            })
        }
        return JSONObject().apply {
            put("on", c.on)
            put("preset", c.preset)
            put("baseUrl", c.baseUrl)
            put("model", c.model)
            put("hasKey", c.key.isNotEmpty())
            put("keyTail", if (c.key.length > 4) c.key.takeLast(4) else "")
            put("enrichModel", c.enrichModel)
            put("enrichUses", c.forEnrich().model)
            put("enrichAuto", c.enrichAuto)
            put("dailyCap", c.dailyCap)
            put("usedToday", used)
            put("records", e.aiStore.len())
            put("total", e.ix?.products?.size ?: 0)
            put("verdicts", JSONObject(e.verdicts.stats() as Map<*, *>))
            e.aiRun?.let { r ->
                put("run", JSONObject().apply {
                    put("kind", r.kind); put("done", r.done); put("of", r.of)
                    put("added", r.added); if (r.err.isNotEmpty()) put("err", r.err); put("live", r.live)
                })
            }
            put("presets", presetsArr)
        }.toString()
    }

    fun changesJson(e: Engine): String {
        val cur = e.ix
        val noticesArr = JSONArray()
        for (n in e.st.notices.asReversed().take(20)) {
            noticesArr.put(JSONObject().put("at", n.at).put("code", n.code).put("title", n.title).put("body", n.body))
        }
        if (cur == null) {
            return JSONObject().put("down", JSONArray()).put("up", JSONArray()).put("total", 0).put("notices", noticesArr).toString()
        }
        val on = HashMap<String, Int>()
        e.activeStores().forEach { i -> on[cur.stores[i].id] = i }
        val latest = LinkedHashMap<String, Change>()
        for (c in e.st.changes) {
            if (on.containsKey(c.store)) latest["${c.code}|${c.store}"] = c
        }
        data class Move(val obj: JSONObject, val pct: Int, val mine: Boolean)
        val down = mutableListOf<Move>()
        val up = mutableListOf<Move>()
        var total = 0
        for (c in latest.values) {
            val p = cur.byCode[c.code] ?: continue
            if (c.from <= 0) continue
            val pct = ((c.to.toDouble() / c.from - 1.0) * 100.0).roundToInt()
            if (pct == 0) continue
            val mine = (e.st.freq[c.code] ?: 0) > 0 || e.st.watch.contains(c.code) || (e.st.cart[c.code] ?: 0.0) > 0
            val obj = JSONObject().apply {
                put("code", c.code); put("name", p.name)
                put("store", cur.storeName(on[c.store]!!))
                put("from", ShopUnits.money(c.from.toDouble()))
                put("to", ShopUnits.money(c.to.toDouble()))
                put("pct", pct); put("day", c.day); put("mine", mine)
                put("img", imgUrl(e, p))
            }
            total++
            if (pct < 0) down.add(Move(obj, pct, mine)) else up.add(Move(obj, pct, mine))
        }
        down.sortWith(compareByDescending<Move> { it.mine }.thenBy { it.pct })
        up.sortWith(compareByDescending<Move> { it.mine }.thenByDescending { it.pct })
        return JSONObject().apply {
            put("down", JSONArray().apply { down.take(150).forEach { put(it.obj) } })
            put("up", JSONArray().apply { up.take(150).forEach { put(it.obj) } })
            put("total", total)
            put("notices", noticesArr)
        }.toString()
    }

    fun watchedJson(e: Engine): String {
        val cur = e.ix ?: return "[]"
        val act = e.activeStores()
        val now = Instant.now()
        val arr = JSONArray()
        for (code in e.st.watch.asReversed()) {
            val p = cur.byCode[code] ?: continue
            arr.put(itemViewJson(e, cur, Hit(p, p.priceInfo(act)), act, now))
        }
        return arr.toString()
    }

    fun listsJson(e: Engine): String {
        val fmt = DateTimeFormatter.ofPattern("d.M.yyyy")
        val arr = JSONArray()
        for (l in e.st.lists) {
            val names = e.ix?.let { cur -> l.items.keys.mapNotNull { cur.byCode[it]?.name }.take(4) } ?: emptyList()
            val dt = Instant.ofEpochSecond(l.at).atZone(ilZone).format(fmt)
            arr.put(JSONObject().apply {
                put("id", l.id); put("name", l.name); put("count", l.items.size)
                put("names", JSONArray(names)); put("at", dt)
            })
        }
        return arr.toString()
    }

    fun shopJson(e: Engine, storeId: String): String {
        val cur = e.ix ?: return JSONObject().put("groups", JSONArray()).put("missing", JSONArray()).put("stores", JSONArray()).toString()
        val act = e.activeStores()
        val now = Instant.now()
        val cmp = cur.compare(e.st.cart, act, e.st.club, now)
        var pickedIdx = act.firstOrNull { cur.stores[it].id == storeId } ?: -1
        if (pickedIdx < 0 && cmp.rows.isNotEmpty()) pickedIdx = cmp.rows[0].store
        if (pickedIdx < 0) return JSONObject().put("groups", JSONArray()).put("missing", JSONArray()).put("stores", JSONArray()).toString()

        val s = cur.stores[pickedIdx]
        val row = cmp.rows.firstOrNull { it.store == pickedIdx }
        val storesArr = JSONArray()
        cmp.rows.forEach { r ->
            storesArr.put(JSONObject().put("id", cur.stores[r.store].id).put("label", "${cur.storeName(r.store)} · ${ShopUnits.money(r.total.toDouble())} ₪"))
        }

        val byCat = LinkedHashMap<String, MutableList<JSONObject>>()
        val missing = mutableListOf<String>()
        var done = 0; var of = 0
        for ((code, q) in e.st.cart) {
            if (q <= 0) continue
            val p = cur.byCode[code] ?: continue
            val pr = cur.priceAt(p, pickedIdx, q, e.st.club, now)
            if (pr == null) {
                missing.add(p.name)
                continue
            }
            val nm = if (p.weighted && p.picks != null && p.picks.getOrNull(pickedIdx)?.isNotEmpty() == true) {
                "${p.name} (${p.picks[pickedIdx]})"
            } else p.name
            val checked = e.st.checked.contains(code)
            val obj = JSONObject().apply {
                put("code", code); put("name", nm); put("qty", q)
                put("price", ShopUnits.money(pr.cost))
                pr.promo?.let { put("deal", ProductIndex.dealText(it)) }
                put("checked", checked); put("stale", p.isStale(pickedIdx)); put("img", imgUrl(e, p))
            }
            byCat.getOrPut(p.cat) { mutableListOf() }.add(obj)
            of++
            if (checked) done++
        }

        val walkOrder = listOf("produce", "bakery", "dairy", "meat", "frozen", "pantry", "snacks", "drinks", "cleaning", "care", "pets", "other")
        val groupsArr = JSONArray()
        val catNames = cur.cat.categories.associate { it.id to it.name }
        for (c in walkOrder) {
            val l = byCat.remove(c) ?: continue
            l.sortBy { it.optString("name") }
            groupsArr.put(JSONObject().put("cat", catNames[c] ?: c).put("items", JSONArray(l)))
        }
        for ((c, l) in byCat) {
            groupsArr.put(JSONObject().put("cat", catNames[c] ?: c).put("items", JSONArray(l)))
        }

        return JSONObject().apply {
            put("store", cur.storeName(pickedIdx))
            put("storeId", s.id)
            put("total", row?.let { ShopUnits.money(it.total.toDouble()) } ?: "0.00")
            put("groups", groupsArr)
            put("missing", JSONArray(missing.sorted()))
            put("done", done); put("of", of)
            put("lat", s.lat ?: 0.0); put("lon", s.lon ?: 0.0)
            put("address", s.address)
            put("stores", storesArr)
        }.toString()
    }

    private val qtyRx = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(?:x|×|יח'?|יחידות)?\s+(.+)$|^(.+?)\s+(?:x|×)\s*(\d+(?:[.,]\d+)?)\s*$""")

    fun parseListJson(e: Engine, text: String): String {
        val cur = e.ix ?: return "[]"
        val act = e.activeStores()
        val arr = JSONArray()
        for (raw in text.split('\n', ',', ';')) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            var line = trimmed.trimStart('-', '*', '•', '·', '▪', '◦', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ')').trim()
            if (line.isEmpty()) line = trimmed
            var q = 1.0; var name = line
            val m = qtyRx.find(trimmed)
            if (m != null) {
                if (m.groupValues[1].isNotEmpty()) {
                    q = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
                    name = m.groupValues[2]
                } else {
                    q = m.groupValues[4].replace(',', '.').toDoubleOrNull() ?: 1.0
                    name = m.groupValues[3]
                }
            }
            if (q <= 0 || q > 50) q = 1.0
            val hits = cur.search(name, SearchOpts(active = act))
            var code = ""; var foundName = ""; var price = ""
            val alts = JSONArray()
            for (h in hits.take(20)) {
                if (h.pi.lo < 0) continue
                if (code.isEmpty()) {
                    code = h.p.code; foundName = h.p.name; price = ShopUnits.money(h.pi.lo.toDouble())
                } else if (alts.length() < 3) {
                    alts.put(JSONObject().put("id", h.p.code).put("label", "${h.p.name} · ${ShopUnits.money(h.pi.lo.toDouble())} ₪"))
                }
            }
            arr.put(JSONObject().apply {
                put("line", name.trim()); put("qty", q); put("code", code)
                put("name", foundName); put("price", price); put("alts", alts)
            })
        }
        return arr.toString()
    }

    fun analysisJson(e: Engine): String {
        val cur = e.ix ?: return JSONObject().put("cheap", JSONArray()).put("dear", JSONArray()).put("own", JSONArray()).put("types", JSONArray()).put("diets", JSONArray()).toString()
        val act = e.activeStores()
        val brands = HashSet<String>(); val types = HashSet<String>()
        var total = 0; var done = 0; var unsure = 0
        data class PricedItem(val p: ShopProduct, val typ: String, val brand: String, val own: Boolean, val unit: UnitPrice, val at: Int)
        val xs = mutableListOf<PricedItem>()
        for (p in cur.products) {
            if (p.code.startsWith("f-")) continue
            total++
            val r = p.ai ?: continue
            done++
            if (r.q) { unsure++; continue }
            if (r.b.isNotEmpty()) brands.add(r.b)
            types.add(r.t)
            val h = Hit(p, p.priceInfo(act))
            val u = h.unit() ?: continue
            xs.add(PricedItem(p, r.t, r.b, r.pl, u, h.pi.at))
        }
        fun median(a: List<Double>): Double {
            val s = a.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }
        val groups = xs.groupBy { "${it.typ}|${it.unit.kind}" }
        val med = HashMap<String, Double>()
        val typeRows = mutableListOf<Pair<Int, JSONObject>>()
        for ((k, l) in groups) {
            if (l.size < 3) continue
            val m = median(l.map { it.unit.agorot })
            med[k] = m
            val best = l.minBy { it.unit.agorot }
            val bs = l.mapNotNull { it.brand.takeIf { b -> b.isNotEmpty() } }.toHashSet()
            val savePct = ((1.0 - best.unit.agorot / m) * 100.0).roundToInt()
            typeRows.add(l.size to JSONObject().apply {
                put("type", best.typ); put("n", l.size); put("brands", bs.size)
                put("code", best.p.code); put("name", best.p.name); put("unit", best.unit.text())
                put("savePct", savePct); put("median", ShopUnits.money(m)); put("where", cur.storeName(best.at))
            })
        }
        typeRows.sortByDescending { it.first }

        data class BrandAcc(val logs: MutableList<Double> = mutableListOf(), val types: HashSet<String> = HashSet(), var own: Int = 0)
        val byBrand = HashMap<String, BrandAcc>()
        val nat = HashMap<String, MutableList<Double>>()
        for (x in xs) {
            val k = "${x.typ}|${x.unit.kind}"
            val m = med[k] ?: continue
            if (x.brand.isEmpty()) continue
            val a = byBrand.getOrPut(x.brand) { BrandAcc() }
            a.logs.add(ln(x.unit.agorot / m))
            a.types.add(x.typ)
            if (x.own) a.own++ else nat.getOrPut(k) { mutableListOf() }.add(x.unit.agorot)
        }
        data class BrandRowData(val brand: String, val n: Int, val types: Int, val pct: Int, val own: Boolean)
        val allBrands = byBrand.entries.mapNotNull { (b, a) ->
            if (a.logs.size < 5 || a.types.size < 2) null
            else BrandRowData(b, a.logs.size, a.types.size, ((exp(a.logs.average()) - 1.0) * 100.0).roundToInt(), a.own * 2 > a.logs.size)
        }.sortedBy { it.pct }

        fun BrandRowData.toJson() = JSONObject().put("brand", brand).put("n", n).put("types", types).put("pct", pct).put("own", own)

        val cheapArr = JSONArray().apply {
            allBrands.filter { it.pct < 0 }.take(15).forEach { put(it.toJson()) }
        }
        val dearArr = JSONArray().apply {
            allBrands.asReversed().filter { it.pct > 0 }.take(15).forEach { put(it.toJson()) }
        }
        val ownSave = HashMap<String, MutableList<Double>>()
        for (x in xs) {
            val l = nat["${x.typ}|${x.unit.kind}"]
            if (!x.own || l == null || l.size < 2) continue
            ownSave.getOrPut(x.brand) { mutableListOf() }.add((1.0 - x.unit.agorot / median(l)) * 100.0)
        }
        val ownArr = JSONArray().apply {
            ownSave.entries
                .filter { it.value.size >= 5 }
                .map { (b, l) -> BrandRowData(b, l.size, 0, median(l).roundToInt(), true) }
                .sortedByDescending { it.pct }
                .forEach { put(it.toJson()) }
        }

        return JSONObject().apply {
            put("total", total); put("done", done); put("unsure", unsure)
            put("brandCount", brands.size); put("typeCount", types.size)
            put("cheap", cheapArr); put("dear", dearArr); put("own", ownArr)
            put("types", JSONArray().apply { typeRows.forEach { put(it.second) } })
            put("diets", dietCounts(cur))
        }.toString()
    }

    fun cartText(e: Engine): String {
        val c = JSONObject(cartJson(e))
        val sb = StringBuilder("רשימת קניות (סל זול)\n")
        val lines = c.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until lines.length()) {
            val l = lines.getJSONObject(i)
            val q = l.optDouble("qty")
            val qStr = if (q == kotlin.math.floor(q)) q.toLong().toString() else q.toString()
            sb.append("• ${l.optString("name")} × $qStr" + if (l.optBoolean("weighted")) " ק״ג" else "").append("\n")
        }
        val rows = c.optJSONArray("rows") ?: JSONArray()
        if (rows.length() > 0) {
            sb.append("\nהכי זול:\n")
            for (i in 0 until minOf(3, rows.length())) {
                val r = rows.getJSONObject(i)
                sb.append("${r.optString("store")}: ${r.optString("total")} ₪")
                val miss = r.optJSONArray("missing")?.length() ?: 0
                if (miss > 0) sb.append(" (חסרים $miss)")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    fun logText(e: Engine): String {
        val st = JSONObject(statusJson(e))
        val sb = StringBuilder()
        val stores = st.optJSONArray("stores") ?: JSONArray()
        val onCount = (0 until stores.length()).count { stores.getJSONObject(it).optBoolean("on") }
        sb.append("סל זול 2.0 · ${Instant.now()} · ready=${st.optBoolean("ready")} products=${st.optInt("products")} stores=$onCount/${stores.length()} priceDate=${st.optString("priceDate")} ai=${st.optBoolean("aiOn")}\n")
        st.optJSONObject("place")?.let { p ->
            sb.append("place: ${p.optString("name")} (${"%.3f".format(p.optDouble("lat"))}, ${"%.3f".format(p.optDouble("lon"))}) r=${p.optDouble("radius").toInt()} km\n")
        }
        for (i in 0 until stores.length()) {
            val s = stores.getJSONObject(i)
            sb.append("  ${s.optString("id")} ${s.optString("chain")} · ${s.optString("branch")} km=${s.optDouble("km")} on=${s.optBoolean("on")}\n")
        }
        val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        for ((ts, tag, msg) in e.getLogs()) {
            val t = Instant.ofEpochSecond(ts).atZone(ilZone).format(fmt)
            sb.append("$t [$tag] $msg\n")
        }
        return sb.toString()
    }
}
