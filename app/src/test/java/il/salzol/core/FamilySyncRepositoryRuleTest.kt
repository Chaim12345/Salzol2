package il.salzol.core

import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.FirebaseFirestoreException
import il.salzol.core.cloud.FamilySyncRepository
import il.salzol.core.cloud.unwrapFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertFailsWith

class FamilySyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun uniqueCode(prefix: String): String {
        val suffix = ((System.nanoTime() xor prefix.hashCode().toLong()) and 0xFFFFFFL)
            .toString(36)
            .uppercase()
            .padStart(5, 'A')
            .take(5)
        return (prefix.uppercase() + suffix).take(10)
    }

    @Test
    fun createSharedList_validPayload_succeedsAndEmitsRealtime() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repo = FamilySyncRepository(firestore)
        val inviteCode = uniqueCode("FAM")

        val created = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createSharedList(aliceUid, "קניות שבת", inviteCode)
        }
        assertThat(created.isSuccess).isTrue()
        val list = created.getOrThrow()
        assertThat(list.id).isEqualTo("fam_$inviteCode")

        val lists = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSharedLists(aliceUid).first { it.any { item -> item.id == list.id } }
        }
        assertThat(lists.map { it.id }).contains(list.id)
    }

    @Test
    fun inviteJoinAndItemCrud_sharedAcrossMembers_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repo = FamilySyncRepository(firestore)
        val inviteCode = uniqueCode("JOIN")
        val list = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createSharedList(aliceUid, "משפחת כהן", inviteCode).getOrThrow()
        }

        val bobUid = signInTestUser(BOB_EMAIL)
        val joined = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.joinListByCode(bobUid, inviteCode)
        }
        assertThat(joined.isSuccess).isTrue()
        val joinedList = joined.getOrThrow()
        assertThat(joinedList.memberUids).containsAtLeast(aliceUid, bobUid)

        // Internal catalog code with colon tests safeFirestoreDocId sanitization
        val addRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertItem(
                list = joinedList,
                actorUid = bobUid,
                code = "shufersal:7290001",
                name = "חלב 3%",
                qty = 2.0,
                checked = false
            )
        }
        com.google.common.truth.Truth.assertWithMessage("upsertItem failed: ${addRes.exceptionOrNull()}")
            .that(addRes.isSuccess).isTrue()

        signInTestUser(ALICE_EMAIL)
        val observedItems = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeListItems(joinedList.id, aliceUid).first { it.isNotEmpty() }
        }
        assertThat(observedItems.first().code).isEqualTo("shufersal:7290001")
    }

    @Test
    fun getSharedList_nonMember_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repo = FamilySyncRepository(firestore)
        val inviteCode = uniqueCode("PRIV")
        val list = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createSharedList(aliceUid, "פרטי", inviteCode).getOrThrow()
        }

        signInTestUser(CHARLIE_EMAIL)
        val result = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getSharedList(list.id) }
        assertThat(result.isFailure).isTrue()
        val fsEx = checkNotNull(result.exceptionOrNull()?.unwrapFirestoreException())
        assertThat(fsEx.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    @Test
    fun observeSharedLists_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = FamilySyncRepository(firestore)

        val exception = assertFailsWith<Exception> {
            withTimeout(FLOW_TIMEOUT_MS) { repo.observeSharedLists("any_uid").first() }
        }
        val fsEx = checkNotNull(exception.unwrapFirestoreException()) {
            "Expected FirebaseFirestoreException inside $exception"
        }
        assertThat(fsEx.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    @Test
    fun watchedItems_perUserIsolation_enforced() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repo = FamilySyncRepository(firestore)

        val saveRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertWatchedItem(aliceUid, "72900001", "שמן זית", 2990L)
        }
        assertThat(saveRes.isSuccess).isTrue()

        signInTestUser(BOB_EMAIL)
        val crossWrite = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertWatchedItem(aliceUid, "72900001", "פריצה", 100L)
        }
        assertThat(crossWrite.isFailure).isTrue()
        val fsEx = checkNotNull(crossWrite.exceptionOrNull()?.unwrapFirestoreException())
        assertThat(fsEx.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_salzol@test.com"
        const val BOB_EMAIL = "bob_salzol@test.com"
        const val CHARLIE_EMAIL = "charlie_salzol@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
