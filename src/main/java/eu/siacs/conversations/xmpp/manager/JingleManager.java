package eu.siacs.conversations.xmpp.manager;

import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Collections2;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.CallIntegrationConnectionService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.NetworkManager;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.JingleFileTransferConnection;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import eu.siacs.conversations.xmpp.jingle.stanzas.Content;
import eu.siacs.conversations.xmpp.jingle.stanzas.GenericDescription;
import eu.siacs.conversations.xmpp.jingle.stanzas.RtpDescription;
import eu.siacs.conversations.xmpp.jingle.transports.InbandBytestreamsTransport;
import eu.siacs.conversations.xmpp.jingle.transports.Transport;
import im.conversations.android.xmpp.IqProcessingException;
import im.conversations.android.xmpp.model.error.Condition;
import im.conversations.android.xmpp.model.ibb.InBandByteStream;
import im.conversations.android.xmpp.model.jingle.Jingle;
import im.conversations.android.xmpp.model.jingle.Reason;
import im.conversations.android.xmpp.model.jingle.error.JingleCondition;
import im.conversations.android.xmpp.model.jmi.Accept;
import im.conversations.android.xmpp.model.jmi.JingleMessage;
import im.conversations.android.xmpp.model.jmi.Proceed;
import im.conversations.android.xmpp.model.jmi.Propose;
import im.conversations.android.xmpp.model.jmi.Reject;
import im.conversations.android.xmpp.model.jmi.Ringing;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class JingleManager extends AbstractManager {
    public static final ScheduledExecutorService SCHEDULED_EXECUTOR_SERVICE =
            Executors.newSingleThreadScheduledExecutor();
    // Tatrak: proposal work that waits for the rtpSessionProposals lock (stream-ack updates, rebind
    // resend, discovery timeout) runs here, in order, never on SCHEDULED_EXECUTOR_SERVICE, whose
    // thread WebRTC's ICE-gathering timeout needs while processProceed holds that lock
    public static final ScheduledExecutorService PROPOSAL_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();
    private final HashMap<RtpSessionProposal, DeviceDiscoveryState> rtpSessionProposals =
            new HashMap<>();
    private final ConcurrentHashMap<AbstractJingleConnection.Id, AbstractJingleConnection>
            connections = new ConcurrentHashMap<>();
    // Tatrak: session ids of proposals the transport has written (one-way), see proposalWritten
    private final Set<String> writtenProposals = ConcurrentHashMap.newKeySet();

    private final Cache<PersistableSessionId, TerminatedRtpSession> terminatedSessions =
            CacheBuilder.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).build();

    private final XmppConnectionService service;

    public JingleManager(final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
    }

    public void process(final Iq packet) {
        final var jingle = packet.getExtension(Jingle.class);
        Preconditions.checkNotNull(
                jingle, "Passed iq packet w/o jingle extension to Connection Manager");
        final String sessionId = jingle.getSessionId();
        final Jingle.Action action = jingle.getAction();
        if (sessionId == null) {
            this.sendErrorFor(packet, new JingleCondition.UnknownSession());
            return;
        }
        if (action == null) {
            this.connection.sendErrorFor(packet, new Condition.BadRequest());
            return;
        }
        final var id = AbstractJingleConnection.Id.of(getAccount(), packet, jingle);
        final var existingJingleConnection = connections.get(id);
        if (existingJingleConnection != null) {
            existingJingleConnection.deliverPacket(packet);
        } else if (action == Jingle.Action.SESSION_INITIATE) {
            final Jid from = packet.getFrom();
            final Content content = jingle.getJingleContent();
            final String descriptionNamespace =
                    content == null ? null : content.getDescriptionNamespace();
            final AbstractJingleConnection connection;
            if (Namespace.JINGLE_APPS_FILE_TRANSFER.equals(descriptionNamespace)) {
                connection = new JingleFileTransferConnection(this.service, id, from);
            } else if (Namespace.JINGLE_APPS_RTP.equals(descriptionNamespace)
                    && isUsingClearNet()) {
                final boolean sessionEnded =
                        this.terminatedSessions.asMap().containsKey(PersistableSessionId.of(id));
                final boolean stranger = isWithStrangerAndStrangerNotificationsAreOff(id.with);
                final boolean busy = isBusy(this.service.getAccounts());
                if (busy || sessionEnded || stranger) {
                    Log.d(
                            Config.LOGTAG,
                            id.account.getJid().asBareJid()
                                    + ": rejected session with "
                                    + id.with
                                    + " because busy. sessionEnded="
                                    + sessionEnded
                                    + ", stranger="
                                    + stranger);
                    sendSessionTerminate(packet, id);
                    if (busy || stranger) {
                        writeLogMissedIncoming(
                                id.with, id.sessionId, null, System.currentTimeMillis(), stranger);
                    }
                    return;
                }
                connection = new JingleRtpConnection(this.service, id, from);
            } else {
                this.sendErrorFor(packet, new JingleCondition.UnsupportedInfo());
                return;
            }
            register(id, connection);
            this.service.updateConversationUi();
            connection.deliverPacket(packet);
            if (connection instanceof JingleRtpConnection rtpConnection) {
                addNewIncomingCall(rtpConnection);
            }
        } else {
            Log.d(Config.LOGTAG, "unable to route jingle packet: " + packet);
            this.sendErrorFor(packet, new JingleCondition.UnknownSession());
        }
    }

    public void deliverMessage(
            final im.conversations.android.xmpp.model.stanza.Message packet, long timestamp) {
        final var account = getAccount();
        final var message = packet.getExtension(JingleMessage.class);
        final var from = packet.getFrom();
        final var to = packet.getTo();
        final var sessionId = message.getSessionId();
        if (sessionId == null) {
            return;
        }
        final var serverMsgId = getManager(StanzaIdManager.class).get(packet);
        if (message instanceof Accept) {
            for (final var connection : connections.values()) {
                if (connection instanceof JingleRtpConnection rtpConnection) {
                    final AbstractJingleConnection.Id id = connection.getId();
                    if (id.sessionId.equals(sessionId)) {
                        rtpConnection.deliveryMessage(from, message, serverMsgId, timestamp);
                        return;
                    }
                }
            }
            return;
        }
        final boolean fromSelf = from.asBareJid().equals(account.getJid().asBareJid());
        final AbstractJingleConnection.Id id;
        if (fromSelf) {
            if (to != null && to.isFullJid()) {
                id = AbstractJingleConnection.Id.of(account, to, sessionId);
            } else {
                return;
            }
        } else {
            id = AbstractJingleConnection.Id.of(account, from, sessionId);
        }
        final AbstractJingleConnection existingJingleConnection = connections.get(id);
        if (existingJingleConnection != null) {
            if (existingJingleConnection instanceof JingleRtpConnection jingleRtpConnection) {
                jingleRtpConnection.deliveryMessage(from, message, serverMsgId, timestamp);
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": "
                                + existingJingleConnection.getClass().getName()
                                + " does not support jingle messages");
            }
            return;
        }

        if (fromSelf) {
            this.processFromSelf(message, id, serverMsgId, timestamp);
            return;
        }
        this.process(packet, id, serverMsgId, timestamp);
    }

    private void process(
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final AbstractJingleConnection.Id id,
            final String serverMsgId,
            final long timestamp) {
        final var account = getAccount();
        final var from = packet.getFrom();
        final var to = packet.getTo();
        // XEP version 0.6.0 sends proceed, reject, ringing to bare jid
        final boolean addressedDirectly = to != null && to.equals(account.getJid());
        final var message = packet.getExtension(JingleMessage.class);
        if (message instanceof Propose propose) {
            processPropose(id, from, propose, serverMsgId, timestamp);
        } else if (addressedDirectly && message instanceof Proceed proceed) {
            synchronized (rtpSessionProposals) {
                processProceed(packet, id, serverMsgId, timestamp, proceed);
            }
        } else if (addressedDirectly && message instanceof Reject) {
            synchronized (rtpSessionProposals) {
                // Tatrak: looked up under the lock, so a proposal the timeout removed meanwhile is
                // not finished a second time
                final RtpSessionProposal proposal =
                        getRtpSessionProposal(from.asBareJid(), id.sessionId);
                if (proposal != null) {
                    setTerminalSessionState(proposal, RtpEndUserState.DECLINED_OR_BUSY);
                    rtpSessionProposals.remove(proposal);
                    proposal.callIntegration.busy();
                    writeLogMissedOutgoing(
                            proposal.with, proposal.sessionId, serverMsgId, timestamp);
                    this.service.notifyJingleRtpConnectionUpdate(
                            account,
                            proposal.with,
                            proposal.sessionId,
                            RtpEndUserState.DECLINED_OR_BUSY);
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": no rtp session proposal found for "
                                    + from
                                    + " to deliver reject");
                }
            }
        } else if (addressedDirectly && message instanceof Ringing) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": " + from + " started ringing");
            updateProposedSessionDiscovered(from, id.sessionId, DeviceDiscoveryState.DISCOVERED);
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid()
                            + ": received out of order jingle message from="
                            + from
                            + ", message="
                            + message
                            + ", addressedDirectly="
                            + addressedDirectly);
        }
    }

    private void processProceed(
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final AbstractJingleConnection.Id id,
            final String serverMsgId,
            final long timestamp,
            final Proceed proceed) {
        final var from = packet.getFrom();
        final var account = getAccount();
        final RtpSessionProposal proposal = getRtpSessionProposal(from.asBareJid(), id.sessionId);
        if (proposal != null) {
            rtpSessionProposals.remove(proposal);
            final JingleRtpConnection rtpConnection =
                    new JingleRtpConnection(
                            this.service, id, account.getJid(), proposal.callIntegration);
            rtpConnection.setProposedMedia(proposal.media);
            register(id, rtpConnection);
            rtpConnection.transitionOrThrow(AbstractJingleConnection.State.PROPOSED);
            rtpConnection.deliveryMessage(from, proceed, serverMsgId, timestamp);
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": no rtp session ("
                            + id.sessionId
                            + ") proposal found for "
                            + from
                            + " to deliver proceed");
            this.connection.sendErrorFor(packet, new Condition.ItemNotFound());
        }
    }

    private void processPropose(
            final AbstractJingleConnection.Id id,
            final Jid from,
            final Propose propose,
            final String serverMsgId,
            final long timestamp) {
        final var account = getAccount();
        final List<GenericDescription> descriptions = propose.getDescriptions();
        final Collection<RtpDescription> rtpDescriptions =
                Collections2.transform(
                        Collections2.filter(descriptions, d -> d instanceof RtpDescription),
                        input -> (RtpDescription) input);
        if (!rtpDescriptions.isEmpty()
                && rtpDescriptions.size() == descriptions.size()
                && isUsingClearNet()) {
            final Collection<Media> media =
                    Collections2.transform(rtpDescriptions, RtpDescription::getMedia);
            if (media.contains(Media.UNKNOWN)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": encountered unknown media in session proposal. "
                                + propose);
                return;
            }
            final Optional<RtpSessionProposal> matchingSessionProposal =
                    findMatchingSessionProposal(id.with, ImmutableSet.copyOf(media));
            if (matchingSessionProposal.isPresent()) {
                final String ourSessionId = matchingSessionProposal.get().sessionId;
                final String theirSessionId = id.sessionId;
                if (ComparisonChain.start()
                                .compare(ourSessionId, theirSessionId)
                                .compare(account.getJid().toString(), id.with.toString())
                                .result()
                        > 0) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": our session lost tie break. automatically accepting"
                                    + " their session. winning Session="
                                    + theirSessionId);
                    // TODO a retract for this reason should probably include some indication of
                    // tie break
                    retractSessionProposal(matchingSessionProposal.get());
                    final JingleRtpConnection rtpConnection =
                            new JingleRtpConnection(this.service, id, from);
                    register(id, rtpConnection);
                    rtpConnection.setProposedMedia(ImmutableSet.copyOf(media));
                    rtpConnection.deliveryMessage(from, propose, serverMsgId, timestamp);
                    addNewIncomingCall(rtpConnection);
                    // TODO actually do the automatic accept?!
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": our session won tie break. waiting for other party to"
                                    + " accept. winningSession="
                                    + ourSessionId);
                    // TODO reject their session with <tie-break/>?
                }
                return;
            }
            final boolean stranger = isWithStrangerAndStrangerNotificationsAreOff(id.with);
            if (isBusy(service.getAccounts()) || stranger) {
                writeLogMissedIncoming(
                        id.with.asBareJid(), id.sessionId, serverMsgId, timestamp, stranger);
                if (stranger) {
                    Log.d(
                            Config.LOGTAG,
                            id.account.getJid().asBareJid()
                                    + ": ignoring call proposal from stranger "
                                    + id.with);
                    return;
                }
                final int activeDevices = account.activeDevicesWithRtpCapability();
                Log.d(Config.LOGTAG, "active devices with rtp capability: " + activeDevices);
                if (activeDevices == 0) {
                    getManager(JingleMessageManager.class).reject(from, id.sessionId);
                } else {
                    Log.d(
                            Config.LOGTAG,
                            id.account.getJid().asBareJid()
                                    + ": ignoring proposal because busy on this device but"
                                    + " there are other devices");
                }
            } else {
                final JingleRtpConnection rtpConnection =
                        new JingleRtpConnection(this.service, id, from);
                register(id, rtpConnection);
                rtpConnection.setProposedMedia(ImmutableSet.copyOf(media));
                rtpConnection.deliveryMessage(from, propose, serverMsgId, timestamp);
                addNewIncomingCall(rtpConnection);
            }
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": unable to react to proposed session with "
                            + rtpDescriptions.size()
                            + " rtp descriptions of "
                            + descriptions.size()
                            + " total descriptions");
        }
    }

    private void processFromSelf(
            final JingleMessage message,
            final AbstractJingleConnection.Id id,
            final String serverMsgId,
            final long timestamp) {
        if (message instanceof Proceed) {
            final Conversation c =
                    this.service.findOrCreateConversation(getAccount(), id.with, false, false);
            final Message previousBusy = c.findRtpSession(id.sessionId, Message.STATUS_RECEIVED);
            if (previousBusy != null) {
                previousBusy.setBody(new RtpSessionStatus(true, 0).toString());
                if (serverMsgId != null) {
                    previousBusy.setServerMsgId(serverMsgId);
                }
                previousBusy.setTime(timestamp);
                this.service.updateMessage(previousBusy, true);
                Log.d(
                        Config.LOGTAG,
                        id.account.getJid().asBareJid()
                                + ": updated previous busy because call got picked up by"
                                + " another device");
                this.service.getNotificationService().clearMissedCall(previousBusy);
                return;
            }
        }
        // TODO handle reject for cases where we don’t have carbon copies (normally reject is to
        // be sent to own bare jid as well)
        Log.d(
                Config.LOGTAG,
                getAccount().getJid().asBareJid() + ": ignore jingle message from self");
    }

    private void addNewIncomingCall(final JingleRtpConnection rtpConnection) {
        if (rtpConnection.isTerminated()) {
            Log.d(
                    Config.LOGTAG,
                    "skip call integration because something must have gone during initiate");
            return;
        }
        if (CallIntegrationConnectionService.addNewIncomingCall(context, rtpConnection.getId())) {
            return;
        }
        rtpConnection.integrationFailure();
    }

    private void sendSessionTerminate(final Iq request, final AbstractJingleConnection.Id id) {
        this.connection.sendResultFor(request);
        final var iq = new Iq(Iq.Type.SET);
        iq.setTo(id.with);
        final var sessionTermination =
                iq.addExtension(new Jingle(Jingle.Action.SESSION_TERMINATE, id.sessionId));
        sessionTermination.setReason(new Reason.Busy(), null);
        this.connection.sendIqPacket(iq);
    }

    private boolean isUsingClearNet() {
        final var appSettings = new AppSettings(context);
        final var account = getAccount();
        return !account.isOnion() && !appSettings.isUseTor();
    }

    private boolean isBusy() {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection rtpConnection) {
                if (connection.isTerminated() && rtpConnection.getCallIntegration().isDestroyed()) {
                    continue;
                }
                return true;
            }
        }
        synchronized (this.rtpSessionProposals) {
            return this.rtpSessionProposals.containsValue(DeviceDiscoveryState.DISCOVERED)
                    || this.rtpSessionProposals.containsValue(DeviceDiscoveryState.SEARCHING)
                    || this.rtpSessionProposals.containsValue(
                            DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED);
        }
    }

    public static boolean isBusy(final Collection<Account> accounts) {
        for (final var account : accounts) {
            final var manager = account.getXmppConnection().getManager(JingleManager.class);
            if (manager.isBusy()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasJingleRtpConnection() {
        for (final var connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection rtpConnection) {
                if (rtpConnection.isTerminated()) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private Optional<RtpSessionProposal> findMatchingSessionProposal(
            final Jid with, final Set<Media> media) {
        synchronized (this.rtpSessionProposals) {
            for (final var entry : this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                final DeviceDiscoveryState state = entry.getValue();
                final boolean openProposal =
                        state == DeviceDiscoveryState.DISCOVERED
                                || state == DeviceDiscoveryState.SEARCHING
                                || state == DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED;
                if (openProposal
                        && proposal.with.equals(with.asBareJid())
                        && proposal.media.equals(media)) {
                    return Optional.of(proposal);
                }
            }
        }
        return Optional.absent();
    }

    private boolean hasMatchingRtpSession(final Jid with, final Set<Media> media) {
        for (final var connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection rtpConnection) {
                if (rtpConnection.isTerminated()) {
                    continue;
                }
                if (rtpConnection.getId().with.asBareJid().equals(with.asBareJid())
                        && rtpConnection.getMedia().equals(media)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWithStrangerAndStrangerNotificationsAreOff(final Jid with) {
        final boolean notifyForStrangers = new AppSettings(context).isNotificationsFromStrangers();
        if (notifyForStrangers) {
            return false;
        }
        final Contact contact = getManager(RosterManager.class).getContact(with);
        return !contact.showInContactList();
    }

    public static ScheduledFuture<?> schedule(
            final Runnable runnable, final long delay, final TimeUnit timeUnit) {
        return SCHEDULED_EXECUTOR_SERVICE.schedule(runnable, delay, timeUnit);
    }

    public void sendErrorFor(final Iq request, final JingleCondition jingleCondition) {
        final var condition =
                Condition.asInstance(JingleCondition.getErrorCondition(jingleCondition));
        this.connection.sendErrorFor(request, condition, jingleCondition);
    }

    private RtpSessionProposal getRtpSessionProposal(final Jid from, final String sessionId) {
        // Tatrak: every access to the HashMap under its lock (owner-approved xmpp/ change)
        synchronized (this.rtpSessionProposals) {
            for (RtpSessionProposal rtpSessionProposal : rtpSessionProposals.keySet()) {
                if (rtpSessionProposal.sessionId.equals(sessionId)
                        && rtpSessionProposal.with.equals(from)) {
                    return rtpSessionProposal;
                }
            }
        }
        return null;
    }

    private void writeLogMissedOutgoing(
            final Jid with, final String sessionId, final String serverMsgId, long timestamp) {
        final Conversation conversation =
                this.service.findOrCreateConversation(getAccount(), with.asBareJid(), false, false);
        final Message message =
                new Message(conversation, Message.STATUS_SEND, Message.TYPE_RTP_SESSION, sessionId);
        message.setBody(new RtpSessionStatus(false, 0).toString());
        message.setServerMsgId(serverMsgId);
        message.setTime(timestamp);
        writeMessage(message);
    }

    private void writeLogMissedIncoming(
            final Jid with,
            final String sessionId,
            final String serverMsgId,
            final long timestamp,
            final boolean stranger) {
        final Conversation conversation =
                this.service.findOrCreateConversation(getAccount(), with.asBareJid(), false, false);
        final Message message =
                new Message(
                        conversation, Message.STATUS_RECEIVED, Message.TYPE_RTP_SESSION, sessionId);
        message.setBody(new RtpSessionStatus(false, 0).toString());
        message.setServerMsgId(serverMsgId);
        message.setTime(timestamp);
        message.setCounterpart(with);
        writeMessage(message);
        if (stranger) {
            return;
        }
        this.service.getNotificationService().pushMissedCallNow(message);
    }

    private void writeMessage(final Message message) {
        final Conversational conversational = message.getConversation();
        if (conversational instanceof Conversation c) {
            c.add(message);
            this.getDatabase().createMessage(message);
            this.service.updateConversationUi();
        } else {
            throw new IllegalStateException("Somehow the conversation in a message was a stub");
        }
    }

    // Tatrak: a call is published with the stream session current at that moment, both under the
    // connection's monitor, which a bind takes to change the session; notifyRebound reads them
    // under it too, so a bind cannot fall between them (a session taken in the constructor could
    // be older than the bind the call is registered and sent on)
    private void register(
            final AbstractJingleConnection.Id id, final AbstractJingleConnection connection) {
        synchronized (this.connection) {
            this.connections.put(id, connection);
            if (connection instanceof JingleRtpConnection rtpConnection) {
                rtpConnection.setStreamSession(this.connection.getStreamSession());
            }
        }
    }

    public void startJingleFileTransfer(final Message message) {
        Preconditions.checkArgument(
                message.isFileOrImage(), "Message is not of type file or image");
        final Transferable old = message.getTransferable();
        if (old != null) {
            old.cancel();
        }
        final JingleFileTransferConnection connection =
                new JingleFileTransferConnection(this.service, message);
        this.connections.put(connection.getId(), connection);
        connection.sendSessionInitialize();
    }

    public Optional<OngoingRtpSession> getOngoingRtpConnection(final Contact contact) {
        // Tatrak: an established call is found without the lock (processProceed can hold it while
        // ICE gathers); the second scan runs under the lock that processProceed holds while it
        // moves a proposal into connections, so the call is not missed in between
        final var connection = findRtpConnection(contact);
        if (connection != null) {
            return Optional.of(connection);
        }
        synchronized (this.rtpSessionProposals) {
            final var promoted = findRtpConnection(contact);
            if (promoted != null) {
                return Optional.of(promoted);
            }
            for (final var entry : this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                if (contact.getAddress().asBareJid().equals(proposal.with)) {
                    final DeviceDiscoveryState preexistingState = entry.getValue();
                    if (preexistingState != null
                            && preexistingState != DeviceDiscoveryState.FAILED) {
                        return Optional.of(proposal);
                    }
                }
            }
        }
        return Optional.absent();
    }

    private JingleRtpConnection findRtpConnection(final Contact contact) {
        for (final var entry : this.connections.entrySet()) {
            if (entry.getValue() instanceof JingleRtpConnection jingleRtpConnection) {
                final AbstractJingleConnection.Id id = entry.getKey();
                if (id.account == contact.getAccount()
                        && id.with.asBareJid().equals(contact.getAddress().asBareJid())) {
                    return jingleRtpConnection;
                }
            }
        }
        return null;
    }

    public JingleRtpConnection getOngoingRtpConnection() {
        for (final AbstractJingleConnection jingleConnection : this.connections.values()) {
            if (jingleConnection instanceof JingleRtpConnection jingleRtpConnection) {
                if (jingleRtpConnection.isTerminated()) {
                    continue;
                }
                return jingleRtpConnection;
            }
        }
        return null;
    }

    public void finishConnectionOrThrow(final AbstractJingleConnection connection) {
        final AbstractJingleConnection.Id id = connection.getId();
        if (this.connections.remove(id) == null) {
            throw new IllegalStateException(
                    String.format("Unable to finish connection with id=%s", id));
        }
        // update chat UI to remove 'ongoing call' icon
        this.service.updateConversationUi();
    }

    // Tatrak: the call a proposal (bare JID, session id) turned into, in any state; it stays in
    // connections until JingleRtpConnection.finish() has stored its terminal state, so a caller
    // that finds nothing here then finds that state
    public JingleRtpConnection findPromotedRtpConnection(final Jid with, final String sessionId) {
        if (sessionId == null) {
            return null;
        }
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection rtpConnection
                    && sessionId.equals(rtpConnection.getId().sessionId)
                    && with.asBareJid().equals(rtpConnection.getId().with.asBareJid())) {
                return rtpConnection;
            }
        }
        return null;
    }

    public boolean fireJingleRtpConnectionStateUpdates() {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection jingleRtpConnection) {
                if (jingleRtpConnection.isTerminated()) {
                    continue;
                }
                jingleRtpConnection.fireStateUpdate();
                return true;
            }
        }
        return false;
    }

    public void retractSessionProposal(final Jid with, @Nullable final String sessionId) {
        synchronized (this.rtpSessionProposals) {
            RtpSessionProposal matchingProposal = null;
            for (final var proposal : this.rtpSessionProposals.keySet()) {
                if (isProposal(proposal, with, sessionId)) {
                    matchingProposal = proposal;
                    break;
                }
            }
            if (matchingProposal != null) {
                retractSessionProposal(matchingProposal, false);
            }
        }
    }

    // Tatrak: the proposal shown with that session id; null (none known) matches any proposal to the
    // peer, as before, so an old screen neither retracts nor shows a newer call to the same peer
    static boolean isProposal(
            final RtpSessionProposal proposal, final Jid with, @Nullable final String sessionId) {
        return with.asBareJid().equals(proposal.with)
                && (sessionId == null || sessionId.equals(proposal.sessionId));
    }

    private boolean retractSessionProposal(final RtpSessionProposal rtpSessionProposal) {
        return retractSessionProposal(rtpSessionProposal, true);
    }

    private boolean retractSessionProposal(
            final RtpSessionProposal rtpSessionProposal, final boolean refresh) {
        final var account = getAccount();
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": retracting rtp session proposal with "
                        + rtpSessionProposal.with);
        // Tatrak: under the lock, like every other access to the map; a proposal another path
        // already removed (timeout, reject, proceed) was finished there, so nothing more is done
        synchronized (this.rtpSessionProposals) {
            if (this.rtpSessionProposals.remove(rtpSessionProposal) == null) {
                Log.d(Config.LOGTAG, "rtp session proposal was already removed. not retracting");
                return false;
            }
        }
        rtpSessionProposal.callIntegration.retracted();
        if (refresh) {
            this.service.notifyJingleRtpConnectionUpdate(
                    account,
                    rtpSessionProposal.with,
                    rtpSessionProposal.sessionId,
                    RtpEndUserState.RETRACTED);
        }
        writeLogMissedOutgoing(
                rtpSessionProposal.with,
                rtpSessionProposal.sessionId,
                null,
                System.currentTimeMillis());
        getManager(JingleMessageManager.class)
                .retract(rtpSessionProposal.with, rtpSessionProposal.sessionId);
        return true;
    }

    public JingleRtpConnection initializeRtpSession(final Jid with, final Set<Media> media) {
        final var account = getAccount();
        final AbstractJingleConnection.Id id = AbstractJingleConnection.Id.of(account, with);
        final JingleRtpConnection rtpConnection =
                new JingleRtpConnection(this.service, id, account.getJid());
        rtpConnection.setProposedMedia(media);
        rtpConnection.getCallIntegration().startAudioRouting();
        register(id, rtpConnection);
        rtpConnection.sendSessionInitiate();
        return rtpConnection;
    }

    public @Nullable RtpSessionProposal proposeJingleRtpSession(
            final Jid with, final Set<Media> media) {
        final var account = getAccount();
        synchronized (this.rtpSessionProposals) {
            for (final var entry : this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                if (with.asBareJid().equals(proposal.with)) {
                    final DeviceDiscoveryState preexistingState = entry.getValue();
                    if (preexistingState != null
                            && preexistingState != DeviceDiscoveryState.FAILED) {
                        final RtpEndUserState endUserState = preexistingState.toEndUserState();
                        this.service.notifyJingleRtpConnectionUpdate(
                                account, with, proposal.sessionId, endUserState);
                        return proposal;
                    }
                }
            }
            if (isBusy(this.service.getAccounts())) {
                if (hasMatchingRtpSession(with, media)) {
                    Log.d(
                            Config.LOGTAG,
                            "ignoring request to propose jingle session because the other party"
                                    + " already created one for us");
                    // TODO return something that we can parse the connection of of
                    return null;
                }
                throw new IllegalStateException(
                        "There is already a running RTP session. This should have been caught by"
                                + " the UI");
            }
            final CallIntegration callIntegration = new CallIntegration(context);
            callIntegration.setVideoState(
                    Media.audioOnly(media)
                            ? VideoProfile.STATE_AUDIO_ONLY
                            : VideoProfile.STATE_BIDIRECTIONAL);
            callIntegration.setAddress(
                    CallIntegration.address(with.asBareJid()), TelecomManager.PRESENTATION_ALLOWED);
            final var contact = account.getRoster().getContact(with);
            callIntegration.setCallerDisplayName(
                    contact.getDisplayName(), TelecomManager.PRESENTATION_ALLOWED);
            callIntegration.setInitialized();
            callIntegration.setInitialAudioDevice(CallIntegration.initialAudioDevice(media));
            callIntegration.startAudioRouting();
            final RtpSessionProposal proposal =
                    RtpSessionProposal.of(with.asBareJid(), media, callIntegration);
            callIntegration.setCallback(new ProposalStateCallback(proposal));
            this.rtpSessionProposals.put(proposal, DeviceDiscoveryState.SEARCHING);
            this.service.notifyJingleRtpConnectionUpdate(
                    account, proposal.with, proposal.sessionId, RtpEndUserState.FINDING_DEVICE);
            // in privacy preserving environments 'propose' is only ACKed when we have presence
            // subscription (to not leak presence). Therefor a timeout is only appropriate for
            // contacts where we can expect the 'ringing' response
            final boolean triggerTimeout =
                    Config.JINGLE_MESSAGE_INIT_STRICT_DEVICE_TIMEOUT
                            || contact.mutualPresenceSubscription();
            if (triggerTimeout) {
                PROPOSAL_EXECUTOR.schedule(
                        () -> {
                            synchronized (rtpSessionProposals) {
                                final DeviceDiscoveryState currentProposalState =
                                        rtpSessionProposals.get(proposal);
                                Log.d(
                                        Config.LOGTAG,
                                        "proposal state after timeout " + currentProposalState);
                                if (!isSearching(currentProposalState)) {
                                    return;
                                }
                            }
                            // Tatrak: asked outside the lock, then checked again, published and
                            // removed in one step (the call screen reads the terminal state and the
                            // proposal), so a proposal that started ringing meanwhile is not failed
                            final RtpEndUserState endUserState = deviceDiscoveryTimeoutState();
                            synchronized (rtpSessionProposals) {
                                if (!isSearching(rtpSessionProposals.get(proposal))) {
                                    return;
                                }
                                Log.d(
                                        Config.LOGTAG,
                                        "call proposal still in device discovery state after"
                                                + " timeout");
                                setTerminalSessionState(proposal, endUserState);
                                rtpSessionProposals.remove(proposal);
                            }
                            deviceDiscoveryTimeout(account, proposal, endUserState);
                        },
                        Config.DEVICE_DISCOVERY_TIMEOUT,
                        TimeUnit.MILLISECONDS);
            }
            this.sendSessionProposal(proposal);
            return proposal;
        }
    }

    private static boolean isSearching(final DeviceDiscoveryState state) {
        return state == DeviceDiscoveryState.SEARCHING
                || state == DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED;
    }

    private RtpEndUserState deviceDiscoveryTimeoutState() {
        // 'endUserState' is what we display in the UI. There is an argument to use 'BUSY' here
        // instead
        // we may or may not want to match this with the tone we are playing (see
        // callIntegration.error() or callIntegration.busy())
        if (new NetworkManager(this.context).getHint() == NetworkManager.Hint.ACTIVE) {
            return RtpEndUserState.CONNECTIVITY_ERROR;
        } else {
            return RtpEndUserState.NO_INTERNET;
        }
    }

    // Tatrak: the caller has set the terminal state and removed the proposal under the lock
    private void deviceDiscoveryTimeout(
            final Account account,
            final RtpSessionProposal proposal,
            final RtpEndUserState endUserState) {
        // error and busy would probably be both appropriate tones to play
        // playing the error tone is probably more in line with what happens on a technical level
        // and would be a similar UX to what happens when you call a user that doesn't exist
        // playing the busy tone might be more in line with what some telephony networks play
        proposal.callIntegration.error();
        writeLogMissedOutgoing(proposal.with, proposal.sessionId, null, System.currentTimeMillis());
        this.service.notifyJingleRtpConnectionUpdate(
                account, proposal.with, proposal.sessionId, endUserState);
        getManager(JingleMessageManager.class).retract(proposal.with, proposal.sessionId);
    }

    public Optional<RtpSessionProposal> matchingProposal(final Jid with) {
        synchronized (this.rtpSessionProposals) {
            for (final var entry : this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                if (with.asBareJid().equals(proposal.with)) {
                    return Optional.of(proposal);
                }
            }
        }
        return Optional.absent();
    }

    public boolean hasMatchingProposal(final Jid with, @Nullable final String sessionId) {
        synchronized (this.rtpSessionProposals) {
            for (final var entry : this.rtpSessionProposals.entrySet()) {
                final var state = entry.getValue();
                final RtpSessionProposal proposal = entry.getKey();
                if (isProposal(proposal, with, sessionId)) {
                    // CallIntegrationConnectionService starts RtpSessionActivity with ACTION_VIEW
                    // and an EXTRA_LAST_REPORTED_STATE of DISCOVERING devices. however due to
                    // possible race conditions the state might have already moved on so we are
                    // going
                    // to update the UI
                    final RtpEndUserState endUserState = state.toEndUserState();
                    this.service.notifyJingleRtpConnectionUpdate(
                            getAccount(), proposal.with, proposal.sessionId, endUserState);
                    return true;
                }
            }
        }
        return false;
    }

    public void deliverIbbPacket(final Iq packet) {
        final var inbandByteStream = packet.getOnlyExtension(InBandByteStream.class);
        if (inbandByteStream == null) {
            this.connection.sendErrorFor(packet, new Condition.BadRequest());
            return;
        }
        final var sid = inbandByteStream.getSid();
        if (Strings.isNullOrEmpty(sid)) {
            Log.d(
                    Config.LOGTAG,
                    getAccount().getJid().asBareJid()
                            + ": unable to deliver ibb packet. missing sid");
            this.connection.sendErrorFor(packet, new Condition.BadRequest());
            return;
        }
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleFileTransferConnection fileTransfer) {
                final Transport transport = fileTransfer.getTransport();
                if (transport instanceof InbandBytestreamsTransport inBandTransport) {
                    if (sid.equals(inBandTransport.getStreamId())) {
                        try {
                            inBandTransport.deliverPacket(packet.getFrom(), inbandByteStream);
                            this.connection.sendResultFor(packet);
                        } catch (final IqProcessingException e) {
                            this.connection.sendErrorFor(packet, e);
                        }
                        return;
                    }
                }
            }
        }
        Log.d(
                Config.LOGTAG,
                getAccount().getJid().asBareJid()
                        + ": unable to deliver ibb packet with sid="
                        + sid);
        connection.sendErrorFor(packet, new Condition.ItemNotFound());
    }

    public void notifyRebound() {
        // Tatrak: a call placed or received in the current session (after this bind, or before
        // a resume that kept it) is still valid; a discovery that ends late must not end it. The
        // session and the calls it judges are read together under the connection's monitor (a
        // bind changes the session under it, a call registered later carries the new one); the
        // calls are ended after it is released
        final Object session;
        final boolean sm;
        final List<AbstractJingleConnection> carriedOver = new ArrayList<>();
        synchronized (this.connection) {
            session = this.connection.getStreamSession();
            sm = this.connection.isStreamManagementEnabled();
            for (final AbstractJingleConnection connection : this.connections.values()) {
                if (!(connection instanceof JingleRtpConnection rtpConnection
                        && rtpConnection.getStreamSession() == session)) {
                    carriedOver.add(connection);
                }
            }
        }
        for (final AbstractJingleConnection connection : carriedOver) {
            connection.notifyRebound();
        }
        // Tatrak: the proposals of the old stream, resent after the pending stream-ack updates
        // queued before them (MessageAcknowledgedProcessor), so an acknowledged proposal or a
        // call placed after the bind is not sent again; without stream management on the new
        // stream only one never written, as nothing tells whether a written one arrived
        final List<RtpSessionProposal> searching = new ArrayList<>();
        synchronized (this.rtpSessionProposals) {
            for (final var entry : this.rtpSessionProposals.entrySet()) {
                if (isRebindResendCandidate(
                        entry.getValue(),
                        sm,
                        !writtenProposals.contains(entry.getKey().sessionId))) {
                    searching.add(entry.getKey());
                }
            }
            final Set<String> live = new HashSet<>();
            for (final RtpSessionProposal proposal : this.rtpSessionProposals.keySet()) {
                live.add(proposal.sessionId);
            }
            writtenProposals.retainAll(live);
        }
        if (!searching.isEmpty()) {
            PROPOSAL_EXECUTOR.execute(() -> resendSessionProposals(searching, session));
        }
    }

    // Tatrak: called by XmppConnection.sendPacket when it writes a proposal (also in a resume's
    // replay); no lock, the transport holds its own
    public void proposalWritten(final String sessionId) {
        writtenProposals.add(sessionId);
    }

    static boolean isRebindResendCandidate(
            final DeviceDiscoveryState state, final boolean sm, final boolean unwritten) {
        return state == DeviceDiscoveryState.SEARCHING && (sm || unwritten);
    }

    public WeakReference<JingleRtpConnection> findJingleRtpConnection(
            final Jid with, final String sessionId) {
        final AbstractJingleConnection.Id id =
                AbstractJingleConnection.Id.of(getAccount(), with, sessionId);
        final AbstractJingleConnection connection = connections.get(id);
        if (connection instanceof JingleRtpConnection) {
            return new WeakReference<>((JingleRtpConnection) connection);
        }
        return null;
    }

    // Tatrak: resent only in the stream-management session current at notifyRebound, if it still
    // is, and only if the proposal was not sent in it yet (a call placed after the bind, an earlier
    // resend, or a send a resume replayed); the connection's monitor keeps the session from
    // changing between this check and the write
    private void resendSessionProposals(
            final List<RtpSessionProposal> proposals, final Object reboundSession) {
        synchronized (this.rtpSessionProposals) {
            synchronized (this.connection) {
                final Object session = this.connection.getStreamSession();
                for (final RtpSessionProposal proposal : proposals) {
                    if (this.rtpSessionProposals.get(proposal) == DeviceDiscoveryState.SEARCHING
                            && isResendDue(reboundSession, session, proposal.sentIn)) {
                        Log.d(
                                Config.LOGTAG,
                                getAccount().getJid().asBareJid()
                                        + ": resending session proposal to "
                                        + proposal.with);
                        this.sendSessionProposal(proposal);
                    }
                }
            }
        }
    }

    static boolean isResendDue(
            final Object reboundSession, final Object session, final Object sentIn) {
        return session != null && session == reboundSession && sentIn != session;
    }

    public void updateProposedSessionDiscovered(
            final Jid from, final String sessionId, final DeviceDiscoveryState target) {
        synchronized (this.rtpSessionProposals) {
            final RtpSessionProposal sessionProposal =
                    getRtpSessionProposal(from.asBareJid(), sessionId);
            final DeviceDiscoveryState currentState =
                    sessionProposal == null ? null : rtpSessionProposals.get(sessionProposal);
            if (currentState == null) {
                Log.d(
                        Config.LOGTAG,
                        "unable to find session proposal for session id "
                                + sessionId
                                + " target="
                                + target);
                return;
            }
            if (currentState == DeviceDiscoveryState.DISCOVERED) {
                Log.d(
                        Config.LOGTAG,
                        "session proposal already at discovered. not going to fall back");
                return;
            }

            Log.d(
                    Config.LOGTAG,
                    getAccount().getJid().asBareJid()
                            + ": flagging session "
                            + sessionId
                            + " as "
                            + target);

            final RtpEndUserState endUserState = target.toEndUserState();

            if (target == DeviceDiscoveryState.FAILED) {
                Log.d(Config.LOGTAG, "removing session proposal after failure");
                setTerminalSessionState(sessionProposal, endUserState);
                this.rtpSessionProposals.remove(sessionProposal);
                sessionProposal.getCallIntegration().error();
                this.service.notifyJingleRtpConnectionUpdate(
                        getAccount(),
                        sessionProposal.with,
                        sessionProposal.sessionId,
                        endUserState);
                return;
            }

            this.rtpSessionProposals.put(sessionProposal, target);

            if (endUserState == RtpEndUserState.RINGING) {
                sessionProposal.callIntegration.setDialing();
            }

            this.service.notifyJingleRtpConnectionUpdate(
                    getAccount(), sessionProposal.with, sessionProposal.sessionId, endUserState);
        }
    }

    public void rejectRtpSession(final String sessionId) {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection.getId().sessionId.equals(sessionId)) {
                if (connection instanceof JingleRtpConnection) {
                    try {
                        ((JingleRtpConnection) connection).rejectCall();
                        return;
                    } catch (final IllegalStateException e) {
                        Log.w(
                                Config.LOGTAG,
                                "race condition on rejecting call from notification",
                                e);
                    }
                }
            }
        }
    }

    public void endRtpSession(final String sessionId) {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection.getId().sessionId.equals(sessionId)) {
                if (connection instanceof JingleRtpConnection jingleRtpConnection) {
                    jingleRtpConnection.endCall();
                }
            }
        }
    }

    public void failProceed(final Jid with, final String sessionId, final String message) {
        final var id = AbstractJingleConnection.Id.of(getAccount(), with, sessionId);
        final AbstractJingleConnection existingJingleConnection = connections.get(id);
        if (existingJingleConnection instanceof JingleRtpConnection jingleRtpConnection) {
            jingleRtpConnection.deliverFailedProceed(message);
        }
    }

    public void ensureConnectionIsRegistered(final AbstractJingleConnection connection) {
        if (connections.containsValue(connection)) {
            return;
        }
        final IllegalStateException e =
                new IllegalStateException(
                        "JingleConnection has not been registered with connection manager");
        Log.e(Config.LOGTAG, "ensureConnectionIsRegistered() failed. Going to throw", e);
        throw e;
    }

    public void setTerminalSessionState(
            AbstractJingleConnection.Id id, final RtpEndUserState state, final Set<Media> media) {
        this.terminatedSessions.put(
                PersistableSessionId.of(id), new TerminatedRtpSession(state, media));
    }

    void setTerminalSessionState(final RtpSessionProposal proposal, final RtpEndUserState state) {
        this.terminatedSessions.put(
                PersistableSessionId.of(proposal), new TerminatedRtpSession(state, proposal.media));
    }

    public TerminatedRtpSession getTerminalSessionState(final Jid with, final String sessionId) {
        final var exact =
                this.terminatedSessions.getIfPresent(new PersistableSessionId(with, sessionId));
        if (exact != null || sessionId == null || !with.isBareJid()) {
            return exact;
        }
        // Tatrak: a proposal (bare JID) that proceed turned into a call ends under the peer's full
        // JID (JingleRtpConnection.finish), with the same session id
        return terminalStateOfSession(this.terminatedSessions.asMap(), with, sessionId);
    }

    static TerminatedRtpSession terminalStateOfSession(
            final Map<PersistableSessionId, TerminatedRtpSession> terminated,
            final Jid bareWith,
            final String sessionId) {
        for (final var entry : terminated.entrySet()) {
            final PersistableSessionId id = entry.getKey();
            if (sessionId.equals(id.sessionId()) && bareWith.equals(id.with().asBareJid())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void sendSessionProposal(final RtpSessionProposal proposal) {
        // Tatrak: remembers the stream-management session it was sent in (a send while unbound is
        // queued in it and replayed if it resumes; a fresh bind starts another session, which
        // resends it); callers hold the rtpSessionProposals lock
        synchronized (this.connection) {
            proposal.sentIn = this.connection.getStreamSession();
            this.getManager(JingleMessageManager.class)
                    .propose(proposal.with, proposal.sessionId, proposal.media);
        }
    }

    // Tatrak: package-private for terminalStateOfSession's test
    record PersistableSessionId(Jid with, String sessionId) {

        public static PersistableSessionId of(final AbstractJingleConnection.Id id) {
            return new PersistableSessionId(id.with, id.sessionId);
        }

        public static PersistableSessionId of(final RtpSessionProposal proposal) {
            return new PersistableSessionId(proposal.with, proposal.sessionId);
        }
    }

    public record TerminatedRtpSession(RtpEndUserState state, Set<Media> media) {}

    public enum DeviceDiscoveryState {
        SEARCHING,
        SEARCHING_ACKNOWLEDGED,
        DISCOVERED,
        FAILED;

        public RtpEndUserState toEndUserState() {
            return switch (this) {
                case SEARCHING, SEARCHING_ACKNOWLEDGED -> RtpEndUserState.FINDING_DEVICE;
                case DISCOVERED -> RtpEndUserState.RINGING;
                default -> RtpEndUserState.CONNECTIVITY_ERROR;
            };
        }
    }

    public static class RtpSessionProposal implements OngoingRtpSession {
        public final Jid with;
        public final String sessionId;
        public final Set<Media> media;
        private final CallIntegration callIntegration;
        // Tatrak: the stream-management session the proposal was last sent in, guarded by
        // rtpSessionProposals
        private Object sentIn;

        private RtpSessionProposal(
                final Jid with,
                final String sessionId,
                final Set<Media> media,
                final CallIntegration callIntegration) {
            this.with = with;
            this.sessionId = sessionId;
            this.media = media;
            this.callIntegration = callIntegration;
        }

        public static RtpSessionProposal of(
                final Jid with, final Set<Media> media, final CallIntegration callIntegration) {
            return new RtpSessionProposal(with, CryptoHelper.random(16), media, callIntegration);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RtpSessionProposal proposal = (RtpSessionProposal) o;
            return Objects.equal(with, proposal.with)
                    && Objects.equal(sessionId, proposal.sessionId);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(with, sessionId);
        }

        @Override
        public Jid getWith() {
            return with;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public CallIntegration getCallIntegration() {
            return this.callIntegration;
        }

        @Override
        public Set<Media> getMedia() {
            return this.media;
        }
    }

    public class ProposalStateCallback implements CallIntegration.Callback {

        private final RtpSessionProposal proposal;

        public ProposalStateCallback(final RtpSessionProposal proposal) {
            this.proposal = proposal;
        }

        @Override
        public void onCallIntegrationShowIncomingCallUi() {}

        @Override
        public void onCallIntegrationDisconnect() {
            Log.d(Config.LOGTAG, "a phone call has just been started. retracting proposal");
            if (retractSessionProposal(this.proposal)) {
                return;
            }
            // Tatrak: proceed may have turned the proposal into a call meanwhile; the hangup goes
            // to that call, as Telecom would have sent it a moment later
            for (final AbstractJingleConnection connection : connections.values()) {
                if (connection instanceof JingleRtpConnection rtpConnection
                        && rtpConnection.getId().sessionId.equals(this.proposal.sessionId)) {
                    rtpConnection.onCallIntegrationDisconnect();
                }
            }
        }

        @Override
        public void onAudioDeviceChanged(
                final CallIntegration.AudioDevice selectedAudioDevice,
                final Set<CallIntegration.AudioDevice> availableAudioDevices) {
            service.notifyJingleRtpConnectionUpdate(selectedAudioDevice, availableAudioDevices);
        }

        @Override
        public void onCallIntegrationReject() {}

        @Override
        public void onCallIntegrationAnswer() {}

        @Override
        public void onCallIntegrationSilence() {}

        @Override
        public void onCallIntegrationMicrophoneEnabled(boolean enabled) {}
    }
}
