package eu.siacs.conversations.xmpp.jingle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import android.app.Application;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import im.conversations.android.model.TransportSecurity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tatrak: an OpenPGP file goes over Jingle as its .pgp ciphertext (cache/<name>.pgp), never as its
 * cleartext, and a received one is written there unchanged for PgpDecryptionService.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class JingleOpenPgpTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
    private static final byte[] PLAIN = "the cleartext of the photo".getBytes();

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private FileBackend fileBackend;
    private EncryptedMediaStore store;
    private File attachments;
    private File cache;

    @Before
    public void setUp() {
        JvmDurable.Companion.install();
        // attached, never created: only the file backend is used
        final var service = Robolectric.buildService(XmppConnectionService.class).get();
        store = new EncryptedMediaStore(service.getFilesDir(), service.getCacheDir(), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return GENERATION; }
            @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(GENERATION, Map.of(GENERATION, new byte[32])); }
        }, null);
        EncryptedMediaStore.install(store);
        fileBackend = service.getFileBackend();
        attachments = new File(service.getFilesDir(), "attachments");
        cache = service.getCacheDir();
    }

    @After
    public void tearDown() {
        EncryptedMediaStore.install(null);
        JvmDurable.Companion.uninstall();
    }

    private static byte[] armored(final int length) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i % 251);
        return bytes;
    }

    private Message message(final int encryption, final int status) {
        final var conversation = (Conversational) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Conversational.class}, (proxy, method, args) -> null);
        final var message = new Message(conversation, "", encryption, status);
        message.setType(Message.TYPE_FILE);
        return message;
    }

    /** A sent photo: its cleartext in the store and, for OpenPGP, PgpEngine's [pgp] in the cache. */
    private Message sent(final int encryption, final byte[] pgp) throws Exception {
        final Message message = message(encryption, Message.STATUS_UNSEND);
        fileBackend.setupRelativeFilePath(message, "photo.jpg");
        final var writer = store.createAttachment(fileBackend.getFile(message));
        try (OutputStream out = writer.getOutputStream()) {
            out.write(PLAIN);
        }
        writer.commit();
        if (pgp != null) {
            Files.write(fileBackend.getFile(message, false).toPath(), pgp);
        }
        return message;
    }

    /** Runs the transmitter on [file]; the bytes it put on the wire. */
    private static byte[] transmit(final File file, final long size) throws Exception {
        final Class<?> type = Class.forName(JingleFileTransferConnection.class.getName() + "$FileTransmitter");
        final var constructor = type.getDeclaredConstructor(
                File.class, TransportSecurity.class, OutputStream.class, CountDownLatch.class, long.class, Runnable.class);
        constructor.setAccessible(true);
        final var wire = new ByteArrayOutputStream();
        final Runnable transmitter = (Runnable) constructor.newInstance(
                file, null, wire, new CountDownLatch(0), size, (Runnable) () -> {});
        transmitter.run();
        final var field = type.getSuperclass().getDeclaredField("complete");
        field.setAccessible(true);
        ((ListenableFuture<?>) field.get(transmitter)).get();
        return wire.toByteArray();
    }

    @Test
    public void anOpenPgpFileIsOfferedAndSentAsItsPgpFile() throws Exception {
        for (final int encryption : new int[] {Message.ENCRYPTION_DECRYPTED, Message.ENCRYPTION_PGP}) {
            final byte[] pgp = armored(5000);
            final Message message = sent(encryption, pgp);
            final File file = fileBackend.getFile(message, false);
            assertEquals(cache, file.getParentFile());
            final var offer = JingleFileTransferConnection.offeredFile(fileBackend, message);
            assertEquals(message.getUuid() + ".jpg.pgp", offer.name); // as the HTTP upload names it
            assertEquals(pgp.length, offer.size);
            assertEquals("application/pgp-encrypted", offer.mediaType); // not the cleartext type
            assertArrayEquals(pgp, transmit(file, offer.size));
        }
    }

    @Test
    public void aFileWithoutOpenPgpIsOfferedAsItsCleartext() throws Exception {
        final Message message = sent(Message.ENCRYPTION_NONE, null);
        final var offer = JingleFileTransferConnection.offeredFile(fileBackend, message);
        assertEquals(message.getUuid() + ".jpg", offer.name);
        assertEquals(PLAIN.length, offer.size);
        assertEquals(message.getMimeType(), offer.mediaType);
        assertArrayEquals(PLAIN, transmit(fileBackend.getFile(message), offer.size));
    }

    /** A received offer of <uuid>.jpg.pgp, set up as setFileOffer does. */
    private Message received() {
        final Message message = message(Message.ENCRYPTION_PGP, Message.STATUS_RECEIVED);
        fileBackend.setupRelativeFilePath(message, message.getUuid() + ".jpg");
        return message;
    }

    @Test
    public void aReceivedPgpFileLandsInTheCacheUnchanged() throws Exception {
        final byte[] pgp = armored(70000);
        for (final TransportSecurity security : Arrays.asList(null, JingleReceiveTest.SECURITY)) {
            final Message message = received();
            final File target = fileBackend.getFile(message, false);
            final byte[] wire = security == null ? pgp : JingleReceiveTest.seal(pgp);
            assertTrue(JingleReceiveTest.receive(target, security, wire, pgp.length, true));
            assertEquals(new File(cache, fileBackend.getFile(message).getName() + ".pgp"), target);
            assertArrayEquals(pgp, Files.readAllBytes(target.toPath()));
            assertFalse(fileBackend.getFile(message).exists());
        }
        assertEquals(0, names(attachments).length); // nothing in files/attachments before decryption
    }

    @Test
    public void aFailedTransferLeavesNoPartialPgpFile() throws Exception {
        final byte[] pgp = armored(70000);
        final byte[] wire = JingleReceiveTest.seal(pgp);
        wire[wire.length - 1] ^= 1;
        final File badTag = fileBackend.getFile(received(), false);
        assertThrows(java.io.IOException.class,
                () -> JingleReceiveTest.receive(badTag, JingleReceiveTest.SECURITY, wire, pgp.length, true));
        assertFalse(badTag.exists());
        final File cut = fileBackend.getFile(received(), false);
        assertThrows(java.io.EOFException.class,
                () -> JingleReceiveTest.receive(cut, null, Arrays.copyOf(pgp, 100), pgp.length, true));
        assertFalse(cut.exists());
        assertEquals(0, names(attachments).length);
    }

    @Test
    public void aPgpOfferStaysOpenPgpWhenTheTransportIsEncryptedToo() {
        // upstream turned it into an OMEMO attachment, so the ciphertext was stored and never decrypted
        assertEquals(Message.ENCRYPTION_PGP, JingleFileTransferConnection.receivedEncryption(Message.ENCRYPTION_PGP, true));
        assertEquals(Message.ENCRYPTION_AXOLOTL, JingleFileTransferConnection.receivedEncryption(Message.ENCRYPTION_NONE, true));
        assertEquals(Message.ENCRYPTION_NONE, JingleFileTransferConnection.receivedEncryption(Message.ENCRYPTION_NONE, false));
        // and the transfer then writes to the cache file PgpDecryptionService reads
        final Message message = received();
        message.setEncryption(JingleFileTransferConnection.receivedEncryption(message.getEncryption(), true));
        assertEquals(cache, JingleFileTransferConnection.transferFile(fileBackend, message).getParentFile());
    }

    @Test
    public void bothDirectionsUseTheOpenPgpFileAndOthersTheStoredOne() throws Exception {
        // setupTransceiver and the offer both go through transferFile
        for (final int encryption : new int[] {Message.ENCRYPTION_DECRYPTED, Message.ENCRYPTION_PGP}) {
            final Message message = sent(encryption, armored(100));
            assertEquals(fileBackend.getFile(message, false), JingleFileTransferConnection.transferFile(fileBackend, message));
            assertTrue(JingleFileTransferConnection.transferFile(fileBackend, message).getName().endsWith(".pgp"));
        }
        final Message plain = sent(Message.ENCRYPTION_NONE, null);
        assertEquals(fileBackend.getFile(plain), JingleFileTransferConnection.transferFile(fileBackend, plain));
    }

    @Test
    public void aPgpFileThatCannotBeCreatedFailsWithoutItsPath() {
        final File impossible = new File(new File(cache, "missing-folder-file"), "x.pgp");
        try {
            new File(cache, "missing-folder-file").createNewFile(); // a file where the folder should be
            JingleReceiveTest.receive(impossible, null, armored(10), 10, true);
            org.junit.Assert.fail();
        } catch (final Exception e) {
            assertFalse(String.valueOf(e.getMessage()).contains(cache.getPath()));
        }
    }

    private static String[] names(final File folder) {
        final String[] names = folder.list();
        return names == null ? new String[0] : names;
    }
}
