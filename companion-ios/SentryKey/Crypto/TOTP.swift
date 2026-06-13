import Foundation
import CryptoKit

/// RFC 6238 TOTP. Uses CryptoKit's HMAC-SHA1 (no hand-rolled SHA-1 needed on
/// iOS, unlike the Garmin watch). Matches the Android `getTOTPCode` exactly.
enum TOTP {
    static func code(secret: String,
                     date: Date = Date(),
                     digits: Int = 6,
                     period: TimeInterval = 30) -> String {
        guard let key = Base32.decode(secret), !key.isEmpty else {
            return String(repeating: "0", count: digits)
        }

        let counter = UInt64(date.timeIntervalSince1970 / period)
        var bigEndian = counter.bigEndian
        let message = withUnsafeBytes(of: &bigEndian) { Data($0) }

        let mac = HMAC<Insecure.SHA1>.authenticationCode(
            for: message,
            using: SymmetricKey(data: key)
        )
        let hash = Data(mac)

        let offset = Int(hash[hash.count - 1] & 0x0F)
        let binary = (UInt32(hash[offset] & 0x7F) << 24)
                   | (UInt32(hash[offset + 1]) << 16)
                   | (UInt32(hash[offset + 2]) << 8)
                   | UInt32(hash[offset + 3])

        let modulo = UInt32(pow(10.0, Double(digits)))
        let otp = binary % modulo
        return String(format: "%0\(digits)d", otp)
    }

    /// Seconds remaining in the current step (for countdown UI).
    static func secondsRemaining(date: Date = Date(), period: TimeInterval = 30) -> Int {
        let elapsed = Int(date.timeIntervalSince1970) % Int(period)
        return Int(period) - elapsed
    }
}
