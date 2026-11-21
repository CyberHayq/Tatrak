package eu.siacs.conversations.ui.activity;

import android.app.Notification;
import android.os.Bundle;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivitySettingsBinding;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.fragment.settings.NotificationsSettingsFragment;
import eu.siacs.conversations.ui.fragment.settings.SettingsHomeFragment;
import eu.siacs.conversations.ui.fragment.settings.XmppPreferenceFragment;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import java.util.Collections;

public class SettingsActivity extends XmppActivity {

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {
        final var fragmentManager = getSupportFragmentManager();
        final var currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof XmppPreferenceFragment xmppPreferenceFragment) {
            xmppPreferenceFragment.onBackendConnected();
        } else if (currentFragment instanceof OnBackendConnected fragment) {
            fragment.onBackendConnected();
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActivitySettingsBinding binding =
                DataBindingUtil.setContentView(this, R.layout.activity_settings);
        setSupportActionBar(binding.materialToolbar);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        final var intent = getIntent();
        final var categories = intent == null ? Collections.emptySet() : intent.getCategories();
        final Fragment preferenceFragment;
        if (ImmutableSet.of(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES)
                .equals(categories)) {
            preferenceFragment = new NotificationsSettingsFragment();
        } else {
            // Tatrak: a page opened from the chat list's Profile tab (null: unknown, the home)
            preferenceFragment =
                    intent == null
                            ? null
                            : SettingsHomeFragment.page(
                                    intent.getIntExtra(SettingsHomeFragment.EXTRA_PAGE, 0));
        }

        final var fragmentManager = getSupportFragmentManager();
        final var currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment == null) {
            fragmentManager
                    .beginTransaction()
                    .replace(
                            R.id.fragment_container,
                            preferenceFragment == null
                                    ? new SettingsHomeFragment()
                                    : preferenceFragment)
                    .commit();
        }
        binding.materialToolbar.setNavigationOnClickListener(
                view -> {
                    if (fragmentManager.getBackStackEntryCount() == 0) {
                        finish();
                    } else {
                        fragmentManager.popBackStack();
                    }
                });
    }
}
