/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;

import static eu.siacs.conversations.ui.util.SoftKeyboardUtils.hideSoftKeyboard;
import static eu.siacs.conversations.ui.util.SoftKeyboardUtils.showKeyboard;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivitySearchBinding;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.MessageSearchTask;
import eu.siacs.conversations.ui.adapter.MessageAdapter;
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable;
import eu.siacs.conversations.ui.util.ChangeWatcher;
import eu.siacs.conversations.ui.util.DateSeparator;
import eu.siacs.conversations.ui.util.ListViewUtils;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.utils.FtsUtils;
import eu.siacs.conversations.utils.MessageUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class SearchActivity extends XmppActivity
        implements TextWatcher, OnSearchResultsAvailable, MessageAdapter.OnContactPictureClicked {

    public static final String EXTRA_SEARCH_TERM = "search-term";
    public static final String EXTRA_CONVERSATION_UUID = "uuid";

    private ActivitySearchBinding binding;
    private MessageAdapter messageListAdapter;
    private final List<Message> messages = new ArrayList<>();
    private WeakReference<Message> selectedMessageReference = new WeakReference<>(null);
    private String uuid;
    private final ChangeWatcher<List<String>> currentSearch = new ChangeWatcher<>();
    private final PendingItem<String> pendingSearchTerm = new PendingItem<>();
    private final PendingItem<List<String>> pendingSearch = new PendingItem<>();
    private SearchCompose compose;

    @Override
    public void onCreate(final Bundle bundle) {
        super.onCreate(bundle);
        final Intent intent = getIntent();
        final Bundle parameters;
        if (bundle != null) {
            parameters = bundle;
        } else if (intent != null) {
            parameters = intent.getExtras();
        } else {
            parameters = null;
        }
        if (parameters != null) {
            this.uuid = parameters.getString(EXTRA_CONVERSATION_UUID);
            final var searchTerm = parameters.getString(EXTRA_SEARCH_TERM);
            if (searchTerm != null) {
                this.pendingSearchTerm.push(searchTerm);
            }
        } else {
            this.uuid = null;
        }
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_search);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(this.binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.messageListAdapter = new MessageAdapter(this, this.messages, uuid == null);
        this.messageListAdapter.setOnContactPictureClicked(this);
        this.binding.searchResults.setAdapter(messageListAdapter);
        registerForContextMenu(this.binding.searchResults);
        // Tatrak: Compose screen over the hidden classic layout; the search term from the intent
        // or the saved state goes to its field, the search itself runs as before
        final String term = pendingSearchTerm.pop();
        compose = new SearchCompose(this, Strings.nullToEmpty(term));
        compose.attach(binding.getRoot());
        if (term != null) {
            onSearchText(term);
        }
    }

    boolean isGlobalSearch() {
        return uuid == null;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if (compose != null) {
            return false;
        }
        getMenuInflater().inflate(R.menu.activity_search, menu);
        final MenuItem searchActionMenuItem = menu.findItem(R.id.action_search);
        final EditText searchField =
                searchActionMenuItem.getActionView().findViewById(R.id.search_field);
        final String term = pendingSearchTerm.pop();
        if (term != null) {
            searchField.append(term);
            final List<String> searchTerm = FtsUtils.parse(term);
            if (xmppConnectionService != null) {
                if (currentSearch.watch(searchTerm)) {
                    xmppConnectionService.search(searchTerm, uuid, this);
                }
            } else {
                pendingSearch.push(searchTerm);
            }
        }
        searchField.addTextChangedListener(this);
        searchField.setHint(R.string.search_messages);
        searchField.setContentDescription(getString(R.string.search_messages));
        searchField.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);
        if (term == null) {
            showKeyboard(searchField);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onCreateContextMenu(
            final ContextMenu menu, final View v, ContextMenu.ContextMenuInfo menuInfo) {
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
        AdapterView.AdapterContextMenuInfo acmi = (AdapterView.AdapterContextMenuInfo) menuInfo;
        final Message message = this.messages.get(acmi.position);
        this.selectedMessageReference = new WeakReference<>(message);
        getMenuInflater().inflate(R.menu.search_result_context, menu);
        final MenuItem copy = menu.findItem(R.id.copy_message);
        final MenuItem quote = menu.findItem(R.id.quote_message);
        if (message.isGeoUri()) {
            copy.setVisible(false);
            quote.setVisible(false);
        }
        super.onCreateContextMenu(menu, v, menuInfo);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            hideSoftKeyboard(this);
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onContextItemSelected(@NonNull final MenuItem item) {
        final Message message = selectedMessageReference.get();
        if (message == null) {
            return super.onContextItemSelected(item);
        }
        return messageAction(message, item.getItemId()) || super.onContextItemSelected(item);
    }

    /** One of the {@code search_result_context} actions; also called by the Compose screen. */
    boolean messageAction(final Message message, final int itemId) {
        if (itemId == R.id.open_conversation) {
            // Tatrak: the chat opens scrolled to the found message
            hideSoftKeyboard(this);
            MessageSearchResults.openAt(this, wrap(message.getConversation()), message.getUuid());
            finish();
            return true;
        } else if (itemId == R.id.share_with) {
            ShareUtil.share(this, message);
            return true;
        } else if (itemId == R.id.copy_message) {
            ShareUtil.copyToClipboard(this, message);
            return true;
        } else if (itemId == R.id.quote_message) {
            quote(message);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        List<String> term = currentSearch.get();
        if (term != null && term.size() > 0) {
            bundle.putString(EXTRA_SEARCH_TERM, FtsUtils.toUserEnteredString(term));
        }
        super.onSaveInstanceState(bundle);
    }

    private void quote(Message message) {
        switchToConversationAndQuote(
                wrap(message.getConversation()), MessageUtils.prepareQuote(message));
    }

    private Conversation wrap(Conversational conversational) {
        if (conversational instanceof Conversation) {
            return (Conversation) conversational;
        } else {
            return xmppConnectionService.findOrCreateConversation(
                    conversational.getAccount(),
                    conversational.getAddress(),
                    conversational.getMode() == Conversational.MODE_MULTI,
                    true,
                    true);
        }
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {
        if (compose != null && uuid != null) {
            final Conversation conversation = xmppConnectionService.findConversationByUuid(uuid);
            compose.setChatName(
                    conversation == null ? null : conversation.getName().toString());
        }
        final List<String> searchTerm = pendingSearch.pop();
        if (searchTerm != null) {
            // Tatrak: onSearchText already recorded the term before the service was bound
            currentSearch.watch(searchTerm);
            xmppConnectionService.search(searchTerm, uuid, this);
        }
    }

    private void changeBackground(boolean hasSearch, boolean hasResults) {
        if (hasSearch) {
            if (hasResults) {
                binding.searchResults.setBackgroundColor(
                        MaterialColors.getColor(
                                binding.searchResults,
                                com.google.android.material.R.attr.colorSurface));
            } else {
                binding.searchResults.setBackgroundResource(R.drawable.background_no_results);
            }
        } else {
            binding.searchResults.setBackgroundResource(R.drawable.background_search);
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(Editable s) {
        onSearchText(s.toString());
    }

    /** The search field changed (classic or Compose). */
    void onSearchText(final String text) {
        final List<String> term = FtsUtils.parse(text.trim());
        if (!currentSearch.watch(term)) {
            return;
        }
        if (term.isEmpty()) {
            MessageSearchTask.cancelRunningTasks();
            this.messages.clear();
            messageListAdapter.setHighlightedTerm(null);
            messageListAdapter.notifyDataSetChanged();
            changeBackground(false, false);
            if (compose != null) {
                compose.clear();
            }
        } else if (xmppConnectionService != null) {
            xmppConnectionService.search(term, uuid, this);
        } else {
            pendingSearch.push(term);
        }
    }

    @Override
    public void onSearchResultsAvailable(List<String> term, List<Message> messages) {
        runOnUiThread(
                () -> {
                    if (compose != null) {
                        compose.publish(term, new ArrayList<>(messages));
                    }
                    this.messages.clear();
                    messageListAdapter.setHighlightedTerm(term);
                    DateSeparator.addAll(messages);
                    this.messages.addAll(messages);
                    messageListAdapter.notifyDataSetChanged();
                    changeBackground(true, !messages.isEmpty());
                    ListViewUtils.scrollToBottom(this.binding.searchResults);
                });
    }

    @Override
    public void onContactPictureClicked(Message message) {
        String fingerprint;
        if (message.getEncryption() == Message.ENCRYPTION_PGP
                || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            fingerprint = "pgp";
        } else {
            fingerprint = message.getFingerprint();
        }
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            final Contact contact = message.getContact();
            if (contact != null) {
                if (contact.isSelf()) {
                    switchToAccount(message.getConversation().getAccount(), fingerprint);
                } else {
                    switchToContactDetails(contact, fingerprint);
                }
            }
        } else {
            switchToAccount(message.getConversation().getAccount(), fingerprint);
        }
    }
}
