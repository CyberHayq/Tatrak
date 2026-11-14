package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaArea;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import android.app.Application;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Map;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tatrak: a shared sticker uploads its plaintext, never the encrypted file. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class StickerUploadTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private EncryptedMediaStore store;

    @Before
    public void setUp() throws Exception {
        JvmDurable.Companion.install();
        store = new EncryptedMediaStore(temporary.newFolder("files"), temporary.newFolder("cache"), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return GENERATION; }
            @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(GENERATION, Map.of(GENERATION, new byte[32])); }
        }, null);
        EncryptedMediaStore.install(store);
    }

    @After
    public void tearDown() {
        EncryptedMediaStore.install(null);
        JvmDurable.Companion.uninstall();
    }

    @Test
    public void theBodyIsThePlaintextOfAStickerAndOfAPlainFile() throws IOException {
        final var writer = store.create(MediaArea.STICKERS, "aaaa.webp");
        writer.getOutputStream().write("sticker".getBytes());
        final File sticker = writer.commit();
        final long size = store.length(sticker);
        assertEquals(7, size);
        final var body = HttpUploadManager.storedBody(sticker, "image/webp", size);
        assertEquals(7, body.contentLength());
        final Buffer sent = new Buffer();
        body.writeTo(sent);
        assertArrayEquals("sticker".getBytes(), sent.readByteArray());

        // the pack JSON is a plain file outside the store
        final File json = temporary.newFile("tatrak-stickers.json");
        Files.write(json.toPath(), "[]".getBytes());
        final Buffer plain = new Buffer();
        HttpUploadManager.storedBody(json, "application/json", store.length(json)).writeTo(plain);
        assertArrayEquals("[]".getBytes(), plain.readByteArray());

        // a damaged sticker fails the upload instead of sending part of it
        try (final var file = new RandomAccessFile(sticker, "rw")) {
            file.seek(file.length() - 1);
            final int last = file.read();
            file.seek(file.length() - 1);
            file.write(last ^ 1);
        }
        assertThrows(IOException.class, () -> body.writeTo(new Buffer()));
    }
}
