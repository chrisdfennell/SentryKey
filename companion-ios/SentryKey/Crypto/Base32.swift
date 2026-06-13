import Foundation

/// RFC 4648 Base32 decoder, mirroring the Android `decodeBase32` and the
/// watch's pure Monkey C implementation so all three produce identical keys.
enum Base32 {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

    static func decode(_ input: String) -> Data? {
        let clean = input
            .uppercased()
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: "=", with: "")

        var bytes = [UInt8]()
        var buffer = 0
        var bitsLeft = 0

        for ch in clean {
            guard let value = alphabet.firstIndex(of: ch) else { continue } // skip invalid chars
            buffer = (buffer << 5) | value
            bitsLeft += 5
            if bitsLeft >= 8 {
                bitsLeft -= 8
                bytes.append(UInt8((buffer >> bitsLeft) & 0xFF))
            }
        }

        return Data(bytes)
    }
}
