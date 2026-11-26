package eu.siacs.conversations.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import android.app.Application;
import com.google.common.io.ByteStreams;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import im.conversations.android.model.TransportSecurity;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tatrak: an OMEMO download (HttpDownloadConnection.decryptFile, run here as it is) is published
 * only after the transport tag passed, and never leaves plaintext in a file (CUJ 1).
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class HttpDownloadDecryptTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
    private static final String MARKER = "plaintext marker of the download test";

    private XmppConnectionService service;
    private EncryptedMediaStore store;
    private TransportSecurity security;

    @Before
    public void setUp() {
        JvmDurable.Companion.install();
        // attached, never created: the download needs only the file backend
        service = Robolectric.buildService(XmppConnectionService.class).get();
        store = new EncryptedMediaStore(service.getFilesDir(), service.getCacheDir(), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return GENERATION; }
            @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(GENERATION, Map.of(GENERATION, new byte[32])); }
        }, null);
        EncryptedMediaStore.install(store);
        final byte[] key = new byte[32], iv = new byte[12];
        Arrays.fill(key, (byte) 3);
        Arrays.fill(iv, (byte) 4);
        security = new TransportSecurity(key, iv);
    }

    @After
    public void tearDown() {
        EncryptedMediaStore.install(null);
        JvmDurable.Companion.uninstall();
    }

    private byte[] seal(final byte[] plain) throws Exception {
        final var cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(true, new AEADParameters(new KeyParameter(security.key()), 128, security.iv()));
        final byte[] out = new byte[cipher.getOutputSize(plain.length)];
        cipher.doFinal(out, cipher.processBytes(plain, 0, plain.length, out, 0));
        return out;
    }

    /** A download whose transport ciphertext [wire] is complete in the cache; returns where its file goes. */
    private File decrypt(final File downloaded, final byte[] wire) throws Throwable {
        final var conversation = (Conversational) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Conversational.class}, (proxy, method, args) -> null);
        final var message = new Message(conversation, "", Message.ENCRYPTION_AXOLOTL, Message.STATUS_RECEIVED);
        service.getFileBackend().setupRelativeFilePath(message, message.getUuid() + ".jpg");
        final File target = service.getFileBackend().getFile(message);
        Files.write(downloaded.toPath(), wire);
        final var connection = new HttpDownloadConnection(message, service.getHttpConnectionManager());
        final var decryptFile = HttpDownloadConnection.class.getDeclaredMethod("decryptFile", File.class, TransportSecurity.class);
        decryptFile.setAccessible(true);
        try {
            decryptFile.invoke(connection, downloaded, security);
        } catch (final InvocationTargetException e) {
            assertFalse(target.exists());
            throw e.getCause();
        }
        return target;
    }

    private boolean plaintextOnDisk() throws IOException {
        for (final File root : new File[] {service.getFilesDir(), service.getCacheDir()}) {
            try (final Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
                for (final var path : (Iterable<java.nio.file.Path>) paths.filter(Files::isRegularFile)::iterator) {
                    if (new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1).contains(MARKER)) return true;
                }
            }
        }
        return false;
    }

    private String[] left(final String folder) {
        final String[] names = new File(service.getFilesDir(), folder).list();
        return names == null ? new String[0] : names;
    }

    @Test
    public void aBadTransportTagPublishesNothingAndLeavesNoPlainFile() throws Throwable {
        final byte[] plain = (MARKER + "x".repeat(70000)).getBytes(StandardCharsets.ISO_8859_1);
        final File downloaded = new File(service.getCacheDir(), "download");
        final byte[] bad = seal(plain);
        bad[bad.length - 1] ^= 1;
        assertThrows(IOException.class, () -> decrypt(downloaded, bad));
        assertEquals(0, left("attachments").length);
        assertEquals(0, left("tatrak_tmp").length);
        assertFalse(downloaded.exists()); // upstream dfeb4bc3e deletes the ciphertext on a bad tag
        assertFalse(plaintextOnDisk());

        final File target = decrypt(downloaded, seal(plain));
        assertTrue(target.getName(), target.getName().matches("tatrak_" + GENERATION + "_[0-9a-f]{32}\\.jpg"));
        try (final var in = store.inputStream(target)) {
            assertArrayEquals(plain, ByteStreams.toByteArray(in));
        }
        assertFalse(downloaded.exists());
        assertEquals(0, left("tatrak_tmp").length);
        assertFalse(plaintextOnDisk());
    }
}
