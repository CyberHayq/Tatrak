package eu.siacs.conversations.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import eu.siacs.conversations.worker.ExportBackupWorker;
import eu.siacs.conversations.xmpp.Jid;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Tatrak: version 4 backups (Argon2id key) and still-readable version 2 backups. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class BackupFileHeaderTest {

    private static final byte[] SALT = new byte[16];

    @Test
    public void newBackupsAreVersionFour() throws IOException {
        final var out = new ByteArrayOutputStream();
        new BackupFileHeader("Tatrak", Jid.of("a@example.com"), 1L, new byte[12], SALT)
                .write(new DataOutputStream(out));
        final var read = BackupFileHeader.read(input(out.toByteArray()));
        assertEquals(BackupFileHeader.VERSION, read.getVersion());
        assertEquals(4, read.getVersion());
    }

    @Test
    public void tatrakVersionReadsAndVersionFourStillDoes() throws IOException {
        assertEquals(1001, BackupFileHeader.read(input(header(BackupFileHeader.TATRAK_VERSION))).getVersion());
        assertEquals(4, BackupFileHeader.read(input(header(4))).getVersion());
    }

    @Test
    public void conversationsVersionTwoStillReads() throws IOException {
        assertEquals(2, BackupFileHeader.read(input(header(2))).getVersion());
    }

    @Test
    public void unknownVersionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BackupFileHeader.read(input(header(3))));
        assertThrows(IllegalArgumentException.class, () -> BackupFileHeader.read(input(header(5))));
        assertThrows(
                BackupFileHeader.OutdatedBackupFileVersion.class,
                () -> BackupFileHeader.read(input(header(1))));
    }

    @Test
    public void argon2KeyIsStableAndNotTheLegacyOne() throws Exception {
        final byte[] key = ExportBackupWorker.getKey("secret", SALT);
        assertEquals(32, key.length);
        assertArrayEquals(key, ExportBackupWorker.getKey("secret", SALT));
        assertFalse(Arrays.equals(key, ExportBackupWorker.getKey("Secret", SALT)));
        final byte[] legacy = ExportBackupWorker.getLegacyKey("secret", SALT);
        assertEquals(16, legacy.length);
        assertFalse(Arrays.equals(Arrays.copyOf(key, 16), legacy));
    }

    @Test
    public void exportCipherAndImportCipherAgreeOnTheLongerKey() throws Exception {
        final byte[] key = ExportBackupWorker.getKey("secret", SALT);
        final byte[] iv = new byte[12];
        final var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        final byte[] sealed = cipher.doFinal("[{}]".getBytes());
        // as ImportBackupWorker reads it
        final var gcm =
                org.bouncycastle.crypto.modes.GCMBlockCipher.newInstance(
                        org.bouncycastle.crypto.engines.AESEngine.newInstance());
        gcm.init(
                false,
                new org.bouncycastle.crypto.params.AEADParameters(
                        new org.bouncycastle.crypto.params.KeyParameter(key), 128, iv));
        final byte[] plain = new byte[gcm.getOutputSize(sealed.length)];
        final int length = gcm.processBytes(sealed, 0, sealed.length, plain, 0);
        gcm.doFinal(plain, length);
        assertEquals("[{}]", new String(plain, 0, 4));
    }

    private static byte[] header(final int version) throws IOException {
        final var out = new ByteArrayOutputStream();
        final var data = new DataOutputStream(out);
        data.writeInt(version);
        data.writeUTF("Conversations");
        data.writeUTF("a@example.com");
        data.writeLong(1L);
        data.write(new byte[12]);
        data.write(SALT);
        return out.toByteArray();
    }

    private static DataInputStream input(final byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
