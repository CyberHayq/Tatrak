package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
// Tatrak: encrypted local storage; protocol APIs are unchanged.
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gson.stream.JsonWriter;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.Random;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class ExportBackupWorker extends Worker {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US);

    private static final String KEY_TYPE = "AES";
    private static final String CIPHER_MODE = "AES/GCM/NoPadding";
    private static final String PROVIDER = "BC";

    public static final String MIME_TYPE = "application/vnd.conversations.backup";

    private static final int NOTIFICATION_ID = 19;
    private static final int BACKUP_CREATED_NOTIFICATION_ID = 23;

    private static final int PENDING_INTENT_FLAGS =
            s()
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;

    private final boolean recurringBackup;
    // Tatrak: AppSettings.BACKUP_FORMAT_* value this export writes
    private final String format;
    // Tatrak: monocles format only: exported file path -> its ${FILES}/media/ name, per account
    private final java.util.Map<String, String> portablePaths = new java.util.HashMap<>();
    // Tatrak: attachments the media store refused to open, named in the success notification
    @VisibleForTesting int leftOut;
    // Tatrak: photos and files, only in one-off backups in Tatrak's format
    private final boolean includeMedia;

    public ExportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        this.recurringBackup = inputData.getBoolean("recurring_backup", false);
        final var settings = new AppSettings(context);
        this.format = settings.getBackupFormat();
        this.includeMedia = !recurringBackup
                && !AppSettings.BACKUP_FORMAT_CONVERSATIONS.equals(format)
                && settings.isBackupMedia();
    }

    // Tatrak: column lists from comparing our schema with Conversations' and monocles' databases.
    // shortcut: code comparison only, verify with real Conversations and monocles backups
    private static final java.util.Set<String> NOT_IN_CONVERSATIONS = java.util.Set.of("replyTo");
    private static final java.util.Set<String> NOT_IN_MONOCLES =
            java.util.Set.of("replyTo", "sharedStorage");

    /**
     * Tatrak: whether a column goes into the backup. Conversations and monocles insert a row with
     * an unknown column as a whole or not at all, so other formats leave out what they lack.
     */
    static boolean exported(final String column, final String format) {
        if (AppSettings.BACKUP_FORMAT_TATRAK.equals(format)) return true;
        if (column.startsWith("tatrak")) return false;
        return !(AppSettings.BACKUP_FORMAT_MONOCLES.equals(format)
                ? NOT_IN_MONOCLES
                : NOT_IN_CONVERSATIONS).contains(column);
    }

    /**
     * Tatrak: whether a message type goes into the backup. Other apps know types up to {@link
     * Message#TYPE_RTP_SESSION}; our deleted placeholders (7) and messages deleted "for me" (8)
     * would show there as empty bubbles or, in monocles, as stories.
     */
    static boolean exportedType(final int type, final String format) {
        return AppSettings.BACKUP_FORMAT_TATRAK.equals(format) || type <= Message.TYPE_RTP_SESSION;
    }

    /** monocles has no PQ encryption of ours: imported PQ history becomes ordinary OMEMO rows. */
    @VisibleForTesting
    static int monoclesEncryption(final int encryption) {
        return switch (encryption) {
            case Message.ENCRYPTION_IMPORTED_PQ -> Message.ENCRYPTION_AXOLOTL;
            case Message.ENCRYPTION_IMPORTED_PQ_UNAVAILABLE ->
                    Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE;
            case Message.ENCRYPTION_IMPORTED_PQ_FAILED -> Message.ENCRYPTION_AXOLOTL_FAILED;
            default -> encryption;
        };
    }

    /**
     * monocles keeps a file's {@code url|size|width|height} in {@code fileParams} and shows the
     * body as a caption; Tatrak (like Conversations) keeps that string in the body. Returns the
     * monocles {@code fileParams}, or null when the message is no file with a body.
     */
    @VisibleForTesting
    static String monoclesFileParams(final int type, final String body) {
        final boolean file = type == Message.TYPE_IMAGE
                || type == Message.TYPE_FILE
                || type == Message.TYPE_PRIVATE_FILE;
        return file && !Strings.isNullOrEmpty(body) ? body : null;
    }

    /** The monocles body of a file message: the URL, the first segment of the Tatrak body. */
    @VisibleForTesting
    static String monoclesFileBody(final String tatrakBody) {
        return tatrakBody.split("\\|", 2)[0];
    }

    /** The XML monocles keeps in {@code payloads} for a reply; null when the message has none. */
    @VisibleForTesting
    static String monoclesReplyPayload(final String replyToJson) {
        final var replyTo = eu.siacs.conversations.entities.ReplyTo.fromString(replyToJson);
        if (replyTo == null) return null;
        final var xml = new StringBuilder("<reply xmlns=\"urn:xmpp:reply:0\" id=\"")
                .append(android.text.TextUtils.htmlEncode(replyTo.id)).append('"');
        if (replyTo.to != null) {
            xml.append(" to=\"").append(android.text.TextUtils.htmlEncode(replyTo.to)).append('"');
        }
        xml.append("/>");
        if (replyTo.fallbackStart >= 0 && replyTo.fallbackEnd >= 0) {
            xml.append("<fallback xmlns=\"urn:xmpp:fallback:0\" for=\"urn:xmpp:reply:0\">")
                    .append("<body start=\"").append(replyTo.fallbackStart)
                    .append("\" end=\"").append(replyTo.fallbackEnd).append("\"/></fallback>");
        }
        return xml.toString();
    }

    /** The unique monocles path of an exported file; repeated names get -1, -2 before the extension. */
    @VisibleForTesting
    static String portablePath(final java.util.Set<String> taken, final String name) {
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final String extension = dot > 0 ? name.substring(dot) : "";
        String candidate = "${FILES}/media/" + name;
        for (int n = 1; !taken.add(candidate); n++) {
            candidate = "${FILES}/media/" + base + "-" + n + extension;
        }
        return candidate;
    }

    @NonNull
    @Override
    public Result doWork() {
        setForegroundAsync(getForegroundInfo());
        final List<Uri> files;
        try {
            files = export();
        } catch (final RuntimeException | IOException
                | InvalidKeySpecException
                | InvalidAlgorithmParameterException
                | InvalidKeyException
                | NoSuchPaddingException
                | NoSuchAlgorithmException
                | NoSuchProviderException e) {
            Log.d(Config.LOGTAG, "could not create backup");
            return Result.failure();
        } finally {
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }
        Log.d(Config.LOGTAG, "done creating " + files.size() + " backup files");
        if (files.isEmpty() || recurringBackup) {
            return Result.success();
        }
        notifySuccess(files);
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        Log.d(Config.LOGTAG, "getForegroundInfo()");
        final NotificationCompat.Builder notification = getNotification();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID,
                    notification.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification.build());
        }
    }

    /** A legacy export cannot represent PQ provenance without silently losing its meaning. */
    public static boolean hasImportedPq(final DatabaseBackend database) {
        try (final Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT 1 FROM messages WHERE encryption BETWEEN 8 AND 10 LIMIT 1", null)) {
            return cursor.moveToFirst();
        }
    }

    private List<Uri> export()
            throws IOException,
                    InvalidKeySpecException,
                    InvalidAlgorithmParameterException,
                    InvalidKeyException,
                    NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    NoSuchProviderException {
        final Context context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final var backupLocation = appSettings.getBackupLocation();
        // Tatrak: the optional pre-migration export uses a separate legacy reader, with no writers.
        final boolean beforeMigration = getInputData().getBoolean("tatrak_before_migration", false);
        final var database = beforeMigration
                ? DatabaseBackend.legacyForExport(context)
                : DatabaseBackend.getInstance(context);
        try {
        if (AppSettings.BACKUP_FORMAT_CONVERSATIONS.equals(format) && hasImportedPq(database)) {
            final var notice = getNotification().setContentText(context.getString(R.string.tatrak_backup_compatible_pq))
                    .setProgress(0, 0, false);
            context.getSystemService(NotificationManager.class).notify(BACKUP_CREATED_NOTIFICATION_ID, notice.build());
            throw new IOException("PQ provenance requires native backup format");
        }
        final var accounts = database.getAccounts();
        if (beforeMigration && accounts.stream().anyMatch(a -> Strings.nullToEmpty(a.getPassword()).trim().isEmpty())) {
            throw new IOException("An account has no export password");
        }

        int count = 0;
        final int max = accounts.size();
        final ImmutableList.Builder<Uri> locations = new ImmutableList.Builder<>();
        Log.d(Config.LOGTAG, "starting backup for " + max + " accounts");
        for (final Account account : accounts) {
            if (isStopped()) {
                if (beforeMigration) throw new IOException("Export cancelled");
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return locations.build();
            }
            final String password = account.getPassword();
            if (Strings.nullToEmpty(password).trim().isEmpty()) {
                Log.d(
                        Config.LOGTAG,
                        String.format(
                                "skipping backup for %s because password is empty. unable to"
                                        + " encrypt",
                                account.getJid().asBareJid()));
                count++;
                continue;
            }
            final Uri uri;
            try {
                uri = export(database, account, password, backupLocation, max, count);
            } catch (final WorkStoppedException e) {
                if (beforeMigration) throw new IOException("Export cancelled");
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return locations.build();
            }
            locations.add(uri);
            count++;
        }
        if (beforeMigration && accounts.isEmpty()) throw new IOException("No exportable accounts");
        return locations.build();
        } finally { if (beforeMigration) database.close(); }
    }

    private Uri export(
            final DatabaseBackend database,
            final Account account,
            final String password,
            final Uri backupLocation,
            final int max,
            final int count)
            throws IOException,
                    InvalidKeySpecException,
                    InvalidAlgorithmParameterException,
                    InvalidKeyException,
                    NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    NoSuchProviderException,
                    WorkStoppedException {
        final var context = getApplicationContext();
        Log.d(
                Config.LOGTAG,
                String.format(
                        "exporting data for account %s (%s)",
                        account.getJid().asBareJid(), account.getUuid()));
        final byte[] IV = new byte[12];
        final byte[] salt = new byte[16];
        Random.SECURE_RANDOM.nextBytes(IV);
        Random.SECURE_RANDOM.nextBytes(salt);
        final BackupFileHeader backupFileHeader =
                new BackupFileHeader(
                        switch (format) {
                            case AppSettings.BACKUP_FORMAT_CONVERSATIONS ->
                                    BackupFileHeader.LEGACY_VERSION;
                            case AppSettings.BACKUP_FORMAT_MONOCLES -> BackupFileHeader.VERSION;
                            default -> BackupFileHeader.TATRAK_VERSION;
                        },
                        context.getString(R.string.app_name),
                        account.getJid(),
                        System.currentTimeMillis(),
                        IV,
                        salt);
        final var notification = getNotification();
        final var cancelPendingIntent =
                WorkManager.getInstance(context).createCancelPendingIntent(getId());
        notification.addAction(
                new NotificationCompat.Action.Builder(
                                R.drawable.ic_cancel_24dp,
                                context.getString(R.string.cancel),
                                cancelPendingIntent)
                        .build());
        final Progress progress = new Progress(notification, max, count);
        final String filename =
                String.format(
                        "%s.%s.ceb",
                        account.getJid().asBareJid().toString(), DATE_FORMAT.format(new Date()));
        final OutputStream outputStream;
        final Uri location;
        if ("file".equalsIgnoreCase(backupLocation.getScheme())) {
            final File file = new File(backupLocation.getPath(), filename);
            final File directory = file.getParentFile();
            if (directory != null && directory.mkdirs()) {
                Log.d(Config.LOGTAG, "created backup directory " + directory.getAbsolutePath());
            }
            outputStream = new FileOutputStream(file);
            location = Uri.fromFile(file);
        } else {
            final var tree = DocumentFile.fromTreeUri(context, backupLocation);
            if (tree == null) {
                throw new IOException(
                        String.format(
                                "DocumentFile.fromTreeUri returned null for %s", backupLocation));
            }
            final var file = tree.createFile(MIME_TYPE, filename);
            if (file == null) {
                throw new IOException(
                        String.format("Could not create %s in %s", filename, backupLocation));
            }
            location = file.getUri();
            outputStream = context.getContentResolver().openOutputStream(location);
        }
        if (outputStream == null) throw new IOException("Backup destination unavailable");
        try (outputStream) {
        final DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
        backupFileHeader.write(dataOutputStream);
        dataOutputStream.flush();

        final Cipher cipher =
                Compatibility.twentyEight()
                        ? Cipher.getInstance(CIPHER_MODE)
                        : Cipher.getInstance(CIPHER_MODE, PROVIDER);
        final byte[] key =
                backupFileHeader.getVersion() == BackupFileHeader.LEGACY_VERSION
                        ? getLegacyKey(password, salt)
                        : getKey(password, salt);
        SecretKeySpec keySpec = new SecretKeySpec(key, KEY_TYPE);
        IvParameterSpec ivSpec = new IvParameterSpec(IV);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, cipher);

        final GZIPOutputStream gzipOutputStream = new GZIPOutputStream(cipherOutputStream);
        try (final JsonWriter jsonWriter =
                new JsonWriter(new OutputStreamWriter(gzipOutputStream, StandardCharsets.UTF_8))) {
        jsonWriter.beginArray();
        final SQLiteDatabase db = database.getReadableDatabase();
        final String uuid = account.getUuid();
        accountExport(db, uuid, jsonWriter);
        // Tatrak: the media keys right after the account, so a restore can open the files it brings
        if (AppSettings.BACKUP_FORMAT_TATRAK.equals(format)) mediaKeyExport(db, jsonWriter);
        simpleExport(db, Conversation.TABLENAME, Conversation.ACCOUNT, uuid, jsonWriter);
        portablePaths.clear();
        if (includeMedia) fileExport(db, uuid, location, jsonWriter);
        messageExport(db, uuid, location, jsonWriter, progress);
        for (final String table :
                Arrays.asList(
                        SQLiteAxolotlStore.PREKEY_TABLENAME,
                        SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                        SQLiteAxolotlStore.SESSION_TABLENAME,
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME)) {
            throwIfWorkStopped(location);
            simpleExport(db, table, SQLiteAxolotlStore.ACCOUNT, uuid, jsonWriter);
        }
        jsonWriter.endArray();
        jsonWriter.flush();
        }
        }
        final var path = location.getPath();
        if ("file".equalsIgnoreCase(location.getScheme()) && path != null) {
            mediaScannerScanFile(new File(path));
        }
        Log.d(Config.LOGTAG, "written backup to " + location);
        return location;
    }

    private NotificationCompat.Builder getNotification() {
        final var context = getApplicationContext();
        final NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, "backup");
        notification
                .setContentTitle(context.getString(R.string.notification_create_backup_title))
                .setSmallIcon(R.drawable.ic_archive_24dp)
                .setProgress(1, 0, false);
        notification.setOngoing(true);
        notification.setLocalOnly(true);
        return notification;
    }

    private void throwIfWorkStopped(final Uri location) throws WorkStoppedException {
        if (isStopped()) {
            if ("file".equalsIgnoreCase(location.getScheme())) {
                final var file = new File(location.getPath());
                if (file.delete()) {
                    Log.d(Config.LOGTAG, "deleted " + file.getAbsolutePath());
                }
            } else {
                final var documentFile =
                        DocumentFile.fromSingleUri(getApplicationContext(), location);
                if (documentFile != null && documentFile.delete()) {
                    Log.d(Config.LOGTAG, "deleted " + location);
                }
            }
            throw new WorkStoppedException();
        }
    }

    private void mediaScannerScanFile(final File file) {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        getApplicationContext().sendBroadcast(intent);
    }

    private void accountExport(
            final SQLiteDatabase db, final String uuid, final JsonWriter writer)
            throws IOException {
        try (final Cursor accountCursor =
                db.query(
                        Account.TABLENAME,
                        null,
                        Account.UUID + "=?",
                        new String[] {uuid},
                        null,
                        null,
                        null)) {
            while (accountCursor != null && accountCursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(Account.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < accountCursor.getColumnCount(); ++i) {
                    final String name = accountCursor.getColumnName(i);
                    if (!exported(name, format)) continue;
                    writer.name(name);
                    final String value = accountCursor.getString(i);
                    if (value == null
                            || Account.ROSTERVERSION.equals(accountCursor.getColumnName(i))) {
                        writer.nullValue();
                    } else if (Account.OPTIONS.equals(accountCursor.getColumnName(i))
                            && value.matches("\\d+")) {
                        int intValue = Integer.parseInt(value);
                        if (QuickConversationsService.isConversations()) {
                            intValue |= 1 << Account.OPTION_DISABLED;
                        }
                        writer.value(intValue);
                    } else {
                        writer.value(value);
                    }
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private void simpleExport(
            final SQLiteDatabase db,
            final String table,
            final String column,
            final String uuid,
            final JsonWriter writer)
            throws IOException {
        try (final Cursor cursor =
                db.query(table, null, column + "=?", new String[] {uuid}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(table);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < cursor.getColumnCount(); ++i) {
                    final String name = cursor.getColumnName(i);
                    if (!exported(name, format)) continue;
                    writer.name(name);
                    final String value = cursor.getString(i);
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    /**
     * Tatrak: every media key this database holds (its own and imported ones), as
     * {@code {generation, master_key: hex}} records; a restore keeps them as imported keys.
     */
    @VisibleForTesting
    static void mediaKeyExport(final androidx.sqlite.db.SupportSQLiteDatabase db, final JsonWriter writer)
            throws IOException {
        final var table = am.cyberhayq.chat.storage.media.MediaKeyTable.TABLE;
        for (final var key : am.cyberhayq.chat.storage.media.MediaKeyTable.read(db).getByGeneration().entrySet()) {
            writer.beginObject();
            writer.name("table").value(table);
            writer.name("values").beginObject();
            writer.name("generation").value(key.getKey());
            writer.name("master_key").value(com.google.common.io.BaseEncoding.base16().lowerCase().encode(key.getValue()));
            writer.endObject();
            writer.endObject();
        }
    }

    /**
     * Tatrak: every file the account's messages point at, in monocles' `files` format ({path: the
     * stored relativeFilePath, sequence, content: base64 of up to 1 MB}), before the messages, as
     * monocles chat does (ExportBackupWorker.fileExport, commit e7d071a7). Missing files are left
     * out; an empty file is one record with empty content, so it comes back as a file.
     */
    @VisibleForTesting
    void fileExport(
            final androidx.sqlite.db.SupportSQLiteDatabase db, final String uuid, final Uri location, final JsonWriter writer)
            throws IOException, WorkStoppedException {
        final var paths = new java.util.LinkedHashSet<String>();
        try (final Cursor cursor =
                db.query(
                        "select distinct relativeFilePath from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid where"
                                + " conversations.accountUuid=? and messages.type in (1,2,5) and"
                                + " messages.deleted=0 and relativeFilePath is not null",
                        new Object[] {uuid})) {
            while (cursor.moveToNext()) paths.add(cursor.getString(0));
        }
        final byte[] buffer = new byte[1024 * 1024];
        for (final String path : paths) {
            final File file = path.startsWith("/")
                    ? new File(path)
                    : eu.siacs.conversations.persistance.FileBackend.getLegacyFileForFilename(getApplicationContext(), path);
            if (!file.isFile() || !file.canRead()) continue;
            final java.io.InputStream opened;
            try { opened = am.cyberhayq.chat.storage.media.EncryptedMediaStore.get().inputStream(file); }
            catch (final java.io.FileNotFoundException e) { continue; } // removed meanwhile
            // a file the store cannot open (no key, a damaged header or end) is left out like a missing one;
            // a tag that fails while reading fails the backup below
            catch (final am.cyberhayq.chat.storage.media.MediaRejectedException e) { leftOut++; continue; }
            // mapped only once the file is open, so a message never points at a file left out
            final String exportedPath = AppSettings.BACKUP_FORMAT_MONOCLES.equals(format)
                    ? portablePaths.computeIfAbsent(path, p -> portablePath(
                            new java.util.HashSet<>(portablePaths.values()), p.substring(p.lastIndexOf('/') + 1)))
                    : path;
            // a read or write error fails the backup: a half-written file must not be restored
            try (final var in = opened) {
                int length;
                int sequence = 0;
                while ((length = com.google.common.io.ByteStreams.read(in, buffer, 0, buffer.length)) > 0 || sequence == 0) {
                    throwIfWorkStopped(location);
                    writer.beginObject();
                    writer.name("table").value("files");
                    writer.name("values").beginObject();
                    writer.name("path").value(exportedPath);
                    writer.name("sequence").value(sequence++);
                    writer.name("content").value(android.util.Base64.encodeToString(
                            buffer, 0, length, android.util.Base64.NO_WRAP));
                    writer.endObject();
                    writer.endObject();
                }
            }
        }
    }

    private void messageExport(
            final SQLiteDatabase db,
            final String uuid,
            final Uri location,
            final JsonWriter writer,
            final Progress progress)
            throws IOException, WorkStoppedException {
        final var notificationManager =
                getApplicationContext().getSystemService(NotificationManager.class);
        try (final Cursor cursor =
                db.rawQuery(
                        "select messages.* from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid where"
                                + " conversations.accountUuid=?",
                        new String[] {uuid})) {
            final int size = cursor.getCount();
            Log.d(Config.LOGTAG, "exporting " + size + " messages for account " + uuid);
            long lastUpdate = 0;
            int i = 0;
            int p = Integer.MIN_VALUE;
            final int typeColumn = cursor.getColumnIndexOrThrow(Message.TYPE);
            while (cursor.moveToNext()) {
                throwIfWorkStopped(location);
                if (!exportedType(cursor.getInt(typeColumn), format)) {
                    ++i;
                    continue;
                }
                writeMessage(cursor, format, portablePaths, writer);
                final int percentage = i * 100 / size;
                if (p < percentage && (SystemClock.elapsedRealtime() - lastUpdate) > 2_000) {
                    p = percentage;
                    lastUpdate = SystemClock.elapsedRealtime();
                    notificationManager.notify(NOTIFICATION_ID, progress.build(p));
                }
                i++;
                if (i % 500 == 0) {
                    Log.d(Config.LOGTAG, "flushing writer after " + i + " messages");
                    writer.flush();
                }
            }
        }
    }


    // a Conversations backup's caption message sorts right after its file
    @VisibleForTesting static final long CAPTION_DELAY_MS = 1;

    /**
     * Tatrak: one message row of the backup in {@code format}. A caption (Message.CAPTION) stays in
     * its column in Tatrak backups; monocles keeps it as the body of the file message (the file is
     * in fileParams) and shows it under the file; Conversations has no captions, so it gets a text
     * message right after the file, a row that exists only in the backup.
     */
    @VisibleForTesting
    static void writeMessage(final Cursor cursor, final String format,
            final java.util.Map<String, String> portablePaths, final JsonWriter writer) throws IOException {
        final int typeColumn = cursor.getColumnIndexOrThrow(Message.TYPE);
        final boolean monocles = AppSettings.BACKUP_FORMAT_MONOCLES.equals(format);
        final int captionColumn = cursor.getColumnIndex(Message.CAPTION);
        final String caption = captionColumn < 0 ? null : Strings.emptyToNull(cursor.getString(captionColumn));
        final int type = attachmentType(cursor.getInt(typeColumn), caption,
                cursor.getString(cursor.getColumnIndexOrThrow(Message.BODY)));
        final boolean tatrak = AppSettings.BACKUP_FORMAT_TATRAK.equals(format);
        final var captionRow = new java.util.LinkedHashMap<String, String>();
        writer.beginObject();
        writer.name("table");
        writer.value(Message.TABLENAME);
        writer.name("values");
        writer.beginObject();
        String replyTo = null;
        String fileParams = null;
        for (int j = 0; j < cursor.getColumnCount(); ++j) {
            final String name = cursor.getColumnName(j);
            if (monocles && Message.REPLY_TO.equals(name)) {
                replyTo = cursor.getString(j); // monocles keeps it as payloads XML
            }
            if (monocles && Message.TIME_RECEIVED.equals(name)) {
                // monocles' own column; it reads a NULL as timeSent
                writer.name("timeReceived").value(cursor.getString(j));
                continue;
            }
            if (!exported(name, format)) continue;
            writer.name(name);
            String value = cursor.getString(j);
            if (CAPTION_MESSAGE_COLUMNS.contains(name)) captionRow.put(name, value);
            if (!tatrak && Message.TYPE.equals(name)) {
                value = Integer.toString(type);
            } else if (monocles && value != null && Message.ENCRYPTION.equals(name)) {
                value = Integer.toString(monoclesEncryption(cursor.getInt(j)));
            } else if (monocles && value != null && Message.RELATIVE_FILE_PATH.equals(name)) {
                value = portablePaths.getOrDefault(value, value);
            } else if (monocles && Message.BODY.equals(name)) {
                fileParams = monoclesFileParams(type, value);
                if (fileParams != null) value = caption != null ? caption : monoclesFileBody(value);
            }
            writer.value(value);
        }
        if (fileParams != null) {
            writer.name("fileParams").value(fileParams);
        }
        if (monocles && replyTo != null) {
            writer.name("payloads").value(monoclesReplyPayload(replyTo));
        }
        writer.endObject();
        writer.endObject();
        if (caption == null || !AppSettings.BACKUP_FORMAT_CONVERSATIONS.equals(format)) return;
        if (type != Message.TYPE_IMAGE && type != Message.TYPE_FILE && type != Message.TYPE_PRIVATE_FILE) return;
        writer.beginObject();
        writer.name("table").value(Message.TABLENAME);
        writer.name("values");
        writer.beginObject();
        for (final var column : captionRow.entrySet()) writer.name(column.getKey()).value(column.getValue());
        writer.name(Message.UUID).value(java.util.UUID.randomUUID().toString());
        writer.name(Message.TYPE).value(type == Message.TYPE_PRIVATE_FILE ? Message.TYPE_PRIVATE : Message.TYPE_TEXT);
        writer.name(Message.BODY).value(caption);
        writer.name(Message.TIME_SENT).value(cursor.getLong(cursor.getColumnIndexOrThrow(Message.TIME_SENT)) + CAPTION_DELAY_MS);
        // a historical message: never sent again by the app that restores it
        final int status = cursor.getInt(cursor.getColumnIndexOrThrow(Message.STATUS));
        writer.name(Message.STATUS).value(status == Message.STATUS_RECEIVED ? Message.STATUS_RECEIVED : Message.STATUS_SEND);
        writer.endObject();
        writer.endObject();
    }

    /**
     * Tatrak: the type a captioned attachment has outside Tatrak. HttpDownloadConnection stores
     * an attachment as text while its re-download runs and leaves it so when that is cancelled
     * or fails; with a caption and a file body (a link first) it is still the file.
     */
    @VisibleForTesting
    static int attachmentType(final int type, @Nullable final String caption, @Nullable final String body) {
        if (caption == null || body == null || (type != Message.TYPE_TEXT && type != Message.TYPE_PRIVATE)) return type;
        // the file descriptor Conversations stores: url, then at most size|width|height|runtime as numbers
        final String[] parts = body.split("\\|", -1);
        if (parts.length > 5 || eu.siacs.conversations.http.URL.tryParse(parts[0]) == null) return type;
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].matches("\\d{1,19}")) return type;
        }
        return type == Message.TYPE_PRIVATE ? Message.TYPE_PRIVATE_FILE : Message.TYPE_FILE;
    }

    // what the Conversations caption message takes from its file message: never a file field
    private static final java.util.Set<String> CAPTION_MESSAGE_COLUMNS = java.util.Set.of(
            Message.CONVERSATION, Message.COUNTERPART, Message.TRUE_COUNTERPART, Message.ENCRYPTION,
            Message.FINGERPRINT, Message.READ, Message.OCCUPANT_ID, Message.CARBON);

    /**
     * Tatrak: the key of a version 4 backup, Argon2id with monocles chat's parameters (3 passes, 64
     * MiB, 4 lanes) and a 256-bit AES key.
     */
    public static byte[] getKey(final String password, final byte[] salt) {
        final var parameters =
                new org.bouncycastle.crypto.params.Argon2Parameters.Builder(
                                org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                        .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                        .withIterations(3)
                        .withMemoryAsKB(65536)
                        .withParallelism(4)
                        .withSalt(salt)
                        .build();
        final var generator = new org.bouncycastle.crypto.generators.Argon2BytesGenerator();
        generator.init(parameters);
        final byte[] key = new byte[32];
        generator.generateBytes(password.toCharArray(), key);
        return key;
    }

    /** The key of a version 2 backup (Conversations' format). */
    public static byte[] getLegacyKey(final String password, final byte[] salt)
            throws InvalidKeySpecException {
        final SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1024, 128))
                .getEncoded();
    }

    private void notifySuccess(final List<Uri> locations) {
        final var context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final String path = appSettings.getBackupLocationAsPath();
        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final ArrayList<Uri> uris = new ArrayList<>();
        for (final Uri uri : locations) {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                uris.add(FileBackend.getUriForFile(context, new File(uri.getPath())));
            } else {
                uris.add(uri);
            }
        }
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(MIME_TYPE);
        final Intent chooser =
                Intent.createChooser(intent, context.getString(R.string.share_backup_files));
        final var shareFilesIntent =
                PendingIntent.getActivity(context, 190, chooser, PENDING_INTENT_FLAGS);

        // Tatrak: say how many attachments were left out
        final String text = context.getString(R.string.notification_backup_created_subtitle, path)
                + (leftOut == 0 ? "" : "\n" + context.getResources().getQuantityString(
                        R.plurals.tatrak_backup_files_left_out, leftOut, leftOut));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "backup");
        builder.setContentTitle(context.getString(R.string.notification_backup_created_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_archive_24dp);

        builder.addAction(
                R.drawable.ic_share_24dp,
                context.getString(R.string.share_backup_files),
                shareFilesIntent);
        builder.setLocalOnly(true);
        final var notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.notify(BACKUP_CREATED_NOTIFICATION_ID, builder.build());
    }

    private static class Progress {
        private final NotificationCompat.Builder notification;
        private final int max;
        private final int count;

        private Progress(
                final NotificationCompat.Builder notification, final int max, final int count) {
            this.notification = notification;
            this.max = max;
            this.count = count;
        }

        private Notification build(int percentage) {
            notification.setProgress(max * 100, count * 100 + percentage, false);
            return notification.build();
        }
    }

    @VisibleForTesting
    static class WorkStoppedException extends Exception {}
}
