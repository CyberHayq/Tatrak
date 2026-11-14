package eu.siacs.conversations.ui.util;

import static eu.siacs.conversations.ui.XmppActivity.EXTRA_ACCOUNT;

import android.app.Activity;
import android.content.Intent;
import androidx.annotation.Nullable;
import com.google.common.collect.Collections2;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.ui.TrustKeysActivity;
import eu.siacs.conversations.xmpp.Jid;

/**
 * The OMEMO "trust keys before sending" check, moved verbatim out of {@code
 * ConversationFragment.trustKeysIfNeeded(int)} so that the classic and the Compose chat screen
 * share one implementation. Callers check {@code getNextEncryption() == ENCRYPTION_AXOLOTL}
 * first, exactly as before.
 */
public final class TrustKeysGate {

    private TrustKeysGate() {}

    /**
     * @return the {@link TrustKeysActivity} intent to start for a result if the user has to
     *     decide on keys before sending, {@code null} if sending may proceed
     */
    public static @Nullable Intent intentIfTrustNeeded(
            final Activity activity, final Conversation conversation) {
        final var axolotlService = conversation.getAccount().getAxolotlService();
        final var targets = axolotlService.getCryptoTargets(conversation);
        boolean hasUnaccepted = !conversation.getAcceptedCryptoTargets().containsAll(targets);
        // TODO basically all of those are hitting the database. This should be async
        boolean hasUndecidedOwn =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided())
                        .isEmpty();
        boolean hasUndecidedContacts =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets)
                        .isEmpty();
        boolean hasPendingKeys = !axolotlService.findDevicesWithoutSession(conversation).isEmpty();
        boolean hasNoTrustedKeys = axolotlService.anyTargetHasNoTrustedKeys(targets);
        boolean downloadInProgress = axolotlService.hasPendingKeyFetches(targets);
        if (hasUndecidedOwn
                || hasUndecidedContacts
                || hasPendingKeys
                || hasNoTrustedKeys
                || hasUnaccepted
                || downloadInProgress) {
            axolotlService.createSessionsIfNeeded(conversation);
            final Intent intent = new Intent(activity, TrustKeysActivity.class);
            intent.putExtra(
                    "contacts",
                    Collections2.transform(targets, Jid::toString).toArray(new String[0]));
            intent.putExtra(
                    EXTRA_ACCOUNT, conversation.getAccount().getJid().asBareJid().toString());
            intent.putExtra("conversation", conversation.getUuid());
            return intent;
        } else {
            return null;
        }
    }
}
