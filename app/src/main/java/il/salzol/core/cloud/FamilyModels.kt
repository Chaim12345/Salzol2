package il.salzol.core.cloud

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class SharedFamilyList(
    @DocumentId val id: String = "",
    val ownerId: String = "",
    val memberUids: List<String> = emptyList(),
    val inviteCode: String = "",
    val name: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SharedFamilyItem(
    @DocumentId val id: String = "",
    val addedBy: String = "",
    val memberUids: List<String> = emptyList(),
    val code: String = "",
    val name: String = "",
    val qty: Double = 1.0,
    val checked: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudWatchedItem(
    @DocumentId val id: String = "",
    val code: String = "",
    val name: String = "",
    val targetAgorot: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

fun safeFirestoreDocId(raw: String): String {
    val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        .trim('_')
        .take(120)
    return cleaned.ifEmpty { "doc_${raw.hashCode().toUInt()}" }
}
