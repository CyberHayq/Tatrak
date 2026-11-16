package eu.siacs.conversations.ui;

import static eu.siacs.conversations.ui.XmppActivity.configureActionBar;

import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import androidx.databinding.DataBindingUtil;
import de.gultsch.common.Linkify;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityAboutBinding;
import eu.siacs.conversations.ui.text.FixedURLSpan;

public class AboutActivity extends ActionBarActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ActivityAboutBinding binding =
                DataBindingUtil.setContentView(this, R.layout.activity_about);
        // Tatrak: own header (translated) and licence text instead of upstream pref_about_message
        final var text =
                new SpannableString(
                        getString(R.string.tatrak_about_header)
                                + getString(R.string.tatrak_about_legal));
        Linkify.addLinks(text);
        FixedURLSpan.fix(text);
        binding.about.setText(text);
        binding.about.setMovementMethod(LinkMovementMethod.getInstance());
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        setTitle(getString(R.string.title_activity_about_x, getString(R.string.app_name)));
    }
}
