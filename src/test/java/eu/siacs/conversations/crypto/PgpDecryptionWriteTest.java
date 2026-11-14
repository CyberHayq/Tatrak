package eu.siacs.conversations.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import android.app.Application;
import android.content.Intent;
import com.google.common.io.ByteStreams;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.openintents.openpgp.OpenPgpMetadata;
import org.openintents.openpgp.util.OpenPgpApi;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tatrak: a received OpenPGP file (PgpDecryptionService.executeApi, run here as it is, with
 * OpenKeychain replaced) is written through the media store and published once, under its final
 * name, only for a successful result.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class PgpDecryptionWriteTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
    private static final byte[] ARMOR = "-----BEGIN PGP MESSAGE-----".getBytes();

    /** The service without a database, a connection or a bound OpenKeychain. */
    public static class Service extends XmppConnectionService {
        OpenPgpApi api;

        @Override public OpenPgpApi getOpenPgpApi() { return api; }
        @Override public void updateMessage(final Message message) {}
        @Override public void updateMessage(final Message message, final boolean includeBody) {}
        @Override public void updateConversationUi() {}
    }

    /** OpenKeychain: reads the .pgp input, writes [output] and answers with [code]. */
    private static final class Keychain extends OpenPgpApi {
        int code = OpenPgpApi.RESULT_CODE_SUCCESS;
        byte[] output = "plain text of the file".getBytes();
        String filename = "notes.txt";
        byte[] input;

        Keychain() { super(null, null); }

        @Override
        public Intent executeApi(final Intent data, final InputStream is, final OutputStream os) {
            final var result = new Intent();
            try {
                input = ByteStreams.toByteArray(is);
                os.write(output);
            } catch (final IOException e) {
                throw new AssertionError(e);
            }
            result.putExtra(OpenPgpApi.RESULT_CODE, code);
            result.putExtra(OpenPgpApi.RESULT_METADATA, new OpenPgpMetadata(filename, "text/plain", 0, output.length));
            return result;
        }
    }

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Service service;
    private Keychain keychain;
    private EncryptedMediaStore store;
    private File attachments;

    @Before
    public void setUp() {
        JvmDurable.Companion.install();
        // attached, never created: the decryption needs only the file backend
        service = Robolectric.buildService(Service.class).get();
        keychain = new Keychain();
        service.api = keychain;
        store = new EncryptedMediaStore(service.getFilesDir(), service.getCacheDir(), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return GENERATION; }
            @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(GENERATION, Map.of(GENERATION, new byte[32])); }
        }, null);
        EncryptedMediaStore.install(store);
        attachments = new File(service.getFilesDir(), "attachments");
        attachments.mkdirs();
    }

    @After
    public void tearDown() {
        EncryptedMediaStore.install(null);
        JvmDurable.Companion.uninstall();
    }

    /** A received OpenPGP file whose .pgp intermediate is in the cache; its name has no extension yet. */
    private Message received() throws IOException {
        final var conversation = (Conversational) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Conversational.class}, (proxy, method, args) -> null);
        final var message = new Message(conversation, "", Message.ENCRYPTION_PGP, Message.STATUS_RECEIVED);
        message.setType(Message.TYPE_FILE);
        service.getFileBackend().setupRelativeFilePath(message, message.getUuid());
        Files.write(service.getFileBackend().getFile(message, false).toPath(), ARMOR);
        return message;
    }

    private void decrypt(final Message message) throws Exception {
        final var method = PgpDecryptionService.class.getDeclaredMethod("executeApi", Message.class);
        method.setAccessible(true);
        try {
            method.invoke(new PgpDecryptionService(service), message);
        } catch (final InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private byte[] read(final File file) throws IOException {
        try (final var in = store.inputStream(file)) {
            return ByteStreams.toByteArray(in);
        }
    }

    private String[] temporaries() {
        final String[] names = new File(service.getFilesDir(), "tatrak_tmp").list();
        return names == null ? new String[0] : names;
    }

    @Test
    public void aSuccessfulResultIsPublishedOnceUnderItsFinalName() throws Exception {
        final Message message = received();
        final File reserved = service.getFileBackend().getFile(message);
        final File pgp = service.getFileBackend().getFile(message, false);
        decrypt(message);
        assertArrayEquals(ARMOR, keychain.input);
        assertEquals(Message.ENCRYPTION_DECRYPTED, message.getEncryption());
        final File published = service.getFileBackend().getFile(message);
        // the name the file was reserved under, plus the extension OpenPGP reported: same fileId
        assertEquals(new File(attachments, reserved.getName() + ".txt"), published);
        assertArrayEquals(new String[] {published.getName()}, attachments.list());
        assertArrayEquals(keychain.output, read(published));
        assertEquals(0, temporaries().length);
        assertFalse(pgp.exists());
    }

    @Test
    public void aFailedResultPublishesNothing() throws Exception {
        final Message message = received();
        final File pgp = service.getFileBackend().getFile(message, false);
        keychain.code = OpenPgpApi.RESULT_CODE_ERROR; // after part of the output was written
        decrypt(message);
        assertEquals(Message.ENCRYPTION_DECRYPTION_FAILED, message.getEncryption());
        assertEquals(0, attachments.list().length);
        assertEquals(0, temporaries().length);
        assertTrue(pgp.exists()); // kept for another try
    }

    @Test
    public void aDecryptionRepeatedAfterAKillBeforeTheMessageUpdateKeepsTheFirstFile() throws Exception {
        final Message message = received();
        final var reserved = message.getRelativeFilePath();
        decrypt(message);
        final File published = service.getFileBackend().getFile(message);
        // the process died before the message row was updated: it still names the reserved file
        message.setRelativeFilePath(reserved);
        message.setEncryption(Message.ENCRYPTION_PGP);
        Files.write(service.getFileBackend().getFile(message, false).toPath(), ARMOR);
        keychain.output = "another copy".getBytes();
        decrypt(message);
        assertEquals(Message.ENCRYPTION_DECRYPTED, message.getEncryption());
        assertEquals(published, service.getFileBackend().getFile(message));
        assertArrayEquals(new String[] {published.getName()}, attachments.list());
        assertArrayEquals("plain text of the file".getBytes(), read(published));
        assertEquals(0, temporaries().length);
    }

    @Test
    public void aMissingIntermediateLeavesAFileInASharedFolderAlone() throws Exception {
        final Message message = received();
        final File shared = new File(temporary.newFolder("Download"), message.getUuid());
        Files.write(shared.toPath(), "already here".getBytes());
        message.setRelativeFilePath(new Message.StorageLocation(shared, true));
        decrypt(message); // no .pgp next to the shared name
        assertEquals(Message.ENCRYPTION_DECRYPTION_FAILED, message.getEncryption());
        assertArrayEquals("already here".getBytes(), Files.readAllBytes(shared.toPath()));
        assertEquals(0, attachments.list().length);
    }

    @Test
    public void anOldSharedRowIsDecryptedIntoTheStoreAndNothingPlainIsWritten() throws Exception {
        // an old row (an imported Conversations backup) naming a shared folder, its .pgp present
        final Message message = received();
        final File shared = new File(temporary.newFolder("Download"), message.getUuid());
        message.setRelativeFilePath(new Message.StorageLocation(shared, true));
        final File pgp = service.getFileBackend().getFile(message, false);
        Files.write(pgp.toPath(), ARMOR);
        decrypt(message);
        assertEquals(Message.ENCRYPTION_DECRYPTED, message.getEncryption());
        assertFalse(shared.exists());
        assertFalse(new File(shared.getPath() + ".txt").exists());
        final File published = service.getFileBackend().getFile(message);
        assertFalse(message.getRelativeFilePath().sharedStorage());
        assertEquals(attachments, published.getParentFile());
        assertTrue(published.getName().matches("tatrak_[0-9a-f-]{36}_[0-9a-f]{32}\\.txt"));
        assertArrayEquals(keychain.output, read(published));
        assertEquals(0, temporaries().length);
        assertFalse(pgp.exists());
    }
}
