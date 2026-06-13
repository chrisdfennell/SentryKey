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

    @Published var statusText: String = "Ready to sync"
    @Published var selectedDevice: IQDevice?
    @Published var deviceConnected: Bool = false

    private var iqApp: IQApp?

    private override init() { super.init() }

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

        statusText = "Sending vault to \(device.friendlyName ?? "watch")…"
        ConnectIQ.sharedInstance().sendMessage(
            vaultString,
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
