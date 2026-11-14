package eu.siacs.conversations.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import com.google.gson.stream.JsonReader;
import eu.siacs.conversations.xmpp.Jid;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

/**
 * Tatrak: the backup password stays out of WorkManager's database; an import commits only a fully
 * read and verified backup, skipping only rows that hit a constraint (never accounts rows).
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class ImportBackupWorkerTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String SECRET_NAME = "secret chat name 7f3a";
    private static final Uri URI = Uri.parse("file:///nonexistent/test.ceb");
    private static final byte[] KEY = new byte[32];
    private static final byte[] IV = new byte[12];
    // the generation staged media is encrypted for, and one a backup can carry
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
    private static final String OLD_GENERATION = "9a1c0c0e-58f1-4d7a-b7c2-5f6d3e2a4b99";
    private static final byte[] MEDIA_KEY = new byte[32];
    private static final byte[] OLD_MEDIA_KEY = new byte[32];
    static {
        Arrays.fill(MEDIA_KEY, (byte) 11);
        Arrays.fill(OLD_MEDIA_KEY, (byte) 10);
    }

    private String failInsertId;
    private JvmDurable durable;
    private SQLiteDatabase db;
    private androidx.sqlite.db.SupportSQLiteDatabase supportDb;

    @Before
    public void createDatabase() {
        db = SQLiteDatabase.create(null);
        // The production parser uses the shared SQLite contract; Robolectric supplies platform SQLite.
        supportDb = (androidx.sqlite.db.SupportSQLiteDatabase) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {androidx.sqlite.db.SupportSQLiteDatabase.class},
                (proxy, method, args) -> {
                    try {
                        if (method.getName().equals("query") && args[0] instanceof String sql) {
                            final String[] bind = args.length == 1 || args[1] == null ? null
                                    : Arrays.stream((Object[]) args[1]).map(String::valueOf).toArray(String[]::new);
                            return db.rawQuery(sql, bind);
                        }
                        if (method.getName().equals("insert")) {
                            if (failInsertId != null && failInsertId.equals(((android.content.ContentValues) args[2]).getAsString("uuid"))) {
                                throw new SQLException("injected I/O failure");
                            }
                            return db.insertWithOnConflict((String) args[0], null,
                                    (android.content.ContentValues) args[2], (int) args[1]);
                        }
                        return db.getClass().getMethod(method.getName(), method.getParameterTypes()).invoke(db, args);
                    } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
        db.execSQL("create table t (a integer)");
        db.execSQL(
                "create table accounts (uuid text primary key, username text, server text,"
                        + " password text, keys text)");
        db.execSQL("create table conversations (uuid text primary key, name text)");
        durable = JvmDurable.Companion.install(); // the media store's rename and fsync on the JVM
    }

    @After
    public void closeDatabase() {
        JvmDurable.Companion.uninstall();
        db.close();
    }

    /** A store that opens files of [generation]: what the app has after the import's commit. */
    private static EncryptedMediaStore store(final Application context, final String generation, final byte[] key) {
        return new EncryptedMediaStore(context.getFilesDir(), context.getCacheDir(), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return generation; }
            @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(generation, java.util.Map.of(generation, key)); }
        }, null);
    }

    private static byte[] readAll(final EncryptedMediaStore store, final String path) throws IOException {
        try (final InputStream in = store.inputStream(new java.io.File(path))) {
            return com.google.common.io.ByteStreams.toByteArray(in);
        }
    }

    /** A worker as importBackup sets it up for a normal import into [GENERATION], with its stage folder. */
    private ImportBackupWorker mediaWorker(final Application context) {
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, true));
        worker.stagedFilesDirectory = new java.io.File(context.getCacheDir(), "stage-" + UUID.randomUUID() + "/files");
        worker.targetGeneration = GENERATION;
        worker.targetKey = MEDIA_KEY;
        worker.knownGenerations.add(GENERATION);
        return worker;
    }

    /** Runs the worker's importRow over the rows and completes the last file, as importBackup does. */
    private void importMedia(final ImportBackupWorker worker, final String... rows) throws IOException {
        final var jsonReader = new JsonReader(new StringReader("[" + String.join(",", rows) + "]"));
        jsonReader.beginArray();
        try {
            ImportBackupWorker.importRows(supportDb, jsonReader, new ByteArrayInputStream(new byte[0]),
                    () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
            worker.finishStagedFile();
        } finally {
            worker.abortStagedFile();
        }
    }

    @Test
    public void stagedCommitUsesNamesAcrossDifferentPhysicalColumnOrders() {
        db.execSQL("ATTACH DATABASE ':memory:' AS tatrak_import");
        try {
            db.execSQL("CREATE TABLE tatrak_import.conversations (name text, uuid text primary key)");
            db.execSQL("INSERT INTO tatrak_import.conversations VALUES ('history name', 'c1')");
            ImportBackupWorker.insertStagedTable(supportDb, "conversations");
            try (final var cursor = db.rawQuery("SELECT uuid,name FROM conversations", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("c1", cursor.getString(0));
                assertEquals("history name", cursor.getString(1));
            }
        } finally { db.execSQL("DETACH DATABASE tatrak_import"); }
    }

    @Test
    public void workDataDoesNotContainThePassword() {
        final Data data = ImportBackupWorker.data(PASSWORD, URI, true);
        for (final Object value : data.getKeyValueMap().values()) {
            assertFalse(String.valueOf(value).contains(PASSWORD));
        }
        assertFalse(Arrays.toString(data.toByteArray()).contains(PASSWORD));
        assertFalse(
                new String(data.toByteArray(), StandardCharsets.ISO_8859_1).contains(PASSWORD));
        assertEquals(PASSWORD, ImportBackupWorker.takePassword(data));
        // handed out once, then forgotten
        assertNull(ImportBackupWorker.takePassword(data));
    }

    @Test
    public void workerFailsWhenThePasswordIsGone() {
        final Data data = ImportBackupWorker.data(PASSWORD, URI, false);
        ImportBackupWorker.takePassword(data); // as if the process had died since
        final ListenableWorker.Result result = worker(data).doWork();
        assertTrue(result instanceof ListenableWorker.Result.Failure);
        final Data output = result.getOutputData();
        assertEquals(
                ImportBackupWorker.Reason.PASSWORD_MISSING,
                ImportBackupWorker.Reason.valueOfOrGeneric(output.getString("reason")));
        assertEquals(URI, ImportBackupWorker.uri(output));
        assertFalse(ImportBackupWorker.includeOmemo(output));
    }

    @Test
    public void unusedPasswordExpires() {
        final Data data = ImportBackupWorker.data(PASSWORD, URI, true);
        final var lifetime = TimeUnit.MINUTES.toMillis(ImportBackupWorker.PASSWORD_LIFETIME_MINUTES);
        ShadowLooper.idleMainLooper(lifetime - 1000, TimeUnit.MILLISECONDS);
        final Data other = ImportBackupWorker.data(PASSWORD, URI, true);
        ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS);
        assertNull(ImportBackupWorker.takePassword(data));
        // a later request keeps its own full lifetime
        assertEquals(PASSWORD, ImportBackupWorker.takePassword(other));
    }

    @Test
    public void conflictingRowIsSkippedAndOthersCommit() throws Exception {
        ShadowLog.clear();
        final Data output =
                importRealRows(
                        account("a1"),
                        conversation("c1"),
                        conversation("c1", SECRET_NAME),
                        conversation("c2"));
        assertEquals(1, ImportBackupWorker.skippedRows(output));
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "accounts"));
        assertEquals(2, DatabaseUtils.queryNumEntries(db, "conversations"));
        assertTrue(
                ShadowLog.getLogs().stream()
                        .anyMatch(i -> String.valueOf(i.msg).contains("skipping row in conversations")));
        assertNotLogged(PASSWORD);
        assertNotLogged(SECRET_NAME);
    }

    @Test
    public void nonConstraintErrorRollsEverythingBack() {
        // A real database error must abort; unknown source columns are supported omissions.
        failInsertId = "c2";
        assertThrows(
                SQLException.class,
                () ->
                        importRealRows(
                                account("a1"),
                                conversation("c1"),
                                "{\"table\":\"conversations\",\"values\":{\"uuid\":\"c2\","
                                        + "\"no_such_column\":1}}",
                                conversation("c3")));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "accounts"));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "conversations"));
        assertFalse(db.inTransaction());
    }

    @Test
    public void conflictingAccountRollsEverythingBack() {
        ShadowLog.clear();
        assertThrows(
                IOException.class,
                () -> importRealRows(account("a1"), conversation("c1"), account("a1")));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "accounts"));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "conversations"));
        assertFalse(db.inTransaction());
        assertNotLogged(PASSWORD);
    }

    /** Runs the worker's own importRow over plain JSON rows. */
    private Data importRealRows(final String... rows) throws IOException {
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, true));
        final var jsonReader = new JsonReader(new StringReader("[" + String.join(",", rows) + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(
                supportDb,
                jsonReader,
                new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        return worker.successResult().getOutputData();
    }

    @Test
    public void successfulRestoreWithoutConflictsHasNoWarning() throws IOException {
        assertEquals(
                0,
                ImportBackupWorker.skippedRows(
                        importRealRows(account("a1"), conversation("c1"))));
        // Completed work from older versions has no skipped-rows key.
        assertEquals(0, ImportBackupWorker.skippedRows(Data.EMPTY));
    }

    @Test
    public void skippedRowsAreTotalledAcrossTables() throws IOException {
        db.execSQL("create table messages (uuid text primary key)");
        final String message = "{\"table\":\"messages\",\"values\":{\"uuid\":\"m1\"}}";
        final Data output =
                importRealRows(
                        account("a1"), conversation("c1"), conversation("c1"), message, message);
        assertEquals(2, ImportBackupWorker.skippedRows(output));
    }

    @Test
    public void onlyRealValuesOfUnknownColumnsAreReported() throws IOException {
        db.execSQL("create table messages (uuid text primary key)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, true));
        final var jsonReader =
                new JsonReader(
                        new StringReader(
                                "["
                                        + String.join(
                                                ",",
                                                account("a1"),
                                                conversation("c1"),
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m1\",\"subject\":null,\"flag\":\"0\"}}",
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m2\",\"subject\":\"hi\",\"flag\":\"1\"}}")
                                        + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(
                supportDb,
                jsonReader,
                new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        final String preview = worker.previewText();
        assertTrue(preview, preview.contains("messages.subject: 1\n"));
        assertTrue(preview, preview.contains("messages.flag: 1\n"));
    }

    @Test
    public void disappearingMessagesAreNotImported() throws IOException {
        db.execSQL("create table messages (uuid text primary key)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, true));
        final var jsonReader =
                new JsonReader(
                        new StringReader(
                                "["
                                        + String.join(
                                                ",",
                                                account("a1"),
                                                conversation("c1"),
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m1\",\"ephemeral_timer\":0,\"expire_at\":0}}",
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m2\",\"ephemeral_timer\":60,\"expire_at\":0}}",
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m3\",\"ephemeral_timer\":0,\"expire_at\":1700000000000}}",
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m4\",\"retractId\":\"r1\"}}")
                                        + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(
                supportDb,
                jsonReader,
                new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "messages"));
        assertTrue(worker.previewText(), worker.previewText().contains(
                ApplicationProvider.getApplicationContext().getString(
                        eu.siacs.conversations.R.string.tatrak_backup_omit_disappearing) + ": 2\n"));
        assertTrue(worker.previewText(), worker.previewText().contains(
                ApplicationProvider.getApplicationContext().getString(
                        eu.siacs.conversations.R.string.tatrak_backup_omit_retracted) + ": 1\n"));
    }

    @Test
    public void monoclesOwnMessageTypesAreNotImported() throws IOException {
        db.execSQL("create table messages (uuid text primary key, type integer, body text)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, true));
        worker.sourceMonocles = true;
        final var jsonReader =
                new JsonReader(
                        new StringReader(
                                "["
                                        + String.join(
                                                ",",
                                                account("a1"),
                                                conversation("c1"),
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m1\",\"type\":0,\"body\":\"hi\"}}",
                                                // monocles story: Tatrak's 7 is the deleted placeholder
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m2\",\"type\":7,\"body\":\"my story\"}}")
                                        + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(
                supportDb,
                jsonReader,
                new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "messages"));
        assertEquals("hi", DatabaseUtils.stringForQuery(db, "select body from messages", null));
        assertTrue(worker.previewText(), worker.previewText().contains(
                ApplicationProvider.getApplicationContext().getString(
                        eu.siacs.conversations.R.string.tatrak_backup_omit_type, 7) + ": 1\n"));
    }

    private long importedTypeSeven(final boolean tatrakSource) throws IOException {
        db.execSQL("create table messages (uuid text primary key, type integer, body text)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, true));
        worker.sourceTatrak = tatrakSource;
        final var jsonReader = new JsonReader(new StringReader("[" + String.join(",",
                account("a1"), conversation("c1"),
                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m2\",\"type\":7,\"body\":\"x\"}}") + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(supportDb, jsonReader, new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        return DatabaseUtils.queryNumEntries(db, "messages");
    }

    @Test
    public void tatrakOnlyMessageTypesAreImportedOnlyFromTatrakBackups() throws IOException {
        assertEquals(0, importedTypeSeven(false));
    }

    @Test
    public void tatrakBackupKeepsItsOwnMessageTypes() throws IOException {
        assertEquals(1, importedTypeSeven(true));
    }

    @Test
    public void exportedMonoclesFileBodyComesBackAsTheTatrakBody() throws IOException {
        db.execSQL("create table messages (uuid text primary key, type integer, body text)");
        final String original = "https://up.example.com/a.jpg|71908|1600|1200";
        final String params = ExportBackupWorker.monoclesFileParams(1, original);
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, true));
        final var jsonReader = new JsonReader(new StringReader("[" + String.join(",",
                account("a1"), conversation("c1"),
                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m1\",\"type\":1,\"body\":\""
                        + ExportBackupWorker.monoclesFileBody(original) + "\",\"fileParams\":\"" + params + "\"}}") + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(supportDb, jsonReader, new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        assertEquals(original, DatabaseUtils.stringForQuery(db, "select body from messages", null));
    }

    @Test
    public void monoclesFileParamsBecomeTheFileBody() throws IOException {
        db.execSQL("create table messages (uuid text primary key, type integer, body text)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, true));
        final String url = "https://up.example.com/a.jpg";
        final var jsonReader =
                new JsonReader(
                        new StringReader(
                                "["
                                        + String.join(
                                                ",",
                                                account("a1"),
                                                conversation("c1"),
                                                // oob: url in the body, params add size and size of the picture
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m1\",\"type\":1,\"body\":\"" + url + "\",\"fileParams\":\"" + url + "|1000|640|480\"}}",
                                                // caption in the body
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m2\",\"type\":2,\"body\":\"see this\",\"fileParams\":\"" + url + "|1000\"}}",
                                                // text message: untouched
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m3\",\"type\":0,\"body\":\"hi\",\"fileParams\":\"" + url + "\"}}")
                                        + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(
                supportDb,
                jsonReader,
                new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        assertEquals(url + "|1000|640|480", DatabaseUtils.stringForQuery(db, "select body from messages where uuid='m1'", null));
        assertEquals(url + "|1000", DatabaseUtils.stringForQuery(db, "select body from messages where uuid='m2'", null));
        assertEquals("hi", DatabaseUtils.stringForQuery(db, "select body from messages where uuid='m3'", null));
        final String preview = worker.previewText();
        assertTrue(preview, preview.contains(
                ApplicationProvider.getApplicationContext().getString(
                        eu.siacs.conversations.R.string.tatrak_backup_omit_caption) + ": 1\n"));
        assertTrue(preview, preview.contains("messages.fileParams: 1\n"));
    }

    @Test
    public void monoclesReplyPayloadBecomesReplyTo() {
        final var values = new android.content.ContentValues();
        values.put("payloads",
                "<reply xmlns=\"urn:xmpp:reply:0\" id=\"orig-1\" to=\"friend@example.com/phone\"/>"
                        + "<fallback xmlns=\"urn:xmpp:fallback:0\" for=\"urn:xmpp:reply:0\"><body start=\"0\" end=\"8\"/></fallback>"
                        + "<html xmlns=\"http://jabber.org/protocol/xhtml-im\"/>");
        ImportBackupWorker.restoreReplyTo(values);
        final var replyTo = eu.siacs.conversations.entities.ReplyTo.fromString(
                values.getAsString(eu.siacs.conversations.entities.Message.REPLY_TO));
        assertEquals("orig-1", replyTo.id);
        assertEquals(0, replyTo.fallbackStart);
        assertEquals(8, replyTo.fallbackEnd);

        final var broken = new android.content.ContentValues();
        broken.put("payloads", "<reply xmlns=");
        ImportBackupWorker.restoreReplyTo(broken);
        assertEquals(null, broken.getAsString(eu.siacs.conversations.entities.Message.REPLY_TO));
    }

    private static String account(final String uuid) {
        return "{\"table\":\"accounts\",\"values\":{\"uuid\":\""
                + uuid
                + "\",\"username\":\"tester\",\"server\":\"example.com\",\"password\":\""
                + PASSWORD
                + "\",\"keys\":null}}";
    }

    private static String conversation(final String uuid) {
        return conversation(uuid, "chat");
    }

    private static String conversation(final String uuid, final String name) {
        return "{\"table\":\"conversations\",\"values\":{\"uuid\":\""
                + uuid
                + "\",\"name\":\""
                + name
                + "\"}}";
    }

    private static void assertNotLogged(final String secret) {
        for (final ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse(item.msg, String.valueOf(item.msg).contains(secret));
            assertFalse(
                    item.msg,
                    item.throwable != null
                            && android.util.Log.getStackTraceString(item.throwable)
                                    .contains(secret));
        }
    }

    private static ImportBackupWorker worker(final Data data) {
        return new ImportBackupWorker(
                ApplicationProvider.getApplicationContext(),
                new WorkerParameters(
                        UUID.randomUUID(),
                        data,
                        List.of(),
                        new WorkerParameters.RuntimeExtras(),
                        0,
                        0,
                        Runnable::run,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    @Test
    public void intactBackupCommitsEveryRow() throws Exception {
        importRows(sealed());
        assertEquals(2, DatabaseUtils.queryNumEntries(db, "t"));
        assertFalse(db.inTransaction());
    }

    @Test
    public void truncatedBackupImportsNothing() throws Exception {
        final byte[] sealed = sealed();
        assertImportsNothing(Arrays.copyOf(sealed, sealed.length - 1));
        assertImportsNothing(Arrays.copyOf(sealed, sealed.length / 2));
    }

    @Test
    public void corruptedTagImportsNothing() throws Exception {
        final byte[] sealed = sealed();
        sealed[sealed.length - 1] ^= 1;
        assertImportsNothing(sealed);
    }

    private void assertImportsNothing(final byte[] sealed) {
        assertThrows(IOException.class, () -> importRows(sealed));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "t"));
        assertFalse(db.inTransaction());
    }

    /** Reads the stream as ImportBackupWorker does after the header. */
    private void importRows(final byte[] sealed) throws IOException {
        final AEADBlockCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(false, new AEADParameters(new KeyParameter(KEY), 128, IV));
        final InputStream cipherInputStream =
                new CipherInputStream(new ByteArrayInputStream(sealed), cipher);
        final JsonReader jsonReader =
                new JsonReader(
                        new InputStreamReader(
                                new GZIPInputStream(cipherInputStream), StandardCharsets.UTF_8));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(
                supportDb,
                jsonReader,
                cipherInputStream,
                () -> {
                    jsonReader.beginObject();
                    jsonReader.nextName();
                    db.execSQL("insert into t (a) values (?)", new Object[] {jsonReader.nextInt()});
                    jsonReader.endObject();
                });
    }

    /**
     * A gzip of exactly whole AES blocks: GCM then hands out every plaintext byte before the tag
     * is checked, so only reading to the end of the stream catches a bad tag.
     */
    private static byte[] sealed() throws Exception {
        byte[] gzip = null;
        for (int padding = 0; gzip == null || gzip.length % 16 != 0; ++padding) {
            assertTrue(padding < 1000);
            // whitespace that does not compress away, so the gzip length moves
            final var json =
                    "[{\"a\":1},{\"a\":2}]"
                            + Long.toBinaryString(padding * 2654435761L)
                                    .replace('0', ' ')
                                    .replace('1', '\n');
            final var out = new ByteArrayOutputStream();
            try (final var gzipOut = new GZIPOutputStream(out)) {
                gzipOut.write(json.getBytes(StandardCharsets.UTF_8));
            }
            gzip = out.toByteArray();
        }
        final AEADBlockCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(true, new AEADParameters(new KeyParameter(KEY), 128, IV));
        final byte[] sealed = new byte[cipher.getOutputSize(gzip.length)];
        final int length = cipher.processBytes(gzip, 0, gzip.length, sealed, 0);
        cipher.doFinal(sealed, length);
        return sealed;
    }

    @Test
    public void restoreStatusLineFollowsProgress() {
        final Application context = ApplicationProvider.getApplicationContext();
        assertNull(ImportBackupWorker.status(context, Data.EMPTY));
        assertEquals("Reading backup… 42%",
                ImportBackupWorker.status(context, new Data.Builder().putInt("tatrak_percent", 42).build()));
        assertEquals("Reading backup…",
                ImportBackupWorker.status(context, new Data.Builder().putInt("tatrak_percent", -1).build()));
        assertTrue(ImportBackupWorker.status(context, new Data.Builder().putInt("tatrak_percent", 101).build())
                .startsWith("Saving chats"));
    }

    private static String fileRow(final String path, final int sequence, final String content) {
        return "{\"table\":\"files\",\"values\":{\"path\":\"" + path + "\",\"sequence\":" + sequence
                + ",\"content\":\"" + java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)) + "\"}}";
    }

    private static String fileMessage(final String uuid, final String path) {
        return "{\"table\":\"messages\",\"values\":{\"uuid\":\"" + uuid + "\",\"type\":1,\"body\":\"https://up.example.com/a.jpg\""
                + ",\"relativeFilePath\":\"" + path + "\",\"sharedStorage\":0}}";
    }

    @Test
    public void monoclesFilesBecomeEncryptedAttachments() throws IOException {
        db.execSQL("create table messages (uuid text primary key, type integer, body text, relativeFilePath text, sharedStorage integer)");
        final Application context = ApplicationProvider.getApplicationContext();
        final var worker = mediaWorker(context);
        final java.io.File stage = worker.stagedFilesDirectory;
        importMedia(worker,
                account("a1"),
                conversation("c1"),
                fileRow("${FILES}/media/a.jpg", 0, "hel"),
                fileRow("${FILES}/media/a.jpg", 1, "lo"),
                fileRow("${FILES}/avatars/abc", 0, "avatar"),
                fileRow("${FILES}/media/empty.bin", 0, ""),
                fileMessage("m1", "${FILES}/media/a.jpg"),
                fileMessage("m2", "${FILES}/../shared_prefs/x.xml"),
                fileMessage("m3", "${EXTERNAL}/Pictures/p.jpg"),
                fileMessage("m4", "${FILES}/media/empty.bin"));

        final String path = DatabaseUtils.stringForQuery(db, "select relativeFilePath from messages where uuid='m1'", null);
        final java.io.File attachments = new java.io.File(context.getFilesDir(), "attachments");
        assertTrue(path, path.matches(java.util.regex.Pattern.quote(attachments.getAbsolutePath() + "/tatrak_" + GENERATION + "_") + "[0-9a-f]{32}\\.jpg"));
        assertEquals(0, DatabaseUtils.longForQuery(db, "select sharedStorage from messages where uuid='m1'", null));
        assertNull(DatabaseUtils.stringForQuery(db, "select relativeFilePath from messages where uuid='m2'", null));
        assertEquals(1, DatabaseUtils.longForQuery(db, "select sharedStorage from messages where uuid='m3'", null));
        final String empty = DatabaseUtils.stringForQuery(db, "select relativeFilePath from messages where uuid='m4'", null);
        // the avatar is not staged; the staged files are encrypted, complete, and nothing is left half written
        assertEquals(2, stage.listFiles().length);
        for (final java.io.File staged : stage.listFiles()) {
            assertTrue(staged.getName(), staged.getName().startsWith("tatrak_" + GENERATION + "_"));
            assertEquals("TATRKENC", new String(java.nio.file.Files.readAllBytes(staged.toPath()), 0, 8, StandardCharsets.US_ASCII));
        }

        durable.getSteps().clear();
        ImportBackupWorker.moveStagedFiles(stage, attachments);
        assertEquals(0, stage.listFiles().length);
        // the attachment folder made by a first import is synced in its parent, then the files in it
        assertEquals("fsync files", durable.getSteps().get(0));
        assertEquals("fsync attachments", durable.getSteps().get(durable.getSteps().size() - 1));
        final var store = store(context, GENERATION, MEDIA_KEY);
        assertEquals("hello", new String(readAll(store, path), StandardCharsets.UTF_8));
        // an empty attachment's single record became an empty encrypted file
        assertEquals(0, readAll(store, empty).length);
        // under another key the files do not open
        assertThrows(IOException.class, () -> readAll(store(context, GENERATION, OLD_MEDIA_KEY), path));
    }

    @Test
    public void fileChunksOutOfOrderFailTheImportAndLeaveNoFile() {
        db.execSQL("create table messages (uuid text primary key, type integer, body text, relativeFilePath text, sharedStorage integer)");
        final Application context = ApplicationProvider.getApplicationContext();
        final String a = "${FILES}/media/a.jpg", b = "${FILES}/media/b.jpg";
        final String[][] wrong = {
            {fileRow(a, 0, "x"), fileRow(a, 2, "z")}, // a gap
            {fileRow(a, 0, "x"), fileRow(a, 0, "x")}, // a duplicate
            {fileRow(a, 1, "y")}, // the first chunk is missing
            {fileRow(a, 0, "x"), fileRow(b, 0, "x"), fileRow(a, 1, "y")}, // another file in between
            {fileRow(a, 0, "x"), fileRow(b, 0, "x"), fileRow(a, 0, "x")}, // a file sent twice
        };
        for (final String[] rows : wrong) {
            final var worker = mediaWorker(context);
            final var all = new java.util.ArrayList<>(List.of(account("a1"), conversation("c1")));
            all.addAll(Arrays.asList(rows));
            assertThrows(String.join(",", rows), IOException.class, () -> importMedia(worker, all.toArray(new String[0])));
            final String[] left = worker.stagedFilesDirectory.list();
            // the files before the fault are complete, the one being written is gone
            if (left != null) for (final String name : left) assertFalse(name, name.endsWith(".part"));
        }
        // without a media key of its own the database imports no file
        final var worker = mediaWorker(context);
        worker.targetKey = null;
        assertThrows(IOException.class, () -> importMedia(worker, account("a1"), conversation("c1"), fileRow(a, 0, "x")));
    }

    @Test
    public void mediaKeysAreImportedBeforeTheMessagesAndNeverReplaceOne() throws IOException {
        db.execSQL("create table messages (uuid text primary key, type integer, body text, relativeFilePath text, sharedStorage integer)");
        MediaKeyTable.create(supportDb); // the stage database, with its own pending key
        final Application context = ApplicationProvider.getApplicationContext();
        final var worker = mediaWorker(context);
        final String hex = com.google.common.io.BaseEncoding.base16().lowerCase().encode(OLD_MEDIA_KEY);
        final String other = com.google.common.io.BaseEncoding.base16().lowerCase().encode(MEDIA_KEY);
        final String attachments = new java.io.File(context.getFilesDir(), "attachments").getAbsolutePath();
        final String old = attachments + "/tatrak_" + OLD_GENERATION + "_" + "ab".repeat(16) + ".jpg";
        final String unknown = attachments + "/tatrak_" + UUID.randomUUID() + "_" + "cd".repeat(16) + ".jpg";
        importMedia(worker,
                account("a1"),
                conversation("c1"),
                "{\"table\":\"tatrak_media_key\",\"values\":{\"generation\":\"" + OLD_GENERATION + "\",\"master_key\":\"" + hex + "\"}}",
                "{\"table\":\"tatrak_media_key\",\"values\":{\"generation\":\"" + OLD_GENERATION + "\",\"master_key\":\"" + other + "\"}}",
                fileMessage("m1", old),
                fileMessage("m2", unknown),
                fileMessage("m3", attachments + "/tatrak_junk.jpg"),
                fileMessage("m4", attachments + "/plain.jpg"));
        // the backup's key came in as an imported key; the second row did not replace it
        final var keys = MediaKeyTable.read(supportDb);
        assertNull(keys.getOwn()); // pending stays unbound
        assertEquals(1, keys.getByGeneration().size());
        assertTrue(Arrays.equals(OLD_MEDIA_KEY, keys.getByGeneration().get(OLD_GENERATION)));
        assertTrue(worker.knownGenerations.contains(OLD_GENERATION));
        // only a file of a generation with a known key keeps its path
        assertEquals(old, DatabaseUtils.stringForQuery(db, "select relativeFilePath from messages where uuid='m1'", null));
        assertNull(DatabaseUtils.stringForQuery(db, "select relativeFilePath from messages where uuid='m2'", null));
        assertNull(DatabaseUtils.stringForQuery(db, "select relativeFilePath from messages where uuid='m3'", null));
        assertEquals(attachments + "/plain.jpg", DatabaseUtils.stringForQuery(db, "select relativeFilePath from messages where uuid='m4'", null));
        // a malformed key fails the import
        assertThrows(IllegalArgumentException.class, () -> importMedia(mediaWorker(context), account("a2"),
                "{\"table\":\"tatrak_media_key\",\"values\":{\"generation\":\"not-a-uuid\",\"master_key\":\"" + hex + "\"}}"));
    }

    @Test
    public void backupFilePathsStayInsideAllowedFolders() {
        final Application context = ApplicationProvider.getApplicationContext();
        final java.util.Set<String> known = java.util.Set.of(GENERATION);
        final String attachment = new java.io.File(context.getFilesDir(), "attachments/x.jpg").getAbsolutePath();
        assertEquals(attachment, ImportBackupWorker.confinedPath(context, attachment, known));
        assertEquals("legacy.jpg", ImportBackupWorker.confinedPath(context, "legacy.jpg", known));
        assertNull(ImportBackupWorker.confinedPath(context, new java.io.File(context.getFilesDir(), "attachments/../../shared_prefs/p.xml").getAbsolutePath(), known));
        assertNull(ImportBackupWorker.confinedPath(context, "/data/data/other.app/files/x", known));
        assertNull(ImportBackupWorker.confinedPath(context, "sub/legacy.jpg", known));
        assertNull(ImportBackupWorker.confinedPath(context, "..", known));
        assertNull(ImportBackupWorker.confinedPath(context, "${UNKNOWN}/x", known));
        // an encrypted name is kept only with its generation's key; any other tatrak_ name opens never
        final String attachments = new java.io.File(context.getFilesDir(), "attachments").getAbsolutePath();
        final String mine = attachments + "/tatrak_" + GENERATION + "_" + "ab".repeat(16) + ".jpg";
        final String foreign = attachments + "/tatrak_" + OLD_GENERATION + "_" + "ab".repeat(16) + ".jpg";
        assertEquals(mine, ImportBackupWorker.confinedPath(context, mine, known));
        assertNull(ImportBackupWorker.confinedPath(context, foreign, known));
        assertEquals(foreign, ImportBackupWorker.confinedPath(context, foreign, java.util.Set.of(GENERATION, OLD_GENERATION)));
        assertNull(ImportBackupWorker.confinedPath(context, attachments + "/tatrak_x.jpg", known));
        assertNull(ImportBackupWorker.confinedPath(context, attachments + "/tatrak_" + GENERATION + "_" + "ab".repeat(16) + ".jpg.pgp", known));
    }
}
