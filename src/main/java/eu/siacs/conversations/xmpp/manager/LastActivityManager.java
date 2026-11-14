package eu.siacs.conversations.xmpp.manager;

import android.util.Log;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.last.Query;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * XEP-0012: asks the contact's server when an offline contact was last online. Only asks about
 * contacts we are subscribed to, never over Tor, and shows nothing when the server does not answer.
 */
public class LastActivityManager extends AbstractManager {

    // one request per contact per period, also after an error (servers without the module)
    private final Cache<Jid, Optional<Instant>> cache =
            CacheBuilder.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).build();
    private final XmppConnectionService service;
    private final AppSettings appSettings;

    public LastActivityManager(
            final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
        this.appSettings = new AppSettings(context.getApplicationContext());
    }

    /** Drops the cached answer: the contact came online, so it is out of date. */
    public void forget(final Contact contact) {
        cache.invalidate(contact.getAddress().asBareJid());
    }

    /** The cached answer; asks the server (once per period) when there is none yet. */
    public Instant lastSeen(final Contact contact) {
        final var address = contact.getAddress().asBareJid();
        final var cached = cache.getIfPresent(address);
        if (cached != null) {
            return cached.orElse(null);
        }
        if (this.appSettings.isUseTor()
                || getAccount().isOnion()
                || !contact.getOption(Contact.Options.TO)
                || !getAccount().isOnlineAndConnected()) {
            return null;
        }
        // placeholder so repeated header refreshes do not send the query again
        cache.put(address, Optional.empty());
        Futures.addCallback(
                Futures.transform(
                        this.connection.sendIqPacket(new Iq(Iq.Type.GET, address, new Query())),
                        iq -> {
                            final var query = iq == null ? null : iq.getOnlyExtension(Query.class);
                            final var seconds = query == null ? null : query.getSeconds();
                            // shortcut: Prosody's mod_lastactivity answers 0 for anyone it never saw
                            // log out, so 0 counts as unknown; drop it when servers answer reliably
                            if (seconds == null || seconds == 0) {
                                throw new IllegalArgumentException("No valid last activity");
                            }
                            return Instant.now().minusSeconds(seconds);
                        },
                        MoreExecutors.directExecutor()),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final Instant result) {
                        cache.put(address, Optional.of(result));
                        service.updateConversationUi();
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable t) {
                        Log.d(Config.LOGTAG, "no last activity from " + address + ": " + t);
                    }
                },
                MoreExecutors.directExecutor());
        return null;
    }
}
