import Foundation
import CommonCrypto

/// Optional passphrase encryption for the phone <-> watch BLE sync string.
///
/// Wire-compatible with the watch (`source/SyncCrypto.mc`) and Android
/// (`SyncCrypto.kt`); all three are pinned to the same canonical test vector.
/// Built on HMAC-SHA1 because the Garmin watch's native SHA-1 is unreliable, so
/// the watch only has a pure Monkey C HMAC-SHA1 to work from.
///
/// Wire format:
///   "SKENC1:" + base64( salt(16) || nonce(8) || mac(20) || ciphertext )
///   key    = PBKDF2-HMAC-SHA1(passphrase, salt, ITERATIONS, dkLen=40)
///   encKey = key[0..20), macKey = key[20..40)
///   keystream block i = HMAC-SHA1(encKey, nonce || be32(i))
///   ciphertext = plaintext XOR keystream
///   mac    = HMAC-SHA1(macKey, salt || nonce || ciphertext)
enum SyncCrypto {
    static let marker = "SKENC1:"
    static let iterations = 1000   // must match SyncCrypto.kt / SyncCrypto.mc

    private static let saltLen = 16
    private static let nonceLen = 8
    private static let macLen = 20

    static func isEncrypted(_ payload: String) -> Bool { payload.hasPrefix(marker) }

    static func encrypt(_ plaintext: String, passphrase: String) -> String {
        var salt = Data(count: saltLen)
        var nonce = Data(count: nonceLen)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, saltLen, $0.baseAddress!) }
        _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, nonceLen, $0.baseAddress!) }
        return encryptWith(plaintext, passphrase: passphrase, salt: salt, nonce: nonce)
    }

    /// Deterministic core, separated so a test can pin a fixed salt/nonce vector.
    static func encryptWith(_ plaintext: String, passphrase: String, salt: Data, nonce: Data) -> String {
        let (encKey, macKey) = deriveKeys(passphrase: passphrase, salt: salt)
        let ciphertext = keystreamXor(Data(plaintext.utf8), encKey: encKey, nonce: nonce)
        let mac = hmacSha1(key: macKey, message: salt + nonce + ciphertext)
        let blob = salt + nonce + mac + ciphertext
        return marker + blob.base64EncodedString()
    }

    /// Returns the plaintext sync string, or nil if the passphrase is wrong /
    /// the payload is corrupt (MAC mismatch).
    static func decrypt(_ payload: String, passphrase: String) -> String? {
        guard isEncrypted(payload), !passphrase.isEmpty else { return nil }
        let b64 = String(payload.dropFirst(marker.count))
        guard let blob = Data(base64Encoded: b64), blob.count >= saltLen + nonceLen + macLen else { return nil }

        let salt = blob.subdata(in: 0..<saltLen)
        let nonce = blob.subdata(in: saltLen..<(saltLen + nonceLen))
        let mac = blob.subdata(in: (saltLen + nonceLen)..<(saltLen + nonceLen + macLen))
        let ciphertext = blob.subdata(in: (saltLen + nonceLen + macLen)..<blob.count)

        let (encKey, macKey) = deriveKeys(passphrase: passphrase, salt: salt)
        let expected = hmacSha1(key: macKey, message: salt + nonce + ciphertext)
        guard constantTimeEquals(mac, expected) else { return nil }

        let plain = keystreamXor(ciphertext, encKey: encKey, nonce: nonce)
        return String(decoding: plain, as: UTF8.self)
    }

    // MARK: - primitives

    private static func deriveKeys(passphrase: String, salt: Data) -> (Data, Data) {
        let dk = pbkdf2(passphrase: passphrase, salt: salt, iterations: iterations, keyLen: 40)
        return (dk.subdata(in: 0..<20), dk.subdata(in: 20..<40))
    }

    private static func pbkdf2(passphrase: String, salt: Data, iterations: Int, keyLen: Int) -> Data {
        var derived = Data(count: keyLen)
        let pwData = Data(passphrase.utf8)
        _ = derived.withUnsafeMutableBytes { derivedPtr in
            salt.withUnsafeBytes { saltPtr in
                pwData.withUnsafeBytes { pwPtr in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        pwPtr.bindMemory(to: Int8.self).baseAddress, pwData.count,
                        saltPtr.bindMemory(to: UInt8.self).baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA1),
                        UInt32(iterations),
                        derivedPtr.bindMemory(to: UInt8.self).baseAddress, keyLen
                    )
                }
            }
        }
        return derived
    }

    private static func keystreamXor(_ input: Data, encKey: Data, nonce: Data) -> Data {
        var out = Data(count: input.count)
        var produced = 0
        var counter: UInt32 = 0
        while produced < input.count {
            let block = hmacSha1(key: encKey, message: nonce + be32(counter))
            var i = 0
            while i < block.count && produced < input.count {
                out[produced] = input[produced] ^ block[i]
                produced += 1; i += 1
            }
            counter += 1
        }
        return out
    }

    private static func hmacSha1(key: Data, message: Data) -> Data {
        var out = Data(count: Int(CC_SHA1_DIGEST_LENGTH))
        out.withUnsafeMutableBytes { outPtr in
            key.withUnsafeBytes { keyPtr in
                message.withUnsafeBytes { msgPtr in
                    CCHmac(CCHmacAlgorithm(kCCHmacAlgSHA1),
                           keyPtr.baseAddress, key.count,
                           msgPtr.baseAddress, message.count,
                           outPtr.baseAddress)
                }
            }
        }
        return out
    }

    private static func be32(_ v: UInt32) -> Data {
        Data([UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)])
    }

    private static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[i] ^ b[i] }
        return diff == 0
    }
}
