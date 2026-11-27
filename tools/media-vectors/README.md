# Media format checks against Tink

`SpecCheck.java` is a preliminary whole-buffer probe of
[the format spec](../../knowledge/egm/attachment-encryption/format-spec.md) against the real Tink
Java; it is not the app's test suite. "Our side" is an independent implementation of the spec on
plain `javax.crypto` (HKDF written out with `HmacSHA256`); Tink is only on the other side. One
AD helper feeds both sides, so the AD encoding is checked only against the spec's worked hex
examples.

It checks: the AD examples (including a 256-byte name) and the `name` alphabet; the size arithmetic as pure functions,
including the reader's last-segment length and the 2^34 bound; for lengths around the segment
boundaries and all four stores: file sizes, the header byte, ours decrypts Tink's files, Tink
decrypts ours (read until Tink's authenticated EOF, never through the unverified `size()`); our
reader takes store and name and reads the AD's `fileId` from the header, as the app will; a
wrong store, name, header `fileId` and expected `fileId` are each rejected, truncation at a
segment boundary fails in both readers; broken empty files (tag, salt, nonce prefix, key, AD,
`hlen`) fail in both; the last segment is verified first; two files under one named path both
open (no freshness, accepted); Tink's random access at segment boundaries; Tink ignores the prefix bytes while our
reader checks the magic; Tink accepts an empty last segment after a full one while our reader
rejects it; the JDK refuses GCM key and IV reuse on one `Cipher`. A failed check exits with
status 1, and so does any exception that is not a rejection. Eight deliberate mutants were
caught on 2026-11-10 (listed in the spec's "Checked against Tink").

Developer tool only; Tink is never an app dependency. JDK 25, Tink 1.24.0 from Maven Central. The
line references in the spec are from Tink commit `ccd4e6d`; against the `v1.24.0` tag
(`e0367100a`) the four streaming files differ only by a FIPS-mode guard in
`AesGcmHkdfStreaming`'s constructors (checked 2026-11-10).

```sh
mkdir -p build && cd build
curl -sfLO https://repo1.maven.org/maven2/com/google/crypto/tink/tink/1.24.0/tink-1.24.0.jar
echo "463bb0cddc8131379c4f87d8185a0314f651286c28e52a8dea5df206d8a3e175  tink-1.24.0.jar" | sha256sum -c
$JAVA_HOME/bin/java -cp tink-1.24.0.jar ../SpecCheck.java   # expect "ALL 465 CHECKS PASSED", exit 0
```

## Test vectors

The committed vectors live in `src/test/resources/media-vectors/` (about 820 KB): the files, and
two indexes with one vector per line (`file`, `source`, `store`, `name`, `fileId`, `ad`,
`masterKey`, `length`, `sha256`; the known answers also `salt` and `noncePrefix`). Every
plaintext is the pattern "byte i is i mod 251".

* `kat.py` computes the three **known answers** (`kat-*.bin`, `kat.json`) from the spec text with
  the Python standard library (HKDF) and `python-cryptography` (AES-GCM): no Tink, no Tatrak
  code, fixed key, salt and nonce prefix, the AD typed as literal hex. `write` is deterministic;
  `check` recomputes them and decrypts every vector of every index in a folder, Tink's too.
* `Generate.java` makes the **Tink vectors** (`tink-*.bin`, `tink.json`: attachments of the eight
  boundary lengths, one short file for each other store, a sticker pack's `name.txt`) with a
  random key, salt and ids, so a new `write` changes them. `verify` decrypts every vector of
  every index in a folder with Tink, the known answers too.

```sh
V=../../../src/test/resources/media-vectors      # from tools/media-vectors/build
python3 -I ../kat.py write $V                                         # only to regenerate
$JAVA_HOME/bin/java -cp tink-1.24.0.jar ../Generate.java write $V     # only to regenerate
$JAVA_HOME/bin/java -cp tink-1.24.0.jar ../Generate.java verify $V    # expect "OK: Tink decrypted 15 vectors"
python3 -I ../kat.py check $V                                         # expect "OK: 3 known answers recomputed, 15 vectors decrypted without Tink"
```

The Tatrak to Tink direction: `MediaCipherTest.writesFilesForTinkToDecrypt` writes eleven files
made by the app's `MediaCipher` and their index into `build/media-vectors-tatrak/` (repository
root). After the unit tests, from the repository root:

```sh
$JAVA_HOME/bin/java -cp tools/media-vectors/build/tink-1.24.0.jar tools/media-vectors/Generate.java verify build/media-vectors-tatrak
python3 -I tools/media-vectors/kat.py check build/media-vectors-tatrak     # expect 11 vectors from each
```

Tink's sequential `newDecryptingStream` reads the header at the stream's current position: skip
the 25 prefix bytes first, or use the seekable channel.
