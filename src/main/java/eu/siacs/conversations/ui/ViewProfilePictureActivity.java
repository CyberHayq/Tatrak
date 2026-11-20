package eu.siacs.conversations.ui;

import android.os.Bundle;
import androidx.databinding.DataBindingUtil;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityViewProfilePictureBinding;
import eu.siacs.conversations.persistance.FileBackend;

public class ViewProfilePictureActivity extends ActionBarActivity {

    public static final String EXTRA_DISPLAY_NAME = "eu.siacs.conversations.extra.DISPLAY_NAME";

    private ActivityViewProfilePictureBinding binding;

    private ProfilePictureCompose compose;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_view_profile_picture);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot(), false, false);

        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        // Tatrak: the Compose screen over the hidden classic layout; see ProfilePictureCompose
        this.compose = new ProfilePictureCompose(this);
        this.compose.attach(binding.getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        final var intent = getIntent();
        if (intent == null) {
            return;
        }
        final var uri = intent.getData();
        if (uri == null) {
            return;
        }
        final var avatar = uri.getSchemeSpecificPart();
        if (avatar == null) {
            return;
        }
        final var displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME);
        final var file = FileBackend.getAvatarFile(this, avatar);
        // Tatrak: the avatar is encrypted at rest; one the store cannot open shows nothing
        final var bitmap =
                am.cyberhayq.chat.storage.media.EncryptedMediaStore.get(this).decodeBitmap(file, null);
        this.binding.imageView.setImageBitmap(bitmap);
        setTitle(displayName);
        this.compose.show(displayName, bitmap);
    }
}
