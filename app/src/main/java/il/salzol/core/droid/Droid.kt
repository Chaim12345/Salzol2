package il.salzol.core.droid

import android.content.Context
import android.content.res.AssetManager
import il.salzol.core.DirectoryRepo
import il.salzol.core.Engine
import il.salzol.core.EngineViews
import il.salzol.core.db.SalzolDatabase
import il.salzol.core.db.SalzolRepository
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

interface Listener {
    fun onEvent(eventJSON: String)
}

object Droid {
    @Volatile
    private var engine: Engine? = null
    @Volatile
    private var repo: SalzolRepository? = null
    private val lock = Any()

    fun initContext(context: Context) {
        val appCtx = context.applicationContext
        DirectoryRepo.initAssets(appCtx.assets)
        synchronized(lock) {
            if (repo == null) {
                repo = SalzolRepository(SalzolDatabase.getInstance(appCtx))
            }
        }
    }

    fun initAssets(assets: AssetManager) {
        DirectoryRepo.initAssets(assets)
    }

    fun repository(): SalzolRepository? = repo

    fun open(dir: String) {
        synchronized(lock) {
            if (engine == null) {
                engine = Engine(dir, repo)
            }
        }
    }

    private fun eng(): Engine = engine ?: throw IllegalStateException("Engine not opened")

    private fun engReady(): Engine {
        val e = eng()
        e.ensureLoaded()
        return e
    }

    private fun guard(fn: () -> String): String {
        return try {
            fn()
        } catch (t: Throwable) {
            logErr(t)
            org.json.JSONObject().put("error", t.message ?: t.toString()).toString()
        }
    }

    private fun logErr(t: Throwable) {
        val dir = engine?.dir ?: return
        runCatching {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            File(dir, "core-errors.txt").appendText("${Instant.now()} $sw\n")
        }
    }

    fun errors(): String {
        val dir = engine?.dir ?: return ""
        val f = File(dir, "core-errors.txt")
        if (!f.exists()) return ""
        val txt = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        return txt
    }

    fun setListener(l: Listener?) {
        eng().setEmitter { ev -> l?.onEvent(ev.toJson()) }
    }

    fun status(): String = guard { EngineViews.statusJson(engReady()) }
    fun home(): String = guard { EngineViews.homeJson(engReady()) }
    fun product(code: String): String = guard { EngineViews.productJson(engReady(), code) }
    fun cart(): String = guard { EngineViews.cartJson(engReady()) }
    fun ai(): String = guard { EngineViews.aiJson(engReady()) }
    fun aiConfig(): String = guard { val e = engReady(); e.aiConfigJsonObj(e.st.ai).toString() }
    fun search(reqJSON: String): String = guard { EngineViews.searchJson(engReady(), reqJSON) }
    fun deals(reqJSON: String): String = guard { EngineViews.dealsJson(engReady(), reqJSON) }

    fun setPlace(name: String, lat: Double, lon: Double, radiusKm: Double) =
        eng().setPlace(name, lat, lon, radiusKm)

    fun sync() = engReady().sync()
    fun cancelSync() = eng().cancelSync()
    fun setQty(code: String, q: Double): Long = engReady().setQty(code, q).toLong()
    fun clearCart() = engReady().clearCart()
    fun toggleStore(id: String) = engReady().toggleStore(id)
    fun setClub(chain: String, on: Boolean) = engReady().setClub(chain, on)
    fun toggleWatch(code: String): Boolean = engReady().toggleWatch(code)
    fun setImages(on: Boolean) = engReady().setImages(on)
    fun geocode(q: String): String = guard { engReady().geocode(q) }

    fun setAI(configJSON: String) = engReady().setAI(configJSON)
    fun testAI(): String = engReady().testAI()
    fun models(): String = engReady().models()
    fun startAI() = eng().startAI()
    fun runEnrich() { Thread { runCatching { engReady().runEnrich() } }.start() }
    fun runCheck() { Thread { runCatching { engReady().runCheck() } }.start() }
    fun stopAI() = eng().stopAI()
    fun clearAI() = engReady().clearAI()

    fun refreshNow(timeoutSec: Int): String = eng().refreshNow(timeoutSec)
    fun changes(): String = guard { EngineViews.changesJson(engReady()) }
    fun watched(): String = guard { EngineViews.watchedJson(engReady()) }
    fun lists(): String = guard { EngineViews.listsJson(engReady()) }
    fun analysis(): String = guard { EngineViews.analysisJson(engReady()) }
    fun logText(): String = guard { EngineViews.logText(engReady()) }
    fun cartText(): String = guard { EngineViews.cartText(engReady()) }
    fun shop(storeID: String): String = guard { EngineViews.shopJson(engReady(), storeID) }
    fun setChecked(code: String, on: Boolean) = engReady().setChecked(code, on)
    fun saveList(name: String) = engReady().saveList(name)
    fun loadList(id: String, merge: Boolean) = engReady().loadList(id, merge)
    fun deleteList(id: String) = engReady().deleteList(id)
    fun parseList(text: String): String = guard { EngineViews.parseListJson(engReady(), text) }
    fun addMany(itemsJSON: String) = engReady().addMany(itemsJSON)
    fun lookup(barcode: String): String = engReady().lookup(barcode)
    fun takeNotices(): String = guard { engReady().takeNotices() }
}
