package il.salzol.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppConfigEntity::class,
        DisabledStoreEntity::class,
        ClubMembershipEntity::class,
        CartItemEntity::class,
        WatchItemEntity::class,
        ProductFrequencyEntity::class,
        SavedListEntity::class,
        SavedListItemEntity::class,
        PriceChangeEntity::class,
        PriceNoticeEntity::class,
        AiRecordEntity::class,
        AiVerdictEntity::class,
        GeocodeCacheEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SalzolDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun aiCacheDao(): AiCacheDao
    abstract fun geocodeDao(): GeocodeDao

    companion object {
        @Volatile
        private var INSTANCE: SalzolDatabase? = null

        fun getInstance(context: Context): SalzolDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SalzolDatabase::class.java,
                    "salzol.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
