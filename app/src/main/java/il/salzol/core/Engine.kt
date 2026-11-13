package il.salzol.core

import android.os.Looper
import il.salzol.core.db.SalzolRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class AiStatus(
    val kind: String = "",
    var done: Int = 0,
    var of: Int = 0,
    var added: Int = 0,
    var err: String = "",
    var at: Long = 0,
    var live: Boolean = false
)

class EngineState {
    var place: Place? = null
    val off = mutableListOf<String>()
    val cart = LinkedHashMap<String, Double>()
    val checked = HashSet<String>()
    val club = HashMap<String, Boolean>()
    val watch = mutableListOf<String>()
    val freq = HashMap<String, Int>()
    val changes = mutableListOf<Change>()
    val notices = mutableListOf<Notice>()
    val lists = mutableListOf<SavedList>()
    var ai: AiConfig = AiConfig()
    var usage: Usage = Usage()
    var images: Boolean = true
}

class Engine(val dir: String, private val repo: SalzolRepository? = null) {
    val st = EngineState()
    val aiStore = AiStore(dir, repo)
    val verdicts = VerdictsStore(dir, repo)

    @Volatile var ix: ProductIndex? = null
    @Volatile private var cachedDeals: List<Deal>? = null
    @Volatile private var dealsKey: String = ""
    @Volatile var syncing: Boolean = false
    @Volatile var syncMsg: String = ""
    @Volatile var syncErr: String = ""
    @Volatile var building: Boolean = false
    @Volatile var aiRun: AiStatus? = null

    private var syncCancel = AtomicBoolean(false)
    private var aiCancel = AtomicBoolean(false)
    private val loadedLatch = CountDownLatch(1)
    private val bgPool = Executors.newCachedThreadPool()
    private val lock = Any()
    private val logLines = mutableListOf<Triple<Long, String, String>>()
    @Volatile private var emitter: (EngineEvent) -> Unit = {}

    init {
        File(dir).mkdirs()
        bgPool.submit {
            try {
                loadState()
                aiStore.loadFromStorage()
                verdicts.loadFromStorage()
                emit(EngineEvent("state"))
                loadCatalog(false)
            } finally {
                loadedLatch.countDown()
            }
        }
        logf("app", "start")
    }

    fun ensureLoaded() {
        if (loadedLatch.count > 0) {
            runCatching { loadedLatch.await() }
        }
    }

    fun setEmitter(fn: (EngineEvent) -> Unit) {
        emitter = fn
    }

    fun emit(ev: EngineEvent) {
        runCatching { emitter(ev) }
    }

    fun logf(tag: String, msg: String) {
        synchronized(logLines) {
            logLines.add(Triple(Instant.now().epochSecond, tag, msg))
            if (logLines.size > 1500) logLines.removeAt(0)
        }
    }

    fun getLogs(): List<Triple<Long, String, String>> = synchronized(logLines) { logLines.toList() }

    private fun loadState() {
        val r = repo
        if (r != null) {
            val loaded = runCatching { runBlocking { r.loadEngineStateInto(st) } }.getOrDefault(false)
            if (loaded) return
        }
        val f = File(dir, "state.json")
        if (f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                o.optJSONObject("place")?.let { p ->
                    st.place = Place(p.optString("name"), p.optDouble("lat"), p.optDouble("lon"), p.optDouble("radius", 4.0))
                }
                o.optJSONArray("off")?.let { a -> (0 until a.length()).forEach { st.off.add(a.getString(it)) } }
                o.optJSONObject("cart")?.let { c ->
                    c.keys().forEach { k -> st.cart[k] = c.optDouble(k) }
                }
                o.optJSONObject("checked")?.let { c ->
                    c.keys().forEach { k -> if (c.optBoolean(k)) st.checked.add(k) }
                }
                o.optJSONObject("club")?.let { c ->
                    c.keys().forEach { k -> st.club[k] = c.optBoolean(k) }
                }
                o.optJSONArray("watch")?.let { a -> (0 until a.length()).forEach { st.watch.add(a.getString(it)) } }
                o.optJSONObject("freq")?.let { c ->
                    c.keys().forEach { k -> st.freq[k] = c.optInt(k) }
                }
                o.optJSONArray("changes")?.let { a ->
                    for (i in 0 until a.length()) {
                        val c = a.getJSONObject(i)
                        st.changes.add(Change(c.optString("day"), c.optString("code"), c.optString("store"), c.optInt("from"), c.optInt("to")))
                    }
                }
                o.optJSONArray("notices")?.let { a ->
                    for (i in 0 until a.length()) {
                        val n = a.getJSONObject(i)
                        st.notices.add(Notice(n.optLong("at"), n.optString("code"), n.optString("title"), n.optString("body"), n.optBoolean("read")))
                    }
                }
                o.optJSONArray("lists")?.let { a ->
                    for (i in 0 until a.length()) {
                        val l = a.getJSONObject(i)
                        val items = LinkedHashMap<String, Double>()
                        l.optJSONObject("items")?.let { io -> io.keys().forEach { k -> items[k] = io.optDouble(k) } }
                        st.lists.add(SavedList(l.optString("id"), l.optString("name"), items, l.optLong("at")))
                    }
                }
                o.optJSONObject("ai")?.let { a ->
                    st.ai = AiConfig(
                        on = a.optBoolean("on", false),
                        preset = a.optString("preset", "gemini"),
                        baseUrl = a.optString("baseUrl", AiPresets["gemini"]!!.baseUrl),
                        model = a.optString("model", AiPresets["gemini"]!!.model),
                        key = a.optString("key", ""),
                        enrichModel = a.optString("enrichModel", ""),
                        enrichAuto = a.optBoolean("enrichAuto", true),
                        dailyCap = a.optInt("dailyCap", 300)
                    )
                }
                o.optJSONObject("usage")?.let { u ->
                    st.usage = Usage(u.optString("day"), u.optInt("n"))
                }
                st.images = o.optBoolean("images", true)
            }
        }
        if (repo != null) {
            saveState()
        }
    }

    @Synchronized
    fun saveState() {
        val r = repo
        if (r != null) {
            runCatching { runBlocking { r.saveEngineState(st) } }
            return
        }
        runCatching {
            val o = JSONObject().apply {
                st.place?.let { p ->
                    put("place", JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("radius", p.radius))
                }
                put("off", JSONArray(st.off))
                put("cart", JSONObject(st.cart as Map<*, *>))
                put("checked", JSONObject().apply { st.checked.forEach { put(it, true) } })
                put("club", JSONObject(st.club as Map<*, *>))
                put("watch", JSONArray(st.watch))
                put("freq", JSONObject(st.freq as Map<*, *>))
                put("changes", JSONArray().apply {
                    st.changes.forEach { c ->
                        put(JSONObject().put("day", c.day).put("code", c.code).put("store", c.store).put("from", c.from).put("to", c.to))
                    }
                })
                put("notices", JSONArray().apply {
                    st.notices.forEach { n ->
                        put(JSONObject().put("at", n.at).put("code", n.code).put("title", n.title).put("body", n.body).put("read", n.read))
                    }
                })
                put("lists", JSONArray().apply {
                    st.lists.forEach { l ->
                        put(JSONObject().put("id", l.id).put("name", l.name).put("items", JSONObject(l.items as Map<*, *>)).put("at", l.at))
                    }
                })
                put("ai", aiConfigJsonObj(st.ai))
                put("usage", JSONObject().put("day", st.usage.day).put("n", st.usage.n))
                put("images", st.images)
            }
            val tmp = File(dir, "state.json.tmp")
            tmp.writeText(o.toString())
            val target = File(dir, "state.json")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun aiConfigJsonObj(c: AiConfig): JSONObject = JSONObject().apply {
        put("on", c.on)
        put("preset", c.preset)
        put("baseUrl", c.baseUrl)
        put("model", c.model)
        put("key", c.key)
        put("enrichModel", c.enrichModel)
        put("enrichAuto", c.enrichAuto)
        put("dailyCap", c.dailyCap)
    }

    fun loadCatalog(afterSync: Boolean) {
        val catFile = File(dir, "catalog.json")
        if (!catFile.exists()) return
        building = true
        try {
            val cat = CatalogSerializer.load(catFile)
            val newIx = ProductIndex.fromCatalog(cat, aiStore.records())
            synchronized(lock) {
                val old = ix
                ix = newIx
                cachedDeals = null
                dealsKey = ""
                if (afterSync && old != null && old.cat.generatedAt != cat.generatedAt) {
                    diffPrices(old, newIx)
                    saveState()
                }
            }
        } catch (e: Exception) {
            logf("catalog", "שגיאה: ${e.message}")
        } finally {
            building = false
        }
        emit(EngineEvent("catalog"))
        if (afterSync) {
            bgPool.submit { runCatching { autoAI() } }
        }
    }

    fun activeStores(): List<Int> = synchronized(lock) {
        val cur = ix ?: return emptyList()
        cur.stores.indices.filter { !st.off.contains(cur.stores[it].id) }
    }

    fun dealsNow(): List<Deal> = synchronized(lock) {
        val cur = ix ?: return emptyList()
        val act = activeStores()
        val clubKey = st.club.entries.filter { it.value }.map { it.key }.sorted().joinToString(",")
        val key = "${st.off.joinToString(",")}|$clubKey"
        val existing = cachedDeals
        if (existing != null && dealsKey == key) return existing
        val built = cur.buildDeals(act, st.club, Instant.now())
        verdicts.apply(built, Instant.now())
        cachedDeals = built
        dealsKey = key
        built
    }

    fun setPlace(name: String, lat: Double, lon: Double, radiusKm: Double) {
        val apply = {
            ensureLoaded()
            val r = if (radiusKm <= 0) 4.0 else radiusKm
            synchronized(lock) {
                st.place = Place(name, lat, lon, r)
                st.off.clear()
                saveState()
            }
            sync()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bgPool.submit(apply)
        } else {
            apply()
        }
    }

    fun sync() {
        val p: Place
        synchronized(lock) {
            if (syncing || st.place == null) return
            p = st.place!!
            syncing = true
            syncMsg = "מחפש סניפים…"
            syncErr = ""
            syncCancel = AtomicBoolean(false)
        }
        emit(EngineEvent("sync", "מחפש סניפים…"))
        bgPool.submit {
            val t0 = System.currentTimeMillis()
            try {
                logf("sync", "start: ${p.name} (${"%.3f".format(p.lat)}, ${"%.3f".format(p.lon)}) r=${p.radius.toInt()}")
                val stores = DirectoryRepo.loadDirectory(dir)
                val near = DirectoryRepo.nearby(stores, p.lat, p.lon, p.radius, 18)
                if (near.isEmpty()) throw RuntimeException("אין סניפים מוכרים בטווח הזה. נסו מרחק גדול יותר.")
                val (areas, refs) = DirectoryRepo.configFor(near, p.name, p.lat, p.lon)
                var parsed = 0
                val syncer = Syncer(dir, { syncCancel.get() }) { ev ->
                    if ((ev.phase == "skip" || ev.phase == "alt") && ev.message.isNotEmpty()) {
                        logf("portal", "${ev.phase} ${ev.store}: ${ev.message}")
                    }
                    val msg = when (ev.phase) {
                        "download", "cached", "skip" -> "בודק מחירונים חדשים…"
                        "parse" -> { parsed++; "מעבד מחירונים… $parsed סניפים" }
                        "build" -> "בונה את הקטלוג…"
                        else -> ""
                    }
                    if (msg.isNotEmpty()) {
                        syncMsg = msg
                        emit(EngineEvent("sync", msg))
                    }
                }
                syncer.sync(areas, refs)
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                logf("sync", "הסתיים: ${near.size} סניפים, ${"%.1f".format(secs)} שניות")
                syncing = false
                loadCatalog(true)
                emit(EngineEvent("sync"))
            } catch (e: Exception) {
                val msg = e.message ?: "שגיאה בעדכון"
                logf("sync", "נכשל: $msg")
                syncing = false
                syncErr = msg
                emit(EngineEvent("sync", msg))
            }
        }
    }

    fun cancelSync() {
        syncCancel.set(true)
    }

    fun geocode(q: String): String {
        val g = Geocoder(File(dir, "geocode.json"), repo)
        val res = g.lookupAddress(q)
        return JSONObject().apply {
            put("found", res.found)
            put("lat", res.lat)
            put("lon", res.lon)
            if (res.error.isNotEmpty()) put("error", res.error)
        }.toString()
    }

    fun setQty(code: String, q: Double): Int = synchronized(lock) {
        if (q <= 0) {
            st.cart.remove(code)
        } else {
            if (!st.cart.containsKey(code)) st.freq[code] = (st.freq[code] ?: 0) + 1
            st.cart[code] = q
        }
        saveState()
        st.cart.size
    }

    fun clearCart() = synchronized(lock) {
        st.cart.clear()
        st.checked.clear()
        saveState()
    }

    fun toggleStore(id: String) = synchronized(lock) {
        if (!st.off.remove(id)) st.off.add(id)
        cachedDeals = null
        saveState()
    }

    fun setClub(chain: String, on: Boolean) = synchronized(lock) {
        st.club[chain] = on
        cachedDeals = null
        saveState()
    }

    fun toggleWatch(code: String): Boolean = synchronized(lock) {
        val added = if (st.watch.remove(code)) false else { st.watch.add(code); true }
        saveState()
        added
    }

    fun setImages(on: Boolean) = synchronized(lock) {
        st.images = on
        saveState()
    }

    fun setChecked(code: String, on: Boolean) = synchronized(lock) {
        if (on) st.checked.add(code) else st.checked.remove(code)
        saveState()
    }

    fun saveList(rawName: String) = synchronized(lock) {
        val base = rawName.trim().ifEmpty { "הסל שלי" }
        var name = base
        var n = 2
        while (st.lists.any { it.name == name }) {
            name = "$base ($n)"; n++
        }
        val id = java.lang.Long.toString(System.nanoTime(), 36)
        st.lists.add(0, SavedList(id, name, HashMap(st.cart), Instant.now().epochSecond))
        saveState()
    }

    fun loadList(id: String, merge: Boolean) = synchronized(lock) {
        val l = st.lists.firstOrNull { it.id == id } ?: return
        if (!merge) st.cart.clear()
        for ((k, v) in l.items) st.cart[k] = (st.cart[k] ?: 0.0) + v
        saveState()
    }

    fun deleteList(id: String) = synchronized(lock) {
        st.lists.removeAll { it.id == id }
        saveState()
    }

    fun addMany(itemsJson: String) = synchronized(lock) {
        runCatching {
            val obj = JSONObject(itemsJson)
            obj.keys().forEach { c ->
                val q = obj.optDouble(c, 0.0)
                if (q > 0) {
                    if (!st.cart.containsKey(c)) st.freq[c] = (st.freq[c] ?: 0) + 1
                    st.cart[c] = (st.cart[c] ?: 0.0) + q
                }
            }
            saveState()
        }
    }

    fun lookup(barcode: String): String = synchronized(lock) {
        val cur = ix ?: return ""
        val trimmed = barcode.trim().trimStart('0')
        if (cur.byCode.containsKey(trimmed)) return trimmed
        if (cur.byCode.containsKey(barcode.trim())) return barcode.trim()
        return ""
    }

    fun takeNotices(): String = synchronized(lock) {
        val out = JSONArray()
        var changed = false
        for (n in st.notices) {
            if (!n.read) {
                n.read = true
                changed = true
                out.put(JSONObject().put("at", n.at).put("code", n.code).put("title", n.title).put("body", n.body))
            }
        }
        if (changed) saveState()
        out.toString()
    }

    fun refreshNow(timeoutSec: Int): String {
        loadedLatch.await()
        sync()
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (!syncing) {
                if (syncErr.isEmpty()) {
                    loadCatalog(false)
                    autoAI()
                }
                return syncErr
            }
            Thread.sleep(1000)
        }
        cancelSync()
        return "timeout"
    }

    private fun diffPrices(old: ProductIndex, nw: ProductIndex) {
        val day = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val oldStore = HashMap<String, Int>()
        old.stores.forEachIndexed { idx, s -> oldStore[s.id] = idx }
        val add = mutableListOf<Change>()
        for (p in nw.products) {
            val op = old.byCode[p.code] ?: continue
            nw.stores.forEachIndexed { i, s ->
                val j = oldStore[s.id] ?: return@forEachIndexed
                val np = p.prices[i]
                val opp = op.prices[j]
                if (np >= 0 && opp >= 0 && np != opp) {
                    add.add(Change(day, p.code, s.id, opp, np))
                }
            }
        }
        if (add.isEmpty()) return
        val cut = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        st.changes.removeAll { it.day < cut }
        st.changes.addAll(add)
        if (st.changes.size > 6000) {
            val drop = st.changes.size - 6000
            repeat(drop) { st.changes.removeAt(0) }
        }
        val act = activeStores()
        val ids = act.map { nw.stores[it].id }.toHashSet()
        for (code in st.watch) {
            val p = nw.byCode[code] ?: continue
            val op = old.byCode[code] ?: continue
            var before = -1
            old.stores.forEachIndexed { j, s ->
                if (ids.contains(s.id) && op.prices[j] > 0 && (before < 0 || op.prices[j] < before)) {
                    before = op.prices[j]
                }
            }
            val after = p.priceInfo(act)
            if (before > 0 && after.lo > 0 && after.lo < before) {
                val pct = ((1.0 - after.lo.toDouble() / before) * 100.0).roundToInt()
                st.notices.add(
                    Notice(
                        at = Instant.now().epochSecond,
                        code = code,
                        title = "ירידת מחיר: ${p.name}",
                        body = "${ShopUnits.money(after.lo.toDouble())} ₪ במקום ${ShopUnits.money(before.toDouble())} ₪ (−$pct%) ב${nw.storeName(after.at)}"
                    )
                )
            }
        }
        if (st.notices.size > 100) {
            repeat(st.notices.size - 100) { st.notices.removeAt(0) }
        }
        logf("sync", "${add.size} מחירים זזו")
    }

    // ---------------- AI controls ----------------

    fun setAI(cfgJson: String) {
        runCatching {
            val a = JSONObject(cfgJson)
            val cfg = AiConfig(
                on = a.optBoolean("on", false),
                preset = a.optString("preset", "gemini"),
                baseUrl = a.optString("baseUrl", AiPresets["gemini"]!!.baseUrl),
                model = a.optString("model", AiPresets["gemini"]!!.model),
                key = a.optString("key", ""),
                enrichModel = a.optString("enrichModel", ""),
                enrichAuto = a.optBoolean("enrichAuto", true),
                dailyCap = a.optInt("dailyCap", 300).let { if (it <= 0) 300 else it }
            )
            synchronized(lock) {
                st.ai = cfg
                saveState()
            }
            if (!cfg.on) stopAI()
        }
    }

    fun testAI(): String {
        val c = synchronized(lock) { st.ai.copy(on = true) }
        if (!c.usable()) return "חסרים מפתח, כתובת או שם מודל"
        return try {
            val txt = AiClient.chat(c, null, """Answer with JSON only: {"ok":true}""", 1024)
            val obj = parseJsonResponse(txt)
            if (obj?.optBoolean("ok", false) == true) "" else "המודל ענה, אבל לא בפורמט הנדרש"
        } catch (e: Exception) {
            e.message ?: "שגיאה"
        }
    }

    fun models(): String {
        val c = synchronized(lock) { st.ai }
        return try {
            val m = AiClient.models(c)
            JSONObject().put("models", JSONArray(m)).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "שגיאה").toString()
        }
    }

    fun startAI() {
        bgPool.submit {
            ensureLoaded()
            runCatching { autoAI() }
        }
    }

    private fun autoAI() {
        val c = synchronized(lock) { st.ai }
        if (!c.usable()) return
        runCheck()
        if (c.enrichAuto) runEnrich()
    }

    fun runCheck() {
        val c = synchronized(lock) { st.ai }
        if (!c.usable()) return
        val deals = dealsNow()
        if (deals.isEmpty()) return
        synchronized(lock) {
            if (aiRun?.live == true) return
            aiCancel = AtomicBoolean(false)
            aiRun = AiStatus(kind = "check", live = true)
        }
        val (asked, stopErr) = verdicts.check(c, deals, 30, { aiCancel.get() }) { done, of ->
            aiRun?.apply { this.done = done; this.of = of }
            emit(EngineEvent("ai", done = done, of = of))
        }
        if (asked > 0) synchronized(lock) { cachedDeals = null }
        aiRun?.apply { live = false; err = stopErr; at = Instant.now().epochSecond }
        emit(EngineEvent("ai", msg = stopErr))
    }

    fun runEnrich() {
        val c = synchronized(lock) { st.ai }
        if (!c.usable()) return
        val cur = ix ?: return
        val sorted = cur.products.filter { !it.code.startsWith("f-") }
            .sortedByDescending { p -> p.prices.count { it >= 0 } }
            .map { AiInputItem(it.code, it.rawName.ifEmpty { it.name }, it.maker, it.cat) }
        val todo = aiStore.delta(sorted)
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val used = if (st.usage.day == today) st.usage.n else 0
        val left = maxOf(0, c.dailyCap - used)
        if (todo.isEmpty() || left == 0) return

        synchronized(lock) {
            if (aiRun?.live == true) return
            aiCancel = AtomicBoolean(false)
            aiRun = AiStatus(kind = "enrich", live = true)
        }
        val stopErr = AiEnricher.enrich(c, aiStore, todo, cur.cat.categories, left, { aiCancel.get() }) { done, of, added ->
            synchronized(lock) {
                if (st.usage.day != today) st.usage = Usage(today, 0)
                st.usage.n++
                saveState()
            }
            aiRun?.apply { this.done = done; this.of = of; this.added = added }
            emit(EngineEvent("ai", done = done, of = of))
        }
        aiRun?.apply { live = false; err = stopErr; at = Instant.now().epochSecond }
        emit(EngineEvent("ai", msg = stopErr))
        loadCatalog(false)
    }

    fun stopAI() {
        aiCancel.set(true)
    }

    fun clearAI() {
        stopAI()
        aiStore.clear()
        verdicts.clear()
        synchronized(lock) { cachedDeals = null }
        bgPool.submit { loadCatalog(false) }
    }
}
