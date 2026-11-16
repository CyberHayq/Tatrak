package eu.siacs.conversations.ui;

import am.cyberhayq.chat.ui.theme.NightBackground;
import am.cyberhayq.chat.ui.theme.TatrakStyle;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.util.SettingsUtils;
import java.util.Objects;

public abstract class BaseActivity extends AppCompatActivity {
    private Boolean isDynamicColors;
    private String accent;
    private TatrakStyle style;
    private NightBackground nightBackground;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        final var appSettings = new AppSettings(this);
        this.accent = appSettings.getAccent();
        this.style = TatrakStyle.read(this);
        this.nightBackground = NightBackground.read(this);
        // Tatrak neutrals always, as Compose keeps them too: dynamic colours (Android 12+, applied before
        // onCreate) then only replace the accent
        getTheme().applyStyle(R.style.ThemeOverlay_Tatrak, true);
        if (!appSettings.isDynamicColorsDesired()) {
            // Tatrak: the style's neutrals under the chosen night background go under the accent; an unset
            // accent is Sea under Slate and Sea
            final int neutrals = this.nightBackground.overlay(this.style);
            if (neutrals != 0) {
                getTheme().applyStyle(neutrals, true);
            }
            getTheme().applyStyle(TatrakStyle.effectiveAccent(this.style, this.accent).getOverlay(), true);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        // Tatrak: route storage initialization/recovery before opening normal screens.
        if (am.cyberhayq.chat.storage.DatabaseStorage.showGate(this)) return;
        final var appSettings = new AppSettings(this);
        final int desiredNightMode = appSettings.getDesiredNightMode();
        if (setDesiredNightMode(desiredNightMode)) {
            return;
        }
        final boolean isDynamicColors = appSettings.isDynamicColorsDesired();
        setDynamicColors(isDynamicColors);
        // Tatrak: the night background is a theme overlay too, so a change needs a recreate like the accent
        if (!Objects.equals(this.accent, appSettings.getAccent())
                || this.style != TatrakStyle.read(this)
                || this.nightBackground != NightBackground.read(this)) {
            Log.i("Recreating {} because the accent, style or night background has changed", getClass().getSimpleName());
            recreate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SettingsUtils.applyScreenshotSetting(this);
    }

    public void setDynamicColors(final boolean isDynamicColors) {
        if (this.isDynamicColors == null) {
            this.isDynamicColors = isDynamicColors;
        } else {
            if (this.isDynamicColors != isDynamicColors) {
                Log.i(
                        "Recreating {} because dynamic color setting has changed",
                        getClass().getSimpleName());
                recreate();
            }
        }
    }

    public boolean setDesiredNightMode(final int desiredNightMode) {
        if (desiredNightMode == AppCompatDelegate.getDefaultNightMode()) {
            return false;
        }
        AppCompatDelegate.setDefaultNightMode(desiredNightMode);
        Log.i("Recreating {} because desired night mode has changed", getClass().getSimpleName());
        recreate();
        return true;
    }
}
