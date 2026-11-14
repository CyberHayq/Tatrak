package am.cyberhayq.chat.storage;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.UnifiedPushDatabase;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** Native checks stay in debug builds; run against backed-up emulator data only. */
public class StorageInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int checks;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; start(); }
    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private void awaitIdle() throws InterruptedException {
        final long until = android.os.SystemClock.elapsedRealtime() + 90_000;
        while (DatabaseStorage.state() == DatabaseStorage.State.STARTING
                || DatabaseStorage.state() == DatabaseStorage.State.WORKING) {
            if (android.os.SystemClock.elapsedRealtime() > until) throw new AssertionError("Storage timeout");
            Thread.sleep(50);
        }
    }
    private androidx.work.WorkInfo runImport(Context context, androidx.work.Data data) throws Exception {
        return runWork(context, data, eu.siacs.conversations.worker.ImportBackupWorker.class);
    }
    private androidx.work.WorkInfo runWork(Context context, androidx.work.Data data,
            Class<? extends androidx.work.ListenableWorker> worker) throws Exception {
        final var manager = androidx.work.WorkManager.getInstance(context);
        final var request = new androidx.work.OneTimeWorkRequest.Builder(
                worker).setInputData(data).build();
        manager.enqueue(request).getResult().get();
        final long until = android.os.SystemClock.elapsedRealtime() + 90_000;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            final var info = manager.getWorkInfoById(request.getId()).get();
            if (info != null && info.getState().isFinished()) return info;
            Thread.sleep(50);
        }
        throw new AssertionError("Import timeout");
    }
    private void checkImports(Context context) throws Exception {
        final var db = DatabaseBackend.getInstance(context).getWritableDatabase();
        final String account = java.util.UUID.randomUUID().toString();
        final String conversation = java.util.UUID.randomUUID().toString();
        final String message = java.util.UUID.randomUUID().toString();
        final String media = java.util.UUID.randomUUID().toString();
        final String local = "native-" + account;
        final String jid = local + "@example.invalid";
        final String password = "test export password";
        final File fixture = new File(context.getCacheDir(), "storage-fixture.ceb");
        try {
            for (int version : new int[] {2, 4}) {
                final byte[] iv = new byte[12], salt = new byte[16];
                new java.security.SecureRandom().nextBytes(iv);
                new java.security.SecureRandom().nextBytes(salt);
                try (var out = new java.io.FileOutputStream(fixture)) {
                    final var header = new eu.siacs.conversations.utils.BackupFileHeader(version, "monocles chat",
                            eu.siacs.conversations.xmpp.Jid.of(jid), System.currentTimeMillis(), iv, salt);
                    header.write(new java.io.DataOutputStream(out));
                    final var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                    final byte[] key = version == 4
                            ? eu.siacs.conversations.worker.ExportBackupWorker.getKey(password, salt)
                            : eu.siacs.conversations.worker.ExportBackupWorker.getLegacyKey(password, salt);
                    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                            new javax.crypto.spec.GCMParameterSpec(128, iv));
                    try (var zipped = new java.util.zip.GZIPOutputStream(new javax.crypto.CipherOutputStream(out, cipher))) {
                        final var rows = new org.json.JSONArray();
                        rows.put(row("accounts", new org.json.JSONObject().put("uuid", account).put("username", local)
                                .put("server", "example.invalid").put("password", password).put("keys", "{}")
                                .put("options", 1 << eu.siacs.conversations.entities.Account.OPTION_DISABLED)));
                        rows.put(row("conversations", new org.json.JSONObject().put("uuid", conversation).put("accountUuid", account)
                                .put("contactJid", "friend@example.invalid").put("attributes", "{\"next_encryption\":8}")
                                .put("source_only_field", 1)));
                        rows.put(row("messages", new org.json.JSONObject().put("uuid", message).put("conversationUuid", conversation)
                                .put("body", "readable historical PQ text").put("encryption", 8).put("status", 5)
                                .put("axolotl_fingerprint", "source fingerprint")));
                        // media in monocles' files format: staged encrypted, published at the commit
                        rows.put(row("files", new org.json.JSONObject().put("path", "${FILES}/media/probe.bin").put("sequence", 0)
                                .put("content", android.util.Base64.encodeToString("hel".getBytes(), android.util.Base64.NO_WRAP))));
                        rows.put(row("files", new org.json.JSONObject().put("path", "${FILES}/media/probe.bin").put("sequence", 1)
                                .put("content", android.util.Base64.encodeToString("lo".getBytes(), android.util.Base64.NO_WRAP))));
                        rows.put(row("messages", new org.json.JSONObject().put("uuid", media).put("conversationUuid", conversation)
                                .put("body", "https://example.invalid/probe.bin|5").put("type", 2).put("encryption", 0).put("status", 0)
                                .put("relativeFilePath", "${FILES}/media/probe.bin").put("sharedStorage", 0)));
                        rows.put(row("pq_only_table", new org.json.JSONObject().put("unknown", 1)));
                        zipped.write(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                final byte[] original = Files.readAllBytes(fixture.toPath());
                var preview = runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.data(
                        password, android.net.Uri.fromFile(fixture), eu.siacs.conversations.worker.ImportBackupWorker.OmemoImport.FULL));
                check(preview.getState() == androidx.work.WorkInfo.State.SUCCEEDED, "v" + version + " authenticated preview: " + preview.getOutputData());
                String stage = eu.siacs.conversations.worker.ImportBackupWorker.previewId(preview.getOutputData());
                check(stage != null, "owned staging created");
                final File[] staged = new File(context.getNoBackupFilesDir(), "tatrak-databases/" + stage + "/files").listFiles();
                check(staged != null && staged.length == 1 && staged[0].getName().startsWith("tatrak_" + activeGeneration(context) + "_")
                        && "TATRKENC".equals(new String(Files.readAllBytes(staged[0].toPath()), 0, 8, java.nio.charset.StandardCharsets.US_ASCII)),
                        "staged media encrypted for the active generation");
                final String omissions = eu.siacs.conversations.worker.ImportBackupWorker.previewDetails(context, stage);
                check(omissions.contains("PQ") && omissions.contains("source_only_field") && omissions.contains("pq_only_table"), "omissions shown before consent");
                check(net.zetetic.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM accounts WHERE uuid=?", new String[] {account}) == 0, "preview does not write active data");
                DatabaseStorage.discardImport(context, stage);
                check(net.zetetic.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM accounts WHERE uuid=?", new String[] {account}) == 0, "cancel does not write active data");
                check(Arrays.equals(original, Files.readAllBytes(fixture.toPath())), "source unchanged");
                // A bad final GCM tag must never create an acceptable preview.
                final byte[] corrupt = original.clone(); corrupt[corrupt.length - 1] ^= 1;
                Files.write(fixture.toPath(), corrupt);
                check(runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.data(password,
                        android.net.Uri.fromFile(fixture), eu.siacs.conversations.worker.ImportBackupWorker.OmemoImport.FULL)).getState() == androidx.work.WorkInfo.State.FAILED, "bad GCM tag rejected");
                Files.write(fixture.toPath(), original);
                preview = runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.data(password,
                        android.net.Uri.fromFile(fixture), eu.siacs.conversations.worker.ImportBackupWorker.OmemoImport.FULL));
                stage = eu.siacs.conversations.worker.ImportBackupWorker.previewId(preview.getOutputData());
                check(stage != null, "second preview created");
                // Commit uses the authenticated stage even when the external URI changes afterwards.
                Files.write(fixture.toPath(), new byte[] {0});
                check(runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.commitData(stage)).getState()
                        == androidx.work.WorkInfo.State.SUCCEEDED, "consented commit uses owned stage");
                check(Arrays.equals("hello".getBytes(), readAll(am.cyberhayq.chat.storage.media.EncryptedMediaStore.get(context),
                        restoredMedia(context, db, media))), "restored media opens with the own key");
                try (var cursor = db.rawQuery("SELECT body,encryption,status,axolotl_fingerprint,tatrak_source_fingerprint FROM messages WHERE uuid=?", new String[] {message})) {
                    check(cursor.moveToFirst() && "readable historical PQ text".equals(cursor.getString(0)), "PQ text retained");
                    check(cursor.getInt(1) == 8 && cursor.getInt(2) == 2 && cursor.isNull(3)
                            && "source fingerprint".equals(cursor.getString(4)), "PQ provenance isolated and pending send disabled");
                }
                check(eu.siacs.conversations.worker.ExportBackupWorker.hasImportedPq(DatabaseBackend.getInstance(context)), "legacy export detects PQ loss");
                Files.write(fixture.toPath(), original);
                final var duplicate = runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.data(password,
                        android.net.Uri.fromFile(fixture), eu.siacs.conversations.worker.ImportBackupWorker.OmemoImport.FULL));
                check(duplicate.getState() == androidx.work.WorkInfo.State.FAILED
                        && "ACCOUNT_ALREADY_EXISTS".equals(duplicate.getOutputData().getString("reason")), "same account rejected");
                if (version == 4) checkExport(context, account, jid, password, message, media);
                restoredMedia(context, db, media).delete();
                db.delete("accounts", "uuid=?", new String[] {account});
            }
        } finally {
            db.delete("accounts", "uuid=?", new String[] {account});
            fixture.delete();
            eu.siacs.conversations.Conversations.getInstance(context).resetAccounts();
        }
    }
    /** The file the restored media message points at: in the attachment folder, named for the active generation. */
    private File restoredMedia(Context context, net.zetetic.database.sqlcipher.SQLiteDatabase db, String media) {
        try (var cursor = db.rawQuery("SELECT relativeFilePath FROM messages WHERE uuid=?", new String[] {media})) {
            check(cursor.moveToFirst() && cursor.getString(0) != null, "media message restored");
            final File restored = new File(cursor.getString(0));
            check(restored.getParentFile().equals(new File(context.getFilesDir(), "attachments"))
                    && restored.getName().startsWith("tatrak_" + activeGeneration(context) + "_"), "restored media in the attachment folder: " + restored);
            return restored;
        }
    }
    private void checkExport(Context context, String account, String jid, String password, String message, String media) throws Exception {
        final var preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        final String locationKey = eu.siacs.conversations.AppSettings.BACKUP_LOCATION;
        final String formatKey = eu.siacs.conversations.AppSettings.BACKUP_FORMAT;
        final String mediaKey = eu.siacs.conversations.AppSettings.BACKUP_MEDIA;
        final String oldLocation = preferences.getString(locationKey, null);
        final boolean hadFormat = preferences.contains(formatKey);
        final String oldFormat = preferences.getString(formatKey, null);
        final boolean hadMedia = preferences.contains(mediaKey);
        final boolean oldMedia = preferences.getBoolean(mediaKey, false);
        final File mediaBefore = restoredMedia(context, DatabaseBackend.getInstance(context).getReadableDatabase(), media);
        // a one-off export (the only one with media) offers its files through FileProvider: a backup folder
        final File destination = new File(eu.siacs.conversations.persistance.FileBackend.getBackupDirectory(context), "native-export-" + account);
        check(destination.mkdirs(), "private export test directory");
        try {
            preferences.edit().putString(locationKey, android.net.Uri.fromFile(destination).toString())
                    .putString(formatKey, eu.siacs.conversations.AppSettings.BACKUP_FORMAT_CONVERSATIONS).commit();
            final var data = new androidx.work.Data.Builder().putBoolean("recurring_backup", true).build();
            check(runWork(context, data, eu.siacs.conversations.worker.ExportBackupWorker.class).getState()
                    == androidx.work.WorkInfo.State.FAILED, "lossy Conversations export refused");
            check(destination.listFiles().length == 0, "refused export writes no files");
            // a one-off export with media: the attachment decrypted, the media keys as records
            preferences.edit().putString(formatKey, eu.siacs.conversations.AppSettings.BACKUP_FORMAT_TATRAK).putBoolean(mediaKey, true).commit();
            check(runWork(context, androidx.work.Data.EMPTY, eu.siacs.conversations.worker.ExportBackupWorker.class).getState()
                    == androidx.work.WorkInfo.State.SUCCEEDED, "native encrypted export succeeds");
            File backup = null;
            for (File candidate : destination.listFiles()) {
                try (var input = new java.io.DataInputStream(new java.io.FileInputStream(candidate))) {
                    if (eu.siacs.conversations.utils.BackupFileHeader.read(input).getJid().toString().equals(jid)) backup = candidate;
                }
            }
            check(backup != null, "fixture account exported");
            final var db = DatabaseBackend.getInstance(context).getWritableDatabase();
            db.delete("accounts", "uuid=?", new String[] {account});
            final var preview = runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.data(password,
                    android.net.Uri.fromFile(backup), eu.siacs.conversations.worker.ImportBackupWorker.OmemoImport.FULL));
            check(preview.getState() == androidx.work.WorkInfo.State.SUCCEEDED, "native backup authenticated");
            final String stage = eu.siacs.conversations.worker.ImportBackupWorker.previewId(preview.getOutputData());
            check(stage != null && runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.commitData(stage)).getState()
                    == androidx.work.WorkInfo.State.SUCCEEDED, "native backup restored");
            try (var cursor = db.rawQuery("SELECT encryption,tatrak_source_fingerprint FROM messages WHERE uuid=?", new String[] {message})) {
                check(cursor.moveToFirst() && cursor.getInt(0) == 8 && "source fingerprint".equals(cursor.getString(1)),
                        "Tatrak round trip preserves PQ provenance");
            }
            final File mediaAfter = restoredMedia(context, db, media);
            check(!mediaAfter.equals(mediaBefore) && Arrays.equals("hello".getBytes(),
                    readAll(am.cyberhayq.chat.storage.media.EncryptedMediaStore.get(context), mediaAfter)), "media exported decrypted and restored as a new file");
            mediaBefore.delete();
        } finally {
            final var edit = preferences.edit();
            if (oldLocation == null) edit.remove(locationKey); else edit.putString(locationKey, oldLocation);
            if (hadFormat) edit.putString(formatKey, oldFormat); else edit.remove(formatKey);
            if (hadMedia) edit.putBoolean(mediaKey, oldMedia); else edit.remove(mediaKey);
            edit.commit();
            for (File file : destination.listFiles()) file.delete();
            destination.delete();
        }
    }
    /** The generation the active history database lives in. */
    private String activeGeneration(Context context) {
        return new File(DatabaseStorage.historyPath(context)).getParentFile().getName();
    }
    /** After an activation that created the database: its media key is bound to that generation. */
    private void checkOwnMediaKey(Context context, String label) {
        final var keys = am.cyberhayq.chat.storage.media.MediaKeyTable.read(
                DatabaseBackend.getInstance(context).getReadableDatabase());
        check(activeGeneration(context).equals(keys.getOwn()) && keys.getByGeneration().get(keys.getOwn()).length == 32, label);
    }
    /** The media format on this device's providers (Conscrypt GCM, BouncyCastle HKDF), which JVM tests do not run. */
    private void checkMediaCipher(Context context) throws Exception {
        final var cipher = am.cyberhayq.chat.storage.media.MediaCipher.INSTANCE;
        final var area = am.cyberhayq.chat.storage.media.MediaArea.ATTACHMENTS;
        final File file = new File(context.getCacheDir(), "media-cipher-check");
        try {
            // the known answer of tools/media-vectors/kat.py for an empty attachment, made without Tink or this code
            final byte[] known = com.google.common.io.BaseEncoding.base16().lowerCase().decode(
                    "544154524b454e4301f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff28404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5fa0a1a2a3a4a5a6e2a4edfed4be1b02181900ce6c41c6ee");
            final byte[] master = new byte[32], knownId = new byte[16];
            for (int i = 0; i < 32; i++) master[i] = (byte) i;
            for (int i = 0; i < 16; i++) knownId[i] = (byte) (0xf0 + i);
            Files.write(file.toPath(), known);
            try (var channel = java.nio.channels.FileChannel.open(file.toPath())) {
                check(cipher.open(master, area, "", knownId, channel).getLength() == 0, "known answer authenticated on this device");
            }
            master[0] ^= 1;
            boolean refused = false;
            try (var channel = java.nio.channels.FileChannel.open(file.toPath())) { cipher.open(master, area, "", knownId, channel); }
            catch (am.cyberhayq.chat.storage.media.MediaRejectedException expected) { refused = true; }
            check(refused, "known answer refused under another key");
            // three segments through the writer and the reader
            final byte[] key = new byte[32], id = new byte[16], plain = new byte[65455 + 65520 + 1];
            new java.security.SecureRandom().nextBytes(key);
            new java.security.SecureRandom().nextBytes(id);
            new java.security.SecureRandom().nextBytes(plain);
            try (var out = cipher.encrypt(key, id, area, "", new java.io.FileOutputStream(file))) { out.write(plain); }
            check(file.length() == cipher.ciphertextSize(plain.length), "encrypted size on this device");
            try (var channel = java.nio.channels.FileChannel.open(file.toPath())) {
                final var reader = cipher.open(key, area, "", id, channel);
                final var read = new java.io.ByteArrayOutputStream();
                for (int i = 0; i < reader.getSegments(); i++) read.write(reader.segment(i));
                check(reader.getSegments() == 3 && Arrays.equals(plain, read.toByteArray()), "three segments round trip on this device");
            }
        } finally { file.delete(); }
    }
    /** The media store on the device's real rename and fsync; leaves no file behind. */
    private void checkMediaStore(Context context) throws Exception {
        final var store = am.cyberhayq.chat.storage.media.EncryptedMediaStore.get(context);
        final var made = new java.util.ArrayList<File>();
        final File temporary = new File(context.getFilesDir(), "tatrak_tmp");
        try {
            // an attachment of three segments: published only at commit, read back across boundaries
            final byte[] data = new byte[65455 + 65520 + 1000];
            new java.security.SecureRandom().nextBytes(data);
            final File target = store.attachmentFile("probe.bin");
            check(target.getName().startsWith("tatrak_" + activeGeneration(context) + "_"), "attachment name carries the generation");
            final var writer = store.createAttachment(target);
            writer.getOutputStream().write(data);
            check(!target.exists(), "nothing published before commit");
            made.add(writer.commit());
            check(target.isFile() && target.length() == am.cyberhayq.chat.storage.media.MediaCipher.INSTANCE.ciphertextSize(data.length),
                    "attachment published with its encrypted size");
            check(temporary.list() == null || temporary.list().length == 0, "temporary folder empty after commit");
            try (var opened = store.open(target)) {
                check(opened.getLength() == data.length, "authenticated length");
                final byte[] part = new byte[2048];
                for (long position : new long[] {0, 65455 - 1000, 65455 + 65520 - 1500, data.length - 2048}) { // each crosses or ends a segment, inside the file
                    check(opened.readAt(position, part, 0, part.length) == part.length
                            && Arrays.equals(part, Arrays.copyOfRange(data, (int) position, (int) position + part.length)), "read at " + position);
                }
            }
            // an existing attachment is never overwritten, and the refused writer leaves nothing
            final var second = store.createAttachment(target);
            second.getOutputStream().write(new byte[10]);
            boolean refused = false;
            try { second.commit(); } catch (java.nio.file.FileAlreadyExistsException expected) { refused = true; }
            check(refused && store.open(target).getLength() == data.length, "existing attachment kept");
            check(temporary.list() == null || temporary.list().length == 0, "refused writer left nothing");
            // an aborted writer leaves nothing
            final var aborted = store.createAttachment(store.attachmentFile("aborted.bin"));
            aborted.getOutputStream().write(data);
            aborted.abort();
            check(temporary.list() == null || temporary.list().length == 0, "aborted writer left nothing");
            // a wallpaper replaced by its new version, inside the generation folder
            final File wallpaper = publish(store, am.cyberhayq.chat.storage.media.MediaArea.WALLPAPERS, "0123456789abcdef.jpg", new byte[] {1, 2, 3});
            made.add(wallpaper);
            check(wallpaper.getParentFile().getName().equals(activeGeneration(context)), "wallpaper in its generation folder");
            publish(store, am.cyberhayq.chat.storage.media.MediaArea.WALLPAPERS, "0123456789abcdef.jpg", new byte[] {4, 5});
            check(Arrays.equals(new byte[] {4, 5}, readAll(store, wallpaper)), "wallpaper replaced");
            // an avatar: published from files/ into cache/, which must be the same filesystem
            check(android.system.Os.stat(context.getFilesDir().getPath()).st_dev == android.system.Os.stat(context.getCacheDir().getPath()).st_dev,
                    "files and cache on one filesystem");
            final File avatar = publish(store, am.cyberhayq.chat.storage.media.MediaArea.AVATARS, "00000000000000000000000000000000probe0", new byte[] {9});
            made.add(avatar);
            check(avatar.getParentFile().equals(new File(context.getCacheDir(), "avatars")) && Arrays.equals(new byte[] {9}, readAll(store, avatar)),
                    "avatar published into the cache");
            // MediaPlayer reads through the store's data source
            final byte[] wav = wav(8000, 8000);
            final File sound = store.attachmentFile("probe.wav");
            final var soundWriter = store.createAttachment(sound);
            soundWriter.getOutputStream().write(wav);
            made.add(soundWriter.commit());
            try (var opened = store.open(sound)) {
                final var player = new android.media.MediaPlayer();
                try {
                    player.setDataSource(opened.dataSource());
                    player.prepare();
                    check(Math.abs(player.getDuration() - 1000) <= 50, "MediaPlayer duration through the data source: " + player.getDuration());
                } finally { player.release(); }
                check(!opened.getFailed(), "no tag failure while playing");
            }
            // a seekable descriptor over the encrypted file, as PdfRenderer and other apps get it
            try (var pfd = store.descriptor(target); var in = new java.io.FileInputStream(pfd.getFileDescriptor())) {
                check(pfd.getStatSize() == data.length, "proxy descriptor size");
                check(Arrays.equals(com.google.common.io.ByteStreams.toByteArray(in), data), "proxy descriptor reads the plaintext");
                final byte[] part = new byte[100];
                check(android.system.Os.lseek(pfd.getFileDescriptor(), 65455 - 10, android.system.OsConstants.SEEK_SET) == 65455 - 10
                        && android.system.Os.read(pfd.getFileDescriptor(), part, 0, 100) == 100
                        && Arrays.equals(part, Arrays.copyOfRange(data, 65455 - 10, 65455 + 90)), "proxy descriptor seeks");
            }
            // what another app gets: a URI without the generation, the plaintext, its size and a readable name
            final var resolver = context.getContentResolver();
            final android.net.Uri shared = am.cyberhayq.chat.storage.media.MediaProvider.uriFor(context, target);
            check(shared != null && !shared.toString().contains(activeGeneration(context))
                    && shared.equals(eu.siacs.conversations.persistance.FileBackend.getUriForFile(context, target)), "provider URI names no generation: " + shared);
            try (var in = resolver.openInputStream(shared)) {
                check(Arrays.equals(com.google.common.io.ByteStreams.toByteArray(in), data), "provider serves the plaintext");
            }
            try (var cursor = resolver.query(shared, null, null, null, null)) {
                check(cursor.moveToFirst() && cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)) == data.length
                        && cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)).matches("Tatrak_\\d{8}_\\d{6}\\.bin"),
                        "provider size and display name");
            }
            final android.net.Uri sharedSound = am.cyberhayq.chat.storage.media.MediaProvider.uriFor(context, sound);
            check(String.valueOf(resolver.getType(sharedSound)).startsWith("audio/"), "provider type: " + resolver.getType(sharedSound));
            // the media server reads the proxy descriptor from its own process, as another app would
            final var retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, sharedSound);
                final String duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                check(duration != null && Math.abs(Long.parseLong(duration) - 1000) <= 50, "another process reads through the provider: " + duration);
            } finally { retriever.release(); }
            boolean missing = false;
            try { resolver.openInputStream(shared.buildUpon().path("/00000000000000000000000000000000.bin").build()).close(); }
            catch (java.io.FileNotFoundException expected) { missing = true; }
            check(missing, "provider has no file for an unknown name");
            boolean readOnly = false;
            try { resolver.openFileDescriptor(shared, "w").close(); } catch (java.io.FileNotFoundException expected) { readOnly = true; }
            check(readOnly, "provider is read-only");
            // a PDF rendered through the proxy descriptor
            final var document = new android.graphics.pdf.PdfDocument();
            final var page = document.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(200, 300, 1).create());
            page.getCanvas().drawColor(0xff336699);
            document.finishPage(page);
            final var pdfBytes = new java.io.ByteArrayOutputStream();
            document.writeTo(pdfBytes);
            document.close();
            final File pdf = publishAttachment(store, "probe.pdf", pdfBytes.toByteArray());
            made.add(pdf);
            try (var renderer = new android.graphics.pdf.PdfRenderer(store.descriptor(pdf))) {
                check(renderer.getPageCount() == 1, "PdfRenderer over the proxy descriptor");
                try (var first = renderer.openPage(0)) { check(first.getWidth() == 200 && first.getHeight() == 300, "PDF page size"); }
            }
            // images: BitmapFactory through the store, and the viewer's decoder
            final var picture = android.graphics.Bitmap.createBitmap(300, 200, android.graphics.Bitmap.Config.ARGB_8888);
            picture.eraseColor(0xff336699);
            final File png = publishAttachment(store, "probe.png", png(picture));
            made.add(png);
            final var decoded = store.decodeBitmap(png, null);
            check(decoded != null && decoded.getWidth() == 300 && decoded.getHeight() == 200 && decoded.getPixel(150, 100) == 0xff336699, "BitmapFactory through the store");
            final var viewed = am.cyberhayq.chat.ui.viewer.ViewerImages.INSTANCE.decode(png, "image/png", 4096);
            check(viewed instanceof am.cyberhayq.chat.ui.viewer.ViewerImage.Still still && still.getBitmap().getWidth() == 300, "viewer decodes through the store");
            final var noise = android.graphics.Bitmap.createBitmap(500, 500, android.graphics.Bitmap.Config.ARGB_8888);
            final int[] pixels = new int[500 * 500];
            final var random = new java.util.Random(7);
            for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt() | 0xff000000;
            noise.setPixels(pixels, 0, 500, 0, 0, 500, 500);
            final File big = publishAttachment(store, "noise.png", png(noise));
            made.add(big);
            check(big.length() > 3 * 65536 && store.decodeBitmap(big, null) != null, "large image decodes");
            try (var raf = new java.io.RandomAccessFile(big, "rw")) {
                raf.seek(65536 + 100); final int b = raf.read(); raf.seek(65536 + 100); raf.write(b ^ 1);
            }
            check(store.decodeBitmap(big, null) == null, "damaged image is not shown in part");
            check(am.cyberhayq.chat.ui.viewer.ViewerImages.INSTANCE.decode(big, "image/png", 4096) == null, "viewer shows no damaged image");
            // a damaged middle segment: open passes, reading it fails every time, the damage is remembered
            try (var raf = new java.io.RandomAccessFile(target, "rw")) {
                raf.seek(65536 + 100); final int b = raf.read(); raf.seek(65536 + 100); raf.write(b ^ 1);
            }
            try (var opened = store.open(target)) {
                final byte[] part = new byte[100];
                check(opened.readAt(0, part, 0, 100) == 100, "segment before the damage still reads");
                for (int i = 0; i < 2; i++) {
                    boolean failed = false;
                    try { opened.readAt(65455 + 10, part, 0, 100); }
                    catch (am.cyberhayq.chat.storage.media.MediaRejectedException expected) {
                        failed = expected.getReason() == am.cyberhayq.chat.storage.media.MediaReject.SEGMENT_TAG;
                    }
                    check(failed, "damaged segment refused");
                }
                check(opened.getFailed(), "damage remembered");
            }
            // step 4b: a plain file in the attachment folder is unavailable and never shared, as is a
            // plain file under an encrypted name
            final File plain = new File(target.getParentFile(), "plain-probe.jpg");
            Files.write(plain.toPath(), new byte[100]);
            made.add(plain);
            boolean plainRefused = false;
            try { store.open(plain); } catch (am.cyberhayq.chat.storage.media.MediaRejectedException expected) { plainRefused = true; }
            check(store.isEncrypted(plain) && plainRefused, "plain attachment unavailable");
            check(am.cyberhayq.chat.storage.media.MediaProvider.uriFor(context, plain) == null, "plain attachment not shared");
            final File disguised = store.attachmentFile("disguised.jpg");
            Files.write(disguised.toPath(), new byte[100]);
            made.add(disguised);
            boolean unavailable = false;
            try { store.open(disguised); } catch (am.cyberhayq.chat.storage.media.MediaRejectedException expected) { unavailable = true; }
            check(unavailable, "plain file under an encrypted name unavailable");
        } finally {
            for (File file : made) file.delete();
        }
    }
    private File publish(am.cyberhayq.chat.storage.media.EncryptedMediaStore store,
            am.cyberhayq.chat.storage.media.MediaArea area, String name, byte[] data) throws Exception {
        final var writer = store.create(area, name);
        writer.getOutputStream().write(data);
        return writer.commit();
    }
    private File publishAttachment(am.cyberhayq.chat.storage.media.EncryptedMediaStore store, String filename, byte[] data) throws Exception {
        final var writer = store.createAttachment(store.attachmentFile(filename));
        writer.getOutputStream().write(data);
        return writer.commit();
    }
    private byte[] png(android.graphics.Bitmap bitmap) {
        final var out = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }
    private byte[] readAll(am.cyberhayq.chat.storage.media.EncryptedMediaStore store, File file) throws Exception {
        try (var in = store.open(file).inputStream()) { return com.google.common.io.ByteStreams.toByteArray(in); } // readAllBytes needs API 33
    }
    /** A silent mono 8-bit PCM WAV of [samples] samples at [rate] Hz. */
    private byte[] wav(int rate, int samples) {
        final var b = java.nio.ByteBuffer.allocate(44 + samples).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + samples).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate).putShort((short) 1).putShort((short) 8)
                .put("data".getBytes()).putInt(samples);
        for (int i = 0; i < samples; i++) b.put((byte) 128);
        return b.array();
    }
    private org.json.JSONObject row(String table, org.json.JSONObject values) throws org.json.JSONException {
        return new org.json.JSONObject().put("table", table).put("values", values);
    }
    private void checkLegacyCopy(Context context) throws Exception {
        final File source = new File(context.getCacheDir(), "legacy-storage-fixture.db");
        final var stage = DatabaseStorage.createGeneration(context);
        try {
            try (final var helper = DatabaseBackend.isolated(context, source.getPath(), null)) {
                final var db = helper.getWritableDatabase();
                db.execSQL("ALTER TABLE messages DROP COLUMN tatrak_source_encryption");
                db.execSQL("ALTER TABLE messages DROP COLUMN tatrak_source_fingerprint");
                db.execSQL("DROP TRIGGER after_message_delete");
                db.execSQL("CREATE TRIGGER after_message_delete AFTER DELETE ON messages BEGIN DELETE FROM messages_index WHERE rowid=OLD.rowid; END");
                db.execSQL("INSERT INTO accounts(uuid,username,server,password,keys,options) VALUES('fixture','legacy','example.invalid','fixture','{}',2)");
                db.execSQL("INSERT INTO conversations(uuid,accountUuid,contactJid) VALUES('chat','fixture','friend@example.invalid')");
                db.execSQL("INSERT INTO messages(uuid,conversationUuid,body,encryption,status) VALUES('kept','chat','preserved history',5,0)");
                db.execSQL("INSERT INTO messages(uuid,conversationUuid,body,encryption,status) VALUES('deleted','chat','deleted search entry',5,0)");
                db.execSQL("DELETE FROM messages WHERE uuid='deleted'");
                db.setVersion(57);
                try (var cursor = db.rawQuery("PRAGMA integrity_check", null)) {
                    check(cursor.moveToFirst() && !"ok".equals(cursor.getString(0)), "legacy stale FTS reproduced");
                }
            }
            final byte[] original = Files.readAllBytes(source.toPath());
            final var export = DatabaseStorage.class.getDeclaredMethod("exportPlaintext", File.class, File.class,
                    DatabaseStorage.Generation.class);
            export.setAccessible(true);
            export.invoke(DatabaseStorage.INSTANCE, source, stage.history(), stage);
            try (var helper = DatabaseBackend.isolated(context, stage.history().getPath(), stage.password())) {
                final var copy = helper.getWritableDatabase();
                check(copy.getVersion() == 59, "legacy encrypted copy upgraded");
                try (var cursor = copy.rawQuery("SELECT body FROM messages WHERE uuid='kept'", null)) {
                    check(cursor.moveToFirst() && "preserved history".equals(cursor.getString(0)), "legacy copy body retained");
                }
            }
            try (var helper = UnifiedPushDatabase.isolated(context, stage.push().getPath(), stage.password())) { helper.getWritableDatabase(); }
            DatabaseStorage.validatePair(stage);
            check(Arrays.equals(original, Files.readAllBytes(source.toPath())), "source with stale FTS kept unchanged");
        } finally {
            source.delete();
            for (String suffix : new String[] {"-journal", "-wal", "-shm"}) new File(source.getPath() + suffix).delete();
            DatabaseStorage.discardImport(context, stage.getId());
        }
    }
    private void checkRecoveryImport(Context context) throws Exception {
        check(DatabaseStorage.state() == DatabaseStorage.State.RECOVERY_REQUIRED, "recovery gate before restore");
        final File root = new File(context.getNoBackupFilesDir(), "tatrak-databases");
        final byte[] oldSelector = Files.readAllBytes(new File(root, "active").toPath());
        final String oldId = new String(oldSelector, java.nio.charset.StandardCharsets.US_ASCII).split(":")[1];
        final File original = new File(root, oldId);
        final byte[] originalHistory = Files.readAllBytes(new File(original, "history.db").toPath());
        final byte[] originalKey = Files.readAllBytes(new File(original, "key").toPath());
        final String account = java.util.UUID.randomUUID().toString();
        final String conversation = java.util.UUID.randomUUID().toString();
        final String media = java.util.UUID.randomUUID().toString();
        final String local = "recovered-" + account;
        final String password = "recovery export password";
        final File backup = new File(context.getCacheDir(), "recovery-fixture.ceb");
        final byte[] iv = new byte[12], salt = new byte[16];
        new java.security.SecureRandom().nextBytes(iv); new java.security.SecureRandom().nextBytes(salt);
        try {
            try (var output = new java.io.FileOutputStream(backup)) {
                new eu.siacs.conversations.utils.BackupFileHeader(2, "Tatrak",
                        eu.siacs.conversations.xmpp.Jid.of(local + "@example.invalid"), System.currentTimeMillis(), iv, salt)
                        .write(new java.io.DataOutputStream(output));
                final var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(
                        eu.siacs.conversations.worker.ExportBackupWorker.getLegacyKey(password, salt), "AES"),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));
                try (var zipped = new java.util.zip.GZIPOutputStream(new javax.crypto.CipherOutputStream(output, cipher))) {
                    final var rows = new org.json.JSONArray().put(row("accounts", new org.json.JSONObject()
                            .put("uuid", account).put("username", local).put("server", "example.invalid")
                            .put("password", password).put("keys", "{}")
                            .put("options", 1 << eu.siacs.conversations.entities.Account.OPTION_DISABLED)));
                    // media: encrypted for the stage, which becomes the active generation
                    rows.put(row("conversations", new org.json.JSONObject().put("uuid", conversation).put("accountUuid", account)
                            .put("contactJid", "friend@example.invalid")));
                    rows.put(row("files", new org.json.JSONObject().put("path", "${FILES}/media/probe.bin").put("sequence", 0)
                            .put("content", android.util.Base64.encodeToString("hello".getBytes(), android.util.Base64.NO_WRAP))));
                    rows.put(row("messages", new org.json.JSONObject().put("uuid", media).put("conversationUuid", conversation)
                            .put("body", "https://example.invalid/probe.bin|5").put("type", 2).put("encryption", 0).put("status", 0)
                            .put("relativeFilePath", "${FILES}/media/probe.bin").put("sharedStorage", 0)));
                    zipped.write(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            final var preview = runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.data(password,
                    android.net.Uri.fromFile(backup), eu.siacs.conversations.worker.ImportBackupWorker.OmemoImport.FULL));
            final String stage = eu.siacs.conversations.worker.ImportBackupWorker.previewId(preview.getOutputData());
            check(stage != null && DatabaseStorage.state() == DatabaseStorage.State.RECOVERY_REQUIRED, "recovery preview keeps old selection");
            check(Arrays.equals(oldSelector, Files.readAllBytes(new File(root, "active").toPath())), "preview keeps active pointer");
            check(runImport(context, eu.siacs.conversations.worker.ImportBackupWorker.commitData(stage)).getState()
                    == androidx.work.WorkInfo.State.SUCCEEDED && DatabaseStorage.isReady(), "explicit restore activates complete pair");
            check(!new File(DatabaseStorage.historyPath(context)).getParentFile().getName().equals(oldId), "fresh recovery generation");
            check(Arrays.equals(originalHistory, Files.readAllBytes(new File(original, "history.db").toPath()))
                    && Arrays.equals(originalKey, Files.readAllBytes(new File(original, "key").toPath())), "inaccessible original retained");
            final var db = DatabaseBackend.getInstance(context).getReadableDatabase();
            check(net.zetetic.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM accounts WHERE uuid=?",
                    new String[] {account}) == 1, "recovered account present");
            checkOwnMediaKey(context, "recovery import binds the stage's media key");
            // the media came with the stage: named for the new generation, it opens with the stage's own key
            check(Arrays.equals("hello".getBytes(), readAll(am.cyberhayq.chat.storage.media.EncryptedMediaStore.get(context),
                    restoredMedia(context, db, media))), "media restored by the recovery import opens");
        } finally { backup.delete(); }
    }
    @Override public void onStart() {
        final Bundle result = new Bundle();
        try {
            final Context context = getTargetContext();
            awaitIdle();
            if ("fresh".equals(arguments.getString("mode"))) {
                check(DatabaseStorage.state() == DatabaseStorage.State.RECOVERY_REQUIRED, "fresh start requires recovery");
                final File root = new File(context.getNoBackupFilesDir(), "tatrak-databases");
                final String id = new String(Files.readAllBytes(new File(root, "active").toPath()),
                        java.nio.charset.StandardCharsets.US_ASCII).split(":")[1];
                final File old = new File(root, id);
                final byte[] previous = Files.readAllBytes(new File(old, "history.db").toPath());
                DatabaseStorage.freshStart(context);
                awaitIdle();
                check(DatabaseStorage.isReady(), "explicit empty generation activated");
                check(net.zetetic.database.DatabaseUtils.queryNumEntries(DatabaseBackend.getInstance(context).getReadableDatabase(), "accounts") == 0,
                        "fresh account database empty");
                check(net.zetetic.database.DatabaseUtils.queryNumEntries(UnifiedPushDatabase.getInstance(context).getReadableDatabase(), "push") == 0,
                        "fresh push database empty");
                check(Arrays.equals(previous, Files.readAllBytes(new File(old, "history.db").toPath()))
                        && new File(old, "key").isFile(), "fresh start retains inaccessible original");
                checkOwnMediaKey(context, "fresh start binds its media key");
            } else if ("restore".equals(arguments.getString("mode"))) {
                checkRecoveryImport(context);
            } else if ("recovery".equals(arguments.getString("mode"))) {
                check(DatabaseStorage.state() == DatabaseStorage.State.RECOVERY_REQUIRED, "fail closed");
                check(new File(context.getNoBackupFilesDir(), "tatrak-databases/active").isFile(), "selector retained");
            } else {
                long accounts = -1, messages = -1;
                if (DatabaseStorage.state() == DatabaseStorage.State.MIGRATION_REQUIRED) {
                    try (var source = android.database.sqlite.SQLiteDatabase.openDatabase(
                            context.getDatabasePath("history").getPath(), null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
                        accounts = android.database.DatabaseUtils.queryNumEntries(source, "accounts");
                        messages = android.database.DatabaseUtils.queryNumEntries(source, "messages");
                    }
                    DatabaseStorage.migrate(context);
                    awaitIdle();
                }
                check(DatabaseStorage.isReady(), "encrypted storage ready: " + DatabaseStorage.state());
                final var db = DatabaseBackend.getInstance(context).getReadableDatabase();
                if ("validate".equals(arguments.getString("mode"))) {
                    try (var cursor = db.rawQuery("PRAGMA integrity_check", null)) {
                        while (cursor.moveToNext()) {
                            if (!"ok".equals(cursor.getString(0))) throw new AssertionError("Integrity: " + cursor.getString(0));
                        }
                    }
                    final var method = DatabaseStorage.class.getDeclaredMethod("current");
                    method.setAccessible(true);
                    DatabaseStorage.validatePair((DatabaseStorage.Generation) method.invoke(DatabaseStorage.INSTANCE));
                }
                check(db.getVersion() == 59, "history schema");
                if (accounts >= 0) {
                    check(net.zetetic.database.DatabaseUtils.queryNumEntries(db, "accounts") == accounts, "accounts retained");
                    check(net.zetetic.database.DatabaseUtils.queryNumEntries(db, "messages") >= messages, "history retained");
                }
                check(UnifiedPushDatabase.getInstance(context).getReadableDatabase().getVersion() == 1, "push schema");
                for (String path : new String[] {DatabaseStorage.historyPath(context), DatabaseStorage.pushPath(context)}) {
                    final File file = new File(path);
                    final byte[] header = new byte[16];
                    try (var input = new java.io.FileInputStream(file)) { check(input.read(header) == 16, "database header"); }
                    check(!new String(header, java.nio.charset.StandardCharsets.US_ASCII).startsWith("SQLite format 3"), "no plaintext header");
                }
                check(!context.getDatabasePath("history").exists(), "plaintext history cleaned");
                check(!context.getDatabasePath("unified-push-distributor").exists(), "plaintext push cleaned");
                final File directory = new File(DatabaseStorage.historyPath(context)).getParentFile();
                check(new File(directory, "key").length() == 61, "wrapped key format");
                checkMediaCipher(context);
                // Wrong-key handling is tested on a disposable generation, never the live database.
                final var stage = DatabaseStorage.createGeneration(context);
                DatabaseStorage.initializeEmpty(context, stage);
                final byte[] original = Files.readAllBytes(stage.history().toPath());
                final byte[] wrong = stage.getKey().clone(); wrong[0] ^= 1;
                boolean rejected = false;
                try (var ignored = DatabaseStorage.open(stage.history(), DatabaseStorage.rawKey(wrong))) { }
                catch (RuntimeException expected) { rejected = true; }
                check(rejected, "wrong key rejected");
                check(Arrays.equals(original, Files.readAllBytes(stage.history().toPath())), "wrong key preserves bytes");
                // The media key on real SQLCipher: made with the new database, unbound until activation.
                try (var created = DatabaseStorage.open(stage.history(), stage.password())) {
                    final var table = am.cyberhayq.chat.storage.media.MediaKeyTable.INSTANCE;
                    check(net.zetetic.database.DatabaseUtils.longForQuery(created,
                            "SELECT count(*) FROM tatrak_media_key WHERE own=1 AND generation='pending' AND length(master_key)=32", null) == 1
                            && net.zetetic.database.DatabaseUtils.queryNumEntries(created, "tatrak_media_key") == 1, "new database has one unbound media key");
                    check(table.read(created).getOwn() == null, "an unbound media key is not usable");
                    check(table.bindOwn(created, stage.getId()) && stage.getId().equals(table.read(created).getOwn()), "activation binds the media key");
                    boolean renamed = true;
                    try { table.bindOwn(created, java.util.UUID.randomUUID().toString()); } catch (IllegalStateException expected) { renamed = false; }
                    check(!renamed, "a bound media key is never renamed");
                }
                DatabaseStorage.discardImport(context, stage.getId());
                // The live database: a key bound to another generation would make its files unreadable.
                // test installs are reinstalled clean for phase 3, so the live database always has its key
                checkOwnMediaKey(context, "live media key bound to the active generation");
                if ("imports".equals(arguments.getString("mode"))) { checkImports(context); checkLegacyCopy(context); }
                if ("store".equals(arguments.getString("mode"))) checkMediaStore(context);
                if (arguments.getString("cleanup") != null) {
                    for (String id : arguments.getString("cleanup").split(",")) {
                        DatabaseStorage.discardImport(context, id);
                        final File[] media = new File(context.getFilesDir(), "attachments").listFiles((d, name) -> name.startsWith("tatrak_" + id + "_"));
                        if (media != null) for (File file : media) file.delete();
                    }
                }
            }
            result.putString("stream", "PASS " + checks + " native storage assertions\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL " + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    + " at " + (failure.getStackTrace().length == 0 ? "unknown" : failure.getStackTrace()[0]) + "\n");
            finish(0, result);
        }
    }
}
