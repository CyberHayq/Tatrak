package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import eu.siacs.conversations.utils.SignupUtils;

public class ConversationActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Tatrak: do not mistake a gated database for a new installation.
        if (am.cyberhayq.chat.storage.DatabaseStorage.showGate(this)) return;
        startActivity(SignupUtils.getRedirectionIntent(this));
        new Handler(Looper.getMainLooper()).post(this::finish);
    }
}
