package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import android.app.Application;
import android.os.Environment;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tatrak: "Save" never skips or overwrites, and the stored name never leaves the device. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class SaveToSharedTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void saveCopiesThePlaintextUnderAReadableNameTwiceAndLeavesNoPartialCopy() throws Exception {
        final String generation = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
        JvmDurable.Companion.install();
        try {
            // attached, never created: the file backend needs only its context
            final var service = Robolectric.buildService(XmppConnectionService.class).get();
            final var store = new EncryptedMediaStore(service.getFilesDir(), service.getCacheDir(), new EncryptedMediaStore.KeySource() {
                @Override public String generation() { return generation; }
                @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(generation, Map.of(generation, new byte[32])); }
            }, null);
            EncryptedMediaStore.install(store);
            final File pictures = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), service.getString(R.string.app_name));
            assertTrue(pictures.mkdirs()); // made by "Save" it would restart the file observer, which needs the database
            final byte[] photo = new byte[3 * 65536];
            for (int i = 0; i < photo.length; i++) photo[i] = (byte) (i % 251);
            final var writer = store.createAttachment(store.attachmentFile("a.jpg"));
            writer.getOutputStream().write(photo);
            final File stored = writer.commit();
            final var location = new Message.StorageLocation(stored, false);

            service.getFileBackend().saveInternalToExternal(location).get();
            service.getFileBackend().saveInternalToExternal(location).get();
            final String[] saved = pictures.list();
            Arrays.sort(saved);
            assertEquals(2, saved.length);
            final String first = am.cyberhayq.chat.storage.media.MediaProvider.displayName(service, stored);
            assertArrayEquals(new String[] {first.replace(".jpg", " (1).jpg"), first}, saved);
            for (final String name : saved) {
                assertFalse(name, name.contains(generation) || name.contains("tatrak_"));
                assertArrayEquals(photo, Files.readAllBytes(new File(pictures, name).toPath()));
            }

            // a segment that fails its tag in the middle of the copy: the save fails, its copy is gone
            try (final var raf = new RandomAccessFile(stored, "rw")) {
                raf.seek(65536 + 500);
                final int b = raf.read();
                raf.seek(65536 + 500);
                raf.write(b ^ 1);
            }
            assertThrows(ExecutionException.class, () -> service.getFileBackend().saveInternalToExternal(location).get());
            assertEquals(2, pictures.list().length);
        } finally {
            EncryptedMediaStore.install(null);
            JvmDurable.Companion.uninstall();
        }
    }

    @Test
    public void aTakenNameGetsANumberAndEveryNameIsCreatedAtOnce() throws Exception {
        final File folder = temporary.newFolder("Download");
        final File wanted = new File(folder, "Tatrak_20261112_101500.jpg");
        assertEquals(wanted, FileBackend.createFreeName(wanted));
        assertEquals(new File(folder, "Tatrak_20261112_101500 (1).jpg"), FileBackend.createFreeName(wanted));
        assertEquals(new File(folder, "Tatrak_20261112_101500 (2).jpg"), FileBackend.createFreeName(wanted));
        // the names are taken as they are handed out, so two saves cannot collide
        for (final String name : new String[] {"Tatrak_20261112_101500.jpg", "Tatrak_20261112_101500 (1).jpg", "Tatrak_20261112_101500 (2).jpg"}) {
            assertTrue(name, new File(folder, name).isFile());
        }
        final File bare = new File(folder, "noext");
        assertEquals(bare, FileBackend.createFreeName(bare));
        assertEquals(new File(folder, "noext (1)"), FileBackend.createFreeName(bare));
    }

    @Test
    public void theNameSentOnUploadAndInAJingleOfferIsTheMessageId() {
        final var conversation = (Conversational) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Conversational.class}, (proxy, method, args) -> null);
        final var message = new Message(conversation, "", Message.ENCRYPTION_NONE, Message.STATUS_UNSEND);
        final String generation = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
        final File stored = new File("/data/files/attachments/tatrak_" + generation + "_" + "ab".repeat(16) + ".jpg");
        assertEquals(message.getUuid() + ".jpg", FileBackend.transferName(message, stored));
        // the OpenPGP intermediate keeps both extensions, as before the media store
        assertEquals(message.getUuid() + ".jpg.pgp", FileBackend.transferName(message, new File("/data/cache", stored.getName() + ".pgp")));
        assertEquals(message.getUuid(), FileBackend.transferName(message, new File("/data/files/attachments/tatrak_" + generation + "_" + "ab".repeat(16))));
        assertEquals(message.getUuid() + ".jpg", FileBackend.transferName(message, new File("/sdcard/Pictures/Tatrak/" + message.getUuid() + ".jpg")));
    }
}
