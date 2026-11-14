package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaArea;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import android.app.Application;
import android.media.MediaRecorder;
import android.net.Uri;
import com.google.common.io.ByteStreams;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.AttachFileToConversationRunnable;
import eu.siacs.conversations.services.XmppConnectionService;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tatrak: the generic writer of attachments (attach, share, voice, camera, transcoded video) and
 * the reuse of a file the store already holds.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class ForwardStoreFileTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";

    private XmppConnectionService service;
    private EncryptedMediaStore store;
    private File attachments;

    @Before
    public void setUp() {
        JvmDurable.Companion.install();
        // attached, never created: the file backend needs only its context
        service = Robolectric.buildService(XmppConnectionService.class).get();
        store = new EncryptedMediaStore(service.getFilesDir(), service.getCacheDir(), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return GENERATION; }
            @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(GENERATION, Map.of(GENERATION, new byte[32])); }
        }, null);
        EncryptedMediaStore.install(store);
        attachments = new File(service.getFilesDir(), "attachments");
        attachments.mkdirs(); // a folder made by the copy would restart the file observer, which needs the database
    }

    @After
    public void tearDown() {
        EncryptedMediaStore.install(null);
        JvmDurable.Companion.uninstall();
    }

    private static Message message() {
        final var conversation = (Conversational) Proxy.newProxyInstance(
                ForwardStoreFileTest.class.getClassLoader(), new Class<?>[] {Conversational.class}, (proxy, method, args) -> null);
        return new Message(conversation, "", Message.ENCRYPTION_NONE, Message.STATUS_UNSEND);
    }

    // the writer behind copyFileToPrivateStorage(Message, Uri, String); that one first guesses the
    // type with MimeUtils, whose List.of(..).contains(null) throws on the JVM (D8 backports it on a device)
    private void copy(final File target, final Uri source) throws Throwable {
        final var method = FileBackend.class.getDeclaredMethod("copyFileToPrivateStorage", File.class, Uri.class);
        method.setAccessible(true);
        try {
            method.invoke(service.getFileBackend(), target, source);
        } catch (final InvocationTargetException e) {
            throw e.getCause();
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
    public void aRecordingIsEncryptedIntoTheStoreAndItsPlainFileDeletedOnlyThen() throws Throwable {
        final File recording = new FileBackend.Cache(service).recording(MediaRecorder.OutputFormat.OGG);
        Files.write(recording.toPath(), "voice".getBytes());
        final File stored = store.attachmentFile(recording.getName());
        copy(stored, Uri.fromFile(recording));
        assertEquals("TATRKENC", new String(Files.readAllBytes(stored.toPath()), 0, 8));
        assertArrayEquals("voice".getBytes(), read(stored));
        assertFalse(recording.exists());
        assertArrayEquals(new String[] {stored.getName()}, attachments.list());

        // a copy that fails publishes nothing and keeps the plain original for another try
        final File second = new FileBackend.Cache(service).recording(MediaRecorder.OutputFormat.MPEG_4);
        Files.write(second.toPath(), "voice".getBytes());
        // the name is taken: the store never overwrites an attachment, so the commit fails
        assertThrows(FileBackend.FileCopyException.class, () -> copy(stored, Uri.fromFile(second)));
        assertTrue(second.exists());
        assertArrayEquals("voice".getBytes(), read(stored));
        // a source that cannot be opened
        final File missing = store.attachmentFile("x.pdf");
        assertThrows(FileBackend.FileCopyException.class, () -> copy(missing, Uri.fromFile(new File(service.getCacheDir(), "none.pdf"))));
        assertFalse(missing.exists());
        assertEquals(0, temporaries().length);
    }

    @Test
    public void aFileTheStoreAlreadyHoldsIsAdoptedByItsPathWithoutACopy() throws Exception {
        final var writer = store.createAttachment(store.attachmentFile("a.oga"));
        writer.getOutputStream().write("voice".getBytes());
        final File stored = writer.commit();
        final Message message = message();
        service.getFileBackend().copyFileToPrivateStorage(message, Uri.fromFile(stored), "audio/ogg");
        assertEquals(new Message.StorageLocation(stored, false), message.getRelativeFilePath());
        assertArrayEquals(new String[] {stored.getName()}, attachments.list());
        assertArrayEquals("voice".getBytes(), read(stored));
    }

    @Test
    public void aStickerIsSentAsItsPlaintextAndStaysReadableAfterItsPackIsDeleted() throws Throwable {
        final var writer = store.create(MediaArea.STICKERS, "pack-p1/aaaa.webp");
        writer.getOutputStream().write("sticker".getBytes());
        final File sticker = writer.commit();
        final File stored = store.attachmentFile("s.webp");
        copy(stored, Uri.fromFile(sticker));
        assertTrue(sticker.delete());
        assertArrayEquals("sticker".getBytes(), read(stored));
    }

    @Test
    public void aTranscodeLeftByADeadProcessIsDeletedAtStart() throws Exception {
        final File folder = new File(service.getCacheDir(), "tatrak_transcode");
        assertTrue(folder.mkdirs());
        Files.write(new File(folder, "a.mp4").toPath(), new byte[10]);
        final File other = new File(service.getCacheDir(), "keep.mp4");
        Files.write(other.toPath(), new byte[10]);
        AttachFileToConversationRunnable.deleteTranscodeLeftovers(service);
        assertEquals(0, folder.list().length);
        assertTrue(other.exists());
        new File(folder, "a.mp4").delete();
        folder.delete();
        AttachFileToConversationRunnable.deleteTranscodeLeftovers(service); // no folder: nothing to do
        assertEquals(Arrays.asList("keep.mp4"), Arrays.asList(service.getCacheDir().list((d, name) -> name.endsWith(".mp4"))));
    }
}
