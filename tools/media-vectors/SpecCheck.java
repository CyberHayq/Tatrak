import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Preliminary whole-buffer probe of format-spec.md against real Tink Java, using only javax.crypto
 * for "our" side. Not the app's test suite: the AD helper feeds both sides, so the AD encoding is
 * checked only against the spec's worked hex examples.
 */
public class SpecCheck {
  static final int C = 65536, T = 16, F = 25, H = 40, D = F + H, P = C - T, P0 = P - D;
  static final long MAX_L = 1L << 34;
  static final SecureRandom R = new SecureRandom();
  static int failures = 0, total = 0;

  static void check(boolean ok, String what) {
    total++; if (!ok) { failures++; System.out.println("FAIL " + what); }
  }

  /** True if the call throws a rejection (IOException or a crypto failure); any other exception propagates and fails the run. */
  interface Call { void run() throws Exception; }
  static boolean rejects(Call c) throws Exception { return !reason(c).equals("accepted"); }
  static String reason(Call c) throws Exception {
    try { c.run(); return "accepted"; }
    catch (IOException | GeneralSecurityException e) { return String.valueOf(e.getMessage()); }
  }

  static boolean validName(String store, String name) {
    if (store.equals("attachments")) return name.isEmpty();
    return name.length() <= 1024 && name.matches("[0-9a-z._-]+(/[0-9a-z._-]+)*")
        && Arrays.stream(name.split("/")).noneMatch(p -> p.equals(".") || p.equals(".."));
  }

  static byte[] ad(byte[] fileId, String store, String name) throws IOException {
    if (!validName(store, name)) throw new IOException("name");
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] dom = "tatrak-media".getBytes(StandardCharsets.US_ASCII);
    o.write(dom.length); o.write(dom); o.write(1); o.write(fileId);
    byte[] st = store.getBytes(StandardCharsets.US_ASCII); o.write(st.length); o.write(st);
    byte[] n = name.getBytes(StandardCharsets.US_ASCII); o.write(n.length >> 8); o.write(n.length & 255); o.write(n);
    return o.toByteArray();
  }

  static byte[] prefix(byte[] fileId) {
    ByteBuffer b = ByteBuffer.allocate(F);
    b.put("TATRKENC".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put(fileId);
    return b.array();
  }

  static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(salt, "HmacSHA256"));
    byte[] prk = m.doFinal(ikm);
    m.init(new SecretKeySpec(prk, "HmacSHA256"));
    m.update(info); m.update((byte) 1);
    return m.doFinal(); // L = 32 = one HMAC-SHA256 block: T(1) only
  }

  static GCMParameterSpec nonce(byte[] np, long i, boolean last) {
    ByteBuffer n = ByteBuffer.allocate(12); n.put(np).putInt((int) i).put((byte) (last ? 1 : 0));
    return new GCMParameterSpec(128, n.array());
  }

  static long expectedSize(long L) {
    long n = L <= P0 ? 1 : 1 + (L - P0 + P - 1) / P;
    return D + L + (long) T * n;
  }

  /** Reader size arithmetic of the spec: {n, rem, lastStart, lastLength, L}. */
  static long[] readerSizes(long S) {
    long full = S / C, rem = S % C, n = rem == 0 ? full : full + 1;
    long lastStart = n == 1 ? D : (n - 1) * C;
    return new long[] {n, rem, lastStart, S - lastStart, S - D - (long) T * n};
  }

  /** Our encryption per the spec, independent of Tink. */
  static byte[] ourEncrypt(byte[] mk, byte[] fileId, byte[] ad, byte[] pt) throws Exception {
    byte[] salt = new byte[32], np = new byte[7]; R.nextBytes(salt); R.nextBytes(np);
    SecretKeySpec k = new SecretKeySpec(hkdf(mk, salt, ad), "AES");
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    o.write(prefix(fileId)); o.write(H); o.write(salt); o.write(np);
    int pos = 0; long i = 0; int cap = P0;
    while (true) {
      int len = Math.min(cap, pt.length - pos);
      boolean last = pt.length - pos <= cap; // writer rule 3: not last only if more than fits
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, k, nonce(np, i, last));
      o.write(c.doFinal(pt, pos, len));
      pos += len; i++; cap = P;
      if (last) break;
    }
    return o.toByteArray();
  }

  static byte[] decryptSegment(SecretKeySpec k, byte[] np, byte[] f, long i, long n) throws Exception {
    long start = i == 0 ? D : i * C, end = Math.min((i + 1) * C, f.length);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, k, nonce(np, i, i == n - 1));
    return c.doFinal(f, (int) start, (int) (end - start));
  }

  /**
   * Our reader per the spec (rules 0-5 at open, then all segments in order), independent of Tink.
   * Takes the expected store and name; the AD's fileId is read from the header, and for
   * attachments the expected fileId (from the file name) must equal it.
   */
  static byte[] ourDecrypt(byte[] mk, String store, String name, byte[] expectedFileId, byte[] f) throws Exception {
    long S = f.length;
    if (S < D + T) throw new IOException("too short");
    if (!Arrays.equals(Arrays.copyOfRange(f, 0, 8), "TATRKENC".getBytes(StandardCharsets.US_ASCII))) throw new IOException("magic");
    if (f[8] != 1) throw new IOException("version");
    byte[] fileId = Arrays.copyOfRange(f, 9, 25);
    if (store.equals("attachments") && !Arrays.equals(fileId, expectedFileId)) throw new IOException("fileId");
    if ((f[25] & 255) != H) throw new IOException("hlen");
    long[] s = readerSizes(S); long n = s[0], rem = s[1], L = s[4];
    if (n > 1 && rem >= 1 && rem <= T) throw new IOException("bad last segment size");
    if (L > MAX_L) throw new IOException("too long");
    byte[] salt = Arrays.copyOfRange(f, 26, 58), np = Arrays.copyOfRange(f, 58, 65);
    SecretKeySpec k = new SecretKeySpec(hkdf(mk, salt, ad(fileId, store, name)), "AES");
    byte[] last; // rule 5: verify the last segment at open, before any other segment
    try { last = decryptSegment(k, np, f, n - 1, n); } catch (GeneralSecurityException e) { throw new IOException("open: last segment", e); }
    check(s[3] == (n == 1 ? S - D : S - (n - 1) * C), "last segment length S=" + S);
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    for (long i = 0; i < n - 1; i++) o.write(decryptSegment(k, np, f, i, n));
    o.write(last);
    byte[] pt = o.toByteArray();
    if (pt.length != L) throw new IOException("length");
    return pt;
  }

  static byte[] tinkEncrypt(AesGcmHkdfStreaming t, byte[] fileId, byte[] ad, byte[] pt) throws Exception {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    o.write(prefix(fileId));
    try (OutputStream e = t.newEncryptingStream(o, ad)) {
      // write in odd chunks to exercise the buffering rule
      int pos = 0; while (pos < pt.length) { int n = Math.min(7777, pt.length - pos); e.write(pt, pos, n); pos += n; }
    }
    return o.toByteArray();
  }

  static SeekableByteChannel tinkChannel(AesGcmHkdfStreaming t, byte[] ad, byte[] f) throws Exception {
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("v", ".bin"); java.nio.file.Files.write(tmp, f); tmp.toFile().deleteOnExit();
    return t.newSeekableDecryptingChannel(java.nio.file.Files.newByteChannel(tmp), ad);
  }

  /** Reads until Tink reports authenticated EOF (-1); size() is unverified, so it is not used. */
  static byte[] tinkDecrypt(AesGcmHkdfStreaming t, byte[] ad, byte[] f) throws Exception {
    try (SeekableByteChannel d = tinkChannel(t, ad, f)) {
      ByteArrayOutputStream o = new ByteArrayOutputStream(); ByteBuffer b = ByteBuffer.allocate(4096);
      while (d.read(b) >= 0) { o.write(b.array(), 0, b.position()); b.clear(); }
      return o.toByteArray();
    }
  }

  static byte[] tinkReadAt(AesGcmHkdfStreaming t, byte[] ad, byte[] f, long pos, int len) throws Exception {
    try (SeekableByteChannel d = tinkChannel(t, ad, f)) {
      d.position(pos); ByteBuffer b = ByteBuffer.allocate(len);
      while (b.hasRemaining() && d.read(b) >= 0) {}
      return Arrays.copyOf(b.array(), b.position());
    }
  }

  static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

  public static void main(String[] a) throws Exception {
    byte[] mk = new byte[32]; R.nextBytes(mk);
    AesGcmHkdfStreaming t = new AesGcmHkdfStreaming(mk, "HmacSha256", 32, C, F);
    check(t.getHeaderLength() == H, "header length " + t.getHeaderLength());
    check(t.getCiphertextOffset() == D, "ciphertext offset " + t.getCiphertextOffset());
    check(t.getPlaintextSegmentSize() == P, "pt segment size");

    // AD against the spec's worked examples
    byte[] fid0 = new byte[16]; for (int i = 0; i < 16; i++) fid0[i] = (byte) i;
    String pre = "0c7461747261 6b2d6d65646961 01 000102030405060708090a0b0c0d0e0f".replace(" ", "");
    check(hex(ad(fid0, "attachments", "")).equals(pre + "0b6174746163686d656e74730000"), "AD example attachments");
    check(hex(ad(fid0, "stickers", "pack-ab12/st.webp")).equals(pre + "08737469636b6572730011" + "7061636b2d616231322f73742e77656270"), "AD example stickers");
    // name alphabet (owner, 2026-11-10)
    for (String bad : new String[] {"ünï/кот", "A.jpg", "a//b", "/a", "a/", "../a", "a/./b", "", "a".repeat(1025)})
      check(rejects(() -> ad(fid0, "stickers", bad)), "name rejected: " + (bad.length() > 20 ? bad.length() + " chars" : bad));
    check(rejects(() -> ad(fid0, "attachments", "x")), "attachments name must be empty");
    check(!rejects(() -> ad(fid0, "stickers", "a".repeat(1024))), "1024-byte name accepted");
    // a name of 256 bytes or more exercises the high byte of the u16be length (review 2, R2-5)
    check(hex(ad(fid0, "stickers", "a".repeat(256))).equals(pre + "08737469636b657273" + "0100" + "61".repeat(256)), "AD 256-byte name length 01 00");

    // size arithmetic as pure functions, including the reader's last segment (review 1, M1)
    long[][] table = {{0, 81}, {1, 82}, {P0 - 1, 65535}, {P0, 65536}, {P0 + 1, 65553}, {P0 + P, 131072}, {P0 + P + 1, 131089}, {MAX_L, 17_184_064_593L}};
    for (long[] r : table) {
      check(expectedSize(r[0]) == r[1], "size L=" + r[0]);
      check(readerSizes(r[1])[4] == r[0], "reader L from S=" + r[1]);
    }
    check(readerSizes(81)[3] == 16 && readerSizes(65536)[3] == C - D && readerSizes(65553)[3] == 17, "reader last segment lengths");
    check(readerSizes(expectedSize(MAX_L + 1))[4] > MAX_L, "2^34 + 1 is detectable from S");

    int[] lens = {0, 1, P0 - 1, P0, P0 + 1, P0 + P - 1, P0 + P, P0 + P + 1, 1_000_000};
    String[][] places = {{"attachments", ""}, {"stickers", "pack-ab12cd34ef567890/1a2b3c4d5e6f7081.webp"},
        {"avatars", "3a7bd3e2360a3d29eea436fcfb7e44c735d117c4"}, {"wallpapers", "9f86d081884c7d65.jpg"}};
    for (String[] pl : places) for (int L : lens) {
      byte[] fid = new byte[16]; R.nextBytes(fid);
      byte[] exp = pl[0].equals("attachments") ? fid : null; // named stores take fileId from the header
      byte[] pt = new byte[L]; R.nextBytes(pt);
      byte[] ad = ad(fid, pl[0], pl[1]);
      String tag = pl[0] + " L=" + L;
      byte[] tf = tinkEncrypt(t, fid, ad, pt);
      check(tf.length == expectedSize(L), tag + " tink size " + tf.length + " != " + expectedSize(L));
      check((tf[25] & 255) == H, tag + " hlen byte");
      check(Arrays.equals(ourDecrypt(mk, pl[0], pl[1], exp, tf), pt), tag + " ours decrypts tink");
      byte[] of = ourEncrypt(mk, fid, ad, pt);
      check(of.length == tf.length, tag + " our size == tink size");
      check(Arrays.equals(tinkDecrypt(t, ad, of), pt), tag + " tink decrypts ours");
      // each AD field separately: named stores change only the store (same name); attachments
      // must change the name too, since only attachments have an empty name
      boolean named = !pl[0].equals("attachments");
      String other = pl[0].equals("wallpapers") ? "avatars" : "wallpapers";
      String otherName = named ? pl[1] : "00.jpg";
      byte[] fid2 = fid.clone(); fid2[0] ^= 1;
      check(rejects(() -> ourDecrypt(mk, other, otherName, exp, tf)), tag + " wrong store rejected");
      if (named) check(rejects(() -> ourDecrypt(mk, pl[0], pl[1] + "x", exp, tf)), tag + " wrong name rejected");
      // header fileId changed (for attachments: to match the expected one, so rule 2 passes);
      // the AD built from it differs, so rule 5 must fail
      byte[] hf = tf.clone(); hf[9] ^= 1;
      check(reason(() -> ourDecrypt(mk, pl[0], pl[1], named ? null : Arrays.copyOfRange(hf, 9, 25), hf)).equals("open: last segment"), tag + " changed header fileId rejected by rule 5");
      if (!named) check(reason(() -> ourDecrypt(mk, pl[0], pl[1], fid2, tf)).equals("fileId"), tag + " wrong expected fileId rejected by rule 2");
      check(rejects(() -> tinkDecrypt(t, ad(fid, other, otherName), tf)), tag + " tink rejects wrong store");
      // truncation at the last segment boundary must fail (no last flag)
      if (tf.length >= C + T + 1) {
        long cut = (tf.length / C) * C; if (cut == tf.length) cut -= C;
        byte[] tr = Arrays.copyOf(tf, (int) cut);
        check(rejects(() -> ourDecrypt(mk, pl[0], pl[1], exp, tr)), tag + " truncation at boundary rejected");
        check(rejects(() -> tinkDecrypt(t, ad, tr)), tag + " tink rejects truncation");
      }
    }

    // L = 0: a broken file must fail in both readers (review 1, M2)
    {
      byte[] fid = new byte[16]; R.nextBytes(fid); byte[] ad = ad(fid, "attachments", "");
      byte[] of = ourEncrypt(mk, fid, ad, new byte[0]);
      int[] flips = {of.length - 1, 30, 60}; String[] what = {"tag", "salt", "nonce prefix"};
      for (int i = 0; i < flips.length; i++) {
        byte[] b = of.clone(); b[flips[i]] ^= 1; String w = what[i];
        check(rejects(() -> ourDecrypt(mk, "attachments", "", fid, b)), "L=0 broken " + w + " ours");
        check(rejects(() -> tinkDecrypt(t, ad, b)), "L=0 broken " + w + " tink");
      }
      byte[] mk2 = mk.clone(); mk2[0] ^= 1;
      check(rejects(() -> ourDecrypt(mk2, "attachments", "", fid, of)), "L=0 wrong key ours");
      check(rejects(() -> tinkDecrypt(new AesGcmHkdfStreaming(mk2, "HmacSha256", 32, C, F), ad, of)), "L=0 wrong key tink");
      check(rejects(() -> ourDecrypt(mk, "avatars", "00", null, of)), "L=0 wrong AD ours");
      check(rejects(() -> tinkDecrypt(t, ad(fid, "avatars", "00"), of)), "L=0 wrong AD tink");
      byte[] hl = of.clone(); hl[25] = 41;
      check(reason(() -> ourDecrypt(mk, "attachments", "", fid, hl)).equals("hlen"), "L=0 bad hlen ours (rule 2)");
      check(rejects(() -> tinkDecrypt(t, ad, hl)), "L=0 bad hlen tink");
    }

    // rule 5 runs at open, before any other segment: with segment 0 and the last segment both
    // damaged, the last one must be the reported cause
    {
      byte[] fid = new byte[16]; R.nextBytes(fid); byte[] ad = ad(fid, "attachments", "");
      byte[] of = ourEncrypt(mk, fid, ad, new byte[P0 + P + 1]);
      of[100] ^= 1; of[of.length - 1] ^= 1;
      check(reason(() -> ourDecrypt(mk, "attachments", "", fid, of)).equals("open: last segment"), "last segment verified first");
    }

    // a different genuine file under the same named-store path opens: no freshness (spec, AD)
    {
      String nm = "pack-ab12cd34ef567890/name.txt";
      byte[] v1 = "old title".getBytes(StandardCharsets.US_ASCII), v2 = "new title".getBytes(StandardCharsets.US_ASCII);
      byte[] fa = new byte[16], fb = new byte[16]; R.nextBytes(fa); R.nextBytes(fb);
      byte[] f1 = ourEncrypt(mk, fa, ad(fa, "stickers", nm), v1), f2 = tinkEncrypt(t, fb, ad(fb, "stickers", nm), v2);
      check(Arrays.equals(ourDecrypt(mk, "stickers", nm, null, f1), v1) && Arrays.equals(ourDecrypt(mk, "stickers", nm, null, f2), v2),
          "both versions under one name open (rollback accepted)");
    }

    // random access through Tink's position() at segment boundaries
    {
      byte[] fid = new byte[16]; R.nextBytes(fid); byte[] ad = ad(fid, "attachments", "");
      byte[] pt = new byte[P0 + 2 * P + 5]; R.nextBytes(pt); byte[] of = ourEncrypt(mk, fid, ad, pt);
      for (long p : new long[] {0, P0 - 1, P0, P0 + P - 1, P0 + P, P0 + 2 * P - 1, P0 + 2 * P, pt.length - 3})
        check(Arrays.equals(tinkReadAt(t, ad, of, p, 3), Arrays.copyOfRange(pt, (int) p, (int) p + 3)), "tink seek to " + p);
    }

    // prefix bytes are ignored by Tink: changing the magic does not stop Tink, only our reader
    {
      byte[] fid = new byte[16]; R.nextBytes(fid); byte[] ad = ad(fid, "attachments", "");
      byte[] pt = new byte[100]; R.nextBytes(pt); byte[] tf = tinkEncrypt(t, fid, ad, pt); tf[0] ^= 1;
      check(Arrays.equals(tinkDecrypt(t, ad, tf), pt), "Tink ignores the magic bytes");
      check(reason(() -> ourDecrypt(mk, "attachments", "", fid, tf)).equals("magic"), "our reader rejects a changed magic");
    }
    // empty last segment after a full one (rem == T): Tink accepts, our reader rejects
    {
      byte[] fid = new byte[16]; R.nextBytes(fid); byte[] ad = ad(fid, "attachments", "");
      byte[] pt = new byte[P0]; R.nextBytes(pt);
      byte[] salt = new byte[32], np = new byte[7]; R.nextBytes(salt); R.nextBytes(np);
      SecretKeySpec k = new SecretKeySpec(hkdf(mk, salt, ad), "AES");
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      o.write(prefix(fid)); o.write(H); o.write(salt); o.write(np);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k, nonce(np, 0, false)); o.write(c.doFinal(pt));
      c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k, nonce(np, 1, true)); o.write(c.doFinal(new byte[0]));
      byte[] f = o.toByteArray();
      check(f.length == 65552, "rem==T file size");
      check(Arrays.equals(tinkDecrypt(t, ad, f), pt), "rem==T: Tink accepts");
      check(reason(() -> ourDecrypt(mk, "attachments", "", fid, f)).equals("bad last segment size"), "rem==T: our reader rejects (rule 3)");
    }
    // Java's default GCM provider (SunJCE) also refuses key+IV reuse for encryption
    {
      byte[] k = new byte[32]; Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      GCMParameterSpec s = new GCMParameterSpec(128, new byte[12]);
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), s); c.doFinal(new byte[1]);
      check(rejects(() -> c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), s)), "JDK refuses GCM key+IV reuse on the same Cipher");
    }
    System.out.println(failures == 0 ? ("ALL " + total + " CHECKS PASSED") : failures + " FAILURES of " + total);
    System.exit(failures == 0 ? 0 : 1);
  }
}
