package il.salzol.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Query("SELECT * FROM app_config WHERE id = 1")
    fun observeConfig(): Flow<AppConfigEntity?>

    @Query("SELECT * FROM app_config WHERE id = 1")
    suspend fun getConfig(): AppConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: AppConfigEntity)

    @Query("SELECT * FROM disabled_stores")
    fun observeDisabledStores(): Flow<List<DisabledStoreEntity>>

    @Query("SELECT * FROM disabled_stores")
    suspend fun getDisabledStores(): List<DisabledStoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDisabledStores(stores: List<DisabledStoreEntity>)

    @Query("DELETE FROM disabled_stores")
    suspend fun clearDisabledStores()

    @Query("SELECT * FROM club_memberships")
    fun observeClubMemberships(): Flow<List<ClubMembershipEntity>>

    @Query("SELECT * FROM club_memberships")
    suspend fun getClubMemberships(): List<ClubMembershipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClubMemberships(clubs: List<ClubMembershipEntity>)

    @Query("DELETE FROM club_memberships")
    suspend fun clearClubMemberships()
}

@Dao
interface ShoppingDao {
    @Query("SELECT * FROM cart_items")
    fun observeCartItems(): Flow<List<CartItemEntity>>

    @Query("SELECT * FROM cart_items")
    suspend fun getCartItems(): List<CartItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCartItems(items: List<CartItemEntity>)

    @Query("DELETE FROM cart_items")
    suspend fun clearCartItems()

    @Query("SELECT * FROM watch_items ORDER BY position ASC")
    fun observeWatchItems(): Flow<List<WatchItemEntity>>

    @Query("SELECT * FROM watch_items ORDER BY position ASC")
    suspend fun getWatchItems(): List<WatchItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchItems(items: List<WatchItemEntity>)

    @Query("DELETE FROM watch_items")
    suspend fun clearWatchItems()

    @Query("SELECT * FROM product_frequencies")
    suspend fun getFrequencies(): List<ProductFrequencyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFrequencies(items: List<ProductFrequencyEntity>)

    @Query("DELETE FROM product_frequencies")
    suspend fun clearFrequencies()

    @Query("SELECT * FROM saved_lists ORDER BY createdAt DESC")
    fun observeSavedLists(): Flow<List<SavedListEntity>>

    @Query("SELECT * FROM saved_lists ORDER BY createdAt DESC")
    suspend fun getSavedLists(): List<SavedListEntity>

    @Query("SELECT * FROM saved_list_items")
    suspend fun getAllSavedListItems(): List<SavedListItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedLists(lists: List<SavedListEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedListItems(items: List<SavedListItemEntity>)

    @Query("DELETE FROM saved_lists")
    suspend fun clearSavedLists()

    @Query("SELECT * FROM price_changes ORDER BY id ASC")
    fun observePriceChanges(): Flow<List<PriceChangeEntity>>

    @Query("SELECT * FROM price_changes ORDER BY id ASC")
    suspend fun getPriceChanges(): List<PriceChangeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPriceChanges(changes: List<PriceChangeEntity>)

    @Query("DELETE FROM price_changes")
    suspend fun clearPriceChanges()

    @Query("SELECT * FROM price_notices ORDER BY id ASC")
    fun observeNotices(): Flow<List<PriceNoticeEntity>>

    @Query("SELECT * FROM price_notices ORDER BY id ASC")
    suspend fun getNotices(): List<PriceNoticeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotices(notices: List<PriceNoticeEntity>)

    @Query("DELETE FROM price_notices")
    suspend fun clearNotices()
}

@Dao
interface AiCacheDao {
    @Query("SELECT COUNT(*) FROM ai_records")
    fun observeAiRecordCount(): Flow<Int>

    @Query("SELECT * FROM ai_records")
    suspend fun getAllAiRecords(): List<AiRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAiRecords(records: List<AiRecordEntity>)

    @Query("DELETE FROM ai_records")
    suspend fun clearAiRecords()

    @Query("SELECT COUNT(*) FROM ai_verdicts WHERE expiresAt > :nowSec")
    fun observeActiveVerdictCount(nowSec: Long): Flow<Int>

    @Query("SELECT * FROM ai_verdicts WHERE expiresAt > :nowSec")
    suspend fun getValidVerdicts(nowSec: Long): List<AiVerdictEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVerdicts(verdicts: List<AiVerdictEntity>)

    @Query("DELETE FROM ai_verdicts")
    suspend fun clearVerdicts()
}

@Dao
interface GeocodeDao {
    @Query("SELECT COUNT(*) FROM geocode_cache")
    fun observeCacheCount(): Flow<Int>

    @Query("SELECT * FROM geocode_cache")
    suspend fun getAll(): List<GeocodeCacheEntity>

    @Query("SELECT * FROM geocode_cache WHERE `query` = :query LIMIT 1")
    suspend fun getByQuery(query: String): GeocodeCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GeocodeCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<GeocodeCacheEntity>)

    @Query("DELETE FROM geocode_cache")
    suspend fun clear()
}
