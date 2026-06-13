import Foundation

/// Imports accounts from Google Authenticator's `otpauth-migration://` export QR.
/// Hand-parses the base64 protobuf (no dependency) and Base32-encodes the raw
/// secrets into our format. Mirrors the Android GoogleAuthImport.
enum GoogleAuthImport {

    static func isMigrationURI(_ raw: String) -> Bool {
        raw.lowercased().hasPrefix("otpauth-migration://")
    }

    static func parse(_ raw: String) -> [TwoFactorAccount] {
        guard isMigrationURI(raw), let range = raw.range(of: "data=") else { return [] }
        var encoded = String(raw[range.upperBound...])
        if let amp = encoded.firstIndex(of: "&") { encoded = String(encoded[..<amp]) }
        guard let decoded = encoded.removingPercentEncoding,
              let data = Data(base64Encoded: decoded) else { return [] }
        return parsePayload([UInt8](data))
    }

    private static func parsePayload(_ bytes: [UInt8]) -> [TwoFactorAccount] {
        var reader = Reader(data: bytes)
        var out: [TwoFactorAccount] = []
        while reader.hasMore {
            let (field, wire) = reader.tag()
            if field == 1 && wire == 2 {
                if let acc = parseOtpParameters(reader.bytes()) { out.append(acc) }
            } else {
                reader.skip(wire)
            }
        }
        return out
    }

    private static func parseOtpParameters(_ bytes: [UInt8]) -> TwoFactorAccount? {
        var reader = Reader(data: bytes)
        var secret: [UInt8]?
        var name = ""
        var issuer = ""
        while reader.hasMore {
            let (field, wire) = reader.tag()
            switch (field, wire) {
            case (1, 2): secret = reader.bytes()
            case (2, 2): name = String(decoding: reader.bytes(), as: UTF8.self)
            case (3, 2): issuer = String(decoding: reader.bytes(), as: UTF8.self)
            default: reader.skip(wire)
            }
        }
        guard let raw = secret else { return nil }
        let base32 = base32Encode(raw)
        if base32.isEmpty { return nil }

        let label: String
        if !issuer.isEmpty, !name.isEmpty, !name.localizedCaseInsensitiveContains(issuer) {
            label = "\(issuer):\(name)"
        } else if !name.isEmpty {
            label = name
        } else if !issuer.isEmpty {
            label = issuer
        } else {
            label = "Account"
        }
        return TwoFactorAccount(label: label, secret: base32)
    }

    static func base32Encode(_ data: [UInt8]) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var out = ""
        var buffer = 0
        var bits = 0
        for byte in data {
            buffer = (buffer << 8) | Int(byte)
            bits += 8
            while bits >= 5 {
                bits -= 5
                out.append(alphabet[(buffer >> bits) & 0x1F])
            }
            buffer &= (1 << bits) - 1
        }
        if bits > 0 {
            out.append(alphabet[(buffer << (5 - bits)) & 0x1F])
        }
        return out
    }

    /// Minimal protobuf wire-format reader (varint + length-delimited).
    private struct Reader {
        let data: [UInt8]
        var pos = 0
        var hasMore: Bool { pos < data.count }

        mutating func varint() -> Int {
            var result = 0
            var shift = 0
            while pos < data.count {
                let b = Int(data[pos]); pos += 1
                result |= (b & 0x7F) << shift
                if b & 0x80 == 0 { break }
                shift += 7
            }
            return result
        }

        mutating func tag() -> (Int, Int) {
            let t = varint()
            return (t >> 3, t & 0x7)
        }

        mutating func bytes() -> [UInt8] {
            let len = varint()
            let end = min(pos + len, data.count)
            let slice = Array(data[pos..<end])
            pos = end
            return slice
        }

        mutating func skip(_ wire: Int) {
            switch wire {
            case 0: _ = varint()
            case 1: pos += 8
            case 2: pos += varint()
            case 5: pos += 4
            default: break
            }
        }
    }
}
