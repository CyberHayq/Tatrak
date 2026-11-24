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

import static eu.siacs.conversations.ui.ConversationFragment.REQUEST_DECRYPT_PGP;

import am.cyberhayq.chat.ui.chatlist.ChatSuggestions;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.WindowCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.Iterables;
import de.gultsch.common.MiniUri;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.databinding.ActivityConversationsBinding;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.interfaces.OnConversationArchived;
import eu.siacs.conversations.ui.interfaces.OnConversationRead;
import eu.siacs.conversations.ui.interfaces.OnConversationSelected;
import eu.siacs.conversations.ui.interfaces.OnConversationsListItemUpdated;
import eu.siacs.conversations.ui.util.ActivityResult;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openintents.openpgp.util.OpenPgpApi;

public class ConversationsActivity extends QrCodeProcessingActivity
        implements OnConversationSelected,
                OnConversationArchived,
                OnConversationsListItemUpdated,
                OnConversationRead,
                XmppConnectionService.OnAccountUpdate,
                XmppConnectionService.OnConversationUpdate,
                XmppConnectionService.OnRosterUpdate,
                OnUpdateBlocklist,
                XmppConnectionService.OnShowErrorToast {

    public static final String ACTION_VIEW_CONVERSATION = "eu.siacs.conversations.action.VIEW";
    public static final String EXTRA_CONVERSATION = "conversationUuid";
    public static final String EXTRA_DOWNLOAD_UUID = "eu.siacs.conversations.download_uuid";
    public static final String EXTRA_AS_QUOTE = "eu.siacs.conversations.as_quote";
    public static final String EXTRA_NICK = "nick";
    public static final String EXTRA_IS_PRIVATE_MESSAGE = "pm";
    public static final String EXTRA_DO_NOT_APPEND = "do_not_append";
    public static final String EXTRA_POST_INIT_ACTION = "post_init_action";
    public static final String POST_ACTION_RECORD_VOICE = "record_voice";
    public static final String EXTRA_TYPE = "type";

    private static final List<String> VIEW_AND_SHARE_ACTIONS =
            Arrays.asList(
                    ACTION_VIEW_CONVERSATION, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE);

    public static final int REQUEST_OPEN_MESSAGE = 0x9876;
    public static final int REQUEST_PLAY_PAUSE = 0x5432;

    // secondary fragment (when holding the conversation, must be initialized before refreshing the
    // overview fragment
    private static final @IdRes int[] FRAGMENT_ID_NOTIFICATION_ORDER = {
        R.id.secondary_fragment, R.id.main_fragment
    };
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();
    // chats the user switched to the classic screen during this session
    private final Set<String> classicConversations = new HashSet<>();
    private ActivityConversationsBinding binding;
    private boolean mActivityPaused = true;

    private static boolean isViewOrShareIntent(Intent i) {
        Log.d(Config.LOGTAG, "action: " + (i == null ? null : i.getAction()));
        return i != null
                && VIEW_AND_SHARE_ACTIONS.contains(i.getAction())
                && i.hasExtra(EXTRA_CONVERSATION);
    }

    private static Intent createLauncherIntent(Context context) {
        final Intent intent = new Intent(context, ConversationsActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    @Override
    protected void refreshUiReal() {
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            refreshFragment(id);
        }
    }

    @Override
    protected void onBackendConnected() {
        xmppConnectionService.getNotificationService().setIsInForeground(true);
        final Intent intent = pendingViewIntent.pop();
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (binding.secondaryFragment != null) {
                    notifyFragmentOfBackendConnected(R.id.main_fragment);
                }
                return;
            }
        }
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            notifyFragmentOfBackendConnected(id);
        }

        final ActivityResult activityResult = postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }

        if (binding.secondaryFragment != null
                && getConversationInSecondary() == null) {
            final var conversation = ChatSuggestions.first(xmppConnectionService, null);
            openConversationOrCatch(conversation, null);
        }
        showDialogsIfMainIsOverview();
    }

    private void showDialogsIfMainIsOverview() {
        if (xmppConnectionService == null) {
            return;
        }
        final Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ChatListFragment) {
            if (ExceptionHelper.checkForCrash(this)) {
                return;
            }
            if (openBatteryOptimizationDialogIfNeeded()) {
                return;
            }
            requestNotificationPermissionIfNeeded();
        }
    }

    private String getBatteryOptimizationPreferenceKey() {
        @SuppressLint("HardwareIds")
        String device = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return "show_battery_optimization" + (device == null ? "" : device);
    }

    private void setNeverAskForBatteryOptimizationsAgain() {
        getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply();
    }

    private boolean openBatteryOptimizationDialogIfNeeded() {
        if (isOptimizingBattery()
                && getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true)) {
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle(R.string.battery_optimizations_enabled);
            builder.setMessage(
                    getString(
                            R.string.battery_optimizations_enabled_dialog,
                            getString(R.string.app_name)));
            builder.setPositiveButton(
                    R.string.next,
                    (dialog, which) -> {
                        final Intent intent =
                                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        final Uri uri = Uri.parse("package:" + getPackageName());
                        intent.setData(uri);
                        try {
                            startActivityForResult(intent, REQUEST_BATTERY_OP);
                        } catch (final ActivityNotFoundException e) {
                            Toast.makeText(
                                            this,
                                            R.string.device_does_not_support_battery_op,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });
            builder.setOnDismissListener(dialog -> setNeverAskForBatteryOptimizationsAgain());
            final AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
            return true;
        }
        return false;
    }

    // Tatrak: Android accepts one outstanding activity permission request at a time.
    private int mainPermissionRequest;
    private boolean askedForNotifications;
    final AtomicBoolean contactsPermissionRequested = new AtomicBoolean();

    boolean requestMainPermissions(final String[] permissions, final int requestCode) {
        if (mainPermissionRequest != 0) {
            return false;
        }
        mainPermissionRequest = requestCode;
        if (Arrays.asList(permissions).contains(Manifest.permission.POST_NOTIFICATIONS)) {
            askedForNotifications = true;
        }
        requestPermissions(permissions, requestCode);
        return true;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (!askedForNotifications
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestMainPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATION);
        }
    }

    private void notifyFragmentOfBackendConnected(@IdRes int id) {
        final Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if (fragment instanceof OnBackendConnected callback) {
            callback.onBackendConnected();
        }
    }

    private void refreshFragment(@IdRes int id) {
        final Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if (fragment instanceof XmppFragment xmppFragment) {
            xmppFragment.refresh();
        }
    }

    private boolean processViewIntent(final Intent intent) {
        final String uuid = intent.getStringExtra(EXTRA_CONVERSATION);
        final Conversation conversation =
                uuid != null ? xmppConnectionService.findConversationByUuidReliable(uuid) : null;
        if (conversation == null) {
            Log.d(Config.LOGTAG, "unable to view conversation with uuid:" + uuid);
            return false;
        }
        // Tatrak: a chat of another account opened from outside switches the app's account filter to it
        am.cyberhayq.chat.ui.accounts.AccountFilter.follow(
                xmppConnectionService, conversation.getAccount());
        openConversation(conversation, intent.getExtras());
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Tatrak: the chat list's Contacts tab asks for the contacts permission as
        // StartConversationActivity does
        if (requestCode == mainPermissionRequest) {
            mainPermissionRequest = 0;
            if (grantResults.length == 0) {
                askedForNotifications = false;
            }
        }
        ContactFlows.onRequestPermissionsResult(this, requestCode, grantResults, () -> {});
        final var main = getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (requestCode == ContactFlows.REQUEST_SYNC_CONTACTS && grantResults.length == 0) {
            contactsPermissionRequested.set(false);
        } else if (requestCode == REQUEST_POST_NOTIFICATION && main instanceof ChatListFragment list) {
            list.requestContactsPermissionIfVisible();
        }
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                switch (requestCode) {
                    case REQUEST_OPEN_MESSAGE:
                        refreshUiReal();
                        ConversationFragment.openPendingMessage(this);
                        break;
                    case REQUEST_PLAY_PAUSE:
                        ConversationFragment.startStopPending(this);
                        break;
                }
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    private void handleActivityResult(final ActivityResult activityResult) {
        if (activityResult.resultCode == AppCompatActivity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data);
        } else {
            handleNegativeActivityResult(activityResult.requestCode);
        }
        if (activityResult.requestCode == REQUEST_BATTERY_OP) {
            // the result code is always 0 even when battery permission were granted
            requestNotificationPermissionIfNeeded();
            XmppConnectionService.toggleForegroundService(xmppConnectionService);
        }
    }

    private void handleNegativeActivityResult(int requestCode) {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                if (conversation == null) {
                    break;
                }
                conversation.getAccount().getPgpDecryptionService().giveUpCurrentDecryption();
                break;
            case REQUEST_BATTERY_OP:
                setNeverAskForBatteryOptimizationsAgain();
                break;
        }
    }

    private void handlePositiveActivityResult(int requestCode, final Intent data) {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        if (conversation == null) {
            Log.d(Config.LOGTAG, "conversation not found");
            return;
        }
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                conversation.getAccount().getPgpDecryptionService().continueDecryption(data);
                break;
            case REQUEST_CHOOSE_PGP_ID:
                long id = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0);
                if (id != 0) {
                    conversation.getAccount().setPgpSignId(id);
                    announcePgp(conversation.getAccount(), null, null, onOpenPGPKeyPublished);
                } else {
                    choosePgpSignId(conversation.getAccount());
                }
                break;
            case REQUEST_ANNOUNCE_PGP:
                announcePgp(conversation.getAccount(), conversation, data, onOpenPGPKeyPublished);
                break;
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Tatrak: a permission request survives activity recreation.
        if (savedInstanceState != null) {
            mainPermissionRequest = savedInstanceState.getInt("tatrak_main_permission_request");
            askedForNotifications = savedInstanceState.getBoolean("tatrak_asked_notifications");
            contactsPermissionRequested.set(savedInstanceState.getBoolean("tatrak_requested_contacts"));
        }
        OmemoSetting.load(this);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        // fragments handle insets themselves so the chat list header can extend under the status bar
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ;
        this.getSupportFragmentManager()
                .addOnBackStackChangedListener(this::showDialogsIfMainIsOverview);
        this.initializeFragments();
        final Intent intent;
        if (savedInstanceState == null) {
            intent = getIntent();
        } else {
            intent = savedInstanceState.getParcelable("intent");
        }
        if (isViewOrShareIntent(intent)) {
            pendingViewIntent.push(intent);
            setIntent(createLauncherIntent(this));
        }
    }

    @Override
    public void onConversationSelected(Conversation conversation) {
        clearPendingViewIntent();
        if (getConversationInSecondary() == conversation) {
            // Tatrak: selecting the open chat again is an entry from the list; a private message ends
            if (getSupportFragmentManager().findFragmentById(R.id.secondary_fragment)
                    instanceof ChatFragment chatFragment) {
                chatFragment.endPrivateMessage();
            }
            Log.d(
                    Config.LOGTAG,
                    "ignore onConversationSelected() because conversation is already open");
            return;
        }
        openConversation(conversation, null);
    }

    public void clearPendingViewIntent() {
        if (pendingViewIntent.clear()) {
            Log.e(Config.LOGTAG, "cleared pending view intent");
        }
    }

    private void openConversation(@Nullable final Conversation conversation, final Bundle extras) {
        final var fragmentManager = getSupportFragmentManager();
        executePendingTransactions(fragmentManager);
        // Tatrak: a chat preview's open gives way to a newer navigation that the line above just ran
        if (ChatFragment.previewOpenCancelled()) {
            return;
        }
        final boolean mainNeedsRefresh;
        if (binding.secondaryFragment != null) {
            fragmentManager.popBackStackImmediate();
            final var secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment);
            if (conversation == null) {
                if (secondaryFragment != null) {
                    Log.d(Config.LOGTAG, "not loading conversation. removing secondary");
                    final var transaction = fragmentManager.beginTransaction();
                    transaction.remove(secondaryFragment);
                    transaction.commitAllowingStateLoss();
                } else {
                    Log.d(Config.LOGTAG, "not loading conversation and secondary is already empty");
                }
            } else {
                showConversation(R.id.secondary_fragment, conversation, extras, false);
            }
            mainNeedsRefresh = true;
        } else if (conversation != null) {
            mainNeedsRefresh = false;
            showConversation(R.id.main_fragment, conversation, extras, true);
        } else {
            mainNeedsRefresh = false;
        }
        if (mainNeedsRefresh) {
            refreshFragment(R.id.main_fragment);
        }
    }

    /**
     * Shows the conversation in the Compose {@link ChatFragment}, or in the classic {@link
     * ConversationFragment} for what the new screen does not handle (see {@link #wantsClassic}).
     */
    private void showConversation(
            @IdRes final int container,
            final Conversation conversation,
            @Nullable final Bundle extras,
            final boolean onBackStack) {
        final var fragmentManager = getSupportFragmentManager();
        final var existing = fragmentManager.findFragmentById(container);
        final var arguments = extras == null ? new Bundle() : extras;
        final boolean classic = wantsClassic(conversation, extras);
        if (classic && existing instanceof ChatFragment chatFragment) {
            // Tatrak: a group private message written in ChatFragment does not go along to the classic screen
            chatFragment.endPrivateMessage();
        }
        if (classic && existing instanceof ConversationFragment conversationFragment) {
            conversationFragment.reInit(conversation, arguments);
            return;
        }
        if (!classic && existing instanceof ChatFragment chatFragment) {
            chatFragment.reInit(conversation, arguments);
            return;
        }
        if (onBackStack && existing instanceof ConversationHost) {
            // switching between the classic and the new screen; don't stack them
            fragmentManager.popBackStackImmediate();
        }
        // reordering lets the pop follow a (predictive or swipe) back gesture
        final var transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        final boolean slide = onBackStack && !(existing instanceof ConversationHost);
        if (slide) {
            transaction.setCustomAnimations(
                    R.animator.tatrak_chat_enter,
                    R.animator.tatrak_list_exit,
                    R.animator.tatrak_list_pop_enter,
                    R.animator.tatrak_chat_pop_exit);
        }
        if (classic) {
            final var conversationFragment = new ConversationFragment();
            transaction.replace(container, conversationFragment);
            conversationFragment.reInit(conversation, arguments);
        } else {
            final var chatFragment = new ChatFragment();
            // Tatrak: wait for the first messages before sliding in
            chatFragment.setSlideAfterLoad(slide);
            transaction.replace(container, chatFragment);
            chatFragment.reInit(conversation, arguments);
        }
        if (onBackStack) {
            transaction.addToBackStack(null);
        } else {
            transaction.runOnCommit(() -> refreshFragment(R.id.main_fragment));
        }
        transaction.commitAllowingStateLoss();
    }

    private boolean wantsClassic(final Conversation conversation, @Nullable final Bundle extras) {
        if (classicConversations.contains(conversation.getUuid())) {
            return true;
        }
        if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
            return true;
        }
        // Tatrak: a private message is composed in ChatFragment; a nick alone (a mention) stays classic
        final boolean pm = extras != null && extras.getBoolean(EXTRA_IS_PRIVATE_MESSAGE, false);
        return extras != null
                && ((extras.containsKey(EXTRA_NICK) && !pm)
                        || extras.containsKey(EXTRA_POST_INIT_ACTION));
    }

    /** Called by {@link ChatFragment} for what only the classic screen can do. */
    public void openClassicConversation(@NonNull final Conversation conversation) {
        classicConversations.add(conversation.getUuid());
        openConversation(conversation, null);
    }

    private @Nullable Conversation getConversationInSecondary() {
        if (binding.secondaryFragment == null) {
            return null;
        }
        final var fragment = getSupportFragmentManager().findFragmentById(R.id.secondary_fragment);
        return fragment instanceof ConversationHost host ? host.getConversation() : null;
    }

    public static boolean isTabletView(final Activity activity) {
        if (activity instanceof ConversationsActivity conversationsActivity) {
            return conversationsActivity.binding.secondaryFragment != null;
        } else {
            return false;
        }
    }

    private void openConversationOrCatch(
            @Nullable final Conversation conversation, final Bundle extras) {
        try {
            openConversation(conversation, extras);
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "could not open conversation", e);
        }
    }

    private static void executePendingTransactions(final FragmentManager fragmentManager) {
        try {
            fragmentManager.executePendingTransactions();
        } catch (final Exception e) {
            Log.e(Config.LOGTAG, "unable to execute pending fragment transactions");
        }
    }

    public boolean onXmppUriClicked(final MiniUri.Xmpp uri) {
        if (uri.isAddress() && uri.getOmemoFingerprints().isEmpty()) {
            final Conversation conversation =
                    xmppConnectionService.findUniqueConversationByJid(uri);
            if (conversation != null) {
                openConversation(conversation, null);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && keyEvent.isCtrlPressed()) {
            final ConversationFragment conversationFragment = ConversationFragment.get(this);
            if (conversationFragment != null && conversationFragment.onArrowUpCtrlPressed()) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, keyEvent);
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle savedInstanceState) {
        // Tatrak: keep the runtime permission requests serialized across recreation.
        savedInstanceState.putInt("tatrak_main_permission_request", mainPermissionRequest);
        savedInstanceState.putBoolean("tatrak_asked_notifications", askedForNotifications);
        savedInstanceState.putBoolean("tatrak_requested_contacts", contactsPermissionRequested.get());
        final Intent pendingIntent = pendingViewIntent.peek();
        savedInstanceState.putParcelable(
                "intent", pendingIntent != null ? pendingIntent : getIntent());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void onNewIntent(@NonNull final Intent intent) {
        super.onNewIntent(intent);
        if (isViewOrShareIntent(intent)) {
            if (xmppConnectionService != null) {
                clearPendingViewIntent();
                processViewIntent(intent);
            } else {
                pendingViewIntent.push(intent);
            }
        }
        setIntent(createLauncherIntent(this));
    }

    @Override
    public void onPause() {
        this.mActivityPaused = true;
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        this.mActivityPaused = false;
    }

    private void initializeFragments() {
        final var fragmentManager = getSupportFragmentManager();
        final var fragments = fragmentManager.getFragments();
        final var optional = Iterables.tryFind(fragments, f -> f instanceof ConversationHost);
        final var existing = fragmentManager.findFragmentById(R.id.main_fragment);
        if (existing instanceof ChatListFragment
                && binding.secondaryFragment == null
                && optional.isPresent()) {
            Log.d(Config.LOGTAG, "moving ConversationFragment from secondary to main");
            fragmentManager.popBackStackImmediate();
            final var remove = fragmentManager.beginTransaction();
            remove.remove(optional.get());
            remove.commitNow();
            final var transaction = fragmentManager.beginTransaction();
            transaction.replace(R.id.main_fragment, optional.get());
            transaction.addToBackStack(null);
            transaction.commitAllowingStateLoss();
            return;
        }
        // Tatrak: preserve the restored list and its tab in either pane layout.
        if (existing instanceof ChatListFragment
                || (existing != null && binding.secondaryFragment == null)) {
            return;
        }
        final var transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.main_fragment, new ChatListFragment());
        transaction.commitAllowingStateLoss();
    }

    @Override
    public void onConversationArchived(final Conversation conversation) {
        final var fragmentManager = getSupportFragmentManager();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationHost) {
            try {
                fragmentManager.popBackStack();
            } catch (final IllegalStateException e) {
                Log.w(
                        Config.LOGTAG,
                        "state loss while popping back state after archiving conversation",
                        e);
                // this usually means activity is no longer active; meaning on the next open we will
                // run through this again
            }
            return;
        }
        final Fragment secondaryFragment =
                fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationHost host) {
            if (host.getConversation() == conversation) {
                final var suggestion =
                        ChatSuggestions.first(xmppConnectionService, conversation);
                openConversation(suggestion, null);
            }
        }
    }

    @Override
    public void onConversationsListItemUpdated() {
        final var fragment = getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ChatListFragment chatListFragment) {
            chatListFragment.refresh();
        }
    }

    @Override
    public void switchToConversation(Conversation conversation) {
        Log.d(Config.LOGTAG, "override");
        openConversation(conversation, null);
    }

    // Tatrak: the chat list's Contacts tab adds contacts from inside this activity; XmppActivity's
    // version would restart it
    @Override
    public void switchToConversationDoNotAppend(
            final Conversation conversation, final String text) {
        final Bundle extras = new Bundle();
        if (text != null) {
            extras.putString(Intent.EXTRA_TEXT, text);
        }
        extras.putBoolean(EXTRA_DO_NOT_APPEND, true);
        openConversation(conversation, extras);
    }

    // Tatrak: the chat screen (ChatFragment's PM bar) handles these extras in the current activity.
    @Override
    public void privateMsgInMuc(final Conversation conversation, final String nick) {
        final Bundle extras = new Bundle();
        if (nick != null) {
            extras.putString(EXTRA_NICK, nick);
            extras.putBoolean(EXTRA_IS_PRIVATE_MESSAGE, true);
        }
        openConversation(conversation, extras);
    }

    // Tatrak: the right pane follows the dock's effective account.
    void reconcileSecondaryAccount() {
        if (binding.secondaryFragment == null || xmppConnectionService == null) {
            return;
        }
        final var conversation = getConversationInSecondary();
        final var selected = am.cyberhayq.chat.ui.accounts.AccountFilter.selected(xmppConnectionService);
        if (conversation == null
                || !am.cyberhayq.chat.ui.accounts.AccountFilter.shows(selected, conversation.getAccount())) {
            final var suggestion = ChatSuggestions.first(xmppConnectionService, conversation);
            if (conversation != null || suggestion != null) {
                openConversation(suggestion, null);
            }
        }
    }

    @Override
    public void onConversationRead(Conversation conversation, String upToUuid) {
        if (!mActivityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation, upToUuid);
        } else {
            Log.d(Config.LOGTAG, "ignoring read callback. mActivityPaused=" + mActivityPaused);
        }
    }

    @Override
    public void onAccountUpdate() {
        this.refreshUi();
    }

    @Override
    public void onConversationUpdate() {
        this.refreshUi();
    }

    @Override
    public void onRosterUpdate() {
        this.refreshUi();
    }

    @Override
    public void OnUpdateBlocklist(OnUpdateBlocklist.Status status) {
        this.refreshUi();
    }

    @Override
    public void onShowErrorToast(int resId) {
        runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }
}
