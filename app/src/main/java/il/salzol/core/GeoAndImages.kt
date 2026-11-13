package il.salzol.core

import android.content.res.AssetManager
import il.salzol.core.db.GeocodeCacheEntity
import il.salzol.core.db.SalzolRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

const val USER_AGENT = "salzol/0.1 (personal grocery price comparison)"
const val RAMI_LEVY_IMAGE = "https://img.rami-levy.co.il/product/{code}/small.jpg"
const val DUPLICATE_KM = 0.3
const val MAX_FROM_CENTRE_KM = 10.0

fun kmBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = Math.PI / 180.0
    val p1 = lat1 * r
    val p2 = lat2 * r
    val dp = p2 - p1
    val dl = (lon2 - lon1) * r
    val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2.0 * 6371.0 * asin(sqrt(h))
}

fun normCity(s: String): String {
    return s.replace("-", " ").replace("–", " ")
        .replace("\"", "").replace("״", "")
        .replace("'", "").replace("׳", "")
        .trim().replace(Regex("""\s+"""), " ")
}

object DirectoryRepo {
    @Volatile
    private var assetManager: AssetManager? = null

    @Volatile
    private var cachedBase: List<DirStore>? = null

    fun initAssets(assets: AssetManager) {
        assetManager = assets
    }

    fun defaultStores(): List<DirStore> {
        cachedBase?.let { return it }
        val am = assetManager ?: return emptyList()
        val text = runCatching {
            am.open("directory.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val list = parseDirectoryJson(text)
        cachedBase = list
        return list
    }

    private fun parseDirectoryJson(text: String): List<DirStore> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("stores") ?: return emptyList()
        val out = ArrayList<DirStore>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                DirStore(
                    chain = o.optString("c"),
                    storeId = o.optString("id"),
                    banner = o.optString("b"),
                    name = o.optString("n"),
                    address = o.optString("a"),
                    city = o.optString("city"),
                    lat = o.optDouble("lat", 0.0),
                    lon = o.optDouble("lon", 0.0),
                    approx = o.optBoolean("approx", false)
                )
            )
        }
        return out
    }

    fun loadDirectory(dataDir: String): List<DirStore> {
        val base = defaultStores()
        if (dataDir.isEmpty()) return base
        val extraFile = File(dataDir, "directory-extra.json")
        if (!extraFile.exists()) return base
        val extra = runCatching { parseDirectoryJson(extraFile.readText()) }.getOrDefault(emptyList())
        if (extra.isEmpty()) return base
        val over = extra.map { it.key() }.toHashSet()
        val merged = ArrayList<DirStore>(base.size + extra.size)
        for (s in base) {
            if (!over.contains(s.key())) merged.add(s)
        }
        merged.addAll(extra)
        return merged
    }

    fun cityCentre(stores: List<DirStore>, city: String): Pair<Double, Double>? {
        val want = normCity(city)
        if (want.isEmpty()) return null
        var sx = 0.0; var sy = 0.0; var n = 0
        var ax = 0.0; var ay = 0.0; var an = 0
        for (s in stores) {
            if (s.lat == 0.0 || normCity(s.city) != want) continue
            if (s.approx) {
                ax += s.lat; ay += s.lon; an++
            } else {
                sx += s.lat; sy += s.lon; n++
            }
        }
        return when {
            n > 0 -> (sx / n) to (sy / n)
            an > 0 -> (ax / an) to (ay / an)
            else -> null
        }
    }

    fun nearby(stores: List<DirStore>, lat: Double, lon: Double, radiusKm: Double, max: Int): List<NearStore> {
        val cands = mutableListOf<NearStore>()
        for (s in stores) {
            if (s.lat == 0.0 && s.lon == 0.0) continue
            val km = kmBetween(lat, lon, s.lat, s.lon)
            val limit = if (s.approx) radiusKm - 1.5 else radiusKm
            if (km <= limit) {
                cands.add(NearStore(s, km))
            }
        }
        cands.sortBy { it.km }
        val out = mutableListOf<NearStore>()
        val chosen = HashSet<String>()
        fun isDup(c: NearStore): Boolean {
            for (picked in out) {
                if (picked.store.banner == c.store.banner &&
                    picked.store.key() != c.store.key() &&
                    kmBetween(picked.store.lat, picked.store.lon, c.store.lat, c.store.lon) <= DUPLICATE_KM
                ) {
                    picked.alt.add(c.store)
                    return true
                }
            }
            return false
        }
        val seenBanner = HashSet<String>()
        for (c in cands) {
            if (seenBanner.contains(c.store.banner)) continue
            if (max > 0 && out.size >= max) break
            seenBanner.add(c.store.banner)
            chosen.add(c.store.key())
            out.add(c)
        }
        for (c in cands) {
            if (chosen.contains(c.store.key()) || isDup(c)) continue
            if (!DiscountBanners.contains(c.store.banner) || (max > 0 && out.size >= max)) continue
            chosen.add(c.store.key())
            out.add(c)
        }
        out.sortBy { it.km }
        return out
    }

    fun configFor(stores: List<NearStore>, place: String, lat: Double, lon: Double): Pair<List<Area>, List<StoreRef>> {
        val areas = listOf(Area(place, lat, lon))
        val refs = stores.map { ns ->
            val s = ns.store
            StoreRef(
                chain = s.chain,
                storeId = s.storeId,
                chainName = s.banner,
                branch = branchLabel(s),
                address = listOf(s.address, s.city).filter { it.isNotBlank() }.joinToString(", "),
                lat = s.lat,
                lon = s.lon,
                approx = s.approx,
                alt = ns.alt.map { a ->
                    StoreRef(
                        chain = a.chain,
                        storeId = a.storeId,
                        chainName = a.banner,
                        branch = branchLabel(a),
                        address = listOf(a.address, a.city).filter { it.isNotBlank() }.joinToString(", "),
                        lat = a.lat,
                        lon = a.lon,
                        approx = a.approx
                    )
                }
            )
        }
        return areas to refs
    }

    private fun branchLabel(s: DirStore): String {
        var n = s.name
        val words = s.banner.split(Regex("""\s+""")) + listOf("שופרסל", "קרפור")
        for (w in words) {
            if (w.isNotEmpty()) n = n.replaceFirst(w, "")
        }
        n = n.trim().replace(Regex("""\s+"""), " ").trim(' ', '-', '|', '·', '/')
        if (n.isEmpty()) return s.city
        return n.replace("|", "·").replace(Regex("""\s+"""), " ").trim()
    }
}

class Geocoder(private val cacheFile: File, private val repo: SalzolRepository? = null) {
    private val cache = ConcurrentHashMap<String, Pair<Double, Double>?>()
    private val misses = HashSet<String>()

    init {
        var loadedFromDb = false
        val r = repo
        if (r != null) {
            val dbEntries = runCatching { runBlocking { r.getAllGeocodeEntries() } }.getOrDefault(emptyList())
            if (dbEntries.isNotEmpty()) {
                loadedFromDb = true
                for (e in dbEntries) {
                    if (e.found) cache[e.query] = e.lat to e.lon else misses.add(e.query)
                }
            }
        }
        if (!loadedFromDb && cacheFile.exists()) {
            runCatching {
                val obj = JSONObject(cacheFile.readText())
                val keys = obj.keys()
                val migrated = mutableListOf<GeocodeCacheEntity>()
                val nowSec = Instant.now().epochSecond
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (obj.isNull(k)) {
                        misses.add(k)
                        migrated.add(GeocodeCacheEntity(k, false, 0.0, 0.0, nowSec))
                    } else {
                        val arr = obj.optJSONArray(k)
                        if (arr != null && arr.length() >= 2) {
                            val lat = arr.getDouble(0)
                            val lon = arr.getDouble(1)
                            cache[k] = lat to lon
                            migrated.add(GeocodeCacheEntity(k, true, lat, lon, nowSec))
                        }
                    }
                }
                if (r != null && migrated.isNotEmpty()) {
                    runCatching { runBlocking { r.importLegacyGeocodeEntries(migrated) } }
                }
            }
        }
    }

    @Synchronized
    fun save() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val obj = JSONObject()
            for ((k, v) in cache) {
                if (v != null) obj.put(k, JSONArray().put(v.first).put(v.second))
            }
            for (k in misses) {
                obj.put(k, JSONObject.NULL)
            }
            cacheFile.writeText(obj.toString())
        }
    }

    data class GeoResult(val found: Boolean, val lat: Double = 0.0, val lon: Double = 0.0, val error: String = "")

    fun lookupAddress(rawQuery: String): GeoResult {
        val q = rawQuery.trim().replace(Regex("""\s+"""), " ")
        if (q.isEmpty()) return GeoResult(false)
        cache[q]?.let { return GeoResult(true, it.first, it.second) }
        if (misses.contains(q)) return GeoResult(false)

        var lastErr = ""
        val providers = listOf(::searchNominatim, ::searchPhoton)
        for (provider in providers) {
            try {
                val hits = provider(q)
                val picked = pickHit(hits, q)
                if (picked != null) {
                    cache[q] = picked.lat to picked.lon
                    val r = repo
                    if (r != null) {
                        runCatching { runBlocking { r.saveGeocodeEntry(q, true, picked.lat, picked.lon) } }
                    } else {
                        save()
                    }
                    return GeoResult(true, picked.lat, picked.lon)
                } else {
                    misses.add(q)
                    val r = repo
                    if (r != null) {
                        runCatching { runBlocking { r.saveGeocodeEntry(q, false, 0.0, 0.0) } }
                    } else {
                        save()
                    }
                    return GeoResult(false)
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "network error"
            }
        }

        // Local directory fallback: match city name in query
        val stores = DirectoryRepo.defaultStores()
        val parts = q.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (part in parts.reversed()) {
            DirectoryRepo.cityCentre(stores, part)?.let { (la, lo) ->
                return GeoResult(true, la, lo)
            }
        }
        return GeoResult(false, error = lastErr)
    }

    private data class GeoHit(val lat: Double, val lon: Double, val address: Map<String, String>)

    private fun searchNominatim(q: String): List<GeoHit> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val u = "https://nominatim.openstreetmap.org/search?q=$enc&format=json&limit=5&countrycodes=il&addressdetails=1"
        val body = httpGetString(u, mapOf("Accept-Language" to "he"))
        val arr = JSONArray(body)
        val out = mutableListOf<GeoHit>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            val addrObj = o.optJSONObject("address")
            val addr = mutableMapOf<String, String>()
            if (addrObj != null) {
                val ks = addrObj.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    addr[k] = addrObj.optString(k)
                }
            }
            out.add(GeoHit(lat, lon, addr))
        }
        return out
    }

    private fun searchPhoton(q: String): List<GeoHit> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val u = "https://photon.komoot.io/api?q=$enc&limit=5&bbox=34.2,29.4,35.9,33.4"
        val body = httpGetString(u)
        val obj = JSONObject(body)
        val feats = obj.optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<GeoHit>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val props = f.optJSONObject("properties") ?: continue
            val cc = props.optString("countrycode")
            if (cc.isNotEmpty() && cc != "IL" && cc != "PS") continue
            val addr = mutableMapOf<String, String>()
            for (k in listOf("city", "town", "village", "county", "district", "state", "street", "housenumber")) {
                val v = props.optString(k)
                if (v.isNotEmpty()) addr[k] = v
            }
            out.add(GeoHit(coords.getDouble(1), coords.getDouble(0), addr))
        }
        return out
    }

    private fun pickHit(hits: List<GeoHit>, q: String): GeoHit? {
        if (hits.isEmpty()) return null
        val parts = q.split(",")
        if (parts.size < 2) return hits.first()
        val city = normCity(parts.last())
        if (city.isEmpty() || city == normCity("ישראל")) return hits.first()
        for (h in hits) {
            for (k in listOf("city", "town", "village", "municipality", "hamlet", "suburb")) {
                val c = normCity(h.address[k] ?: "")
                if (c.isNotEmpty() && (c == city || c.contains(city) || city.contains(c))) {
                    return h
                }
            }
        }
        return null
    }
}

private fun httpGetString(urlStr: String, headers: Map<String, String> = emptyMap()): String {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("User-Agent", USER_AGENT)
        for ((k, v) in headers) setRequestProperty(k, v)
    }
    try {
        if (conn.responseCode !in 200..299) {
            throw RuntimeException("HTTP ${conn.responseCode}")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
