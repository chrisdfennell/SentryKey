# SentryKey — iOS Companion

SwiftUI port of the Android companion. Stores 2FA accounts, shows live TOTP
codes, and syncs them to the Garmin watch app over BLE via the **Connect IQ
Mobile SDK for iOS**. The watch app is unchanged — it receives the same
`label:secret,label:secret` payload regardless of phone platform.

## Requirements

- **macOS + Xcode 15+** (iOS can't be built on Windows/Linux)
- An **iOS 16+ device** for testing (BLE + camera don't work in the Simulator)
- An **Apple Developer account** (free tier works for on-device installs)
- **Garmin Connect Mobile** installed on the test device
- **`ConnectIQ.framework`** from Garmin's [Connect IQ Mobile SDK](https://developer.garmin.com/connect-iq/sdk/)

## Project layout

```
companion-ios/SentryKey/
├── SentryKeyApp.swift        # @main, URL-scheme handling
├── Info.plist                # URL scheme + privacy keys (reference)
├── Crypto/
│   ├── Base32.swift          # RFC 4648 decoder (matches Android + watch)
│   └── TOTP.swift            # RFC 6238 via CryptoKit HMAC-SHA1
├── Model/
│   ├── TwoFactorAccount.swift
│   └── OtpAuthParser.swift   # otpauth:// QR parsing
├── Storage/
│   └── VaultStore.swift      # Keychain-backed vault
├── Sync/
│   └── GarminSyncManager.swift  # Connect IQ wrapper (the iOS-specific part)
└── Views/
    ├── ContentView.swift     # Dark, orange-accented dashboard
    ├── AccountRow.swift      # Live code + countdown ring
    ├── AddAccountView.swift  # Manual entry + QR scan
    └── QRScannerView.swift   # VisionKit DataScanner
```

## Setup

1. **Create the project:** Xcode → *New Project* → *App* (SwiftUI, Swift). Name it `SentryKey`.
2. **Add the sources:** drag the folders above into the target (✓ *Copy if needed*, ✓ add to target).
3. **Add the SDK:** drop `ConnectIQ.framework` into the project; under *General → Frameworks, Libraries, and Embedded Content* set it to **Embed & Sign**.
4. **Info.plist:** merge the keys from [`Info.plist`](SentryKey/Info.plist) — the `sentrykey` URL scheme, `gcm-ciq` query scheme, and camera/Bluetooth usage strings.
5. **Capabilities:** the `appUUID` in `GarminSyncManager.swift` already matches the watch (`a8d3e91b-…`); leave it.
6. **Run on a real device** and grant camera + Bluetooth permissions.

## Sync flow (iOS-specific)

Unlike Android, device selection bounces through Garmin Connect:

1. Tap **Device** → `showDeviceSelection()` opens Garmin Connect.
2. Pick the watch; Garmin Connect reopens SentryKey via `sentrykey://`.
3. `onOpenURL` → `handleOpenURL(_:)` parses the chosen device.
4. Tap **Sync Watch** → `sendMessage(...)` pushes the vault string.

## Notes

- Secrets live in the **Keychain** (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`), not UserDefaults.
- `ConnectIQ` API signatures can vary slightly by SDK version; if the compiler
  flags one (e.g. `sendMessage` or `IQApp` init), match it to your SDK headers.
