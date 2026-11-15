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
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
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
        final Data data = ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL);
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
        final Data data = ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.TRUST);
        ImportBackupWorker.takePassword(data); // as if the process had died since
        final ListenableWorker.Result result = worker(data).doWork();
        assertTrue(result instanceof ListenableWorker.Result.Failure);
        final Data output = result.getOutputData();
        assertEquals(
                ImportBackupWorker.Reason.PASSWORD_MISSING,
                ImportBackupWorker.Reason.valueOfOrGeneric(output.getString("reason")));
        assertEquals(URI, ImportBackupWorker.uri(output));
        assertEquals(ImportBackupWorker.OmemoImport.TRUST, ImportBackupWorker.omemoImport(output));
    }

    @Test
    public void unusedPasswordExpires() {
        final Data data = ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL);
        final var lifetime = TimeUnit.MINUTES.toMillis(ImportBackupWorker.PASSWORD_LIFETIME_MINUTES);
        ShadowLooper.idleMainLooper(lifetime - 1000, TimeUnit.MILLISECONDS);
        final Data other = ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL);
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
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
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
        db.execSQL("create table messages (uuid text primary key, occupantId text)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
        final var jsonReader =
                new JsonReader(
                        new StringReader(
                                "["
                                        + String.join(
                                                ",",
                                                account("a1"),
                                                conversation("c1"),
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m1\",\"subject\":null,\"flag\":\"0\","
                                                        + "\"fileParams\":\"\",\"occupant_id\":\"occ-1\"}}",
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
        assertFalse(preview, preview.contains("fileParams")); // an empty string is the default
        // monocles' occupant_id lands in Tatrak's occupantId
        assertFalse(preview, preview.contains("occupant_id"));
        assertEquals("occ-1", DatabaseUtils.stringForQuery(db, "select occupantId from messages where uuid = 'm1'", null));
    }

    @Test
    public void disappearingMessagesAreNotImported() throws IOException {
        db.execSQL("create table messages (uuid text primary key)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
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
                                                "{\"table\":\"messages\",\"values\":{\"uuid\":\"m3\",\"ephemeral_timer\":0,\"expire_at\":1700000000000}}")
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
    }

    @Test
    public void monoclesOwnMessageTypesAreNotImported() throws IOException {
        db.execSQL("create table messages (uuid text primary key, type integer, body text)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
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
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
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
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
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
        db.execSQL("create table messages (uuid text primary key, type integer, body text, tatrak_caption text)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
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
        // the caption stays with its file
        assertEquals("see this", DatabaseUtils.stringForQuery(db, "select tatrak_caption from messages where uuid='m2'", null));
        assertEquals(3, DatabaseUtils.queryNumEntries(db, "messages"));
        final String preview = worker.previewText();
        assertFalse(preview, preview.contains(
                ApplicationProvider.getApplicationContext().getString(eu.siacs.conversations.R.string.tatrak_backup_omit_caption)));
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
        // the formatting was not carried over, so payloads stays and is reported
        assertTrue(values.containsKey("payloads"));

        final var replyOnly = new android.content.ContentValues();
        replyOnly.put("payloads",
                "<reply xmlns=\"urn:xmpp:reply:0\" id=\"orig-2\"/>"
                        + "<fallback xmlns=\"urn:xmpp:fallback:0\" for=\"urn:xmpp:reply:0\"><body start=\"0\" end=\"3\"/></fallback>");
        ImportBackupWorker.restoreReplyTo(replyOnly);
        assertEquals("orig-2", eu.siacs.conversations.entities.ReplyTo.fromString(
                replyOnly.getAsString(eu.siacs.conversations.entities.Message.REPLY_TO)).id);
        assertFalse(replyOnly.containsKey("payloads"));

        // a second reply, or a whole-body fallback, was not carried over: payloads stays reported
        for (final String payloads : new String[] {
                "<reply xmlns=\"urn:xmpp:reply:0\" id=\"a\"/><reply xmlns=\"urn:xmpp:reply:0\" id=\"b\"/>"
                        + "<fallback xmlns=\"urn:xmpp:fallback:0\" for=\"urn:xmpp:reply:0\"><body start=\"0\" end=\"3\"/></fallback>",
                "<reply xmlns=\"urn:xmpp:reply:0\" id=\"a\"/><fallback xmlns=\"urn:xmpp:fallback:0\" for=\"urn:xmpp:reply:0\"/>",
                "<reply xmlns=\"urn:xmpp:reply:0\" id=\"a\"/><fallback xmlns=\"urn:xmpp:fallback:0\" for=\"urn:xmpp:reply:0\">"
                        + "<body start=\"0\" end=\"3\"/><body start=\"5\" end=\"7\"/></fallback>"}) {
            final var partial = new android.content.ContentValues();
            partial.put("payloads", payloads);
            ImportBackupWorker.restoreReplyTo(partial);
            assertEquals("a", eu.siacs.conversations.entities.ReplyTo.fromString(
                    partial.getAsString(eu.siacs.conversations.entities.Message.REPLY_TO)).id);
            assertTrue(payloads, partial.containsKey("payloads"));
        }

        final var broken = new android.content.ContentValues();
        broken.put("payloads", "<reply xmlns=");
        ImportBackupWorker.restoreReplyTo(broken);
        assertEquals(null, broken.getAsString(eu.siacs.conversations.entities.Message.REPLY_TO));
    }

    /** Imports [messages] (the values objects) after an account and a conversation into a full messages table. */
    private ImportBackupWorker importMessages(final String... messages) throws IOException {
        db.execSQL("create table messages (uuid text primary key, conversationUuid text, counterpart text,"
                + " trueCounterpart text, type integer, status integer, timeSent integer, encryption integer,"
                + " axolotl_fingerprint text, read integer, body text, relativeFilePath text, deleted integer,"
                + " oob integer, edited text, reactions text, replyTo text, occupantId text, tatrak_caption text)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
        final var rows = new java.util.ArrayList<String>(List.of(account("a1"), conversation("c1")));
        for (final String values : messages) rows.add("{\"table\":\"messages\",\"values\":{" + values + "}}");
        final var jsonReader = new JsonReader(new StringReader("[" + String.join(",", rows) + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(supportDb, jsonReader, new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        return worker;
    }

    private String column(final String column, final String uuid) {
        return DatabaseUtils.stringForQuery(db, "select " + column + " from messages where uuid = ?", new String[] {uuid});
    }

    @Test
    public void occupantIdDuplicatesAreNotReportedAndOccupantIdWins() throws IOException {
        final var worker = importMessages(
                "\"uuid\":\"m1\",\"occupantId\":\"occ-1\",\"occupant_id\":\"occ-1\"",
                "\"uuid\":\"m2\",\"occupantId\":\"old\",\"occupant_id\":\"new\"",
                "\"uuid\":\"m3\",\"occupantId\":\"kept\",\"occupant_id\":null");
        assertEquals("occ-1", column("occupantId", "m1"));
        assertEquals("new", column("occupantId", "m2")); // monocles' reader takes a non-null occupant_id
        assertEquals("kept", column("occupantId", "m3"));
        assertFalse(worker.previewText(), worker.previewText().contains("occupant_id"));
    }

    @Test
    public void subjectBecomesTheFirstLineOfATextMessage() throws IOException {
        final var worker = importMessages(
                "\"uuid\":\"m1\",\"type\":0,\"body\":\"hello\",\"subject\":\"Topic\"",
                "\"uuid\":\"m2\",\"type\":0,\"body\":\"\",\"subject\":\"0\"",
                // the quote fallback (0..6, \"> q\\n\" in code points) moves with the text
                "\"uuid\":\"m3\",\"type\":0,\"body\":\"> q\\nanswer\",\"subject\":\"Re\",\"payloads\":\""
                        + "<reply xmlns='urn:xmpp:reply:0' id='orig'/><fallback xmlns='urn:xmpp:fallback:0'"
                        + " for='urn:xmpp:reply:0'><body start='0' end='4'/></fallback>\"",
                // a subject cannot join an OpenPGP body or a file
                "\"uuid\":\"m4\",\"type\":0,\"encryption\":1,\"body\":\"-----BEGIN\",\"subject\":\"secret topic\"",
                "\"uuid\":\"m5\",\"type\":2,\"body\":\"https://up.example.com/a.pdf\",\"subject\":\"0\"");
        assertEquals("Topic\nhello", column("body", "m1"));
        assertEquals("0", column("body", "m2")); // a subject \"0\" is text, not a default
        assertEquals("Re\n> q\nanswer", column("body", "m3"));
        final var replyTo = eu.siacs.conversations.entities.ReplyTo.fromString(column("replyTo", "m3"));
        assertEquals("Re\nanswer", replyTo.stripFallback(column("body", "m3")));
        assertEquals("-----BEGIN", column("body", "m4"));
        assertTrue(worker.previewText(), worker.previewText().contains("messages.subject: 2\n"));
    }

    @Test
    public void monoclesEditIdsBecomeTatrakEditIds() throws IOException {
        importMessages("\"uuid\":\"m1\",\"type\":0,\"body\":\"fixed\",\"edited\":\"[{\\\"edited_id\\\":\\\"r1\\\","
                + "\\\"server_msg_id\\\":\\\"s1\\\"}]\"");
        final var edits = eu.siacs.conversations.entities.Edit.ofString(column("edited", "m1"));
        assertEquals(1, edits.size());
        assertEquals("r1", edits.get(0).id());
        assertEquals("s1", edits.get(0).serverMsgId());
    }

    @Test
    public void captionStaysInTheFileRow() throws IOException {
        final String url = "https://up.example.com/a.jpg";
        final String file = "\"conversationUuid\":\"c1\",\"counterpart\":\"friend@example.com/phone\",\"type\":1,"
                + "\"status\":0,\"timeSent\":1000,\"encryption\":5,\"axolotl_fingerprint\":\"05ab\",\"read\":1,"
                + "\"relativeFilePath\":\"a.jpg\",\"oob\":1,\"fileParams\":\"" + url + "|1000\",";
        final var worker = importMessages(
                "\"uuid\":\"m1\"," + file + "\"body\":\"look at this\\n" + url + "\"",
                // only the link and a reply quote: nothing to keep
                "\"uuid\":\"m2\"," + file + "\"body\":\"> q\\n" + url + "\",\"payloads\":\""
                        + "<reply xmlns='urn:xmpp:reply:0' id='orig'/><fallback xmlns='urn:xmpp:fallback:0'"
                        + " for='urn:xmpp:reply:0'><body start='0' end='4'/></fallback>\"",
                "\"uuid\":\"m3\"," + file.replace("\"encryption\":5", "\"encryption\":1") + "\"body\":\"pgp caption\"",
                // a Tatrak backup's caption column comes back as it is
                "\"uuid\":\"m4\",\"conversationUuid\":\"c1\",\"type\":1,\"body\":\"" + url + "|10\",\"tatrak_caption\":\"kept\"");
        assertEquals(4, DatabaseUtils.queryNumEntries(db, "messages"));
        assertEquals(url + "|1000", column("body", "m1"));
        assertEquals("look at this", column("tatrak_caption", "m1"));
        assertNull(column("tatrak_caption", "m2"));
        assertNull(column("tatrak_caption", "m3")); // text added to OpenPGP would break it: reported
        assertEquals("kept", column("tatrak_caption", "m4"));
        // the quote was in the replaced body: the file keeps its reply without a range
        final var replyTo = eu.siacs.conversations.entities.ReplyTo.fromString(column("replyTo", "m2"));
        assertEquals("orig", replyTo.id);
        assertFalse(replyTo.hasFallback());
        assertTrue(worker.previewText(), worker.previewText().contains(ApplicationProvider.getApplicationContext()
                .getString(eu.siacs.conversations.R.string.tatrak_backup_omit_caption) + ": 1\n"));
    }

    @Test
    public void senderRetractedMessageBecomesTheErasedPlaceholder() throws IOException {
        final var worker = importMessages("\"uuid\":\"m1\",\"conversationUuid\":\"c1\",\"type\":1,\"status\":0,"
                + "\"timeSent\":1000,\"body\":\"secret\",\"subject\":\"secret subject\",\"tatrak_caption\":\"secret caption\",\"relativeFilePath\":\"a.jpg\","
                + "\"fileParams\":\"https://up.example.com/a.jpg|10\",\"deleted\":1,\"oob\":1,\"retractId\":\"r1\","
                + "\"reactions\":\"[]\",\"payloads\":\"<retract xmlns='urn:xmpp:message-retract:1'/>\","
                + "\"edited\":\"[\\\"old secret\\\",{\\\"id\\\":\\\"e1\\\",\\\"sentAt\\\":5,\\\"body\\\":\\\"old secret\\\","
                + "\\\"note\\\":\\\"secret too\\\"},{\\\"body\\\":\\\"secret without id\\\"},{\\\"edited_id\\\":\\\"e0\\\",\\\"sentAt\\\":\\\"x\\\"}]\"");
        assertEquals(String.valueOf(eu.siacs.conversations.entities.Message.TYPE_RETRACTED), column("type", "m1"));
        assertEquals("", column("body", "m1"));
        assertNull(column("relativeFilePath", "m1"));
        assertNull(column("tatrak_caption", "m1"));
        assertNull(column("reactions", "m1"));
        assertEquals("0", column("deleted", "m1"));
        assertEquals("1000", column("timeSent", "m1"));
        // rebuilt from the ids and times alone, as Message.eraseContent keeps them
        assertFalse(column("edited", "m1"), column("edited", "m1").contains("secret"));
        final var edits = eu.siacs.conversations.entities.Edit.ofString(column("edited", "m1"));
        assertEquals(2, edits.size());
        assertEquals("e1", edits.get(0).id());
        assertEquals(5, edits.get(0).sentAt().toEpochMilli());
        assertNull(edits.get(0).body());
        assertEquals("e0", edits.get(1).id());
        assertNull(edits.get(1).sentAt());
        assertEquals("", worker.previewText()); // nothing of it is reported lost
    }

    @Test
    public void monoclesDraftAndClearedHistoryBecomeTatrakAttributes() throws Exception {
        db.execSQL("alter table conversations add column created integer");
        db.execSQL("alter table conversations add column attributes text");
        db.execSQL("create table messages (uuid text primary key)");
        final var worker = worker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
        final var jsonReader = new JsonReader(new StringReader("[" + String.join(",", account("a1"),
                "{\"table\":\"conversations\",\"values\":{\"uuid\":\"c1\",\"name\":\"chat\",\"created\":500,"
                        + "\"attributes\":\"{\\\"next_message\\\":\\\" unsent \\\",\\\"next_message_timestamp\\\":\\\"1700\\\","
                        + "\\\"last_clear_history\\\":\\\"1600:stanza-7\\\",\\\"muted_till\\\":\\\"0\\\"}\"}}",
                "{\"table\":\"conversations\",\"values\":{\"uuid\":\"c2\",\"name\":\"chat\",\"created\":500,"
                        + "\"attributes\":\"{\\\"next_message\\\":\\\"old\\\",\\\"last_clear_history\\\":1600}\"}}",
                // unreadable boundaries: no boundary, and the import goes on
                "{\"table\":\"conversations\",\"values\":{\"uuid\":\"c3\",\"name\":\"chat\","
                        + "\"attributes\":\"{\\\"last_clear_history\\\":\\\":\\\"}\"}}",
                "{\"table\":\"conversations\",\"values\":{\"uuid\":\"c4\",\"name\":\"chat\","
                        + "\"attributes\":\"{\\\"last_clear_history\\\":\\\"::\\\"}\"}}") + "]"));
        jsonReader.beginArray();
        ImportBackupWorker.importRows(supportDb, jsonReader, new ByteArrayInputStream(new byte[0]),
                () -> worker.importRow(supportDb, jsonReader, Jid.of("tester@example.com"), PASSWORD));
        final var c1 = new org.json.JSONObject(DatabaseUtils.stringForQuery(db, "select attributes from conversations where uuid = 'c1'", null));
        // read with the types Tatrak's Conversation.Attributes reads them with
        final var draft = im.conversations.android.json.Services.GSON.fromJson(
                c1.getJSONObject("draft").toString(), eu.siacs.conversations.entities.Conversation.Draft.class);
        assertEquals("unsent", draft.message());
        assertEquals(1700, draft.instant().toEpochMilli());
        assertEquals(new eu.siacs.conversations.xmpp.mam.MamReference(1600, "stanza-7"), im.conversations.android.json.Services.GSON
                .fromJson(c1.getJSONObject("last_clear").toString(), eu.siacs.conversations.xmpp.mam.MamReference.class));
        assertFalse(c1.has("next_message"));
        assertFalse(c1.has("last_clear_history"));
        final var c2 = new org.json.JSONObject(DatabaseUtils.stringForQuery(db, "select attributes from conversations where uuid = 'c2'", null));
        assertEquals(500, c2.getJSONObject("draft").getLong("instant")); // no draft time: the chat's creation
        assertEquals(1600, c2.getJSONObject("last_clear").getLong("timestamp"));
        assertFalse(c2.getJSONObject("last_clear").has("reference"));
        for (final String uuid : new String[] {"c3", "c4"}) {
            assertEquals("{}", DatabaseUtils.stringForQuery(db, "select attributes from conversations where uuid = ?", new String[] {uuid}));
        }
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

    // Generated OMEMO fixtures: real legacy (libsignal) identity keys, in the order and shape the
    // apps export them (values as text). Both formats carry legacy OMEMO 0.3 state; monocles adds
    // its OMEMO2 (PQ) tables, its own OMEMO2 key and its contacts' OMEMO2 keys in `identities`.
    private static final org.whispersystems.libsignal.IdentityKeyPair OWN_KEY =
            org.whispersystems.libsignal.util.KeyHelper.generateIdentityKeyPair();
    private static final java.util.Map<String, org.whispersystems.libsignal.IdentityKey> CONTACT_KEYS = new java.util.TreeMap<>();
    static {
        // mine: another own device; peer: legacy contact with a legacy message; silent: legacy
        // contact without one; pq: OMEMO2 key (pinned, only a PQ message); shared: a key monocles
        // used for both stacks (pinned and in a legacy message)
        for (final String name : new String[] {"mine", "peer", "silent", "pq", "shared"}) {
            CONTACT_KEYS.put(name, org.whispersystems.libsignal.util.KeyHelper.generateIdentityKeyPair().getPublicKey());
        }
    }

    private static String fingerprint(final org.whispersystems.libsignal.IdentityKey key) {
        return eu.siacs.conversations.utils.CryptoHelper.bytesToHex(key.serialize());
    }

    private static String fingerprint(final String contact) {
        return fingerprint(CONTACT_KEYS.get(contact));
    }

    private static String base64(final byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static String row(final String table, final String values) {
        return "{\"table\":\"" + table + "\",\"values\":{\"account\":\"a1\"," + values + "}}";
    }

    private static String identity(final String name, final int own, final String fingerprint, final String trust, final String key) {
        return row("identities", "\"name\":\"" + name + "\",\"ownkey\":\"" + own + "\",\"fingerprint\":\""
                + fingerprint + "\",\"trust\":\"" + trust + "\",\"active\":\"1\",\"last_activation\":\"0\",\"key\":\"" + key + "\"");
    }

    private static String contact(final String contact, final String jid, final String trust) {
        return identity(jid, 0, fingerprint(contact), trust, base64(CONTACT_KEYS.get(contact).serialize()));
    }

    private static String message(final String uuid, final int encryption, final String contact) {
        return "{\"table\":\"messages\",\"values\":{\"uuid\":\"" + uuid + "\",\"conversationUuid\":\"c1\",\"type\":\"0\","
                + "\"status\":\"0\",\"body\":\"hi\",\"encryption\":\"" + encryption + "\",\"axolotl_fingerprint\":"
                + (contact == null ? "null" : "\"" + fingerprint(contact) + "\"") + "}}";
    }

    private static List<String> omemoBackup(final boolean monocles) {
        final var rows = new java.util.ArrayList<String>();
        rows.add("{\"table\":\"accounts\",\"values\":{\"uuid\":\"a1\",\"username\":\"tester\",\"server\":\"example.com\","
                + "\"password\":\"" + PASSWORD + "\",\"keys\":\"{\\\"axolotl_reg_id\\\":\\\"4242\\\",\\\"axolotl_cur_prekey_id\\\":\\\"7\\\"}\"}}");
        rows.add("{\"table\":\"conversations\",\"values\":{\"uuid\":\"c1\",\"name\":\"chat\",\"accountUuid\":\"a1\"}}");
        // messages come before the keys in both exports
        rows.add(message("m1", 5, "peer"));
        rows.add(message("m2", 5, "shared"));
        rows.add(message("m3", 5, "mine"));
        rows.add(message("m4", monocles ? 8 : 0, monocles ? "pq" : null)); // a PQ message loses its fingerprint
        rows.add(message("m5", 0, "silent")); // a fingerprint on a plain message proves nothing
        if (monocles) {
            rows.add(row("omemo2_prekeys", "\"id\":\"1\",\"key\":\"pq\""));
            rows.add(row("omemo2_signed_prekeys", "\"id\":\"1\",\"key\":\"pq\""));
            rows.add(row("omemo2_sessions", "\"name\":\"friend@example.com\",\"device_id\":\"9\",\"key\":\"pq\""));
            rows.add(row("kyber_prekeys", "\"id\":\"1\",\"key\":\"pq\",\"is_last_resort\":\"1\""));
        }
        rows.add(identity("tester@example.com", 1, fingerprint(OWN_KEY.getPublicKey()), "VERIFIED", base64(OWN_KEY.serialize())));
        rows.add(contact("mine", "tester@example.com", "TRUSTED"));
        rows.add(contact("peer", "friend@example.com", "VERIFIED"));
        rows.add(contact("silent", "other@example.com", "TRUSTED"));
        rows.add(contact("pq", "friend@example.com", "TRUSTED"));
        rows.add(contact("shared", "third@example.com", "VERIFIED"));
        rows.add(identity("bob@example.com", 0, "05bad", "MAYBE", "k"));
        rows.add(row("identities", "\"name\":\"nofp@example.com\",\"ownkey\":\"0\",\"fingerprint\":null,"
                + "\"trust\":\"TRUSTED\",\"active\":\"1\",\"last_activation\":\"0\",\"key\":\"k\""));
        if (monocles) {
            rows.add(identity("tester@example.com\\u0000omemo2-own", 1, "05own2", "VERIFIED", "k"));
            rows.add(row("omemo2_pq_identities", "\"name\":\"\\u0000omemo2-pq-own\",\"fingerprint\":\"self\",\"pq_key\":\"pq\""));
            rows.add(row("omemo2_pq_identities", "\"name\":\"friend@example.com\",\"fingerprint\":\"" + fingerprint("pq") + "\",\"pq_key\":\"pq\""));
            rows.add(row("omemo2_pq_identities", "\"name\":\"third@example.com\",\"fingerprint\":\"" + fingerprint("shared") + "\",\"pq_key\":\"pq\""));
            // crafted: a pin of another account naming our own key must not reach it
            rows.add(row("omemo2_pq_identities", "\"name\":\"tester@example.com\",\"fingerprint\":\""
                    + fingerprint(OWN_KEY.getPublicKey()) + "\",\"pq_key\":\"pq\"").replace("\"a1\"", "\"a2\""));
        }
        rows.add(row("sessions", "\"name\":\"friend@example.com\",\"device_id\":\"7\",\"key\":\"s\""));
        rows.add(row("prekeys", "\"id\":\"1\",\"key\":\"p\""));
        rows.add(row("signed_prekeys", "\"id\":\"1\",\"key\":\"sp\""));
        return rows;
    }

    /** An encrypted backup file as the apps write it: header, then AES-GCM over the gzipped JSON rows. */
    private static byte[] backupFile(final String app, final List<String> rows) throws Exception {
        final byte[] salt = new byte[16];
        final var out = new ByteArrayOutputStream();
        new eu.siacs.conversations.utils.BackupFileHeader(eu.siacs.conversations.utils.BackupFileHeader.LEGACY_VERSION,
                app, Jid.of("tester@example.com"), 1L, IV, salt).write(new java.io.DataOutputStream(out));
        final var gzip = new ByteArrayOutputStream();
        try (final var gzipOut = new GZIPOutputStream(gzip)) {
            gzipOut.write(("[" + String.join(",", rows) + "]").getBytes(StandardCharsets.UTF_8));
        }
        final byte[] plain = gzip.toByteArray();
        final AEADBlockCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(true, new AEADParameters(new KeyParameter(ExportBackupWorker.getLegacyKey(PASSWORD, salt)), 128, IV));
        final byte[] sealed = new byte[cipher.getOutputSize(plain.length)];
        cipher.doFinal(sealed, cipher.processBytes(plain, 0, plain.length, sealed, 0));
        out.write(sealed);
        return out.toByteArray();
    }

    /** A worker that runs the production import up to the encrypted stage's preview file. */
    private static final class StagingWorker extends ImportBackupWorker {
        boolean previewWritten;

        StagingWorker(final Data data) {
            super(ApplicationProvider.getApplicationContext(), new WorkerParameters(UUID.randomUUID(), data, List.of(),
                    new WorkerParameters.RuntimeExtras(), 0, 0, Runnable::run, null, null, null,
                    (context, id, progress) -> com.google.common.util.concurrent.Futures.immediateVoidFuture(), null));
        }

        @Override
        Result writePreview(final Jid jid) {
            previewWritten = true;
            return Result.success();
        }
    }

    /** Imports [backup] through the production path and returns the worker. */
    private StagingWorker importBackupFile(final ImportBackupWorker.OmemoImport mode, final byte[] backup) throws Exception {
        createBackupSchema();
        final var worker = new StagingWorker(ImportBackupWorker.data(PASSWORD, URI, mode));
        worker.importBackup(supportDb, new ByteArrayInputStream(backup), backup.length, PASSWORD,
                com.google.common.base.Stopwatch.createStarted());
        return worker;
    }

    /** The tables a backup's rows land in, with the columns the fixtures use. */
    private void createBackupSchema() {
        db.execSQL("drop table if exists conversations");
        db.execSQL("create table conversations (uuid text primary key, name text, accountUuid text)");
        db.execSQL("create table messages (uuid text primary key, conversationUuid text, type integer, status integer,"
                + " body text, encryption integer, axolotl_fingerprint text, tatrak_source_encryption integer,"
                + " tatrak_source_fingerprint text, tatrak_time_received integer)");
        db.execSQL("create table identities (account text, name text, ownkey integer, fingerprint text,"
                + " certificate blob, trust text, active number, last_activation number, key text,"
                + " unique (account, name, fingerprint) on conflict ignore)");
        db.execSQL("create table sessions (account text, name text, device_id integer, key text)");
        db.execSQL("create table prekeys (account text, id integer, key text)");
        db.execSQL("create table signed_prekeys (account text, id integer, key text)");
    }

    private java.util.Set<String> importedIdentities() {
        final var rows = new java.util.TreeSet<String>();
        try (final var cursor = db.rawQuery("select fingerprint, trust, ownkey from identities", null)) {
            while (cursor.moveToNext()) rows.add(cursor.getString(0) + ":" + cursor.getString(1) + ":" + cursor.getInt(2));
        }
        return rows;
    }

    private static String omission(final int label, final int count) {
        return ApplicationProvider.getApplicationContext().getString(label) + ": " + count + "\n";
    }

    /** What every source and mode imports: the legacy rows the mode allows, never a PQ, unproven or invalid one. */
    private void assertOmemoImport(final ImportBackupWorker.OmemoImport mode, final boolean monocles) throws Exception {
        assertOmemoImport(mode, monocles, monocles ? "monocles chat" : "Conversations");
    }

    /** [app] is the header's app name: not authenticated, so the rows alone decide. */
    private void assertOmemoImport(final ImportBackupWorker.OmemoImport mode, final boolean monocles, final String app)
            throws Exception {
        final var worker = importBackupFile(mode, backupFile(app, omemoBackup(monocles)));
        assertTrue(worker.previewWritten);
        final String preview = worker.previewText();
        final boolean full = mode == ImportBackupWorker.OmemoImport.FULL;
        final boolean none = mode == ImportBackupWorker.OmemoImport.NONE;
        final var expected = new java.util.TreeSet<String>();
        if (full) expected.add(fingerprint(OWN_KEY.getPublicKey()) + ":VERIFIED:1");
        if (!none) {
            expected.add(fingerprint("mine") + ":TRUSTED:0");
            expected.add(fingerprint("peer") + ":VERIFIED:0");
            expected.add(fingerprint("shared") + ":VERIFIED:0");
            if (!monocles) { // no provenance filter for Conversations and Tatrak backups
                expected.add(fingerprint("silent") + ":TRUSTED:0");
                expected.add(fingerprint("pq") + ":TRUSTED:0");
            }
        }
        assertEquals(expected, importedIdentities());
        for (final String table : new String[] {"sessions", "prekeys", "signed_prekeys"}) {
            assertEquals(table, full ? 1 : 0, DatabaseUtils.queryNumEntries(db, table));
        }
        final var keys = eu.siacs.conversations.entities.Account.Keys.parse(
                DatabaseUtils.stringForQuery(db, "select keys from accounts", null));
        assertEquals(full ? com.google.common.base.Optional.of(4242) : com.google.common.base.Optional.absent(),
                keys.getAxolotlRegistrationId());
        // the backup's counter (7) is above its one prekey (1); without prekeys the counter stays 0
        assertEquals(full ? 7 : 0, prekeyCounter());
        // the omission list names what this mode and source leave out
        // trust MAYBE and a missing fingerprint
        assertTrue(preview, preview.contains(omission(eu.siacs.conversations.R.string.tatrak_backup_omit_bad_identity, 2)));
        // own identity, the three key tables and the device id
        assertEquals(preview, !full, preview.contains(omission(eu.siacs.conversations.R.string.tatrak_backup_omit_private, 5)));
        assertEquals(preview, none, preview.contains(omission(eu.siacs.conversations.R.string.tatrak_backup_omit_contact_trust, 5)));
        // silent and pq; NONE left every contact key out already
        assertEquals(preview, monocles && !none,
                preview.contains(omission(eu.siacs.conversations.R.string.tatrak_backup_omit_unproven_contact, 2)));
        // four PQ table rows, four pins and the OMEMO2 own key
        assertEquals(preview, monocles, preview.contains(omission(eu.siacs.conversations.R.string.tatrak_backup_omit_pq_keys, 9)));
        assertFalse(preview, preview.contains("Unsupported table"));
    }

    @Test
    public void monoclesBackupFullImportsOnlyProvenLegacyOmemo() throws Exception {
        assertOmemoImport(ImportBackupWorker.OmemoImport.FULL, true);
    }

    @Test
    public void monoclesBackupTrustImportsOnlyProvenContactKeys() throws Exception {
        assertOmemoImport(ImportBackupWorker.OmemoImport.TRUST, true);
    }

    @Test
    public void monoclesBackupNoneImportsNoOmemo() throws Exception {
        assertOmemoImport(ImportBackupWorker.OmemoImport.NONE, true);
    }

    @Test
    public void conversationsBackupFullImportsAllOmemo() throws Exception {
        assertOmemoImport(ImportBackupWorker.OmemoImport.FULL, false);
    }

    @Test
    public void conversationsBackupTrustImportsOnlyContactTrust() throws Exception {
        assertOmemoImport(ImportBackupWorker.OmemoImport.TRUST, false);
    }

    @Test
    public void conversationsBackupNoneImportsNoOmemo() throws Exception {
        assertOmemoImport(ImportBackupWorker.OmemoImport.NONE, false);
    }

    @Test
    public void monoclesBackupRelabelledAsConversationsIsStillFiltered() throws Exception {
        assertOmemoImport(ImportBackupWorker.OmemoImport.FULL, true, "Conversations");
    }

    @Test
    public void conversationsBackupRelabelledAsMonoclesKeepsItsContactKeys() throws Exception {
        assertOmemoImport(ImportBackupWorker.OmemoImport.TRUST, false, "monocles chat");
    }

    @Test
    public void importedLegacyKeysStillLoad() throws Exception {
        importBackupFile(ImportBackupWorker.OmemoImport.FULL, backupFile("monocles chat", omemoBackup(true)));
        // the crafted foreign pin naming our own key reached nothing: the own key pair is there and loads
        final var own = new org.whispersystems.libsignal.IdentityKeyPair(java.util.Base64.getDecoder().decode(
                DatabaseUtils.stringForQuery(db, "select key from identities where ownkey = 1", null)));
        assertEquals(OWN_KEY.getPublicKey(), own.getPublicKey());
        final var peer = new org.whispersystems.libsignal.IdentityKey(java.util.Base64.getDecoder().decode(
                DatabaseUtils.stringForQuery(db, "select key from identities where fingerprint = ?",
                        new String[] {fingerprint("peer")})), 0);
        assertEquals(CONTACT_KEYS.get("peer"), peer);
    }

    private int prekeyCounter() throws org.json.JSONException {
        final String keys = DatabaseUtils.stringForQuery(db, "select keys from accounts", null);
        return keys == null ? 0 : new org.json.JSONObject(keys).optInt("axolotl_cur_prekey_id", 0);
    }

    /** A monocles backup whose legacy counter is [legacyCounter] and that holds one more prekey, [extraPrekey]. */
    private int prekeyCounterAfterFullImport(final String legacyCounter, final int extraPrekey) throws Exception {
        // a fresh stage for each import of a test
        for (final String table : new String[] {"messages", "identities", "sessions", "prekeys", "signed_prekeys"}) {
            db.execSQL("drop table if exists " + table);
        }
        db.execSQL("delete from accounts");
        final var rows = new java.util.ArrayList<>(omemoBackup(true));
        // monocles' axolotl_cur_prekey_id (7) counts its OMEMO2 prekeys, the legacy ones have their own counter
        rows.set(0, rows.get(0).replace("\\\"axolotl_cur_prekey_id\\\":\\\"7\\\"",
                "\\\"axolotl_cur_prekey_id\\\":\\\"7\\\",\\\"axolotl_cur_legacy_prekey_id\\\":\\\"" + legacyCounter + "\\\""));
        rows.add(row("prekeys", "\"id\":\"" + extraPrekey + "\",\"key\":\"p\""));
        importBackupFile(ImportBackupWorker.OmemoImport.FULL, backupFile("monocles chat", rows));
        return prekeyCounter();
    }

    @Test
    public void fullImportKeepsMonoclesLegacyPrekeyCounter() throws Exception {
        // new prekeys are numbered from counter + 1: 151 and up never replace an imported one
        assertEquals(150, prekeyCounterAfterFullImport("150", 102));
    }

    @Test
    public void fullImportKeepsThePrekeyCounterAboveEveryImportedPrekey() throws Exception {
        assertEquals(160, prekeyCounterAfterFullImport("150", 160));
    }

    /** The prekeys libsignal generates next from [counter], as AxolotlService asks for them. */
    private static java.util.Set<Integer> nextPrekeyIds(final long counter) {
        final var ids = new java.util.TreeSet<Integer>();
        for (final var record : org.whispersystems.libsignal.util.KeyHelper.generatePreKeys((int) counter + 1, 100)) ids.add(record.getId());
        return ids;
    }

    @Test
    public void fullImportAcceptsACounterOneRefillBelowTheWrap() throws Exception {
        final long counter = ImportBackupWorker.MAX_PREKEY_ID - 100;
        assertEquals(counter, prekeyCounterAfterFullImport(String.valueOf(counter), 102));
        final var next = nextPrekeyIds(counter);
        assertEquals(Integer.valueOf((int) counter + 1), next.iterator().next());
        assertFalse(next.contains(1));
        assertFalse(next.contains(102));
    }

    @Test
    public void fullImportRefusesACounterThatWouldWrapOntoImportedPrekeys() {
        final long counter = ImportBackupWorker.MAX_PREKEY_ID - 99;
        assertTrue(nextPrekeyIds(counter).contains(1)); // libsignal would replace the imported prekey 1
        assertThrows(ImportBackupWorker.UnsafePreKeysException.class,
                () -> prekeyCounterAfterFullImport(String.valueOf(counter), 102));
    }

    @Test
    public void fullImportRefusesCountersAndIdsLibsignalCannotHave() {
        for (final String counter : new String[] {String.valueOf(ImportBackupWorker.MAX_PREKEY_ID + 1),
                "4294967297", "99999999999999999999", "-1", "abc"}) {
            assertThrows(counter, ImportBackupWorker.UnsafePreKeysException.class,
                    () -> prekeyCounterAfterFullImport(counter, 102));
        }
        assertThrows(ImportBackupWorker.UnsafePreKeysException.class,
                () -> prekeyCounterAfterFullImport("150", (int) ImportBackupWorker.MAX_PREKEY_ID + 1));
        assertThrows(ImportBackupWorker.UnsafePreKeysException.class, () -> prekeyCounterAfterFullImport("150", 0));
    }

    @Test
    public void captionKeepsEveryLinkButTheAttachment() {
        final String url = "https://up.example/a.jpg";
        assertEquals("Compare https://up.example/a.jpg?variant=raw",
                ImportBackupWorker.caption("Compare https://up.example/a.jpg?variant=raw\n" + url, url, null));
        assertEquals("see aesgcm://other.example/b.png#k",
                ImportBackupWorker.caption("see aesgcm://other.example/b.png#k\naesgcm://up.example/a.jpg#key", url, null));
        assertEquals("mirror: https://up.example/a.jpg.bak",
                ImportBackupWorker.caption("mirror: https://up.example/a.jpg.bak " + url, url, null));
        assertNull(ImportBackupWorker.caption("aesgcm://up.example/a.jpg#key", url, null));
        assertNull(ImportBackupWorker.caption(url + "|1000", url, null));
    }

    /** A monocles backup with one message per values tail, imported into a fresh stage. */
    private StagingWorker importTimedMessages(final String app, final List<String> messages) throws Exception {
        for (final String table : new String[] {"messages", "identities", "sessions", "prekeys", "signed_prekeys"}) {
            db.execSQL("drop table if exists " + table);
        }
        db.execSQL("delete from accounts");
        final var rows = new java.util.ArrayList<>(omemoBackup(false).subList(0, 2)); // the account and the chat
        rows.addAll(messages);
        return importBackupFile(ImportBackupWorker.OmemoImport.FULL, backupFile(app, rows));
    }

    private static String timedMessage(final String uuid, final String timeReceived) {
        return "{\"table\":\"messages\",\"values\":{\"uuid\":\"" + uuid + "\",\"conversationUuid\":\"c1\","
                + "\"type\":\"0\",\"status\":\"0\",\"body\":\"hi\",\"encryption\":\"0\"" + timeReceived + "}}";
    }

    @Test
    public void monoclesTimeReceivedBecomesTatrakTimeReceived() throws Exception {
        final long farFuture = System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(30);
        final var worker = importTimedMessages("monocles chat", List.of(
                timedMessage("m1", ",\"timeReceived\":\"1700000000123\""),
                timedMessage("m2", ",\"timeReceived\":1700000000124"), // a number, not the usual text
                timedMessage("m3", ",\"timeReceived\":\"0\""), // monocles' unknown
                timedMessage("m4", ""),
                timedMessage("m5", ",\"timeReceived\":null"),
                timedMessage("m6", ",\"timeReceived\":\"yesterday\""),
                timedMessage("m7", ",\"timeReceived\":\"-5\""),
                timedMessage("m8", ",\"timeReceived\":\"99999999999999999999\""),
                timedMessage("m9", ",\"timeReceived\":\"" + farFuture + "\""),
                // a crafted file with both: Tatrak's own column wins
                timedMessage("m10", ",\"timeReceived\":\"1\",\"tatrak_time_received\":\"1700000000125\""),
                // monocles stamps its own messages too; Tatrak keeps a time only for received ones
                timedMessage("m11", ",\"timeReceived\":\"1700000000126\"").replace("\"status\":\"0\"", "\"status\":\"2\""),
                timedMessage("m12", ",\"timeReceived\":\"1700000000127\"").replace(",\"status\":\"0\"", ""),
                // JSON values the generic reader cannot take as a long
                timedMessage("m13", ",\"timeReceived\":true"),
                timedMessage("m14", ",\"timeReceived\":1700000000123.5"),
                timedMessage("m15", ",\"timeReceived\":99999999999999999999"),
                timedMessage("m16", ",\"tatrak_time_received\":[1]"),
                // Tatrak's own column wins even when it is null
                timedMessage("m17", ",\"tatrak_time_received\":null,\"timeReceived\":\"1700000000128\"")));
        // a bad value never costs the message
        assertEquals(17, DatabaseUtils.queryNumEntries(db, "messages"));
        assertEquals("1700000000123", column("tatrak_time_received", "m1"));
        assertEquals("1700000000124", column("tatrak_time_received", "m2"));
        for (final String dropped : new String[] {"m3", "m4", "m5", "m6", "m7", "m8", "m9", "m11", "m12", "m13", "m14", "m15", "m16", "m17"}) {
            assertNull(dropped, column("tatrak_time_received", dropped));
        }
        assertEquals("1700000000125", column("tatrak_time_received", "m10"));
        final String preview = worker.previewText();
        assertFalse(preview, preview.contains("timeReceived"));
        assertFalse(preview, preview.contains("tatrak_time_received"));
    }

    @Test
    public void timeReceivedRoundTripsThroughEachExportFormat() throws Exception {
        final String[][] cases = {
            {eu.siacs.conversations.AppSettings.BACKUP_FORMAT_TATRAK, "Tatrak", "1700000000123"},
            {eu.siacs.conversations.AppSettings.BACKUP_FORMAT_MONOCLES, "monocles chat", "1700000000123"},
            {eu.siacs.conversations.AppSettings.BACKUP_FORMAT_CONVERSATIONS, "Conversations", null}, // Conversations has no such column
        };
        for (final String[] format : cases) {
            final var exported = new java.util.ArrayList<String>();
            try (var source = SQLiteDatabase.create(null)) {
                source.execSQL("create table messages (uuid text, conversationUuid text, type integer, status integer,"
                        + " body text, encryption integer, tatrak_time_received integer)");
                source.execSQL("insert into messages values ('m1', 'c1', 0, 0, 'hi', 0, 1700000000123)");
                source.execSQL("insert into messages values ('m2', 'c1', 0, 2, 'mine', 0, 1700000000124)"); // crafted
                try (var cursor = source.rawQuery("select * from messages order by uuid", null)) {
                    while (cursor.moveToNext()) {
                        final var text = new java.io.StringWriter();
                        try (var writer = new com.google.gson.stream.JsonWriter(text)) {
                            ExportBackupWorker.writeMessage(cursor, format[0], new java.util.HashMap<>(), writer);
                        }
                        exported.add(text.toString());
                    }
                }
            }
            final String m1 = exported.get(0);
            assertEquals(m1, eu.siacs.conversations.AppSettings.BACKUP_FORMAT_TATRAK.equals(format[0]), m1.contains("\"tatrak_time_received\""));
            assertEquals(m1, eu.siacs.conversations.AppSettings.BACKUP_FORMAT_MONOCLES.equals(format[0]), m1.contains("\"timeReceived\""));
            importTimedMessages(format[1], exported);
            assertEquals(format[0], format[2], column("tatrak_time_received", "m1"));
            assertNull(format[0], column("tatrak_time_received", "m2"));
        }
    }

    @Test
    public void tamperedBackupWritesNoPreview() throws Exception {
        final byte[] backup = backupFile("monocles chat", omemoBackup(true));
        backup[backup.length - 1] ^= 1;
        final var worker = new StagingWorker(ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.FULL));
        createBackupSchema();
        assertThrows(IOException.class, () -> worker.importBackup(supportDb, new ByteArrayInputStream(backup),
                backup.length, PASSWORD, com.google.common.base.Stopwatch.createStarted()));
        assertFalse(worker.previewWritten);
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "identities"));
    }

    @Test
    public void unknownOmemoModeFallsBackToTheDialogDefault() {
        assertEquals(ImportBackupWorker.OmemoImport.FULL, ImportBackupWorker.omemoImport(Data.EMPTY));
        assertEquals(ImportBackupWorker.OmemoImport.NONE, ImportBackupWorker.omemoImport(
                ImportBackupWorker.data(PASSWORD, URI, ImportBackupWorker.OmemoImport.NONE)));
    }
}
