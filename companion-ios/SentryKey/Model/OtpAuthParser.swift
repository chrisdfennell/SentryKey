import Foundation

/// Parses `otpauth://totp/...` URIs from scanned QR codes.
/// Mirrors the Android `parseOtpauthUri` (incl. issuer-prefixed labels).
enum OtpAuthParser {
    static func parse(_ raw: String) -> TwoFactorAccount? {
        guard let components = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "otpauth",
              components.host?.lowercased() == "totp" else {
            return nil
        }

        // secret (required)
        guard let secretItem = components.queryItems?.first(where: { $0.name == "secret" }),
              let rawSecret = secretItem.value, !rawSecret.isEmpty else {
            return nil
        }
        let secret = rawSecret
            .uppercased()
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")

        // label = path (strip leading "/")
        var label = components.path
        if label.hasPrefix("/") { label.removeFirst() }
        label = label.removingPercentEncoding ?? label

        // optional issuer
        if let issuer = components.queryItems?.first(where: { $0.name == "issuer" })?.value,
           !issuer.isEmpty,
           !label.lowercased().hasPrefix(issuer.lowercased()) {
            label = "\(issuer) (\(label))"
        }

        let trimmed = label.trimmingCharacters(in: .whitespaces)
        return TwoFactorAccount(label: trimmed.isEmpty ? "Account" : trimmed, secret: secret)
    }
}
