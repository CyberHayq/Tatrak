"""Known-answer vectors for the encrypted media format v1 (knowledge/egm/attachment-encryption/format-spec.md).

Written from the spec text: HKDF from the standard library's hmac, AES-GCM from `cryptography`;
no Tink and no Tatrak code, so a mistake shared by Tink's use and ours cannot hide. The
associated data of the three known answers is typed as literal hex, not built by an encoder.

  python3 -I kat.py write <dir>   # writes kat-*.bin and kat.json (deterministic)
  python3 -I kat.py check <dir>   # recomputes the known answers and decrypts every vector of
                                  # every *.json index in <dir> (also Tink's) with this code
"""
import glob, hashlib, hmac, json, os, re, sys
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

C, T, F, H = 65536, 16, 25, 40
D, P = F + H, C - T
P0 = P - D
MASTER = bytes(range(32))  # test key, never a real one

# (file, store, name, fileId, salt, nonce prefix, plaintext length, AD as literal hex)
KATS = [
    ("kat-attachments-0.bin", "attachments", "", bytes(range(0xF0, 0x100)), bytes(range(0x40, 0x60)),
     bytes(range(0xA0, 0xA7)), 0,
     "0c 74617472616b2d6d65646961 01 f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
     " 0b 6174746163686d656e7473 0000"),
    ("kat-stickers-65455.bin", "stickers", "pack-01/02.webp", bytes(range(0xE0, 0xF0)), bytes(range(0x60, 0x80)),
     bytes(range(0xB0, 0xB7)), P0,
     "0c 74617472616b2d6d65646961 01 e0e1e2e3e4e5e6e7e8e9eaebecedeeef"
     " 08 737469636b657273 000f 7061636b2d30312f30322e77656270"),
    ("kat-wallpapers-130976.bin", "wallpapers", "9f86d081884c7d65.jpg", bytes(range(0xD0, 0xE0)), bytes(range(0x80, 0xA0)),
     bytes(range(0xC0, 0xC7)), P0 + P + 1,
     "0c 74617472616b2d6d65646961 01 d0d1d2d3d4d5d6d7d8d9dadbdcdddedf"
     " 0a 77616c6c706170657273 0014 396638366430383138383463376436352e6a7067"),
]


def pattern(length):
    """The plaintext of every vector: byte i is i mod 251 (251 is prime, so no segment repeats)."""
    return bytes(i % 251 for i in range(length))


def hkdf(ikm, salt, info):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()          # RFC 5869 extract
    return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()  # expand, L = 32 = one block


def nonce(prefix, i, last):
    return prefix + i.to_bytes(4, "big") + (b"\x01" if last else b"\x00")


def ad(file_id, store, name):
    """Encoder used only by `check` for other indexes; the known answers use the literal hex."""
    assert len(file_id) == 16 and store in ("attachments", "avatars", "wallpapers", "stickers")
    assert (name == "") == (store == "attachments") and len(name) <= 1024
    assert name == "" or re.fullmatch(r"[0-9a-z._-]+(/[0-9a-z._-]+)*", name), name
    assert not any(part in (".", "..") for part in name.split("/"))
    n = name.encode("ascii")
    return b"\x0ctatrak-media\x01" + file_id + bytes([len(store)]) + store.encode() + len(n).to_bytes(2, "big") + n


def encrypt(master, file_id, salt, prefix, associated, plaintext):
    key = AESGCM(hkdf(master, salt, associated))
    out = [b"TATRKENC\x01", file_id, bytes([H]), salt, prefix]
    pos, i, cap = 0, 0, P0
    while True:
        last = len(plaintext) - pos <= cap  # writer rule 3: not last only when more than fits
        out.append(key.encrypt(nonce(prefix, i, last), plaintext[pos:pos + cap], None))
        pos, i, cap = pos + cap, i + 1, P
        if last:
            return b"".join(out)


def decrypt(master, store, name, expected_file_id, data):
    """Reader rules 1-6 of the spec; raises ValueError on any rejection."""
    size = len(data)
    if size < D + T: raise ValueError("too short")
    if data[:8] != b"TATRKENC" or data[8] != 1: raise ValueError("magic or version")
    file_id = data[9:25]
    if store == "attachments" and file_id != expected_file_id: raise ValueError("fileId")
    if data[25] != H: raise ValueError("hlen")
    full, rem = divmod(size, C)
    n = full if rem == 0 else full + 1
    if n > 1 and 1 <= rem <= T: raise ValueError("bad last segment size")
    length = size - D - T * n
    if length > 2 ** 34: raise ValueError("too long")
    key = AESGCM(hkdf(master, data[26:58], ad(file_id, store, name)))
    prefix = data[58:65]

    def segment(i):
        start = D if i == 0 else i * C
        try:
            return key.decrypt(nonce(prefix, i, i == n - 1), data[start:min((i + 1) * C, size)], None)
        except InvalidTag:
            raise ValueError("tag of segment %d" % i)

    last = segment(n - 1)  # rule 5: the last segment first
    plaintext = b"".join(segment(i) for i in range(n - 1)) + last
    if len(plaintext) != length: raise ValueError("length")
    return plaintext


def known_answers():
    for file, store, name, file_id, salt, prefix, length, ad_hex in KATS:
        associated = bytes.fromhex(ad_hex)
        assert associated == ad(file_id, store, name), "literal AD and encoder disagree: " + file
        data = encrypt(MASTER, file_id, salt, prefix, associated, pattern(length))
        assert len(data) == D + length + T * (1 if length <= P0 else 1 + -(-(length - P0) // P))
        assert decrypt(MASTER, store, name, file_id, data) == pattern(length)
        yield data, {
            "file": file, "source": "kat", "store": store, "name": name, "fileId": file_id.hex(),
            "ad": associated.hex(), "masterKey": MASTER.hex(), "salt": salt.hex(),
            "noncePrefix": prefix.hex(), "length": length, "sha256": hashlib.sha256(data).hexdigest()}


def write(directory):
    os.makedirs(directory, exist_ok=True)
    entries = []
    for data, entry in known_answers():
        with open(os.path.join(directory, entry["file"]), "wb") as f:
            f.write(data)
        entries.append(entry)
        print(entry["file"], len(data), entry["sha256"])
    with open(os.path.join(directory, "kat.json"), "w") as f:  # one vector per line: Generate.java reads it by line
        f.write("[\n" + ",\n".join(json.dumps(e) for e in entries) + "\n]\n")


def check(directory):
    count = known = 0
    if os.path.exists(os.path.join(directory, "kat.json")):  # another folder holds only the app's files
        for data, entry in known_answers():
            with open(os.path.join(directory, entry["file"]), "rb") as f:
                assert f.read() == data, "known answer differs: " + entry["file"]
            known += 1
    for index in sorted(glob.glob(os.path.join(directory, "*.json"))):
        for e in json.load(open(index)):
            with open(os.path.join(directory, e["file"]), "rb") as f:
                data = f.read()
            file_id = bytes.fromhex(e["fileId"])
            assert hashlib.sha256(data).hexdigest() == e["sha256"], "sha256: " + e["file"]
            assert ad(file_id, e["store"], e["name"]).hex() == e["ad"], "AD: " + e["file"]
            plaintext = decrypt(bytes.fromhex(e["masterKey"]), e["store"], e["name"], file_id, data)
            assert plaintext == pattern(e["length"]), "plaintext: " + e["file"]
            # the reader must refuse the same file under another name, key or with a flipped byte
            broken = bytearray(data); broken[-1] ^= 1
            for bad in (lambda: decrypt(bytes(32), e["store"], e["name"], file_id, data),
                        lambda: decrypt(bytes.fromhex(e["masterKey"]), "avatars", "00", file_id, data),
                        lambda: decrypt(bytes.fromhex(e["masterKey"]), e["store"], e["name"], file_id, bytes(broken))):
                try:
                    bad()
                except ValueError:
                    continue
                raise AssertionError("accepted a wrong key, name or tag: " + e["file"])
            count += 1
    assert count > 0, "no vectors in " + directory
    print("OK: %d known answers recomputed, %d vectors decrypted without Tink" % (known, count))


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("write", "check"):
        sys.exit(__doc__)
    {"write": write, "check": check}[sys.argv[1]](sys.argv[2])
