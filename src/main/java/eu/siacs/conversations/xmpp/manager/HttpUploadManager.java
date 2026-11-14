package eu.siacs.conversations.xmpp.manager;

import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Longs;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.ExtensionFactory;
import im.conversations.android.xmpp.model.disco.info.InfoQuery;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.upload.Request;
import im.conversations.android.xmpp.model.upload.purpose.Permanent;
import im.conversations.android.xmpp.model.upload.purpose.Purpose;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class HttpUploadManager extends AbstractManager {

    /** Tatrak convention: an upload component named stickers.&lt;domain&gt; holds sticker packs. */
    public static final String STICKER_SERVICE_PREFIX = "stickers.";

    private final XmppConnectionService service;

    public HttpUploadManager(final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
    }

    public ListenableFuture<Slot> request(final File file, final String mime, final long size) {
        return request(file.getName(), mime, size, null);
    }

    public ListenableFuture<Slot> request(
            final String filename,
            final String mime,
            final long size,
            @Nullable final Purpose purpose) {
        final var result = findUploadService(false);
        if (result == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("No HTTP upload host found"));
        }
        return requestHttpUpload(result.getKey(), filename, mime, size, purpose);
    }

    /**
     * Upload to the server's sticker component ({@link #STICKER_SERVICE_PREFIX}), which keeps files
     * without expiry; falls back to the regular service when the server has none.
     */
    public ListenableFuture<HttpUrl> uploadSticker(final File file, final String mime) {
        // Tatrak: a sticker file is encrypted at rest: its plaintext length and bytes are uploaded
        final long size = am.cyberhayq.chat.storage.media.EncryptedMediaStore.get().length(file);
        final var service = findUploadService(true);
        final var slotFuture =
                service == null
                        ? request(file.getName(), mime, size, new Permanent())
                        : requestHttpUpload(
                                service.getKey(), file.getName(), mime, size, new Permanent());
        return Futures.transformAsync(
                slotFuture,
                slot -> upload(storedBody(file, mime, size), slot),
                MoreExecutors.directExecutor());
    }

    /** Tatrak: [file]'s plaintext, read through the media store whenever OkHttp sends the body. */
    static RequestBody storedBody(final File file, final String mime, final long size) {
        return new RequestBody() {
            @Override
            public long contentLength() {
                return size;
            }

            @Override
            public MediaType contentType() {
                return MediaType.parse(mime);
            }

            @Override
            public void writeTo(@NonNull final BufferedSink sink) throws IOException {
                try (final Source source =
                        Okio.source(
                                am.cyberhayq.chat.storage.media.EncryptedMediaStore.get()
                                        .inputStream(file))) {
                    sink.writeAll(source);
                } catch (final java.io.FileNotFoundException e) {
                    // its message is the path, which names the store's generation: kept out of logs
                    throw new java.io.FileNotFoundException("the stored file is gone");
                }
            }
        };
    }

    /** True when uploads land on a sticker component, i.e. links don't expire with the server's cleanup. */
    public boolean hasStickerService() {
        final var service = findUploadService(true);
        return service != null && isStickerService(service.getKey());
    }

    /**
     * The regular upload service never is the sticker component, which only takes stickers and
     * would otherwise keep every file forever; with {@code stickers} the sticker component is
     * preferred.
     */
    private Map.Entry<Jid, InfoQuery> findUploadService(final boolean stickers) {
        final var items =
                getManager(DiscoManager.class).findDiscoItemsByFeature(Namespace.HTTP_UPLOAD);
        Map.Entry<Jid, InfoQuery> regular = null;
        for (final var item : items.entrySet()) {
            if (isStickerService(item.getKey())) {
                if (stickers) {
                    return item;
                }
            } else if (regular == null) {
                regular = item;
            }
        }
        return regular;
    }

    private static boolean isStickerService(final Jid address) {
        return address.getDomain().toString().startsWith(STICKER_SERVICE_PREFIX);
    }

    public ListenableFuture<HttpUrl> upload(
            final File file, final String mime, final Purpose purpose) {
        final var filename = file.getName();
        // Tatrak: an avatar is encrypted at rest: its plaintext length and bytes are uploaded
        final var size = am.cyberhayq.chat.storage.media.EncryptedMediaStore.get().length(file);
        final var slotFuture = request(filename, mime, size, purpose);
        return Futures.transformAsync(
                slotFuture,
                slot -> upload(storedBody(file, mime, size), slot),
                MoreExecutors.directExecutor());
    }

    // Tatrak: takes the body, so a sticker or an avatar can send its plaintext (see storedBody)
    private ListenableFuture<HttpUrl> upload(final RequestBody body, final Slot slot) {
        final SettableFuture<HttpUrl> future = SettableFuture.create();
        final OkHttpClient client =
                service.getHttpConnectionManager()
                        .buildHttpClient(slot.put, getAccount(), 0, false);
        final okhttp3.Request request =
                new okhttp3.Request.Builder().url(slot.put).put(body).headers(slot.headers).build();
        client.newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                future.setException(e);
                            }

                            @Override
                            public void onResponse(@NonNull Call call, @NonNull Response response) {
                                if (response.isSuccessful()) {
                                    future.set(slot.get);
                                } else {
                                    future.setException(
                                            new IllegalStateException(
                                                    String.format(
                                                            "Response code was %s",
                                                            response.code())));
                                }
                            }
                        });
        return future;
    }

    private ListenableFuture<Slot> requestHttpUpload(
            final Jid host,
            final String filename,
            final String mime,
            final long size,
            @Nullable final Purpose purpose) {
        final Iq iq = new Iq(Iq.Type.GET);
        iq.setTo(host);
        final var request = iq.addExtension(new Request());
        request.setFilename(convertFilename(filename));
        request.setSize(size);
        request.setContentType(mime);
        if (purpose != null) {
            request.addExtension(purpose);
        }
        Log.d(Config.LOGTAG, "-->" + iq);
        final var iqFuture = this.connection.sendIqPacket(iq);
        return Futures.transform(
                iqFuture,
                response -> {
                    final var slot =
                            response.getExtension(
                                    im.conversations.android.xmpp.model.upload.Slot.class);
                    if (slot == null) {
                        throw new IllegalStateException("Slot not found in IQ response");
                    }
                    final var getUrl = slot.getGetUrl();
                    final var put = slot.getPut();
                    if (getUrl == null || put == null) {
                        throw new IllegalStateException("Missing get or put in slot response");
                    }
                    final var putUrl = put.getUrl();
                    if (putUrl == null) {
                        throw new IllegalStateException("Missing put url");
                    }
                    final var contentType = mime == null ? "application/octet-stream" : mime;
                    final var headers =
                            new ImmutableMap.Builder<String, String>()
                                    .putAll(put.getHeadersAllowList())
                                    .put("Content-Type", contentType)
                                    .buildKeepingLast();
                    return new Slot(putUrl, getUrl, headers);
                },
                MoreExecutors.directExecutor());
    }

    public Service getService() {
        if (Config.ENABLE_HTTP_UPLOAD) {
            final var entry = findUploadService(false);
            return entry == null ? null : new Service(entry);
        }
        return null;
    }

    private static String convertFilename(final String name) {
        int pos = name.indexOf('.');
        if (pos < 0) {
            return name;
        }
        try {
            UUID uuid = UUID.fromString(name.substring(0, pos));
            ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
            bb.putLong(uuid.getMostSignificantBits());
            bb.putLong(uuid.getLeastSignificantBits());
            return Base64.encodeToString(
                            bb.array(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP)
                    + name.substring(pos);
        } catch (final Exception e) {
            return name;
        }
    }

    public boolean isAvailableForSize(final long size) {
        final var result = getManager(HttpUploadManager.class).getService();
        if (result == null) {
            return false;
        }
        final Long maxSize = result.getMaxFileSize();
        if (maxSize == null) {
            return true;
        }
        if (size <= maxSize) {
            return true;
        } else {
            Log.d(
                    Config.LOGTAG,
                    getAccount().getJid().asBareJid()
                            + ": http upload is not available for files with"
                            + " size "
                            + size
                            + " (max is "
                            + maxSize
                            + ")");
            return false;
        }
    }

    public static final class Service {
        private final Map.Entry<Jid, InfoQuery> addressInfoQuery;

        public Service(final Map.Entry<Jid, InfoQuery> addressInfoQuery) {
            this.addressInfoQuery = addressInfoQuery;
        }

        public Jid getAddress() {
            return this.addressInfoQuery.getKey();
        }

        public InfoQuery getInfoQuery() {
            return this.addressInfoQuery.getValue();
        }

        public boolean supportsPurpose(final Class<? extends Purpose> purpose) {
            final var id = ExtensionFactory.id(purpose);
            if (id == null) {
                throw new IllegalStateException("Purpose has not been annotated as @XmlElement");
            }
            final var feature = String.format("%s#%s", id.namespace(), id.name());
            return getInfoQuery().hasFeature(feature);
        }

        public Long getMaxFileSize() {
            final var value =
                    getInfoQuery()
                            .getServiceDiscoveryExtension(Namespace.HTTP_UPLOAD, "max-file-size");
            return value == null ? null : Longs.tryParse(value);
        }
    }

    public static class Slot {
        public final HttpUrl put;
        public final HttpUrl get;
        public final Headers headers;

        private Slot(final HttpUrl put, final HttpUrl get, final Headers headers) {
            this.put = put;
            this.get = get;
            this.headers = headers;
        }

        private Slot(final HttpUrl put, final HttpUrl get, final Map<String, String> headers) {
            this(put, get, Headers.of(headers));
        }

        @Override
        @NonNull
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("put", put)
                    .add("get", get)
                    .add("headers", headers)
                    .toString();
        }
    }
}
