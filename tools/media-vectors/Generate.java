import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test vectors of the encrypted media format v1 (format-spec.md), made and checked by real Tink.
 *
 * <pre>
 * java -cp tink-1.24.0.jar Generate.java write DIR    writes tink-*.bin and tink.json (random key, salt, ids)
 * java -cp tink-1.24.0.jar Generate.java verify DIR   decrypts every vector of every *.json index in DIR with Tink
 * </pre>
 *
 * `verify` also covers the known answers of kat.py and files written by the app's MediaCipher,
 * as long as their index has the same fields.
 */
public class Generate {
  static final int C = 65536, T = 16, F = 25, D = 65, P = C - T, P0 = P - D;
  static final HexFormat HEX = HexFormat.of();

  /** The plaintext of every vector: byte i is i mod 251, as in kat.py. */
  static byte[] pattern(int length) {
    byte[] b = new byte[length];
    for (int i = 0; i < length; i++) b[i] = (byte) (i % 251);
    return b;
  }

  static byte[] ad(byte[] fileId, String store, String name) throws IOException {
    boolean attachment = store.equals("attachments");
    if (attachment != name.isEmpty() || name.length() > 1024
        || !(attachment || name.matches("[0-9a-z._-]+(/[0-9a-z._-]+)*"))
        || List.of(name.split("/")).contains(".") || List.of(name.split("/")).contains("..")) throw new IOException("name " + name);
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    o.write(12); o.write("tatrak-media".getBytes(StandardCharsets.US_ASCII)); o.write(1); o.write(fileId);
    o.write(store.length()); o.write(store.getBytes(StandardCharsets.US_ASCII));
    o.write(name.length() >> 8); o.write(name.length() & 255); o.write(name.getBytes(StandardCharsets.US_ASCII));
    return o.toByteArray();
  }

  static AesGcmHkdfStreaming tink(byte[] masterKey) throws Exception {
    return new AesGcmHkdfStreaming(masterKey, "HmacSha256", 32, C, F);
  }

  static String sha256(byte[] b) throws Exception { return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }

  static void write(Path dir) throws Exception {
    Files.createDirectories(dir);
    SecureRandom random = new SecureRandom();
    byte[] masterKey = new byte[32]; random.nextBytes(masterKey);
    AesGcmHkdfStreaming t = tink(masterKey);
    Object[][] vectors = {
      {"attachments", "", 0}, {"attachments", "", 1}, {"attachments", "", P0 - 1}, {"attachments", "", P0},
      {"attachments", "", P0 + 1}, {"attachments", "", P0 + P - 1}, {"attachments", "", P0 + P}, {"attachments", "", P0 + P + 1},
      {"avatars", "3a7bd3e2360a3d29eea436fcfb7e44c735d117c4", 1000},
      {"wallpapers", "9f86d081884c7d65.jpg", 1000},
      {"stickers", "pack-ab12cd34ef567890/1a2b3c4d5e6f7081.webp", 1000},
      {"stickers", "pack-ab12cd34ef567890/name.txt", 9},
    };
    List<String> lines = new ArrayList<>();
    for (Object[] v : vectors) {
      String store = (String) v[0], name = (String) v[1]; int length = (Integer) v[2];
      byte[] fileId = new byte[16]; random.nextBytes(fileId);
      byte[] ad = ad(fileId, store, name);
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      o.write("TATRKENC".getBytes(StandardCharsets.US_ASCII)); o.write(1); o.write(fileId); // the prefix is ours, Tink skips it
      try (OutputStream e = t.newEncryptingStream(o, ad)) { e.write(pattern(length)); }
      byte[] file = o.toByteArray();
      String fileName = "tink-" + store + "-" + (name.endsWith("name.txt") ? "name-" : "") + length + ".bin";
      Files.write(dir.resolve(fileName), file);
      lines.add(String.format(
          "{\"file\": \"%s\", \"source\": \"tink\", \"store\": \"%s\", \"name\": \"%s\", \"fileId\": \"%s\", \"ad\": \"%s\", \"masterKey\": \"%s\", \"length\": %d, \"sha256\": \"%s\"}",
          fileName, store, name, HEX.formatHex(fileId), HEX.formatHex(ad), HEX.formatHex(masterKey), length, sha256(file)));
      System.out.println(fileName + " " + file.length);
    }
    Files.writeString(dir.resolve("tink.json"), "[\n" + String.join(",\n", lines) + "\n]\n");
  }

  // shortcut: reads the index by line and by regex (one vector per line, no escapes in values),
  // upgrade to a JSON parser if an index ever needs nested or escaped values
  static String field(String line, String key) {
    Matcher m = Pattern.compile("\"" + key + "\": \"?([^\",}]*)\"?").matcher(line);
    if (!m.find()) throw new IllegalArgumentException("no " + key + " in " + line);
    return m.group(1);
  }

  static byte[] tinkDecrypt(AesGcmHkdfStreaming t, byte[] ad, Path file) throws Exception {
    try (SeekableByteChannel d = t.newSeekableDecryptingChannel(Files.newByteChannel(file), ad)) {
      ByteArrayOutputStream o = new ByteArrayOutputStream(); ByteBuffer b = ByteBuffer.allocate(8192);
      while (d.read(b) >= 0) { o.write(b.array(), 0, b.position()); b.clear(); } // to Tink's authenticated end of data
      return o.toByteArray();
    }
  }

  static void verify(Path dir) throws Exception {
    int count = 0;
    List<Path> indexes;
    try (var s = Files.list(dir)) { indexes = s.filter(p -> p.toString().endsWith(".json")).sorted().toList(); }
    for (Path index : indexes) for (String line : Files.readAllLines(index)) {
      if (!line.startsWith("{")) continue;
      Path file = dir.resolve(field(line, "file"));
      byte[] fileId = HEX.parseHex(field(line, "fileId")), bytes = Files.readAllBytes(file);
      byte[] ad = ad(fileId, field(line, "store"), field(line, "name"));
      int length = Integer.parseInt(field(line, "length"));
      require(HEX.formatHex(ad).equals(field(line, "ad")), "AD built from the fields differs from the index: " + file);
      require(sha256(bytes).equals(field(line, "sha256")), "sha256: " + file);
      require(java.util.Arrays.equals(java.util.Arrays.copyOfRange(bytes, 0, 9), "TATRKENC\u0001".getBytes(StandardCharsets.ISO_8859_1)), "prefix: " + file);
      require(java.util.Arrays.equals(java.util.Arrays.copyOfRange(bytes, 9, 25), fileId), "fileId in the prefix: " + file);
      AesGcmHkdfStreaming t = tink(HEX.parseHex(field(line, "masterKey")));
      require(java.util.Arrays.equals(tinkDecrypt(t, ad, file), pattern(length)), "plaintext: " + file);
      boolean refused = false; // the same file under another name must fail in Tink too
      try { tinkDecrypt(t, ad(fileId, "avatars", "00"), file); } catch (IOException e) { refused = true; }
      require(refused, "Tink accepted another store and name: " + file);
      count++;
    }
    require(count > 0, "no vectors in " + dir);
    System.out.println("OK: Tink decrypted " + count + " vectors of " + indexes.size() + " index file(s)");
  }

  static void require(boolean ok, String what) { if (!ok) throw new IllegalStateException(what); }

  public static void main(String[] a) throws Exception {
    if (a.length == 2 && a[0].equals("write")) write(Path.of(a[1]));
    else if (a.length == 2 && a[0].equals("verify")) verify(Path.of(a[1]));
    else { System.err.println("usage: Generate.java write|verify DIR"); System.exit(2); }
  }
}
