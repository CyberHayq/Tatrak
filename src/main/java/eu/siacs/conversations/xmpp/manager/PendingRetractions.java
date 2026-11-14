package eu.siacs.conversations.xmpp.manager;

import androidx.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XEP-0424 retractions whose target has not arrived yet. Over MAM paged newest-first, or with
 * stanzas reordered by the server, the retraction can come before the message it retracts; it is
 * kept here for a while and applied when the message shows up, so the message is never stored.
 *
 * <p>Only what {@link ModerationManager#handleUserRetraction} would have checked is kept: the
 * same sender (occupant id in groups, direction in 1:1 chats) must match, so a retraction cannot
 * hit somebody else's message later either. The retraction's OMEMO proof is kept too, so the
 * caller can decide on arrival whether it may delete an encrypted message (see {@link
 * ModerationManager#isVerified}). In memory only, bounded and time-limited.
 */
final class PendingRetractions {

    static final int MAX_ENTRIES = 200;
    static final long MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;

    private record Key(String conversation, String id) {}

    /**
     * What the retraction proves about its sender.
     *
     * @param fingerprint OMEMO key the retraction was encrypted with, null when it was not
     * @param ownTrustedDevice it came from one of our own trusted OMEMO devices
     * @param at when it arrived
     */
    record Proof(@Nullable String fingerprint, boolean ownTrustedDevice, long at) {}

    /** @param taken its message arrived and was dropped; kept until expiry for later corrections */
    private record Pending(
            boolean groupchat,
            @Nullable String occupantId,
            boolean received,
            Proof proof,
            boolean taken) {}

    private final LinkedHashMap<Key, Pending> entries =
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<Key, Pending> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /**
     * @param groupchat the retraction came as a groupchat message, so {@code id} is a stanza id
     *     assigned by the room; otherwise it is the sender's message id
     * @param occupantId the retracting occupant in a group, null in a 1:1 chat
     * @param received the retraction came from the contact, not from one of our own devices
     */
    synchronized void add(
            final String conversation,
            final String id,
            final boolean groupchat,
            @Nullable final String occupantId,
            final boolean received,
            @Nullable final String fingerprint,
            final boolean ownTrustedDevice,
            final long now) {
        expire(now);
        final var key = new Key(conversation, id);
        // re-insert so iteration order stays age order for expire()
        entries.remove(key);
        entries.put(
                key,
                new Pending(
                        groupchat,
                        occupantId,
                        received,
                        new Proof(fingerprint, ownTrustedDevice, now),
                        false));
    }

    /**
     * The proof of a pending retraction whose target is the just-arrived message, or null when
     * there is none; the entry is consumed.
     *
     * @param occupantId of the message, null in a 1:1 chat
     * @param received the message is from the contact
     */
    @Nullable
    synchronized Proof take(
            final String conversation,
            @Nullable final String serverMsgId,
            @Nullable final String remoteMsgId,
            @Nullable final String uuid,
            @Nullable final String occupantId,
            final boolean received,
            final long now) {
        final var key =
                find(conversation, serverMsgId, remoteMsgId, uuid, occupantId, received, false, now);
        if (key == null) {
            return null;
        }
        final var entry = entries.get(key);
        // Tatrak: kept (in its age slot) so a correction arriving after the message still sees it
        entries.put(
                key,
                new Pending(entry.groupchat, entry.occupantId, entry.received, entry.proof, true));
        return entry.proof;
    }

    /**
     * Tatrak: like {@link #take} for a correction (XEP-0308) of a message that has not arrived
     * yet or was already dropped: {@code id} is the sender's id of that message. The entry stays,
     * so the message itself is still dropped when it comes.
     */
    @Nullable
    synchronized Proof peek(
            final String conversation,
            final String id,
            @Nullable final String occupantId,
            final boolean received,
            final long now) {
        final var key = find(conversation, null, id, null, occupantId, received, true, now);
        return key == null ? null : entries.get(key).proof;
    }

    @Nullable
    private Key find(
            final String conversation,
            @Nullable final String serverMsgId,
            @Nullable final String remoteMsgId,
            @Nullable final String uuid,
            @Nullable final String occupantId,
            final boolean received,
            final boolean includeTaken,
            final long now) {
        expire(now);
        if (entries.isEmpty()) {
            return null;
        }
        for (final String id : new String[] {serverMsgId, remoteMsgId, uuid}) {
            if (id == null) {
                continue;
            }
            final var key = new Key(conversation, id);
            final var entry = entries.get(key);
            if (entry == null || (entry.taken && !includeTaken)) {
                continue;
            }
            // a groupchat retraction names the room's stanza id; anything else the sender's own id
            final boolean idMatches = entry.groupchat == id.equals(serverMsgId);
            final boolean sameSender =
                    entry.occupantId != null
                            ? entry.occupantId.equals(occupantId)
                            : occupantId == null && entry.received == received;
            if (idMatches && sameSender) {
                return key;
            }
        }
        return null;
    }

    synchronized int size() {
        return entries.size();
    }

    private void expire(final long now) {
        final Iterator<Pending> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            // insertion order is age order, so stop at the first fresh one
            if (now - iterator.next().proof.at() > MAX_AGE_MILLIS) {
                iterator.remove();
            } else {
                return;
            }
        }
    }
}
