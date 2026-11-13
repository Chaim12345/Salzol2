package il.salzol.go

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import il.salzol.go.ui.Hint
import il.salzol.go.ui.P

sealed interface ParsedSalzolLink {
    data class CartImport(val name: String, val items: Map<String, Double>) : ParsedSalzolLink
    data class FamilyJoin(val listId: String, val inviteCode: String, val name: String) : ParsedSalzolLink
    data class ShortcutRoute(val route: String) : ParsedSalzolLink
}

object SalzolDeepLinks {
    fun encodeCartDeepLink(listName: String, items: Map<String, Double>): String {
        val pairs = items.entries
            .filter { it.value > 0 }
            .take(80)
            .joinToString(",") { (c, q) -> "${Uri.encode(c)}:$q" }
        return "salzol://list?n=${Uri.encode(listName)}&items=$pairs"
    }

    fun encodeFamilyDeepLink(listId: String, inviteCode: String, listName: String): String =
        "salzol://family?id=${Uri.encode(listId)}&code=${Uri.encode(inviteCode)}&n=${Uri.encode(listName)}"

    fun parse(uri: Uri?): ParsedSalzolLink? {
        if (uri == null || uri.scheme != "salzol") return null
        return when (uri.host?.lowercase()) {
            "list" -> {
                val name = uri.getQueryParameter("n")?.ifBlank { "רשימה משותפת" } ?: "רשימה משותפת"
                val rawItems = uri.getQueryParameter("items").orEmpty()
                val items = LinkedHashMap<String, Double>()
                rawItems.split(',').forEach { entry ->
                    val idx = entry.lastIndexOf(':')
                    if (idx > 0) {
                        val code = Uri.decode(entry.substring(0, idx))
                        val qty = entry.substring(idx + 1).toDoubleOrNull() ?: 1.0
                        if (code.isNotBlank() && qty > 0) items[code] = qty
                    }
                }
                if (items.isEmpty()) null else ParsedSalzolLink.CartImport(name, items)
            }
            "family" -> {
                val id = uri.getQueryParameter("id").orEmpty()
                val code = uri.getQueryParameter("code").orEmpty()
                val name = uri.getQueryParameter("n").orEmpty()
                if (id.isNotBlank() || code.isNotBlank()) ParsedSalzolLink.FamilyJoin(id, code, name)
                else ParsedSalzolLink.ShortcutRoute("family")
            }
            "scan" -> ParsedSalzolLink.ShortcutRoute("scan")
            "shop" -> ParsedSalzolLink.ShortcutRoute("shop?store=")
            "receipt" -> ParsedSalzolLink.ShortcutRoute("receipt")
            "cart" -> ParsedSalzolLink.ShortcutRoute("cart")
            else -> null
        }
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}

@Composable
fun QrShareDialog(
    title: String,
    subtitle: String,
    deepLink: String,
    shareMessage: String,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val bmp = remember(deepLink) { SalzolDeepLinks.generateQrBitmap(deepLink, 512) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint(subtitle)
                if (bmp != null) {
                    Box(
                        Modifier
                            .size(210.dp)
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "קוד QR לשיתוף רשימה",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Text("ניתן לסרוק ישירות מתוך סורק האפליקציה או לשלוח קישור.", fontSize = 12.sp, color = P.ink2)
            }
        },
        confirmButton = {
            Button(
                onClick = { shareText(ctx, shareMessage, title) },
                modifier = Modifier.testTag("qr_share_link_button")
            ) {
                Text("שליחת קישור")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("סגירה") }
        }
    )
}
