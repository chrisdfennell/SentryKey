import SwiftUI
import LocalAuthentication

/// Face ID / Touch ID / passcode gate, mirroring the Android biometric app lock.
/// Wrap the app's content in `AppLockGate { ... }`. Re-locks on background.
struct AppLockGate<Content: View>: View {
    @ViewBuilder var content: () -> Content

    @AppStorage("app_lock_enabled") private var enabled = false
    @State private var unlocked = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if unlocked || !enabled {
                content()
            } else {
                LockScreen(onUnlock: authenticate)
            }
        }
        .onAppear {
            if enabled { authenticate() } else { unlocked = true }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background && enabled { unlocked = false }
        }
    }

    private func authenticate() {
        let context = LAContext()
        var error: NSError?
        let policy: LAPolicy = .deviceOwnerAuthentication // biometrics, falls back to passcode

        // If no auth is available, don't trap the user out of their codes.
        guard context.canEvaluatePolicy(policy, error: &error) else {
            unlocked = true
            return
        }
        context.evaluatePolicy(policy, localizedReason: "Unlock SentryKey to view your codes") { success, _ in
            DispatchQueue.main.async {
                if success { unlocked = true }
            }
        }
    }
}

private struct LockScreen: View {
    let onUnlock: () -> Void

    var body: some View {
        ZStack {
            Color(red: 0.027, green: 0.031, blue: 0.043).ignoresSafeArea()
            VStack(spacing: 16) {
                Text("🔒").font(.system(size: 48))
                Text("SentryKey is locked")
                    .font(.headline)
                    .foregroundStyle(.white)
                Button("Unlock", action: onUnlock)
                    .buttonStyle(.borderedProminent)
                    .tint(Color(red: 1.0, green: 0.647, blue: 0.0))
            }
        }
    }
}
