package am.cyberhayq.chat.storage.media

import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.SecureRandom
import java.util.UUID

/**
 * `tatrak_media_key` in the history database: one random master key per database generation.
 * The database's own key is created only together with the table; a database that lost it never
 * gets another one here (its files would silently be written under a key no backup knows).
 */
object MediaKeyTable {
    const val TABLE = "tatrak_media_key"
    /** The generation of a new database's own key until [bindOwn] learns the real id. */
    const val PENDING = "pending"

    /** [own]: the generation of the database's own key, null when it has none or it is not bound yet. */
    class Keys(val own: String?, val byGeneration: Map<String, ByteArray>)

    /** Only for `DatabaseBackend.onCreate`, inside its transaction. */
    @JvmStatic fun create(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (generation TEXT NOT NULL PRIMARY KEY, " +
                "master_key BLOB NOT NULL CHECK (typeof(master_key) = 'blob' AND length(master_key) = 32), " +
                "own INTEGER NOT NULL CHECK (own IN (0, 1)))")
        db.execSQL("CREATE UNIQUE INDEX ${TABLE}_own ON $TABLE (own) WHERE own = 1") // at most one own key
        val key = ByteArray(MediaCipher.KEY_SIZE).also { SecureRandom().nextBytes(it) }
        db.execSQL("INSERT INTO $TABLE (generation, master_key, own) VALUES (?, ?, 1)", arrayOf<Any>(PENDING, key))
    }

    /**
     * Names the own key after [generation] when that generation is activated. False when the
     * database has no table or no own key. An own key of another generation is an error: renaming
     * it would orphan every file written under the old name.
     */
    @JvmStatic fun bindOwn(db: SupportSQLiteDatabase, generation: String): Boolean {
        requireGeneration(generation)
        if (!exists(db)) return false
        val own = db.query("SELECT generation FROM $TABLE WHERE own = 1").use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        if (own == generation) return true
        check(own == PENDING) { "The media key belongs to another generation" }
        db.execSQL("UPDATE $TABLE SET generation = ? WHERE own = 1 AND generation = ?", arrayOf<Any>(generation, PENDING))
        return true
    }

    /** For the stage of a normal import, which is merged into another database: it keeps no key of its own. */
    @JvmStatic fun removePending(db: SupportSQLiteDatabase) {
        if (exists(db)) db.execSQL("DELETE FROM $TABLE WHERE own = 1 AND generation = ?", arrayOf<Any>(PENDING))
    }

    /** Adds a key brought by a backup; a generation the database already has a key for keeps it. */
    @JvmStatic fun addImported(db: SupportSQLiteDatabase, generation: String, key: ByteArray) {
        requireGeneration(generation)
        require(key.size == MediaCipher.KEY_SIZE)
        db.execSQL("INSERT OR IGNORE INTO $TABLE (generation, master_key, own) VALUES (?, ?, 0)", arrayOf<Any>(generation, key))
    }

    /** Every bound key; a database without the table has none. */
    @JvmStatic fun read(db: SupportSQLiteDatabase): Keys {
        if (!exists(db)) return Keys(null, emptyMap())
        var own: String? = null
        val keys = HashMap<String, ByteArray>()
        db.query("SELECT generation, master_key, own FROM $TABLE").use { rows ->
            while (rows.moveToNext()) {
                val generation = rows.getString(0)
                if (generation == PENDING) continue // not bound: no file can name it
                keys[generation] = rows.getBlob(1)
                if (rows.getInt(2) == 1) own = generation
            }
        }
        return Keys(own, keys)
    }

    private fun exists(db: SupportSQLiteDatabase) =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$TABLE'").use { it.moveToFirst() }

    private fun requireGeneration(id: String) = require(UUID.fromString(id).toString() == id) { "not a generation id" }
}
