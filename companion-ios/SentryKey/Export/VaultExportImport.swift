import Foundation
import SwiftUI
import CoreImage.CIFilterBuiltins

/// Standards-based (otpauth://) export & import, mirroring the Android version.
enum VaultExportImport {

    static func otpauthURI(for account: TwoFactorAccount) -> String {
        let issuer = account.label
            .components(separatedBy: ":").first?
            .components(separatedBy: " (").first?
            .trimmingCharacters(in: .whitespaces) ?? ""
        let label = account.label.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? account.label
        let secret = account.secret.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? account.secret
        var uri = "otpauth://totp/\(label)?secret=\(secret)"
        if !issuer.isEmpty, let enc = issuer.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
            uri += "&issuer=\(enc)"
        }
        return uri
    }

    static func exportJSON(_ accounts: [TwoFactorAccount]) -> String {
        let items = accounts.map {
            ["label": $0.label, "secret": $0.secret, "otpauth": otpauthURI(for: $0)]
        }
        let root: [String: Any] = ["app": "SentryKey", "version": 1, "accounts": items]
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted]),
              let str = String(data: data, encoding: .utf8) else { return "{}" }
        return str
    }

    /// Writes the plaintext backup to a temp file and returns its URL (for ShareLink).
    static func exportFile(_ accounts: [TwoFactorAccount]) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("sentrykey-vault.json")
        try? exportJSON(accounts).data(using: .utf8)?.write(to: url)
        return url
    }

    /// Serializes to a passphrase-encrypted backup string (see `BackupCrypto`).
    static func exportEncryptedJSON(_ accounts: [TwoFactorAccount], password: String) throws -> String {
        try BackupCrypto.encrypt(exportJSON(accounts), password: password)
    }

    /// Writes an encrypted backup to a temp file and returns its URL (for ShareLink).
    static func exportEncryptedFile(_ accounts: [TwoFactorAccount], password: String) -> URL? {
        guard let body = try? exportEncryptedJSON(accounts, password: password) else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("sentrykey-vault.skbackup")
        try? body.data(using: .utf8)?.write(to: url)
        return url
    }

    /// True if `text` is an encrypted SentryKey backup (needs a passphrase to import).
    static func isEncryptedBackup(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
        return (obj["encrypted"] as? Bool) == true
    }

    /// Decrypts an encrypted backup with `password`, then parses it. Throws
    /// `BadPasswordError` on a wrong passphrase or corrupt file.
    static func parseEncryptedImport(_ text: String, password: String) throws -> [TwoFactorAccount] {
        let plaintext = try BackupCrypto.decrypt(text, password: password)
        return parseImport(plaintext)
    }

    static func parseImport(_ text: String) -> [TwoFactorAccount] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{") || trimmed.hasPrefix("[") {
            return parseJSON(trimmed)
        }
        return trimmed
            .split(whereSeparator: { $0.isWhitespace })
            .map(String.init)
            .filter { $0.lowercased().hasPrefix("otpauth://") }
            .compactMap { OtpAuthParser.parse($0) }
    }

    private static func parseJSON(_ text: String) -> [TwoFactorAccount] {
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let array: [[String: Any]]
        if let dict = obj as? [String: Any], let accs = dict["accounts"] as? [[String: Any]] {
            array = accs
        } else if let arr = obj as? [[String: Any]] {
            array = arr
        } else {
            return []
        }
        return array.compactMap { item in
            if let secret = item["secret"] as? String, !secret.isEmpty {
                let label = (item["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Account"
                return TwoFactorAccount(label: label, secret: secret.uppercased())
            } else if let otp = item["otpauth"] as? String {
                return OtpAuthParser.parse(otp)
            }
            return nil
        }
    }

    static func qrImage(from string: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Sheet showing a scannable otpauth QR for one account.
struct AccountQRSheet: View {
    let account: TwoFactorAccount

    var body: some View {
        VStack(spacing: 16) {
            Text(account.label).font(.headline)
            if let img = VaultExportImport.qrImage(from: VaultExportImport.otpauthURI(for: account)) {
                Image(uiImage: img)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 240, height: 240)
            }
            Text("Scan in another authenticator app")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .presentationDetents([.medium])
    }
}
