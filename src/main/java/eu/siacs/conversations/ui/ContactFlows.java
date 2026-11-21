package eu.siacs.conversations.ui;

import am.cyberhayq.chat.ui.accounts.AccountFilter;
import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.widget.TextView;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.ImmutableList;
import de.gultsch.common.MiniUri;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.StartConversationActivity.Invite;
import eu.siacs.conversations.ui.util.JidDialog;
import im.conversations.android.xmpp.model.stanza.Presence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tatrak: the contact-list flows of {@link StartConversationActivity}, moved here unchanged (the
 * activity's fields became parameters, Invite's fields its accessors) so the chat list's Contacts
 * tab runs the same code. The activity's methods delegate here.
 */
final class ContactFlows {

    static final String PREF_KEY_CONTACT_INTEGRATION_CONSENT = "contact_list_integration_consent";

    static final int REQUEST_SYNC_CONTACTS = 0x28cf;

    private ContactFlows() {}

    /** The activity's "hide offline" menu entry, as read in its onCreate. */
    static boolean isHidingOfflineContacts(final SharedPreferences preferences) {
        return QuickConversationsService.isConversations()
                && preferences.getBoolean("hide_offline", false);
    }

    static void setHideOfflineContacts(final SharedPreferences preferences, final boolean hide) {
        preferences.edit().putBoolean("hide_offline", hide).apply();
    }

    static void filterContacts(
            final XmppConnectionService xmppConnectionService,
            final List<ListItem> contacts,
            final boolean hideOfflineContacts,
            final String needle) {
        contacts.clear();
        final List<Account> accounts = xmppConnectionService.getAccounts();
        final var showOffline = !hideOfflineContacts;
        // Tatrak: only the account the app is filtered to (the dock's account switcher)
        final Account only = AccountFilter.selected(xmppConnectionService);
        for (final Account account : accounts) {
            if (account.isEnabled() && AccountFilter.shows(only, account)) {
                for (Contact contact : account.getRoster().getContacts()) {
                    Presence.Availability s = contact.getShownStatus();
                    if (contact.showInContactList()
                            && contact.match(needle)
                            && (showOffline || s.compareTo(Presence.Availability.OFFLINE) < 0)) {
                        contacts.add(contact);
                    }
                }
            }
        }
        Collections.sort(contacts);
    }

    static void deleteContact(
            final XmppActivity activity, final Contact contact, final Runnable refilter) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.action_delete_contact);
        // Tatrak: says what the roster removal does
        builder.setMessage(
                JidDialog.style(
                        activity,
                        R.string.tatrak_delete_contact_explained,
                        contact.getAddress().toString()));
        builder.setPositiveButton(
                R.string.delete,
                (dialog, which) -> {
                    activity.xmppConnectionService.deleteContactOnServer(contact);
                    refilter.run();
                });
        builder.create().show();
    }

    @SuppressLint("InflateParams")
    static void showCreateContactDialog(
            final XmppActivity activity,
            final ArrayList<String> activatedAccounts,
            final String prefilledJid,
            final Invite invite) {
        FragmentTransaction ft = activity.getSupportFragmentManager().beginTransaction();
        Fragment prev =
                activity.getSupportFragmentManager()
                        .findFragmentByTag(XmppActivity.FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        EnterJidDialog dialog =
                EnterJidDialog.newInstance(
                        activatedAccounts,
                        activity.getString(R.string.add_contact),
                        activity.getString(R.string.add),
                        prefilledJid,
                        invite == null ? null : invite.account(),
                        invite == null || !invite.uri().hasOmemoFingerprints(),
                        true);

        dialog.setOnEnterJidDialogPositiveListener(
                (accountJid, contactJid) -> {
                    if (!activity.xmppConnectionServiceBound) {
                        return false;
                    }

                    final Account account =
                            activity.xmppConnectionService.findAccountByJid(accountJid);
                    if (account == null) {
                        return true;
                    }

                    final Contact contact = account.getRoster().getContact(contactJid);
                    if (invite != null && invite.uri().getName() != null) {
                        contact.setServerName(invite.uri().getName());
                    }
                    if (contact.isSelf()) {
                        switchToConversation(activity, contact);
                        return true;
                    } else if (contact.showInRoster()) {
                        throw new EnterJidDialog.JidError(
                                activity.getString(R.string.contact_already_exists));
                    } else {
                        final String preAuth =
                                invite == null
                                        ? null
                                        : invite.uri()
                                                .getParameter(MiniUri.Xmpp.PARAMETER_PRE_AUTH);
                        activity.xmppConnectionService.createContact(contact, preAuth);
                        if (invite != null && invite.uri().hasOmemoFingerprints()) {
                            activity.xmppConnectionService.verifyFingerprints(
                                    contact, invite.uri().getOmemoFingerprints());
                        }
                        switchToConversationDoNotAppend(
                                activity, contact, invite == null ? null : invite.uri().getBody());
                        return true;
                    }
                });
        dialog.show(ft, XmppActivity.FRAGMENT_TAG_DIALOG);
    }

    static void switchToConversation(final XmppActivity activity, Contact contact) {
        Conversation conversation =
                activity.xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getAddress(), false, true);
        activity.switchToConversation(conversation);
    }

    static void switchToConversationDoNotAppend(
            final XmppActivity activity, Contact contact, String body) {
        Conversation conversation =
                activity.xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getAddress(), false, true);
        activity.switchToConversationDoNotAppend(conversation, body);
    }

    static boolean askForContactsPermissions(
            final XmppActivity activity, final AtomicBoolean requestedContactsPermission) {
        if (!QuickConversationsService.isContactListIntegration(activity)) {
            return false;
        }
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (requestedContactsPermission.compareAndSet(false, true)) {
            final ImmutableList.Builder<String> permissionBuilder = new ImmutableList.Builder<>();
            permissionBuilder.add(Manifest.permission.READ_CONTACTS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionBuilder.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            final String[] permission = permissionBuilder.build().toArray(new String[0]);
            final String consent =
                    PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext())
                            .getString(PREF_KEY_CONTACT_INTEGRATION_CONSENT, null);
            final boolean requiresConsent =
                    (QuickConversationsService.isQuicksy()
                                    || QuickConversationsService.isPlayStoreFlavor())
                            && !"agreed".equals(consent);
            if (requiresConsent && "declined".equals(consent)) {
                Log.d(
                        Config.LOGTAG,
                        "not asking for contacts permission because consent has been declined");
                return false;
            }
            if (requiresConsent
                    || activity.shouldShowRequestPermissionRationale(
                            Manifest.permission.READ_CONTACTS)) {
                final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
                final AtomicBoolean requestPermission = new AtomicBoolean(false);
                if (QuickConversationsService.isQuicksy()) {
                    builder.setTitle(R.string.quicksy_wants_your_consent);
                    builder.setMessage(
                            Html.fromHtml(
                                    activity.getString(
                                            R.string.sync_with_contacts_quicksy_static)));
                } else {
                    builder.setTitle(R.string.sync_with_contacts);
                    builder.setMessage(
                            activity.getString(
                                    R.string.sync_with_contacts_long,
                                    activity.getString(R.string.app_name)));
                }
                @StringRes int confirmButtonText;
                if (requiresConsent) {
                    confirmButtonText = R.string.agree_and_continue;
                } else {
                    confirmButtonText = R.string.next;
                }
                builder.setPositiveButton(
                        confirmButtonText,
                        (dialog, which) -> {
                            if (requiresConsent) {
                                PreferenceManager.getDefaultSharedPreferences(
                                                activity.getApplicationContext())
                                        .edit()
                                        .putString(PREF_KEY_CONTACT_INTEGRATION_CONSENT, "agreed")
                                        .apply();
                            }
                            if (requestPermission.compareAndSet(false, true)) {
                                activity.requestPermissions(permission, REQUEST_SYNC_CONTACTS);
                            }
                        });
                if (requiresConsent) {
                    builder.setNegativeButton(
                            R.string.decline,
                            (dialog, which) ->
                                    PreferenceManager.getDefaultSharedPreferences(
                                                    activity.getApplicationContext())
                                            .edit()
                                            .putString(
                                                    PREF_KEY_CONTACT_INTEGRATION_CONSENT,
                                                    "declined")
                                            .apply());
                } else {
                    builder.setOnDismissListener(
                            dialog -> {
                                if (requestPermission.compareAndSet(false, true)) {
                                    activity.requestPermissions(permission, REQUEST_SYNC_CONTACTS);
                                }
                            });
                }
                builder.setCancelable(requiresConsent);
                final AlertDialog dialog = builder.create();
                dialog.setCanceledOnTouchOutside(requiresConsent);
                dialog.setOnShowListener(
                        dialogInterface -> {
                            final TextView tv = dialog.findViewById(android.R.id.message);
                            if (tv != null) {
                                tv.setMovementMethod(LinkMovementMethod.getInstance());
                            }
                        });
                dialog.show();
            } else {
                activity.requestPermissions(permission, REQUEST_SYNC_CONTACTS);
            }
        }
        return true;
    }

    /**
     * The activity's onRequestPermissionsResult, after super; {@code setRefreshing} is Quicksy's
     * spinner.
     */
    static void onRequestPermissionsResult(
            final XmppActivity activity,
            int requestCode,
            final int[] grantResults,
            final Runnable setRefreshing) {
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_SYNC_CONTACTS && activity.xmppConnectionServiceBound) {
                    if (QuickConversationsService.isQuicksy()) {
                        setRefreshing.run();
                    }
                    activity.xmppConnectionService.loadPhoneContacts();
                    activity.xmppConnectionService.startContactObserver();
                }
            }
    }
}
