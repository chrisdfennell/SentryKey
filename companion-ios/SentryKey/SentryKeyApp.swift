import SwiftUI

@main
struct SentryKeyApp: App {
    @StateObject private var vault = VaultStore()
    @StateObject private var sync = GarminSyncManager.shared

    init() {
        // Initialize the Connect IQ SDK with our custom URL scheme.
        GarminSyncManager.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            AppLockGate {
                // Cloud login on open (when not signed in); else straight into the app.
                CloudAuthGate {
                    ContentView()
                        .environmentObject(vault)
                        .environmentObject(sync)
                        // Silent cloud auto-backup whenever the vault changes.
                        .onChange(of: vault.accounts) { newAccounts in
                            CloudSync.autoBackup(accounts: newAccounts)
                        }
                }
            }
            .preferredColorScheme(.dark)
            .onOpenURL { url in
                // Garmin Connect returns the chosen device via our URL scheme.
                GarminSyncManager.shared.handleOpenURL(url)
            }
        }
    }
}
