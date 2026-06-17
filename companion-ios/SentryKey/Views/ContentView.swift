import SwiftUI
import UIKit
import UniformTypeIdentifiers

private let brandOrange = Color(red: 1.0, green: 0.647, blue: 0.0) // #FFA500
private let bgDark = Color(red: 0.027, green: 0.031, blue: 0.043)  // #07080B
private let cardDark = Color(red: 0.063, green: 0.071, blue: 0.102) // #10121A

struct ContentView: View {
    @EnvironmentObject private var vault: VaultStore
    @EnvironmentObject private var sync: GarminSyncManager
    @StateObject private var updates = UpdateChecker.shared
    @Environment(\.openURL) private var openURL

    @State private var showAdd = false
    @State private var search = ""
    @State private var qrAccount: TwoFactorAccount?
    @State private var importing = false
    @State private var showCloud = false
    @State private var restoreMessage: String?
    @AppStorage("app_lock_enabled") private var appLockEnabled = false

    // Encrypted export: collect a passphrase, then share the encrypted file.
    @State private var showEncryptExport = false
    @State private var exportPassword = ""
    @State private var exportConfirm = ""
    @State private var shareItem: ShareItem?

    // Encrypted import: hold the picked ciphertext until a passphrase arrives.
    @State private var pendingEncryptedText: String?
    @State private var importPassword = ""

    // Generic error surface (bad passphrase, invalid export passphrase, …).
    @State private var errorMessage: String?

    // Optional BLE sync passphrase.
    @State private var showSyncPass = false
    @State private var syncPassInput = ""

    private var filtered: [TwoFactorAccount] {
        search.isEmpty ? vault.accounts
            : vault.accounts.filter { $0.label.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                bgDark.ignoresSafeArea()
                VStack(spacing: 16) {
                    if let tag = updates.newerTag {
                        updateBanner(tag: tag)
                    }
                    syncCard
                    if vault.accounts.isEmpty {
                        Spacer()
                        Text("No accounts yet. Tap + to add one.")
                            .foregroundStyle(.gray)
                        Spacer()
                    } else {
                        List {
                            ForEach(filtered) { account in
                                AccountRow(account: account)
                                    .listRowBackground(cardDark)
                                    .swipeActions {
                                        Button(role: .destructive) {
                                            vault.delete(account)
                                        } label: { Label("Delete", systemImage: "trash") }
                                    }
                                    .contextMenu {
                                        Button {
                                            qrAccount = account
                                        } label: { Label("Show QR", systemImage: "qrcode") }
                                    }
                            }
                        }
                        .scrollContentBackground(.hidden)
                        .searchable(text: $search, prompt: "Search accounts")
                    }
                }
                .padding(.top)
            }
            .navigationTitle("SentryKey Vault")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button {
                            exportPassword = ""; exportConfirm = ""
                            showEncryptExport = true
                        } label: { Label("Export (encrypted)", systemImage: "lock.doc") }
                        ShareLink(item: VaultExportImport.exportFile(vault.accounts) ?? URL(fileURLWithPath: "/dev/null")) {
                            Label("Export (plaintext)", systemImage: "doc")
                        }
                        Button {
                            importing = true
                        } label: { Label("Import vault", systemImage: "square.and.arrow.down") }
                        Button {
                            showCloud = true
                        } label: { Label("Cloud Backup", systemImage: "icloud") }
                        Button {
                            syncPassInput = sync.getSyncPassphrase()
                            showSyncPass = true
                        } label: {
                            Label(
                                sync.syncEncryptionEnabled ? "Sync encryption: ON" : "Sync encryption: off",
                                systemImage: sync.syncEncryptionEnabled ? "lock.fill" : "lock.open"
                            )
                        }
                        Toggle(isOn: $appLockEnabled) {
                            Label("App lock (Face ID)", systemImage: "faceid")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle").foregroundStyle(brandOrange)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAdd = true } label: {
                        Image(systemName: "plus.circle.fill").foregroundStyle(brandOrange)
                    }
                }
            }
            .sheet(isPresented: $showAdd) {
                AddAccountView()
            }
            .sheet(isPresented: $showCloud) {
                CloudBackupSheet().environmentObject(vault)
            }
            .sheet(item: $qrAccount) { account in
                AccountQRSheet(account: account)
            }
            .fileImporter(
                isPresented: $importing,
                // .data so encrypted .skbackup files (any extension) are pickable too.
                allowedContentTypes: [.json, .plainText, .text, .data],
                allowsMultipleSelection: false
            ) { result in
                handleImport(result)
            }
        }
        .tint(brandOrange)
        .onAppear { updates.start() }
        .onChange(of: sync.lastPulledVault) { pulledString in
            guard let pulledString else { return }
            sync.lastPulledVault = nil
            let pulled = VaultStore.parseVaultString(pulledString)
            if pulled.isEmpty {
                restoreMessage = "The watch sent an empty vault. Nothing to restore."
                return
            }
            var added = 0
            for account in pulled
            where !vault.accounts.contains(where: { $0.label == account.label && $0.secret == account.secret }) {
                vault.add(account)
                added += 1
            }
            restoreMessage = added == 0
                ? "Your vault is already up to date — all \(pulled.count) account(s) from the watch are already here."
                : "Restored \(added) account(s) from your watch."
        }
        .alert(
            "Restore from Watch",
            isPresented: Binding(get: { restoreMessage != nil }, set: { if !$0 { restoreMessage = nil } })
        ) {
            Button("OK", role: .cancel) { restoreMessage = nil }
        } message: {
            Text(restoreMessage ?? "")
        }
        .sheet(item: $shareItem) { item in
            ShareSheet(url: item.url)
        }
        .alert("Set backup passphrase", isPresented: $showEncryptExport) {
            SecureField("Passphrase", text: $exportPassword)
            SecureField("Confirm passphrase", text: $exportConfirm)
            Button("Cancel", role: .cancel) { exportPassword = ""; exportConfirm = "" }
            Button("Export") { startEncryptedExport() }
        } message: {
            Text("At least 6 characters. There's no way to recover a lost passphrase.")
        }
        .alert(
            "Enter backup passphrase",
            isPresented: Binding(get: { pendingEncryptedText != nil }, set: { if !$0 { pendingEncryptedText = nil; importPassword = "" } })
        ) {
            SecureField("Passphrase", text: $importPassword)
            Button("Cancel", role: .cancel) { pendingEncryptedText = nil; importPassword = "" }
            Button("Import") { finishEncryptedImport() }
        } message: {
            Text("This backup is encrypted. Enter its passphrase to import.")
        }
        .alert(
            "Couldn't complete",
            isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })
        ) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
        .alert("Sync passphrase", isPresented: $showSyncPass) {
            SecureField("Passphrase", text: $syncPassInput)
            Button("Cancel", role: .cancel) { syncPassInput = "" }
            Button("Save") {
                sync.setSyncPassphrase(syncPassInput.trimmingCharacters(in: .whitespaces))
                syncPassInput = ""
            }
        } message: {
            Text("Encrypts the vault sent over Bluetooth. Enter the SAME passphrase in the watch's app settings (Garmin Connect → SentryKey → Settings). Leave blank to disable.")
        }
    }

    private func startEncryptedExport() {
        let pw = exportPassword
        let confirm = exportConfirm
        exportPassword = ""; exportConfirm = ""
        guard pw.count >= 6 else { errorMessage = "Passphrase must be at least 6 characters."; return }
        guard pw == confirm else { errorMessage = "Passphrases don't match."; return }
        guard let url = VaultExportImport.exportEncryptedFile(vault.accounts, password: pw) else {
            errorMessage = "Couldn't create the encrypted backup."; return
        }
        shareItem = ShareItem(url: url)
    }

    private func finishEncryptedImport() {
        guard let text = pendingEncryptedText else { return }
        let pw = importPassword
        pendingEncryptedText = nil; importPassword = ""
        do {
            let imported = try VaultExportImport.parseEncryptedImport(text, password: pw)
            for account in imported
            where !vault.accounts.contains(where: { $0.label == account.label && $0.secret == account.secret }) {
                vault.add(account)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let url = urls.first else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        if VaultExportImport.isEncryptedBackup(text) {
            pendingEncryptedText = text
            return
        }
        for account in VaultExportImport.parseImport(text)
        where !vault.accounts.contains(where: { $0.label == account.label && $0.secret == account.secret }) {
            vault.add(account)
        }
    }

    private func updateBanner(tag: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Update available").font(.footnote.bold()).foregroundStyle(.green)
                Text(tag).font(.caption).foregroundStyle(.white)
            }
            Spacer()
            Button("View") {
                if let url = updates.releaseURL { openURL(url) }
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
        }
        .padding()
        .background(Color.green.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
    }

    private var syncCard: some View {
        VStack(spacing: 12) {
            HStack {
                Circle()
                    .fill(sync.deviceConnected ? .green : brandOrange)
                    .frame(width: 8, height: 8)
                Text(sync.statusText)
                    .font(.footnote)
                    .foregroundStyle(.white)
                Spacer()
            }
            HStack(spacing: 8) {
                Button {
                    sync.sync(vaultString: vault.toVaultString())
                } label: {
                    Label("Sync Watch", systemImage: "arrow.triangle.2.circlepath")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(brandOrange)

                Button {
                    sync.selectDevice()
                } label: {
                    Label("Device", systemImage: "applewatch")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }

            // Recover the vault FROM the watch (e.g. after losing this phone).
            // The watch prompts for confirmation before sending anything back.
            Button {
                sync.requestVaultFromWatch()
            } label: {
                Label("Restore from Watch", systemImage: "arrow.down.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(brandOrange)
        }
        .padding()
        .background(cardDark)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
    }
}

/// Identifiable wrapper so a generated file URL can drive a `.sheet(item:)`.
struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

/// Minimal UIActivityViewController bridge for sharing a generated backup file.
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
