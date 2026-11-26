package eu.siacs.conversations.generator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.file.File;
import im.conversations.android.xmpp.model.file.Size;
import im.conversations.android.xmpp.model.sfs.FileSharing;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tatrak: a file message whose size is unknown (a restored one) still sends, without SFS &lt;size&gt;. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class FileSharingSizeTest {
    private static final Jid FRIEND = Jid.of("friend@example.com");

    private static File sfsFile(final String body) {
        final var service = Robolectric.buildService(XmppConnectionService.class).get();
        final var account = new Account(Jid.of("tester@example.com"), "secret");
        final var contact = new Contact(FRIEND);
        contact.setAccount(account);
        final var conversation = new Conversation("friend", account, FRIEND, Conversation.MODE_SINGLE) {
            @Override
            public Contact getContact() {
                return contact;
            }
        };
        final var message = new Message(conversation, body, Message.ENCRYPTION_NONE, Message.STATUS_SEND);
        message.setType(Message.TYPE_FILE);
        final var packet = new MessageGenerator(service).generateChat(message);
        return packet.getExtension(FileSharing.class).getExtension(File.class);
    }

    @Test
    public void anUnknownSizeIsLeftOut() {
        assertNull(sfsFile("https://example.com/a.bin").getExtension(Size.class));
    }

    @Test
    public void aKnownSizeIsSent() {
        assertEquals("1234", sfsFile("https://example.com/a.bin|1234").getExtension(Size.class).getContent());
    }
}
