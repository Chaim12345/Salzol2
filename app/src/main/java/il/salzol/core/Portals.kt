package il.salzol.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class FileRef(val name: String, val url: String)

class NotPublishedException(val chain: String, val storeId: String) :
    Exception("$chain:$storeId has not published a PriceFull file today (closed, Shabbat, or late)")

interface Portal {
    fun stores(): List<StoreRow>
    fun latestPriceFull(storeId: String): FileRef
    fun latestPromoFull(storeId: String): FileRef
    fun open(f: FileRef): InputStream
}

class PortalHttp(useCookies: Boolean = false) {
    private val cookieManager: CookieManager? =
        if (useCookies) CookieManager(null, CookiePolicy.ACCEPT_ALL) else null

    private fun openConn(urlStr: String, method: String = "GET"): HttpURLConnection {
        var current = URL(urlStr)
        var redirects = 0
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20000
                readTimeout = 45000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                cookieManager?.let { cm ->
                    val cookies = cm.cookieStore.get(current.toURI())
                    if (cookies.isNotEmpty()) {
                        setRequestProperty("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
                    }
                }
            }
            if (method == "GET") {
                val code = conn.responseCode
                saveCookies(conn, current)
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return conn
                    conn.disconnect()
                    if (++redirects > 6) throw RuntimeException("Too many redirects")
                    current = URL(current, loc)
                    continue
                }
            }
            return conn
        }
    }

    private fun saveCookies(conn: HttpURLConnection, url: URL) {
        val cm = cookieManager ?: return
        val uri = runCatching { url.toURI() }.getOrNull() ?: return
        val headerFields = conn.headerFields ?: return
        for ((k, vals) in headerFields) {
            if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                for (v in vals) {
                    runCatching {
                        java.net.HttpCookie.parse(v).forEach { cm.cookieStore.add(uri, it) }
                    }
                }
            }
        }
    }

    fun getStream(urlStr: String): InputStream {
        val conn = openConn(urlStr, "GET")
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw RuntimeException("GET ${redactUrl(urlStr)}: HTTP $code")
        }
        return conn.inputStream
    }

    fun getText(urlStr: String): String = getStream(urlStr).bufferedReader().use { it.readText() }

    fun postForm(urlStr: String, form: Map<String, String>): String {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 45000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            cookieManager?.let { cm ->
                val cookies = cm.cookieStore.get(url.toURI())
                if (cookies.isNotEmpty()) {
                    setRequestProperty("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
                }
            }
        }
        val body = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        saveCookies(conn, url)
        if (code in 300..399) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            if (loc != null) return getText(URL(url, loc).toString())
        }
        if (code != 200) {
            conn.disconnect()
            throw RuntimeException("POST ${redactUrl(urlStr)}: HTTP $code")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}

private fun redactUrl(u: String): String {
    val i = u.indexOf('?')
    return if (i >= 0) u.substring(0, i) else u
}

private val stampRx = Regex("""(\d{8})-?(\d{4,6})?(?:\.\w+)*$""")

fun fileStamp(name: String): String {
    val m = stampRx.find(name) ?: return name
    val d = m.groupValues[1]
    val t = (m.groupValues[2] + "0000").substring(0, 4)
    return d + t
}

fun newestFile(files: List<FileRef>): FileRef =
    files.maxBy { fileStamp(it.name) }

fun storeTag(storeId: String): String {
    val n = storeId.trim().toIntOrNull() ?: 0
    return "-%03d-".format(n)
}

object PortalFactory {
    private val binaChains = mapOf(
        "bareket" to ("superbareket" to listOf("7290875100001")),
        "kingstore" to ("kingstore" to listOf("7290058108879")),
        "maayan2000" to ("maayan2000" to listOf("7290058159628")),
        "zolvebegadol" to ("zolvebegadol" to listOf("7290058173198")),
        "supersapir" to ("supersapir" to listOf("7290058156016")),
        "shukhair" to ("shuk-hayir" to listOf("7290058148776")),
        "shefa" to ("shefabirkathashem" to listOf("7290058134977")),
        "citymarket" to ("citymarketkiryatgat" to listOf("7290058288526", "7290058266241", "7290058288090")),
        "ktmarket" to ("ktshivuk" to listOf("7290058289400"))
    )

    fun create(chain: String): Portal {
        return when (chain) {
            "shufersal" -> ShufersalPortal()
            "carrefour" -> PublishPricePortal("carrefour", "https://prices.carrefour.co.il")
            "quik" -> PublishPricePortal("quik", "https://prices.quik.co.il")
            "victory" -> LaibCatalogPortal("victory", listOf("7290696200003", "7290058103393"))
            "mahsanei" -> LaibCatalogPortal("mahsanei", listOf("7290661400001", "7290633800006"))
            "hcohen" -> LaibCatalogPortal("hcohen", listOf("7290455000004"))
            "wolt" -> WoltPortal()
            "hazihinam" -> HaziHinamPortal()
            "citymarketshops" -> CityMarketShopsPortal()
            "netivhased" -> NetivHesedPortal()
            in binaChains -> {
                val (prefix, ids) = binaChains[chain]!!
                BinaPortal(chain, prefix, ids)
            }
            in PublishedPricesUsers -> PublishedPricesPortal(chain)
            else -> throw IllegalArgumentException("Unknown chain $chain")
        }
    }
}

private class ShufersalPortal : Portal {
    private val http = PortalHttp(false)
    private val base = "https://prices.shufersal.co.il/FileObject/UpdateCategory"
    private val hrefRx = Regex("""href="(https://[^"]+)"""")
    private val nameRx = Regex("""((?:PriceFull|Price|PromoFull|Promo|Stores)\d+-[\d-]+)""")

    private fun links(cat: String, store: String): List<FileRef> {
        val h = http.getText("$base?catID=$cat&storeId=$store").replace("&amp;", "&")
        return hrefRx.findAll(h).map { m ->
            val u = m.groupValues[1]
            val name = nameRx.find(u)?.groupValues?.get(1) ?: u.substringAfterLast('/').substringBefore('?')
            FileRef(name, u)
        }.toList()
    }

    override fun stores(): List<StoreRow> {
        val l = links("5", "0")
        if (l.isEmpty()) throw RuntimeException("shufersal: no stores list")
        return open(l.first()).use { XmlParsers.parseStoresFile(it, "shufersal") }
    }

    override fun latestPriceFull(storeId: String): FileRef = latest("2", "PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = latest("4", "PromoFull", storeId)

    private fun latest(cat: String, prefix: String, storeId: String): FileRef {
        val full = links(cat, storeId).filter { it.name.startsWith(prefix) }
        if (full.isEmpty()) throw NotPublishedException("shufersal", storeId)
        return newestFile(full)
    }

    override fun open(f: FileRef): InputStream = http.getStream(f.url)
}

private class PublishedPricesPortal(private val user: String) : Portal {
    private val http = PortalHttp(true)
    private val base = "https://url.publishedprices.co.il"
    private val csrfRx = Regex("""name="csrftoken" content="([^"]+)"""")
    private var token = ""
    private val cachedFiles = HashMap<String, List<FileRef>>()

    private val logins = mapOf(
        "SalachD" to ("12345" to "/"),
        "Paz_bo" to ("paz468" to "/"),
        "yuda_ho" to ("Yud@147" to "/Yuda")
    )

    @Synchronized
    private fun ensureLogin() {
        if (token.isNotEmpty()) return
        var h = http.getText("$base/login")
        val m1 = csrfRx.find(h) ?: throw RuntimeException("publishedprices: no login token for $user")
        val (pw, _) = logins[user] ?: ("" to "/")
        http.postForm("$base/login/user", mapOf("username" to user, "password" to pw, "csrftoken" to m1.groupValues[1]))
        h = http.getText("$base/file")
        val m2 = csrfRx.find(h) ?: throw RuntimeException("publishedprices login failed for $user")
        token = m2.groupValues[1]
    }

    private fun list(search: String): List<FileRef> {
        cachedFiles[search]?.let { return it }
        ensureLogin()
        val folder = logins[user]?.second ?: "/"
        val (files, subs) = dir(folder, search)
        val out = files.toMutableList()
        for (sub in subs) {
            runCatching {
                val (more, _) = dir(folder.trimEnd('/') + "/" + sub, search)
                out.addAll(more)
            }
        }
        cachedFiles[search] = out
        return out
    }

    private fun dir(folder: String, search: String): Pair<List<FileRef>, List<String>> {
        val body = http.postForm(
            "$base/file/json/dir",
            mapOf(
                "sEcho" to "1",
                "iDisplayStart" to "0",
                "iDisplayLength" to "100000",
                "sSearch" to search,
                "cd" to folder,
                "csrftoken" to token
            )
        )
        val obj = JSONObject(body)
        val arr = obj.optJSONArray("aaData") ?: JSONArray()
        val prefix = folder.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val files = mutableListOf<FileRef>()
        val subs = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val fname = row.optString("fname")
            val ftype = row.optString("ftype")
            if (ftype == "folder" || !fname.any { it.isDigit() }) {
                if (fname.isNotEmpty() && fname != "." && fname != "..") subs.add(fname)
                continue
            }
            files.add(FileRef(fname, "$base/file/d/$prefix$fname"))
        }
        return files to subs
    }

    override fun stores(): List<StoreRow> {
        val l = list("Stores")
        if (l.isEmpty()) throw RuntimeException("$user: no Stores file")
        return open(newestFile(l)).use { XmlParsers.parseStoresFile(it, user) }
    }

    override fun latestPriceFull(storeId: String): FileRef = latest("PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = latest("PromoFull", storeId)

    private fun latest(kind: String, storeId: String): FileRef {
        val tag = storeTag(storeId)
        val mine = list(kind).filter { it.name.contains(tag) && it.name.contains(kind, ignoreCase = true) }
        if (mine.isEmpty()) throw NotPublishedException(user, storeId)
        return newestFile(mine)
    }

    override fun open(f: FileRef): InputStream {
        ensureLogin()
        return http.getStream(f.url)
    }
}

private class PublishPricePortal(private val chain: String, private val base: String) : Portal {
    private val http = PortalHttp(false)
    private val pathRx = Regex("""const path = ['"]([^'"]+)['"]""")
    private val nameRx = Regex(""""name":"([^"]+)"""")
    private var cached: List<FileRef>? = null

    @Synchronized
    private fun index(): List<FileRef> {
        cached?.let { return it }
        val h = http.getText("$base/")
        val p = pathRx.find(h)?.groupValues?.get(1) ?: throw RuntimeException("$chain: index format changed")
        val refs = nameRx.findAll(h).map { it.groupValues[1] }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { FileRef(it, "$base/$p/$it") }
            .toList()
        cached = refs
        return refs
    }

    override fun stores(): List<StoreRow> {
        val st = index().filter { it.name.startsWith("Stores", ignoreCase = true) }
        if (st.isEmpty()) throw RuntimeException("$chain: no Stores file")
        return open(newestFile(st)).use { XmlParsers.parseStoresFile(it, chain) }
    }

    override fun latestPriceFull(storeId: String): FileRef = latest("PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = latest("PromoFull", storeId)

    private fun latest(kind: String, storeId: String): FileRef {
        val tag = storeTag(storeId)
        val mine = index().filter { it.name.startsWith(kind, ignoreCase = true) && it.name.contains(tag) }
        if (mine.isEmpty()) throw NotPublishedException(chain, storeId)
        return newestFile(mine)
    }

    override fun open(f: FileRef): InputStream = http.getStream(f.url)
}

private class LaibCatalogPortal(private val chain: String, private val edis: List<String>) : Portal {
    private val http = PortalHttp(false)
    private val base = "https://laibcatalog.co.il"
    private var files: List<FileRef>? = null

    @Synchronized
    private fun list(): List<FileRef> {
        files?.let { return it }
        val out = mutableListOf<FileRef>()
        var lastErr: Exception? = null
        for (edi in edis) {
            try {
                val body = http.getText("$base/webapi/api/getfiles?edi=$edi")
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val fn = arr.optJSONObject(i)?.optString("fileName") ?: ""
                    if (fn.isNotEmpty()) out.add(FileRef(fn, "$base/webapi/${edis[0]}/$fn"))
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        if (out.isEmpty() && lastErr != null) throw RuntimeException("$chain: ${lastErr.message}")
        files = out
        return out
    }

    override fun stores(): List<StoreRow> {
        val st = list().filter { it.name.startsWith("Stores", ignoreCase = true) }
        if (st.isEmpty()) throw RuntimeException("$chain: no Stores file")
        return open(newestFile(st)).use { XmlParsers.parseStoresFile(it, chain) }
    }

    override fun latestPriceFull(storeId: String): FileRef = latest("PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = latest("PromoFull", storeId)

    private fun latest(kind: String, storeId: String): FileRef {
        val tag = storeTag(storeId)
        val mine = list().filter { it.name.startsWith(kind, ignoreCase = true) && it.name.contains(tag) }
        if (mine.isEmpty()) throw NotPublishedException(chain, storeId)
        return newestFile(mine)
    }

    override fun open(f: FileRef): InputStream = http.getStream(f.url)
}

private class BinaPortal(
    private val chain: String,
    private val prefix: String,
    private val chainIds: List<String>
) : Portal {
    private val http = PortalHttp(true)
    private val base = "https://$prefix.binaprojects.com"
    private var warm = false
    private val cache = HashMap<String, List<FileRef>>()

    @Synchronized
    private fun list(fileType: String): List<FileRef> {
        cache[fileType]?.let { return it }
        if (!warm) {
            runCatching { http.getText("$base/Main.aspx") }
            warm = true
        }
        val out = mutableListOf<FileRef>()
        for (id in chainIds) {
            val q = "https://$prefix.binaprojects.com/MainIO_Hok.aspx?_=${URLEncoder.encode(id, "UTF-8")}&wReshet=%D7%94%D7%9B%D7%9C&WFileType=$fileType&WDate=&WStore="
            val arr = JSONArray(http.getText(q))
            for (i in 0 until arr.length()) {
                val fn = arr.optJSONObject(i)?.optString("FileNm") ?: ""
                if (fn.isNotEmpty()) {
                    out.add(FileRef(fn, "$base/Download.aspx?FileNm=${URLEncoder.encode(fn, "UTF-8")}"))
                }
            }
        }
        cache[fileType] = out
        return out
    }

    override fun stores(): List<StoreRow> {
        val l = list("1")
        if (l.isEmpty()) throw RuntimeException("$chain: no Stores file")
        return open(newestFile(l)).use { XmlParsers.parseStoresFile(it, chain) }
    }

    override fun latestPriceFull(storeId: String): FileRef = latest("4", "PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = latest("5", "PromoFull", storeId)

    private fun latest(fileType: String, kind: String, storeId: String): FileRef {
        val tag = storeTag(storeId)
        val mine = list(fileType).filter { it.name.startsWith(kind, ignoreCase = true) && it.name.contains(tag) }
        if (mine.isEmpty()) throw NotPublishedException(chain, storeId)
        return newestFile(mine)
    }

    override fun open(f: FileRef): InputStream {
        val arr = JSONArray(http.getText(f.url))
        val spath = arr.optJSONObject(0)?.optString("SPath") ?: ""
        if (spath.isEmpty()) throw RuntimeException("$chain: no download path for ${f.name}")
        return http.getStream(spath)
    }
}

private class WoltPortal : Portal {
    private val http = PortalHttp(false)
    private val base = "https://wm-gateway.wolt.com/isr-prices/public/v1/"
    private val hrefRx = Regex("""href="(download/[^"]+)"""")
    private var files: List<FileRef>? = null

    @Synchronized
    private fun list(): List<FileRef> {
        files?.let { return it }
        val out = mutableListOf<FileRef>()
        val today = LocalDate.now()
        for (back in 0 until 3) {
            if (out.isNotEmpty()) break
            val day = today.minusDays(back.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            runCatching {
                val h = http.getText("$base$day.html")
                hrefRx.findAll(h).forEach { m ->
                    val rel = m.groupValues[1]
                    out.add(FileRef(rel.substringAfterLast('/'), base + rel))
                }
            }
        }
        if (out.isEmpty()) throw RuntimeException("wolt: no files listed")
        files = out
        return out
    }

    override fun stores(): List<StoreRow> {
        val st = list().filter { it.name.startsWith("Stores", ignoreCase = true) }
        if (st.isEmpty()) throw RuntimeException("wolt: no Stores file")
        return open(newestFile(st)).use { XmlParsers.parseStoresFile(it, "wolt") }
    }

    override fun latestPriceFull(storeId: String): FileRef = latest("PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = latest("PromoFull", storeId)

    private fun latest(kind: String, storeId: String): FileRef {
        val tag = storeTag(storeId)
        val mine = list().filter { it.name.startsWith(kind, ignoreCase = true) && it.name.contains(tag) }
        if (mine.isEmpty()) throw NotPublishedException("wolt", storeId)
        return newestFile(mine)
    }

    override fun open(f: FileRef): InputStream = http.getStream(f.url)
}

private val rowRx = Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val cellRx = Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val hrefAttrRx = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
private val tagStripRx = Regex("""<[^>]+>""")

private class HaziHinamPortal : Portal {
    private val http = PortalHttp(false)
    private val baseUrl = "https://shop.hazi-hinam.co.il/Prices"
    private val cache = HashMap<String, List<FileRef>>()

    @Synchronized
    private fun list(typ: String, day: String): List<FileRef> {
        val key = "$typ|$day"
        cache[key]?.let { return it }
        val out = mutableListOf<FileRef>()
        val seen = HashSet<String>()
        val base = URL(baseUrl)
        for (page in 1..150) {
            val body = http.getText("$baseUrl?d=$day&t=$typ&f=null&p=$page")
            var added = 0
            for (rm in rowRx.findAll(body)) {
                val cells = cellRx.findAll(rm.groupValues[1]).toList()
                if (cells.size < 6) continue
                var name = tagStripRx.replace(cells[2].groupValues[1], "").trim()
                val href = hrefAttrRx.find(cells[5].groupValues[1])?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
                if (name.isEmpty() || !seen.add(name)) continue
                if (!name.contains('.')) name += ".xml.gz"
                out.add(FileRef(name, URL(base, href).toString()))
                added++
            }
            if (added == 0) break
        }
        cache[key] = out
        return out
    }

    override fun stores(): List<StoreRow> {
        val today = LocalDate.now()
        for (back in 0..2) {
            val day = today.minusDays(back.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val refs = runCatching { list("3", day).filter { it.name.startsWith("Stores", ignoreCase = true) } }.getOrDefault(emptyList())
            if (refs.isNotEmpty()) {
                return open(newestFile(refs)).use { XmlParsers.parseStoresFile(it, "hazihinam") }
            }
        }
        throw RuntimeException("hazihinam: no Stores file")
    }

    override fun latestPriceFull(storeId: String): FileRef = latest("1", "PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = latest("2", "PromoFull", storeId)

    private fun latest(typ: String, kind: String, storeId: String): FileRef {
        val tag = storeTag(storeId)
        val today = LocalDate.now()
        for (back in 0..1) {
            val day = today.minusDays(back.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val mine = list(typ, day).filter { it.name.startsWith(kind, ignoreCase = true) && it.name.contains(tag) }
            if (mine.isNotEmpty()) return newestFile(mine)
        }
        throw NotPublishedException("hazihinam", storeId)
    }

    override fun open(f: FileRef): InputStream = http.getStream(f.url)
}

private class CityMarketShopsPortal : Portal {
    private val http = PortalHttp(false)
    private val baseUrl = "https://www.citymarket-shops.co.il/"
    private val pageRx = Regex("""[?&]p=(\d+)""")
    private val cache = HashMap<String, List<Pair<FileRef, Double>>>()

    @Synchronized
    private fun list(typ: String, full: String, day: String): List<Pair<FileRef, Double>> {
        val key = "$typ|$full|$day"
        cache[key]?.let { return it }
        val out = mutableListOf<Pair<FileRef, Double>>()
        val seen = HashSet<String>()
        val base = URL(baseUrl)
        var pages = 1
        var page = 1
        while (page <= pages && page <= 40) {
            val body = http.getText("${baseUrl}?p=$page&s=&f=$full&t=$typ&d=$day")
            pageRx.findAll(body).forEach { m ->
                val n = m.groupValues[1].toIntOrNull() ?: 1
                if (n > pages) pages = n
            }
            for (rm in rowRx.findAll(body)) {
                val cells = cellRx.findAll(rm.groupValues[1]).toList()
                if (cells.size < 7) continue
                var name = tagStripRx.replace(cells[2].groupValues[1], "").trim()
                val href = hrefAttrRx.find(cells[6].groupValues[1])?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
                if (name.isEmpty() || !seen.add(name)) continue
                val kb = tagStripRx.replace(cells[5].groupValues[1], "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
                if (!name.contains('.')) name += ".xml.gz"
                out.add(FileRef(name, URL(base, href).toString()) to kb)
            }
            page++
        }
        cache[key] = out
        return out
    }

    override fun stores(): List<StoreRow> {
        val refs = list("3", "", "").map { it.first }.filter { it.name.startsWith("Stores", ignoreCase = true) }
        if (refs.isEmpty()) throw RuntimeException("citymarketshops: no Stores file")
        return open(newestFile(refs)).use { XmlParsers.parseStoresFile(it, "citymarketshops") }
    }

    override fun latestPriceFull(storeId: String): FileRef = latest("1", "PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = latest("2", "PromoFull", storeId)

    private fun latest(typ: String, kind: String, storeId: String): FileRef {
        val tag = storeTag(storeId)
        val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        for (day in listOf("", yesterday)) {
            val big = list(typ, "1", day)
                .filter { (f, kb) -> f.name.startsWith(kind, ignoreCase = true) && f.name.contains(tag) && kb >= 50.0 }
                .map { it.first }
            if (big.isNotEmpty()) return newestFile(big)
        }
        throw NotPublishedException("citymarketshops", storeId)
    }

    override fun open(f: FileRef): InputStream = http.getStream(f.url)
}

private class NetivHesedPortal : Portal {
    private val http = PortalHttp(false)
    private val baseUrl = "https://app.netiv-hesed.com/"
    private val cache = HashMap<String, List<FileRef>>()

    @Synchronized
    private fun list(day: String): List<FileRef> {
        cache[day]?.let { return it }
        val body = http.getText("$baseUrl?Date=$day")
        val base = URL(baseUrl)
        val out = mutableListOf<FileRef>()
        val seen = HashSet<String>()
        for (rm in rowRx.findAll(body)) {
            val href = hrefAttrRx.find(rm.groupValues[1])?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
            val u = runCatching { URL(base, href) }.getOrNull() ?: continue
            val qName = u.query?.split("&")?.firstOrNull { it.startsWith("fileName=") }?.substringAfter("fileName=")
            val name = java.net.URLDecoder.decode(qName ?: u.path.substringAfterLast('/'), "UTF-8")
            if (name.isEmpty() || !name.any { it.isDigit() } || !seen.add(name)) continue
            out.add(FileRef(name, u.toString()))
        }
        cache[day] = out
        return out
    }

    override fun stores(): List<StoreRow> {
        val f = find("Stores", "")
        return open(f).use { XmlParsers.parseStoresFile(it, "netivhased") }
    }

    override fun latestPriceFull(storeId: String): FileRef = find("PriceFull", storeId)
    override fun latestPromoFull(storeId: String): FileRef = find("PromoFull", storeId)

    private fun find(kind: String, storeId: String): FileRef {
        val tag = storeTag(storeId)
        val today = LocalDate.now()
        for (back in 0 until 3) {
            val day = today.minusDays(back.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val list = runCatching { list(day) }.getOrNull() ?: continue
            val mine = list.filter { f ->
                f.name.startsWith(kind, ignoreCase = true) &&
                    !(kind == "Price" && f.name.startsWith("PriceFull", ignoreCase = true)) &&
                    !(kind == "Promo" && f.name.startsWith("PromoFull", ignoreCase = true)) &&
                    (storeId.isEmpty() || f.name.contains(tag))
            }
            if (mine.isNotEmpty()) return newestFile(mine)
        }
        throw NotPublishedException("netivhased", storeId)
    }

    override fun open(f: FileRef): InputStream = http.getStream(f.url)
}

class Syncer(
    private val dataDir: String,
    private val isCancelled: () -> Boolean,
    private val onEvent: (CoreEvent) -> Unit
) {
    private data class FetchResult(
        val path: String = "",
        val file: String = "",
        val err: String? = null,
        val promoPath: String = "",
        val promoFile: String = ""
    )

    fun sync(areas: List<Area>, refsInput: List<StoreRef>): Catalog {
        val rawDir = File(dataDir, "raw").apply { mkdirs() }
        val refs = refsInput.toMutableList()
        val byChain = LinkedHashMap<String, MutableList<Int>>()
        refs.forEachIndexed { idx, r -> byChain.getOrPut(r.chain) { mutableListOf() }.add(idx) }

        val results = arrayOfNulls<FetchResult>(refs.size)
        val pool = Executors.newFixedThreadPool(minOf(6, maxOf(1, byChain.size)))
        val futures = mutableListOf<Future<*>>()
        for ((chain, idxs) in byChain) {
            futures.add(pool.submit {
                val portal = runCatching { PortalFactory.create(chain) }.getOrNull()
                if (portal == null) {
                    for (i in idxs) results[i] = FetchResult(err = "unknown chain $chain")
                    return@submit
                }
                for (i in idxs) {
                    if (isCancelled()) return@submit
                    val st = refs[i]
                    try {
                        val (path, file) = fetch(portal, st, rawDir, "PriceFull") { portal.latestPriceFull(it) }
                        var promoPath = ""
                        var promoFile = ""
                        runCatching {
                            val (pp, pf) = fetch(portal, st, rawDir, "PromoFull") { portal.latestPromoFull(it) }
                            promoPath = pp
                            promoFile = pf
                        }.onFailure { e ->
                            onEvent(CoreEvent("promo-skip", st.key(), message = e.message ?: ""))
                        }
                        results[i] = FetchResult(path = path, file = file, promoPath = promoPath, promoFile = promoFile)
                    } catch (e: Exception) {
                        results[i] = FetchResult(err = e.message ?: "fetch error")
                    }
                }
            })
        }
        futures.forEach { it.get() }
        pool.shutdown()
        if (isCancelled()) throw RuntimeException("העדכון בוטל")

        // Try alternates if primary listing failed
        for (i in refs.indices) {
            val r = results[i]
            if (r?.err == null) continue
            for (alt in refs[i].alt) {
                if (isCancelled()) break
                runCatching {
                    val portal = PortalFactory.create(alt.chain)
                    val (path, file) = fetch(portal, alt, rawDir, "PriceFull") { portal.latestPriceFull(it) }
                    var promoPath = ""
                    var promoFile = ""
                    runCatching {
                        val (pp, pf) = fetch(portal, alt, rawDir, "PromoFull") { portal.latestPromoFull(it) }
                        promoPath = pp
                        promoFile = pf
                    }
                    onEvent(CoreEvent("alt", refs[i].key(), message = "using ${alt.key()}"))
                    results[i] = FetchResult(path = path, file = file, promoPath = promoPath, promoFile = promoFile)
                    refs[i] = alt
                }
                if (results[i]?.err == null) break
            }
        }

        // Check if unchanged
        val catFile = File(dataDir, "catalog.json")
        if (catFile.exists()) {
            runCatching {
                val prev = CatalogSerializer.load(catFile)
                if (prev.version == 2 && isUnchanged(prev, areas, refs, results)) {
                    onEvent(CoreEvent("done", message = "unchanged", items = prev.products.size))
                    return prev
                }
            }
        }

        // Parse files
        val parsedStores = arrayOfNulls<StoreData>(refs.size)
        val skipped = mutableListOf<String>()
        val parsePool = Executors.newFixedThreadPool(4)
        val parseFutures = mutableListOf<Future<*>>()
        for (i in refs.indices) {
            val r = results[i] ?: continue
            val st = refs[i]
            val label = "${st.chainName} ${st.branch}"
            if (r.err != null) {
                synchronized(skipped) { skipped.add("$label: ${r.err}") }
                onEvent(CoreEvent("skip", st.key(), message = r.err))
                continue
            }
            parseFutures.add(parsePool.submit {
                if (isCancelled()) return@submit
                try {
                    val items = FileInputStream(r.path).use { XmlParsers.parsePriceFile(it) }
                    val promoFile = if (r.promoPath.isNotEmpty()) {
                        runCatching {
                            FileInputStream(r.promoPath).use { XmlParsers.parsePromoFile(it) }
                        }.onFailure {
                            File(r.promoPath).delete()
                        }.getOrNull()
                    } else null
                    val storeObj = Store(
                        id = st.key(),
                        chain = st.chainName,
                        branch = st.branch,
                        address = st.address,
                        lat = st.lat,
                        lon = st.lon,
                        approx = st.approx,
                        file = r.file,
                        fileTime = fileStamp(r.file),
                        promoFile = if (promoFile != null) r.promoFile else ""
                    )
                    parsedStores[i] = StoreData(storeObj, items, promoFile)
                    onEvent(CoreEvent("parse", st.key(), items = items.size))
                } catch (e: Exception) {
                    File(r.path).delete()
                    synchronized(skipped) { skipped.add("$label: ${e.message}") }
                }
            })
        }
        parseFutures.forEach { it.get() }
        parsePool.shutdown()

        val stores = mutableListOf<StoreData>()
        val chainKeys = mutableListOf<String>()
        for (i in refs.indices) {
            val sd = parsedStores[i]
            if (sd != null) {
                stores.add(sd)
                chainKeys.add(refs[i].chain)
            }
        }
        if (stores.isEmpty()) {
            throw RuntimeException("לא הצלחנו לטעון אף סניף: ${skipped.take(3).joinToString("; ")}")
        }

        val products = CatalogBuilder.buildCatalog(stores, 1, chainKeys)
        onEvent(CoreEvent("build", items = products.size))
        val (promos, promoStats) = CatalogBuilder.buildPromos(stores, products)
        val promoNotes = CatalogBuilder.buildNotes(stores, products)
        val finalizedStores = stores.map { sd ->
            val sig = CatalogBuilder.signals(sd)
            sd.store.copy(hasSold = sig.hasSold, hasChg = sig.hasChg)
        }
        val catalog = Catalog(
            version = 2,
            priceDate = priceDateOf(finalizedStores),
            generatedAt = java.time.Instant.now().toString(),
            skipped = skipped,
            areas = areas,
            categories = CatalogBuilder.usedCategories(products),
            stores = finalizedStores,
            products = products,
            promos = promos,
            promoStats = promoStats,
            promoNotes = promoNotes,
            images = listOf(RAMI_LEVY_IMAGE)
        )
        CatalogSerializer.save(catFile, catalog)
        onEvent(CoreEvent("done", items = products.size))
        return catalog
    }

    private fun fetch(
        portal: Portal,
        st: StoreRef,
        rawDir: File,
        kind: String,
        latestFn: (String) -> FileRef
    ): Pair<String, String> {
        onEvent(CoreEvent("check", st.key()))
        val ref = latestFn(st.storeId)
        var safe = ref.name.replace(Regex("""[/\\:?&]"""), "_")
        if (!safe.endsWith(".gz") && !safe.endsWith(".xml")) safe += ".gz"
        val dst = File(rawDir, "${st.chain}_$safe")
        if (dst.exists() && dst.length() > 0) {
            onEvent(CoreEvent("cached", st.key(), file = ref.name))
            return dst.absolutePath to ref.name
        }
        val tmp = File.createTempFile(".dl-", ".tmp", rawDir)
        try {
            portal.open(ref).use { input ->
                tmp.outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        onEvent(CoreEvent("download", st.key(), file = ref.name, bytes = dst.length()))
        val tag = storeTag(st.storeId)
        rawDir.listFiles()?.forEach { f ->
            if (f.absolutePath != dst.absolutePath &&
                f.name.startsWith("${st.chain}_") &&
                f.name.contains(tag) &&
                f.name.contains(kind)
            ) {
                f.delete()
            }
        }
        return dst.absolutePath to ref.name
    }

    private fun isUnchanged(
        prev: Catalog,
        areas: List<Area>,
        refs: List<StoreRef>,
        results: Array<FetchResult?>
    ): Boolean {
        if (prev.stores.size != refs.size || prev.areas.size != areas.size) return false
        for (i in refs.indices) {
            val r = results[i] ?: return false
            if (r.err != null) return false
            if (prev.stores[i].id != refs[i].key() ||
                prev.stores[i].file != r.file ||
                prev.stores[i].promoFile != r.promoFile
            ) return false
        }
        return true
    }

    private fun priceDateOf(stores: List<Store>): String {
        val best = stores.map { it.fileTime }.filter { it.length >= 8 }.maxOrNull() ?: ""
        if (best.length < 8) return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "${best.substring(0, 4)}-${best.substring(4, 6)}-${best.substring(6, 8)}"
    }
}
