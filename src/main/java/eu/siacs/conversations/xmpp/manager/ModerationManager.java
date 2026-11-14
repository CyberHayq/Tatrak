package eu.siacs.conversations.xmpp.manager;

import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.Config;
import com.google.common.base.Strings;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.fallback.Fallback;
import im.conversations.android.xmpp.model.hints.Store;
import im.conversations.android.xmpp.model.moderation.Moderate;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import im.conversations.android.xmpp.model.retraction.Retract;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Message;
import java.util.Collection;
import java.util.List;

public class ModerationManager extends AbstractManager {

    // shown by clients without XEP-0424; the wording follows the XEP's example
    private static final String RETRACTION_FALLBACK =
            "This person attempted to retract a previous message, but it's unsupported by your"
                    + " client.";

    // Tatrak: the retracted id repeated inside the encrypted body; the outer <retract id> is not
    // covered by legacy OMEMO, so a server could point a genuine retraction at another message
    private static final String INNER_ID_PREFIX = " [retract:";

    private final XmppConnectionService service;

    /** The encrypted body of a retraction: the fallback text plus the retracted id. */
    static String encryptedBody(final String id) {
        return RETRACTION_FALLBACK + INNER_ID_PREFIX + id + "]";
    }

    /** The retracted id inside a decrypted retraction body; null when the sender put none there. */
    @Nullable
    static String innerId(@Nullable final String body) {
        if (body == null || !body.endsWith("]")) {
            return null;
        }
        final int start = body.lastIndexOf(INNER_ID_PREFIX);
        return start < 0 ? null : body.substring(start + INNER_ID_PREFIX.length(), body.length() - 1);
    }

    // Tatrak: decrypted bodies that make an outer <retract> on an OMEMO message count: ours
    // (with or without the inner id), monocles' and the XEP-0424 fallback
    private static final String MONOCLES_RETRACTION_BODY =
            "This message has been retracted by the sender.";
    private static final String XEP_RETRACTION_PREFIX = "/me retracted a previous message";

    /**
     * Whether the decrypted body of an OMEMO message with an outer {@code <retract>} is a
     * retraction notice. Legacy OMEMO leaves the {@code <retract>} unencrypted, so without this a
     * server could add one to any ordinary encrypted message and delete another message of the
     * same sender; such a message is shown normally instead.
     */
    public static boolean isRetractionNotice(@Nullable final String body) {
        if (body == null) {
            return false;
        }
        final String text = body.trim();
        return text.startsWith(RETRACTION_FALLBACK)
                || text.equals(MONOCLES_RETRACTION_BODY)
                || text.startsWith(XEP_RETRACTION_PREFIX);
    }

    private final PendingRetractions pendingRetractions = new PendingRetractions();

    public ModerationManager(final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
    }

    public ListenableFuture<Void> moderate(final eu.siacs.conversations.entities.Message message) {
        final var serverMsgId = message.getServerMsgId();
        final var previous = message.getEditedServerMessageIds();
        if (!previous.isEmpty()) {
            Log.d(
                    Config.LOGTAG,
                    getAccount().getJid()
                            + ": requesting deletion of previous stanza-ids: "
                            + previous);
        }
        final var serverMsgIds =
                new ImmutableSet.Builder<String>().add(serverMsgId).addAll(previous).build();
        final var conversation = message.getConversation();
        final var address = conversation.getAddress().asBareJid();
        final var future = moderate(address, serverMsgIds);
        return Futures.transform(
                future,
                result -> {
                    if (message.getConversation() instanceof Conversation c) {
                        tombstone(c, message);
                        return null;
                    } else {
                        throw new IllegalStateException("Message was not part of conversation");
                    }
                },
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<List<Iq>> moderate(final Jid address, final Collection<String> ids) {
        final var futures =
                Collections2.transform(
                        ids,
                        id -> {
                            final var iq = new Iq(Iq.Type.SET);
                            iq.setTo(address);
                            final var moderate = iq.addExtension(new Moderate(id));
                            moderate.addExtension(new Retract());
                            return this.connection.sendIqPacket(iq);
                        });
        return Futures.allAsList(futures);
    }

    public void handleRetraction(final Message message) {
        final var account = getAccount();
        final var from = Jid.Invalid.getNullForInvalid(message.getFrom());
        if (from == null || from.isFullJid() || message.getType() != Message.Type.GROUPCHAT) {
            Log.d(
                    Config.LOGTAG,
                    "received retraction from "
                            + from
                            + " but retractions are only supported in MUC");
            return;
        }
        final var mucOptions = getManager(MultiUserChatManager.class).getState(from.asBareJid());
        if (mucOptions == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": received retraction in MUC w/o state");
            return;
        }
        if (mucOptions.isPrivateAndNonAnonymous()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": retractions are only supported in public channels");
            return;
        }
        final var retraction = message.getExtension(Retract.class);
        final var stanzaId = retraction == null ? null : retraction.getId();
        if (stanzaId == null) {
            Log.d(Config.LOGTAG, "retraction was missing stanza-id");
            return;
        }
        final var conversation = mucOptions.getConversation();
        final eu.siacs.conversations.entities.Message retractedMessage;
        final var inMemoryMessage = conversation.findMessageWithServerMsgId(stanzaId);
        if (inMemoryMessage != null) {
            retractedMessage = inMemoryMessage;
        } else {
            retractedMessage = getDatabase().getMessageWithServerMsgId(conversation, stanzaId);
        }
        if (retractedMessage == null) {
            Log.d(Config.LOGTAG, "received retraction for " + stanzaId + ". Message not found.");
            return;
        }
        final var moderated = retraction.getModerated();
        final var by = moderated == null ? null : moderated.getBy();
        Log.d(Config.LOGTAG, "received retraction for " + stanzaId + " in " + from + " by " + by);
        tombstone(conversation, retractedMessage);
    }

    /**
     * XEP-0424: asks everyone in the chat to delete our own message, then deletes the local copy
     * like a moderation does. False when the message can't be referenced (not sent yet, channel
     * without stanza-ids or occupant-ids) or we are offline.
     */
    public boolean retract(final eu.siacs.conversations.entities.Message message) {
        if (!(message.getConversation() instanceof Conversation conversation)) {
            return false;
        }
        if (message.getStatus() == eu.siacs.conversations.entities.Message.STATUS_RECEIVED
                || !getAccount().isOnlineAndConnected()) {
            return false;
        }
        final String id;
        final Jid to;
        final boolean groupChat;
        if (conversation.getMode() == Conversational.MODE_MULTI && !message.isPrivateMessage()) {
            final var mucOptions = conversation.getMucOptions();
            // receivers only accept retractions they can tie to the author's occupant-id
            if (!mucOptions.participating() || !mucOptions.occupantId()) {
                return false;
            }
            id = message.getServerMsgId();
            to = conversation.getAddress().asBareJid();
            groupChat = true;
        } else {
            id = message.getMessageId();
            to =
                    message.isPrivateMessage()
                            ? message.getCounterpart()
                            : conversation.getAddress().asBareJid();
            groupChat = false;
        }
        if (to == null || Strings.isNullOrEmpty(id)) {
            return false;
        }
        final var packet = new Message(groupChat ? Message.Type.GROUPCHAT : Message.Type.CHAT);
        packet.setTo(to);
        packet.addExtension(new Retract(id));
        packet.addExtension(new Fallback(Namespace.RETRACTION));
        packet.setBody(RETRACTION_FALLBACK);
        packet.addExtension(new Store());
        if (message.getEncryption() == eu.siacs.conversations.entities.Message.ENCRYPTION_AXOLOTL) {
            sendEncrypted(conversation, message, packet, id);
        } else {
            this.connection.sendMessagePacket(packet);
        }
        delete(conversation, message);
        return true;
    }

    /**
     * Receivers only let a retraction delete an OMEMO message when the retraction is encrypted
     * with the author's key (see {@link #isVerified}). Legacy OMEMO encrypts only the body, so the
     * fallback text goes in encrypted and the {@code <retract>} stays outside, as monocles does.
     * Encrypted off the main thread through the existing {@code AxolotlService.encrypt}, the same
     * call the normal send path makes. If that fails (no sessions), the retraction goes out plain:
     * up-to-date receivers then keep the message with a mark.
     */
    private void sendEncrypted(
            final Conversation conversation,
            final eu.siacs.conversations.entities.Message message,
            final Message packet,
            final String id) {
        final var axolotl = getAccount().getAxolotlService();
        final var carrier =
                new eu.siacs.conversations.entities.Message(
                        conversation,
                        encryptedBody(id),
                        eu.siacs.conversations.entities.Message.ENCRYPTION_AXOLOTL);
        if (message.isPrivateMessage()) {
            carrier.setType(eu.siacs.conversations.entities.Message.TYPE_PRIVATE);
            carrier.setCounterpart(message.getCounterpart());
            carrier.setTrueCounterpart(message.getTrueCounterpart());
        }
        ENCRYPTION_EXECUTOR.execute(
                () -> {
                    final var encrypted = axolotl == null ? null : axolotl.encrypt(carrier);
                    if (encrypted != null) {
                        packet.setAxolotlMessage(encrypted.toElement());
                        packet.addChild("encryption", "urn:xmpp:eme:0")
                                .setAttribute("name", "OMEMO")
                                .setAttribute(
                                        "namespace",
                                        eu.siacs.conversations.crypto.axolotl.AxolotlService
                                                .PEP_PREFIX);
                    } else {
                        Log.w(Config.LOGTAG, "could not encrypt retraction, sending it plain");
                    }
                    this.connection.sendMessagePacket(packet);
                });
    }

    private static final java.util.concurrent.Executor ENCRYPTION_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * XEP-0424 from a participant (not a moderator). Only the author may retract: same direction
     * in 1:1 chats, same occupant-id in channels and private messages, as reactions validate.
     *
     * <p>An OMEMO-encrypted message is only deleted when the retraction proves it comes from the
     * author's device (see {@link #isVerified}); otherwise a server could forge a plain retraction
     * and silently destroy an encrypted message. Such a message is kept and marked instead.
     *
     * @param fingerprint OMEMO key the retraction itself was encrypted with, null if plain
     */
    public void handleUserRetraction(
            final Message packet, final Jid counterpart, @Nullable final String fingerprint) {
        handleUserRetraction(packet, counterpart, fingerprint, null);
    }

    /**
     * @param decryptedBody the body of an OMEMO-encrypted retraction; when it carries the
     *     retracted id (see {@link #encryptedBody}) that id is used instead of the outer one
     */
    public void handleUserRetraction(
            final Message packet,
            final Jid counterpart,
            @Nullable final String fingerprint,
            @Nullable final String decryptedBody) {
        final var retraction = packet.getExtension(Retract.class);
        final var outerId = retraction == null ? null : retraction.getId();
        final var account = getAccount();
        final var conversation =
                counterpart == null ? null : this.service.find(account, counterpart.asBareJid());
        if (outerId == null || conversation == null) {
            return;
        }
        // the encrypted copy is what the author retracted; the outer id can be rewritten in transit
        final var innerId = fingerprint == null ? null : innerId(decryptedBody);
        if (innerId != null && !innerId.equals(outerId)) {
            Log.d(Config.LOGTAG, "retraction for " + outerId + " was encrypted for " + innerId);
        }
        final String id = innerId != null ? innerId : outerId;
        final eu.siacs.conversations.entities.Message target;
        final boolean groupchat = packet.getType() == Message.Type.GROUPCHAT;
        String retractingOccupant = null;
        if (conversation.getMode() == Conversational.MODE_MULTI) {
            final var mucOptions =
                    getManager(MultiUserChatManager.class).getOrCreateState(conversation);
            final var occupant =
                    mucOptions.occupantId() ? packet.getOnlyExtension(OccupantId.class) : null;
            final var occupantId = occupant == null ? null : occupant.getId();
            if (occupantId == null) {
                Log.d(Config.LOGTAG, "received retraction w/o occupant id. ignoring");
                return;
            }
            retractingOccupant = occupantId;
            if (groupchat) {
                final var inMemory = conversation.findMessageWithServerMsgId(id);
                target =
                        inMemory != null
                                ? inMemory
                                : getDatabase().getMessageWithServerMsgId(conversation, id);
            } else {
                final var inMemory = conversation.findMessageWithUuidOrRemoteId(id, null, null);
                target =
                        inMemory != null
                                ? inMemory
                                : getDatabase().getMessageWithUuidOrRemoteId(conversation, id);
            }
            if (target != null && !occupantId.equals(target.getOccupantId())) {
                Log.d(Config.LOGTAG, "retraction by a different occupant. ignoring");
                return;
            }
        } else {
            final boolean received = !packet.fromAccount(account);
            final var inMemory = conversation.findMessageWithUuidOrRemoteId(id, null, received);
            final var candidate =
                    inMemory != null
                            ? inMemory
                            : getDatabase().getMessageWithUuidOrRemoteId(conversation, id);
            final boolean sameSide =
                    candidate != null
                            && (candidate.getStatus()
                                            == eu.siacs.conversations.entities.Message
                                                    .STATUS_RECEIVED)
                                    == received;
            target = sameSide ? candidate : null;
        }
        // not gated on fromAccount: in a group our other device's retraction comes from
        // room@muc/nick; the occupant id / direction checks above keep it to our own messages
        final boolean ownTrustedDevice = isOwnTrustedDevice(fingerprint);
        if (target == null) {
            // the message may still be on its way (MAM pages newest-first): apply it on arrival
            Log.d(Config.LOGTAG, "received retraction for " + id + ". Message not found, keeping it.");
            pendingRetractions.add(
                    conversation.getUuid(),
                    id,
                    groupchat,
                    retractingOccupant,
                    !packet.fromAccount(account),
                    fingerprint,
                    ownTrustedDevice,
                    System.currentTimeMillis());
            return;
        }
        if (isVerified(target.getFingerprint(), fingerprint, ownTrustedDevice)) {
            tombstone(conversation, target);
        } else {
            markAttempt(target, System.currentTimeMillis());
        }
    }

    /**
     * Whether a retraction may delete a message: a plain message (no OMEMO key) always; an
     * encrypted one only when the retraction was encrypted with the same key, or comes from one
     * of our own trusted devices (deleting our own message from another device). Same rule as
     * monocles' {@code editFingerprintsMatch}.
     */
    static boolean isVerified(
            @Nullable final String messageFingerprint,
            @Nullable final String retractionFingerprint,
            final boolean ownTrustedDevice) {
        return messageFingerprint == null
                || messageFingerprint.equals(retractionFingerprint)
                || ownTrustedDevice;
    }

    /** A key of one of our own other devices that we trust (blind trust or verified). */
    private boolean isOwnTrustedDevice(@Nullable final String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        final var axolotl = getAccount().getAxolotlService();
        if (axolotl == null) {
            return false;
        }
        for (final var session : axolotl.findOwnSessions()) {
            if (fingerprint.equals(session.getFingerprint())) {
                final var status = axolotl.getFingerprintTrust(fingerprint);
                return status != null && status.isTrustedAndActive();
            }
        }
        return false;
    }

    /** Keeps an encrypted message an unverified retraction asked to delete, and says so. */
    private void markAttempt(final eu.siacs.conversations.entities.Message message, final long at) {
        Log.d(Config.LOGTAG, "unverified retraction of encrypted message " + message.getUuid() + ", keeping it");
        if (message.getRetractAttempt() != 0) {
            return;
        }
        message.setRetractAttempt(at);
        getDatabase().updateMessage(message, false);
        this.service.updateConversationUi();
    }

    /**
     * Called by the parser before it stores a new message: true when a retraction for it arrived
     * earlier, in which case the message must be dropped instead of stored.
     */
    public boolean isRetracted(
            final Conversation conversation, final eu.siacs.conversations.entities.Message message) {
        final var proof =
                pendingRetractions.take(
                        conversation.getUuid(),
                        message.getServerMsgId(),
                        message.getRemoteMsgId(),
                        message.getUuid(),
                        conversation.getMode() == Conversational.MODE_MULTI
                                ? message.getOccupantId()
                                : null,
                        message.getStatus() == eu.siacs.conversations.entities.Message.STATUS_RECEIVED,
                        System.currentTimeMillis());
        if (proof == null) {
            return false;
        }
        if (!isVerified(message.getFingerprint(), proof.fingerprint(), proof.ownTrustedDevice())) {
            // stored as usual, with the mark; the caller saves it right after
            message.setRetractAttempt(proof.at());
            Log.d(Config.LOGTAG, "unverified early retraction of encrypted message " + message.getUuid() + ", keeping it");
            return false;
        }
        Log.d(Config.LOGTAG, "dropping message " + message.getUuid() + ", retracted before it arrived");
        return true;
    }

    /**
     * Tatrak: called by the parser for every correction (XEP-0308), whatever the correction
     * setting: true when it is aimed at a retracted or hidden message, or at one whose retraction
     * is waiting for it; such a correction would bring the deleted text back, so it is dropped.
     *
     * @param replaced the corrected message as the parser looked it up (same sender), or null
     */
    public boolean isCorrectionOfRetracted(
            final Conversation conversation,
            final eu.siacs.conversations.entities.Message correction,
            final String replacementId,
            @Nullable final eu.siacs.conversations.entities.Message replaced) {
        final boolean retracted =
                isCorrectionOfRetracted(
                        pendingRetractions,
                        conversation,
                        correction,
                        replacementId,
                        replaced,
                        System.currentTimeMillis());
        if (retracted) {
            Log.d(Config.LOGTAG, "dropping correction of " + replacementId + ", it was retracted");
        }
        return retracted;
    }

    static boolean isCorrectionOfRetracted(
            final PendingRetractions pendingRetractions,
            final Conversation conversation,
            final eu.siacs.conversations.entities.Message correction,
            final String replacementId,
            @Nullable final eu.siacs.conversations.entities.Message replaced,
            final long now) {
        if (replaced != null) {
            final int type = replaced.getType();
            return type == eu.siacs.conversations.entities.Message.TYPE_RETRACTED
                    || type == eu.siacs.conversations.entities.Message.TYPE_HIDDEN;
        }
        final var proof =
                pendingRetractions.peek(
                        conversation.getUuid(),
                        replacementId,
                        conversation.getMode() == Conversational.MODE_MULTI
                                ? correction.getOccupantId()
                                : null,
                        correction.getStatus() == eu.siacs.conversations.entities.Message.STATUS_RECEIVED,
                        now);
        if (proof == null) {
            return false;
        }
        // the original (and its key) is not here: the proof is checked against the correction's own
        // key, so an edit under another key than the retraction's is kept, marked (policy, see risks)
        if (!isVerified(correction.getFingerprint(), proof.fingerprint(), proof.ownTrustedDevice())) {
            correction.setRetractAttempt(proof.at());
            return false;
        }
        return true;
    }

    /**
     * Tatrak: the message of somebody else was retracted. Keeps a "deleted" placeholder and erases the
     * text, the edit history and the attachment (unless another message uses the file).
     */
    private void tombstone(
            final Conversation conversation,
            final eu.siacs.conversations.entities.Message message) {
        // deleted "for me" already: it stays out of sight, not a placeholder
        if (message.getType() == eu.siacs.conversations.entities.Message.TYPE_HIDDEN) return;
        // a running download would restore the file when it finishes
        final var transferable = message.getTransferable();
        if (transferable != null) transferable.cancel();
        final var location = message.getRelativeFilePath();
        final boolean hadFile = message.isFileOrImage();
        message.eraseContent();
        // with the body, so the text and its search index entry are gone from disk too
        getDatabase().updateMessage(message, true);
        this.service.getNotificationService().clear(message);
        if (hadFile && location != null) {
            deleteUnusedFile(location.file());
        }
        this.service.updateConversationUi();
    }

    private void delete(
            final Conversation conversation,
            final eu.siacs.conversations.entities.Message message) {
        conversation.remove(message);
        this.service.getNotificationService().clear(message);
        if (getDatabase().deleteMessage(message.getUuid())) {
            deleteAssociatedFile(message);
        }
        this.service.updateConversationUi();
    }

    private void deleteAssociatedFile(final eu.siacs.conversations.entities.Message message) {
        if (message.isFileOrImage()) {
            final var storageLocation = message.getRelativeFilePath();
            if (storageLocation == null) {
                return;
            }
            deleteUnusedFile(storageLocation.file());
        }
    }

    private void deleteUnusedFile(final java.io.File file) {
        // since the retracted message has already been deleted or erased this should come up
        // empty for files that are not used elsewhere
        final var messagesWithFile = getDatabase().getMessagesWithFile(file);
        if (messagesWithFile.isEmpty() && file.exists()) {
            synchronized (service.FILENAMES_TO_IGNORE_DELETION) {
                service.FILENAMES_TO_IGNORE_DELETION.add(file.getAbsolutePath());
            }
            if (file.delete()) {
                Log.d(Config.LOGTAG, "deleted associated file");
            }
        }
    }
}
