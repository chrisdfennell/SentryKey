import Foundation
import CryptoKit
import CommonCrypto

/// Wrong passphrase or a corrupt/foreign backup file.
struct BadPasswordError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Passphrase-based backup encryption. Wire-compatible with the Android
/// `BackupCrypto` envelope, so a backup made on either platform restores on the
/// other: PBKDF2-HMAC-SHA256 (210k) → AES-256-GCM, in a self-describing JSON
/// envelope. The "ciphertext" field is GCM ciphertext WITH the 16-byte tag
/// appended (matching Java's `Cipher` GCM output).
enum BackupCrypto {
    private static let kdf = "PBKDF2WithHmacSHA256"
    private static let iterations = 210_000
    private static let keyBytes = 32      // AES-256
    private static let saltLength = 16
    private static let ivLength = 12
    private static let tagLength = 16

    static func encrypt(_ plaintext: String, password: String) throws -> String {
        var salt = Data(count: saltLength)
        var iv = Data(count: ivLength)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, saltLength, $0.baseAddress!) }
        _ = iv.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, ivLength, $0.baseAddress!) }

        let key = try deriveKey(password: password, salt: salt, iterations: iterations)
        let nonce = try AES.GCM.Nonce(data: iv)
        let sealed = try AES.GCM.seal(Data(plaintext.utf8), using: key, nonce: nonce)
        let combined = sealed.ciphertext + sealed.tag   // ciphertext || tag, mirrors Java

        let envelope: [String: Any] = [
            "app": "SentryKey",
            "version": 1,
            "encrypted": true,
            "kdf": kdf,
            "iterations": iterations,
            "salt": salt.base64EncodedString(),
            "iv": iv.base64EncodedString(),
            "ciphertext": combined.base64EncodedString()
        ]
        let data = try JSONSerialization.data(withJSONObject: envelope, options: [.prettyPrinted])
        return String(decoding: data, as: UTF8.self)
    }

    static func decrypt(_ envelope: String, password: String) throws -> String {
        guard let data = envelope.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let saltB64 = obj["salt"] as? String,
              let ivB64 = obj["iv"] as? String,
              let ctB64 = obj["ciphertext"] as? String,
              let salt = Data(base64Encoded: saltB64),
              let iv = Data(base64Encoded: ivB64),
              let combined = Data(base64Encoded: ctB64),
              combined.count > tagLength else {
            throw BadPasswordError(message: "Not a valid SentryKey backup file.")
        }

        let iters = (obj["iterations"] as? Int) ?? iterations
        let ciphertext = combined.prefix(combined.count - tagLength)
        let tag = combined.suffix(tagLength)

        do {
            let key = try deriveKey(password: password, salt: salt, iterations: iters)
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv),
                                            ciphertext: ciphertext, tag: tag)
            let plaintext = try AES.GCM.open(box, using: key)
            return String(decoding: plaintext, as: UTF8.self)
        } catch {
            // Wrong passphrase fails the GCM tag check and lands here.
            throw BadPasswordError(message: "Wrong passphrase or corrupt backup.")
        }
    }

    // MARK: - PBKDF2 (CommonCrypto)

    private static func deriveKey(password: String, salt: Data, iterations: Int) throws -> SymmetricKey {
        var derived = Data(count: keyBytes)
        let pwData = Data(password.utf8)
        let status = derived.withUnsafeMutableBytes { derivedPtr in
            salt.withUnsafeBytes { saltPtr in
                pwData.withUnsafeBytes { pwPtr in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        pwPtr.bindMemory(to: Int8.self).baseAddress, pwData.count,
                        saltPtr.bindMemory(to: UInt8.self).baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        derivedPtr.bindMemory(to: UInt8.self).baseAddress, keyBytes
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            throw BadPasswordError(message: "Key derivation failed.")
        }
        return SymmetricKey(data: derived)
    }
}
