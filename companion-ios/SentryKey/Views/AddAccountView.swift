import SwiftUI
import VisionKit

/// Add-account sheet: scan a QR or enter label + Base32 secret manually.
struct AddAccountView: View {
    @EnvironmentObject private var vault: VaultStore
    @Environment(\.dismiss) private var dismiss

    @State private var label = ""
    @State private var secret = ""
    @State private var showScanner = false
    @State private var error: String?

    private var scanningSupported: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Account") {
                    TextField("Label (e.g. GitHub)", text: $label)
                        .autocorrectionDisabled()
                    TextField("Base32 Secret", text: $secret)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.characters)
                        .onChange(of: secret) { _, newValue in
                            secret = newValue.uppercased().replacingOccurrences(of: " ", with: "")
                        }
                }

                if scanningSupported {
                    Section {
                        Button {
                            showScanner = true
                        } label: {
                            Label("Scan QR Code", systemImage: "qrcode.viewfinder")
                        }
                    }
                }

                if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
            }
            .navigationTitle("Add 2FA Account")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { addAccount() }
                }
            }
            .sheet(isPresented: $showScanner) {
                QRScannerView { scanned in
                    if GoogleAuthImport.isMigrationURI(scanned) {
                        // Google Authenticator bulk export — import all and close
                        let fresh = GoogleAuthImport.parse(scanned).filter { new in
                            !vault.accounts.contains { $0.label == new.label && $0.secret == new.secret }
                        }
                        fresh.forEach { vault.add($0) }
                        dismiss()
                    } else if let account = OtpAuthParser.parse(scanned) {
                        label = account.label
                        secret = account.secret
                    } else {
                        error = "Not a valid 2FA QR code."
                    }
                }
                .ignoresSafeArea()
            }
        }
    }

    private func addAccount() {
        let trimmedLabel = label.trimmingCharacters(in: .whitespaces)
        let trimmedSecret = secret.trimmingCharacters(in: .whitespaces)
        let valid = trimmedSecret.range(of: "^[A-Z2-7]+=*$", options: .regularExpression) != nil

        guard !trimmedLabel.isEmpty else { error = "Enter an account name."; return }
        guard !trimmedSecret.isEmpty, valid else { error = "Invalid secret (A–Z, 2–7 only)."; return }

        vault.add(TwoFactorAccount(label: trimmedLabel, secret: trimmedSecret))
        dismiss()
    }
}
