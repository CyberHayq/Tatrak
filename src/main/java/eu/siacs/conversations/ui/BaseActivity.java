package eu.siacs.conversations.ui;

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
    private boolean elegant;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        final var appSettings = new AppSettings(this);
        this.accent = appSettings.getAccent();
        this.style = TatrakStyle.read(this);
        this.elegant = am.cyberhayq.chat.ui.theme.ElegantDark.enabled(this);
        // Tatrak neutrals always, as Compose keeps them too: dynamic colours (Android 12+, applied before
        // onCreate) then only replace the accent
        getTheme().applyStyle(R.style.ThemeOverlay_Tatrak, true);
        if (!appSettings.isDynamicColorsDesired()) {
            // Tatrak: Slate and Sea neutrals (graphite with the elegant switch, else the opt-in elegant night) go under the accent, whose
            // house overlays override them; an unset accent is Sea under Slate and Sea
            if (this.style == TatrakStyle.SLATE_SEA) {
                getTheme().applyStyle(this.elegant ? R.style.ThemeOverlay_Tatrak_SlateSea_Elegant : R.style.ThemeOverlay_Tatrak_SlateSea, true);
            } else if (this.elegant) {
                getTheme().applyStyle(R.style.ThemeOverlay_Tatrak_Elegant, true);
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
        // Tatrak: the elegant night is a theme overlay too, so a change needs a recreate like the accent
        if (!Objects.equals(this.accent, appSettings.getAccent())
                || this.style != TatrakStyle.read(this)
                || this.elegant != am.cyberhayq.chat.ui.theme.ElegantDark.enabled(this)) {
            Log.i("Recreating {} because the accent, style or elegant night has changed", getClass().getSimpleName());
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
