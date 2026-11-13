package il.salzol.core

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

data class ParsedReceiptLine(
    val id: String,
    val rawText: String,
    val code: String = "",
    val productName: String = "",
    val paidAgorot: Int,
    val qty: Double = 1.0,
    val cheapestOfficialAgorot: Int = -1,
    val cheapestWhere: String = "",
    val deltaAgorot: Int = 0
)

data class ParsedReceipt(
    val storeHint: String = "",
    val receiptDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val detectedTotalAgorot: Int = 0,
    val lines: List<ParsedReceiptLine> = emptyList(),
    val rawOcrText: String = ""
)

object ReceiptScanner {
    private val barcodeRx = Regex("""\b(\d{7,13})\b""")
    private val priceRx = Regex("""\b(\d{1,4})[.,](\d{2})\b""")
    private val dateRx = Regex("""\b(\d{2})[./-](\d{2})[./-](\d{2,4})\b""")

    suspend fun scanFromUri(
        context: Context,
        uri: Uri,
        ix: ProductIndex?,
        activeStores: List<Int>,
        club: Map<String, Boolean>
    ): ParsedReceipt {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromFilePath(context, uri)
        val result: Text = recognizer.process(image).await()
        recognizer.close()
        return parseText(result, ix, activeStores, club)
    }

    fun parseText(
        text: Text,
        ix: ProductIndex?,
        activeStores: List<Int>,
        club: Map<String, Boolean>
    ): ParsedReceipt {
        val allLines = text.textBlocks.flatMap { it.lines }
            .sortedBy { it.boundingBox?.top ?: 0 }
        val linesOut = mutableListOf<ParsedReceiptLine>()
        var detectedTotal = 0
        var receiptDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val now = java.time.Instant.now()

        // Group horizontally close lines (~24px tolerance) so barcode on left & price on right pair cleanly
        val mergedRows = mutableListOf<String>()
        val usedIdx = HashSet<Int>()
        for (i in allLines.indices) {
            if (i in usedIdx) continue
            val y = allLines[i].boundingBox?.centerY() ?: -1000
            val parts = mutableListOf(allLines[i])
            usedIdx.add(i)
            for (j in i + 1 until allLines.size) {
                if (j in usedIdx) continue
                val y2 = allLines[j].boundingBox?.centerY() ?: 100000
                if (abs(y2 - y) <= 24) {
                    parts.add(allLines[j])
                    usedIdx.add(j)
                }
            }
            parts.sortBy { it.boundingBox?.left ?: 0 }
            mergedRows.add(parts.joinToString("   ") { it.text })
        }

        val fullText = mergedRows.joinToString("\n")
        dateRx.find(fullText)?.let { dm ->
            val d = dm.groupValues[1].toIntOrNull()
            val m = dm.groupValues[2].toIntOrNull()
            var yr = dm.groupValues[3].toIntOrNull()
            if (d != null && m != null && yr != null && d in 1..31 && m in 1..12) {
                if (yr < 100) yr += 2000
                runCatching {
                    receiptDate = LocalDate.of(yr, m, d).format(DateTimeFormatter.ISO_LOCAL_DATE)
                }
            }
        }

        val storeHint = detectStoreHint(fullText)

        for ((idx, row) in mergedRows.withIndex()) {
            val lower = row.lowercase()
            val prices = priceRx.findAll(row).mapNotNull { m ->
                val whole = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val frac = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                whole * 100 + frac
            }.filter { it in 50..500000 }.toList()

            if (prices.isEmpty()) continue
            val linePrice = prices.last()

            if (lower.contains("total") || lower.contains("sum") || lower.contains("nis") ||
                lower.contains("ils")
            ) {
                detectedTotal = maxOf(detectedTotal, linePrice)
                continue
            }

            // Look for barcode on this row
            val barcodes = barcodeRx.findAll(row).map { it.groupValues[1] }.toList()
            var matchedProduct: ShopProduct? = null
            if (ix != null) {
                for (rawCode in barcodes) {
                    val trimmed = rawCode.trimStart('0')
                    matchedProduct = ix.byCode[trimmed] ?: ix.byCode[rawCode]
                    if (matchedProduct != null) break
                }
            }

            if (matchedProduct != null || barcodes.isNotEmpty()) {
                val p = matchedProduct
                val audited = auditSingleLine(
                    id = "r_${idx}_${linesOut.size}",
                    rawText = row,
                    product = p,
                    codeFallback = barcodes.firstOrNull().orEmpty(),
                    paidAgorot = linePrice,
                    qty = 1.0,
                    ix = ix,
                    activeStores = activeStores,
                    club = club,
                    now = now
                )
                linesOut.add(audited)
            }
        }

        // If no explicit TOTAL line matched, estimate from max line price or sum
        if (detectedTotal == 0) {
            val sumLines = linesOut.sumOf { (it.paidAgorot * it.qty).roundToInt() }
            detectedTotal = sumLines
        }

        return ParsedReceipt(
            storeHint = storeHint,
            receiptDate = receiptDate,
            detectedTotalAgorot = detectedTotal,
            lines = linesOut,
            rawOcrText = fullText
        )
    }

    fun auditSingleLine(
        id: String,
        rawText: String,
        product: ShopProduct?,
        codeFallback: String,
        paidAgorot: Int,
        qty: Double,
        ix: ProductIndex?,
        activeStores: List<Int>,
        club: Map<String, Boolean>,
        now: java.time.Instant = java.time.Instant.now()
    ): ParsedReceiptLine {
        val code = product?.code ?: codeFallback
        val name = product?.name ?: (if (rawText.isNotBlank()) rawText.take(40) else "פריט ($codeFallback)")
        var cheapest = -1
        var cheapestWhere = ""
        if (product != null && ix != null && activeStores.isNotEmpty()) {
            for (sIdx in activeStores) {
                val priced = ix.priceAt(product, sIdx, qty, club, now) ?: continue
                val totalAg = priced.cost.roundToInt()
                if (totalAg > 0 && (cheapest < 0 || totalAg < cheapest)) {
                    cheapest = totalAg
                    cheapestWhere = ix.storeName(sIdx)
                }
            }
        }
        val paidTotal = (paidAgorot * qty).roundToInt()
        val delta = if (cheapest > 0) paidTotal - cheapest else 0
        return ParsedReceiptLine(
            id = id,
            rawText = rawText,
            code = code,
            productName = name,
            paidAgorot = paidAgorot,
            qty = qty,
            cheapestOfficialAgorot = cheapest,
            cheapestWhere = cheapestWhere,
            deltaAgorot = delta
        )
    }

    private fun detectStoreHint(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("shufersal") -> "שופרסל"
            t.contains("rami") -> "רמי לוי"
            t.contains("carrefour") -> "קרפור"
            t.contains("victory") -> "ויקטורי"
            t.contains("yohananof") -> "יוחננוף"
            t.contains("osher") -> "אושר עד"
            t.contains("super-pharm") || t.contains("superpharm") -> "סופר-פארם"
            t.contains("tiv taam") -> "טיב טעם"
            else -> ""
        }
    }
}
