import Foundation
import CryptoKit

/// Sign-in + silent auto-backup, mirroring the Android AutoSyncManager.
enum CloudSync {

    /// Derives keys, registers if [isRegister], logs in, and persists the session.
    @MainActor
    static func signIn(url: String, username: String, password: String, isRegister: Bool) async throws {
        let keys = CloudCrypto.deriveUserKeys(username: username, password: password)
        if isRegister {
            try await CloudBackupClient.register(baseURL: url, username: username, authKey: keys.authKey)
        }
        let token = try await CloudBackupClient.login(baseURL: url, username: username, authKey: keys.authKey)
        CloudStore.shared.saveSession(url: url, username: username, token: token, encKey: keys.encKey)
    }

    /// Encrypts + uploads the vault if signed in and it changed. Fire-and-forget.
    static func autoBackup(accounts: [TwoFactorAccount]) {
        let store = CloudStore.shared
        guard store.isSignedIn, let token = store.token(), let encKey = store.encKey() else { return }
        let json = vaultJSON(accounts)
        let hash = sha256Hex(json)
        if hash == store.lastSyncHash { return }

        Task {
            do {
                let envelope = CloudCrypto.encryptWithKey(json, encKey: encKey)
                _ = try await CloudBackupClient.uploadBackup(baseURL: store.serverURL, token: token, envelopeJson: envelope)
                await MainActor.run { store.lastSyncHash = hash }
            } catch let e as CloudError {
                let m = e.message.lowercased()
                if m.contains("unauthorized") || m.contains("session") {
                    await MainActor.run { store.signOut() }
                }
            } catch {
                // best-effort
            }
        }
    }

    /// `{app,version,accounts:[{label,secret}]}` — the shape the web + Android use.
    static func vaultJSON(_ accounts: [TwoFactorAccount]) -> String {
        let items = accounts.map { ["label": $0.label, "secret": $0.secret] }
        let root: [String: Any] = ["app": "SentryKey", "version": 1, "accounts": items]
        let data = (try? JSONSerialization.data(withJSONObject: root)) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    private static func sha256Hex(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
