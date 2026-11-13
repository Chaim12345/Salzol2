package il.salzol.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

data class Size(val kind: UnitKind, val amount: Double, val packs: Int = 1) {
    fun text(): String {
        val fmt = { x: Double ->
            val r = (x * 100.0).roundToInt() / 100.0
            if (r == floor(r)) r.toLong().toString() else r.toString()
        }
        return when (kind) {
            UnitKind.Weight -> if (amount >= 1000.0) "${fmt(amount / 1000.0)} ק״ג" else "${fmt(amount)} גרם"
            UnitKind.Volume -> if (amount >= 1000.0) "${fmt(amount / 1000.0)} ליטר" else "${fmt(amount)} מ״ל"
            UnitKind.Count -> "${fmt(amount)} יחידות"
        }
    }
}

data class UnitPrice(val kind: UnitKind, val agorot: Double) {
    fun text(): String {
        val label = when (kind) {
            UnitKind.Weight -> "ל־100 גרם"
            UnitKind.Volume -> "לליטר"
            UnitKind.Count -> "ליחידה"
        }
        return "${ShopUnits.money(agorot)} ₪ $label"
    }
}

object ShopUnits {
    private val baseMap = mapOf(UnitKind.Weight to 100.0, UnitKind.Volume to 1000.0, UnitKind.Count to 1.0)

    private data class UnitWord(val w: String, val kind: UnitKind, val factor: Double, val strong: Boolean)

    private val units = listOf(
        UnitWord("קילוגרם", UnitKind.Weight, 1000.0, true),
        UnitWord("גרמים", UnitKind.Weight, 1.0, true),
        UnitWord("גרם", UnitKind.Weight, 1.0, true),
        UnitWord("מיליליטר", UnitKind.Volume, 1.0, true),
        UnitWord("ליטרים", UnitKind.Volume, 1000.0, true),
        UnitWord("ליטר", UnitKind.Volume, 1000.0, true),
        UnitWord("קילו", UnitKind.Weight, 1000.0, false),
        UnitWord("קג", UnitKind.Weight, 1000.0, false),
        UnitWord("kg", UnitKind.Weight, 1000.0, false),
        UnitWord("גר", UnitKind.Weight, 1.0, false),
        UnitWord("gr", UnitKind.Weight, 1.0, false),
        UnitWord("g", UnitKind.Weight, 1.0, false),
        UnitWord("ג", UnitKind.Weight, 1.0, false),
        UnitWord("ליט", UnitKind.Volume, 1000.0, false),
        UnitWord("l", UnitKind.Volume, 1000.0, false),
        UnitWord("ל", UnitKind.Volume, 1000.0, false),
        UnitWord("מל", UnitKind.Volume, 1.0, false),
        UnitWord("ml", UnitKind.Volume, 1.0, false)
    ).sortedByDescending { it.w.length }

    private val countWords = listOf(
        "יחידות", "יחידה", "יחי", "יח", "שקיקים", "גלילים", "טיטולים",
        "חיתולים", "קפסולות", "כוסות", "מגבונים", "תחבושות", "כפפות"
    )
    private val metreContext = listOf("גליל", "רול", "ניילון", "נייר", "סרט", "שקית", "מטר", "רצועות")
    private val nutrients = listOf("חלבון", "שומן", "סוכר", "פחמימות", "קלוריות", "מלח", "סיבים", "ויטמין")

    fun money(agorot: Double): String = "%.2f".format(java.util.Locale.US, (agorot.roundToInt()) / 100.0)

    private fun normalize(name: String): String {
        val sb = StringBuilder(name.length)
        for (c in name) {
            when (c) {
                '"', '\'', '`', '״', '׳', '”', '“', '’', '‘' -> continue
                '×', '*' -> sb.append('x')
                else -> sb.append(c.lowercaseChar())
            }
        }
        val chars = sb.toString().toCharArray()
        for (i in 1 until chars.size - 1) {
            if (chars[i] == ',' && chars[i - 1].isDigit() && chars[i + 1].isDigit()) {
                var n = 0
                var j = i + 1
                while (j < chars.size && chars[j].isDigit()) {
                    n++; j++
                }
                if (n <= 2) chars[i] = '.'
            }
        }
        return String(chars)
    }

    private fun readNumber(s: String, i: Int): Pair<Double, Int>? {
        if (i >= s.length || !s[i].isDigit()) return null
        if (i > 0 && (s[i - 1].isDigit() || s[i - 1] == '.')) return null
        var j = i
        while (j < s.length && s[j].isDigit()) j++
        if (j + 1 < s.length && s[j] == '.' && s[j + 1].isDigit()) {
            j++
            while (j < s.length && s[j].isDigit()) j++
        }
        val v = s.substring(i, j).toDoubleOrNull() ?: return null
        return v to j
    }

    private fun skipSpaces(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun unitAt(s: String, i: Int, bareM: Boolean): Triple<UnitKind, Double, Int>? {
        for (u in units) {
            if (!s.startsWith(u.w, i)) continue
            val n = u.w.length
            if (!u.strong && i + n < s.length && s[i + n].isLetter()) continue
            return Triple(u.kind, u.factor, n)
        }
        if (bareM && i < s.length && s[i] == 'מ' && (i + 1 >= s.length || !s[i + 1].isLetter())) {
            return Triple(UnitKind.Volume, 1.0, 1)
        }
        return null
    }

    private fun unitTextAt(s: String, i: Int): Int {
        for (u in units) {
            if (s.startsWith(u.w, i)) return u.w.length
        }
        return 0
    }

    private fun doubtful(s: String, start: Int, end: Int): Boolean {
        val rest = skipSpaces(s, end)
        if (nutrients.any { s.startsWith(it, rest) }) return true
        var j = start
        if (j == 0 || !s[j - 1].isWhitespace()) return false
        while (j > 0 && s[j - 1].isWhitespace()) j--
        var k = j
        while (k > 0 && s[k - 1].isDigit()) k--
        if (j - k !in 1..2 || (k > 0 && (s[k - 1].isDigit() || s[k - 1] == '.' || s[k - 1] == '-'))) return false
        val lead = s.substring(k, j).toIntOrNull() ?: return false
        if (lead !in 2..24) return false
        val before = s.substring(0, k).trimEnd()
        return !before.endsWith("שלב")
    }

    private fun inRange(k: UnitKind, amount: Double): Boolean = when (k) {
        UnitKind.Count -> amount in 2.0..1000.0
        UnitKind.Weight -> amount in 5.0..50000.0
        UnitKind.Volume -> amount in 10.0..30000.0
    }

    fun parseSize(name: String, cat: String): Size? {
        val s = normalize(name)
        val bareM = metreContext.none { s.contains(it) }

        val found = mutableListOf<Size>()
        val consumed = mutableListOf<IntRange>()
        var i = 0
        while (i < s.length) {
            val (a, aEnd) = readNumber(s, i) ?: run { i++; continue }
            var j = skipSpaces(s, aEnd)
            if (j >= s.length || s[j] != 'x') {
                i = aEnd; continue
            }
            j = skipSpaces(s, j + 1)
            val (b, bEnd) = readNumber(s, j) ?: run { i = aEnd; continue }
            val end = skipSpaces(s, bEnd)
            val packs: Int
            val amount: Double
            when {
                a == floor(a) && a in 2.0..24.0 && b >= 20.0 -> { packs = a.toInt(); amount = b }
                b == floor(b) && b in 2.0..24.0 && a >= 20.0 -> { packs = b.toInt(); amount = a }
                else -> return null
            }
            val u = unitAt(s, end, bareM)
            if (u != null) {
                found.add(Size(u.first, amount * u.second * packs, packs))
                consumed.add(i until (end + u.third))
            } else if (cat == "drinks" && amount in 150.0..750.0) {
                found.add(Size(UnitKind.Volume, amount * packs, packs))
                consumed.add(i until end)
            } else {
                return null
            }
            i = end
        }

        if (found.isNotEmpty()) {
            if (found.size > 1) return null
            val chars = s.toCharArray()
            for (r in consumed) {
                for (k in r) if (k < chars.size) chars[k] = ' '
            }
            val rest = String(chars)
            for (idx in rest.indices) {
                val n = readNumber(rest, idx)
                if (n != null && unitTextAt(rest, skipSpaces(rest, n.second)) > 0) return null
            }
            return if (inRange(found[0].kind, found[0].amount)) found[0] else null
        }

        val sizes = mutableListOf<Size>()
        i = 0
        while (i < s.length) {
            val (v, e) = readNumber(s, i) ?: run { i++; continue }
            val us = skipSpaces(s, e)
            val n = unitTextAt(s, us)
            if (n == 0) { i = e; continue }
            val end = us + n
            val u = unitAt(s, us, bareM)
            if (u != null && !doubtful(s, i, end)) {
                var packs = 1
                var j = skipSpaces(s, end)
                if (j < s.length && s[j] == 'x') {
                    j = skipSpaces(s, j + 1)
                    var d = j
                    while (d < s.length && s[d].isDigit() && d - j < 3) d++
                    if (d > j && d - j <= 2 && (d >= s.length || (!s[d].isDigit() && s[d] != '.'))) {
                        packs = s.substring(j, d).toIntOrNull() ?: 1
                    }
                }
                sizes.add(Size(u.first, v * u.second * packs, packs))
            }
            i = end
        }

        if (sizes.isEmpty() && bareM) {
            i = 0
            while (i < s.length) {
                val (v, e) = readNumber(s, i) ?: run { i++; continue }
                val j = skipSpaces(s, e)
                if (j < s.length && s[j] == 'מ' && (j + 1 >= s.length || !s[j + 1].isLetter()) && v >= 30.0 && !doubtful(s, i, j + 1)) {
                    sizes.add(Size(UnitKind.Volume, v, 1))
                }
                i = e
            }
        }
        if (sizes.size > 1) return null

        val counts = mutableListOf<Double>()
        i = 0
        while (i < s.length) {
            val res = readNumber(s, i)
            if (res == null) { i++; continue }
            val (v, e) = res
            if (s.substring(i, e).contains('.') || e - i > 4) { i = e; continue }
            val j = skipSpaces(s, e)
            for (w in countWords) {
                if (s.startsWith(w, j)) {
                    val n = w.length
                    if (j + n >= s.length || !s[j + n].isLetter()) {
                        counts.add(v); break
                    }
                }
            }
            i = e
        }

        if (sizes.size == 1) {
            if (counts.any { it > 1.0 }) return null
            return if (inRange(sizes[0].kind, sizes[0].amount)) sizes[0] else null
        }
        if (counts.size == 1 && inRange(UnitKind.Count, counts[0])) {
            return Size(UnitKind.Count, counts[0], 1)
        }
        return null
    }

    fun sizeOf(p: ShopProduct): Size? {
        if (p.weighted) return null
        if (p.sizeDone) return p.cachedSize
        val name = p.rawName.ifEmpty { p.name }
        val named = parseSize(name, p.cat)
        var z = named
        val st = p.statedSize
        if (st != null && st.amount > 0) {
            var stated = Size(st.kind, st.amount, 1)
            if (named != null && named.kind != UnitKind.Count && stated.kind != UnitKind.Count && named.kind != stated.kind) {
                stated = stated.copy(kind = named.kind)
            }
            z = when {
                named != null && named.packs > 1 -> named
                p.statedAgree >= 2 -> {
                    if (named != null && named.kind == stated.kind && abs(named.amount - stated.amount) <= 0.02 * stated.amount) named
                    else stated
                }
                named != null -> named
                else -> stated
            }
        } else if (named == null && p.ai?.s != null) {
            val sr = p.ai!!.s!!
            val k = when (sr.u) {
                "g" -> UnitKind.Weight
                "ml" -> UnitKind.Volume
                else -> UnitKind.Count
            }
            val packs = maxOf(1, sr.p)
            z = Size(k, sr.a * packs, packs)
        }
        p.cachedSize = z
        p.sizeDone = true
        return z
    }

    fun unitPriceOf(z: Size?, agorot: Double): UnitPrice? {
        if (z == null || agorot <= 0.0 || z.amount <= 0.0) return null
        return UnitPrice(z.kind, agorot / z.amount * (baseMap[z.kind] ?: 1.0))
    }
}
