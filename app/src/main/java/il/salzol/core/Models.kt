package il.salzol.core

import org.json.JSONArray
import org.json.JSONObject

data class Category(val id: String, val name: String)

val CategoryNames = listOf(
    Category("produce", "פירות וירקות"),
    Category("meat", "בשר, עוף ודגים"),
    Category("dairy", "חלב, גבינות וביצים"),
    Category("bakery", "לחם ומאפים"),
    Category("drinks", "משקאות"),
    Category("snacks", "חטיפים ומתוקים"),
    Category("pantry", "מזווה"),
    Category("frozen", "קפואים"),
    Category("cleaning", "ניקיון ובית"),
    Category("care", "טיפוח ותינוקות"),
    Category("pets", "חיות מחמד"),
    Category("other", "שונות")
)

val PublishedPricesUsers = mapOf(
    "RamiLevi" to "רמי לוי",
    "osherad" to "אושר עד",
    "yohananof" to "יוחננוף",
    "TivTaam" to "טיב טעם",
    "Keshet" to "קשת טעמים",
    "doralon" to "דור אלון (am:pm)",
    "freshmarket" to "פרשמרקט",
    "SalachD" to "סאלח דבאח",
    "Stop_Market" to "סטופ מרקט",
    "politzer" to "פוליצר",
    "Paz_bo" to "יילו",
    "yuda_ho" to "סופר יודה",
    "SuperCofixApp" to "קופיקס"
)

val ChainNames: Map<String, String> = mapOf(
    "shufersal" to "שופרסל",
    "carrefour" to "קרפור",
    "quik" to "קוויק",
    "victory" to "ויקטורי",
    "mahsanei" to "מחסני השוק",
    "hcohen" to "ח. כהן",
    "wolt" to "וולט מרקט",
    "hazihinam" to "חצי חינם",
    "bareket" to "סופר ברקת",
    "kingstore" to "קינג סטור",
    "maayan2000" to "מעיין 2000",
    "zolvebegadol" to "זול ובגדול",
    "supersapir" to "סופר ספיר",
    "shukhair" to "שוק העיר",
    "shefa" to "שפע ברכת השם",
    "citymarket" to "סיטי מרקט",
    "ktmarket" to "קיי.טי מרקט (משנת יוסף)",
    "netivhased" to "נתיב החסד",
    "citymarketshops" to "סיטי מרקט"
) + PublishedPricesUsers

val DiscountBanners = setOf(
    "רמי לוי", "אושר עד", "יוחננוף", "קשת טעמים", "יש חסד",
    "שופרסל יוניברס", "שופרסל דיל", "קרפור היפר",
    "ויקטורי", "מחסני השוק", "חצי חינם"
)

val Diets = listOf(
    "kosher", "mehadrin", "badatz", "parve", "dairy", "meat",
    "gluten_free", "sugar_free", "low_fat", "organic", "vegan",
    "lactose_free", "high_protein", "diet"
)

val DietLabel = mapOf(
    "kosher" to "כשר",
    "mehadrin" to "מהדרין",
    "badatz" to "בד״ץ",
    "parve" to "פרווה",
    "dairy" to "חלבי",
    "meat" to "בשרי",
    "gluten_free" to "ללא גלוטן",
    "sugar_free" to "ללא סוכר",
    "low_fat" to "דל שומן",
    "organic" to "אורגני",
    "vegan" to "טבעוני",
    "lactose_free" to "ללא לקטוז",
    "high_protein" to "עתיר חלבון",
    "diet" to "דיאט"
)

data class DirStore(
    val chain: String,
    val storeId: String,
    val banner: String,
    val name: String,
    val address: String,
    val city: String,
    val lat: Double,
    val lon: Double,
    val approx: Boolean = false
) {
    fun key(): String = "$chain:$storeId"
}

data class NearStore(
    val store: DirStore,
    val km: Double,
    val alt: MutableList<DirStore> = mutableListOf()
)

data class Area(val name: String, val lat: Double, val lon: Double)

data class StoreRef(
    val chain: String,
    val storeId: String,
    val chainName: String,
    val branch: String,
    val address: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val approx: Boolean = false,
    val alt: List<StoreRef> = emptyList()
) {
    fun key(): String = "$chain:$storeId"
}

data class Store(
    val id: String,
    val chain: String,
    val branch: String,
    val address: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val approx: Boolean = false,
    val file: String = "",
    val fileTime: String = "",
    val promoFile: String = "",
    val hasSold: Boolean = false,
    val hasChg: Boolean = false
)

data class StoreRow(
    val chain: String,
    val storeId: String,
    val name: String,
    val address: String,
    val city: String,
    val subChain: String = ""
)

data class Item(
    val code: String,
    val name: String,
    val price: Double,
    val qty: String = "",
    val unit: String = "",
    val weighted: Boolean = false,
    val maker: String = "",
    val unitQty: String = "",
    val country: String = "",
    val updated: String = "",
    val sold: String = "",
    val noPrice: Boolean = false
)

data class Promo(
    val id: String,
    val codes: List<String>,
    val minQty: Int,
    val kind: String, // "price", "bundle", "percent"
    val value: Double, // agorot or percent
    val club: Boolean = false,
    val desc: String = "",
    val max: Int = 0,
    val start: String = "",
    val end: String = "",
    val second: Boolean = false
)

data class PromoNote(
    val id: String,
    val codes: List<String>,
    val desc: String,
    val why: String,
    val club: Boolean = false,
    val start: String = "",
    val end: String = ""
)

data class PromoStats(
    val parsed: Int = 0,
    val modeled: Int = 0,
    val applied: Int = 0,
    val skipped: Map<String, Int> = emptyMap()
)

data class PromoFile(
    val total: Int,
    val promos: List<Promo>,
    val notes: List<PromoNote>,
    val skipped: Map<String, Int>
)

data class StatedSize(val kind: UnitKind, val amount: Double)

data class ProductMeta(
    val sizeAmount: Double? = null,
    val sizeUnit: String? = null,
    val sizeAgree: Int = 0,
    val maker: String = "",
    val il: Boolean = false,
    val stale: List<Pair<Int, Int>> = emptyList(),
    val chg: List<Pair<Int, Int>> = emptyList(),
    val np: List<Int> = emptyList(),
    val odd: List<Pair<Int, Int>> = emptyList(),
    val raw: String = ""
) {
    fun isEmpty(): Boolean =
        sizeAmount == null && maker.isEmpty() && !il &&
            stale.isEmpty() && chg.isEmpty() && np.isEmpty() && odd.isEmpty() && raw.isEmpty()
}

data class CatalogProductRow(
    val code: String,
    val name: String,
    val cat: String,
    val weighted: Int,
    val prices: List<Int?>,
    val picks: List<String?>? = null,
    val img: String? = null,
    val meta: ProductMeta? = null
)

data class Catalog(
    val version: Int = 2,
    val priceDate: String,
    val generatedAt: String,
    val skipped: List<String>,
    val areas: List<Area>,
    val categories: List<Category>,
    val stores: List<Store>,
    val products: List<CatalogProductRow>,
    val promos: List<List<Promo>>,
    val promoStats: List<PromoStats>,
    val promoNotes: List<List<PromoNote>>,
    val images: List<String>
)

data class Place(val name: String, val lat: Double, val lon: Double, val radius: Double)

data class Change(
    val day: String,
    val code: String,
    val store: String,
    val from: Int,
    val to: Int
)

data class Notice(
    val at: Long,
    val code: String,
    val title: String,
    val body: String,
    var read: Boolean = false
)

data class SavedList(
    val id: String,
    val name: String,
    val items: Map<String, Double>,
    val at: Long
)

data class Usage(val day: String = "", var n: Int = 0)

data class SizeRec(val a: Double, val u: String, val p: Int)

data class AiRecord(
    val t: String = "",
    val c: String = "",
    val b: String = "",
    val l: String = "",
    val v: String = "",
    val s: SizeRec? = null,
    val d: List<String> = emptyList(),
    val pl: Boolean = false,
    val q: Boolean = false
)

data class AiConfig(
    val on: Boolean = false,
    val preset: String = "gemini",
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai",
    val model: String = "gemini-flash-lite-latest",
    val key: String = "",
    val enrichModel: String = "",
    val enrichAuto: Boolean = true,
    val dailyCap: Int = 300
) {
    fun usable(): Boolean =
        on && model.isNotBlank() && validAiUrl(baseUrl) && (key.isNotBlank() || preset == "custom")

    fun forEnrich(): AiConfig {
        val presetObj = AiPresets[preset] ?: AiPresets["custom"]!!
        return when {
            enrichModel.isNotBlank() -> copy(model = enrichModel.trim())
            presetObj.enrichModel.isNotBlank() && baseUrl == presetObj.baseUrl -> copy(model = presetObj.enrichModel)
            else -> this
        }
    }
}

data class AiPreset(
    val label: String,
    val baseUrl: String,
    val model: String,
    val keyUrl: String,
    val enrichModel: String,
    val rpm: Int
)

val AiPresets = mapOf(
    "gemini" to AiPreset(
        "Gemini (חינם)",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "gemini-flash-lite-latest",
        "https://aistudio.google.com/apikey",
        "gemini-4-hydrogen",
        10
    ),
    "openrouter" to AiPreset(
        "OpenRouter",
        "https://openrouter.ai/api/v1",
        "",
        "https://openrouter.ai/keys",
        "",
        15
    ),
    "groq" to AiPreset(
        "Groq",
        "https://api.groq.com/openai/v1",
        "",
        "https://console.groq.com/keys",
        "",
        25
    ),
    "custom" to AiPreset(
        "אחר (תואם OpenAI)",
        "",
        "",
        "",
        "",
        20
    )
)

private val aiUrlRx = Regex("""^(https://[^\s/]+|http://(localhost|127\.0\.0\.1|10\.0\.2\.2)(:\d+)?)(/|$)""")
fun validAiUrl(u: String): Boolean = aiUrlRx.containsMatchIn(u.trim())

data class EngineEvent(
    val type: String,
    val msg: String = "",
    val done: Int = 0,
    val of: Int = 0
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type)
        if (msg.isNotEmpty()) put("msg", msg)
        if (done > 0) put("done", done)
        if (of > 0) put("of", of)
    }.toString()
}

data class CoreEvent(
    val phase: String,
    val store: String = "",
    val file: String = "",
    val items: Int = 0,
    val bytes: Long = 0,
    val millis: Long = 0,
    val message: String = ""
)

enum class UnitKind { Weight, Volume, Count }

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, null)

fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { if (isNull(it)) null else optString(it) }
