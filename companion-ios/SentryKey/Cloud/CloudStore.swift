import Foundation
import Combine

/// Persists the cloud session so the app opens already connected. The session
/// token and the derived encKey live in the Keychain; the master password is
/// never stored. Mirrors the Android VaultStorage cloud helpers.
final class CloudStore: ObservableObject {
    static let shared = CloudStore()

    @Published private(set) var isSignedIn: Bool = false

    /// Set during account recovery; ContentView merges it into the local vault on appear.
    var pendingRestoreJSON: String?

    private let service = "com.sentrykey.cloud"
    private let urlKey = "cloud_url"
    private let userKey = "cloud_user"
    private let offlineKey = "cloud_offline"

    private init() {
        isSignedIn = (token() != nil) && (encKey() != nil)
    }

    // MARK: - Non-secret prefs (UserDefaults)

    var serverURL: String {
        get { UserDefaults.standard.string(forKey: urlKey) ?? CloudBackupClient.defaultServerURL }
        set { UserDefaults.standard.set(newValue, forKey: urlKey) }
    }
    var username: String {
        get { UserDefaults.standard.string(forKey: userKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: userKey) }
    }
    /// User chose "use without cloud" at the startup gate.
    var offlineChosen: Bool {
        get { UserDefaults.standard.bool(forKey: offlineKey) }
        set { UserDefaults.standard.set(newValue, forKey: offlineKey) }
    }

    // MARK: - Secrets (Keychain)

    func token() -> String? { keychainGet("token").flatMap { String(data: $0, encoding: .utf8) } }
    func encKey() -> Data? { keychainGet("enckey") }

    func saveSession(url: String, username: String, token: String, encKey: Data) {
        serverURL = url
        self.username = username
        keychainSet("token", Data(token.utf8))
        keychainSet("enckey", encKey)
        offlineChosen = false
        isSignedIn = true
    }

    func signOut() {
        keychainDelete("token")
        keychainDelete("enckey")
        UserDefaults.standard.removeObject(forKey: "cloud_last_hash")
        isSignedIn = false
    }

    // MARK: - Sync bookkeeping (skip no-op uploads)

    var lastSyncHash: String {
        get { UserDefaults.standard.string(forKey: "cloud_last_hash") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "cloud_last_hash") }
    }

    // MARK: - Keychain primitives

    private func baseQuery(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    private func keychainSet(_ account: String, _ data: Data) {
        var q = baseQuery(account)
        SecItemDelete(q as CFDictionary)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    private func keychainGet(_ account: String) -> Data? {
        var q = baseQuery(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private func keychainDelete(_ account: String) {
        SecItemDelete(baseQuery(account) as CFDictionary)
    }
}
