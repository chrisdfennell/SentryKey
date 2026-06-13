import Foundation

/// One stored 2FA account. Mirrors the Android `TwoFactorAccount`.
struct TwoFactorAccount: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var label: String
    var secret: String
}
