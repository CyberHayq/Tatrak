package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Blockable;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.RawBlockable;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.manager.BlockingManager;
import java.util.Collections;

public class BlocklistActivity extends AbstractSearchableListItemActivity
        implements OnUpdateBlocklist {

    private Account account = null;

    private BlocklistCompose compose;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getListView()
                .setOnItemLongClickListener(
                        (parent, view, position, id) -> {
                            BlockContactDialog.show(
                                    BlocklistActivity.this,
                                    (Blockable) getListItems().get(position));
                            return true;
                        });
        this.binding.fab.show();
        this.binding.fab.setOnClickListener((v) -> showEnterJidDialog());
        // Tatrak: the Compose list over the hidden classic layout; see BlocklistCompose
        this.compose = new BlocklistCompose(this);
        this.compose.attach(binding.getRoot());
    }

    @Override
    public void onBackendConnected() {
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (account.getJid().toString().equals(getIntent().getStringExtra(EXTRA_ACCOUNT))) {
                this.account = account;
                break;
            }
        }
        // Tatrak: the search field is the Compose top bar's
        filterContacts(compose.getNeedle());
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (fragment instanceof OnBackendConnected) {
            ((OnBackendConnected) fragment).onBackendConnected();
        }
    }

    @Override
    protected void filterContacts(final String needle) {
        getListItems().clear();
        if (account != null) {
            // TODO create getBlocklistAsListItems
            for (final Jid jid :
                    account.getXmppConnection().getManager(BlockingManager.class).getBlocklist()) {
                ListItem item;
                if (jid.isFullJid()) {
                    item = new RawBlockable(account, jid);
                } else {
                    item = account.getRoster().getContact(jid);
                }
                if (item.match(needle)) {
                    getListItems().add(item);
                }
            }
            Collections.sort(getListItems());
            // Tatrak: the list also goes to the Compose screen
            compose.publish(getListItems());
        }
        getListItemAdapter().notifyDataSetChanged();
    }

    protected void showEnterJidDialog() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        EnterJidDialog dialog =
                EnterJidDialog.newInstance(
                        null,
                        getString(R.string.block_jabber_id),
                        getString(R.string.block),
                        null,
                        account.getJid().asBareJid().toString(),
                        true,
                        false);

        dialog.setOnEnterJidDialogPositiveListener(
                (accountJid, contactJid) -> {
                    Blockable blockable = new RawBlockable(account, contactJid);
                    if (xmppConnectionService.sendBlockRequest(blockable, false, null)) {
                        Toast.makeText(
                                        BlocklistActivity.this,
                                        R.string.corresponding_chats_closed,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                    return true;
                });

        dialog.show(ft, "dialog");
    }

    protected void refreshUiReal() {
        // Tatrak: the search field is the Compose top bar's
        filterContacts(compose.getNeedle());
    }

    @Override
    public void OnUpdateBlocklist(final OnUpdateBlocklist.Status status) {
        refreshUi();
    }
}
