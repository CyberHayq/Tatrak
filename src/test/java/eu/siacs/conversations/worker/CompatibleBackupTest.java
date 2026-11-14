package eu.siacs.conversations.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ContentValues;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.ReplyTo;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.xmpp.Jid;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tatrak: backups Conversations can restore. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class CompatibleBackupTest {

    private static final String TATRAK = AppSettings.BACKUP_FORMAT_TATRAK;
    private static final String CONVERSATIONS = AppSettings.BACKUP_FORMAT_CONVERSATIONS;
    private static final String MONOCLES = AppSettings.BACKUP_FORMAT_MONOCLES;

    @Test
    public void columnsPerFormat() {
        final String[] columns = {
            Message.REPLY_TO, Message.SHARED_STORAGE, Message.RETRACT_ATTEMPT, "tatrak_source_encryption", Message.BODY
        };
        final boolean[] tatrak = {true, true, true, true, true};
        final boolean[] conversations = {false, true, false, false, true};
        final boolean[] monocles = {false, false, false, false, true};
        for (int i = 0; i < columns.length; i++) {
            assertEquals(columns[i], tatrak[i], ExportBackupWorker.exported(columns[i], TATRAK));
            assertEquals(columns[i], conversations[i], ExportBackupWorker.exported(columns[i], CONVERSATIONS));
            assertEquals(columns[i], monocles[i], ExportBackupWorker.exported(columns[i], MONOCLES));
        }
    }

    @Test
    public void foreignFormatsLeaveOutTatrakMessageTypes() {
        for (final String format : new String[] {CONVERSATIONS, MONOCLES}) {
            assertTrue(ExportBackupWorker.exportedType(Message.TYPE_RTP_SESSION, format));
            assertFalse(ExportBackupWorker.exportedType(Message.TYPE_RETRACTED, format));
            assertFalse(ExportBackupWorker.exportedType(Message.TYPE_HIDDEN, format));
        }
        assertTrue(ExportBackupWorker.exportedType(Message.TYPE_HIDDEN, TATRAK));
    }

    @Test
    public void monoclesGetsImportedPqAsLegacyOmemo() {
        assertEquals(5, ExportBackupWorker.monoclesEncryption(8));
        assertEquals(6, ExportBackupWorker.monoclesEncryption(9));
        assertEquals(7, ExportBackupWorker.monoclesEncryption(10));
        for (final int other : new int[] {0, 1, 2, 3, 4, 5, 6, 7}) {
            assertEquals(other, ExportBackupWorker.monoclesEncryption(other));
        }
    }

    private static ContentValues replyRoundTrip(final ReplyTo replyTo) {
        final var values = new ContentValues();
        values.put("payloads", ExportBackupWorker.monoclesReplyPayload(ReplyTo.toString(replyTo)));
        ImportBackupWorker.restoreReplyTo(values);
        return values;
    }

    @Test
    public void replyPayloadRoundTripsWithFallback() {
        final var back = ReplyTo.fromString(
                replyRoundTrip(new ReplyTo("id-1", "friend@example.com/phone", 0, 12)).getAsString(Message.REPLY_TO));
        assertEquals("id-1", back.id);
        assertEquals("friend@example.com/phone", back.to);
        assertEquals(12, back.fallbackEnd);
    }

    @Test
    public void replyPayloadRoundTripsWithoutFallbackOrAddress() {
        final var back = ReplyTo.fromString(replyRoundTrip(new ReplyTo("id-2", null, -1, -1)).getAsString(Message.REPLY_TO));
        assertEquals("id-2", back.id);
        assertEquals(null, back.to);
        assertFalse(back.hasFallback());
        assertFalse(ExportBackupWorker.monoclesReplyPayload(ReplyTo.toString(new ReplyTo("i", null, -1, -1)))
                .contains("fallback"));
    }

    @Test
    public void replyPayloadEscapesAttributes() {
        final String id = "a\"b<c>&d'e";
        final var back = ReplyTo.fromString(replyRoundTrip(new ReplyTo(id, "x&y@example.com", 0, 3)).getAsString(Message.REPLY_TO));
        assertEquals(id, back.id);
        assertEquals("x&y@example.com", back.to);
        assertEquals(null, ExportBackupWorker.monoclesReplyPayload(null));
    }

    private static String[] monoclesFile(final int type, final String body) {
        final String params = ExportBackupWorker.monoclesFileParams(type, body);
        return new String[] {params == null ? body : ExportBackupWorker.monoclesFileBody(body), params};
    }

    @Test
    public void monoclesFileBodyMovesToFileParams() {
        final var image = monoclesFile(Message.TYPE_IMAGE, "https://up.example.com/a.jpg|71908|1600|1200");
        assertEquals("https://up.example.com/a.jpg", image[0]);
        assertEquals("https://up.example.com/a.jpg|71908|1600|1200", image[1]);
        final var noUrl = monoclesFile(Message.TYPE_FILE, "|71908|1600|1200");
        assertEquals("", noUrl[0]);
        assertEquals("|71908|1600|1200", noUrl[1]);
        final var text = monoclesFile(Message.TYPE_TEXT, "a|b");
        assertEquals("a|b", text[0]);
        assertEquals(null, text[1]);
        assertEquals(null, ExportBackupWorker.monoclesFileParams(Message.TYPE_IMAGE, ""));
        assertEquals(null, ExportBackupWorker.monoclesFileParams(Message.TYPE_IMAGE, null));
    }

    @Test
    public void portableMediaNamesAreUnique() {
        final var taken = new java.util.HashSet<String>();
        assertEquals("${FILES}/media/a.jpg", ExportBackupWorker.portablePath(taken, "a.jpg"));
        assertEquals("${FILES}/media/a-1.jpg", ExportBackupWorker.portablePath(taken, "a.jpg"));
        assertEquals("${FILES}/media/a-2.jpg", ExportBackupWorker.portablePath(taken, "a.jpg"));
        assertEquals("${FILES}/media/b", ExportBackupWorker.portablePath(taken, "b"));
        assertEquals("${FILES}/media/b-1", ExportBackupWorker.portablePath(taken, "b"));
    }

    @Test
    public void backupFormatFallsBackToTheOldSwitch() {
        final var context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        final var preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        final var settings = new AppSettings(context);
        assertEquals(TATRAK, settings.getBackupFormat());
        preferences.edit().putBoolean("tatrak_backup_conversations_compatible", true).commit();
        assertEquals(CONVERSATIONS, settings.getBackupFormat());
        preferences.edit().putString(AppSettings.BACKUP_FORMAT, MONOCLES).commit();
        assertEquals(MONOCLES, settings.getBackupFormat());
    }

    @Test
    public void compatibleHeaderIsConversationsVersion() throws Exception {
        final var out = new ByteArrayOutputStream();
        new BackupFileHeader(
                        BackupFileHeader.LEGACY_VERSION,
                        "Tatrak",
                        Jid.of("a@example.com"),
                        1L,
                        new byte[12],
                        new byte[16])
                .write(new DataOutputStream(out));
        final var in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(2, new DataInputStream(new ByteArrayInputStream(out.toByteArray())).readInt());
        assertEquals(2, BackupFileHeader.read(in).getVersion());
    }
}
