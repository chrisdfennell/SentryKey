import Foundation
import Combine
import ConnectIQ

/// Wraps the Garmin Connect IQ Mobile SDK for iOS.
///
/// iOS differs from Android: device selection is a URL-scheme handshake that
/// bounces through the Garmin Connect app. Flow:
///   1. `start()` once at launch with the app's custom URL scheme.
///   2. `selectDevice()` opens Garmin Connect; the user picks a watch.
///   3. Garmin Connect returns via the custom scheme -> `handleOpenURL(_:)`.
///   4. `sync(vaultString:)` sends the payload to the selected device.
///
/// The watch app (UUID below) needs no changes — it already listens via
/// Communications.registerForPhoneAppMessages.
final class GarminSyncManager: NSObject, ObservableObject {
    static let shared = GarminSyncManager()

    // Must match manifest.xml <iq:application id="..."> and the Android appUuid.
    private let appUUID = UUID(uuidString: "a8d3e91b-cf52-4a87-bb4b-9800d0c85467")!
    private let urlScheme = "sentrykey" // also declared in Info.plist CFBundleURLTypes

    // Asks the watch to send its vault back (watch -> phone recovery). MUST match
    // PULL_REQUEST in the watch app and pullRequest in the Android companion.
    private let pullRequest = "__SENTRYKEY_PULL__"

    @Published var statusText: String = "Ready to sync"
    @Published var selectedDevice: IQDevice?
    @Published var deviceConnected: Bool = false

    /// Set to the vault string the watch returns from a recovery pull. The UI
    /// observes this, merges, then clears it back to nil.
    @Published var lastPulledVault: String?

    /// True when a sync passphrase is configured (drives UI + encryption).
    @Published var syncEncryptionEnabled: Bool = false

    private var iqApp: IQApp?

    private override init() {
        super.init()
        syncEncryptionEnabled = !getSyncPassphrase().isEmpty
    }

    // MARK: - Optional sync passphrase (Keychain)

    private let passService = "com.sentrykey.syncpass"
    private let passKey = "passphrase"

    func getSyncPassphrase() -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: passService,
            kSecAttrAccount as String: passKey,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data, let s = String(data: data, encoding: .utf8) else { return "" }
        return s
    }

    func setSyncPassphrase(_ passphrase: String) {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: passService,
            kSecAttrAccount as String: passKey
        ]
        SecItemDelete(query as CFDictionary)
        if !passphrase.isEmpty {
            query[kSecValueData as String] = Data(passphrase.utf8)
            query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            SecItemAdd(query as CFDictionary, nil)
        }
        syncEncryptionEnabled = !passphrase.isEmpty
    }

    /// Call once at app launch (e.g. from the App initializer).
    func start() {
        ConnectIQ.sharedInstance().initialize(withUrlScheme: urlScheme, uiOverrideDelegate: self)
        restoreSavedDevice()
    }

    /// Opens Garmin Connect for the user to choose a paired device.
    func selectDevice() {
        statusText = "Opening Garmin Connect…"
        ConnectIQ.sharedInstance().showDeviceSelection()
    }

    /// Handle the return URL from Garmin Connect (wire to SwiftUI `.onOpenURL`).
    func handleOpenURL(_ url: URL) {
        guard let devices = ConnectIQ.sharedInstance().parseDeviceSelectionResponse(from: url) as? [IQDevice],
              let device = devices.first else {
            statusText = "No device returned from Garmin Connect."
            return
        }
        bind(to: device)
        persistDevice(device)
    }

    /// Sends the vault string to the selected watch.
    func sync(vaultString: String) {
        guard let device = selectedDevice else {
            statusText = "Pick a Garmin device first."
            selectDevice()
            return
        }
        guard deviceConnected else {
            statusText = "\(device.friendlyName ?? "Watch") not connected. Open Garmin Connect."
            return
        }
        let app = iqApp ?? IQApp(uuid: appUUID, store: nil, device: device)
        iqApp = app

        // Encrypt the BLE payload if a sync passphrase is set (watch decrypts
        // with the same passphrase). Otherwise send plaintext (legacy).
        let pass = getSyncPassphrase()
        let payload = pass.isEmpty ? vaultString : SyncCrypto.encrypt(vaultString, passphrase: pass)

        statusText = "Sending vault to \(device.friendlyName ?? "watch")…"
        ConnectIQ.sharedInstance().sendMessage(
            payload,
            to: app,
            progress: nil
        ) { [weak self] result in
            DispatchQueue.main.async {
                if result == .success {
                    self?.statusText = "Synced to \(device.friendlyName ?? "watch")!"
                } else {
                    self?.statusText = "Sync failed: \(ConnectIQ.sharedInstance().log(forMessage: result) ?? "error")"
                }
            }
        }
    }

    /// Asks the watch to send its stored vault back to the phone. The watch
    /// gates this behind an on-watch confirmation, then transmits the vault
    /// string, which arrives via `receivedMessage(_:from:)`.
    func requestVaultFromWatch() {
        guard let device = selectedDevice else {
            statusText = "Pick a Garmin device first."
            selectDevice()
            return
        }
        guard deviceConnected else {
            statusText = "\(device.friendlyName ?? "Watch") not connected. Open Garmin Connect."
            return
        }
        let app = iqApp ?? IQApp(uuid: appUUID, store: nil, device: device)
        iqApp = app

        // Listen for the watch's reply before asking for it.
        ConnectIQ.sharedInstance().register(forAppMessages: app, delegate: self)

        statusText = "Confirm the recovery prompt on your watch…"
        ConnectIQ.sharedInstance().sendMessage(pullRequest, to: app, progress: nil) { [weak self] result in
            DispatchQueue.main.async {
                if result != .success {
                    self?.statusText = "Couldn't reach watch: \(ConnectIQ.sharedInstance().log(forMessage: result) ?? "error")"
                }
            }
        }
    }

    // MARK: - Device binding / status

    private func bind(to device: IQDevice) {
        selectedDevice = device
        iqApp = IQApp(uuid: appUUID, store: nil, device: device)
        ConnectIQ.sharedInstance().register(forDeviceEvents: device, delegate: self)
        let status = ConnectIQ.sharedInstance().getDeviceStatus(device)
        deviceConnected = (status == .connected)
        statusText = "\(device.friendlyName ?? "Device") selected."
    }

    // MARK: - Lightweight device persistence (UserDefaults, non-secret)

    private func persistDevice(_ device: IQDevice) {
        let dict: [String: String] = [
            "uuid": device.uuid?.uuidString ?? "",
            "name": device.friendlyName ?? "",
            "model": device.modelName ?? ""
        ]
        UserDefaults.standard.set(dict, forKey: "garminDevice")
    }

    private func restoreSavedDevice() {
        guard let dict = UserDefaults.standard.dictionary(forKey: "garminDevice") as? [String: String],
              let uuidString = dict["uuid"], let uuid = UUID(uuidString: uuidString) else {
            return
        }
        let device = IQDevice(id: uuid, modelName: dict["model"] ?? "", friendlyName: dict["name"] ?? "")
        bind(to: device)
    }
}

// MARK: - ConnectIQ delegates

extension GarminSyncManager: IQUIOverrideDelegate {
    /// Called when Garmin Connect Mobile isn't installed.
    func needsToInstallConnectMobile() {
        DispatchQueue.main.async {
            self.statusText = "Install the Garmin Connect app to sync."
        }
        ConnectIQ.sharedInstance().showAppStoreForConnectMobile()
    }
}

extension GarminSyncManager: IQDeviceEventDelegate {
    func deviceStatusChanged(_ device: IQDevice, status: IQDeviceStatus) {
        DispatchQueue.main.async {
            self.deviceConnected = (status == .connected)
        }
    }
}

extension GarminSyncManager: IQAppMessagesDelegate {
    /// The watch's reply to a recovery pull (the serialized vault string).
    func receivedMessage(_ message: Any!, from app: IQApp!) {
        let vaultString: String
        if let s = message as? String {
            vaultString = s
        } else if let arr = message as? [Any], let s = arr.first as? String {
            vaultString = s
        } else {
            vaultString = ""
        }
        ConnectIQ.sharedInstance().unregister(forAppMessages: app)
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            // The watch encrypts its reply when a sync passphrase is set.
            if SyncCrypto.isEncrypted(vaultString) {
                guard let decrypted = SyncCrypto.decrypt(vaultString, passphrase: self.getSyncPassphrase()) else {
                    self.statusText = "Watch sent an encrypted vault — set the matching sync passphrase and retry."
                    return
                }
                self.lastPulledVault = decrypted
            } else {
                self.lastPulledVault = vaultString
            }
        }
    }
}
