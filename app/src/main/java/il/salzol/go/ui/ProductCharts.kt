package il.salzol.go.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.salzol.core.ShopUnits
import kotlin.math.roundToInt

data class PricePointUi(
    val day: String,
    val agorot: Int,
    val storeName: String = ""
)

@Composable
fun PriceHistoryChart(
    points: List<PricePointUi>,
    min90Agorot: Int,
    avg90Agorot: Int,
    prePromoHike: Boolean,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return
    var selectedIdx by remember(points) { mutableStateOf(points.lastIndex) }
    val selected = points.getOrNull(selectedIdx) ?: points.last()

    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("היסטוריית מחיר (${points.size} ימים)", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = P.ink)
                if (min90Agorot > 0) {
                    Text(
                        "שפל 90 יום: ${ShopUnits.money(min90Agorot.toDouble())} ₪ · ממוצע: ${ShopUnits.money(avg90Agorot.toDouble())} ₪",
                        fontSize = 12.sp,
                        color = P.ink2
                    )
                }
            }
            if (prePromoHike) {
                Badge("התייקר לפני מבצע", P.bad)
            } else if (min90Agorot > 0 && points.last().agorot <= min90Agorot) {
                Badge("שפל 90 יום!", P.good)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(P.surface2, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected.day, fontSize = 12.sp, color = P.ink2, modifier = Modifier.weight(1f))
            if (selected.storeName.isNotEmpty()) {
                Text(selected.storeName, fontSize = 12.sp, color = P.ink2, modifier = Modifier.padding(end = 8.dp))
            }
            Text(
                "${ShopUnits.money(selected.agorot.toDouble())} ₪",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                color = P.brand
            )
        }

        val lineColor = P.brand
        val fillBrush = Brush.verticalGradient(listOf(P.brand.copy(alpha = 0.25f), P.brand.copy(alpha = 0.02f)))
        val gridColor = P.line

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        if (points.size <= 1) {
                            selectedIdx = 0
                        } else {
                            val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            selectedIdx = (ratio * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val padTop = 14.dp.toPx()
            val padBot = 14.dp.toPx()
            val padSide = 10.dp.toPx()
            val drawW = (w - padSide * 2).coerceAtLeast(1f)
            val drawH = (h - padTop - padBot).coerceAtLeast(1f)

            val minVal = points.minOf { it.agorot }.toFloat()
            val maxVal = points.maxOf { it.agorot }.toFloat()
            val range = (maxVal - minVal).coerceAtLeast(50f)

            for (g in 0..2) {
                val y = padTop + drawH * (g / 2f)
                drawLine(gridColor, Offset(padSide, y), Offset(w - padSide, y), strokeWidth = 1.dp.toPx())
            }

            fun xAt(i: Int): Float =
                if (points.size == 1) w / 2f else padSide + drawW * (i.toFloat() / (points.size - 1))

            fun yAt(v: Int): Float =
                padTop + drawH * (1f - ((v - minVal) / range).coerceIn(0f, 1f))

            if (points.size >= 2) {
                val linePath = Path()
                val fillPath = Path()
                points.forEachIndexed { idx, pt ->
                    val x = xAt(idx)
                    val y = yAt(pt.agorot)
                    if (idx == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, h - padBot)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(xAt(points.lastIndex), h - padBot)
                fillPath.close()
                drawPath(fillPath, fillBrush)
                drawPath(linePath, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }

            points.forEachIndexed { idx, pt ->
                val center = Offset(xAt(idx), yAt(pt.agorot))
                val isSel = idx == selectedIdx
                drawCircle(
                    color = if (isSel) lineColor else lineColor.copy(alpha = 0.65f),
                    radius = if (isSel) 6.dp.toPx() else 3.5.dp.toPx(),
                    center = center
                )
                if (isSel) {
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White,
                        radius = 2.5.dp.toPx(),
                        center = center
                    )
                }
            }
        }
    }
}
