package il.salzol.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

data class StoreData(
    val store: Store,
    val items: List<Item>,
    val promos: PromoFile? = null
)

data class StoreSignals(
    val ref: LocalDateTime?,
    val hasSold: Boolean,
    val hasChg: Boolean
)

object CatalogBuilder {
    private data class CatRule(val key: String, val inc: Regex, val exc: Regex? = null)

    private val catRules = listOf(
        CatRule("produce", Regex("""^(עגבני|מלפפון|פלפל |חסה|כרוב|גזר|בצל|שום |תפוח עץ|בננ|אבוקדו|לימון|תפוז|קלמנטינ|ענב|אגס|פטריות|נבטים|עשבי|פטרוזיליה|כוסברה|שמיר|נענע|סלק|קישוא|חציל|בטטה|תות|אוכמניות|מארז אוכמניות|מלון|אבטיח|עגבניות שרי)""")),
        CatRule("pets", Regex("""כלב|חתול|גורים|דוגלי|ויסקס|פריסקיז|פדיגרי|בונזו|חול ל""")),
        CatRule("frozen", Regex("""קפוא|מוקפא|גלידה|ארטיק|שלגון|בצק עלים|קרפלך|רביולי|כיסונ""")),
        CatRule("pantry", Regex("""^מרק|אבקת מרק|ציר |^רוטב|^תערובת|^סירופ|תמצית|אצות|קוקטייל פירות|אננס|אפרס""")),
        CatRule("snacks", Regex("""שוקולד|חטיף|במבה|ביסלי|ופל""")),
        CatRule("dairy", Regex("""יוגורט|^גבינ""")),
        CatRule("cleaning", Regex("""רצפות|קוטל|לילי |גלילים|מרכך """), Regex("""מרכך ל?שיער|מרכך נגד""")),
        CatRule("care", Regex("""תח\.ספיגה|תחתוני ספיגה|חיתול|האגיס|פמפרס|טיטולים|קרפרי|תחבושת|טמפון|מטרנה|סימילאק|תמ"ל|שמפו|מרכך שיער|דאודורנט|משחת שיניים|מברשת שיניים|ג'ל רחצה|תחליב|גילוח|סכיני|קולגייט|אוראל|ניוואה|דאב |הד אנד|פינוק|מרכך ל?שיער|מרכך נגד|דאו |שפתון|שעווה|אולוויז|משחת|מרידול|אל תוש|קרם (ידיים|גוף|פנים|הגנה)|סבון (ידיים|גוף|נוזלי)|משייף|מסיר איפור|מי פה|צמר גפן|קיסמי""")),
        CatRule("cleaning", Regex("""אקונומיקה|כביסה|מרכך כביסה|מדיח|ספוג|מגבונ|נייר טואלט|מגבות? נייר|סופר מגבת|שקיות|נייר אפיה|נייר כסף|רדיד|מטהר|מסיר|ניקוי|נוזל כלים|פרסיל|אריאל|סנו|פיניש|טישו|ממחטות|סבון כלים|אשפ|כוסות|צלחות|מזלגות|כפיות|מפיות|מטלית|גפרורים|ניקול|פיירי|XPO|נ\.רצפה|רצפות|סוללות|נרות|קיסמים|תבניות? אלומיניום|כלים חד""")),
        CatRule("meat", Regex("""עוף|בקר|הודו|כבש|שניצל|נקניק|קבב|המבורגר|פסטרמה|סלמי|טונה|סרדינ|סלמון|דג |דגי|אמנון|לוקוס|נסיכת|הרינג|שרימפס|בשר|קציצות|מעדנות"""), Regex("""כבשים""")),
        CatRule("dairy", Regex("""חלב|גבינ|^גב\.|^ג\.|יוגורט|יופלה|מעדן|^לבן |לבנה|קוטג|שמנת|חמאה|ביצים|ביצה|דנונ|אקטימל|מילקי|פודינג|צפתית|בולגרית|מוצרלה|פטה|ריקוטה|קפיר|פרמזן|פרמזנו|פקורינו|גאודה|עמק|נעם|גלבוע|קממבר|ברי |צ'דר|אמנטל|מסקרפונה|יטבתה|תנובה|שטראוס|דני |גו |כבשים""")),
        CatRule("bakery", Regex("""לחם|לחמני|פיתה|פיתות|בגט|חלה|חלות|טורטיה|מאפה|עוגה|עוגת|עוגיות|רוגלך|קרואסון|בורקס|מצה|קרקר|פריכיות|לחמית|בייגל|כעכים|ביסקוויט|וופל|טוסט""")),
        CatRule("drinks", Regex("""מים |^מים|סודה|קולה|ספרייט|פאנטה|סבן אפ|מיץ|נקטר|משקה|^תה |חליטת|ליפטון|ויסוצקי|קפה|נס קפה|נספרסו|יין|בירה|וודקה|וויסקי|ליקר|ערק|פריגת|ספרינג|שוופס|אנרגיה|רד בול|נביעות|עין גדי|קפסול|שרדונה|קברנה|מרלו|סוביניון|רוזה|מוסקטו|למברוסקו|גולדסטאר|קרלסברג|הייניקן|סמוזי|פיוז טי|נסטי""")),
        CatRule("snacks", Regex("""במבה|ביסלי|תפוצ|דוריטוס|ציפס|צ'יפס|חטיף|שוקולד|ש\.מריר|ש\.חלב|סוכרי|מסטיק|קליק|פסק זמן|מקופלת|קינדר|בונבונ|קרמבו|ממתק|גומי|לוטוס|אגוזי|פיצוח|גרעינ|שקדים|בוטנים|פופקורן|דגני|קורנפלקס|גרנולה|פיטנס|כריות|חלבה|ממרח|מנטוס|טוסו|מרציפן|בייגלה|גמייקה|סלים|ליציס|קינוח|מקדמיה|קשיו|פיסטוק|צימוק|פירות יבשים|תמרים|אגוז|טופי|מרשמלו|סוכריות|ריבת חלב|נוטלה|אפרופו|תפוציפס|צ'יטוס|פרינגלס""")),
        CatRule("pantry", Regex("""אורז|פסטה|ספגטי|פנה|פתיתים|קמח|סוכר|מלח|שמן|חומץ|קטשופ|מיונז|חרדל|טחינה|חומוס|שעועית|עדשים|תירס|זיתים|מלפפונים|רסק|שימורי|תבלין|פפריקה|כמון|פלפל שחור|אבקת|שמרים|דבש|ריבה|קוסקוס|בורגול|קינואה|פירורי|אטריות|נודלס|רכז|קוקוס|וניל|סילאן|קנולה|גלטין|אפונ|שקשוקה|לקט|סלט|מטבל|אנשובי|קפרי|רוטב|פסטו|ג'לי|קורנפלור|סודה לשתיה|אבקת אפיה|לימון כבוש"""))
    )

    fun categorize(name: String): String {
        for (r in catRules) {
            if (r.inc.containsMatchIn(name) && (r.exc == null || !r.exc.containsMatchIn(name))) {
                return r.key
            }
        }
        return "other"
    }

    private data class FreshRule(
        val id: String,
        val label: String,
        val category: String,
        val inc: Regex,
        val exc: Regex?,
        val min: Double,
        val max: Double
    )

    private fun fresh(id: String, label: String, cat: String, inc: String, exc: String, min: Double, max: Double) =
        FreshRule(id, label, cat, Regex(inc), if (exc.isNotEmpty()) Regex(exc) else null, min, max)

    private val freshRules = listOf(
        fresh("f-tomato", "עגבניות", "produce", """^(עגבני|עגבניות)(\s|$)""", """שרי|אשכול|מיובש|תמר|מארז|אורגנ""", 2.0, 20.0),
        fresh("f-cherry", "עגבניות שרי", "produce", """עגבניות שרי""", """אורגנ""", 5.0, 40.0),
        fresh("f-cucumber", "מלפפונים", "produce", """^מלפפון|^מלפפונים""", """בייבי|חמוץ|כבוש|מיני|אורגנ""", 2.0, 20.0),
        fresh("f-onion", "בצל יבש", "produce", """^בצל(\s+יבש)?(\s|$)""", """ירוק|סגול|אדום|שאלוט|פנינה|אורגנ""", 1.5, 15.0),
        fresh("f-potato", "תפוח אדמה", "produce", """^תפוח(י)?\s*אדמה|^תפו"א""", """מיני|בטטה|אדום|גורמה|אורגנ""", 2.0, 15.0),
        fresh("f-carrot", "גזר", "produce", """^גזר(\s|$)""", """גמדי|אורגנ|צבעוני|שקית|ארוז""", 2.0, 15.0),
        fresh("f-pepper", "פלפל אדום", "produce", """פלפל\s*אדום""", """חריף|אורגנ""", 3.0, 25.0),
        fresh("f-zucchini", "קישואים", "produce", """^קישוא""", """אורגנ""", 3.0, 20.0),
        fresh("f-eggplant", "חצילים", "produce", """^חציל""", """בלאדי|קטן|אורגנ""", 3.0, 20.0),
        fresh("f-batata", "בטטה", "produce", """^בטט""", """אורגנ""", 3.0, 22.0),
        fresh("f-avocado", "אבוקדו", "produce", """^אבוקדו""", """אורגנ""", 5.0, 40.0),
        fresh("f-banana", "בננות", "produce", """^בננ""", """אורגנ""", 3.0, 22.0),
        fresh("f-lemon", "לימון", "produce", """^לימון""", """אורגנ""", 3.0, 22.0),
        fresh("f-orange", "תפוזים", "produce", """^תפוז""", """אורגנ|סחיטה""", 3.0, 18.0),
        fresh("f-clementine", "קלמנטינות", "produce", """^קלמנטינ""", "", 3.0, 15.0),
        fresh("f-apple", "תפוח עץ (הזן הזול בסניף)", "produce", """^תפוח עץ""", """אורגנ|סטורי|ברשת""", 5.0, 25.0),
        fresh("f-grapes", "ענבים ירוקים", "produce", """^ענב(ים)? (ירוק|לבן)""", """אורגנ""", 10.0, 45.0),
        fresh("f-chickenbreast", "חזה עוף טרי", "meat", """^(חזה|פילה) עוף""", """קפוא|שלם|חצוי|פרוס|"יש"|מעושן|מתובל""", 25.0, 70.0),
        fresh("f-schnitzel", "שניצל עוף טרי", "meat", """^שניצל(ון)? עוף""", """קפוא|שניצלוני|מתובל|פרימיום|בוטיק""", 30.0, 70.0),
        fresh("f-thighs", "כרעיים עוף טריות", "meat", """^כרעיים( עוף)?""", """קפוא|מתובל|מעושנ|בשום|איטלקי|בלי עור|מקוצב|"יש"""", 15.0, 50.0),
        fresh("f-drumsticks", "שוקיים עוף טריות", "meat", """^שוקיים( עוף)?""", """קפוא|הודו|בלי עור|"יש"""", 15.0, 55.0),
        fresh("f-groundbeef", "בשר בקר טחון טרי", "meat", """(בקר טחון|בשר( בקר)? טחון|טחון בקר|בשר טחון)""", """קפוא|עגל|הודו|עוף|צלעות""", 45.0, 110.0)
    )

    private val stampFmt12 = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    private val stampFmt19 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val stampFmt16 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val stampFmt10 = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun parseStamp(s: String): LocalDateTime? {
        val t = s.trim()
        if (t.length == 12 && t.none { it == '-' || it == ':' || it == ' ' || it == 'T' }) {
            return runCatching { LocalDateTime.parse(t, stampFmt12) }.getOrNull()
        }
        if (t.length < 10) return null
        val norm = t.replace('T', ' ')
        if (norm.length >= 19) {
            runCatching { LocalDateTime.parse(norm.substring(0, 19), stampFmt19) }.getOrNull()?.let { return it }
        }
        if (norm.length >= 16) {
            runCatching { LocalDateTime.parse(norm.substring(0, 16), stampFmt16) }.getOrNull()?.let { return it }
        }
        return runCatching { java.time.LocalDate.parse(norm.substring(0, 10), stampFmt10).atStartOfDay() }.getOrNull()
    }

    fun daysBetween(ref: LocalDateTime?, t: LocalDateTime?): Int {
        if (ref == null || t == null || t.isAfter(ref)) return 0
        return ChronoUnit.DAYS.between(t, ref).toInt()
    }

    fun signals(sd: StoreData): StoreSignals {
        var ref = parseStamp(sd.store.fileTime)
        var newest: LocalDateTime? = null
        var sold = 0
        var upd = 0
        val stamps = mutableListOf<LocalDateTime>()
        for (it in sd.items) {
            if (it.sold.isNotEmpty()) {
                parseStamp(it.sold)?.let { t ->
                    sold++
                    if (newest == null || t.isAfter(newest)) newest = t
                }
            }
            if (it.updated.isNotEmpty()) {
                parseStamp(it.updated)?.let { t ->
                    upd++
                    stamps.add(t)
                    if (newest == null || t.isAfter(newest)) newest = t
                }
            }
        }
        if (ref == null || (newest != null && newest!!.isAfter(ref))) {
            ref = newest
        }
        val n = sd.items.size
        val hasSold = n > 0 && sold * 2 >= n
        var hasChg = false
        if (n > 0 && upd * 2 >= n) {
            var recent = 0
            for (t in stamps) {
                if (daysBetween(ref, t) <= 2) recent++
            }
            hasChg = recent * 4 < stamps.size
        }
        return StoreSignals(ref, hasSold, hasChg)
    }

    private val unitQuotesRx = Regex("""["'״׳.\s]""")
    private fun unitKindOf(uRaw: String): Pair<String, Double>? {
        val u = unitQuotesRx.replace(uRaw, "").lowercase()
        return when {
            u.startsWith("קילו") || u == "קג" || u == "kg" -> "g" to 1000.0
            u.startsWith("מיליליטר") || u == "מל" || u == "ml" -> "ml" to 1.0
            u.startsWith("ליטר") || u == "ל" || u == "l" || u == "lt" -> "ml" to 1000.0
            u.startsWith("גרם") || u == "גר" || u == "ג" || u == "g" || u == "gr" -> "g" to 1.0
            u.startsWith("יחיד") || u == "יח" || u == "unit" || u == "units" -> "u" to 1.0
            else -> null
        }
    }

    private fun itemSize(it: Item): Triple<String, Double, Boolean> {
        val q = it.qty.trim().toDoubleOrNull() ?: return Triple("", 0.0, false)
        if (q <= 0) return Triple("", 0.0, false)
        var (kind, mult) = unitKindOf(it.unitQty) ?: ("" to 0.0)
        if (kind.isEmpty()) {
            when {
                q < 10 -> return Triple("", 0.0, false)
                it.unit.contains("גרם") -> { kind = "g"; mult = 1.0 }
                it.unit.contains("מיליליטר") || it.unit.contains("""מ"ל""") -> { kind = "ml"; mult = 1.0 }
                else -> return Triple("", 0.0, false)
            }
        }
        val amount = ((q * mult * 10.0).roundToInt()) / 10.0
        val ok = when (kind) {
            "g" -> amount in 5.0..50000.0
            "ml" -> amount in 10.0..30000.0
            "u" -> amount in 2.0..1000.0
            else -> false
        }
        return Triple(kind, amount, ok)
    }

    private val makerParens = Regex("""\(.*?\)""")
    private val makerPunct = Regex("""[,/\\|]+""")
    private val makerSuffix = Regex("""(?i)\b(ltd|inc|llc|gmbh)\b|בע"?מ""")
    private val noMaker = setOf("לא ידוע", "unknown", "כללי", "אחר", "none", "-", "0")
    private val genericMakerWords = setOf(
        "מאפיית", "מאפיות", "יקב", "יקבי", "מעדני", "חברת", "קבוצת", "מפעלי",
        "שיווק", "אחים", "משק", "מחלבת", "מחלבות", "סלטי", "תעשיות", "טחנות",
        "מוצרי", "גלידות", "פרח", "the", "א", "מ", "ש", "י"
    )

    private fun cleanMaker(sRaw: String): String {
        var s = sRaw.replace("\"", "").replace("'", "").replace("״", "").replace("׳", "")
        s = makerParens.replace(s, " ")
        s = makerPunct.replace(s, " ")
        s = makerSuffix.replace(s, " ")
        s = s.trim().replace(Regex("""\s+"""), " ")
        if (s.length < 2 || noMaker.contains(s.lowercase())) return ""
        return s
    }

    private fun makerKey(clean: String): String {
        val w = clean.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (w.isEmpty()) return ""
        if (genericMakerWords.contains(w[0]) && w.size > 1) return "${w[0]} ${w[1]}"
        return w[0]
    }

    private fun isIsrael(sRaw: String): Pair<Boolean, Boolean> {
        return when (sRaw.trim().lowercase()) {
            "", "לא ידוע", "unknown", "0", "-", "לא מוגדר" -> false to false
            "il", "isr", "israel", "ישראל", "il2", "תוצרת הארץ" -> true to true
            else -> false to true
        }
    }

    private class MetaAccumulator {
        val sizeVotes = HashMap<Double, HashMap<String, String>>() // amount -> chain -> kind
        val makerVotes = HashMap<String, HashMap<String, String>>() // key -> chain -> clean
        val ilChains = HashSet<String>()
        val notIlChains = HashSet<String>()
        val stale = mutableListOf<Pair<Int, Int>>()
        val chg = mutableListOf<Pair<Int, Int>>()

        fun add(storeIdx: Int, chain: String, it: Item, sig: StoreSignals) {
            val (kind, amt, ok) = itemSize(it)
            if (ok) {
                sizeVotes.getOrPut(amt) { HashMap() }.putIfAbsent(chain, kind)
            }
            val cm = cleanMaker(it.maker)
            val mk = makerKey(cm)
            if (mk.isNotEmpty()) {
                makerVotes.getOrPut(mk) { HashMap() }.putIfAbsent(chain, cm)
            }
            val (isIl, known) = isIsrael(it.country)
            if (known) {
                if (isIl) ilChains.add(chain) else notIlChains.add(chain)
            }
            dates(storeIdx, it, sig)
        }

        fun dates(storeIdx: Int, it: Item, sig: StoreSignals) {
            if (sig.hasSold) {
                val d = daysBetween(sig.ref, parseStamp(it.sold))
                if (d >= 14) stale.add(storeIdx to d)
            }
            if (sig.hasChg) {
                val t = parseStamp(it.updated)
                if (t != null) {
                    val d = daysBetween(sig.ref, t)
                    if (d <= 30) chg.add(storeIdx to d)
                }
            }
        }

        fun build(cat: String): ProductMeta? {
            var bestSizeAmt: Double? = null
            var bestSizeUnit: String? = null
            var bestSizeN = 0
            for ((amt, chains) in sizeVotes) {
                val n = chains.size
                if (n > bestSizeN) {
                    bestSizeN = n
                    bestSizeAmt = amt
                    bestSizeUnit = if (cat == "drinks" && chains.values.contains("ml")) "ml"
                    else chains.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                }
            }
            if (bestSizeN < 2 && sizeVotes.size > 1) {
                bestSizeAmt = null
                bestSizeUnit = null
                bestSizeN = 0
            }

            var bestMakerKey = ""
            var bestMakerN = 1
            for ((k, chains) in makerVotes) {
                if (chains.size > bestMakerN || (chains.size == bestMakerN && bestMakerKey.isNotEmpty() && k < bestMakerKey)) {
                    bestMakerKey = k
                    bestMakerN = chains.size
                }
            }
            val maker = if (bestMakerKey.isNotEmpty()) {
                makerVotes[bestMakerKey]!!.values.minWithOrNull(compareBy<String> { it.length }.thenBy { it }) ?: ""
            } else ""

            val il = ilChains.size >= 2 && ilChains.size > notIlChains.size
            val meta = ProductMeta(
                sizeAmount = bestSizeAmt,
                sizeUnit = bestSizeUnit,
                sizeAgree = bestSizeN,
                maker = maker,
                il = il,
                stale = stale.sortedBy { it.first },
                chg = chg.sortedBy { it.first }
            )
            return if (meta.isEmpty()) null else meta
        }
    }

    private class Entry(nStores: Int) {
        val names = mutableListOf<String>()
        val prices = DoubleArray(nStores) { Double.NaN }
        val np = mutableListOf<Int>()
        val meta = MetaAccumulator()
    }

    fun buildCatalog(stores: List<StoreData>, minStores: Int, chainKeys: List<String>): List<CatalogProductRow> {
        val n = stores.size
        val sigs = stores.map { signals(it) }
        val chainOf = stores.indices.map { i ->
            chainKeys.getOrNull(i)?.takeIf { it.isNotEmpty() }
                ?: stores[i].store.chain.takeIf { it.isNotEmpty() }
                ?: "#$i"
        }

        val byCode = LinkedHashMap<String, Entry>()
        val local = HashMap<String, Boolean>()

        for (i in stores.indices) {
            val sd = stores[i]
            for (it in sd.items) {
                var key = it.code
                if (it.code.length < 7 || it.weighted) {
                    val code = it.code.ifEmpty { "n:${it.name}" }
                    key = "${chainOf[i]}:$code"
                    local[key] = it.weighted
                }
                val e = byCode.getOrPut(key) { Entry(n) }
                if (it.noPrice) {
                    if (e.prices[i].isNaN() && !e.np.contains(i)) {
                        e.np.add(i)
                        e.names.add(it.name)
                    }
                    continue
                }
                if (e.prices[i].isNaN()) {
                    e.prices[i] = it.price
                    e.names.add(it.name)
                    e.meta.add(i, chainOf[i], it, sigs[i])
                    e.np.remove(i)
                }
            }
        }

        val products = mutableListOf<CatalogProductRow>()
        for ((code, e) in byCode) {
            val have = e.prices.filter { !it.isNaN() }
            var need = if (local.containsKey(code)) 1 else minStores
            if (have.isEmpty() && e.np.isNotEmpty()) need = 0
            if (have.size < need) continue

            val prices = MutableList<Int?>(n) { null }
            val odd = mutableListOf<Pair<Int, Int>>()
            if (have.isNotEmpty()) {
                val med = median(have)
                for (i in 0 until n) {
                    val p = e.prices[i]
                    if (p.isNaN()) continue
                    val agorot = (p * 100.0).roundToInt()
                    if (p >= med / 4.0 && p <= med * 4.0) {
                        prices[i] = agorot
                    } else {
                        odd.add(i to agorot)
                    }
                }
            }
            val name = e.names.maxByOrNull { it.length } ?: code
            val cat = categorize(name)
            var meta = e.meta.build(cat)
            if (meta != null) {
                meta = meta.copy(
                    stale = meta.stale.filter { (idx, _) -> idx < prices.size && prices[idx] != null },
                    chg = meta.chg.filter { (idx, _) -> idx < prices.size && prices[idx] != null }
                )
            }
            if (e.np.isNotEmpty() || odd.isNotEmpty()) {
                val baseMeta = meta ?: ProductMeta()
                meta = baseMeta.copy(np = e.np.sorted(), odd = odd)
            }
            if (meta?.isEmpty() == true) meta = null

            if (local.containsKey(code)) {
                val weighted = if (local[code] == true) 1 else 0
                val parts = code.split(":", limit = 2)
                val img = if (parts.size == 2 && parts[0] == "RamiLevi") parts[1] else null
                products.add(CatalogProductRow(code, name, cat, weighted, prices, null, img, meta))
            } else {
                products.add(CatalogProductRow(code, name, cat, 0, prices, null, code, meta))
            }
        }

        for (fr in freshRules) {
            val prices = MutableList<Int?>(n) { null }
            val picks = MutableList<String?>(n) { null }
            var img: String? = null
            val mb = MetaAccumulator()
            var found = 0
            for (i in stores.indices) {
                val sd = stores[i]
                var best: Item? = null
                var bestOld: Item? = null
                for (it in sd.items) {
                    if (!it.weighted || it.price < fr.min || it.price > fr.max ||
                        !fr.inc.containsMatchIn(it.name) ||
                        (fr.exc != null && fr.exc.containsMatchIn(it.name))
                    ) continue
                    if (sigs[i].hasSold && daysBetween(sigs[i].ref, parseStamp(it.sold)) >= 30) {
                        if (bestOld == null || it.price < bestOld.price) bestOld = it
                        continue
                    }
                    if (best == null || it.price < best.price) best = it
                }
                val chosen = best ?: bestOld
                if (chosen != null) {
                    prices[i] = (chosen.price * 100.0).roundToInt()
                    picks[i] = chosen.name
                    mb.dates(i, chosen, sigs[i])
                    found++
                    if (img == null && chainKeys.getOrNull(i) == "RamiLevi" && chosen.code.length >= 7) {
                        img = chosen.code
                    }
                }
            }
            if (found >= 2) {
                products.add(CatalogProductRow(fr.id, fr.label, fr.category, 1, prices, picks, img, mb.build(fr.category)))
            }
        }

        products.sortWith { a, b ->
            val wa = a.picks != null
            val wb = b.picks != null
            if (wa != wb) return@sortWith if (wa) -1 else 1
            val ca = a.prices.count { it != null }
            val cb = b.prices.count { it != null }
            if (ca != cb) return@sortWith cb.compareTo(ca)
            a.name.compareTo(b.name)
        }
        return products
    }

    fun usedCategories(products: List<CatalogProductRow>): List<Category> {
        val used = products.map { it.cat }.toHashSet()
        return CategoryNames.filter { used.contains(it.id) }
    }

    fun buildNotes(stores: List<StoreData>, products: List<CatalogProductRow>): List<List<PromoNote>> {
        val sold = HashMap<String, List<Int?>>(products.size)
        for (p in products) {
            if (p.weighted == 0) sold[p.code] = p.prices
        }
        return stores.mapIndexed { i, sd ->
            val pf = sd.promos ?: return@mapIndexed emptyList()
            val now = sd.store.fileTime
            pf.notes.mapNotNull { n ->
                if (now.isNotEmpty() && ((n.end.isNotEmpty() && n.end < now) || (n.start.isNotEmpty() && n.start > now))) {
                    return@mapNotNull null
                }
                val codes = n.codes.filter { c -> sold[c]?.getOrNull(i) != null }.sorted()
                if (codes.isEmpty()) null else n.copy(codes = codes)
            }
        }
    }

    fun buildPromos(stores: List<StoreData>, products: List<CatalogProductRow>): Pair<List<List<Promo>>, List<PromoStats>> {
        val shelf = HashMap<String, List<Int?>>(products.size)
        for (p in products) {
            if (p.weighted == 0) shelf[p.code] = p.prices
        }
        val out = mutableListOf<List<Promo>>()
        val stats = mutableListOf<PromoStats>()
        for (i in stores.indices) {
            val sd = stores[i]
            val pf = sd.promos
            if (pf == null) {
                out.add(emptyList())
                stats.add(PromoStats())
                continue
            }
            val sk = pf.skipped.toMutableMap()
            val now = sd.store.fileTime
            val seen = HashSet<String>()
            val dropped = HashMap<String, String>()
            val kept = HashSet<String>()
            val storePromos = mutableListOf<Promo>()

            for (pr in pf.promos) {
                seen.add(pr.id)
                if (now.isNotEmpty() && ((pr.end.isNotEmpty() && pr.end < now) || (pr.start.isNotEmpty() && pr.start > now))) {
                    dropped[pr.id] = "expired"
                    continue
                }
                val codes = mutableListOf<String>()
                val pairs = HashMap<Double, MutableList<String>>()
                for (c in pr.codes) {
                    val priceAg = shelf[c]?.getOrNull(i) ?: continue
                    val p = priceAg.toDouble()
                    if (pr.second) {
                        if (pr.value >= p) {
                            dropped.putIfAbsent(pr.id, "implausible")
                            continue
                        }
                        pairs.getOrPut(p + pr.value) { mutableListOf() }.add(c)
                        continue
                    }
                    val eff = priceAfter(pr, p, pr.minQty) / pr.minQty.toDouble()
                    if (eff >= p || eff < 0.25 * p) {
                        dropped.putIfAbsent(pr.id, "implausible")
                        continue
                    }
                    codes.add(c)
                }
                if (pr.second) {
                    for (total in pairs.keys.sorted()) {
                        storePromos.add(pr.copy(second = false, value = total, codes = pairs[total]!!.sorted()))
                        kept.add(pr.id)
                    }
                    if (pairs.isEmpty()) dropped.putIfAbsent(pr.id, "not_in_catalog")
                    continue
                }
                if (codes.isEmpty()) {
                    dropped.putIfAbsent(pr.id, "not_in_catalog")
                    continue
                }
                storePromos.add(pr.copy(codes = codes.sorted()))
                kept.add(pr.id)
            }
            for ((id, why) in dropped) {
                if (!kept.contains(id)) sk[why] = (sk[why] ?: 0) + 1
            }
            out.add(storePromos)
            stats.add(PromoStats(pf.total, seen.size, kept.size, sk))
        }
        return out to stats
    }

    private fun priceAfter(pr: Promo, p: Double, q: Int): Double {
        val units = if (pr.max in 1 until q) pr.max else q
        val rest = (q - units) * p
        return when (pr.kind) {
            "price" -> rest + units * pr.value
            "percent" -> rest + units * p * (1.0 - pr.value / 100.0)
            "bundle" -> {
                val n = units / maxOf(1, pr.minQty)
                rest + n * pr.value + (units - n * pr.minQty) * p
            }
            else -> p * q
        }
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}

object CatalogSerializer {
    fun save(file: File, cat: Catalog) {
        val root = JSONObject().apply {
            put("version", cat.version)
            put("priceDate", cat.priceDate)
            put("generatedAt", cat.generatedAt)
            put("skipped", JSONArray(cat.skipped))
            put("areas", JSONArray().apply {
                cat.areas.forEach { a ->
                    put(JSONObject().put("name", a.name).put("lat", a.lat).put("lon", a.lon))
                }
            })
            put("categories", JSONArray().apply {
                cat.categories.forEach { c ->
                    put(JSONObject().put("id", c.id).put("name", c.name))
                }
            })
            put("stores", JSONArray().apply {
                cat.stores.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("chain", s.chain)
                        put("branch", s.branch)
                        put("address", s.address)
                        if (s.lat != null) put("lat", s.lat)
                        if (s.lon != null) put("lon", s.lon)
                        if (s.approx) put("approx", true)
                        put("file", s.file)
                        put("fileTime", s.fileTime)
                        if (s.promoFile.isNotEmpty()) put("promoFile", s.promoFile)
                        if (s.hasSold) put("hasSold", true)
                        if (s.hasChg) put("hasChg", true)
                    })
                }
            })
            put("products", JSONArray().apply {
                cat.products.forEach { p ->
                    put(JSONArray().apply {
                        put(p.code)
                        put(p.name)
                        put(p.cat)
                        put(p.weighted)
                        put(JSONArray().apply { p.prices.forEach { put(it ?: JSONObject.NULL) } })
                        if (p.picks != null) {
                            put(JSONArray().apply { p.picks.forEach { put(it ?: JSONObject.NULL) } })
                        } else {
                            put(JSONObject.NULL)
                        }
                        put(p.img ?: JSONObject.NULL)
                        val m = p.meta
                        if (m != null && !m.isEmpty()) {
                            put(JSONObject().apply {
                                if (m.sizeAmount != null && m.sizeUnit != null) {
                                    put("size", JSONArray().put(m.sizeAmount).put(m.sizeUnit).put(m.sizeAgree))
                                }
                                if (m.maker.isNotEmpty()) put("maker", m.maker)
                                if (m.il) put("il", true)
                                if (m.stale.isNotEmpty()) put("stale", JSONArray().apply { m.stale.forEach { put(JSONArray().put(it.first).put(it.second)) } })
                                if (m.chg.isNotEmpty()) put("chg", JSONArray().apply { m.chg.forEach { put(JSONArray().put(it.first).put(it.second)) } })
                                if (m.np.isNotEmpty()) put("np", JSONArray(m.np))
                                if (m.odd.isNotEmpty()) put("odd", JSONArray().apply { m.odd.forEach { put(JSONArray().put(it.first).put(it.second)) } })
                                if (m.raw.isNotEmpty()) put("raw", m.raw)
                            })
                        } else {
                            put(JSONObject.NULL)
                        }
                    })
                }
            })
            put("promos", JSONArray().apply {
                cat.promos.forEach { list ->
                    put(JSONArray().apply {
                        list.forEach { pr ->
                            put(JSONObject().apply {
                                put("id", pr.id)
                                put("codes", JSONArray(pr.codes))
                                put("minQty", pr.minQty)
                                put("kind", pr.kind)
                                put("value", pr.value)
                                if (pr.club) put("club", true)
                                if (pr.desc.isNotEmpty()) put("desc", pr.desc)
                                if (pr.max > 0) put("max", pr.max)
                                if (pr.start.isNotEmpty()) put("start", pr.start)
                                if (pr.end.isNotEmpty()) put("end", pr.end)
                            })
                        }
                    })
                }
            })
            put("promoNotes", JSONArray().apply {
                cat.promoNotes.forEach { list ->
                    put(JSONArray().apply {
                        list.forEach { n ->
                            put(JSONObject().apply {
                                put("id", n.id)
                                put("codes", JSONArray(n.codes))
                                put("desc", n.desc)
                                put("why", n.why)
                                if (n.club) put("club", true)
                                if (n.start.isNotEmpty()) put("start", n.start)
                                if (n.end.isNotEmpty()) put("end", n.end)
                            })
                        }
                    })
                }
            })
            put("images", JSONArray(cat.images))
        }
        val tmp = File(file.parentFile, "${file.name}.part")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun load(file: File): Catalog {
        val root = JSONObject(file.readText())
        val areas = mutableListOf<Area>()
        root.optJSONArray("areas")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                areas.add(Area(o.optString("name"), o.optDouble("lat"), o.optDouble("lon")))
            }
        }
        val categories = mutableListOf<Category>()
        root.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                categories.add(Category(o.optString("id"), o.optString("name")))
            }
        }
        val stores = mutableListOf<Store>()
        root.optJSONArray("stores")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                stores.add(
                    Store(
                        id = o.optString("id"),
                        chain = o.optString("chain"),
                        branch = o.optString("branch"),
                        address = o.optString("address"),
                        lat = if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null,
                        lon = if (o.has("lon") && !o.isNull("lon")) o.optDouble("lon") else null,
                        approx = o.optBoolean("approx", false),
                        file = o.optString("file"),
                        fileTime = o.optString("fileTime"),
                        promoFile = o.optString("promoFile"),
                        hasSold = o.optBoolean("hasSold", false),
                        hasChg = o.optBoolean("hasChg", false)
                    )
                )
            }
        }
        val products = mutableListOf<CatalogProductRow>()
        root.optJSONArray("products")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONArray(i)
                val pArr = r.getJSONArray(4)
                val prices = (0 until pArr.length()).map { if (pArr.isNull(it)) null else pArr.getInt(it) }
                val picks = if (r.length() > 5 && !r.isNull(5)) {
                    val pkArr = r.getJSONArray(5)
                    (0 until pkArr.length()).map { if (pkArr.isNull(it)) null else pkArr.getString(it) }
                } else null
                val img = if (r.length() > 6 && !r.isNull(6)) r.getString(6) else null
                val meta = if (r.length() > 7 && !r.isNull(7)) {
                    val m = r.getJSONObject(7)
                    val sArr = m.optJSONArray("size")
                    ProductMeta(
                        sizeAmount = if (sArr != null && sArr.length() >= 3) sArr.optDouble(0) else null,
                        sizeUnit = if (sArr != null && sArr.length() >= 3) sArr.optString(1) else null,
                        sizeAgree = if (sArr != null && sArr.length() >= 3) sArr.optInt(2) else 0,
                        maker = m.optString("maker"),
                        il = m.optBoolean("il", false),
                        stale = parsePairs(m.optJSONArray("stale")),
                        chg = parsePairs(m.optJSONArray("chg")),
                        np = m.optJSONArray("np")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                        odd = parsePairs(m.optJSONArray("odd")),
                        raw = m.optString("raw")
                    )
                } else null
                products.add(CatalogProductRow(r.getString(0), r.getString(1), r.getString(2), r.getInt(3), prices, picks, img, meta))
            }
        }
        val promos = mutableListOf<List<Promo>>()
        root.optJSONArray("promos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sub = arr.getJSONArray(i)
                val list = mutableListOf<Promo>()
                for (j in 0 until sub.length()) {
                    val o = sub.getJSONObject(j)
                    list.add(
                        Promo(
                            id = o.optString("id"),
                            codes = o.optJSONArray("codes")?.toStringList() ?: emptyList(),
                            minQty = o.optInt("minQty", 1),
                            kind = o.optString("kind"),
                            value = o.optDouble("value"),
                            club = o.optBoolean("club", false),
                            desc = o.optString("desc"),
                            max = o.optInt("max", 0),
                            start = o.optString("start"),
                            end = o.optString("end")
                        )
                    )
                }
                promos.add(list)
            }
        }
        val notes = mutableListOf<List<PromoNote>>()
        root.optJSONArray("promoNotes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sub = arr.getJSONArray(i)
                val list = mutableListOf<PromoNote>()
                for (j in 0 until sub.length()) {
                    val o = sub.getJSONObject(j)
                    list.add(
                        PromoNote(
                            id = o.optString("id"),
                            codes = o.optJSONArray("codes")?.toStringList() ?: emptyList(),
                            desc = o.optString("desc"),
                            why = o.optString("why"),
                            club = o.optBoolean("club", false),
                            start = o.optString("start"),
                            end = o.optString("end")
                        )
                    )
                }
                notes.add(list)
            }
        }
        return Catalog(
            version = root.optInt("version", 2),
            priceDate = root.optString("priceDate"),
            generatedAt = root.optString("generatedAt"),
            skipped = root.optJSONArray("skipped")?.toStringList() ?: emptyList(),
            areas = areas,
            categories = categories,
            stores = stores,
            products = products,
            promos = promos,
            promoStats = emptyList(),
            promoNotes = notes,
            images = root.optJSONArray("images")?.toStringList() ?: listOf(RAMI_LEVY_IMAGE)
        )
    }

    private fun parsePairs(arr: JSONArray?): List<Pair<Int, Int>> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONArray(i)
            if (p != null && p.length() >= 2) p.getInt(0) to p.getInt(1) else null
        }
    }
}
