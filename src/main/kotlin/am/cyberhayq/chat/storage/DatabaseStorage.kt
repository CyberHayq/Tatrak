package am.cyberhayq.chat.storage

import am.cyberhayq.chat.storage.Durable.durableWrite
import am.cyberhayq.chat.storage.Durable.syncDirectory
import am.cyberhayq.chat.storage.Durable.syncFile
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.storage.media.MediaKeyTable
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import eu.siacs.conversations.Conversations
import eu.siacs.conversations.R
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.persistance.UnifiedPushDatabase
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.utils.Compatibility
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject

/** Owns selection of the complete history/push pair before any application helper can open. */
object DatabaseStorage {
    enum class State { STARTING, WORKING, READY, RECOVERY_REQUIRED }
    data class Generation(val id: String, val directory: File, val key: ByteArray) {
        fun history() = File(directory, "history.db")
        fun push() = File(directory, "push.db")
        fun password(): ByteArray = rawKey(key)
    }

    @JvmField val PRESERVE = DatabaseErrorHandler { _, _ -> /* Never delete on corruption. */ }
    private const val PREFIX = "tatrak-db-"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val gatedActivities = WeakHashMap<Activity, Boolean>()
    @Volatile private var started = false
    @Volatile private var selected: Generation? = null
    @Volatile private var state = State.STARTING
    @Volatile private var error: String? = null

    @JvmStatic fun state() = state
    @JvmStatic fun isReady() = state == State.READY
    @JvmStatic fun error() = error
    /** Id of the selected generation once storage is ready; it stays the same while the process lives. */
    @JvmStatic fun activeGenerationId(): String? = selected?.takeIf { isReady() }?.id
    @JvmStatic fun historyPath(context: Context): String = current().history().absolutePath
    @JvmStatic fun pushPath(context: Context): String = current().push().absolutePath
    @JvmStatic fun password(context: Context): ByteArray = current().password()
    private fun current() = checkNotNull(selected?.takeIf { isReady() }) { "Local storage is not ready" }
    private fun root(context: Context) = File(context.noBackupFilesDir, "tatrak-databases")
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @JvmStatic @Synchronized fun start(context: Context) {
        if (started) return
        started = true
        EncryptedMediaStore.get(context) // made at process start: readers without a Context use EncryptedMediaStore.get()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel("backup") == null) manager.createNotificationChannel(
            NotificationChannel("backup", context.getString(R.string.backup_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
        execute(context) { prepare(context.applicationContext) }
    }

    private fun execute(context: Context, task: () -> Unit) {
        executor.execute {
            try { task() }
            catch (_: Exception) {
                // Exception messages from SQLite can contain SQL/key text; never log them.
                error = context.getString(R.string.tatrak_storage_failed)
                state = State.RECOVERY_REQUIRED
                notifyRequired(context)
            }
        }
    }

    private fun prepare(context: Context) {
        error = null
        System.loadLibrary("sqlcipher")
        val base = root(context)
        ensureDirectory(base)
        // Only a confirmed ENOENT means "no selector": on any other error the read below fails and
        // storage stays in recovery, instead of starting from another generation.
        val pending = File(base, "commit")
        if (!confirmedAbsent(pending)) {
            val candidate = load(context, readSelector(pending))
            verifyPair(candidate)
            validatePair(candidate)
            // A durable commit intent is written only after validation and explicit consent.
            writeSelector(File(base, "active"), candidate.id)
            check(pending.delete())
            syncDirectory(base)
        }
        val active = File(base, "active")
        if (!confirmedAbsent(active)) {
            val generation = load(context, readSelector(active))
            verifyPair(generation)
            selected = generation
            dropPreview(context, generation)
            discardOrphans(context, generation)
            ready(context)
            return
        }
        // A plaintext database of a build before encryption is never opened: no such install exists.
        val artifacts = base.listFiles().orEmpty().any { it.name != "imports" } ||
            listOf("history", "history-wal", "history-journal", "unified-push-distributor",
                "unified-push-distributor-wal", "unified-push-distributor-journal")
                .any { context.getDatabasePath(it).exists() } ||
            store().aliases().toList().any { it.startsWith(PREFIX) }
        check(!artifacts) { "Ambiguous initial storage" }
        val generation = createGeneration(context)
        initializeEmpty(context, generation)
        activate(context, generation)
    }

    @JvmStatic fun open(file: File, password: ByteArray): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, password, null, SQLiteDatabase.OPEN_READWRITE, PRESERVE, null)
            .apply { execSQL("PRAGMA temp_store=MEMORY") }

    private fun validate(db: SQLiteDatabase) {
        check(scalar(db, "PRAGMA integrity_check") == "ok")
        db.rawQuery("PRAGMA cipher_integrity_check", null).use { check(!it.moveToFirst()) }
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
        if (scalar(db, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='messages_index'") != "0") {
            db.execSQL("INSERT INTO messages_index(messages_index) VALUES('integrity-check')")
        }
    }

    private fun verifyPair(generation: Generation) {
        check(generation.history().isFile && generation.push().isFile)
        // Startup only checks that the selected keyed schema opens; full checks precede activation.
        open(generation.history(), generation.password()).use { check(it.version in 1..DatabaseBackend.DATABASE_VERSION); scalar(it, "SELECT count(*) FROM accounts") }
        open(generation.push(), generation.password()).use { check(it.version == 1); scalar(it, "SELECT count(*) FROM push") }
    }

    @JvmStatic fun createGeneration(context: Context): Generation {
        System.loadLibrary("sqlcipher")
        val id = UUID.randomUUID().toString()
        val directory = File(root(context), id)
        ensureDirectory(directory)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        check(!store().containsAlias(PREFIX + id))
        generator.init(KeyGenParameterSpec.Builder(PREFIX + id,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey())
        cipher.updateAAD("tatrak-db-key-v1".toByteArray(Charsets.UTF_8))
        val wrapped = byteArrayOf(1) + cipher.iv + cipher.doFinal(key)
        check(wrapped.size == 61 && cipher.iv.size == 12)
        durableWrite(File(directory, "key"), wrapped)
        val generation = load(context, id)
        check(MessageDigest.isEqual(key, generation.key))
        return generation
    }

    private fun load(context: Context, id: String): Generation {
        require(UUID.fromString(id).toString() == id)
        val directory = File(root(context), id)
        check(directory.canonicalFile.parentFile == root(context).canonicalFile && directory.canonicalFile.name == id)
        val blob = File(directory, "key").readBytes()
        require(blob.size == 61 && blob[0] == 1.toByte())
        val secret = store().getKey(PREFIX + id, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, blob.copyOfRange(1, 13)))
        cipher.updateAAD("tatrak-db-key-v1".toByteArray(Charsets.UTF_8))
        val key = cipher.doFinal(blob, 13, 48)
        require(key.size == 32)
        return Generation(id, directory, key)
    }

    @JvmStatic fun importGeneration(context: Context, id: String): Generation {
        check(id != selected?.id) { "Not an import stage" }
        // commit before active, as mayName reads them: prepare() writes active before it deletes commit
        check(!Selector.mayName(root(context), id, confirmedAbsent)) { "Not a disposable stage" }
        return load(context, id)
    }

    @JvmStatic fun discardImport(context: Context, id: String) {
        val generation = importGeneration(context, id)
        check(generation.directory.deleteRecursively())
        store().deleteEntry(PREFIX + id)
        syncDirectory(root(context))
    }

    /**
     * Import stages left by a failed, interrupted or never answered import hold a whole database
     * copy for good. Remove every such stage, unless something in it changed within the last hour (an
     * import still running or waiting for consent). A generation that was once active (it has
     * `metadata`) is kept: "Start fresh" promises to retain the old files and their key.
     */
    private fun discardOrphans(context: Context, selected: Generation) {
        val base = root(context)
        val now = System.currentTimeMillis()
        // An unreadable selector means nothing can be told apart: keep everything.
        val kept = runCatching {
            listOf("active", "commit").map { File(base, it) }.filter { it.exists() }.map { readSelector(it) }.toSet()
        }.getOrNull()?.plus(selected.id) ?: return
        for (directory in base.listFiles().orEmpty()) {
            val id = directory.name
            if (!directory.isDirectory || id in kept || !isOrphan(directory, now, confirmedAbsent)) continue
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) continue
            try { discardImport(context, id) }
            catch (_: Exception) {
                // An unreadable key leaves an inactive copy to remove; a refusal because a selector
                // may name it by now is not a permission to delete.
                if (Selector.mayName(base, id, confirmedAbsent)) continue
                directory.deleteRecursively()
                runCatching { store().deleteEntry(PREFIX + id) }
            }
        }
    }

    /** [absent]: true only when a file is known not to exist; an unreadable `metadata` keeps the generation. */
    @JvmStatic fun isOrphan(directory: File, now: Long, absent: (File) -> Boolean): Boolean =
        absent(File(directory, "metadata")) &&
            directory.walkTopDown().none { now - it.lastModified() < 60 * 60 * 1000L }

    @JvmStatic fun initializeEmpty(context: Context, generation: Generation) {
        DatabaseBackend.isolated(context, generation.history().path, generation.password()).use { it.writableDatabase }
        UnifiedPushDatabase.isolated(context, generation.push().path, generation.password()).use { it.writableDatabase }
    }

    @JvmStatic @Synchronized fun recoverImport(context: Context, generation: Generation) {
        val admitted = state == State.RECOVERY_REQUIRED
        try {
            check(admitted)
            state = State.WORKING
            activate(context, generation)
        } catch (failure: Exception) {
            // Decided here, before the state lets a retry or a fresh start change the selectors.
            // Also for a refused call: a second commit of a stage that is already active must
            // not look like a rollback case.
            val past = isPastCommit(context, generation)
            if (admitted) state = State.RECOVERY_REQUIRED
            throw if (past) PastCommitException(failure) else failure
        }
    }

    /** [recoverImport] failed after a selector may name the generation: the next start activates it, so nothing is rolled back. */
    class PastCommitException(cause: Exception) : Exception(cause)

    /** Once a selector may name [generation], the next start activates it: activation can only finish forward. */
    @JvmStatic fun isPastCommit(context: Context, generation: Generation) =
        Selector.mayName(root(context), generation.id, confirmedAbsent)

    // Only a confirmed ENOENT counts as absent: File.exists() also reads false on an I/O error.
    // A property so that JVM tests can replace it: Robolectric's Os.stat does not report ENOENT.
    @Volatile internal var confirmedAbsent: (File) -> Boolean =
        { file -> try { Os.stat(file.path); false } catch (e: ErrnoException) { e.errno == OsConstants.ENOENT } }

    @JvmStatic fun validatePair(generation: Generation) {
        open(generation.history(), generation.password()).use(::validate)
        open(generation.push(), generation.password()).use(::validate)
    }

    private fun activate(context: Context, generation: Generation) {
        // Before the files are synced and any selector names the generation: its media key gets
        // the generation's id (a database without the key table stays without one).
        open(generation.history(), generation.password()).use { MediaKeyTable.bindOwn(it, generation.id) }
        validatePair(generation)
        syncFile(generation.history()); syncFile(generation.push())
        durableWrite(File(generation.directory, "metadata"),
            JSONObject().put("version", 1).toString().toByteArray())
        syncDirectory(generation.directory)
        val base = root(context)
        writeSelector(File(base, "commit"), generation.id)
        writeSelector(File(base, "active"), generation.id)
        check(File(base, "commit").delete()); syncDirectory(base)
        verifyPair(generation)
        selected = generation
        dropPreview(context, generation)
        ready(context)
    }

    // A recovery import's stage becomes active with its plaintext preview (JID, account id); nothing reads it after.
    private fun dropPreview(context: Context, generation: Generation) {
        val preview = File(generation.directory, "preview")
        if (confirmedAbsent(preview)) return
        // not fatal: the warning shows and the next start tries again
        val removed = preview.delete() && runCatching { syncDirectory(generation.directory) }.isSuccess
        if (!removed) error = context.getString(R.string.tatrak_storage_cleanup)
    }

    @JvmStatic @Synchronized fun retry(context: Context) {
        if (state != State.RECOVERY_REQUIRED) return
        state = State.STARTING
        execute(context) { prepare(context) }
    }

    @JvmStatic @Synchronized fun freshStart(context: Context) {
        check(state == State.RECOVERY_REQUIRED)
        state = State.WORKING
        execute(context) {
            val generation = createGeneration(context)
            initializeEmpty(context, generation)
            activate(context, generation)
        }
    }

    private fun ready(context: Context) {
        state = State.READY
        main.post {
            if (error == null) context.getSystemService(NotificationManager::class.java).cancel(7314)
            else notifyRequired(context)
            Conversations.getInstance(context).resetAccounts()
            if (Conversations.getInstance(context).hasEnabledAccount()) {
                Compatibility.startService(context, Intent(context, XmppConnectionService::class.java).setAction("ui"))
            }
            // A gated activity gets no second onStart when the gate screen closes before the activity
            // stops (a cold start where storage opens within milliseconds): it would stay blank. Start it over.
            recreateGated()
        }
    }

    internal fun recreateGated() {
        val waiting = gatedActivities.keys.toList()
        gatedActivities.clear()
        waiting.forEach { if (!it.isFinishing) it.recreate() }
    }

    @JvmStatic fun showGate(activity: Activity): Boolean {
        if (isReady()) { gatedActivities.remove(activity); return false }
        if (activity is DatabaseStorageActivity || activity is eu.siacs.conversations.ui.ImportBackupActivity) return false
        if (gatedActivities.put(activity, true) == null) {
            // Keep the original activity and its temporary share-URI grants alive behind the gate.
            activity.startActivity(Intent(activity, DatabaseStorageActivity::class.java)
                .putExtra("tatrak_return_to_caller", true))
        }
        return true
    }

    @JvmStatic fun notifyRequired(context: Context) {
        runCatching { notifyStorage(context) }
    }

    private fun notifyStorage(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("tatrak-storage", context.getString(R.string.tatrak_storage_title), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, 7314, Intent(context, DatabaseStorageActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(7314, NotificationCompat.Builder(context, "tatrak-storage")
            .setSmallIcon(R.drawable.ic_notifications_24dp).setContentTitle(context.getString(R.string.tatrak_storage_title))
            .setContentText(if (isReady()) error else context.getString(R.string.tatrak_storage_action)).setContentIntent(intent).setAutoCancel(true).build())
    }

    private fun writeSelector(file: File, id: String) = durableWrite(file, Selector.encode(id))
    private fun readSelector(file: File) = Selector.decode(file.readBytes())
    private fun ensureDirectory(file: File) {
        check(file.isDirectory || file.mkdirs())
        file.parentFile?.let(::syncDirectory)
    }
    private fun scalar(db: SQLiteDatabase, sql: String): String = db.rawQuery(sql, null).use {
        check(it.moveToFirst()); it.getString(0)
    }
    @JvmStatic fun rawKey(key: ByteArray): ByteArray {
        require(key.size == 32)
        return ("x'" + key.joinToString("") { "%02x".format(it.toInt() and 255) } + "'").toByteArray(Charsets.US_ASCII)
    }
}

/** Small platform-independent selector codec so torn/unknown selectors never select data. */
object Selector {
    fun encode(id: String): ByteArray {
        require(UUID.fromString(id).toString() == id)
        val payload = "1:$id"
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$payload:$checksum".toByteArray(Charsets.US_ASCII)
    }
    fun decode(bytes: ByteArray): String {
        require(bytes.size == 103)
        val id = String(bytes, Charsets.US_ASCII).split(':').let { require(it.size == 3 && it[0] == "1"); it[1] }
        require(MessageDigest.isEqual(bytes, encode(id)))
        return id
    }
    /** True if the `commit` or `active` selector in [base] names [id]; an unreadable one counts as naming it. */
    fun mayName(base: File, id: String, absent: (File) -> Boolean): Boolean =
        listOf("commit", "active").map { File(base, it) }
            .filterNot(absent).any { runCatching { decode(it.readBytes()) == id }.getOrDefault(true) }
}
