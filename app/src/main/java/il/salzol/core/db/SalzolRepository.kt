package il.salzol.core.db

import androidx.room.withTransaction
import il.salzol.core.AiConfig
import il.salzol.core.AiPresets
import il.salzol.core.AiRecord
import il.salzol.core.AiVerdict
import il.salzol.core.Change
import il.salzol.core.EngineState
import il.salzol.core.Notice
import il.salzol.core.Place
import il.salzol.core.SavedList
import il.salzol.core.SizeRec
import il.salzol.core.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.Instant

data class StoredAiRow(
    val code: String,
    val hash: String,
    val rec: AiRecord,
    val model: String,
    val at: Long
)

data class PersistenceSummary(
    val placeName: String = "",
    val hasPlace: Boolean = false,
    val aiEnabled: Boolean = false,
    val aiModel: String = "",
    val cartCount: Int = 0,
    val watchCount: Int = 0,
    val savedListCount: Int = 0,
    val aiRecordCount: Int = 0,
    val geocodeCacheCount: Int = 0
)

class SalzolRepository(private val db: SalzolDatabase) {
    private val configDao = db.configDao()
    private val shoppingDao = db.shoppingDao()
    private val aiCacheDao = db.aiCacheDao()
    private val geocodeDao = db.geocodeDao()

    val configFlow: Flow<AppConfigEntity?> = configDao.observeConfig()
    val disabledStoresFlow: Flow<List<DisabledStoreEntity>> = configDao.observeDisabledStores()
    val clubMembershipsFlow: Flow<List<ClubMembershipEntity>> = configDao.observeClubMemberships()
    val cartItemsFlow: Flow<List<CartItemEntity>> = shoppingDao.observeCartItems()
    val watchItemsFlow: Flow<List<WatchItemEntity>> = shoppingDao.observeWatchItems()
    val savedListsFlow: Flow<List<SavedListEntity>> = shoppingDao.observeSavedLists()
    val priceChangesFlow: Flow<List<PriceChangeEntity>> = shoppingDao.observePriceChanges()
    val noticesFlow: Flow<List<PriceNoticeEntity>> = shoppingDao.observeNotices()
    val aiRecordCountFlow: Flow<Int> = aiCacheDao.observeAiRecordCount()
    val geocodeCacheCountFlow: Flow<Int> = geocodeDao.observeCacheCount()

    val summaryFlow: Flow<PersistenceSummary> = combine(
        combine(configFlow, cartItemsFlow, watchItemsFlow) { cfg, cart, watch ->
            Triple(cfg, cart.size, watch.size)
        },
        combine(savedListsFlow, aiRecordCountFlow, geocodeCacheCountFlow) { lists, aiCount, geoCount ->
            Triple(lists.size, aiCount, geoCount)
        }
    ) { (cfg, cartCount, watchCount), (listCount, aiCount, geoCount) ->
        PersistenceSummary(
            placeName = cfg?.placeName ?: "",
            hasPlace = cfg?.hasPlace == true,
            aiEnabled = cfg?.aiOn == true,
            aiModel = cfg?.aiModel ?: "",
            cartCount = cartCount,
            watchCount = watchCount,
            savedListCount = listCount,
            aiRecordCount = aiCount,
            geocodeCacheCount = geoCount
        )
    }

    suspend fun hasPersistedConfig(): Boolean = withContext(Dispatchers.IO) {
        configDao.getConfig() != null
    }

    suspend fun loadEngineStateInto(st: EngineState): Boolean = withContext(Dispatchers.IO) {
        val cfg = configDao.getConfig() ?: return@withContext false

        st.place = if (cfg.hasPlace) Place(cfg.placeName, cfg.placeLat, cfg.placeLon, cfg.placeRadius) else null
        st.images = cfg.imagesEnabled
        st.ai = AiConfig(
            on = cfg.aiOn,
            preset = cfg.aiPreset,
            baseUrl = cfg.aiBaseUrl.ifEmpty { AiPresets["gemini"]!!.baseUrl },
            model = cfg.aiModel.ifEmpty { AiPresets["gemini"]!!.model },
            key = cfg.aiKey,
            enrichModel = cfg.aiEnrichModel,
            enrichAuto = cfg.aiEnrichAuto,
            dailyCap = if (cfg.aiDailyCap > 0) cfg.aiDailyCap else 300
        )
        st.usage = Usage(cfg.usageDay, cfg.usageCount)

        st.off.clear()
        configDao.getDisabledStores().forEach { st.off.add(it.storeId) }

        st.club.clear()
        configDao.getClubMemberships().forEach { st.club[it.chain] = it.owned }

        st.cart.clear()
        st.checked.clear()
        shoppingDao.getCartItems().forEach {
            st.cart[it.code] = it.qty
            if (it.checked) st.checked.add(it.code)
        }

        st.watch.clear()
        shoppingDao.getWatchItems().forEach { st.watch.add(it.code) }

        st.freq.clear()
        shoppingDao.getFrequencies().forEach { st.freq[it.code] = it.count }

        st.changes.clear()
        shoppingDao.getPriceChanges().forEach {
            st.changes.add(Change(it.day, it.code, it.store, it.fromPrice, it.toPrice))
        }

        st.notices.clear()
        shoppingDao.getNotices().forEach {
            st.notices.add(Notice(it.at, it.code, it.title, it.body, it.isRead))
        }

        st.lists.clear()
        val listRows = shoppingDao.getSavedLists()
        val itemsByList = shoppingDao.getAllSavedListItems().groupBy { it.listId }
        for (l in listRows) {
            val map = LinkedHashMap<String, Double>()
            itemsByList[l.id]?.forEach { item -> map[item.code] = item.qty }
            st.lists.add(SavedList(l.id, l.name, map, l.createdAt))
        }
        true
    }

    suspend fun saveEngineState(st: EngineState) = withContext(Dispatchers.IO) {
        val place = st.place
        val cfg = AppConfigEntity(
            id = 1,
            hasPlace = place != null,
            placeName = place?.name ?: "",
            placeLat = place?.lat ?: 0.0,
            placeLon = place?.lon ?: 0.0,
            placeRadius = place?.radius ?: 4.0,
            imagesEnabled = st.images,
            aiOn = st.ai.on,
            aiPreset = st.ai.preset,
            aiBaseUrl = st.ai.baseUrl,
            aiModel = st.ai.model,
            aiKey = st.ai.key,
            aiEnrichModel = st.ai.enrichModel,
            aiEnrichAuto = st.ai.enrichAuto,
            aiDailyCap = st.ai.dailyCap,
            usageDay = st.usage.day,
            usageCount = st.usage.n
        )
        val disabled = st.off.distinct().map { DisabledStoreEntity(it) }
        val clubs = st.club.entries.map { ClubMembershipEntity(it.key, it.value) }
        val cartRows = st.cart.entries.map { CartItemEntity(it.key, it.value, st.checked.contains(it.key)) }
        val watchRows = st.watch.mapIndexed { idx, code -> WatchItemEntity(code, idx) }
        val freqRows = st.freq.entries.map { ProductFrequencyEntity(it.key, it.value) }
        val listEntities = st.lists.map { SavedListEntity(it.id, it.name, it.at) }
        val listItems = st.lists.flatMap { l ->
            l.items.entries.map { (code, qty) -> SavedListItemEntity(l.id, code, qty) }
        }
        val changeRows = st.changes.map {
            PriceChangeEntity(day = it.day, code = it.code, store = it.store, fromPrice = it.from, toPrice = it.to)
        }
        val noticeRows = st.notices.map {
            PriceNoticeEntity(at = it.at, code = it.code, title = it.title, body = it.body, isRead = it.read)
        }

        db.withTransaction {
            configDao.upsertConfig(cfg)

            configDao.clearDisabledStores()
            if (disabled.isNotEmpty()) configDao.insertDisabledStores(disabled)

            configDao.clearClubMemberships()
            if (clubs.isNotEmpty()) configDao.insertClubMemberships(clubs)

            shoppingDao.clearCartItems()
            if (cartRows.isNotEmpty()) shoppingDao.insertCartItems(cartRows)

            shoppingDao.clearWatchItems()
            if (watchRows.isNotEmpty()) shoppingDao.insertWatchItems(watchRows)

            shoppingDao.clearFrequencies()
            if (freqRows.isNotEmpty()) shoppingDao.insertFrequencies(freqRows)

            shoppingDao.clearSavedLists()
            if (listEntities.isNotEmpty()) {
                shoppingDao.insertSavedLists(listEntities)
                if (listItems.isNotEmpty()) shoppingDao.insertSavedListItems(listItems)
            }

            shoppingDao.clearPriceChanges()
            if (changeRows.isNotEmpty()) shoppingDao.insertPriceChanges(changeRows)

            shoppingDao.clearNotices()
            if (noticeRows.isNotEmpty()) shoppingDao.insertNotices(noticeRows)
        }
    }

    suspend fun getAllAiRecords(): List<StoredAiRow> = withContext(Dispatchers.IO) {
        aiCacheDao.getAllAiRecords().map { e ->
            val size = if (e.sizeAmount != null && e.sizeAmount > 0 && e.sizeUnit != null) {
                SizeRec(e.sizeAmount, e.sizeUnit, e.sizePack ?: 1)
            } else null
            val diets = if (e.dietsCsv.isEmpty()) emptyList() else e.dietsCsv.split(',').filter { it.isNotEmpty() }
            StoredAiRow(
                code = e.code,
                hash = e.nameHash,
                rec = AiRecord(
                    t = e.type,
                    c = e.cat,
                    b = e.brand,
                    l = e.line,
                    v = e.variant,
                    s = size,
                    d = diets,
                    pl = e.privateLabel,
                    q = e.unsure
                ),
                model = e.model,
                at = e.updatedAt
            )
        }
    }

    suspend fun upsertAiRecords(rows: List<StoredAiRow>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val entities = rows.map { r ->
            AiRecordEntity(
                code = r.code,
                nameHash = r.hash,
                type = r.rec.t,
                cat = r.rec.c,
                brand = r.rec.b,
                line = r.rec.l,
                variant = r.rec.v,
                sizeAmount = r.rec.s?.a,
                sizeUnit = r.rec.s?.u,
                sizePack = r.rec.s?.p,
                dietsCsv = r.rec.d.joinToString(","),
                privateLabel = r.rec.pl,
                unsure = r.rec.q,
                model = r.model,
                updatedAt = r.at
            )
        }
        aiCacheDao.upsertAiRecords(entities)
    }

    suspend fun clearAiRecords() = withContext(Dispatchers.IO) {
        aiCacheDao.clearAiRecords()
    }

    suspend fun getValidVerdicts(): Map<String, AiVerdict> = withContext(Dispatchers.IO) {
        val now = Instant.now().epochSecond
        aiCacheDao.getValidVerdicts(now).associate { it.key to AiVerdict(it.answer, it.expiresAt) }
    }

    suspend fun upsertVerdicts(entries: Map<String, AiVerdict>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        aiCacheDao.upsertVerdicts(entries.entries.map { AiVerdictEntity(it.key, it.value.a, it.value.exp) })
    }

    suspend fun clearVerdicts() = withContext(Dispatchers.IO) {
        aiCacheDao.clearVerdicts()
    }

    suspend fun getAllGeocodeEntries(): List<GeocodeCacheEntity> = withContext(Dispatchers.IO) {
        geocodeDao.getAll()
    }

    suspend fun saveGeocodeEntry(query: String, found: Boolean, lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        geocodeDao.upsert(
            GeocodeCacheEntity(
                query = query,
                found = found,
                lat = lat,
                lon = lon,
                cachedAt = Instant.now().epochSecond
            )
        )
    }

    suspend fun importLegacyGeocodeEntries(entries: List<GeocodeCacheEntity>) = withContext(Dispatchers.IO) {
        if (entries.isNotEmpty()) geocodeDao.upsertAll(entries)
    }
}
