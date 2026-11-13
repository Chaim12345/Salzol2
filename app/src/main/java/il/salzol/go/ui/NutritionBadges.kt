package il.salzol.go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.salzol.core.NutritionInfo

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NutritionPanel(info: NutritionInfo, modifier: Modifier = Modifier) {
    if (!info.hasAnyLabel && info.sugarsG100 == null && info.sodiumMg100 == null && info.satFatG100 == null) {
        return
    }
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("סימון תזונתי ואלרגנים", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = P.ink, modifier = Modifier.weight(1f))
            if (info.nutriscore.isNotEmpty()) {
                val gradeColor = when (info.nutriscore) {
                    "A", "B" -> P.good
                    "C" -> P.warn
                    else -> P.bad
                }
                Badge("Nutri-Score ${info.nutriscore}", gradeColor)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info.redSugar) RedMinistryBadge("סוכר בכמות גבוהה")
            if (info.redSodium) RedMinistryBadge("נתרן בכמות גבוהה")
            if (info.redSatFat) RedMinistryBadge("שומן רווי בכמות גבוהה")
            if (info.greenLabel) {
                Row(
                    Modifier
                        .background(P.good.copy(alpha = 0.14f), CircleShape)
                        .border(1.dp, P.good, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Outlined.Eco, null, tint = P.good, modifier = Modifier.size(16.dp))
                    Text("הסימון הירוק", color = P.good, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        val macros = buildList {
            info.sugarsG100?.let { add("סוכרים: ${"%.1f".format(it)} גר׳/100") }
            info.sodiumMg100?.let { add("נתרן: ${it.toInt()} מ״ג/100") }
            info.satFatG100?.let { add("שומן רווי: ${"%.1f".format(it)} גר׳/100") }
        }
        if (macros.isNotEmpty()) {
            Text(macros.joinToString(" · "), fontSize = 12.5.sp, color = P.ink2)
        }

        if (info.allergens.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(P.warn.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Outlined.WarningAmber, "אלרגנים", tint = P.warn, modifier = Modifier.size(18.dp))
                Text(
                    "עלול להכיל / מכיל אלרגנים: ${info.allergens.joinToString(", ")}",
                    fontSize = 12.5.sp,
                    color = P.warn,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun RedMinistryBadge(label: String) {
    Row(
        Modifier
            .background(Color(0xFFC62828), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(Color.White, CircleShape)
        )
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
