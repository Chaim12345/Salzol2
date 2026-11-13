package il.salzol.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

object XmlParsers {
    private fun openXmlStream(raw: InputStream): InputStreamReader {
        var bis = BufferedInputStream(raw, 65536)
        bis.mark(4)
        val magic = ByteArray(2)
        val n = bis.read(magic)
        bis.reset()
        if (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
            bis = BufferedInputStream(GZIPInputStream(bis), 65536)
        } else if (n == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
            val zis = ZipInputStream(bis)
            val entry = zis.nextEntry
            if (entry != null) {
                bis = BufferedInputStream(zis, 65536)
            }
        }
        bis.mark(4)
        val bom = ByteArray(3)
        val bn = bis.read(bom)
        if (bn >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()) {
            return InputStreamReader(bis, Charsets.UTF_8)
        }
        if (bn >= 2 && bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte()) {
            bis.reset()
            bis.skip(2)
            return InputStreamReader(bis, Charsets.UTF_16LE)
        }
        if (bn >= 2 && bom[0] == 0xFE.toByte() && bom[1] == 0xFF.toByte()) {
            bis.reset()
            bis.skip(2)
            return InputStreamReader(bis, Charsets.UTF_16BE)
        }
        bis.reset()
        return InputStreamReader(bis, Charsets.UTF_8)
    }

    private fun newParser(reader: InputStreamReader): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        parser.setInput(reader)
        return parser
    }

    private fun eachRecord(raw: InputStream, recordTag: String, onRecord: (Map<String, String>) -> Unit) {
        openXmlStream(raw).use { reader ->
            val parser = newParser(reader)
            var event = parser.eventType
            var depth = 0
            var recDepth = -1
            var rec: MutableMap<String, String>? = null
            var field: String? = null
            val text = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val name = parser.name?.lowercase() ?: ""
                        if (recDepth < 0 && name == recordTag) {
                            recDepth = depth
                            rec = HashMap(16)
                        } else if (recDepth >= 0 && depth == recDepth + 1) {
                            field = name
                            text.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (field != null) {
                            text.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        if (recDepth >= 0 && depth == recDepth + 1 && field == name) {
                            rec?.put(field!!, text.toString().trim())
                            field = null
                        } else if (depth == recDepth && name == recordTag) {
                            rec?.let(onRecord)
                            recDepth = -1
                            rec = null
                            field = null
                        }
                        depth--
                    }
                }
                event = parser.next()
            }
        }
    }

    fun cleanName(n: String): String {
        return n.replace("*מבצע*", "")
            .replace("מבצע*", "")
            .replace("*", " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-')
    }

    fun parsePriceFile(raw: InputStream): List<Item> {
        val items = ArrayList<Item>(2048)
        eachRecord(raw, "item") { m ->
            val name = cleanName(m["itemname"] ?: m["itemnm"] ?: "")
            if (name.isEmpty()) return@eachRecord
            val code = (m["itemcode"] ?: "").trim()
            val price = (m["itemprice"] ?: "").toDoubleOrNull()
            val noPrice = price == null || price <= 0.0
            val qty = m["quantity"] ?: ""
            val unit = m["unitofmeasure"] ?: ""
            val weighted = (m["bisweighted"] ?: "") == "1"
            val maker = m["manufacturername"] ?: ""
            val unitQty = m["unitqty"] ?: ""
            val country = m["manufacturecountry"] ?: ""
            val updated = m["priceupdatedate"] ?: m["priceupdatetime"] ?: ""
            val sold = m["lastsaledatetime"] ?: ""
            items.add(
                Item(
                    code = code,
                    name = name,
                    price = if (noPrice) 0.0 else price!!,
                    qty = qty,
                    unit = unit,
                    weighted = weighted,
                    maker = maker,
                    unitQty = unitQty,
                    country = country,
                    updated = updated,
                    sold = sold,
                    noPrice = noPrice
                )
            )
        }
        return items
    }

    fun parseStoresFile(raw: InputStream, chain: String): List<StoreRow> {
        val bytes = raw.readBytes()
        val lowerSample = String(bytes, 0, minOf(bytes.size, 8192), Charset.forName("ISO-8859-1")).lowercase()
        val tag = if (lowerSample.contains("<subchainstorexmlobject>")) "subchainstorexmlobject" else "store"
        val out = mutableListOf<StoreRow>()
        eachRecord(ByteArrayInputStream(bytes), tag) { m ->
            val rawId = (m["storeid"] ?: "").trimStart('0')
            val id = rawId.ifEmpty { "0" }
            var name = (m["storename"] ?: "").trim()
            var address = m["address"] ?: ""
            var city = m["city"] ?: ""
            if (unknownField(address) && unknownField(city)) {
                val parsed = addressFromName(name)
                name = parsed.first
                address = parsed.second
                city = parsed.third
            }
            out.add(
                StoreRow(
                    chain = chain,
                    storeId = id,
                    name = name,
                    address = address,
                    city = city,
                    subChain = m["subchainname"] ?: ""
                )
            )
        }
        return out
    }

    private fun unknownField(s: String): Boolean {
        val t = s.trim().lowercase()
        return t.isEmpty() || t == "unknown" || t == "לא ידוע"
    }

    private val nameAddrRx = Regex("""^(.*?)\s*,\s*([^,]*?\d+[א-ת]?)\s+([^,\d]+)$""")
    private fun addressFromName(name: String): Triple<String, String, String> {
        val m = nameAddrRx.find(name.trim()) ?: return Triple(name, "", "")
        return Triple(m.groupValues[1].trim(), m.groupValues[2].trim(), m.groupValues[3].trim())
    }

    // ---------------- PromoFull XML Parser ----------------

    private class XmlNode(val name: String) {
        val text = StringBuilder()
        val kids = mutableListOf<XmlNode>()
        fun child(vararg names: String): XmlNode? = kids.firstOrNull { k -> names.any { it == k.name } }
        fun children(vararg names: String): List<XmlNode> = kids.filter { k -> names.any { it == k.name } }
        fun valOf(vararg names: String): String = child(*names)?.text?.toString()?.trim() ?: ""
        fun numOf(vararg names: String): Double = valOf(*names).toDoubleOrNull() ?: 0.0
    }

    fun parsePromoFile(raw: InputStream): PromoFile {
        val promos = mutableListOf<Promo>()
        val notes = mutableListOf<PromoNote>()
        val skipped = mutableMapOf<String, Int>()
        var total = 0

        openXmlStream(raw).use { reader ->
            val parser = newParser(reader)
            var event = parser.eventType
            val stack = ArrayList<XmlNode>()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        if (name == "promotion" || stack.isNotEmpty()) {
                            val node = XmlNode(name)
                            if (stack.isNotEmpty()) stack.last().kids.add(node)
                            stack.add(node)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (stack.isNotEmpty()) {
                            stack.last().text.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (stack.isNotEmpty()) {
                            val done = stack.removeAt(stack.size - 1)
                            if (stack.isEmpty() && done.name == "promotion") {
                                total++
                                val (ps, note, skipReason) = modelPromotion(done)
                                if (ps.isNotEmpty()) {
                                    promos.addAll(ps)
                                } else {
                                    if (note != null) notes.add(note)
                                    val why = skipReason.ifEmpty { "unsupported" }
                                    skipped[why] = (skipped[why] ?: 0) + 1
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
        return PromoFile(total = total, promos = promos, notes = notes, skipped = skipped)
    }

    private data class PromoTerms(
        val kind: String,
        val minQty: Int,
        val value: Double,
        val max: Int,
        val second: Boolean
    )

    private val limitRx = Regex("""(?:^|\s)(?:מוגבל\s*ל-?\s*|מוג\s?|מו\s?)(\d{1,2})(?:\s|$)""")
    private val secondItemRx = Regex("""(?:^|[\s,/-])ה?שני(?:ה|יה)?\s*(?:ב|ל|בחצי)""")

    private fun modelPromotion(n: XmlNode): Triple<List<Promo>, PromoNote?, String> {
        val id = n.valOf("promotionid")
        val desc = cleanPromoDesc(n.valOf("promotiondescription"))
        val start = promoTimestamp(
            n.valOf("promotionstartdatetime", "promotionstartdate"),
            n.valOf("promotionstarthour")
        )
        val end = promoTimestamp(
            n.valOf("promotionenddatetime", "promotionenddate"),
            n.valOf("promotionendhour")
        )
        val clubs = collectClubIds(n)
        val club = !isEveryone(clubs)

        val itemNodes = collectPromoItems(n)
        val codes = itemNodes.mapNotNull { it.valOf("itemcode").takeIf { c -> c.isNotEmpty() } }.distinct()

        // Drop coupon, basket_min, multi_group -> return note if codes exist
        if (n.numOf("minpurchaseamnt") > 0 || n.numOf("rewardtype") == 6.0) {
            val note = if (codes.isNotEmpty() && desc.isNotEmpty()) {
                PromoNote(id.ifEmpty { "x$desc" }, codes, desc, "basket_min", club, start, end)
            } else null
            return Triple(emptyList(), note, "basket_min")
        }

        val groups = n.child("groups")?.children("group") ?: emptyList()
        if (groups.size > 1) {
            val note = if (codes.isNotEmpty() && desc.isNotEmpty()) {
                PromoNote(id.ifEmpty { "x$desc" }, codes, desc, "multi_group", club, start, end)
            } else null
            return Triple(emptyList(), note, "multi_group")
        }

        if (n.valOf("isgift") == "1" || n.valOf("allowmultiplegifts") == "1" || n.numOf("rewardtype") == 2.0) {
            val note = if (codes.isNotEmpty() && desc.isNotEmpty()) {
                PromoNote(id.ifEmpty { "x$desc" }, codes, desc, "gift", club, start, end)
            } else null
            return Triple(emptyList(), note, "gift")
        }

        val byTerms = LinkedHashMap<PromoTerms, MutableList<String>>()
        var why = "unsupported"

        val globalWeighted = n.valOf("isweightedpromo") == "1"
        for (it in itemNodes) {
            val code = it.valOf("itemcode")
            if (code.isEmpty()) continue
            if (globalWeighted || it.valOf("bisweighted") == "1") {
                why = "weighted"
                continue
            }
            fun num(vararg names: String): Double {
                val v = it.numOf(*names)
                return if (v != 0.0) v else n.numOf(*names)
            }

            var minQ = num("minqty")
            val dp = num("discountedprice")
            var dr = num("discountrate")
            if (dr > 100) dr /= 100.0
            if (minQ < 1.0) minQ = 1.0
            if (minQ != kotlin.math.truncate(minQ) || minQ > 20.0) {
                why = "unsupported"
                continue
            }

            val kind: String
            val value: Double
            var second = false
            when {
                dp > 0.0 && minQ == 1.0 -> {
                    kind = "price"
                    value = dp
                }
                dp > 0.0 -> {
                    kind = "bundle"
                    value = dp
                    if (minQ.toInt() == 2 && secondItemRx.containsMatchIn(desc)) {
                        second = true
                    }
                }
                dr > 0.0 && dr < 100.0 && minQ == 1.0 -> {
                    kind = "percent"
                    value = dr
                }
                dr >= 100.0 || minQ > 1.0 -> {
                    why = "gift"
                    continue
                }
                else -> {
                    why = "unsupported"
                    continue
                }
            }

            var max = num("maxqty").toInt()
            if (max == 0) {
                val m = limitRx.find(desc)
                if (m != null) {
                    max = m.groupValues[1].toIntOrNull() ?: 0
                    if (kind == "bundle" && max > 0 && max < minQ.toInt()) {
                        max *= minQ.toInt()
                    }
                }
            }
            if (max == 0) {
                val rl = n.numOf("redemptionlimit")
                if (rl >= 1.0 && rl <= 50.0 && rl == kotlin.math.truncate(rl)) {
                    max = rl.toInt() * minQ.toInt()
                }
            }

            val t = PromoTerms(kind, minQ.toInt(), value, max, second)
            byTerms.getOrPut(t) { mutableListOf() }.add(code)
        }

        if (byTerms.isEmpty()) {
            return Triple(emptyList(), null, why)
        }

        val out = byTerms.map { (t, cList) ->
            val v = if (t.kind == "percent") t.value else (t.value * 100.0).roundToInt().toDouble()
            Promo(
                id = id,
                codes = cList,
                minQty = t.minQty,
                kind = t.kind,
                value = v,
                club = club,
                desc = desc,
                max = t.max,
                start = start,
                end = end,
                second = t.second
            )
        }
        return Triple(out, null, "")
    }

    private fun collectPromoItems(n: XmlNode): List<XmlNode> {
        val out = mutableListOf<XmlNode>()
        n.child("promotionitems")?.children("item")?.let { out.addAll(it) }
        n.child("groups")?.children("group")?.forEach { g ->
            g.child("promotionitems")?.children("item")?.let { out.addAll(it) }
        }
        return out
    }

    private fun collectClubIds(n: XmlNode): List<String> {
        val out = mutableListOf<String>()
        n.child("clubs")?.children("clubid")?.forEach { c ->
            val v = c.text.toString().trim()
            if (v.isNotEmpty()) out.add(v)
        }
        val single = n.valOf("clubid")
        if (single.isNotEmpty()) out.add(single)
        return out
    }

    private fun isEveryone(clubs: List<String>): Boolean {
        if (clubs.isEmpty()) return true
        return clubs.any { it == "0" || it.equals("all", ignoreCase = true) }
    }

    private fun cleanPromoDesc(s: String): String =
        s.trim().replace(Regex("""\s+"""), " ")

    private fun promoTimestamp(dateStr: String, hourStr: String): String {
        val d = dateStr.filter { it.isDigit() }
        if (d.length >= 12) return d.substring(0, 12)
        if (d.length < 8) return ""
        val h = hourStr.filter { it.isDigit() }.padEnd(4, '0').substring(0, 4)
        return d.substring(0, 8) + h
    }
}
