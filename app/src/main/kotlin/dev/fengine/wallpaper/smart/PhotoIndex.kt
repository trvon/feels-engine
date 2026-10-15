package dev.fengine.wallpaper.smart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.fengine.wallpaper.data.PhotoId
import org.json.JSONObject
import java.io.File

/**
 * The durable, per-photo index: ML Kit labels (computed once per photo, ever) and Gemini Nano
 * verdicts per rule. SQLite, because the owner's library is tens of thousands of photos and a
 * JSON file rewritten every few labels does not survive that.
 *
 * Rows are keyed by [PhotoId.identity], so the picker's URI and the library's URI for the same
 * file share one row. Written only by the workers, which WorkManager serialises by unique name.
 */
// Closeable explicitly: SQLiteOpenHelper only implements AutoCloseable from Android 10 (API 29),
// so `PhotoIndex(...).use { }` threw ClassCastException on Android 9 (found by scripts/smoke-matrix.sh).
class PhotoIndex(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION), java.io.Closeable {
    private val legacy = File(context.filesDir, "label_index.json")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photos (identity TEXT PRIMARY KEY, uri TEXT NOT NULL, labels TEXT NOT NULL, labeled_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE verdicts (rule_key TEXT NOT NULL, identity TEXT NOT NULL, yes INTEGER NOT NULL, PRIMARY KEY (rule_key, identity))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onOpen(db: SQLiteDatabase) {
        if (legacy.exists()) importLegacy(db)
    }

    /** One-time import of the v0.2 JSON file, then it is renamed so this never runs twice. */
    private fun importLegacy(db: SQLiteDatabase) {
        runCatching {
            val root = JSONObject(legacy.readText())
            db.beginTransaction()
            try {
                val l = root.optJSONObject("labels") ?: JSONObject()
                for (uri in l.keys()) {
                    val o = l.getJSONObject(uri)
                    putLabels(db, uri, o.keys().asSequence().associateWith { o.getDouble(it).toFloat() })
                }
                val v = root.optJSONObject("verdicts") ?: JSONObject()
                for (rule in v.keys()) {
                    val o = v.getJSONObject(rule)
                    for (uri in o.keys()) putVerdict(db, rule, uri, o.getBoolean(uri))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        legacy.renameTo(File(legacy.parentFile, "label_index.json.migrated"))
    }

    // Labels

    fun labelsFor(uri: String): Map<String, Float>? =
        readableDatabase.rawQuery("SELECT labels FROM photos WHERE identity = ?", arrayOf(PhotoId.identity(uri))).use { c ->
            if (c.moveToFirst()) decode(c.getString(0)) else null
        }

    fun putLabels(uri: String, labels: Map<String, Float>) = putLabels(writableDatabase, uri, labels)

    private fun putLabels(db: SQLiteDatabase, uri: String, labels: Map<String, Float>) {
        db.insertWithOnConflict("photos", null, ContentValues().apply {
            put("identity", PhotoId.identity(uri)); put("uri", uri)
            put("labels", JSONObject(labels.mapValues { it.value.toDouble() }).toString())
            put("labeled_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Labels for every indexed photo, by identity. One read per scan beats a query per candidate. */
    fun allLabels(): Map<String, Map<String, Float>> =
        readableDatabase.rawQuery("SELECT identity, labels FROM photos", null).use { c ->
            val out = HashMap<String, Map<String, Float>>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = decode(c.getString(1))
            out
        }

    /** Which of [uris] still need labeling, in the given order. */
    fun unlabeled(uris: List<String>): List<String> {
        val have = readableDatabase.rawQuery("SELECT identity FROM photos", null).use { c ->
            val s = HashSet<String>(c.count); while (c.moveToNext()) s += c.getString(0); s
        }
        return uris.filter { PhotoId.identity(it) !in have }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM photos", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Every distinct label seen so far, for prompts that must name labels. */
    fun knownLabels(): Set<String> = allLabels().values.flatMapTo(HashSet()) { it.keys }

    /** Batch several label writes in one transaction; the labeler calls this every few photos. */
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    // Verdicts

    fun verdict(ruleKey: String, uri: String): Boolean? =
        readableDatabase.rawQuery("SELECT yes FROM verdicts WHERE rule_key = ? AND identity = ?", arrayOf(ruleKey, PhotoId.identity(uri))).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else null
        }

    fun verdicts(ruleKey: String): Map<String, Boolean> =
        readableDatabase.rawQuery("SELECT identity, yes FROM verdicts WHERE rule_key = ?", arrayOf(ruleKey)).use { c ->
            val out = HashMap<String, Boolean>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) != 0
            out
        }

    fun putVerdict(ruleKey: String, uri: String, yes: Boolean) = putVerdict(writableDatabase, ruleKey, uri, yes)

    private fun putVerdict(db: SQLiteDatabase, ruleKey: String, uri: String, yes: Boolean) {
        db.insertWithOnConflict("verdicts", null, ContentValues().apply {
            put("rule_key", ruleKey); put("identity", PhotoId.identity(uri)); put("yes", if (yes) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun decode(json: String): Map<String, Float> = runCatching {
        val o = JSONObject(json); o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
    }.getOrDefault(emptyMap())

    companion object {
        const val NAME = "photo_index.db"
        const val VERSION = 1
    }
}
