package eu.siacs.conversations.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import am.cyberhayq.chat.storage.JvmDurable;
import am.cyberhayq.chat.storage.media.EncryptedMediaStore;
import am.cyberhayq.chat.storage.media.MediaKeyTable;
import android.app.Application;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.MemorizingTrustManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tatrak: HttpDownloadConnection's worker (run here as it is) against a local HTTP server: a
 * partial file is resumed only under the identity it was fetched with, only a complete body is
 * moved or decrypted, and one worker at a time owns a message's partial file.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class HttpDownloadPartialTest {
    private static final String GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11";
    private static final byte[] KEY = filled(32, 3), IV = filled(12, 4);

    /** Writes a raw HTTP/1.1 response for a request's headers (names in lower case). */
    private interface Reply {
        void handle(Map<String, String> headers, OutputStream out) throws Exception;
    }

    private XmppConnectionService service;
    private EncryptedMediaStore store;
    private ServerSocket server;
    private ExecutorService serverThreads;
    private volatile Reply reply;
    /** "Range|If-Range" of every request the server saw. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() throws Exception {
        JvmDurable.Companion.install();
        service = Robolectric.buildService(XmppConnectionService.class).get();
        // plain HTTP only: the platform's trust manager is never asked
        final var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trust.init((java.security.KeyStore) null);
        service.setMemorizingTrustManager(new MemorizingTrustManager(service, (javax.net.ssl.X509TrustManager) trust.getTrustManagers()[0]));
        store = new EncryptedMediaStore(service.getFilesDir(), service.getCacheDir(), new EncryptedMediaStore.KeySource() {
            @Override public String generation() { return GENERATION; }
            @Override public MediaKeyTable.Keys read() { return new MediaKeyTable.Keys(GENERATION, Map.of(GENERATION, new byte[32])); }
        }, null);
        EncryptedMediaStore.install(store);
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        serverThreads = Executors.newCachedThreadPool();
        serverThreads.execute(() -> {
            while (!server.isClosed()) {
                try {
                    final Socket socket = server.accept();
                    serverThreads.execute(() -> serve(socket));
                } catch (final IOException closed) {
                    return;
                }
            }
        });
    }

    /** One request per connection; the connection closes after the reply (a cut body ends there). */
    private void serve(final Socket socket) {
        try (socket) {
            final var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            final String path = reader.readLine().split(" ")[1]; // GET /file.bin HTTP/1.1
            final var headers = new HashMap<String, String>();
            headers.put(":path", path);
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                final int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
            }
            requests.add(headers.get("range") + "|" + headers.get("if-range"));
            final OutputStream out = socket.getOutputStream();
            reply.handle(headers, out);
            out.flush();
        } catch (final Exception ignored) {
            // the client went away
        }
    }

    private static void head(final OutputStream out, final String status, final String... headers) throws IOException {
        final var text = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        for (final String header : headers) if (header != null) text.append(header).append("\r\n");
        out.write(text.append("Connection: close\r\n\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    @After
    public void tearDown() throws IOException {
        if (server != null) server.close();
        serverThreads.shutdownNow();
        EncryptedMediaStore.install(null);
        JvmDurable.Companion.uninstall();
    }

    private static byte[] filled(final int length, final int value) {
        final byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] pattern(final int length, final int seed) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; ++i) bytes[i] = (byte) (i * 31 + seed);
        return bytes;
    }

    private static byte[] seal(final byte[] plain) throws Exception {
        final var cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(true, new AEADParameters(new KeyParameter(KEY), 128, IV));
        final byte[] out = new byte[cipher.getOutputSize(plain.length)];
        cipher.doFinal(out, cipher.processBytes(plain, 0, plain.length, out, 0));
        return out;
    }

    /** A server object: honours a Range unless If-Range names another validator; sends only [cut] bytes. */
    private static Reply object(final byte[] body, final String etag, final int cut) {
        return (headers, out) -> {
            final String range = headers.get("range");
            final String ifRange = headers.get("if-range");
            final String tag = etag == null ? null : "ETag: " + etag;
            int from = 0;
            if (range != null && (ifRange == null || ifRange.equals(etag))) {
                from = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
                head(out, "206 Partial Content", tag, "Content-Length: " + (body.length - from),
                        "Content-Range: bytes " + from + "-" + (body.length - 1) + "/" + body.length);
            } else {
                head(out, "200 OK", tag, "Content-Length: " + body.length);
            }
            out.write(body, from, Math.max(0, Math.min(cut, body.length) - from));
        };
    }

    private String url(final boolean omemo) {
        final String base = "http://127.0.0.1:" + server.getLocalPort() + "/file.bin";
        return omemo ? base + "#" + BaseEncoding.base16().lowerCase().encode(IV) + BaseEncoding.base16().lowerCase().encode(KEY) : base;
    }

    private Message message(final int encryption, final String uuid) {
        final var account = new Account(Jid.of("tester@example.com"), "secret");
        final var conversation = new Conversation("friend", account, Jid.of("friend@example.com"), Conversation.MODE_SINGLE);
        final var message = new Message(conversation, "", encryption, Message.STATUS_RECEIVED);
        if (uuid != null) message.setUuid(uuid);
        message.setType(Message.TYPE_FILE);
        service.getFileBackend().setupRelativeFilePath(message, message.getUuid() + ".bin");
        return message;
    }

    /** A connection as init() leaves it, without init's own scheduling. */
    private HttpDownloadConnection connection(final Message message, final String url, final long expected) throws Exception {
        final var connection = new HttpDownloadConnection(message, service.getHttpConnectionManager());
        set(connection, "mUrl", AesGcmURL.of(url));
        final var setupFile = HttpDownloadConnection.class.getDeclaredMethod("setupFile");
        setupFile.setAccessible(true);
        setupFile.invoke(connection);
        set(connection, "expectedSize", expected);
        message.setTransferable(connection);
        return connection;
    }

    private static void set(final Object target, final String name, final Object value) throws Exception {
        final var field = HttpDownloadConnection.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** One download attempt: a new connection whose worker runs on this thread. */
    private void attempt(final Message message, final String url, final long expected) throws Exception {
        final var connection = connection(message, url, expected);
        final var type = Class.forName(HttpDownloadConnection.class.getName() + "$FileDownloader");
        final var constructor = type.getDeclaredConstructor(HttpDownloadConnection.class, boolean.class);
        constructor.setAccessible(true);
        ((Runnable) constructor.newInstance(connection, true)).run();
    }

    private byte[] stored(final Message message) throws IOException {
        try (final var in = store.inputStream(service.getFileBackend().getFile(message))) {
            return ByteStreams.toByteArray(in);
        }
    }

    @Test
    public void aResumeAsksForTheSameObjectAndCompletesIt() throws Exception {
        final byte[] plain = pattern(70000, 1), wire = seal(plain);
        final var message = message(Message.ENCRYPTION_AXOLOTL, null);
        reply = object(wire, "\"v1\"", wire.length / 2);
        attempt(message, url(true), wire.length);
        reply = object(wire, "\"v1\"", Integer.MAX_VALUE);
        attempt(message, url(true), wire.length);
        assertEquals("bytes=" + wire.length / 2 + "-|\"v1\"", requests.get(1));
        assertArrayEquals(plain, stored(message));
    }

    @Test
    public void aReplacedObjectIsFetchedFromTheStart() throws Exception {
        final byte[] first = pattern(70000, 1), second = pattern(70000, 2);
        final byte[] firstWire = seal(first), secondWire = seal(second);
        final var message = message(Message.ENCRYPTION_AXOLOTL, null);
        reply = object(firstWire, "\"v1\"", firstWire.length / 2);
        attempt(message, url(true), firstWire.length);
        // the server now holds another object of the same length at the same URL
        reply = object(secondWire, "\"v2\"", Integer.MAX_VALUE);
        attempt(message, url(true), secondWire.length);
        assertArrayEquals(second, stored(message));
    }

    @Test
    public void withoutAValidatorADownloadStartsAgain() throws Exception {
        final byte[] plain = pattern(70000, 1), wire = seal(plain);
        final var message = message(Message.ENCRYPTION_AXOLOTL, null);
        reply = object(wire, null, wire.length / 2);
        attempt(message, url(true), wire.length);
        reply = object(wire, null, Integer.MAX_VALUE);
        attempt(message, url(true), wire.length);
        assertEquals("null|null", requests.get(1));
        assertArrayEquals(plain, stored(message));
    }

    @Test
    public void aWeakETagOrADateIsNoReasonToResume() throws Exception {
        final byte[] first = pattern(1000, 1), second = pattern(1000, 2);
        final var message = message(Message.ENCRYPTION_PGP, null);
        final File target = service.getFileBackend().getFile(message, false);
        final String date = "Last-Modified: Thu, 26 Nov 2026 10:00:00 GMT", weak = "ETag: W/\"v1\"";
        reply = (headers, out) -> {
            head(out, "200 OK", weak, date, "Content-Length: 1000");
            out.write(first, 0, 500);
        };
        attempt(message, url(false), first.length);
        // replaced within the same second: same length, date and weak ETag; the server honours a Range
        reply = (headers, out) -> {
            if (headers.get("range") != null) {
                head(out, "206 Partial Content", weak, date, "Content-Length: 500", "Content-Range: bytes 500-999/1000");
                out.write(second, 500, 500);
            } else {
                head(out, "200 OK", weak, date, "Content-Length: 1000");
                out.write(second);
            }
        };
        attempt(message, url(false), second.length);
        assertEquals("null|null", requests.get(1));
        assertArrayEquals(second, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aRedirectToAnotherObjectWithTheSameETagIsNotResumed() throws Exception {
        final byte[] first = pattern(1000, 1), second = pattern(1000, 2);
        final var message = message(Message.ENCRYPTION_PGP, null);
        final File target = service.getFileBackend().getFile(message, false);
        final Reply firstObject = object(first, "\"v1\"", 500), secondObject = object(second, "\"v1\"", Integer.MAX_VALUE);
        reply = (headers, out) -> {
            if (headers.get(":path").equals("/file.bin")) head(out, "302 Found", "Location: /b", "Content-Length: 0");
            else firstObject.handle(headers, out);
        };
        attempt(message, url(false), first.length);
        // the link now leads elsewhere, to another object that happens to carry the same ETag
        reply = (headers, out) -> {
            if (headers.get(":path").equals("/file.bin")) head(out, "302 Found", "Location: /c", "Content-Length: 0");
            else secondObject.handle(headers, out);
        };
        attempt(message, url(false), second.length);
        assertFalse(target.exists());
        // the partial and its validator are gone: the next attempt fetches the whole file
        attempt(message, url(false), second.length);
        assertEquals("null|null", requests.get(requests.size() - 1));
        assertArrayEquals(second, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aRangeOfAnotherETagIsNotAppended() throws Exception {
        final byte[] first = pattern(1000, 1), second = pattern(1000, 2);
        final var message = message(Message.ENCRYPTION_PGP, null);
        final File target = service.getFileBackend().getFile(message, false);
        reply = object(first, "\"v1\"", 500);
        attempt(message, url(false), first.length);
        // a server that ignores If-Range and sends the rest of the replaced object
        reply = (headers, out) -> {
            head(out, "206 Partial Content", "ETag: \"v2\"", "Content-Length: 500", "Content-Range: bytes 500-999/1000");
            out.write(second, 500, 500);
        };
        attempt(message, url(false), second.length);
        assertEquals("bytes=500-|\"v1\"", requests.get(1));
        assertFalse(target.exists());
    }

    @Test
    public void aPartialOfFullLengthIsFetchedAgain() throws Exception {
        final byte[] first = pattern(1000, 1), second = pattern(1000, 2);
        final var message = message(Message.ENCRYPTION_PGP, null);
        final File target = service.getFileBackend().getFile(message, false);
        // every byte arrives, but the chunked body breaks off before its last chunk
        reply = (headers, out) -> {
            head(out, "200 OK", "ETag: \"v1\"", "Transfer-Encoding: chunked");
            out.write("3e8\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.write(first);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        };
        attempt(message, url(false), first.length);
        assertFalse(target.exists());
        reply = object(second, "\"v2\"", Integer.MAX_VALUE);
        attempt(message, url(false), second.length);
        assertEquals(2, requests.size());
        assertArrayEquals(second, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aShortRangeResponseIsNotMovedIntoPlace() throws Exception {
        final byte[] body = pattern(1000, 1);
        final var message = message(Message.ENCRYPTION_PGP, null);
        final File target = service.getFileBackend().getFile(message, false);
        reply = object(body, "\"v1\"", 500);
        attempt(message, url(false), body.length);
        assertFalse(target.exists());
        // a 206 whose range ends before the file does
        reply = (headers, out) -> {
            head(out, "206 Partial Content", "Content-Length: 300", "Content-Range: bytes 500-799/1000");
            out.write(body, 500, 300);
        };
        attempt(message, url(false), body.length);
        assertFalse(target.exists());
    }

    @Test
    public void aShortWholeResponseIsNotMovedIntoPlace() throws Exception {
        final byte[] body = pattern(1000, 1);
        final var message = message(Message.ENCRYPTION_PGP, null);
        final File target = service.getFileBackend().getFile(message, false);
        // no Content-Length: the body ends cleanly, with the connection, after half the file
        reply = (headers, out) -> {
            head(out, "200 OK");
            out.write(body, 0, 500);
        };
        attempt(message, url(false), body.length);
        assertFalse(target.exists());
        // the whole file is moved into place
        reply = object(body, null, Integer.MAX_VALUE);
        attempt(message, url(false), body.length);
        assertArrayEquals(body, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aRetryDoesNotRunBesideAnUnwindingWorker() throws Exception {
        final byte[] plain = pattern(70000, 1), wire = seal(plain);
        final var message = message(Message.ENCRYPTION_AXOLOTL, null);
        final var gate = new CountDownLatch(1);
        final Reply served = object(wire, "\"v1\"", Integer.MAX_VALUE);
        reply = (headers, out) -> {
            gate.await(10, TimeUnit.SECONDS);
            served.handle(headers, out);
        };
        final var download = HttpDownloadConnection.class.getDeclaredMethod("download", boolean.class);
        download.setAccessible(true);
        final var first = connection(message, url(true), wire.length);
        download.invoke(first, true);
        for (int i = 0; i < 100 && requests.isEmpty(); ++i) Thread.sleep(50);
        assertEquals(1, requests.size());
        // a retry while the first worker still holds the partial file (its connection was
        // cancelled, so it is no longer in the manager's list) makes no new connection ...
        service.getHttpConnectionManager().createNewDownloadConnection(message, true);
        assertSame(first, message.getTransferable());
        // ... and a second connection starts no worker beside the first
        download.invoke(connection(message, url(true), wire.length), true);
        Thread.sleep(500);
        assertEquals(1, requests.size());
        gate.countDown();
        for (int i = 0; i < 100 && !service.getFileBackend().getFile(message).exists(); ++i) Thread.sleep(50);
        Thread.sleep(200);
        assertArrayEquals(plain, stored(message));
    }

    @Test
    public void aRestoredMessageIdThatIsNoUuidStillDownloads() throws Exception {
        final byte[] plain = pattern(5000, 1), wire = seal(plain);
        final var clear = message(Message.ENCRYPTION_NONE, "restored-1");
        reply = object(plain, null, Integer.MAX_VALUE);
        attempt(clear, url(false), plain.length);
        assertArrayEquals(plain, stored(clear));
        // the OMEMO ciphertext goes through a partial file named after the id
        final var omemo = message(Message.ENCRYPTION_AXOLOTL, "restored-2");
        reply = object(wire, null, Integer.MAX_VALUE);
        attempt(omemo, url(true), wire.length);
        assertArrayEquals(plain, stored(omemo));
    }

    @Test
    public void aFailedMoveNamesNoPath() throws Exception {
        final File partial = new File(service.getCacheDir(), "partial-test");
        Files.write(partial.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        final File notAFolder = new File(service.getCacheDir(), "tatrak_" + GENERATION + "_folder");
        assertTrue(notAFolder.createNewFile());
        final File target = new File(notAFolder, "x.pgp");
        final var e = assertThrows(IOException.class, () -> FileBackend.move(partial, target));
        assertFalse(String.valueOf(e.getMessage()), String.valueOf(e.getMessage()).contains(GENERATION));
        assertNull(e.getCause());
    }
}
