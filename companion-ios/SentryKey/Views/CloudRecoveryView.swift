import SwiftUI

private let rOrange = Color(red: 1.0, green: 0.647, blue: 0.0)
private let rBg = Color(red: 0.027, green: 0.031, blue: 0.043)
private let rCard = Color(red: 0.063, green: 0.071, blue: 0.102)

/// "Set up account recovery": generate a one-time recovery key, wrap the
/// encryption key under it, and enroll with the server. Mirrors Android/web.
struct RecoverySetupView: View {
    @ObservedObject private var store = CloudStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var recoveryKey = CloudCrypto.generateRecoveryKey()
    @State private var email = ""
    @State private var phone = ""
    @State private var saved = false
    @State private var busy = false
    @State private var status: String?

    var body: some View {
        NavigationStack {
            ZStack {
                rBg.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Zero-knowledge means no one can reset your master password. Save this recovery key — it's your only way back in.")
                            .font(.footnote).foregroundStyle(.secondary)

                        Text(recoveryKey)
                            .font(.system(.title3, design: .monospaced).bold())
                            .foregroundStyle(rOrange)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(rCard)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .textSelection(.enabled)

                        Button {
                            UIPasteboard.general.string = recoveryKey
                            status = "Recovery key copied."
                        } label: { Text("Copy recovery key").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).tint(rOrange)

                        field("Email (optional — for a code 2nd factor later)", text: $email)
                        field("Phone (optional, e.g. +15551234567)", text: $phone)

                        Toggle("I've saved my recovery key somewhere safe.", isOn: $saved)
                            .font(.footnote).tint(rOrange)

                        Button(action: enable) {
                            if busy { ProgressView().tint(rBg) }
                            else { Text("Enable recovery").bold().foregroundStyle(rBg) }
                        }
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(saved ? rOrange : rOrange.opacity(0.4))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .disabled(!saved || busy)

                        if let status { Text(status).font(.caption).foregroundStyle(.secondary) }
                    }
                    .padding()
                }
            }
            .navigationTitle("Account Recovery")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Close") { dismiss() } } }
        }
    }

    private func enable() {
        guard let encKey = store.encKey() else { status = "Not signed in."; return }
        busy = true; status = nil
        Task {
            do {
                let salt = CloudCrypto.randomSalt()
                let rec = CloudCrypto.deriveRecovery(recoveryKey: recoveryKey, salt: salt)
                let blob = CloudCrypto.wrapBytes(encKey, wrapKey: rec.wrapKey)
                try await CloudBackupClient.setupRecovery(
                    baseURL: store.serverURL, token: store.token() ?? "",
                    salt: salt.base64EncodedString(), blob: blob, authKey: rec.authKey,
                    email: email.trimmingCharacters(in: .whitespaces),
                    phone: phone.trimmingCharacters(in: .whitespaces))
                await MainActor.run { status = "Recovery enabled."; busy = false }
            } catch {
                await MainActor.run { status = error.localizedDescription; busy = false }
            }
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        TextField(label, text: text)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .padding(12).background(rCard).foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

/// "Forgot master password?": prove the recovery key, unlock the vault, set a new
/// master password. On success the session is saved (the gate proceeds) and the
/// recovered vault is queued for ContentView to merge.
struct AccountRecoveryView: View {
    @ObservedObject private var store = CloudStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var serverURL = CloudStore.shared.serverURL
    @State private var username = CloudStore.shared.username
    @State private var otp = ""
    @State private var otpRequired = false
    @State private var otpStarted = false
    @State private var recoveryKey = ""
    @State private var newPass = ""
    @State private var newPass2 = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ZStack {
                rBg.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 12) {
                        Text("🛟").font(.system(size: 40))
                        Text("Recover account").font(.title.bold()).foregroundStyle(rOrange)
                        Text("Use the recovery key you saved.").font(.footnote).foregroundStyle(.secondary)

                        field("Server URL", text: $serverURL)
                        field("Username", text: $username)
                        if otpRequired { field("Verification code", text: $otp) }
                        field("Recovery key", text: $recoveryKey)
                        secure("New master password", text: $newPass)
                        secure("Confirm new password", text: $newPass2)

                        if let error { Text(error).font(.caption).foregroundStyle(.red) }

                        Button(action: recover) {
                            if busy { ProgressView().tint(rBg) }
                            else { Text("Recover").bold().foregroundStyle(rBg) }
                        }
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(rOrange).clipShape(RoundedRectangle(cornerRadius: 10))
                        .disabled(busy)

                        Button("Back to sign in") { dismiss() }
                            .font(.footnote).foregroundStyle(.secondary).disabled(busy)
                    }
                    .padding(.horizontal, 24).padding(.top, 40)
                }
            }
        }
    }

    private func recover() {
        guard !username.isEmpty else { error = "Enter your username."; return }
        busy = true; error = nil
        Task {
            do {
                // 1. Start — sends an OTP if a channel is configured.
                if !otpStarted {
                    let start = try await CloudBackupClient.recoveryStart(baseURL: serverURL, username: username)
                    await MainActor.run {
                        otpStarted = true
                        otpRequired = start.otpRequired
                        if start.otpRequired {
                            error = "Code sent\(start.sentTo.isEmpty ? "" : " to \(start.sentTo.joined(separator: ", "))"). Enter it, then tap Recover."
                            busy = false
                        }
                    }
                    if otpRequired { return }
                }
                guard !recoveryKey.isEmpty else { await MainActor.run { error = "Enter your recovery key."; busy = false }; return }
                guard newPass.count >= 8 else { await MainActor.run { error = "New password must be at least 8 characters."; busy = false }; return }
                guard newPass == newPass2 else { await MainActor.run { error = "Passwords don't match."; busy = false }; return }
                let otpVal = otpRequired ? otp.trimmingCharacters(in: .whitespaces) : nil

                // 2. Fetch wrapped material + vault.
                let mat = try await CloudBackupClient.recoveryFetch(baseURL: serverURL, username: username, otp: otpVal)

                // 3. Unwrap the encryption key.
                guard let salt = Data(base64Encoded: mat.salt) else { throw CloudError(message: "Corrupt recovery data.") }
                let rec = CloudCrypto.deriveRecovery(recoveryKey: recoveryKey, salt: salt)
                guard let oldEncKey = CloudCrypto.unwrapBytes(mat.blob, wrapKey: rec.wrapKey) else {
                    throw CloudError(message: "Invalid recovery key.")
                }

                // 4. Decrypt the existing vault (if any).
                var vaultPlain = "{\"app\":\"SentryKey\",\"version\":1,\"accounts\":[]}"
                if let v = mat.vault {
                    guard let p = CloudCrypto.decryptWithKey(v, encKey: oldEncKey) else { throw CloudError(message: "Invalid recovery key.") }
                    vaultPlain = p
                }

                // 5. Derive new keys, re-encrypt + re-wrap under the same recovery key.
                let nk = CloudCrypto.deriveUserKeys(username: username, password: newPass)
                let newVaultEnv = CloudCrypto.encryptWithKey(vaultPlain, encKey: nk.encKey)
                let newSalt = CloudCrypto.randomSalt()
                let newRec = CloudCrypto.deriveRecovery(recoveryKey: recoveryKey, salt: newSalt)
                let newBlob = CloudCrypto.wrapBytes(nk.encKey, wrapKey: newRec.wrapKey)

                // 6. Reset on the server.
                let token = try await CloudBackupClient.recoveryReset(
                    baseURL: serverURL, username: username, recoveryAuthKey: rec.authKey, otp: otpVal,
                    newAuthKey: nk.authKey,
                    newRecovery: (salt: newSalt.base64EncodedString(), blob: newBlob, authKey: newRec.authKey),
                    vault: newVaultEnv)

                // 7. Save the new session; queue the vault for the app to merge.
                await MainActor.run {
                    store.saveSession(url: serverURL, username: username, token: token, encKey: nk.encKey)
                    store.pendingRestoreJSON = vaultPlain
                    dismiss()
                }
            } catch {
                await MainActor.run { self.error = error.localizedDescription; busy = false }
            }
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        TextField(label, text: text)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .padding(12).background(rCard).foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
    private func secure(_ label: String, text: Binding<String>) -> some View {
        SecureField(label, text: text)
            .padding(12).background(rCard).foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
