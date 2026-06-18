import Foundation
import CryptoKit
import CommonCrypto

/// Zero-knowledge cloud-account crypto. Byte-for-byte compatible with the web
/// dashboard (`crypto.js`) and Android (`CloudCrypto.kt`), pinned to a shared
/// vector (username "alice" / "supersecuremasterpassword"):
///   authKey = 68c6d3429e6d6b8025aa38fc281779b1cf308a90e6422888c94aceeb621bc0f4
///   encKey  = 3294dbf0fca8ca2c526b1397fb9bd6c1175caf5674fb5ccbf99af4956a9dbb03
///
///   masterKey = PBKDF2-HMAC-SHA256(password, salt = lowercased username, 210k, 32B)
///   authKey   = HMAC-SHA256(masterKey, "auth-key")        -> hex, sent to server
///   encKey    = HMAC-SHA256(masterKey, "encryption-key")  -> 32B, never leaves device
/// Vault is sealed with AES-256-GCM using encKey directly (envelope salt is decorative).
enum CloudCrypto {
    static let iterations = 210_000

    struct UserKeys {
        let authKey: String
        let encKey: Data
    }

    static func deriveUserKeys(username: String, password: String) -> UserKeys {
        let cleanUser = username.trimmingCharacters(in: .whitespaces).lowercased()
        let master = pbkdf2SHA256(password: password, salt: Data(cleanUser.utf8), iterations: iterations, keyLen: 32)
        let key = SymmetricKey(data: master)
        let authMac = HMAC<SHA256>.authenticationCode(for: Data("auth-key".utf8), using: key)
        let encMac = HMAC<SHA256>.authenticationCode(for: Data("encryption-key".utf8), using: key)
        let authHex = Data(authMac).map { String(format: "%02x", $0) }.joined()
        return UserKeys(authKey: authHex, encKey: Data(encMac))
    }

    // MARK: - Account recovery (matches web crypto.js / Android CloudCrypto.kt)

    struct RecoveryKeys {
        let wrapKey: Data
        let authKey: String
    }

    /// A readable one-time recovery key, e.g. ABCDE-FGHJK-LMNPQ-RSTUV (~100 bits).
    static func generateRecoveryKey() -> String {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") // no ambiguous 0/O/1/I
        var bytes = Data(count: 20)
        _ = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 20, $0.baseAddress!) }
        var out = ""
        for (i, b) in bytes.enumerated() {
            out.append(alphabet[Int(b) % alphabet.count])
            if (i + 1) % 5 == 0 && i < bytes.count - 1 { out.append("-") }
        }
        return out
    }

    static func randomSalt() -> Data {
        var s = Data(count: 16)
        _ = s.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        return s
    }

    /// wrapKey (stays on device) + authKey (proves possession to the server).
    static func deriveRecovery(recoveryKey: String, salt: Data) -> RecoveryKeys {
        let norm = recoveryKey
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
            .uppercased()
        let master = pbkdf2SHA256(password: norm, salt: salt, iterations: iterations, keyLen: 32)
        let key = SymmetricKey(data: master)
        let wrap = HMAC<SHA256>.authenticationCode(for: Data("recovery-wrap".utf8), using: key)
        let auth = HMAC<SHA256>.authenticationCode(for: Data("recovery-auth".utf8), using: key)
        let authHex = Data(auth).map { String(format: "%02x", $0) }.joined()
        return RecoveryKeys(wrapKey: Data(wrap), authKey: authHex)
    }

    /// AES-GCM wrap → base64(iv(12) || ciphertext || tag).
    static func wrapBytes(_ plain: Data, wrapKey: Data) -> String {
        var iv = Data(count: 12)
        _ = iv.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 12, $0.baseAddress!) }
        let sealed = try! AES.GCM.seal(plain, using: SymmetricKey(data: wrapKey), nonce: try! AES.GCM.Nonce(data: iv))
        return (iv + sealed.ciphertext + sealed.tag).base64EncodedString()
    }

    /// Reverses wrapBytes; nil if the wrap key is wrong.
    static func unwrapBytes(_ blobB64: String, wrapKey: Data) -> Data? {
        guard let combined = Data(base64Encoded: blobB64), combined.count > 12 + 16 else { return nil }
        let iv = combined.prefix(12)
        let body = combined.dropFirst(12)
        let ct = body.prefix(body.count - 16)
        let tag = body.suffix(16)
        guard let box = try? AES.GCM.SealedBox(nonce: try AES.GCM.Nonce(data: iv), ciphertext: ct, tag: tag),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: wrapKey)) else { return nil }
        return plain
    }

    static func encryptWithKey(_ plaintext: String, encKey: Data) -> String {
        var salt = Data(count: 16)
        var iv = Data(count: 12)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        _ = iv.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 12, $0.baseAddress!) }

        let key = SymmetricKey(data: encKey)
        let sealed = try! AES.GCM.seal(Data(plaintext.utf8), using: key, nonce: try! AES.GCM.Nonce(data: iv))
        let combined = sealed.ciphertext + sealed.tag   // ciphertext || tag, matches web/Android

        let env: [String: Any] = [
            "app": "SentryKey",
            "version": 1,
            "encrypted": true,
            "kdf": "PBKDF2WithHmacSHA256",
            "iterations": iterations,
            "salt": salt.base64EncodedString(),
            "iv": iv.base64EncodedString(),
            "ciphertext": combined.base64EncodedString()
        ]
        let data = try! JSONSerialization.data(withJSONObject: env, options: [.prettyPrinted])
        return String(decoding: data, as: UTF8.self)
    }

    static func decryptWithKey(_ envelopeJson: String, encKey: Data) -> String? {
        guard let data = envelopeJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (obj["encrypted"] as? Bool) == true,
              let ivB64 = obj["iv"] as? String,
              let ctB64 = obj["ciphertext"] as? String,
              let iv = Data(base64Encoded: ivB64),
              let combined = Data(base64Encoded: ctB64),
              combined.count > 16 else { return nil }

        let ciphertext = combined.prefix(combined.count - 16)
        let tag = combined.suffix(16)
        let key = SymmetricKey(data: encKey)
        guard let box = try? AES.GCM.SealedBox(nonce: try AES.GCM.Nonce(data: iv), ciphertext: ciphertext, tag: tag),
              let plain = try? AES.GCM.open(box, using: key) else { return nil }
        return String(decoding: plain, as: UTF8.self)
    }

    // MARK: - PBKDF2 (CommonCrypto)

    private static func pbkdf2SHA256(password: String, salt: Data, iterations: Int, keyLen: Int) -> Data {
        var derived = Data(count: keyLen)
        let pw = Data(password.utf8)
        _ = derived.withUnsafeMutableBytes { dp in
            salt.withUnsafeBytes { sp in
                pw.withUnsafeBytes { pp in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        pp.bindMemory(to: Int8.self).baseAddress, pw.count,
                        sp.bindMemory(to: UInt8.self).baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        dp.bindMemory(to: UInt8.self).baseAddress, keyLen
                    )
                }
            }
        }
        return derived
    }
}
