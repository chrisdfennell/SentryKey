import SwiftUI

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
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAdd = true } label: {
                        Image(systemName: "plus.circle.fill").foregroundStyle(brandOrange)
                    }
                }
            }
            .sheet(isPresented: $showAdd) {
                AddAccountView()
            }
        }
        .tint(brandOrange)
        .onAppear { updates.start() }
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
        }
        .padding()
        .background(cardDark)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
    }
}
