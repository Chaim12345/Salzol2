package il.salzol.core.cloud

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import il.salzol.go.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

class FamilySyncRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    fun observeSharedLists(userId: String): Flow<List<SharedFamilyList>> =
        db.collection(COLL_SHARED_LISTS)
            .whereArrayContains("memberUids", userId)
            .snapshots()
            .map { snap ->
                snap.toObjects(SharedFamilyList::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
            }

    fun observeListItems(listId: String, userId: String): Flow<List<SharedFamilyItem>> =
        db.collection(COLL_SHARED_LISTS)
            .document(listId)
            .collection(SUB_ITEMS)
            .whereArrayContains("memberUids", userId)
            .snapshots()
            .map { snap ->
                snap.toObjects(SharedFamilyItem::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedWith(compareBy<SharedFamilyItem> { it.checked }.thenBy { it.name })
            }

    fun observeWatchedItems(userId: String): Flow<List<CloudWatchedItem>> =
        db.collection(COLL_USERS)
            .document(userId)
            .collection(SUB_WATCHED)
            .snapshots()
            .map { snap ->
                snap.toObjects(CloudWatchedItem::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.name }
            }

    suspend fun createSharedList(
        ownerUid: String,
        name: String,
        initialInviteCode: String? = null
    ): Result<SharedFamilyList> {
        val cleanName = name.trim().take(80)
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("שם הרשימה לא יכול להיות ריק"))
        }
        val code = (initialInviteCode ?: generateInviteCode())
            .uppercase()
            .replace(Regex("[^A-Z0-9]"), "")
            .take(16)
        require(code.length >= 4) { "Invite code must be at least 4 alphanumeric characters" }
        val listId = listIdFromInviteCode(code)
        val docRef = db.collection(COLL_SHARED_LISTS).document(listId)
        val model = SharedFamilyList(
            id = listId,
            ownerId = ownerUid,
            memberUids = listOf(ownerUid),
            inviteCode = code,
            name = cleanName,
            createdAt = null,
            updatedAt = null
        )
        return try {
            docRef.set(model).await()
            val saved = docRef.get().await()
                .toObject(SharedFamilyList::class.java, ServerTimestampBehavior.ESTIMATE) ?: model
            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun joinListByCode(userId: String, rawCodeOrId: String): Result<SharedFamilyList> {
        val clean = rawCodeOrId.trim()
        val listId = if (clean.startsWith("fam_")) {
            safeFirestoreDocId(clean)
        } else {
            val code = clean.uppercase().replace(Regex("[^A-Z0-9]"), "")
            if (code.length < 4) {
                return Result.failure(IllegalArgumentException("קוד ההזמנה קצר מדי"))
            }
            listIdFromInviteCode(code)
        }
        val docRef = db.collection(COLL_SHARED_LISTS).document(listId)
        return try {
            val payload = mapOf(
                "memberUids" to FieldValue.arrayUnion(userId),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                docRef.update(payload).await()
            } catch (updateEx: CancellationException) {
                throw updateEx
            } catch (updateEx: Exception) {
                // If caller is already a member, update fails Action 2 unless reading directly
                val existing = runCatching {
                    docRef.get().await().toObject(SharedFamilyList::class.java, ServerTimestampBehavior.ESTIMATE)
                }.getOrNull()
                if (existing != null && userId in existing.memberUids) {
                    return Result.success(existing)
                }
                throw updateEx
            }
            val joined = docRef.get().await()
                .toObject(SharedFamilyList::class.java, ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("הרשימה לא נמצאה"))
            Result.success(joined)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getSharedList(listId: String): Result<SharedFamilyList?> {
        val docRef = db.collection(COLL_SHARED_LISTS).document(safeFirestoreDocId(listId))
        return try {
            val snap = docRef.get().await()
            val item = if (snap.exists()) {
                snap.toObject(SharedFamilyList::class.java, ServerTimestampBehavior.ESTIMATE)
            } else null
            Result.success(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteSharedList(listId: String): Result<Unit> {
        val docRef = db.collection(COLL_SHARED_LISTS).document(safeFirestoreDocId(listId))
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertItem(
        list: SharedFamilyList,
        actorUid: String,
        code: String,
        name: String,
        qty: Double,
        checked: Boolean,
        existing: SharedFamilyItem? = null
    ): Result<Unit> {
        val cleanListId = safeFirestoreDocId(list.id)
        val itemDocId = safeFirestoreDocId(code)
        val docRef = db.collection(COLL_SHARED_LISTS)
            .document(cleanListId)
            .collection(SUB_ITEMS)
            .document(itemDocId)
        val boundedQty = qty.coerceIn(0.05, 500.0)
        val cleanName = name.trim().ifEmpty { code }.take(140)
        val cleanCode = code.trim().take(80)
        return try {
            val resolvedExisting = if (existing != null && existing.createdAt != null) {
                existing
            } else {
                val snap = docRef.get().await()
                if (snap.exists()) snap.toObject(SharedFamilyItem::class.java) else null
            }
            if (resolvedExisting != null && resolvedExisting.createdAt != null) {
                val updated = resolvedExisting.copy(
                    name = cleanName,
                    qty = boundedQty,
                    checked = checked,
                    memberUids = list.memberUids,
                    updatedAt = null
                )
                docRef.set(updated).await()
            } else {
                val created = SharedFamilyItem(
                    id = itemDocId,
                    addedBy = actorUid,
                    memberUids = list.memberUids,
                    code = cleanCode,
                    name = cleanName,
                    qty = boundedQty,
                    checked = checked,
                    createdAt = null,
                    updatedAt = null
                )
                docRef.set(created).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteItem(listId: String, code: String): Result<Unit> {
        val docRef = db.collection(COLL_SHARED_LISTS)
            .document(safeFirestoreDocId(listId))
            .collection(SUB_ITEMS)
            .document(safeFirestoreDocId(code))
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun syncMemberUidsToItems(list: SharedFamilyList, items: List<SharedFamilyItem>) {
        val expectedSet = list.memberUids.toSet()
        items.filter { it.memberUids.toSet() != expectedSet && it.createdAt != null }
            .forEach { item ->
                val docRef = db.collection(COLL_SHARED_LISTS)
                    .document(safeFirestoreDocId(list.id))
                    .collection(SUB_ITEMS)
                    .document(safeFirestoreDocId(item.id.ifEmpty { item.code }))
                runCatching {
                    docRef.set(item.copy(memberUids = list.memberUids, updatedAt = null)).await()
                }
            }
    }

    suspend fun upsertWatchedItem(
        userId: String,
        code: String,
        name: String,
        targetAgorot: Long,
        existing: CloudWatchedItem? = null
    ): Result<Unit> {
        val docId = safeFirestoreDocId(code)
        val docRef = db.collection(COLL_USERS)
            .document(userId)
            .collection(SUB_WATCHED)
            .document(docId)
        val boundedTarget = targetAgorot.coerceIn(0L, 1_000_000L)
        val cleanName = name.trim().ifEmpty { code }.take(140)
        val cleanCode = code.trim().take(80)
        return try {
            val resolvedExisting = if (existing != null && existing.createdAt != null) {
                existing
            } else {
                val snap = docRef.get().await()
                if (snap.exists()) snap.toObject(CloudWatchedItem::class.java) else null
            }
            if (resolvedExisting != null && resolvedExisting.createdAt != null) {
                docRef.set(
                    resolvedExisting.copy(
                        name = cleanName,
                        targetAgorot = boundedTarget,
                        updatedAt = null
                    )
                ).await()
            } else {
                docRef.set(
                    CloudWatchedItem(
                        id = docId,
                        code = cleanCode,
                        name = cleanName,
                        targetAgorot = boundedTarget,
                        createdAt = null,
                        updatedAt = null
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteWatchedItem(userId: String, code: String): Result<Unit> {
        val docRef = db.collection(COLL_USERS)
            .document(userId)
            .collection(SUB_WATCHED)
            .document(safeFirestoreDocId(code))
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    companion object {
        const val COLL_SHARED_LISTS = "shared_lists"
        const val SUB_ITEMS = "items"
        const val COLL_USERS = "users"
        const val SUB_WATCHED = "watched"
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val rng = SecureRandom()

        fun generateInviteCode(length: Int = 8): String =
            buildString(length) {
                repeat(length) { append(CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)]) }
            }

        fun listIdFromInviteCode(code: String): String =
            safeFirestoreDocId("fam_${code.uppercase()}")
    }
}
