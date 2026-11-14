package eu.siacs.conversations.worker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import am.cyberhayq.chat.storage.media.MediaRejectedException;
import am.cyberhayq.chat.storage.media.TestDatabaseKt;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.WorkerParameters;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tatrak: a backup carries the media decrypted and the keys that open what it does not carry. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class ExportBackupWorkerTest {

    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
    private static final String OLD_GENERATION = "9a1c0c0e-58f1-4d7a-b7c2-5f6d3e2a4b99";
    private static final String UNKNOWN_GENERATION = "c0ffee00-1234-4abc-8def-001122334455";
    private static final byte[] OLD_KEY = new byte[32];
    private static final Uri LOCATION = Uri.parse("file:///nonexistent/backup.ceb");

    private final Application context = ApplicationProvider.getApplicationContext();
    private SQLiteDatabase db;
    private SupportSQLiteDatabase supportDb;
    private EncryptedMediaStore store;

    @Before
    public void setUp() {
        Arrays.fill(OLD_KEY, (byte) 10);
        JvmDurable.Companion.install();
        db = SQLiteDatabase.create(null);
        supportDb = TestDatabaseKt.supportDatabase(db);
        db.execSQL("create table conversations (uuid text primary key, accountUuid text)");
        db.execSQL("create table messages (uuid text primary key, conversationUuid text, type integer, deleted integer, relativeFilePath text)");
        db.execSQL("insert into conversations values ('c1', 'a1')");
        MediaKeyTable.create(supportDb);
        MediaKeyTable.bindOwn(supportDb, GENERATION);
        MediaKeyTable.addImported(supportDb, OLD_GENERATION, OLD_KEY);
        // the app's store reads its keys from this database
        store = new EncryptedMediaStore(context.getFilesDir(), context.getCacheDir(), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return GENERATION; }
            @Override public MediaKeyTable.Keys read() { return MediaKeyTable.read(supportDb); }
        }, null);
        EncryptedMediaStore.install(store);
    }

    @After
    public void tearDown() {
        EncryptedMediaStore.install(null);
        JvmDurable.Companion.uninstall();
        db.close();
    }

    private File attachment(final String name, final byte[] content) throws IOException {
        final var writer = store.createAttachment(store.attachmentFile(name));
        writer.getOutputStream().write(content);
        return writer.commit();
    }

    private void message(final String uuid, final File file) {
        db.execSQL("insert into messages values (?, 'c1', 1, 0, ?)", new Object[] {uuid, file.getAbsolutePath()});
    }

    private static byte[] pattern(final int length) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i % 251);
        return bytes;
    }

    private ExportBackupWorker worker() {
        return new ExportBackupWorker(context, new WorkerParameters(UUID.randomUUID(), Data.EMPTY, List.of(),
                new WorkerParameters.RuntimeExtras(), 0, 0, Runnable::run, null, null, null, null, null));
    }

    /** The records fileExport writes for account a1. */
    private JsonArray exported(final ExportBackupWorker worker) throws Exception {
        final var text = new StringWriter();
        try (final var json = new JsonWriter(text)) {
            json.beginArray();
            worker.fileExport(supportDb, "a1", LOCATION, json);
            json.endArray();
        }
        return JsonParser.parseString(text.toString()).getAsJsonArray();
    }

    @Test
    public void mediaIsExportedDecryptedAnEmptyFileAsOneRecordAndARefusedFileIsLeftOut() throws Exception {
        final byte[] big = pattern(1024 * 1024 + 10); // two chunks
        final File photo = attachment("photo.jpg", big);
        final File empty = attachment("empty.bin", new byte[0]);
        // a file of a generation whose key this database does not hold: refused at open
        final File foreign = new File(photo.getParentFile(), "tatrak_" + UNKNOWN_GENERATION + "_" + "ab".repeat(16) + ".pdf");
        java.nio.file.Files.write(foreign.toPath(), pattern(300));
        message("m1", photo);
        message("m2", empty);
        message("m3", foreign);
        final var worker = worker();
        final JsonArray records = exported(worker);
        assertEquals(3, records.size());
        final JsonObject first = records.get(0).getAsJsonObject().getAsJsonObject("values");
        final JsonObject second = records.get(1).getAsJsonObject().getAsJsonObject("values");
        final JsonObject third = records.get(2).getAsJsonObject().getAsJsonObject("values");
        assertEquals(photo.getAbsolutePath(), first.get("path").getAsString());
        assertEquals(0, first.get("sequence").getAsInt());
        assertEquals(1, second.get("sequence").getAsInt());
        final byte[] decrypted = com.google.common.primitives.Bytes.concat(
                android.util.Base64.decode(first.get("content").getAsString(), android.util.Base64.NO_WRAP),
                android.util.Base64.decode(second.get("content").getAsString(), android.util.Base64.NO_WRAP));
        assertArrayEquals(big, decrypted);
        // the empty attachment: one record, so a restore makes the file again
        assertEquals(empty.getAbsolutePath(), third.get("path").getAsString());
        assertEquals(0, third.get("sequence").getAsInt());
        assertEquals("", third.get("content").getAsString());
        assertEquals(1, worker.leftOut);
    }

    @Test
    public void aTagFailingWhileReadingFailsTheBackup() throws Exception {
        final File photo = attachment("photo.jpg", pattern(3 * 65536));
        message("m1", photo);
        try (final var raf = new RandomAccessFile(photo, "rw")) {
            raf.seek(65536 + 500);
            final int b = raf.read();
            raf.seek(65536 + 500);
            raf.write(b ^ 1); // inside a middle segment: open passes, the read of that segment fails
        }
        final var worker = worker();
        assertThrows(MediaRejectedException.class, () -> exported(worker));
        assertEquals(0, worker.leftOut);
    }

    @Test
    public void everyMediaKeyIsWrittenAsHexAndThePendingOneNever() throws Exception {
        final var text = new StringWriter();
        try (final var json = new JsonWriter(text)) {
            json.beginArray();
            ExportBackupWorker.mediaKeyExport(supportDb, json);
            json.endArray();
        }
        final JsonArray records = JsonParser.parseString(text.toString()).getAsJsonArray();
        assertEquals(2, records.size());
        final var keys = new java.util.HashMap<String, String>();
        for (final var record : records) {
            final JsonObject row = record.getAsJsonObject();
            assertEquals(MediaKeyTable.TABLE, row.get("table").getAsString());
            final JsonObject values = row.getAsJsonObject("values");
            keys.put(values.get("generation").getAsString(), values.get("master_key").getAsString());
        }
        assertEquals("0a".repeat(32), keys.get(OLD_GENERATION));
        final byte[] own = MediaKeyTable.read(supportDb).getByGeneration().get(GENERATION);
        assertEquals(com.google.common.io.BaseEncoding.base16().lowerCase().encode(own), keys.get(GENERATION));
        // a database without the table (migrated from the legacy layout) writes nothing
        db.execSQL("drop table " + MediaKeyTable.TABLE);
        final var none = new StringWriter();
        try (final var json = new JsonWriter(none)) {
            json.beginArray();
            ExportBackupWorker.mediaKeyExport(supportDb, json);
            json.endArray();
        }
        assertEquals("[]", none.toString());
    }
}
