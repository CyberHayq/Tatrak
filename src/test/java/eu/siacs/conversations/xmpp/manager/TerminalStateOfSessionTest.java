package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class TerminalStateOfSessionTest {

    private static final Jid PEER = Jid.of("friend@example.com");
    private final JingleManager.TerminatedRtpSession failed =
            new JingleManager.TerminatedRtpSession(
                    RtpEndUserState.CONNECTIVITY_ERROR, Set.of(Media.AUDIO));
    private final Map<JingleManager.PersistableSessionId, JingleManager.TerminatedRtpSession>
            terminated =
                    Map.of(
                            new JingleManager.PersistableSessionId(
                                    Jid.of("friend@example.com/phone"), "s1"),
                            failed);

    @Test
    public void aPromotedCallsEndIsFoundByThePeersBareJid() {
        assertSame(failed, JingleManager.terminalStateOfSession(terminated, PEER, "s1"));
    }

    @Test
    public void anotherSessionOrPeerIsNot() {
        assertNull(JingleManager.terminalStateOfSession(terminated, PEER, "s2"));
        assertNull(
                JingleManager.terminalStateOfSession(
                        terminated, Jid.of("other@example.com"), "s1"));
    }
}
