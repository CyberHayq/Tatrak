package eu.siacs.conversations.http;

import static eu.siacs.conversations.http.HttpConnectionManager.EXECUTOR;

import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Longs;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.DebouncedInterfaceUpdater;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.FileWriterException;
import eu.siacs.conversations.utils.MimeUtils;
import im.conversations.android.model.TransportSecurity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherOutputStream;
import org.bouncycastle.crypto.io.InvalidCipherTextIOException;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

public class HttpDownloadConnection implements Transferable {

    private final Message message;
    private final HttpConnectionManager mHttpConnectionManager;
    private final XmppConnectionService mXmppConnectionService;
    private final DebouncedInterfaceUpdater debouncedInterfaceUpdater;
    private HttpUrl mUrl;
    private File file;
    private TransportSecurity transportSecurity;
    private Long expectedSize = null;
    private int mStatus = Transferable.STATUS_UNKNOWN;
    private boolean acceptedAutomatically = false;
    private int mProgress = 0;
    private Call mostRecentCall;

    HttpDownloadConnection(Message message, HttpConnectionManager manager) {
        this.message = message;
        this.mHttpConnectionManager = manager;
        this.mXmppConnectionService = manager.getXmppConnectionService();
        this.debouncedInterfaceUpdater = new DebouncedInterfaceUpdater(this.mXmppConnectionService);
    }

    @Override
    public boolean start() {
        if (this.mStatus == STATUS_OFFER_CHECK_FILESIZE) {
            checkFileSize(true);
        } else {
            download(true);
        }
        return true;
    }

    public void init(boolean interactive) {
        if (message.isDeleted()) {
            if (message.getType() == Message.TYPE_PRIVATE_FILE) {
                message.setType(Message.TYPE_PRIVATE);
            } else if (message.isFileOrImage()) {
                message.setType(Message.TYPE_TEXT);
            }
            message.setOob(true);
            message.setDeleted(false);
            mXmppConnectionService.updateMessage(message);
        }
        this.message.setTransferable(this);
        try {
            final Message.FileParams fileParams = message.getFileParams();
            if (message.hasFileOnRemoteHost()) {
                mUrl = AesGcmURL.of(fileParams.url);
            } else if (message.isOOb() && fileParams.url != null && fileParams.size != null) {
                mUrl = AesGcmURL.of(fileParams.url);
            } else {
                mUrl = AesGcmURL.of(message.getBody().split("\n")[0]);
            }
            final AbstractConnectionManager.Extension extension =
                    AbstractConnectionManager.Extension.of(mUrl.encodedPath());
            final String ext = extension.getExtension();
            final String filename =
                    Strings.isNullOrEmpty(ext)
                            ? message.getUuid()
                            : String.format("%s.%s", message.getUuid(), ext);
            mXmppConnectionService.getFileBackend().setupRelativeFilePath(message, filename);
            setupFile();
            if (this.message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                    && this.transportSecurity == null) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            final Long knownFileSize;
            if (message.getEncryption() == Message.ENCRYPTION_PGP
                    || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                knownFileSize = null;
            } else {
                knownFileSize = message.getFileParams().size;
            }
            if (knownFileSize != null && interactive) {
                if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                        && this.transportSecurity != null) {
                    this.expectedSize = knownFileSize + GCM_AUTHENTICATION_TAG_LENGTH;
                } else {
                    this.expectedSize = knownFileSize;
                }
                download(true);
            } else {
                checkFileSize(interactive);
            }
        } catch (final IllegalArgumentException e) {
            this.cancel();
        }
    }

    private void setupFile() {
        final String reference = mUrl.fragment();
        if (reference != null && AesGcmURL.IV_KEY.matcher(reference).matches()) {
            this.transportSecurity = TransportSecurity.ofAnchor(CryptoHelper.hexToBytes(reference));
        } else {
            this.transportSecurity = null;
        }
        this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
    }

    // Tatrak: a plain download into the media store (not OMEMO ciphertext, not an OpenPGP file)
    private boolean isStoredAttachment() {
        return transportSecurity == null
                && am.cyberhayq.chat.storage.media.EncryptedMediaStore.get().isEncrypted(file);
    }

    private void download(final boolean interactive) {
        // Tatrak: one worker per message owns its partial file, from here until the worker ended
        if (mHttpConnectionManager.claimDownloadWorker(this) != null) {
            Log.d(Config.LOGTAG, "a download of " + message.getUuid() + " is still running");
            return;
        }
        EXECUTOR.execute(new FileDownloader(interactive));
    }

    // Tatrak: a partial file is bound to the message, the transfer mode and the URL (with its key)
    private File partialFile() {
        final String mode = transportSecurity != null ? "aesgcm" : "raw";
        return new FileBackend.Cache(mXmppConnectionService)
                .partial(message.getUuid() + '\n' + mode + '\n' + mUrl);
    }

    // Tatrak: the strong ETag and the effective URL (after redirects) the partial file came from
    private static File validatorFile(final File partial) {
        return new File(partial.getPath() + ".validator");
    }

    private void checkFileSize(final boolean interactive) {
        EXECUTOR.execute(new FileSizeChecker(interactive));
    }

    @Override
    public void cancel() {
        final Call call = this.mostRecentCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        mHttpConnectionManager.finishConnection(this);
        message.setTransferable(null);
        if (message.isFileOrImage()) {
            message.setDeleted(true);
        }
        this.mXmppConnectionService.updateConversationUi();
    }

    // Tatrak: decrypted straight into the media store, not into cache/decrypted (no plaintext on disk)
    private void decryptFile(final File partial, final TransportSecurity transportSecurity)
            throws IOException {
        final var outputFile = mXmppConnectionService.getFileBackend().getFile(message);

        final var directory = outputFile.getParentFile();
        if (directory != null && directory.mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directories for the output"); // Tatrak: no stored file name in the log
        }

        final var cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(
                false,
                new AEADParameters(
                        new KeyParameter(transportSecurity.key()), 128, transportSecurity.iv()));
        // Tatrak: decrypted into the media store's writer and published only after close() checked
        // the transport tag; on a failed tag nothing is published
        final var writer =
                am.cyberhayq.chat.storage.media.EncryptedMediaStore.get().createAttachment(outputFile);
        try {
            try (final var is = new FileInputStream(partial);
                    final var outputStream =
                            new CipherOutputStream(writer.getOutputStream(), cipher)) {
                ByteStreams.copy(is, outputStream);
            }
            writer.commit();
        } catch (final InvalidCipherTextIOException e) {
            if (partial.delete()) {
                Log.d(
                        Config.LOGTAG,
                        "deleted encrypted file with invalid authentication tag "
                                + partial.getAbsolutePath());
            }
            throw e;
        } finally {
            writer.abort(); // nothing after a commit; otherwise the partial output is gone
        }

        if (partial.delete()) {
            Log.w(
                    Config.LOGTAG,
                    "deleted temporary OMEMO encrypted file " + partial.getAbsolutePath());
        }
    }

    private void finish() {
        message.setTransferable(null);
        mHttpConnectionManager.finishConnection(this);
        boolean notify = acceptedAutomatically && !message.isRead();
        if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            notify =
                    message.getConversation()
                            .getAccount()
                            .getPgpDecryptionService()
                            .decrypt(message, notify);
        }
        this.mXmppConnectionService.updateConversationUi();
        final boolean notifyAfterScan = notify;
        final var file = mXmppConnectionService.getFileBackend().getFile(message);
        mXmppConnectionService
                .getFileBackend()
                .updateMediaScanner(
                        file,
                        () -> {
                            if (notifyAfterScan) {
                                mXmppConnectionService.getNotificationService().push(message);
                            }
                        });
    }

    private void changeStatus(final int status) {
        this.mStatus = status;
        this.mXmppConnectionService.updateConversationUi();
    }

    private void showToastForException(final Exception e) {
        final Call call = mostRecentCall;
        final boolean cancelled = call != null && call.isCanceled();
        if (e == null || cancelled) {
            return;
        }
        if (e instanceof java.net.UnknownHostException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_server_not_found);
        } else if (e instanceof java.net.ConnectException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_connect);
        } else if (e instanceof SSLHandshakeException) {
            mXmppConnectionService.showErrorToastInUi(
                    R.string.download_failed_could_not_establish_tls);
        } else if (e instanceof FileWriterException) {
            mXmppConnectionService.showErrorToastInUi(
                    R.string.download_failed_could_not_write_file);
        } else if (e instanceof InvalidFileException | e instanceof InvalidCipherTextIOException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_invalid_file);
        } else {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_file_not_found);
        }
    }

    private void updateProgress(long i) {
        this.mProgress = (int) i;
        this.debouncedInterfaceUpdater.run();
    }

    @Override
    public int getStatus() {
        return this.mStatus;
    }

    @Override
    public Long getFileSize() {
        if (this.file != null) {
            return this.expectedSize;
        } else {
            return null;
        }
    }

    @Override
    public int getProgress() {
        return this.mProgress;
    }

    public Message getMessage() {
        return message;
    }

    private class FileSizeChecker implements Runnable {

        private final boolean interactive;

        FileSizeChecker(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            check();
        }

        private void retrieveFailed(@Nullable final Exception e) {
            changeStatus(STATUS_OFFER_CHECK_FILESIZE);
            if (interactive) {
                showToastForException(e);
            } else {
                HttpDownloadConnection.this.acceptedAutomatically = false;
                HttpDownloadConnection.this
                        .mXmppConnectionService
                        .getNotificationService()
                        .push(message);
            }
            cancel();
        }

        private void check() {
            long size;
            try {
                size = retrieveFileSize();
            } catch (final Exception e) {
                Log.d(Config.LOGTAG, "could not retrieve file size", e);
                retrieveFailed(e);
                return;
            }
            persistFileSize(size);
            message.setOob(true);
            mXmppConnectionService.databaseBackend.updateMessage(message, true);
            expectedSize = size;
            message.resetFileParams();
            final var autoAcceptFileSize =
                    new AppSettings(mXmppConnectionService).getAutoAcceptFileSize();
            if (Compatibility.hasStoragePermission(mXmppConnectionService)
                    && autoAcceptFileSize.isPresent()
                    && size <= autoAcceptFileSize.get()
                    && mXmppConnectionService.isDataSaverDisabled()) {
                HttpDownloadConnection.this.acceptedAutomatically = true;
                download(interactive);
            } else {
                changeStatus(STATUS_OFFER);
                HttpDownloadConnection.this.acceptedAutomatically = false;
                HttpDownloadConnection.this
                        .mXmppConnectionService
                        .getNotificationService()
                        .push(message);
            }
        }

        private long retrieveFileSize() throws IOException {
            Log.d(Config.LOGTAG, "retrieve file size. interactive:" + interactive);
            changeStatus(STATUS_CHECKING);
            final OkHttpClient client =
                    mHttpConnectionManager.buildHttpClient(
                            mUrl, message.getConversation().getAccount(), interactive);
            final Request request =
                    new Request.Builder()
                            .url(URL.stripFragment(mUrl))
                            .addHeader("Accept-Encoding", "identity")
                            .head()
                            .build();
            mostRecentCall = client.newCall(request);
            try (final Response response = mostRecentCall.execute()) {
                throwOnInvalidCode(response);
                final String contentLength = response.header("Content-Length");
                final String contentType = response.header("Content-Type");
                final AbstractConnectionManager.Extension extension =
                        AbstractConnectionManager.Extension.of(mUrl.encodedPath());
                if (Strings.isNullOrEmpty(extension.getExtension()) && contentType != null) {
                    final String fileExtension = MimeUtils.guessExtensionFromMimeType(contentType);
                    if (fileExtension != null) {
                        mXmppConnectionService
                                .getFileBackend()
                                .setupRelativeFilePath(
                                        message,
                                        String.format("%s.%s", message.getUuid(), fileExtension),
                                        contentType);
                        Log.d(
                                Config.LOGTAG,
                                "rewriting name after not finding extension in url but in content"
                                        + " type");
                        setupFile();
                    }
                }
                final Long size = Longs.tryParse(Strings.nullToEmpty(contentLength));
                if (size == null || size < 0) {
                    throw new IOException("no content-length found in HEAD response");
                }
                return size;
            }
        }
    }

    private void persistFileSize(final long size) {
        final Message.FileParams fileParams = message.getFileParams();
        if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL && transportSecurity != null) {
            // store the file size of the clear text file. If we resume the download we will add the
            // auth tag size again
            // this is equivalent to use updating file params *after* download (which would take the
            // clear text size as well)
            FileBackend.updateFileParams(
                    message, fileParams.url, size - GCM_AUTHENTICATION_TAG_LENGTH);
        } else {
            FileBackend.updateFileParams(message, fileParams.url, size);
        }
    }

    private class FileDownloader implements Runnable {

        private final boolean interactive;

        public FileDownloader(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            // Tatrak: the claim taken in download(boolean) ends only after the partial file closed
            try {
                downloadAndFinish();
            } finally {
                mHttpConnectionManager.releaseDownloadWorker(HttpDownloadConnection.this);
            }
        }

        private void downloadAndFinish() {
            try {
                changeStatus(STATUS_DOWNLOADING);
                // Tatrak: no partial file for a media store attachment (written in place)
                final var partial = isStoredAttachment() ? null : partialFile();
                download(partial);
                final var transportSecurity = HttpDownloadConnection.this.transportSecurity;
                if (transportSecurity != null) {
                    decryptFile(partial, transportSecurity);
                } else if (partial != null) {
                    FileBackend.move(partial, HttpDownloadConnection.this.file);
                }
                if (partial != null && validatorFile(partial).delete()) {
                    Log.d(Config.LOGTAG, "deleted the validator of the used partial file");
                }
                if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                    message.setEncryption(Message.ENCRYPTION_PGP);
                }
                updateImageBounds();
                finish();
            } catch (final SSLHandshakeException e) {
                changeStatus(STATUS_OFFER);
            } catch (final Exception e) {
                Log.d(
                        Config.LOGTAG,
                        message.getConversation().getAccount().getJid().asBareJid()
                                + ": unable to download file",
                        e);
                if (interactive) {
                    showToastForException(e);
                } else {
                    HttpDownloadConnection.this.acceptedAutomatically = false;
                    HttpDownloadConnection.this
                            .mXmppConnectionService
                            .getNotificationService()
                            .push(message);
                }
                cancel();
            }
        }

        private void download(@Nullable final File partial) throws Exception {
            final long expected = expectedSize;
            // Tatrak: a plain attachment of the media store is written straight into it, without
            // resume (no plaintext in the cache); one that exists is complete and kept
            final boolean stored = partial == null;
            if (stored && file.exists()) {
                Log.d(Config.LOGTAG, "attachment already downloaded");
                return;
            }
            // Tatrak: a partial file is resumed only with the validator it came with (ETag, URL);
            // one of full length is fetched again, its length alone does not prove it complete
            final File validatorFile = stored ? null : validatorFile(partial);
            final List<String> validator =
                    validatorFile != null && validatorFile.exists()
                            ? com.google.common.io.Files.asCharSource(
                                            validatorFile, java.nio.charset.StandardCharsets.UTF_8)
                                    .readLines()
                            : null;
            final var fileExists =
                    !stored && partial.exists() && validator != null && validator.size() == 2;
            final var existingFileSize = fileExists ? partial.length() : -1L;
            final OkHttpClient client =
                    mHttpConnectionManager.buildHttpClient(
                            mUrl, message.getConversation().getAccount(), interactive);

            final Request.Builder requestBuilder =
                    new Request.Builder().url(URL.stripFragment(mUrl));

            final boolean tryResume =
                    fileExists && existingFileSize > 0 && existingFileSize < expected;
            final long resumeSize;
            if (tryResume) {
                resumeSize = existingFileSize;
                Log.d(
                        Config.LOGTAG,
                        "http download trying resume after " + resumeSize + " of " + expected);
                requestBuilder.addHeader(
                        "Range", String.format(Locale.ENGLISH, "bytes=%d-", resumeSize));
                // Tatrak: the server sends the whole file instead if the object changed
                requestBuilder.addHeader("If-Range", validator.get(0));
            } else {
                resumeSize = 0;
            }
            final Request request = requestBuilder.build();
            mostRecentCall = client.newCall(request);
            try (final Response response = mostRecentCall.execute()) {
                throwOnInvalidCode(response);
                final String contentRange = response.header("Content-Range");
                // Tatrak: a 206 counts only as exactly the rest of this file, from the same object
                final boolean serverResumed = response.code() == 206;
                if (serverResumed
                        && !(tryResume
                                && String.format(
                                                Locale.ENGLISH,
                                                "bytes %d-%d/%d",
                                                resumeSize,
                                                expected - 1,
                                                expected)
                                        .equals(contentRange)
                                && validator.get(0).equals(response.header("ETag"))
                                && validator.get(1).equals(response.request().url().toString()))) {
                    if (!stored) {
                        final boolean discarded = validatorFile.delete() & partial.delete();
                        Log.d(Config.LOGTAG, "partial discarded: " + discarded);
                    }
                    throw new IOException("Unexpected partial content " + contentRange);
                }
                final var body = response.body();
                final InputStream inputStream = body.byteStream();
                if (serverResumed) {
                    Log.d(Config.LOGTAG, "server resumed");
                    final var offset = partial.length();
                    try (final OutputStream os = new FileOutputStream(partial, true)) {
                        if (copy(inputStream, os, offset, expected) != expected) {
                            throw new IOException("Response body shorter than expected size");
                        }
                    }
                } else {
                    final String contentLength = response.header("Content-Length");
                    final Long size = Longs.tryParse(Strings.nullToEmpty(contentLength));
                    if (size == null) {
                        Log.d(Config.LOGTAG, "no content-length in GET response (probably gzip)");
                    } else {
                        if (expected != size) {
                            if (expected == 0) {
                                // this means we got 0 (unknown) on HEAD. We won't download the file
                                // but we update the file size so the user can try it again now that
                                // the actual file size is known
                                persistFileSize(size);
                            }
                            throw new IOException(
                                    "Content-Length in GET response did not match expected size");
                        }
                    }
                    final var directory = stored ? null : partial.getParentFile(); // Tatrak: none in the store
                    if (directory != null && directory.mkdirs()) {
                        Log.d(Config.LOGTAG, "create directory " + directory.getAbsolutePath());
                    }
                    Log.d(Config.LOGTAG, "creating file for " + message.getUuid()); // Tatrak: no stored name in logs
                    // Tatrak: an attachment is written through the media store, without resume;
                    // the OMEMO ciphertext and an OpenPGP file go through the plain partial file
                    if (stored) {
                        final var writer =
                                am.cyberhayq.chat.storage.media.EncryptedMediaStore.get()
                                        .createAttachment(file);
                        try {
                            // a body that ends early is not published as the whole file
                            if (copy(inputStream, writer.getOutputStream(), 0, expected) != expected) {
                                throw new IOException("Response body shorter than expected size");
                            }
                            writer.commit();
                        } finally {
                            writer.abort();
                        }
                    } else {
                        if (!partial.exists() && !partial.createNewFile()) {
                            throw new FileWriterException(partial);
                        }
                        try (final OutputStream os = new FileOutputStream(partial)) {
                            // Tatrak: the validator after the truncation, so it never stands next
                            // to bytes of another response
                            final String etag = response.header("ETag");
                            if (etag == null || etag.startsWith("W/")) {
                                if (validatorFile.delete()) {
                                    Log.d(Config.LOGTAG, "no strong ETag: no resume");
                                }
                            } else {
                                com.google.common.io.Files.asCharSink(
                                                validatorFile,
                                                java.nio.charset.StandardCharsets.UTF_8)
                                        .write(etag + '\n' + response.request().url());
                            }
                            if (copy(inputStream, os, 0, expected) != expected) {
                                throw new IOException("Response body shorter than expected size");
                            }
                        }
                    }
                }
            }
        }

        private long copy(
                final InputStream inputStream,
                final OutputStream outputStream,
                final long offset,
                final long expected)
                throws IOException, FileWriterException {
            long transmitted = offset;
            int count;
            final byte[] buffer = new byte[4096];
            updateProgress(Math.round(((double) transmitted / expected) * 100));
            while ((count = inputStream.read(buffer)) != -1) {
                transmitted += count;
                try {
                    outputStream.write(buffer, 0, count);
                } catch (final IOException e) {
                    throw new FileWriterException();
                }
                if (transmitted > expected) {
                    throw new InvalidFileException(
                            String.format("File exceeds expected size of %d", expected));
                }
                updateProgress(Math.round(((double) transmitted / expected) * 100));
            }
            return transmitted;
        }

        private void updateImageBounds() {
            final boolean privateMessage = message.isPrivateMessage();
            message.setType(privateMessage ? Message.TYPE_PRIVATE_FILE : Message.TYPE_FILE);
            final String url;
            final String ref = mUrl.fragment();
            if (ref != null && AesGcmURL.IV_KEY.matcher(ref).matches()) {
                url = AesGcmURL.toAesGcmUrl(mUrl);
            } else {
                url = mUrl.toString();
            }
            mXmppConnectionService.getFileBackend().updateFileParams(message, url);
            mXmppConnectionService.updateMessage(message);
        }
    }

    private static void throwOnInvalidCode(final Response response) throws IOException {
        final int code = response.code();
        if (code < 200 || code >= 300) {
            throw new IOException(String.format(Locale.ENGLISH, "HTTP Status code was %d", code));
        }
    }

    private static class InvalidFileException extends IOException {

        private InvalidFileException(final String message) {
            super(message);
        }
    }
}
