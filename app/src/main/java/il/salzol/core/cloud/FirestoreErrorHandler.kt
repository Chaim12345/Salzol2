package il.salzol.core.cloud

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write")
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

fun Throwable.unwrapFirestoreException(): FirebaseFirestoreException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is FirebaseFirestoreException) return current
        current = current.cause
    }
    return null
}

fun Throwable.userFriendlyFirestoreMessage(): String {
    val fsEx = unwrapFirestoreException()
    val msg = fsEx?.message ?: localizedMessage ?: ""
    return when {
        msg.contains("Quota exceeded", ignoreCase = true) ->
            "מכסת הסנכרון היומית הסתיימה ותתחדש מחר."
        fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "אין הרשאה לפעולה זו. ודאו שאתם מחוברים לחשבון Google."
        fsEx?.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
            "אין חיבור לענן כרגע. השינויים יסונכרנו בהתחברות."
        else -> msg.ifBlank { "שגיאה בסנכרון מול הענן" }
    }
}
