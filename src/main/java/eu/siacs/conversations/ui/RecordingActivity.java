package eu.siacs.conversations.ui;

import am.cyberhayq.chat.storage.media.VoiceStream;
import am.cyberhayq.chat.ui.storage.MediaPrompts;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.databinding.DataBindingUtil;
import com.google.common.base.Stopwatch;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityRecordingBinding;
import eu.siacs.conversations.utils.TimeFrameUtils;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

public class RecordingActivity extends BaseActivity implements View.OnClickListener {

    public static String EXTRA_AUTO_SEND_RECORDING = "auto_send_recording";

    private ActivityRecordingBinding binding;

    // Tatrak: VoiceStream records into the media store (a plain file only where a pipe failed)
    private VoiceStream.Session mRecorder;
    private AlertDialog voiceWarning;
    private Stopwatch stopwatch;

    private final Handler mHandler = new Handler();
    private final Runnable mTickExecutor =
            new Runnable() {
                @Override
                public void run() {
                    tick();
                    mHandler.postDelayed(mTickExecutor, 100);
                }
            };

    private boolean autoSendRecording = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        final var appSettings = new AppSettings(this);
        this.autoSendRecording = appSettings.isAutoSendRecording();
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_recording);
        this.binding.timer.setOnClickListener(
                v -> {
                    onPauseContinue();
                });
        this.binding.cancelButton.setOnClickListener(this);
        if (this.autoSendRecording) {
            this.binding.shareButton.setText(R.string.send);
            this.binding.shareButton.setIconResource(R.drawable.ic_send_24dp);
        } else {
            this.binding.shareButton.setText(R.string.attach);
            this.binding.shareButton.setIconResource(R.drawable.ic_check_24dp);
        }
        this.binding.shareButton.setOnClickListener(this);
        this.setFinishOnTouchOutside(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void onPauseContinue() {
        final var recorder = this.mRecorder;
        final var stopwatch = this.stopwatch;
        if (recorder == null
                || stopwatch == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        if (stopwatch.isRunning()) {
            try {
                recorder.pause();
                stopwatch.stop();
            } catch (final IllegalStateException e) {
                Log.d(Config.LOGTAG, "could not pause recording", e);
            }
        } else {
            try {
                recorder.resume();
                stopwatch.start();
            } catch (final IllegalStateException e) {
                Log.d(Config.LOGTAG, "could not resume recording", e);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        record();
    }

    // Tatrak: a recording through a plain file is announced once, also right after a failed pipe
    // start; "Record" starts it, "Cancel" closes the screen
    private void record() {
        if (!MediaPrompts.voiceWarningDue(this) && startRecording()) {
            return;
        }
        if (MediaPrompts.voiceWarningDue(this)) {
            voiceWarning =
                    MediaPrompts.warnPlainVoice(
                            this,
                            this::record,
                            () -> {
                                setResult(RESULT_CANCELED);
                                finish();
                            });
        } else {
            this.binding.shareButton.setEnabled(false);
            this.binding.timer.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            // TODO reset font family. make red?
            this.binding.timer.setText(R.string.unable_to_start_recording);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mRecorder != null) {
            mHandler.removeCallbacks(mTickExecutor);
            stopRecording(false);
        }
        if (voiceWarning != null) {
            voiceWarning.dismiss(); // asked again by onStart
            voiceWarning = null;
        }
    }

    private boolean startRecording() {
        mRecorder = VoiceStream.start(this);
        if (mRecorder == null) {
            return false;
        }
        stopwatch = Stopwatch.createStarted();
        if (mRecorder.getPlainFile()) {
            this.binding.timer.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_lock_open_outline_24dp, 0, 0, 0);
            TextViewCompat.setCompoundDrawableTintList(
                    this.binding.timer, this.binding.timer.getTextColors());
            ViewCompat.setStateDescription(
                    this.binding.timer, getString(R.string.tatrak_voice_plain_mark));
        }
        mHandler.postDelayed(mTickExecutor, 100);
        return true;
    }

    protected void stopRecording(final boolean saveFile) {
        final var recorder = mRecorder;
        mRecorder = null;
        if (recorder == null) {
            return;
        }
        if (stopwatch.isRunning()) {
            stopwatch.stop();
        }
        recorder.stop(saveFile);
        if (saveFile) {
            new Thread(new Finisher(recorder, autoSendRecording, this)).start();
        }
    }

    private static class Finisher implements Runnable {

        private final VoiceStream.Session recording;
        private final boolean autoSendRecording;
        private final WeakReference<Activity> activityReference;

        private Finisher(
                final VoiceStream.Session recording,
                final boolean autoSendRecording,
                final Activity activity) {
            this.recording = recording;
            this.autoSendRecording = autoSendRecording;
            this.activityReference = new WeakReference<>(activity);
        }

        @Override
        public void run() {
            final File outputFile = recording.await();
            final Activity activity = activityReference.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(() -> finish(activity, outputFile));
        }

        private void finish(final Activity activity, final File outputFile) {
            if (outputFile == null) {
                Toast.makeText(activity, R.string.unable_to_save_recording, Toast.LENGTH_SHORT)
                        .show();
                activity.setResult(Activity.RESULT_CANCELED);
                activity.finish();
                return;
            }
            final var intent = new Intent();
            intent.setData(Uri.fromFile(outputFile));
            intent.putExtra(EXTRA_AUTO_SEND_RECORDING, autoSendRecording);
            activity.setResult(Activity.RESULT_OK, intent);
            activity.finish();
        }
    }

    private void tick() {
        this.binding.timer.setText(
                TimeFrameUtils.formatElapsedTime(stopwatch.elapsed(TimeUnit.MILLISECONDS), true));
    }

    @Override
    public void onClick(final View view) {
        if (view.getId() == R.id.cancel_button) {
            mHandler.removeCallbacks(mTickExecutor);
            stopRecording(false);
            setResult(RESULT_CANCELED);
            finish();
        } else if (view.getId() == R.id.share_button) {
            this.binding.timer.setOnClickListener(null);
            this.binding.shareButton.setEnabled(false);
            this.binding.shareButton.setText(R.string.please_wait);
            mHandler.removeCallbacks(mTickExecutor);
            mHandler.postDelayed(() -> stopRecording(true), 500);
        }
    }
}
