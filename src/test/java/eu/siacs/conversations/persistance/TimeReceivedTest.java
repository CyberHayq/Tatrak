package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tatrak: Message.TIME_RECEIVED, set when a received message is first stored. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class TimeReceivedTest {

    private static final Conversation CONVERSATION = new Conversation("c1", "Alice", null, "a1", null, 0,
            Conversation.STATUS_AVAILABLE, Conversational.MODE_SINGLE, Conversation.Attributes.parse(null));

    private static Message message(final int status) {
        return new Message(CONVERSATION, "hi", Message.ENCRYPTION_NONE, status);
    }

    @Test
    public void onlyAMessageFromSomeoneElseGetsTheTimeItWasStored() {
        final var received = message(Message.STATUS_RECEIVED);
        DatabaseBackend.stampTimeReceived(received, 5000);
        assertEquals(Long.valueOf(5000), received.getTimeReceived());
        // stored again (never happens today): the first time stays
        DatabaseBackend.stampTimeReceived(received, 9000);
        assertEquals(Long.valueOf(5000), received.getTimeReceived());
        // sent here, or a carbon / MAM copy / MUC reflection of one sent from another device of ours
        for (final int own : new int[] {Message.STATUS_UNSEND, Message.STATUS_SEND, Message.STATUS_WAITING,
                Message.STATUS_SEND_FAILED, Message.STATUS_OFFERED, Message.STATUS_SEND_RECEIVED}) {
            final var sent = message(own);
            DatabaseBackend.stampTimeReceived(sent, 5000);
            assertNull(String.valueOf(own), sent.getTimeReceived());
        }
    }

    @Test
    public void aMessageInTheChatWithOurselvesGetsNone() {
        // another device of ours writing to our own address: the parser marks it STATUS_RECEIVED
        final var account = new eu.siacs.conversations.entities.Account(
                eu.siacs.conversations.xmpp.Jid.of("me@example.org"), "secret");
        final var self = new Conversation("Me", account, eu.siacs.conversations.xmpp.Jid.of("me@example.org/phone"),
                Conversational.MODE_SINGLE);
        final var fromMe = new Message(self, "note", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        DatabaseBackend.stampTimeReceived(fromMe, 5000);
        assertNull(fromMe.getTimeReceived());
        final var other = new Conversation("Bob", account, eu.siacs.conversations.xmpp.Jid.of("bob@example.org"),
                Conversational.MODE_SINGLE);
        final var fromBob = new Message(other, "hi", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        DatabaseBackend.stampTimeReceived(fromBob, 5000);
        assertEquals(Long.valueOf(5000), fromBob.getTimeReceived());
    }

    @Test
    public void theColumnRoundTripsThroughTheMessageRow() {
        try (var db = SQLiteDatabase.create(null)) {
            final var received = message(Message.STATUS_RECEIVED);
            received.setTimeReceived(1_700_000_000_123L);
            final var values = received.getContentValues();
            db.execSQL("create table messages (" + String.join(" , ", values.keySet()) + ", "
                    + Message.SHARED_STORAGE + ")");
            db.insert(Message.TABLENAME, null, values);
            db.insert(Message.TABLENAME, null, message(Message.STATUS_SEND).getContentValues());
            try (var cursor = db.rawQuery("select * from messages order by status", null)) {
                cursor.moveToNext();
                final var read = Message.fromCursor(ApplicationProvider.getApplicationContext(), cursor, CONVERSATION);
                assertEquals(Long.valueOf(1_700_000_000_123L), read.getTimeReceived());
                // the search loader too
                final var individual = eu.siacs.conversations.entities.IndividualMessage
                        .fromCursor(ApplicationProvider.getApplicationContext(), cursor, CONVERSATION);
                assertEquals(Long.valueOf(1_700_000_000_123L), individual.getTimeReceived());
                cursor.moveToNext();
                final var own = Message.fromCursor(ApplicationProvider.getApplicationContext(), cursor, CONVERSATION);
                assertNull(own.getTimeReceived());
            }
        }
    }
}
