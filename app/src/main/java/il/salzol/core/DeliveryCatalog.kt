package il.salzol.core

data class DeliveryRule(
    val chainKey: String,
    val displayName: String,
    val baseFeeAgorot: Int,
    val minOrderAgorot: Int,
    val freeAboveAgorot: Int? = null,
    val note: String = ""
)

data class DeliveryEstimate(
    val rule: DeliveryRule,
    val feeAgorot: Int,
    val belowMinAgorot: Int
)

object DeliveryCatalog {
    val rules: Map<String, DeliveryRule> = listOf(
        DeliveryRule("shufersal", "שופרסל אונליין", 3000, 15000, 75000, "חינם מעל 750 ₪"),
        DeliveryRule("RamiLevi", "רמי לוי באינטרנט", 2990, 15000, null, "איסוף חינם בסניף"),
        DeliveryRule("carrefour", "קרפור אונליין", 2990, 15000, 65000, "חינם מעל 650 ₪"),
        DeliveryRule("quik", "קוויק", 2990, 15000, null, "משלוח מהיר עד הבית"),
        DeliveryRule("wolt", "וולט מרקט", 1600, 5000, null, "משלוח תוך ~30 דקות"),
        DeliveryRule("victory", "ויקטורי אונליין", 3000, 15000, 70000, "חינם מעל 700 ₪"),
        DeliveryRule("hazihinam", "חצי חינם אונליין", 2900, 20000, 70000, "חינם מעל 700 ₪"),
        DeliveryRule("TivTaam", "טיב טעם בקליק", 2900, 15000, 65000, "משלוח גם בשבת"),
        DeliveryRule("superpharm", "סופר-פארם אונליין", 1990, 0, 29900, "חינם מעל 299 ₪"),
        DeliveryRule("be", "Be אונליין", 2490, 0, 29900, "חינם מעל 299 ₪"),
        DeliveryRule("goodpharm", "גוד פארם", 2500, 9900, 29900, "פארם במחיר דיסקאונט")
    ).associateBy { it.chainKey }

    fun estimateFor(chainKey: String, subtotalAgorot: Int): DeliveryEstimate? {
        val rule = rules[chainKey] ?: return null
        val fee = if (rule.freeAboveAgorot != null && subtotalAgorot >= rule.freeAboveAgorot) 0 else rule.baseFeeAgorot
        val belowMin = maxOf(0, rule.minOrderAgorot - subtotalAgorot)
        return DeliveryEstimate(rule, fee, belowMin)
    }

    fun isPharmaChain(chainKey: String): Boolean =
        chainKey == "superpharm" || chainKey == "be" || chainKey == "goodpharm"
}
