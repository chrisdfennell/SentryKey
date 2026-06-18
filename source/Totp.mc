import Toybox.Lang;

// RFC 6238 TOTP for SentryKey, in pure Monkey C. Extracted from SentryKeyView so
// the crypto can be unit-tested headlessly (see CryptoTests.mc). Uses a
// hand-rolled SHA-1 because Toybox.Cryptography's SHA-1 faults uncatchably on
// some fenix 8 firmware, crashing the app.
module Totp {

    // Computes the 6-digit TOTP code for a Base32 secret at a given UNIX epoch
    // (seconds). Time is a parameter (not Time.now()) so it can be tested against
    // the RFC 6238 known-answer vectors.
    function generateAt(secret as String, epochTime as Number) as String {
        try {
            var keyBytes = decodeBase32(secret);
            if (keyBytes.size() == 0) {
                return "ERRKEY";
            }

            var timeStep = epochTime / 30;

            // Message is the 8-byte big-endian representation of timeStep.
            var messageBytes = new [8]b;
            messageBytes[0] = 0;
            messageBytes[1] = 0;
            messageBytes[2] = 0;
            messageBytes[3] = 0;
            messageBytes[4] = ((timeStep >> 24) & 0xFF);
            messageBytes[5] = ((timeStep >> 16) & 0xFF);
            messageBytes[6] = ((timeStep >> 8) & 0xFF);
            messageBytes[7] = (timeStep & 0xFF);

            var hmacResult = computeHmacSha1(keyBytes, messageBytes);

            // Dynamic truncation to extract a 6-digit code.
            var offset = (hmacResult[19] & 0xFF) & 0x0F;
            var binary = ((hmacResult[offset] & 0x7F) << 24) |
                         ((hmacResult[offset + 1] & 0xFF) << 16) |
                         ((hmacResult[offset + 2] & 0xFF) << 8) |
                         (hmacResult[offset + 3] & 0xFF);

            var otp = binary % 1000000;
            return otp.format("%06d");
        } catch (e) {
            return "ERRTOTP";
        }
    }

    // HMAC-SHA1 built on the pure Monkey C SHA-1 below.
    function computeHmacSha1(keyBytes as ByteArray, messageBytes as ByteArray) as ByteArray {
        var blockSize = 64; // SHA-1 block size

        var formattedKey = keyBytes;
        if (formattedKey.size() > blockSize) {
            formattedKey = computeSha1(formattedKey);
        }

        var ipadKey = new [blockSize]b;
        var opadKey = new [blockSize]b;
        for (var i = 0; i < blockSize; i++) {
            var keyByte = (i < formattedKey.size()) ? formattedKey[i] : 0;
            ipadKey[i] = (keyByte ^ 0x36) & 0xFF;
            opadKey[i] = (keyByte ^ 0x5C) & 0xFF;
        }

        var innerDigest = computeSha1(concatBytes(ipadKey, messageBytes));
        return computeSha1(concatBytes(opadKey, innerDigest));
    }

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

    function rotl32(x as Long, n as Number) as Long {
        x = x & 0xFFFFFFFFl;
        return ((x << n) | (x >> (32 - n))) & 0xFFFFFFFFl;
    }

    // Pure Monkey C SHA-1 (64-bit Long arithmetic masked to 32 bits).
    function computeSha1(msg as ByteArray) as ByteArray {
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

    // RFC 4648 Base32 decode.
    function decodeBase32(secret as String) as ByteArray {
        var base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var upperSecret = secret.toUpper();
        var bytes = [] as Array<Number>;

        var buffer = 0;
        var bitsLeft = 0;

        for (var i = 0; i < upperSecret.length(); i++) {
            var charStr = upperSecret.substring(i, i + 1);
            if (charStr.equals("=")) {
                break;
            }
            var val = base32Chars.find(charStr);
            if (val == null) {
                continue; // Skip padding or invalid characters
            }

            buffer = ((buffer << 5) | val) & 0xFFFF;
            bitsLeft += 5;

            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                var b = (buffer >> bitsLeft) & 0xFF;
                bytes.add(b);
            }
        }

        var byteArray = new [bytes.size()]b;
        for (var idx = 0; idx < bytes.size(); idx++) {
            byteArray[idx] = bytes[idx];
        }
        return byteArray;
    }
}
