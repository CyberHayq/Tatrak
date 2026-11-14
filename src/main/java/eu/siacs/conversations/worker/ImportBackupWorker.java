package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import androidx.sqlite.db.SupportSQLiteDatabase;
import am.cyberhayq.chat.storage.DatabaseStorage;
import am.cyberhayq.chat.storage.Durable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingInputStream;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.json.Services;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import javax.crypto.BadPaddingException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

public class ImportBackupWorker extends Worker {

    public static final String TAG_IMPORT_BACKUP = "tag-import-backup";

    // Tatrak: Data only carries a key into PASSWORDS; WorkManager keeps input Data on disk in plain
    // text, so the backup password itself stays in memory
    private static final String DATA_KEY_PASSWORD_KEY = "password-key";
    private static final Map<String, String> PASSWORDS = new ConcurrentHashMap<>();
    // Tatrak: a password whose worker never starts (cancelled, enqueue failed) is dropped after this
    @VisibleForTesting static final long PASSWORD_LIFETIME_MINUTES = 10;
    private static final String DATA_KEY_URI = "uri";
    private static final String DATA_KEY_INCLUDE_OMEMO = "omemo";
    private static final String DATA_KEY_SKIPPED_ROWS = "skipped-rows";

    private static final Collection<String> OMEMO_TABLE_LIST =
            Arrays.asList(
                    SQLiteAxolotlStore.PREKEY_TABLENAME,
                    SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                    SQLiteAxolotlStore.SESSION_TABLENAME,
                    SQLiteAxolotlStore.IDENTITIES_TABLENAME);

    private static final List<String> TABLE_ALLOW_LIST =
            new ImmutableList.Builder<String>()
                    .add(Account.TABLENAME, Conversation.TABLENAME, Message.TABLENAME)
                    .addAll(OMEMO_TABLE_LIST)
                    .build();

    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[a-zA-Z_]+$");

    private static final int NOTIFICATION_ID = 21;

    private final String password;
    private final Uri uri;
    private final boolean includeOmemo;
    // Tatrak: rows (other than accounts) skipped because they could not be inserted, per table
    private final Map<String, Integer> skippedRows = new HashMap<>();
    private final java.util.SortedMap<String, Integer> omissions = new java.util.TreeMap<>();
    private Map<String, java.util.Set<String>> columns = new HashMap<>();
    private String importAccount;
    private String previewId;
    @VisibleForTesting boolean sourceMonocles;
    // Tatrak: only a Tatrak backup may carry Tatrak's message types (7, 8); other apps number theirs differently
    @VisibleForTesting boolean sourceTatrak;
    // Tatrak: media rows (monocles' `files` format): backup path -> final attachment file, and the
    // stage folder the chunks are written to until the import is accepted
    private final Map<String, File> stagedFiles = new HashMap<>();
    @VisibleForTesting File stagedFilesDirectory;
    // Tatrak: staged media is encrypted for this generation (the active one; in a recovery import
    // the stage itself) with its key; one writer takes a file's chunks in order
    @VisibleForTesting String targetGeneration;
    @VisibleForTesting byte[] targetKey;
    // generations whose key the backup carries or the target holds: a tatrak_ path is kept only for one of them
    @VisibleForTesting final java.util.Set<String> knownGenerations = new java.util.HashSet<>();
    private am.cyberhayq.chat.storage.media.MediaWriter stagedWriter;
    private String stagedPath;
    private long stagedSequence;

    public ImportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        this.password = takePassword(inputData);
        this.uri = Uri.parse(inputData.getString(DATA_KEY_URI));
        this.includeOmemo = inputData.getBoolean(DATA_KEY_INCLUDE_OMEMO, true);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Tatrak: the in-memory password is gone (process died, the worker was re-created after a
        // stop, or the password expired); the activity asks for it again
        if (getInputData().getString("tatrak_stage") != null) {
            // Tatrak: the commit is the longest step; keep it a foreground job like the import
            setForegroundAsync(getForegroundInfo());
            setProgressAsync(new Data.Builder().putInt(PROGRESS_PERCENT, SAVING).build());
            return commitPreview();
        }
        if (password == null) {
            return Result.failure(
                    new Data.Builder()
                            .putString("reason", Reason.PASSWORD_MISSING.toString())
                            .putString(DATA_KEY_URI, uri.toString())
                            .putBoolean(DATA_KEY_INCLUDE_OMEMO, includeOmemo)
                            .build());
        }
        setForegroundAsync(getForegroundInfo());
        final Result result;
        try {
            result = importBackup(this.uri, this.password);
        } catch (final FileNotFoundException e) {
            return failure(Reason.FILE_NOT_FOUND);
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "error restoring backup");
            final Throwable throwable = e.getCause();
            if (throwable instanceof BadPaddingException || e instanceof ZipException) {
                return failure(Reason.DECRYPTION_FAILED);
            } else {
                return failure(Reason.GENERIC);
            }
        } finally {
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }

        return result;
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        final var notification = createImportBackupNotification(1, 0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification);
        }
    }

    private Result importBackup(final Uri uri, final String password)
            throws IOException, InvalidKeySpecException {
        final var context = getApplicationContext();
        final var stage = DatabaseStorage.createGeneration(context);
        previewId = stage.getId();
        stagedFilesDirectory = new File(stage.getDirectory(), "files");
        boolean previewCreated = false;
        try {
            DatabaseStorage.initializeEmpty(context, stage);
            final Stopwatch stopwatch = Stopwatch.createStarted();
            try (final var helper = DatabaseBackend.isolated(context, stage.history().getPath(), stage.password())) {
                final SupportSQLiteDatabase db = helper.getWritableDatabase();
                bindMediaTarget(context, db, stage);
                final String path = uri.getPath();
                final long fileSize;
                if ("file".equals(uri.getScheme()) && path != null) {
                    fileSize = new File(path).length();
                } else {
                    try (final Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                        final int column = cursor == null ? -1 : cursor.getColumnIndex(OpenableColumns.SIZE);
                        fileSize = cursor != null && column >= 0 && cursor.moveToFirst() ? cursor.getLong(column) : 0;
                    }
                }
                try (final InputStream input = "file".equals(uri.getScheme()) && path != null
                        ? new FileInputStream(path) : context.getContentResolver().openInputStream(uri)) {
                    if (input == null) return failure(Reason.FILE_NOT_FOUND);
                    final Result result = importBackup(db, input, fileSize, password, stopwatch);
                    previewCreated = result instanceof Result.Success;
                    return result;
                }
            }
        } finally {
            if (!previewCreated) {
                try { DatabaseStorage.discardImport(context, previewId); }
                catch (Exception ignored) { /* Inactive encrypted stage; never touch active data. */ }
            }
        }
    }

    private Result importBackup(
            final SupportSQLiteDatabase db,
            final InputStream inputStream,
            final long fileSize,
            final String password,
            final Stopwatch stopwatch)
            throws IOException, InvalidKeySpecException {
        final CountingInputStream countingInputStream = new CountingInputStream(inputStream);
        final DataInputStream dataInputStream = new DataInputStream(countingInputStream);
        final BackupFileHeader backupFileHeader = BackupFileHeader.read(dataInputStream);
        Log.d(Config.LOGTAG, backupFileHeader.toString());

        // our own backups: header version TATRAK_VERSION or app name "Tatrak"
        sourceTatrak = backupFileHeader.getVersion() == BackupFileHeader.TATRAK_VERSION
                || backupFileHeader.getApp().toLowerCase(java.util.Locale.ROOT).contains("tatrak");
        sourceMonocles = backupFileHeader.getApp().toLowerCase(java.util.Locale.ROOT).contains("monocles");
        final var accounts = DatabaseStorage.isReady()
                ? DatabaseBackend.getInstance(getApplicationContext()).getAccountAddresses()
                : java.util.List.<Jid>of();

        if (QuickConversationsService.isQuicksy() && !accounts.isEmpty()) {
            return failure(Reason.ACCOUNT_ALREADY_EXISTS);
        }

        if (accounts.contains(backupFileHeader.getJid())) {
            return failure(Reason.ACCOUNT_ALREADY_EXISTS);
        }

        // Tatrak: the header's version picks the key derivation
        final byte[] key =
                backupFileHeader.getVersion() >= BackupFileHeader.VERSION
                        ? ExportBackupWorker.getKey(password, backupFileHeader.getSalt())
                        : ExportBackupWorker.getLegacyKey(password, backupFileHeader.getSalt());

        final AEADBlockCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(
                false, new AEADParameters(new KeyParameter(key), 128, backupFileHeader.getIv()));
        final CipherInputStream cipherInputStream =
                new CipherInputStream(countingInputStream, cipher);

        final GZIPInputStream gzipInputStream = new GZIPInputStream(cipherInputStream);
        final BufferedReader reader =
                new BufferedReader(new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8));
        final JsonReader jsonReader = new JsonReader(reader);
        if (jsonReader.peek() == JsonToken.BEGIN_ARRAY) {
            jsonReader.beginArray();
        } else {
            throw new IllegalStateException("Backup file did not begin with array");
        }
        try {
            importRows(
                    db,
                    jsonReader,
                    cipherInputStream,
                    () -> {
                        importRow(db, jsonReader, backupFileHeader.getJid(), password);
                        updateImportBackupNotification(fileSize, countingInputStream.getCount());
                    });
            finishStagedFile();
        } finally {
            abortStagedFile(); // a failed import publishes no file
        }
        if (!skippedRows.isEmpty()) {
            Log.w(Config.LOGTAG, "skipped rows that could not be inserted: " + skippedRows);
        }
        final Jid jid = backupFileHeader.getJid();
        final Cursor countCursor =
                db.query(
                        "select count(messages.uuid) from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid join accounts on"
                                + " conversations.accountUuid=accounts.uuid where"
                                + " accounts.username=? and accounts.server=?",
                        new String[] {jid.getLocal(), jid.getDomain().toString()});
        countCursor.moveToFirst();
        final int count = countCursor.getInt(0);
        Log.d(Config.LOGTAG, String.format("restored %d messages in %s", count, stopwatch.stop()));
        countCursor.close();
        // Tatrak: the authenticated destination stays isolated until explicit foreground consent.
        if (importAccount == null) throw new IOException("Backup has no account");
        try (final Cursor c = db.query("PRAGMA foreign_key_check")) {
            if (c.moveToFirst()) throw new IOException("Invalid account references in backup");
        }
        try (final Cursor c = db.query("SELECT 1 FROM conversations WHERE accountUuid IS NOT ? LIMIT 1",
                new Object[] {importAccount})) {
            if (c.moveToFirst()) throw new IOException("Foreign conversation ownership");
        }
        for (final String table : OMEMO_TABLE_LIST) {
            try (final Cursor c = db.query("SELECT 1 FROM \"" + table + "\" WHERE account IS NOT ? LIMIT 1",
                    new Object[] {importAccount})) {
                if (c.moveToFirst()) throw new IOException("Foreign crypto ownership");
            }
        }
        try (final Cursor c = db.query("SELECT 1 FROM messages WHERE conversationUuid IS NULL LIMIT 1")) {
            if (c.moveToFirst()) throw new IOException("Missing message ownership");
        }
        final var stage = DatabaseStorage.importGeneration(getApplicationContext(), previewId);
        DatabaseStorage.validatePair(stage);
        final var report = new org.json.JSONObject();
        try {
            report.put("version", 2).put("jid", jid.toString()).put("account", importAccount);
            report.put("omissions", previewText());
            // the stage's media is bound to this target and mode (item 28e)
            report.put("target", targetGeneration).put("recovery", !DatabaseStorage.isReady());
        } catch (org.json.JSONException e) { throw new IOException("Preview metadata", e); }
        Durable.durableWrite(new File(stage.getDirectory(), "preview"),
                report.toString().getBytes(StandardCharsets.UTF_8));
        return Result.success(new Data.Builder().putString("tatrak_preview", previewId).build());
    }

    /**
     * Tatrak: a recovery import's stage becomes the active generation, so its media is named after
     * the stage and encrypted with the stage's own key; a normal import is merged into the active
     * database, so its stage keeps no key of its own and its media belongs to the active generation.
     */
    private void bindMediaTarget(final Context context, final SupportSQLiteDatabase db, final DatabaseStorage.Generation stage) {
        final am.cyberhayq.chat.storage.media.MediaKeyTable.Keys keys;
        if (DatabaseStorage.isReady()) {
            am.cyberhayq.chat.storage.media.MediaKeyTable.removePending(db);
            targetGeneration = DatabaseStorage.activeGenerationId();
            keys = am.cyberhayq.chat.storage.media.MediaKeyTable.read(DatabaseBackend.getInstance(context).getReadableDatabase());
            knownGenerations.addAll(keys.getByGeneration().keySet());
        } else {
            am.cyberhayq.chat.storage.media.MediaKeyTable.bindOwn(db, stage.getId());
            targetGeneration = stage.getId();
            keys = am.cyberhayq.chat.storage.media.MediaKeyTable.read(db);
        }
        // a database that lost its own key gets no new one: a backup with media then fails to import
        targetKey = targetGeneration.equals(keys.getOwn()) ? keys.getByGeneration().get(targetGeneration) : null;
        knownGenerations.add(targetGeneration);
    }

    /** Tatrak: a key the backup carries, kept as an imported key (own = 0); a generation already known keeps its key. */
    private void importMediaKey(final SupportSQLiteDatabase db, final JsonReader jsonReader) throws IOException {
        if (!"values".equals(jsonReader.nextName())) throw new IllegalStateException("Expected key 'values'");
        String generation = null;
        byte[] key = null;
        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            switch (jsonReader.nextName()) {
                case "generation" -> generation = jsonReader.nextString();
                case "master_key" -> key = com.google.common.io.BaseEncoding.base16().lowerCase().decode(jsonReader.nextString());
                default -> jsonReader.skipValue();
            }
        }
        jsonReader.endObject();
        if (generation == null || key == null) throw new IOException("Incomplete media key");
        am.cyberhayq.chat.storage.media.MediaKeyTable.addImported(db, generation, key);
        knownGenerations.add(generation);
    }

    private void omitted(int label, Object... arguments) {
        omissions.merge(getApplicationContext().getString(label, arguments), 1, Integer::sum);
    }

    // monocles keeps a file's url|size|width|height|runtime in fileParams (same format as
    // Conversations' file body) and may use the body for a caption; Conversations reads the body.
    private void restoreFileBody(final ContentValues values) {
        final Integer type = values.getAsInteger(Message.TYPE);
        final String params = values.getAsString("fileParams");
        if (type == null
                || (type != Message.TYPE_IMAGE
                        && type != Message.TYPE_FILE
                        && type != Message.TYPE_PRIVATE_FILE)
                || Strings.isNullOrEmpty(params)) {
            return;
        }
        final String url = params.split("\\|", 2)[0];
        // shortcut: size-only params carry no link; a SIMS-only file (url just in payloads) stays lost
        if (eu.siacs.conversations.http.URL.tryParse(url) == null) return;
        final String body = values.getAsString(Message.BODY);
        if (!Strings.isNullOrEmpty(body) && !body.split("\\|", 2)[0].equals(url)) {
            omitted(R.string.tatrak_backup_omit_caption);
        }
        values.put(Message.BODY, params);
        values.remove("fileParams");
    }

    /**
     * A chunk of a file in monocles' `files` format ({path, sequence, content: base64 of up to
     * 1 MB}; Tatrak's media backups write the same). The file is encrypted into the stage under a
     * random name, so the backup chooses no path; the commit moves it to the attachments folder.
     * One writer takes a file's chunks in order: a gap, a duplicate or a chunk of another file in
     * between fails the import, so a file is never restored in part.
     * Reads the format monocles chat writes (commit e7d071a7); the parsing follows upstream readRow.
     */
    private void importFile(final JsonReader jsonReader) throws IOException {
        if (!"values".equals(jsonReader.nextName())) throw new IllegalStateException("Expected key 'values'");
        String path = null;
        long sequence = -1;
        byte[] content = null;
        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            switch (jsonReader.nextName()) {
                case "path" -> path = jsonReader.nextString();
                case "sequence" -> sequence = jsonReader.nextLong();
                case "content" -> content = android.util.Base64.decode(jsonReader.nextString(), android.util.Base64.NO_WRAP);
                default -> jsonReader.skipValue();
            }
        }
        jsonReader.endObject();
        if (Strings.isNullOrEmpty(path) || content == null) return;
        if (path.contains("/avatars/")) return; // avatars come again from the server
        if (!path.equals(stagedPath)) {
            if (sequence != 0 || stagedFiles.containsKey(path)) throw new IOException("Backup file chunks out of order");
            finishStagedFile();
            if (targetKey == null) throw new IOException("No media key to import files with");
            stagedWriter = am.cyberhayq.chat.storage.media.EncryptedMediaStore.get(getApplicationContext())
                    .createStaged(stagedFilesDirectory, targetGeneration, targetKey, path.substring(path.lastIndexOf('/') + 1));
            stagedPath = path;
            stagedSequence = 0;
            stagedFiles.put(path, new File(attachments(getApplicationContext()), stagedWriter.getTarget().getName()));
        } else if (sequence != stagedSequence) {
            throw new IOException("Backup file chunks out of order");
        }
        stagedWriter.getOutputStream().write(content);
        stagedSequence++;
    }

    /** Completes the file whose chunks came last; nothing is left half written in the stage. */
    @VisibleForTesting
    void finishStagedFile() throws IOException {
        if (stagedWriter == null) return;
        final var writer = stagedWriter;
        stagedWriter = null;
        stagedPath = null;
        writer.commit();
    }

    /** After a failed import: the file whose chunks came last is dropped (nothing after a commit). */
    @VisibleForTesting
    void abortStagedFile() {
        if (stagedWriter != null) stagedWriter.abort();
        stagedWriter = null;
        stagedPath = null;
    }

    private static File attachments(final Context context) {
        return new File(context.getFilesDir(), "attachments"); // FileBackend.getInternalStorageLocation
    }

    /**
     * Tatrak: publishes every staged file durably in the attachment folder before the rows are
     * committed; one failure fails the import (the caller returns what was moved and discards the stage).
     */
    @VisibleForTesting
    static void moveStagedFiles(final File staged, final File attachments) throws IOException {
        final File[] files = staged.listFiles();
        if (files == null || files.length == 0) return;
        if (!attachments.isDirectory()) {
            if (!attachments.mkdirs() && !attachments.isDirectory()) throw new IOException("No attachments folder");
            Durable.syncDirectory(attachments.getParentFile()); // a new folder must survive a power loss too
        }
        for (final File file : files) {
            Durable.rename(file, new File(attachments, file.getName()));
        }
        Durable.syncDirectory(attachments);
        Log.d(Config.LOGTAG, "moved " + files.length + " imported files");
    }

    // A file that failed to move would otherwise stay inside the active generation for good.
    // Its message offers the download again, as after a normal import.
    private static void deleteLeftovers(final File stagedFiles) {
        final File[] left = stagedFiles.listFiles();
        if (left != null) for (final File file : left) file.delete();
        stagedFiles.delete();
    }

    // Rolled back: the media goes back to the stage, which a retry imports again.
    private static void returnStagedFiles(final File[] moved, final File attachments) {
        if (moved == null) return;
        for (final File file : moved) new File(attachments, file.getName()).renameTo(file);
    }

    /**
     * Tatrak: the app later shows, re-shares and deletes the file a message points at, so a backup
     * may only point into the attachments folder or shared storage; monocles' portable paths
     * (${FILES}/...) are resolved first. Imported media gets its staged file.
     */
    private void restoreFilePath(final ContentValues values) {
        final String path = values.getAsString(Message.RELATIVE_FILE_PATH);
        if (Strings.isNullOrEmpty(path)) return;
        final File staged = stagedFiles.get(path);
        if (staged != null) {
            values.put(Message.RELATIVE_FILE_PATH, staged.getAbsolutePath());
            values.put(Message.SHARED_STORAGE, 0);
            return;
        }
        final String confined = confinedPath(getApplicationContext(), path, knownGenerations);
        if (confined == null) {
            values.putNull(Message.RELATIVE_FILE_PATH);
        } else {
            values.put(Message.RELATIVE_FILE_PATH, confined);
            // only files in our own folder may be deleted with the message or the account
            if (confined.startsWith("/") && !confined.startsWith(attachments(getApplicationContext()).getAbsolutePath() + "/")) {
                values.put(Message.SHARED_STORAGE, 1);
            }
        }
    }

    @VisibleForTesting
    static String confinedPath(final Context context, final String path, final java.util.Set<String> generations) {
        final String resolved;
        if (path.startsWith("${FILES}")) resolved = context.getFilesDir().getAbsolutePath() + path.substring(8);
        else if (path.startsWith("${CACHE}")) resolved = context.getCacheDir().getAbsolutePath() + path.substring(8);
        else if (path.startsWith("${EXTERNAL}")) resolved = android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + path.substring(11);
        else if (path.startsWith("${DOCUMENTS}")) resolved = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS).getAbsolutePath() + path.substring(12);
        else if (path.startsWith("${")) return null;
        else resolved = path;
        if (!resolved.startsWith("/")) {
            // a legacy bare file name, resolved against the legacy media folders
            return resolved.contains("/") || resolved.contains("\\") || resolved.startsWith(".") ? null : resolved;
        }
        try {
            final String canonical = new File(resolved).getCanonicalPath();
            if (canonical.startsWith(attachments(context).getCanonicalPath() + File.separator)) {
                // an encrypted file opens only with the key of the generation in its name; a
                // tatrak_ name of any other form opens never
                final String name = canonical.substring(canonical.lastIndexOf('/') + 1);
                if (!name.startsWith("tatrak_")) return resolved;
                final String generation = am.cyberhayq.chat.storage.media.EncryptedMediaStore.generationOf(name);
                return generation != null && generations.contains(generation) ? resolved : null;
            }
            if (canonical.startsWith(android.os.Environment.getExternalStorageDirectory().getCanonicalPath() + File.separator)) return resolved;
        } catch (final IOException e) {
            return null;
        }
        return null;
    }

    // monocles keeps the XEP-0461 reply and its XEP-0428 fallback range as XML in payloads;
    // Tatrak keeps them as ReplyTo in replyTo. Read like MessageParser.replyTo does.
    @VisibleForTesting
    static void restoreReplyTo(final ContentValues values) {
        final String payloads = values.getAsString("payloads");
        if (Strings.isNullOrEmpty(payloads) || values.getAsString(Message.REPLY_TO) != null) return;
        final im.conversations.android.xmpp.model.stanza.Message packet;
        try {
            packet = im.conversations.android.xml.XmlElementReader.read(
                    "<message xmlns='jabber:client'>" + payloads + "</message>",
                    im.conversations.android.xmpp.model.stanza.Message.class);
        } catch (final IOException | RuntimeException e) {
            return; // unreadable payloads stay an omission, the body keeps the quote
        }
        final var reply = packet.getExtension(im.conversations.android.xmpp.model.reply.Reply.class);
        final String id = reply == null ? null : reply.getId();
        if (id == null) return;
        final var range = im.conversations.android.xmpp.model.fallback.Fallback.get(packet,
                im.conversations.android.xmpp.model.reply.Reply.class,
                im.conversations.android.xmpp.model.fallback.Body.class);
        final var replyTo = range.isPresent()
                        && range.get() instanceof im.conversations.android.xmpp.model.fallback.Fallback.StartEndRange r
                ? new eu.siacs.conversations.entities.ReplyTo(id, reply.getTo(), r.start(), r.end())
                : new eu.siacs.conversations.entities.ReplyTo(id, reply.getTo(), -1, -1);
        values.put(Message.REPLY_TO, eu.siacs.conversations.entities.ReplyTo.toString(replyTo));
    }

    private static boolean positive(final ContentValues values, final String name) {
        final Long value = values.getAsLong(name);
        return value != null && value > 0;
    }

    @VisibleForTesting
    String previewText() {
        final var lines = new StringBuilder();
        omissions.forEach((name, count) -> lines.append(name).append(": ").append(count).append('\n'));
        skippedRows.forEach((name, count) -> lines.append(getApplicationContext().getString(R.string.tatrak_backup_omit_conflict, name)).append(": ").append(count).append('\n'));
        return lines.toString();
    }

    public static Data commitData(final String id) {
        return new Data.Builder().putString("tatrak_stage", id).putString(DATA_KEY_URI, "").build();
    }

    public static String previewId(final Data output) { return output.getString("tatrak_preview"); }

    public static String previewDetails(final Context context, final String id) {
        try {
            final var stage = DatabaseStorage.importGeneration(context, id);
            final String text = new String(com.google.common.io.Files.toByteArray(
                    new File(stage.getDirectory(), "preview")), StandardCharsets.UTF_8);
            return new org.json.JSONObject(text).getString("omissions");
        } catch (Exception e) { throw new IllegalStateException("Import preview unavailable"); }
    }

    private Result commitPreview() {
        final var context = getApplicationContext();
        final String id = getInputData().getString("tatrak_stage");
        String step = "prepare";
        try {
            final var stage = DatabaseStorage.importGeneration(context, id);
            final var report = new org.json.JSONObject(new String(com.google.common.io.Files.toByteArray(
                    new File(stage.getDirectory(), "preview")), StandardCharsets.UTF_8));
            if (report.getInt("version") != 2) return failure(Reason.GENERIC);
            final Jid jid = Jid.of(report.getString("jid"));
            // Tatrak: the stage's media was named for one target in one mode (item 28e)
            final boolean recovery = report.getBoolean("recovery");
            if (recovery == DatabaseStorage.isReady()
                    || !report.getString("target").equals(recovery ? id : DatabaseStorage.activeGenerationId())) {
                return failure(Reason.GENERIC);
            }
            if (!DatabaseStorage.isReady()) {
                if (DatabaseStorage.state() != DatabaseStorage.State.RECOVERY_REQUIRED
                        && DatabaseStorage.state() != DatabaseStorage.State.MIGRATION_REQUIRED) {
                    return failure(Reason.GENERIC);
                }
                // Explicit recovery activation only; normal import never replaces a readable pair.
                // Media first, as below: the stage becomes the active generation and is never discarded.
                final File stagedFiles = new File(stage.getDirectory(), "files");
                final File[] moved = stagedFiles.listFiles();
                try {
                    moveStagedFiles(stagedFiles, attachments(context));
                    DatabaseStorage.recoverImport(context, stage);
                } catch (final Exception e) {
                    // tatrak: past the commit selector the next start activates the stage, so its media stays
                    // (decided inside recoverImport: a fresh start right after it could change the selectors)
                    if (!(e instanceof DatabaseStorage.PastCommitException)) {
                        returnStagedFiles(moved, attachments(context));
                        throw e;
                    }
                    // Not through the outer catch: its discardImport could race the retry and delete the stage.
                    Log.d(Config.LOGTAG, "recovery activation failed after commit: " + e.getCause().getClass().getName());
                    DatabaseStorage.retry(context); // finish the committed activation now, not at the next start
                    deleteLeftovers(stagedFiles);
                    return failure(Reason.GENERIC);
                }
                deleteLeftovers(stagedFiles);
            } else {
                final var db = DatabaseBackend.getInstance(context).getWritableDatabase();
                db.execSQL("ATTACH DATABASE ? AS tatrak_import KEY ?", new Object[] {
                    stage.history().getPath(), new String(stage.password(), StandardCharsets.US_ASCII)});
                // media first: a process death right after the commit must not leave messages without files
                final File stagedFiles = new File(stage.getDirectory(), "files");
                final File[] moved = stagedFiles.listFiles();
                boolean committed = false;
                try {
                    moveStagedFiles(stagedFiles, attachments(context));
                    db.beginTransaction();
                    DatabaseBackend.setLongWrite(true);
                    try {
                        try (final Cursor c = db.rawQuery(
                                "SELECT 1 FROM accounts WHERE username=? AND server=?",
                                new String[] {jid.getLocal(), jid.getDomain().toString()})) {
                            if (c.moveToFirst()) return failure(Reason.ACCOUNT_ALREADY_EXISTS);
                        }
                        // Exact authenticated staging schema; all inserts succeed together or roll back.
                        for (final String table : TABLE_ALLOW_LIST) {
                            step = table;
                            insertStagedTable(db, table);
                        }
                        // Tatrak: the keys the backup brought, outside the generic copy: no key is
                        // replaced, and the stage's own row (none in a normal import) is never copied
                        step = am.cyberhayq.chat.storage.media.MediaKeyTable.TABLE;
                        db.execSQL("INSERT OR IGNORE INTO main." + step + " (generation, master_key, own)"
                                + " SELECT generation, master_key, 0 FROM tatrak_import." + step + " WHERE own = 0");
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); DatabaseBackend.setLongWrite(false); }
                    committed = true;
                } finally {
                    db.execSQL("DETACH DATABASE tatrak_import");
                    // rolled back: the media goes back to the stage, which a retry imports again
                    if (!committed) returnStagedFiles(moved, attachments(context));
                }
                try { DatabaseStorage.discardImport(context, id); }
                catch (Exception ignored) { /* Committed import remains successful; only staging cleanup failed. */ }
            }
            am.cyberhayq.chat.storage.media.EncryptedMediaStore.get(context).keysChanged(); // Tatrak
            Conversations.getInstance(context).resetAccounts();
            stopBackgroundService();
            notifySuccess(0);
            return Result.success();
        } catch (Exception e) {
            // No SQL/exception message logging: native error strings can contain key material.
            // The table and the exception class are enough to tell a conflict from a stop.
            Log.d(Config.LOGTAG, "backup commit failed at " + step + ": " + e.getClass().getName());
            // The screen offers no retry of a commit; a kept stage would only hold space for good.
            try { if (id != null) DatabaseStorage.discardImport(context, id); }
            catch (Exception ignored) { /* inactive stage; removed by DatabaseStorage on a later start */ }
            return failure(Reason.GENERIC);
        }
    }

    @VisibleForTesting
    static void insertStagedTable(final SupportSQLiteDatabase db, final String table) {
        final String quotedTable = "\"" + table.replace("\"", "\"\"") + "\"";
        final String names;
        try (final Cursor cursor = db.query("SELECT * FROM tatrak_import." + quotedTable + " LIMIT 0")) {
            names = Arrays.stream(cursor.getColumnNames())
                    .map(name -> "\"" + name.replace("\"", "\"\"") + "\"")
                    .collect(java.util.stream.Collectors.joining(","));
        }
        // Upgraded databases and fresh staging schemas need not have the same column order.
        db.execSQL("INSERT INTO main." + quotedTable + " (" + names + ") SELECT " + names
                + " FROM tatrak_import." + quotedTable);
    }

    @VisibleForTesting
    Result successResult() {
        final int count = skippedRows.values().stream().mapToInt(Integer::intValue).sum();
        return Result.success(new Data.Builder().putInt(DATA_KEY_SKIPPED_ROWS, count).build());
    }

    public static int skippedRows(final Data data) {
        return data.getInt(DATA_KEY_SKIPPED_ROWS, 0);
    }

    /**
     * Tatrak: imports the rest of the backup's JSON array in one transaction. It commits only after
     * the encrypted stream has been read to its end, which makes CipherInputStream check the
     * AES-GCM tag; a broken JSON row, a failed accounts row, any non-constraint SQL error, a
     * truncated file or a bad tag rolls everything back. Other rows that hit a constraint are
     * skipped (see insert).
     */
    @VisibleForTesting
    static void importRows(
            final SupportSQLiteDatabase db,
            final JsonReader jsonReader,
            final InputStream cipherInputStream,
            final RowImporter rowImporter)
            throws IOException {
        db.beginTransaction();
        try {
            while (jsonReader.hasNext()) {
                rowImporter.importRow();
            }
            jsonReader.endArray();
            ByteStreams.exhaust(cipherInputStream);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @VisibleForTesting
    interface RowImporter {
        void importRow() throws IOException;
    }

    @VisibleForTesting
    void importRow(
            final SupportSQLiteDatabase db,
            final JsonReader jsonReader,
            final Jid account,
            final String passphrase)
            throws IOException {
        jsonReader.beginObject();
        final String firstParameter = jsonReader.nextName();
        if (!firstParameter.equals("table")) {
            throw new IllegalStateException("Expected key 'table'");
        }
        final String table = jsonReader.nextString();
        if ("files".equals(table)) {
            importFile(jsonReader);
            jsonReader.endObject();
            return;
        }
        if (am.cyberhayq.chat.storage.media.MediaKeyTable.TABLE.equals(table)) { // Tatrak: before the messages
            importMediaKey(db, jsonReader);
            jsonReader.endObject();
            return;
        }
        if (!TABLE_ALLOW_LIST.contains(table)) {
            jsonReader.skipValue(); // remaining member name (values)
            jsonReader.skipValue();
            jsonReader.endObject();
            omitted(R.string.tatrak_backup_omit_table, table);
            return;
        }
        final ContentValues contentValues = new ContentValues();
        final String secondParameter = jsonReader.nextName();
        if (!secondParameter.equals("values")) {
            throw new IllegalStateException("Expected key 'values'");
        }
        jsonReader.beginObject();
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            final String name = jsonReader.nextName();
            if (COLUMN_PATTERN.matcher(name).matches()) {
                if (jsonReader.peek() == JsonToken.NULL) {
                    jsonReader.nextNull();
                    contentValues.putNull(name);
                } else if (jsonReader.peek() == JsonToken.NUMBER) {
                    contentValues.put(name, jsonReader.nextLong());
                } else {
                    contentValues.put(name, jsonReader.nextString());
                }
            } else {
                throw new IOException(String.format("Unexpected column name %s", name));
            }
        }
        jsonReader.endObject();
        jsonReader.endObject();
        if (!columns.containsKey(table)) {
            final var names = new java.util.HashSet<String>();
            try (final Cursor schema = db.query("PRAGMA table_info(\"" + table + "\")")) {
                while (schema.moveToNext()) names.add(schema.getString(1));
            }
            columns.put(table, names);
        }
        if (Message.TABLENAME.equals(table)
                && (positive(contentValues, "ephemeral_timer") || positive(contentValues, "expire_at"))) {
            // Tatrak has no disappearing messages: imported, such a message would stay for good.
            omitted(R.string.tatrak_backup_omit_disappearing);
            return;
        }
        if (Message.TABLENAME.equals(table) && !Strings.isNullOrEmpty(contentValues.getAsString("retractId"))) {
            // Tatrak deletes retractions for good
            omitted(R.string.tatrak_backup_omit_retracted);
            return;
        }
        final Integer type = contentValues.getAsInteger(Message.TYPE);
        if (Message.TABLENAME.equals(table) && !sourceTatrak && type != null && type > Message.TYPE_RTP_SESSION) {
            // other apps' types (monocles: 7 = story) would take Tatrak's meaning of the same number (7 = deleted placeholder)
            omitted(R.string.tatrak_backup_omit_type, type);
            return;
        }
        if (Message.TABLENAME.equals(table)) {
            restoreFileBody(contentValues);
            restoreReplyTo(contentValues);
            restoreFilePath(contentValues);
        }
        for (final String name : new java.util.ArrayList<>(contentValues.keySet())) {
            if (!columns.get(table).contains(name)) {
                final Object value = contentValues.get(name);
                contentValues.remove(name);
                // NULL or 0 is the column's default, so nothing is lost; count only real values.
                // shortcut: a column where 0 is real data is under-reported; list such columns if one appears
                // backups write every value as text, so a 0 arrives as "0"
                if (value != null && !"0".equals(String.valueOf(value))) {
                    omitted(R.string.tatrak_backup_omit_field, table, name);
                }
            }
        }
        if ((Account.TABLENAME.equals(table) || Conversation.TABLENAME.equals(table) || Message.TABLENAME.equals(table))
                && Strings.isNullOrEmpty(contentValues.getAsString("uuid"))) {
            throw new IOException("Missing row identity");
        }
        if (Account.TABLENAME.equals(table)) {
            if (importAccount != null) throw new IOException("Multiple account rows");
            importAccount = contentValues.getAsString(Account.UUID);
            if (importAccount == null) throw new IOException("Missing account identity");
            final Jid jid =
                    Jid.of(
                            contentValues.getAsString(Account.USERNAME),
                            contentValues.getAsString(Account.SERVER),
                            null);
            final String password = contentValues.getAsString(Account.PASSWORD);
            if (QuickConversationsService.isQuicksy()) {
                if (!jid.getDomain().equals(Config.QUICKSY_DOMAIN)) {
                    throw new IOException("Trying to restore non Quicksy account on Quicksy");
                }
            }
            if (jid.equals(account) && passphrase.equals(password)) {
                Log.d(Config.LOGTAG, "jid and password from backup header had matching row");
            } else {
                throw new IOException("jid or password in table did not match backup");
            }
            if (sourceMonocles) omitted(R.string.tatrak_backup_omit_account_crypto);
            final var keys = Account.Keys.parse(contentValues.getAsString(Account.KEYS));
            final var deviceId = keys.getAxolotlRegistrationId();
            final Account.Keys importReadyKeys;
            if (deviceId.isPresent() && this.includeOmemo && !sourceMonocles) {
                importReadyKeys = new Account.Keys(deviceId.get());
            } else {
                importReadyKeys = new Account.Keys();
            }
            contentValues.put(Account.KEYS, Services.GSON.toJson(importReadyKeys));
        }
        if (Message.TABLENAME.equals(table)) {
            final Integer encryption = contentValues.getAsInteger(Message.ENCRYPTION);
            if (encryption != null && encryption >= 8 && encryption <= 10) {
                contentValues.put("tatrak_source_encryption", encryption);
                if (contentValues.getAsString("tatrak_source_fingerprint") == null) {
                    contentValues.put("tatrak_source_fingerprint", contentValues.getAsString(Message.FINGERPRINT));
                }
                contentValues.putNull(Message.FINGERPRINT);
                // Historical source state is never imported as a legacy session/trust identity.
                contentValues.put(Message.ENCRYPTION, encryption);
                final Integer status = contentValues.getAsInteger(Message.STATUS);
                if (status != null && (status == Message.STATUS_UNSEND || status == Message.STATUS_WAITING
                        || status == Message.STATUS_SEND_FAILED || status == Message.STATUS_OFFERED)) {
                    contentValues.put(Message.STATUS, Message.STATUS_SEND);
                    omitted(R.string.tatrak_backup_omit_pending_pq);
                }
                omitted(R.string.tatrak_backup_omit_pq_sessions);
            } else if (encryption != null && (encryption < 0 || encryption > 7)) {
                omitted(R.string.tatrak_backup_omit_encryption); return;
            }
        }
        if (Conversation.TABLENAME.equals(table)) {
            final String attributes = contentValues.getAsString(Conversation.ATTRIBUTES);
            if (attributes != null) {
                try {
                    final var json = new org.json.JSONObject(attributes);
                    if (json.optInt("next_encryption", 0) > 7) {
                        json.remove("next_encryption");
                        contentValues.put(Conversation.ATTRIBUTES, json.toString());
                        omitted(R.string.tatrak_backup_omit_outgoing);
                    }
                } catch (org.json.JSONException e) { throw new IOException("Invalid conversation attributes", e); }
            }
        }
        if (sourceMonocles && OMEMO_TABLE_LIST.contains(table)) {
            // Current monocles identities share PQ trust; importing those as legacy is unsafe.
            omitted(R.string.tatrak_backup_omit_monocles_keys);
            return;
        }
        if (this.includeOmemo) {
            insert(db, table, contentValues);
        } else {
            if (OMEMO_TABLE_LIST.contains(table)) {
                if (SQLiteAxolotlStore.IDENTITIES_TABLENAME.equals(table)
                        && Integer.valueOf(0).equals(contentValues.getAsInteger(SQLiteAxolotlStore.OWN))) {
                    insert(db, table, contentValues);
                } else {
                    omitted(R.string.tatrak_backup_omit_private, table);
                }
            } else {
                insert(db, table, contentValues);
            }
        }
    }

    /**
     * Tatrak: insertOrThrow, because SQLiteDatabase.insert logs all values (the account password)
     * when it fails. A failed accounts row fails the whole import; other rows that conflict are
     * skipped (as monocles does) so leftovers in a backup don't make it unrestorable.
     */
    private void insert(
            final SupportSQLiteDatabase db, final String table, final ContentValues contentValues) {
        if (Account.TABLENAME.equals(table)) {
            db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, contentValues);
            return;
        }
        try {
            db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, contentValues);
        } catch (final SQLiteConstraintException e) {
            // only a constraint error aborts just this statement; SQLITE_FULL / IOERR can roll back
            // the whole transaction, so they propagate. The message names columns, never values
            if (skippedRows.merge(table, 1, Integer::sum) == 1) {
                Log.w(Config.LOGTAG, "skipping row in " + table + ": " + e.getMessage());
            }
        }
    }

    private void stopBackgroundService() {
        final var intent = new Intent(getApplicationContext(), XmppConnectionService.class);
        getApplicationContext().stopService(intent);
    }

    private void updateImportBackupNotification(final long total, final long current) {
        final int max;
        final int progress;
        if (total == 0) {
            max = 1;
            progress = 0;
        } else {
            max = 100;
            progress = (int) (current * 100 / total);
        }
        // Tatrak: called per row; publish only when the percentage changes
        if (progress == lastProgress) return;
        lastProgress = progress;
        setProgressAsync(new Data.Builder().putInt(PROGRESS_PERCENT, total == 0 ? -1 : progress).build());
        getApplicationContext()
                .getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, createImportBackupNotification(max, progress));
    }

    // Tatrak: status line for the restore screen; -1 = size unknown, SAVING = commit step
    private static final String PROGRESS_PERCENT = "tatrak_percent";
    private static final int SAVING = 101;
    private int lastProgress = -1;

    @Nullable
    public static String status(final Context context, final Data progress) {
        final int percent = progress.getInt(PROGRESS_PERCENT, -2);
        if (percent == -2) return null;
        if (percent == SAVING) return context.getString(R.string.tatrak_backup_status_saving);
        if (percent < 0) return context.getString(R.string.tatrak_backup_status_reading);
        return context.getString(R.string.tatrak_backup_status_reading_percent, percent);
    }

    private Notification createImportBackupNotification(final int max, final int progress) {
        final var context = getApplicationContext();
        final var builder = new NotificationCompat.Builder(getApplicationContext(), "backup");
        builder.setContentTitle(context.getString(R.string.restoring_backup))
                // Tatrak: the foreground notification of the commit job says what it is doing
                .setContentText(context.getString(getInputData().getString("tatrak_stage") != null
                        ? R.string.tatrak_backup_status_saving : R.string.tatrak_backup_status_reading))
                .setSmallIcon(R.drawable.ic_unarchive_24dp)
                .setProgress(max, progress, max == 1 && progress == 0);
        return builder.build();
    }

    private void notifySuccess(final int skippedRows) {
        final var context = getApplicationContext();
        final String summary =
                skippedRows > 0
                        ? context.getString(R.string.tatrak_backup_partial_summary, skippedRows)
                        : context.getString(R.string.notification_restored_backup_subtitle);
        final var builder = new NotificationCompat.Builder(context, "backup");
        builder.setContentTitle(
                        context.getString(
                                skippedRows > 0
                                        ? R.string.tatrak_backup_partial_title
                                        : R.string.notification_restored_backup_title))
                .setContentText(summary)
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_unarchive_24dp);
        if (skippedRows > 0) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(summary));
        }
        if (QuickConversationsService.isConversations()
                && AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            builder.setContentIntent(
                    PendingIntent.getActivity(
                            context,
                            145,
                            new Intent(context, AccountUtils.MANAGE_ACCOUNT_ACTIVITY),
                            s()
                                    ? PendingIntent.FLAG_IMMUTABLE
                                            | PendingIntent.FLAG_UPDATE_CURRENT
                                    : PendingIntent.FLAG_UPDATE_CURRENT));
        }
        getApplicationContext()
                .getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID + 2, builder.build());
    }

    public static Data data(final String password, final Uri uri, final boolean includeOmemo) {
        final String passwordKey = UUID.randomUUID().toString();
        PASSWORDS.put(passwordKey, password);
        new Handler(Looper.getMainLooper())
                .postDelayed(
                        () -> PASSWORDS.remove(passwordKey),
                        TimeUnit.MINUTES.toMillis(PASSWORD_LIFETIME_MINUTES));
        return new Data.Builder()
                .putString(DATA_KEY_PASSWORD_KEY, passwordKey)
                .putString(DATA_KEY_URI, uri.toString())
                .putBoolean(DATA_KEY_INCLUDE_OMEMO, includeOmemo)
                .build();
    }

    /** Tatrak: hands the password out once and forgets it; null if it is gone. */
    public static String takePassword(final Data inputData) {
        final String passwordKey = inputData.getString(DATA_KEY_PASSWORD_KEY);
        return passwordKey == null ? null : PASSWORDS.remove(passwordKey);
    }

    public static Uri uri(final Data outputData) {
        final String uri = outputData.getString(DATA_KEY_URI);
        return uri == null ? null : Uri.parse(uri);
    }

    public static boolean includeOmemo(final Data outputData) {
        return outputData.getBoolean(DATA_KEY_INCLUDE_OMEMO, true);
    }

    private static Result failure(final Reason reason) {
        return Result.failure(new Data.Builder().putString("reason", reason.toString()).build());
    }

    public enum Reason {
        ACCOUNT_ALREADY_EXISTS,
        DECRYPTION_FAILED,
        FILE_NOT_FOUND,
        // Tatrak: the in-memory password was lost with the process
        PASSWORD_MISSING,
        GENERIC;

        public static Reason valueOfOrGeneric(final String value) {
            if (Strings.isNullOrEmpty(value)) {
                return GENERIC;
            }
            try {
                return valueOf(value);
            } catch (final IllegalArgumentException e) {
                return GENERIC;
            }
        }
    }
}
