package il.salzol.core

import il.salzol.core.db.SalzolRepository
import il.salzol.core.db.StoredAiRow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

data class AiVerdict(val a: String, val exp: Long)

class AiStore(dir: String, private val repo: SalzolRepository? = null) {
    private val file = File(dir, "ai-records.json")
    private val rows = HashMap<String, StoredRow>()

    data class StoredRow(val code: String, val h: String, val rec: AiRecord, val model: String, val at: Long)

    @Synchronized
    fun loadFromStorage() {
        rows.clear()
        val r = repo
        if (r != null) {
            val dbRows = runCatching { runBlocking { r.getAllAiRecords() } }.getOrDefault(emptyList())
            if (dbRows.isNotEmpty()) {
                for (row in dbRows) {
                    rows[row.code] = StoredRow(row.code, row.hash, row.rec, row.model, row.at)
                }
                return
            }
        }
        if (file.exists()) {
            runCatching {
                val obj = JSONObject(file.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val recJson = obj.getJSONObject(code)
                    val recObj = recJson.optJSONObject("rec") ?: continue
                    rows[code] = StoredRow(
                        code = code,
                        h = recJson.optString("h"),
                        rec = parseAiRecord(recObj),
                        model = recJson.optString("model"),
                        at = recJson.optLong("at")
                    )
                }
            }
            if (r != null && rows.isNotEmpty()) {
                val migrated = rows.values.map { StoredAiRow(it.code, it.h, it.rec, it.model, it.at) }
                runCatching { runBlocking { r.upsertAiRecords(migrated) } }
            }
        }
    }

    @Synchronized
    fun len(): Int = rows.size

    @Synchronized
    fun records(): Map<String, AiRecord> = rows.mapValues { it.value.rec }

    @Synchronized
    fun clear() {
        rows.clear()
        file.delete()
        repo?.let { r ->
            runCatching { runBlocking { r.clearAiRecords() } }
        }
    }

    @Synchronized
    fun put(newRows: List<StoredRow>) {
        if (newRows.isEmpty()) return
        for (row in newRows) rows[row.code] = row
        val r = repo
        if (r != null) {
            val mapped = newRows.map { StoredAiRow(it.code, it.h, it.rec, it.model, it.at) }
            runCatching { runBlocking { r.upsertAiRecords(mapped) } }
        } else {
            save()
        }
    }

    @Synchronized
    fun delta(items: List<AiInputItem>): List<AiInputItem> {
        return items.filter { it ->
            val h = nameHash(it.name)
            val prev = rows[it.code]
            prev == null || prev.h != h
        }
    }

    private fun save() {
        runCatching {
            val obj = JSONObject()
            for ((code, row) in rows) {
                obj.put(code, JSONObject().apply {
                    put("h", row.h)
                    put("model", row.model)
                    put("at", row.at)
                    put("rec", JSONObject().apply {
                        put("t", row.rec.t)
                        put("c", row.rec.c)
                        put("b", row.rec.b)
                        put("l", row.rec.l)
                        put("v", row.rec.v)
                        row.rec.s?.let { s ->
                            put("s", JSONObject().put("a", s.a).put("u", s.u).put("p", s.p))
                        }
                        if (row.rec.d.isNotEmpty()) put("d", JSONArray(row.rec.d))
                        if (row.rec.pl) put("pl", true)
                        if (row.rec.q) put("q", true)
                    })
                })
            }
            file.writeText(obj.toString())
        }
    }

    companion object {
        fun nameHash(s: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(s.trim().lowercase().toByteArray(Charsets.UTF_8))
            return bytes.take(6).joinToString("") { "%02x".format(it) }
        }

        fun parseAiRecord(o: JSONObject): AiRecord {
            val sObj = o.optJSONObject("s")
            val sizeRec = if (sObj != null && sObj.optDouble("a", 0.0) > 0) {
                SizeRec(sObj.optDouble("a"), sObj.optString("u", "u"), sObj.optInt("p", 1))
            } else null
            return AiRecord(
                t = o.optString("t"),
                c = o.optString("c"),
                b = o.optString("b"),
                l = o.optString("l"),
                v = o.optString("v"),
                s = sizeRec,
                d = o.optJSONArray("d")?.toStringList() ?: emptyList(),
                pl = o.optBoolean("pl", false),
                q = o.optBoolean("q", false)
            )
        }
    }
}

data class AiInputItem(val code: String, val name: String, val maker: String, val cat: String)

class VerdictsStore(dir: String, private val repo: SalzolRepository? = null) {
    private val file = File(dir, "ai-verdicts.json")
    private val map = HashMap<String, AiVerdict>()

    @Synchronized
    fun loadFromStorage() {
        map.clear()
        val r = repo
        if (r != null) {
            val dbMap = runCatching { runBlocking { r.getValidVerdicts() } }.getOrDefault(emptyMap())
            if (dbMap.isNotEmpty()) {
                map.putAll(dbMap)
                return
            }
        }
        if (file.exists()) {
            runCatching {
                val obj = JSONObject(file.readText())
                val now = Instant.now().epochSecond
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.getJSONObject(k)
                    val exp = v.optLong("e")
                    if (exp > now) map[k] = AiVerdict(v.optString("a"), exp)
                }
            }
            if (r != null && map.isNotEmpty()) {
                runCatching { runBlocking { r.upsertVerdicts(map.toMap()) } }
            }
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        file.delete()
        repo?.let { r ->
            runCatching { runBlocking { r.clearVerdicts() } }
        }
    }

    @Synchronized
    fun apply(deals: List<Deal>, now: Instant) {
        val ts = now.epochSecond
        for (d in deals) {
            if (d.confidence != "check") continue
            val v = map["${d.chain}|${d.promo.id}|${d.p.code}"] ?: continue
            if (v.exp <= ts) continue
            d.ai = v.a
            if (v.a == "yes") {
                if (d.realPct > 60) {
                    d.checkKind = "deep"
                    d.why = "המבצע חל על המוצר, אבל ההנחה חריגה במיוחד"
                } else {
                    d.confidence = "ok"; d.why = ""; d.checkKind = ""
                }
            }
        }
    }

    @Synchronized
    fun stats(): Map<String, Int> {
        val now = Instant.now().epochSecond
        val out = mutableMapOf("yes" to 0, "no" to 0, "unclear" to 0)
        for (v in map.values) {
            if (v.exp > now) out[v.a] = (out[v.a] ?: 0) + 1
        }
        return out
    }

    @Synchronized
    fun check(
        cfg: AiConfig,
        deals: List<Deal>,
        maxRequests: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int, Int) -> Unit
    ): Pair<Int, String> {
        val now = Instant.now()
        val ts = now.epochSecond
        val groups = LinkedHashMap<String, CheckGroup>()
        for (d in deals) {
            if (d.confidence != "check" || (d.checkKind != "words" && d.checkKind != "spread")) continue
            val k = "${d.chain}|${d.promo.id}|${d.p.code}"
            val existing = map[k]
            if (existing != null && existing.exp > ts) continue
            val gk = "${d.chain}|${d.promo.id}"
            val g = groups.getOrPut(gk) {
                val end = d.end?.epochSecond ?: (ts + 14 * 86400)
                CheckGroup(d.promo.desc, d.chainLabel, end)
            }
            if (g.keys.size < 40 && !g.keys.contains(k)) {
                val nm = if (d.p.maker.isNotEmpty()) "${d.p.name} (${d.p.maker})" else d.p.name
                g.keys.add(k)
                g.names.add(nm)
            }
        }

        val batches = mutableListOf<List<CheckGroup>>()
        var cur = mutableListOf<CheckGroup>()
        var size = 0
        for (g in groups.values) {
            val n = g.desc.length + 30 + g.names.sumOf { it.length + 8 }
            if (cur.isNotEmpty() && size + n > 5000) {
                batches.add(cur); cur = mutableListOf(); size = 0
            }
            cur.add(g); size += n
        }
        if (cur.isNotEmpty()) batches.add(cur)
        val limited = batches.take(maxRequests)
        if (limited.isEmpty()) return 0 to ""

        var asked = 0
        var stopErr = ""
        val delayMs = pacingMs(cfg)
        for ((idx, batch) in limited.withIndex()) {
            if (isCancelled()) { stopErr = "נעצר"; break }
            if (idx > 0 && delayMs > 0) Thread.sleep(delayMs)
            try {
                val prompt = buildCheckPrompt(batch)
                val reply = AiClient.chat(cfg, checkSystemPrompt, prompt, 4096)
                val obj = parseJsonResponse(reply)
                if (obj != null) {
                    asked += recordAnswers(batch, obj, Instant.now().epochSecond)
                }
                onProgress(idx + 1, limited.size)
            } catch (e: Exception) {
                stopErr = e.message ?: "שגיאה"
                break
            }
        }
        return asked to stopErr
    }

    @Synchronized
    private fun recordAnswers(batch: List<CheckGroup>, obj: JSONObject, now: Long): Int {
        var n = 0
        for ((i, g) in batch.withIndex()) {
            val a = obj.optJSONObject("P${i + 1}")
            val seen = HashSet<Int>()
            val until = maxOf(now + 86400L, g.end)
            if (a != null) {
                for (kind in listOf("no", "unclear", "yes")) {
                    val arr = a.optJSONArray(kind) ?: continue
                    for (j in 0 until arr.length()) {
                        val idx = arr.optInt(j, -1) - 1
                        if (idx in g.keys.indices) {
                            seen.add(idx)
                            map[g.keys[idx]] = AiVerdict(kind, until)
                            n++
                        }
                    }
                }
            }
            for ((idx, k) in g.keys.withIndex()) {
                if (!seen.contains(idx)) map[k] = AiVerdict("unclear", now + 86400L)
            }
        }
        saveUpdated(batch, now)
        return n
    }

    private fun saveUpdated(batch: List<CheckGroup>, now: Long) {
        val r = repo
        if (r != null) {
            val entries = HashMap<String, AiVerdict>()
            for (g in batch) {
                for (k in g.keys) {
                    map[k]?.let { entries[k] = it }
                }
            }
            runCatching { runBlocking { r.upsertVerdicts(entries) } }
        } else {
            save()
        }
    }

    private fun save() {
        runCatching {
            val obj = JSONObject()
            for ((k, v) in map) {
                obj.put(k, JSONObject().put("a", v.a).put("e", v.exp))
            }
            file.writeText(obj.toString())
        }
    }

    private data class CheckGroup(
        val desc: String,
        val chain: String,
        val end: Long,
        val keys: MutableList<String> = mutableListOf(),
        val names: MutableList<String> = mutableListOf()
    )

    private fun buildCheckPrompt(batch: List<CheckGroup>): String {
        return batch.mapIndexed { i, g ->
            val lines = mutableListOf("P${i + 1} [${g.chain}] «${g.desc}»")
            g.names.forEachIndexed { j, nm -> lines.add("  ${j + 1}. $nm") }
            lines.joinToString("\n")
        }.joinToString("\n\n")
    }

    private val checkSystemPrompt = """
        You check Israeli supermarket promotions. Each promotion has the chain's own Hebrew wording and the products its price file lists under it.
        For each product, decide whether the promotion's wording covers it ("yes", "no", or "unclear").
        Answer with JSON only, in exactly this shape: {"P1":{"yes":[1,2],"no":[3],"unclear":[]},"P2":{...}}
    """.trimIndent()
}

object AiClient {
    fun chat(cfg: AiConfig, system: String?, user: String, maxTokens: Int): String {
        val base = cfg.baseUrl.trim().trimEnd('/')
        val url = URL("$base/chat/completions")
        val msgs = JSONArray().apply {
            if (!system.isNullOrEmpty()) put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", user))
        }
        val body = JSONObject().apply {
            put("model", cfg.model.trim())
            put("messages", msgs)
            put("temperature", 0)
            put("max_tokens", maxTokens)
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 45000
            setRequestProperty("Content-Type", "application/json")
            if (cfg.key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.key.trim()}")
            if (cfg.preset == "openrouter") setRequestProperty("X-Title", "Salzol")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val respStream = if (code < 300) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val respText = respStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code >= 300) {
            throw RuntimeException(formatError(code, respText))
        }
        val root = JSONObject(respText)
        val choices = root.optJSONArray("choices") ?: throw RuntimeException("תשובה לא תקינה מהשרת")
        if (choices.length() == 0) throw RuntimeException("תשובה ריקה מהשרת")
        return choices.getJSONObject(0).getJSONObject("message").optString("content", "")
    }

    fun models(cfg: AiConfig): List<String> {
        val base = cfg.baseUrl.trim().trimEnd('/')
        val url = URL("$base/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            if (cfg.key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.key.trim()}")
        }
        val code = conn.responseCode
        val respText = (if (code < 300) conn.inputStream else (conn.errorStream ?: conn.inputStream))
            .bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code >= 300) throw RuntimeException(formatError(code, respText))
        val arr = JSONObject(respText).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() }
        }.sorted()
    }

    private fun formatError(status: Int, body: String): String {
        val detail = runCatching {
            val raw = body.trim()
            val obj = if (raw.startsWith("[")) JSONArray(raw).getJSONObject(0) else JSONObject(raw)
            obj.optJSONObject("error")?.optString("message") ?: obj.optString("message")
        }.getOrDefault("").take(140)
        val suffix = if (detail.isNotEmpty()) ": $detail" else ""
        return when {
            status == 401 || status == 403 -> "המפתח לא התקבל ($status)$suffix"
            status == 429 -> "הגעתם למגבלת הבקשות של הספק$suffix"
            status == 404 -> "המודל או הכתובת לא נמצאו (404)$suffix"
            status >= 500 -> "שגיאת שרת אצל הספק ($status)"
            else -> "שגיאה $status$suffix"
        }
    }
}

fun pacingMs(cfg: AiConfig): Long {
    val rpm = (AiPresets[cfg.preset]?.rpm ?: 15).coerceAtLeast(1)
    return 60000L / rpm
}

fun parseJsonResponse(raw: String): JSONObject? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
}

object AiEnricher {
    private const val BATCH_SIZE = 30

    fun enrich(
        cfgInput: AiConfig,
        store: AiStore,
        items: List<AiInputItem>,
        cats: List<Category>,
        maxReq: Int,
        isCancelled: () -> Boolean,
        onBatch: (Int, Int, Int) -> Unit
    ): String {
        val cfg = cfgInput.forEnrich()
        val validCats = cats.map { it.id }.toHashSet()
        val total = minOf(maxReq, (items.size + BATCH_SIZE - 1) / BATCH_SIZE)
        if (total == 0) return ""
        val delayMs = pacingMs(cfg)
        var added = 0
        for (b in 0 until total) {
            if (isCancelled()) return "נעצר"
            if (b > 0 && delayMs > 0) Thread.sleep(delayMs)
            val slice = items.subList(b * BATCH_SIZE, minOf(items.size, (b + 1) * BATCH_SIZE))
            val catList = cats.joinToString(", ") { "${it.id}=${it.name}" }
            val lines = slice.mapIndexed { idx, it ->
                val mk = if (it.maker.isNotEmpty()) " (${it.maker})" else ""
                "${idx + 1}. ${it.name}$mk"
            }.joinToString("\n")
            val sys = """
                Classify Israeli grocery products. Categories: $catList.
                Diet tags (only when clear): ${Diets.joinToString(",")}.
                Return JSON only: {"1":{"t":"סוג מוצר","c":"cat_id","b":"מותג","l":"סדרה","v":"וריאנט","pl":false,"d":[]}, ...}
            """.trimIndent()
            try {
                val reply = AiClient.chat(cfg, sys, lines, 8192)
                val obj = parseJsonResponse(reply)
                if (obj != null) {
                    val nowSec = Instant.now().epochSecond
                    val outRows = mutableListOf<AiStore.StoredRow>()
                    slice.forEachIndexed { idx, it ->
                        val rawRec = obj.optJSONObject("${idx + 1}") ?: return@forEachIndexed
                        val rec = AiStore.parseAiRecord(rawRec)
                        val fixedCat = if (validCats.contains(rec.c)) rec.c else it.cat
                        outRows.add(AiStore.StoredRow(it.code, AiStore.nameHash(it.name), rec.copy(c = fixedCat), cfg.model, nowSec))
                    }
                    added += outRows.size
                    store.put(outRows)
                }
                onBatch(b + 1, total, added)
            } catch (e: Exception) {
                return e.message ?: "שגיאה"
            }
        }
        return ""
    }
}
