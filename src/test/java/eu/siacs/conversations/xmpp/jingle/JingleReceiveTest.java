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
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import im.conversations.android.model.TransportSecurity;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
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
 * Tatrak: the Jingle receiver (JingleFileTransferConnection.FileReceiver, run here as it is)
 * publishes a file and reports success only after the transport tag passed.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class JingleReceiveTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
    static final TransportSecurity SECURITY = new TransportSecurity(filled(32, 3), filled(12, 4));

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private EncryptedMediaStore store;
    private File files;

    @Before
    public void setUp() throws Exception {
        JvmDurable.Companion.install();
        files = temporary.newFolder("files");
        store = new EncryptedMediaStore(files, temporary.newFolder("cache"), new EncryptedMediaStore.KeySource() {
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

    private static byte[] filled(final int length, final int value) {
        final byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] pattern(final int length) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i % 251);
        return bytes;
    }

    /** What the sender puts on the wire: AES-GCM with the tag at the end. */
    static byte[] seal(final byte[] plain) throws Exception {
        final var cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(true, new AEADParameters(new KeyParameter(SECURITY.key()), 128, SECURITY.iv()));
        final byte[] out = new byte[cipher.getOutputSize(plain.length)];
        cipher.doFinal(out, cipher.processBytes(plain, 0, plain.length, out, 0));
        return out;
    }

    private boolean receive(final File target, final TransportSecurity security, final byte[] wire, final long size) throws Exception {
        return receive(target, security, wire, size, false);
    }

    /** Runs the receiver on [wire]; true when it found [target] published at the moment it reported success. */
    static boolean receive(final File target, final TransportSecurity security, final byte[] wire, final long size, final boolean openPgp) throws Exception {
        // as setupTransceiver: a partial file only for OpenPGP, apart from the target as cache/partial/ is
        final File partial = openPgp ? File.createTempFile("jingle", ".partial") : null;
        if (partial != null) partial.deleteOnExit();
        return receive(target, security, wire, size, partial);
    }

    /** Receives through [partial] (OpenPGP) or, without one, through the media store's writer. */
    static boolean receive(final File target, final TransportSecurity security, final byte[] wire, final long size, final File partial) throws Exception {
        final Class<?> type = Class.forName(JingleFileTransferConnection.class.getName() + "$FileReceiver");
        final var constructor = type.getDeclaredConstructor(
                File.class, File.class, TransportSecurity.class, InputStream.class, CountDownLatch.class, long.class, Runnable.class, boolean.class);
        constructor.setAccessible(true);
        final Runnable receiver = (Runnable) constructor.newInstance(
                partial, target, security, new ByteArrayInputStream(wire), new CountDownLatch(0), size, (Runnable) () -> {}, partial != null);
        final var field = type.getSuperclass().getDeclaredField("complete");
        field.setAccessible(true);
        final ListenableFuture<?> complete = (ListenableFuture<?>) field.get(receiver);
        final var published = new AtomicBoolean();
        complete.addListener(() -> published.set(target.isFile()), MoreExecutors.directExecutor());
        receiver.run();
        try {
            complete.get();
        } catch (final ExecutionException e) {
            throw (Exception) e.getCause();
        }
        return published.get();
    }

    private String[] left(final String folder) {
        final String[] names = new File(files, folder).list();
        return names == null ? new String[0] : names;
    }

    @Test
    public void successIsReportedOnlyAfterTheFileIsPublished() throws Exception {
        final byte[] plain = pattern(70000);
        final File encrypted = store.attachmentFile("a.jpg");
        assertTrue(receive(encrypted, SECURITY, seal(plain), plain.length));
        try (final var in = store.inputStream(encrypted)) {
            assertArrayEquals(plain, ByteStreams.toByteArray(in));
        }
        // without transport encryption the file goes through the same writer
        final File clear = store.attachmentFile("b.jpg");
        assertTrue(receive(clear, null, plain, plain.length));
        try (final var in = store.inputStream(clear)) {
            assertArrayEquals(plain, ByteStreams.toByteArray(in));
        }
        assertEquals(0, left("tatrak_tmp").length);
    }

    @Test
    public void aBadTransportTagPublishesNothingAndFailsTheTransfer() throws Exception {
        final byte[] plain = pattern(70000);
        final byte[] wire = seal(plain);
        wire[wire.length - 1] ^= 1;
        final File target = store.attachmentFile("a.jpg");
        assertThrows(java.io.IOException.class, () -> receive(target, SECURITY, wire, plain.length));
        assertFalse(target.exists());
        // a transfer that ends early
        final File cut = store.attachmentFile("b.jpg");
        assertThrows(java.io.EOFException.class, () -> receive(cut, null, Arrays.copyOf(plain, 100), plain.length));
        assertFalse(cut.exists());
        assertEquals(0, left("attachments").length);
        assertEquals(0, left("tatrak_tmp").length);
    }
}
