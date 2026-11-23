package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.xmpp.Jid;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;

/** A picture that cannot be compressed (a PNG with transparency) falls back to a file: a group PM must stay private. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application.class, sdk = 35)
public class PrivateImageFallbackTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
    private static final Jid ROOM = Jid.of("room@muc.example.com");
    private static final Jid FRIEND = Jid.of("room@muc.example.com/friend");

    /** Stops at the send: the test only needs the message the attach path built. */
    public static class CapturingService extends XmppConnectionService {
        final List<Message> sent = new ArrayList<>();

        @Override
        public ListenableFuture<Void> encryptIfNeededAndSend(final Message message) {
            sent.add(message);
            return Futures.immediateVoidFuture();
        }
    }

    /** A picker-like content URI with a display name (MimeUtils needs one; a file:// URI would not do). */
    public static class Images extends ContentProvider {
        static File file;

        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            final var cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[] {"transparent.png", file.length()});
            return cursor;
        }
        @Override public String getType(Uri uri) { return "image/png"; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }

    private CapturingService service;

    @Before
    public void setUp() {
        JvmDurable.Companion.install();
        service = Robolectric.buildService(CapturingService.class).get();
        EncryptedMediaStore.install(new EncryptedMediaStore(service.getFilesDir(), service.getCacheDir(), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return GENERATION; }
            @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(GENERATION, Map.of(GENERATION, new byte[32])); }
        }, null));
        new File(service.getFilesDir(), "attachments").mkdirs(); // a folder made by the copy would restart the file observer
    }

    @After
    public void tearDown() {
        EncryptedMediaStore.install(null);
        JvmDurable.Companion.uninstall();
    }

    private Uri transparentPng() throws Exception {
        final Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        bitmap.setPixel(10, 10, Color.RED);
        final File file = new File(service.getCacheDir(), "transparent.png");
        try (var out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        // the fallback's trigger: FileBackend refuses to compress a non-JPEG with transparent pixels
        assertTrue(FileBackend.hasAlpha(BitmapFactory.decodeFile(file.getPath())));
        Images.file = file;
        Robolectric.setupContentProvider(Images.class, "test.images");
        return Uri.parse("content://test.images/transparent.png");
    }

    private Message attach(final Jid nextCounterpart) throws Exception {
        // no connection: no roster to choose the encryption from, no manager holding the room's options
        final var account = new Account(Jid.of("tester@example.com"), "secret");
        final var conversation = new Conversation(ROOM.toString(), account, ROOM, Conversation.MODE_MULTI) {
            private MucOptions options;

            @Override
            public synchronized MucOptions getMucOptions() {
                if (options == null) options = new MucOptions(this);
                return options;
            }

            @Override
            public im.conversations.android.model.Bookmark getBookmark() {
                return null;
            }

            @Override
            public int getNextEncryption() {
                return Message.ENCRYPTION_NONE;
            }
        };
        conversation.setNextCounterpart(nextCounterpart);
        service.attachImageToConversation(conversation, transparentPng(), "image/png").get(20, TimeUnit.SECONDS);
        assertEquals(1, service.sent.size());
        return service.sent.get(0);
    }

    @Test
    public void aPrivatePictureStaysPrivateWhenItCannotBeCompressed() throws Exception {
        final Message message = attach(FRIEND);
        assertEquals(Message.TYPE_PRIVATE_FILE, message.getType());
        assertEquals(FRIEND, message.getCounterpart());
    }

    @Test
    public void aRoomPictureStaysARoomMessage() throws Exception {
        final Message message = attach(null);
        assertFalse(message.isPrivateMessage());
        assertEquals(Message.TYPE_IMAGE, message.getType()); // FileBackend.updateFileParams after the file fallback
    }
}
