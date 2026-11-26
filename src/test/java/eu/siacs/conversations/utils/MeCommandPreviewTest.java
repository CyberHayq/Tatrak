package eu.siacs.conversations.utils;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tatrak: a "/me" preview inserts the sender's name as plain text. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MeCommandPreviewTest {

    private static final Jid ROOM = Jid.of("room@conference.example.com");

    @Test
    public void meMessagePreviewInsertsTheNickAsPlainText() {
        final var account = new Account(Jid.of("tester@example.com"), "secret");
        final var conversation =
                new Conversation(ROOM.toString(), account, ROOM, Conversation.MODE_MULTI) {
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
                };
        final var message =
                new Message(conversation, "/me waves", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(ROOM.withResource("a$1b\\"));

        final var preview =
                UIHelper.getMessagePreview(ApplicationProvider.getApplicationContext(), message);

        assertEquals("a$1b\\ waves", preview.first.toString());
    }
}
