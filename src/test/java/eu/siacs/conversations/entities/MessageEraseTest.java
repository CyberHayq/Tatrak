package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import org.junit.Test;

/** Tatrak: a retracted message keeps only its ids and side. */
public class MessageEraseTest {

    @Test
    public void erasedMessageKeepsOnlyIds() {
        final var conversation =
                (Conversational)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {Conversational.class},
                                (proxy, method, args) -> null);
        final var message =
                new Message(
                        conversation, "secret", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setServerMsgId("stanza-1");
        message.setRemoteMsgId("remote-1");
        message.setEncryptedBody("cipher");
        message.putEdited(new Message.BodyVersion("older secret", Message.ENCRYPTION_NONE, null));
        final boolean edited = message.hasEditHistory();
        final String remoteId = message.getRemoteMsgId();

        message.eraseContent();

        assertEquals(Message.TYPE_RETRACTED, message.getType());
        assertEquals("", message.getBody());
        assertNull(message.getEncryptedBody());
        assertNull(message.getRelativeFilePath());
        assertTrue(message.getReactions().isEmpty());
        assertEquals("stanza-1", message.getServerMsgId());
        assertEquals(Message.STATUS_RECEIVED, message.getStatus());
        // the edit ids stay, so a late correction still matches this message
        assertEquals(edited, message.hasEditHistory());
        assertEquals(remoteId, message.getRemoteMsgId());
        assertEquals("", message.getVersionsAsMessages().get(0).getBody());
    }

    @Test
    public void messageDeletedForMeIsSkippedInTheChatListPreview() {
        final var conversation =
                new Conversation(
                        "c1", "Alice", null, "a1", null, 0, Conversation.STATUS_AVAILABLE,
                        Conversational.MODE_SINGLE, Conversation.Attributes.parse(null));
        final var visible =
                new Message(conversation, "hello", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        final var hidden =
                new Message(conversation, "gone", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        hidden.eraseContent();
        hidden.setType(Message.TYPE_HIDDEN);
        conversation.add(visible);
        conversation.add(hidden);

        assertEquals(visible, conversation.getLatestVisibleMessage());
        // catch-up and the history boundary still see the stored row
        assertEquals(hidden, conversation.getLatestMessage());
    }
}
