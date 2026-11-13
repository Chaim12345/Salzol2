package il.salzol.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import il.salzol.core.AiPresets

@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val id: Int = 1,
    val hasPlace: Boolean = false,
    val placeName: String = "",
    val placeLat: Double = 0.0,
    val placeLon: Double = 0.0,
    val placeRadius: Double = 4.0,
    val imagesEnabled: Boolean = true,
    val aiOn: Boolean = false,
    val aiPreset: String = "gemini",
    val aiBaseUrl: String = AiPresets["gemini"]!!.baseUrl,
    val aiModel: String = AiPresets["gemini"]!!.model,
    val aiKey: String = "",
    val aiEnrichModel: String = "",
    val aiEnrichAuto: Boolean = true,
    val aiDailyCap: Int = 300,
    val usageDay: String = "",
    val usageCount: Int = 0
)

@Entity(tableName = "disabled_stores")
data class DisabledStoreEntity(
    @PrimaryKey val storeId: String
)

@Entity(tableName = "club_memberships")
data class ClubMembershipEntity(
    @PrimaryKey val chain: String,
    val owned: Boolean
)

@Entity(tableName = "cart_items")
data class CartItemEntity(
    @PrimaryKey val code: String,
    val qty: Double,
    val checked: Boolean = false
)

@Entity(tableName = "watch_items")
data class WatchItemEntity(
    @PrimaryKey val code: String,
    val position: Int
)

@Entity(tableName = "product_frequencies")
data class ProductFrequencyEntity(
    @PrimaryKey val code: String,
    val count: Int
)

@Entity(tableName = "saved_lists")
data class SavedListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

@Entity(
    tableName = "saved_list_items",
    primaryKeys = ["listId", "code"],
    foreignKeys = [
        ForeignKey(
            entity = SavedListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["listId"])]
)
data class SavedListItemEntity(
    val listId: String,
    val code: String,
    val qty: Double
)

@Entity(tableName = "price_changes")
data class PriceChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val code: String,
    val store: String,
    val fromPrice: Int,
    val toPrice: Int
)

@Entity(tableName = "price_notices")
data class PriceNoticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val code: String,
    val title: String,
    val body: String,
    val isRead: Boolean = false
)

@Entity(tableName = "ai_records")
data class AiRecordEntity(
    @PrimaryKey val code: String,
    val nameHash: String,
    val type: String,
    val cat: String,
    val brand: String,
    val line: String,
    val variant: String,
    val sizeAmount: Double?,
    val sizeUnit: String?,
    val sizePack: Int?,
    val dietsCsv: String,
    val privateLabel: Boolean,
    val unsure: Boolean,
    val model: String,
    val updatedAt: Long
)

@Entity(tableName = "ai_verdicts")
data class AiVerdictEntity(
    @PrimaryKey val key: String,
    val answer: String,
    val expiresAt: Long
)

@Entity(tableName = "geocode_cache")
data class GeocodeCacheEntity(
    @PrimaryKey val query: String,
    val found: Boolean,
    val lat: Double,
    val lon: Double,
    val cachedAt: Long
)
