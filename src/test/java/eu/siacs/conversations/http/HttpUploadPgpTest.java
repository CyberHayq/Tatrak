package eu.siacs.conversations.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import am.cyberhayq.chat.storage.media.MediaRejectedException;
import android.app.Application;
import eu.siacs.conversations.services.AbstractConnectionManager;
import java.io.File;
import java.io.IOException;
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

/**
 * Tatrak: what an upload sends. An OpenPGP intermediate in the cache goes out as the raw file it
 * is, an attachment as its plaintext, and a plain file in the attachment folder not at all.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class HttpUploadPgpTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private EncryptedMediaStore store;
    private File files;
    private File cache;

    @Before
    public void setUp() throws Exception {
        JvmDurable.Companion.install();
        files = temporary.newFolder("files");
        cache = temporary.newFolder("cache");
        store = new EncryptedMediaStore(files, cache, new EncryptedMediaStore.KeySource() {
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

    private static byte[] sent(final File file) throws IOException {
        final var buffer = new Buffer();
        AbstractConnectionManager.requestBody(new HttpUploadConnection.Upload(file, 0, "application/octet-stream", null), progress -> {})
                .writeTo(buffer);
        return buffer.readByteArray();
    }

    @Test
    public void anOpenPgpUploadSendsItsIntermediateRawAndAPlainAttachmentIsRefused() throws Exception {
        final var writer = store.createAttachment(store.attachmentFile("a.jpg"));
        writer.getOutputStream().write("photo".getBytes());
        final File attachment = writer.commit();
        // FileBackend.getFile(message, false): the attachment's name plus .pgp, in the cache
        final File pgp = new File(cache, attachment.getName() + ".pgp");
        final byte[] armor = "-----BEGIN PGP MESSAGE-----".getBytes();
        Files.write(pgp.toPath(), armor);
        assertArrayEquals(armor, sent(pgp));
        assertEquals(armor.length, store.length(pgp)); // the size of the slot request
        // the attachment itself goes out as its plaintext, with the plaintext length
        assertArrayEquals("photo".getBytes(), sent(attachment));
        assertEquals(5, store.length(attachment));
        // a plain file in the attachment folder is never read
        final File plain = new File(attachment.getParentFile(), "plain.jpg");
        Files.write(plain.toPath(), "plain".getBytes());
        assertThrows(MediaRejectedException.class, () -> sent(plain));
        assertEquals(0, store.length(plain));
    }
}
