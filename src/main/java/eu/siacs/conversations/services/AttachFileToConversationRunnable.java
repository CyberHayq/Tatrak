package eu.siacs.conversations.services;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.TranscoderStrategies;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class AttachFileToConversationRunnable implements Runnable, TranscoderListener {

    private final XmppConnectionService mXmppConnectionService;
    private final AppSettings appSettings;
    private final Message message;
    private final Uri uri;
    private final String type;
    private final SettableFuture<Void> callbackHandler = SettableFuture.create();
    private final boolean isVideoMessage;
    private final long originalFileSize;
    private int currentProgress = -1;
    // Tatrak: the transcoder needs a seekable file, so it writes a plain temporary one here; the
    // result is encrypted into the media store and this file deleted on every outcome
    private File transcodeFile;

    AttachFileToConversationRunnable(
            final XmppConnectionService xmppConnectionService,
            final Uri uri,
            final String type,
            final Message message) {
        this.uri = uri;
        this.type = type;
        this.mXmppConnectionService = xmppConnectionService;
        this.appSettings = new AppSettings(xmppConnectionService.getApplicationContext());
        this.message = message;
        final String mimeType =
                MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type);
        final int autoAcceptFileSize =
                mXmppConnectionService.getResources().getInteger(R.integer.auto_accept_filesize);
        this.originalFileSize = FileBackend.getFileSize(mXmppConnectionService, uri);
        this.isVideoMessage =
                (mimeType != null && mimeType.startsWith("video/"))
                        && originalFileSize > autoAcceptFileSize
                        && appSettings.isCompressVideo();
    }

    boolean isVideoMessage() {
        return this.isVideoMessage;
    }

    private void processAsFile() {
        mXmppConnectionService.getFileBackend().copyFileToPrivateStorage(message, uri, type);
        mXmppConnectionService.getFileBackend().updateFileParams(message);
    }

    /** Tatrak: a transcode interrupted by a process death leaves its plain file here; emptied at start. */
    public static void deleteTranscodeLeftovers(final Context context) {
        final File[] files = transcodeDirectory(context).listFiles();
        if (files != null) {
            for (final File file : files) {
                file.delete();
            }
        }
    }

    private static File transcodeDirectory(final Context context) {
        return new File(context.getCacheDir(), "tatrak_transcode");
    }

    private void deleteTranscodeFile() {
        if (transcodeFile != null && transcodeFile.delete()) {
            Log.d(Config.LOGTAG, "deleted transcoded file " + transcodeFile.getAbsolutePath());
        }
    }

    private ListenableFuture<Void> fallbackToProcessAsFile() {
        deleteTranscodeFile();
        final var file = mXmppConnectionService.getFileBackend().getFile(message);
        if (file.exists() && file.delete()) {
            Log.d(Config.LOGTAG, "deleted preexisting file"); // Tatrak: no stored file name in the log (it carries the generation id)
        }
        return Futures.submit(this::processAsFile, XmppConnectionService.FILE_ATTACHMENT_EXECUTOR);
    }

    private void processAsVideo() throws FileNotFoundException {
        Log.d(Config.LOGTAG, "processing file as video");
        mXmppConnectionService.startOngoingVideoTranscodingForegroundNotification();
        mXmppConnectionService
                .getFileBackend()
                .setupRelativeFilePath(message, String.format("%s.%s", message.getUuid(), "mp4"));
        // Tatrak: into the plain temporary folder, not the attachment path
        final var directory = transcodeDirectory(mXmppConnectionService);
        if (directory.mkdirs()) {
            Log.d(Config.LOGTAG, "created directory " + directory.getAbsolutePath());
        }
        this.transcodeFile = new File(directory, message.getUuid() + ".mp4");

        final DefaultVideoStrategy selectedVideoTranscoderStrategy;
        final DefaultAudioStrategy derivedAudioTranscoderStrategy;
        switch (appSettings.getVideoCompression()) {
            case "360":
                selectedVideoTranscoderStrategy = TranscoderStrategies.VIDEO_360P;
                derivedAudioTranscoderStrategy = TranscoderStrategies.AUDIO_MQ;
                break;
            case "480":
                selectedVideoTranscoderStrategy = TranscoderStrategies.VIDEO_480P;
                derivedAudioTranscoderStrategy = TranscoderStrategies.AUDIO_MQ;
                break;
            case "720":
                selectedVideoTranscoderStrategy = TranscoderStrategies.VIDEO_720P;
                derivedAudioTranscoderStrategy = TranscoderStrategies.AUDIO_HQ;
                break;
            case "1080":
                selectedVideoTranscoderStrategy = TranscoderStrategies.VIDEO_1080P;
                derivedAudioTranscoderStrategy = TranscoderStrategies.AUDIO_HQ;
                break;
            default:
                selectedVideoTranscoderStrategy = TranscoderStrategies.VIDEO_480P;
                derivedAudioTranscoderStrategy = TranscoderStrategies.AUDIO_MQ;
                break;
        }

        final Future<Void> transcoderFuture;
        try {
            transcoderFuture =
                    Transcoder.into(transcodeFile.getAbsolutePath())
                            .addDataSource(mXmppConnectionService, uri)
                            .setVideoTrackStrategy(selectedVideoTranscoderStrategy)
                            .setAudioTrackStrategy(derivedAudioTranscoderStrategy)
                            .setListener(this)
                            .transcode();
        } catch (final RuntimeException e) {
            // transcode can already throw if there is an invalid file format or a platform bug
            mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
            this.callbackHandler.setFuture(fallbackToProcessAsFile());
            return;
        }
        try {
            transcoderFuture.get();
        } catch (final InterruptedException e) {
            throw new AssertionError(e);
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof Error) {
                mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
                this.callbackHandler.setFuture(fallbackToProcessAsFile());
            } else {
                Log.d(Config.LOGTAG, "ignoring execution exception. Handled by onTranscodeFiled()");
            }
        }
    }

    @Override
    public void onTranscodeProgress(double progress) {
        final int p = (int) Math.round(progress * 100);
        if (p > currentProgress) {
            currentProgress = p;
            mXmppConnectionService
                    .getNotificationService()
                    .updateFileAddingNotification(p, message);
        }
    }

    @Override
    public void onTranscodeCompleted(int successCode) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        // Tatrak: off the transcoder's callback thread: the copy into the store reads the whole file
        this.callbackHandler.setFuture(
                Futures.submit(
                        () -> storeTranscodedVideo(successCode),
                        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR));
    }

    private void storeTranscodedVideo(final int successCode) {
        try {
            // Tatrak: SUCCESS_NOT_NEEDED writes no video (the muxer leaves an empty file): the
            // original is sent, as when the converted one is not smaller
            if (successCode == Transcoder.SUCCESS_NOT_NEEDED) {
                Log.d(Config.LOGTAG, "transcoding not needed. processing as file");
                processAsFile();
                return;
            }
            final long convertedFileSize = transcodeFile.length();
            Log.d(
                    Config.LOGTAG,
                    "originalFileSize=" + originalFileSize + " convertedFileSize=" + convertedFileSize);
            if (originalFileSize != 0 && convertedFileSize >= originalFileSize) {
                Log.d(
                        Config.LOGTAG,
                        "original file size was smaller. deleting and processing as file");
                processAsFile();
                return;
            }
            // encrypted into the media store by the generic copy
            mXmppConnectionService
                    .getFileBackend()
                    .copyFileToPrivateStorage(message, Uri.fromFile(transcodeFile), "video/mp4");
            mXmppConnectionService.getFileBackend().updateFileParams(message);
        } finally {
            deleteTranscodeFile();
        }
    }

    @Override
    public void onTranscodeCanceled() {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        this.callbackHandler.setFuture(fallbackToProcessAsFile());
    }

    @Override
    public void onTranscodeFailed(@NonNull final Throwable exception) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        Log.d(Config.LOGTAG, "video transcoding failed", exception);
        this.callbackHandler.setFuture(fallbackToProcessAsFile());
    }

    @Override
    public void run() {
        final var messageUuid = FileBackend.getMessageUuid(mXmppConnectionService, uri);
        if (messageUuid.isPresent()) {
            final var m =
                    DatabaseBackend.getInstance(mXmppConnectionService)
                            .getIndividualMessage(messageUuid.get());
            final var storageLocation = m != null ? m.getRelativeFilePath() : null;
            if (storageLocation != null) {
                final var sanityCheck =
                        FileBackend.getUriForFile(mXmppConnectionService, storageLocation.file());
                if (sanityCheck.getPath() != null && sanityCheck.getPath().equals(uri.getPath())) {
                    Log.d(Config.LOGTAG, "using existing file"); // Tatrak: no stored file name in the log (it carries the generation id)
                    message.setRelativeFilePath(storageLocation);
                    mXmppConnectionService.getFileBackend().updateFileParams(message);
                    return;
                }
            }
        }
        if (this.isVideoMessage()) {
            try {
                processAsVideo();
                awaitCallbackOrFallback();
            } catch (final FileNotFoundException e) {
                processAsFile();
            }
        } else {
            processAsFile();
        }
    }

    private void awaitCallbackOrFallback() {
        Log.d(Config.LOGTAG, "awaiting callback");
        try {
            callbackHandler.get();
        } catch (final Exception e) {
            // Tatrak: a failed copy into the store fails the attach, as it does without
            // transcoding; swallowed here, the message would be sent without its file
            if (e.getCause() instanceof FileBackend.FileCopyException copy) {
                throw copy;
            }
            Log.e(Config.LOGTAG, "awaiting callback or fallback failed", e);
        }
    }
}
