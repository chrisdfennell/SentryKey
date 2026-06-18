import SwiftUI

/// Cloud account management: back up now, restore a backup, sign out. The startup
/// CloudAuthGate handles sign-in; this is for signed-in users (mirrors Android's
/// CloudBackupDialog restore/sign-out half).
struct CloudBackupSheet: View {
    @EnvironmentObject private var vault: VaultStore
    @ObservedObject private var store = CloudStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var backups: [CloudBackupClient.BackupMeta] = []
    @State private var status: String?
    @State private var busy = false
    @State private var showRecovery = false

    var body: some View {
        NavigationStack {
            List {
                Section("Account") {
                    Text(store.username.isEmpty ? "Signed in" : store.username)
                        .foregroundStyle(.secondary)
                    Button("Back up now") { backUp() }.disabled(busy)
                    Button("🛟 Set up account recovery") { showRecovery = true }
                    Button("Sign out", role: .destructive) {
                        store.signOut()
                        dismiss()
                    }
                }
                Section("Backups on server") {
                    if backups.isEmpty {
                        Text("No backups yet").foregroundStyle(.secondary)
                    }
                    ForEach(Array(backups.enumerated()), id: \.element.id) { idx, b in
                        Button {
                            restore(b.filename)
                        } label: {
                            HStack {
                                Text(b.timestamp).font(.footnote)
                                if idx == 0 { Spacer(); Text("latest").font(.caption2).foregroundStyle(.orange) }
                            }
                        }
                        .disabled(busy)
                    }
                }
                if let status {
                    Text(status).font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Cloud Backup")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .task { await loadList() }
            .sheet(isPresented: $showRecovery) { RecoverySetupView() }
        }
    }

    private func loadList() async {
        guard let token = store.token() else { return }
        do {
            let list = try await CloudBackupClient.listBackups(baseURL: store.serverURL, token: token)
            await MainActor.run { backups = list }
        } catch {
            await MainActor.run { status = error.localizedDescription }
        }
    }

    private func backUp() {
        guard let token = store.token(), let encKey = store.encKey() else { return }
        busy = true; status = "Backing up…"
        Task {
            do {
                let envelope = CloudCrypto.encryptWithKey(CloudSync.vaultJSON(vault.accounts), encKey: encKey)
                _ = try await CloudBackupClient.uploadBackup(baseURL: store.serverURL, token: token, envelopeJson: envelope)
                await loadList()
                await MainActor.run { status = "Backed up \(vault.accounts.count) account(s)."; busy = false }
            } catch {
                await MainActor.run { status = error.localizedDescription; busy = false }
            }
        }
    }

    private func restore(_ filename: String) {
        guard let token = store.token(), let encKey = store.encKey() else { return }
        busy = true; status = "Restoring…"
        Task {
            do {
                let envelope = try await CloudBackupClient.downloadBackup(baseURL: store.serverURL, token: token, filename: filename)
                guard let plaintext = CloudCrypto.decryptWithKey(envelope, encKey: encKey) else {
                    await MainActor.run { status = "Couldn't decrypt backup."; busy = false }
                    return
                }
                let imported = VaultExportImport.parseImport(plaintext)
                await MainActor.run {
                    for account in imported where !vault.accounts.contains(account) {
                        vault.add(account)
                    }
                    status = "Restored \(imported.count) account(s)."
                    busy = false
                }
            } catch {
                await MainActor.run { status = error.localizedDescription; busy = false }
            }
        }
    }
}
