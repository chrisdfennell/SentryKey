import Toybox.Lang;
import Toybox.Math;
import Toybox.StringUtil;
import Toybox.Time;

// Optional passphrase encryption for the phone <-> watch BLE sync string.
//
// Built entirely on a pure Monkey C HMAC-SHA1 (the watch's native
// Toybox.Cryptography SHA-1 faults uncatchably on some fenix 8 firmware), so the
// exact same scheme is implementable on Android (SyncCrypto.kt) and iOS
// (SyncCrypto.swift). All three MUST agree byte-for-byte; the Android
// instrumented test pins a canonical vector that this code is written to match.
//
// Wire format:
//   "SKENC1:" + base64( salt(16) || nonce(8) || mac(20) || ciphertext )
//   key    = PBKDF2-HMAC-SHA1(passphrase, salt, ITERATIONS, dkLen=40)
//   encKey = key[0..20),  macKey = key[20..40)
//   keystream block i = HMAC-SHA1(encKey, nonce || be32(i))
//   ciphertext = plaintext XOR keystream
//   mac    = HMAC-SHA1(macKey, salt || nonce || ciphertext)
module SyncCrypto {

    const MARKER = "SKENC1:";
    const ITERATIONS = 1000;   // must match SyncCrypto.kt / SyncCrypto.swift

    // True if a received payload is an encrypted sync string.
    function isEncrypted(payload as String) as Boolean {
        if (payload == null || payload.length() < 7) {
            return false;
        }
        return payload.substring(0, 7).equals(MARKER);
    }

    // Decrypts an "SKENC1:" payload. Returns the plaintext sync string, or null
    // if the passphrase is wrong / payload corrupt (caller keeps existing vault).
    function decrypt(payload as String, passphrase as String) as String? {
        if (!isEncrypted(payload) || passphrase == null || passphrase.length() == 0) {
            return null;
        }
        try {
            var blob = base64Decode(payload.substring(7, payload.length()));
            if (blob.size() < 44) { // 16 + 8 + 20
                return null;
            }
            var salt = slice(blob, 0, 16);
            var nonce = slice(blob, 16, 24);
            var mac = slice(blob, 24, 44);
            var ciphertext = slice(blob, 44, blob.size());

            var keys = deriveKeys(passphrase, salt);
            var encKey = keys[0];
            var macKey = keys[1];

            var expected = hmacSha1(macKey, concatBytes(concatBytes(salt, nonce), ciphertext));
            if (!bytesEqual(mac, expected)) {
                return null;
            }
            var plain = keystreamXor(ciphertext, encKey, nonce);
            // utf8ArrayToString wants an Array<Number>, not a ByteArray.
            var plainArr = [] as Array<Number>;
            for (var i = 0; i < plain.size(); i++) {
                plainArr.add(plain[i] & 0xFF);
            }
            return StringUtil.utf8ArrayToString(plainArr);
        } catch (e) {
            return null;
        }
    }

    // Encrypts a plaintext sync string for transmission to the phone (pull/recovery).
    function encrypt(plaintext as String, passphrase as String) as String {
        var salt = randomBytes(16);
        var nonce = randomBytes(8);
        return encryptWith(plaintext, passphrase, salt, nonce);
    }

    // Deterministic core (testable with fixed salt/nonce).
    function encryptWith(plaintext as String, passphrase as String, salt as ByteArray, nonce as ByteArray) as String {
        var keys = deriveKeys(passphrase, salt);
        var encKey = keys[0];
        var macKey = keys[1];

        var plainBytes = utf8Bytes(plaintext);
        var ciphertext = keystreamXor(plainBytes, encKey, nonce);
        var mac = hmacSha1(macKey, concatBytes(concatBytes(salt, nonce), ciphertext));
        var blob = concatBytes(concatBytes(concatBytes(salt, nonce), mac), ciphertext);
        return MARKER + base64Encode(blob);
    }

    // ---- key derivation ----

    // Returns [encKey(20), macKey(20)] from PBKDF2-HMAC-SHA1(dkLen=40).
    function deriveKeys(passphrase as String, salt as ByteArray) as Array<ByteArray> {
        var dk = pbkdf2(utf8Bytes(passphrase), salt, ITERATIONS, 40);
        return [slice(dk, 0, 20), slice(dk, 20, 40)] as Array<ByteArray>;
    }

    // PBKDF2-HMAC-SHA1. hLen = 20, so dkLen=40 needs 2 output blocks.
    function pbkdf2(password as ByteArray, salt as ByteArray, iterations as Number, dkLen as Number) as ByteArray {
        var hLen = 20;
        var blocks = (dkLen + hLen - 1) / hLen;
        var out = new [blocks * hLen]b;
        for (var b = 1; b <= blocks; b++) {
            // U1 = HMAC(password, salt || INT32BE(b))
            var u = hmacSha1(password, concatBytes(salt, be32(b)));
            var t = new [hLen]b;
            for (var i = 0; i < hLen; i++) {
                t[i] = u[i];
            }
            for (var c = 1; c < iterations; c++) {
                u = hmacSha1(password, u);
                for (var i = 0; i < hLen; i++) {
                    t[i] = (t[i] ^ u[i]) & 0xFF;
                }
            }
            var dest = (b - 1) * hLen;
            for (var i = 0; i < hLen; i++) {
                out[dest + i] = t[i];
            }
        }
        return slice(out, 0, dkLen);
    }

    // ---- stream cipher ----

    function keystreamXor(input as ByteArray, encKey as ByteArray, nonce as ByteArray) as ByteArray {
        var out = new [input.size()]b;
        var produced = 0;
        var counter = 0;
        while (produced < input.size()) {
            var block = hmacSha1(encKey, concatBytes(nonce, be32(counter)));
            var i = 0;
            while (i < block.size() && produced < input.size()) {
                out[produced] = (input[produced] ^ block[i]) & 0xFF;
                produced++;
                i++;
            }
            counter++;
        }
        return out;
    }

    // ---- HMAC-SHA1 (mirrors SentryKeyView's proven implementation) ----

    function hmacSha1(keyBytes as ByteArray, messageBytes as ByteArray) as ByteArray {
        var blockSize = 64;
        var formattedKey = keyBytes;
        if (formattedKey.size() > blockSize) {
            formattedKey = sha1(formattedKey);
        }
        var ipadKey = new [blockSize]b;
        var opadKey = new [blockSize]b;
        for (var i = 0; i < blockSize; i++) {
            var keyByte = (i < formattedKey.size()) ? formattedKey[i] : 0;
            ipadKey[i] = (keyByte ^ 0x36) & 0xFF;
            opadKey[i] = (keyByte ^ 0x5C) & 0xFF;
        }
        var innerDigest = sha1(concatBytes(ipadKey, messageBytes));
        return sha1(concatBytes(opadKey, innerDigest));
    }

    function sha1(msg as ByteArray) as ByteArray {
        var mask = 0xFFFFFFFFl;
        var data = [] as Array<Number>;
        var msgLen = msg.size();
        for (var i = 0; i < msgLen; i++) {
            data.add(msg[i] & 0xFF);
        }
        data.add(0x80);
        while (data.size() % 64 != 56) {
            data.add(0x00);
        }
        var bitLen = msgLen * 8;
        data.add(0x00);
        data.add(0x00);
        data.add(0x00);
        data.add(0x00);
        data.add((bitLen >> 24) & 0xFF);
        data.add((bitLen >> 16) & 0xFF);
        data.add((bitLen >> 8) & 0xFF);
        data.add(bitLen & 0xFF);

        var h0 = 0x67452301l;
        var h1 = 0xEFCDAB89l;
        var h2 = 0x98BADCFEl;
        var h3 = 0x10325476l;
        var h4 = 0xC3D2E1F0l;

        var numChunks = data.size() / 64;
        for (var chunk = 0; chunk < numChunks; chunk++) {
            var w = new [80] as Array<Long>;
            for (var i = 0; i < 16; i++) {
                var base = chunk * 64 + i * 4;
                w[i] = ((data[base].toLong() << 24) |
                        (data[base + 1].toLong() << 16) |
                        (data[base + 2].toLong() << 8) |
                        data[base + 3].toLong()) & mask;
            }
            for (var i = 16; i < 80; i++) {
                var x = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]) & mask;
                w[i] = rotl32(x, 1);
            }

            var a = h0;
            var b = h1;
            var c = h2;
            var d = h3;
            var e = h4;

            for (var i = 0; i < 80; i++) {
                var f;
                var k;
                if (i < 20) {
                    f = ((b & c) | ((~b & mask) & d)) & mask;
                    k = 0x5A827999l;
                } else if (i < 40) {
                    f = (b ^ c ^ d) & mask;
                    k = 0x6ED9EBA1l;
                } else if (i < 60) {
                    f = ((b & c) | (b & d) | (c & d)) & mask;
                    k = 0x8F1BBCDCl;
                } else {
                    f = (b ^ c ^ d) & mask;
                    k = 0xCA62C1D6l;
                }
                var temp = (rotl32(a, 5) + f + e + k + w[i]) & mask;
                e = d;
                d = c;
                c = rotl32(b, 30);
                b = a;
                a = temp;
            }

            h0 = (h0 + a) & mask;
            h1 = (h1 + b) & mask;
            h2 = (h2 + c) & mask;
            h3 = (h3 + d) & mask;
            h4 = (h4 + e) & mask;
        }

        var out = new [20]b;
        var words = [h0, h1, h2, h3, h4] as Array<Long>;
        for (var i = 0; i < 5; i++) {
            var word = words[i];
            out[i * 4] = ((word >> 24) & 0xFF).toNumber();
            out[i * 4 + 1] = ((word >> 16) & 0xFF).toNumber();
            out[i * 4 + 2] = ((word >> 8) & 0xFF).toNumber();
            out[i * 4 + 3] = (word & 0xFF).toNumber();
        }
        return out;
    }

    function rotl32(x as Long, n as Number) as Long {
        x = x & 0xFFFFFFFFl;
        return ((x << n) | (x >> (32 - n))) & 0xFFFFFFFFl;
    }

    // ---- byte helpers ----

    function concatBytes(a as ByteArray, b as ByteArray) as ByteArray {
        var result = new [a.size() + b.size()]b;
        for (var i = 0; i < a.size(); i++) {
            result[i] = a[i];
        }
        for (var j = 0; j < b.size(); j++) {
            result[a.size() + j] = b[j];
        }
        return result;
    }

    function slice(a as ByteArray, start as Number, end as Number) as ByteArray {
        var out = new [end - start]b;
        for (var i = 0; i < end - start; i++) {
            out[i] = a[start + i];
        }
        return out;
    }

    function bytesEqual(a as ByteArray, b as ByteArray) as Boolean {
        if (a.size() != b.size()) {
            return false;
        }
        var diff = 0;
        for (var i = 0; i < a.size(); i++) {
            diff = diff | (a[i] ^ b[i]);
        }
        return diff == 0;
    }

    function be32(v as Number) as ByteArray {
        var out = new [4]b;
        out[0] = (v >> 24) & 0xFF;
        out[1] = (v >> 16) & 0xFF;
        out[2] = (v >> 8) & 0xFF;
        out[3] = v & 0xFF;
        return out;
    }

    function utf8Bytes(s as String) as ByteArray {
        var arr = s.toUtf8Array();
        var out = new [arr.size()]b;
        for (var i = 0; i < arr.size(); i++) {
            out[i] = arr[i] & 0xFF;
        }
        return out;
    }

    function randomBytes(n as Number) as ByteArray {
        // Math.rand() PRNG (not a CSPRNG). Adequate for per-message salt/nonce
        // uniqueness in this defense-in-depth layer over already-encrypted BLE.
        Math.srand(Time.now().value() + n);
        var out = new [n]b;
        for (var i = 0; i < n; i++) {
            out[i] = Math.rand() & 0xFF;
        }
        return out;
    }

    // ---- standard base64 ----

    const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    function base64Encode(bytes as ByteArray) as String {
        var out = "";
        var i = 0;
        var n = bytes.size();
        while (i < n) {
            var b0 = bytes[i] & 0xFF;
            var b1 = (i + 1 < n) ? (bytes[i + 1] & 0xFF) : 0;
            var b2 = (i + 2 < n) ? (bytes[i + 2] & 0xFF) : 0;

            out = out + B64.substring((b0 >> 2) & 0x3F, ((b0 >> 2) & 0x3F) + 1);
            out = out + B64.substring(((b0 << 4) | (b1 >> 4)) & 0x3F, (((b0 << 4) | (b1 >> 4)) & 0x3F) + 1);
            if (i + 1 < n) {
                out = out + B64.substring(((b1 << 2) | (b2 >> 6)) & 0x3F, (((b1 << 2) | (b2 >> 6)) & 0x3F) + 1);
            } else {
                out = out + "=";
            }
            if (i + 2 < n) {
                out = out + B64.substring(b2 & 0x3F, (b2 & 0x3F) + 1);
            } else {
                out = out + "=";
            }
            i += 3;
        }
        return out;
    }

    function base64Decode(s as String) as ByteArray {
        var vals = [] as Array<Number>;
        for (var i = 0; i < s.length(); i++) {
            var ch = s.substring(i, i + 1);
            if (ch.equals("=")) {
                break;
            }
            var idx = B64.find(ch);
            if (idx == null) {
                continue; // skip whitespace / stray chars
            }
            vals.add(idx);
        }
        var outBytes = [] as Array<Number>;
        var buffer = 0;
        var bits = 0;
        for (var i = 0; i < vals.size(); i++) {
            buffer = ((buffer << 6) | vals[i]) & 0xFFFFFF;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                outBytes.add((buffer >> bits) & 0xFF);
            }
        }
        var out = new [outBytes.size()]b;
        for (var i = 0; i < outBytes.size(); i++) {
            out[i] = outBytes[i];
        }
        return out;
    }
}
