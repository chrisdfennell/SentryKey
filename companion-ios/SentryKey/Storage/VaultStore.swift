import Foundation
import Combine

/// Keychain-backed vault. Stores the whole account list as one encrypted
/// Keychain item (secrets never touch UserDefaults). Publishes changes for SwiftUI.
final class VaultStore: ObservableObject {
    @Published private(set) var accounts: [TwoFactorAccount] = []

    private let service = "com.sentrykey.vault"
    private let key = "accounts"

    init() {
        accounts = load()
    }

    // MARK: - Mutations

    func add(_ account: TwoFactorAccount) {
        accounts.append(account)
        persist()
    }

    func delete(_ account: TwoFactorAccount) {
        accounts.removeAll { $0 == account }
        persist()
    }

    /// Serializes to the watch vault string: "label:secret,label:secret".
    /// The watch splits each entry on its LAST colon, so labels may contain colons.
    func toVaultString() -> String {
        accounts.map { "\($0.label):\($0.secret)" }.joined(separator: ",")
    }

    /// Parses the watch vault string "label:secret,label:secret" back into
    /// accounts (split each entry on its LAST colon, mirroring the watch). Used
    /// by watch -> phone recovery.
    static func parseVaultString(_ s: String) -> [TwoFactorAccount] {
        var out: [TwoFactorAccount] = []
        for rawPair in s.split(separator: ",") {
            let pair = rawPair.trimmingCharacters(in: .whitespaces)
            guard let colon = pair.lastIndex(of: ":") else { continue }
            let label = String(pair[..<colon]).trimmingCharacters(in: .whitespaces)
            let secret = String(pair[pair.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            if !label.isEmpty && !secret.isEmpty {
                out.append(TwoFactorAccount(label: label, secret: secret))
            }
        }
        return out
    }

    // MARK: - Persistence

    private func persist() {
        guard let data = try? JSONEncoder().encode(accounts) else { return }
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }

    private func load() -> [TwoFactorAccount] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let decoded = try? JSONDecoder().decode([TwoFactorAccount].self, from: data) else {
            return []
        }
        return decoded
    }
}
