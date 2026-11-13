package il.salzol.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class NutritionInfo(
    val code: String,
    val redSugar: Boolean = false,
    val redSodium: Boolean = false,
    val redSatFat: Boolean = false,
    val greenLabel: Boolean = false,
    val nutriscore: String = "",
    val allergens: List<String> = emptyList(),
    val sugarsG100: Double? = null,
    val sodiumMg100: Double? = null,
    val satFatG100: Double? = null,
    val imageUrl: String = "",
    val fetchedAt: Long = 0L
) {
    val redCount: Int
        get() = (if (redSugar) 1 else 0) + (if (redSodium) 1 else 0) + (if (redSatFat) 1 else 0)

    val hasAnyLabel: Boolean
        get() = redSugar || redSodium || redSatFat || greenLabel || allergens.isNotEmpty() || nutriscore.isNotEmpty()
}

object OpenFoodFactsClient {
    private val allergenMap = mapOf(
        "en:gluten" to "גלוטן",
        "en:wheat" to "חיטה (גלוטן)",
        "en:milk" to "חלב",
        "en:peanuts" to "בוטנים",
        "en:nuts" to "אגוזים",
        "en:sesame-seeds" to "שומשום",
        "en:eggs" to "ביצים",
        "en:soybeans" to "סויה",
        "en:fish" to "דגים",
        "en:crustaceans" to "סרטנים",
        "en:mustard" to "חרדל",
        "en:celery" to "סלרי",
        "en:lupin" to "תורמוס",
        "en:sulphur-dioxide-and-sulphites" to "סולפיטים"
    )

    fun isQueryableBarcode(code: String): Boolean =
        code.length in 7..14 && code.all { it.isDigit() } && !code.startsWith("f-")

    fun fetchProduct(code: String, isLiquid: Boolean): NutritionInfo? {
        if (!isQueryableBarcode(code)) return null
        val url = "https://world.openfoodfacts.org/api/v2/product/$code.json?fields=product_name,nutriments,nutriscore_grade,allergens_tags,image_front_small_url,image_front_url"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Salzol/2.0 (Android; il.salzol.go)")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (root.optInt("status", 0) != 1) return null
            val p = root.optJSONObject("product") ?: return null
            val nut = p.optJSONObject("nutriments") ?: JSONObject()

            val sugars = nut.optDoubleOrNull("sugars_100g")
            val sodiumG = nut.optDoubleOrNull("sodium_100g")
                ?: nut.optDoubleOrNull("salt_100g")?.let { it / 2.5 }
            val sodiumMg = sodiumG?.let { it * 1000.0 }
            val satFat = nut.optDoubleOrNull("saturated-fat_100g")

            // Israeli Ministry of Health Red Label Stage 2 thresholds
            val sugarLimit = if (isLiquid) 5.0 else 10.0
            val sodiumLimitMg = if (isLiquid) 300.0 else 400.0
            val satFatLimit = if (isLiquid) 3.0 else 4.0

            val redSugar = sugars != null && sugars >= sugarLimit
            val redSodium = sodiumMg != null && sodiumMg >= sodiumLimitMg
            val redSatFat = satFat != null && satFat >= satFatLimit

            val grade = p.optString("nutriscore_grade", "").lowercase().takeIf { it in setOf("a", "b", "c", "d", "e") } ?: ""
            val greenLabel = !redSugar && !redSodium && !redSatFat && (grade == "a" || grade == "b")

            val algArr = p.optJSONArray("allergens_tags")
            val allergens = mutableListOf<String>()
            if (algArr != null) {
                for (i in 0 until algArr.length()) {
                    val raw = algArr.optString(i)
                    val label = allergenMap[raw]
                    if (label != null && label !in allergens) allergens.add(label)
                }
            }

            val img = p.optString("image_front_small_url", "").ifEmpty {
                p.optString("image_front_url", "")
            }

            return NutritionInfo(
                code = code,
                redSugar = redSugar,
                redSodium = redSodium,
                redSatFat = redSatFat,
                greenLabel = greenLabel,
                nutriscore = grade.uppercase(),
                allergens = allergens,
                sugarsG100 = sugars,
                sodiumMg100 = sodiumMg,
                satFatG100 = satFat,
                imageUrl = img,
                fetchedAt = Instant.now().epochSecond
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }
}
