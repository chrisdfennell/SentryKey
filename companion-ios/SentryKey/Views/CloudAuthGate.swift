import SwiftUI

private let brandOrange = Color(red: 1.0, green: 0.647, blue: 0.0)
private let bgDark = Color(red: 0.027, green: 0.031, blue: 0.043)

/// Startup gate: if not signed into the cloud (and offline wasn't chosen), show a
/// login screen so the app opens already connected. Sits inside AppLockGate, so
/// Face ID (when enabled) comes first. Mirrors the Android CloudAuthGate.
struct CloudAuthGate<Content: View>: View {
    @ObservedObject private var store = CloudStore.shared
    private let content: () -> Content

    init(@ViewBuilder content: @escaping () -> Content) { self.content = content }

    var body: some View {
        if store.isSignedIn || store.offlineChosen {
            content()
        } else {
            CloudAuthScreen()
        }
    }
}

struct CloudAuthScreen: View {
    @ObservedObject private var store = CloudStore.shared
    @State private var serverURL = CloudStore.shared.serverURL
    @State private var username = CloudStore.shared.username
    @State private var password = ""
    @State private var invite = ""
    @State private var isRegister = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ZStack {
            bgDark.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 14) {
                    Spacer(minLength: 60)
                    Text("🛡️").font(.system(size: 44))
                    Text("SentryKey").font(.largeTitle.bold()).foregroundStyle(brandOrange)
                    Text(isRegister ? "Create your encrypted cloud account" : "Sign in to sync your vault")
                        .font(.footnote).foregroundStyle(.secondary)

                    field("Server URL", text: $serverURL)
                    field("Username", text: $username)
                    secureField("Master password", text: $password)
                    if isRegister {
                        field("Invite code (if required)", text: $invite)
                    }

                    if let error { Text(error).font(.caption).foregroundStyle(.red) }

                    Button(action: submit) {
                        if busy { ProgressView().tint(bgDark) }
                        else { Text(isRegister ? "Create account" : "Sign in").bold().foregroundStyle(bgDark) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(brandOrange)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .disabled(busy)

                    Button(isRegister ? "Have an account? Sign in" : "New here? Create an account") {
                        isRegister.toggle()
                    }
                    .font(.footnote).foregroundStyle(brandOrange).disabled(busy)

                    Button("Use without cloud") {
                        store.offlineChosen = true
                        store.objectWillChange.send()
                    }
                    .font(.footnote).foregroundStyle(.secondary).disabled(busy)

                    Spacer(minLength: 40)
                }
                .padding(.horizontal, 28)
            }
        }
    }

    private func submit() {
        guard !serverURL.isEmpty, !username.isEmpty, !password.isEmpty else {
            error = "Enter server, username, and master password."
            return
        }
        busy = true; error = nil
        Task {
            do {
                try await CloudSync.signIn(url: serverURL, username: username, password: password,
                                           isRegister: isRegister, invite: invite)
                // CloudStore.isSignedIn flips -> the gate swaps to content.
            } catch {
                await MainActor.run { self.error = error.localizedDescription }
            }
            await MainActor.run { busy = false }
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        TextField(label, text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(14)
            .background(Color(red: 0.063, green: 0.071, blue: 0.102))
            .foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func secureField(_ label: String, text: Binding<String>) -> some View {
        SecureField(label, text: text)
            .padding(14)
            .background(Color(red: 0.063, green: 0.071, blue: 0.102))
            .foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
