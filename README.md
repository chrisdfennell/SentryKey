<div align="center">

# 🛡️ SentryKey

### Your 2FA codes, on your wrist.

**SentryKey** is a self-contained TOTP (two-factor authentication) authenticator for **Garmin** watches, with companion apps for **Android** and **iOS**. Scan your 2FA QR codes on your phone, sync them to your watch over Bluetooth, and read your rotating codes right from your wrist — no phone required after sync.

[![Build](https://github.com/chrisdfennell/SentryKey/actions/workflows/build.yml/badge.svg)](https://github.com/chrisdfennell/SentryKey/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/chrisdfennell/SentryKey?include_prereleases&color=ff8800)](https://github.com/chrisdfennell/SentryKey/releases)
![Platform](https://img.shields.io/badge/platform-Garmin%20%7C%20Android%20%7C%20iOS-ff8800)
![Monkey C](https://img.shields.io/badge/watch-Monkey%20C-orange)
![Kotlin](https://img.shields.io/badge/android-Kotlin%20%2B%20Compose-7F52FF)
![Swift](https://img.shields.io/badge/ios-Swift%20%2B%20SwiftUI-F05138)

</div>

---

## ✨ Features

- ⌚ **Standalone watch authenticator** — RFC 6238 TOTP codes generated **on-device**, even with no phone nearby.
- 🔐 **Fully self-contained crypto** — Base32, HMAC-SHA1, and SHA-1 implemented in pure Monkey C (no flaky native dependency).
- 📷 **QR scanning** — add accounts by scanning `otpauth://` QR codes on your phone.
- 🔄 **One-tap Bluetooth sync** — push your vault from phone to watch via the Garmin Connect IQ SDK.
- 🎨 **Premium dark UI** — black background, brand-orange countdown ring that turns red in the final 5 seconds, auto-fitting account labels, and multi-account page dots.
- 🗄️ **Persistent & secure** — vault survives watch restarts; phone secrets live in Android Keystore-backed prefs / iOS Keychain.

---

## 🧩 Architecture

```
   ┌─────────────────────┐        ┌─────────────────────┐
   │   Android companion │        │    iOS companion    │
   │   (Kotlin + Compose)│        │   (Swift + SwiftUI) │
   └──────────┬──────────┘        └──────────┬──────────┘
              │   "label:secret,label:secret"  │
              └───────────────┬────────────────┘
                              │  Connect IQ SDK (BLE)
                              ▼
                   ┌──────────────────────┐
                   │  Garmin watch app    │
                   │     (Monkey C)       │
                   │  • Base32 decode     │
                   │  • HMAC-SHA1 / SHA-1 │
                   │  • TOTP every 30s    │
                   └──────────────────────┘
```

Both companion apps serialize the vault to a simple `label:secret,label:secret`
string and send it to the watch, which parses, stores, and computes codes
locally. The watch never needs the phone again until you add a new account.

---

## 📂 Repository layout

| Path | What it is |
|---|---|
| [`source/`](source/) | Garmin watch app (Monkey C) |
| [`resources/`](resources/) | Watch strings, settings, drawables |
| [`manifest.xml`](manifest.xml) | Watch app manifest (UUID, products, permissions) |
| [`companion/`](companion/) | Android companion (Kotlin + Jetpack Compose) |
| [`companion-ios/`](companion-ios/) | iOS companion (Swift + SwiftUI) |
| [`.github/workflows/build.yml`](.github/workflows/build.yml) | CI: build all targets + publish releases |

---

## ⌚ Supported watches

Built for the **fenix 8 / tactix** family (Connect IQ API 3.2+):

- fenix 8 43mm / 47mm (AMOLED)
- fenix 8 Solar 47mm / 51mm (MIP)

Other Connect IQ devices can be added in [`manifest.xml`](manifest.xml).

---

## 🚀 Building

### Garmin watch app

Requires the [Connect IQ SDK](https://developer.garmin.com/connect-iq/sdk/) and a developer key.

```powershell
# Build for a specific device
./build.ps1 -Device fenix8solar51mm

# Run in the Connect IQ simulator
./build.ps1 -Device fenix8solar51mm -Run

# Package a store .iq
./build.ps1 -Export
```

### Android companion

```bash
cd companion
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### iOS companion

Requires macOS + Xcode. See [`companion-ios/README.md`](companion-ios/README.md) for full setup (the Connect IQ Mobile SDK framework + URL-scheme handshake).

---

## 🔁 Releases

Every push of a `v*` tag triggers CI to build the watch binaries (all targets),
the Android APK, and publish them as a [GitHub Release](https://github.com/chrisdfennell/SentryKey/releases).

```bash
git tag -a v1.0.0-beta.x -m "release notes"
git push origin v1.0.0-beta.x
```

---

## 🔒 Security notes

- TOTP secrets are sensitive. On iOS they're stored in the **Keychain**; on the
  watch they live in app **Storage**. Treat the `label:secret` sync string as
  secret material — it grants the same access as the QR codes themselves.
- This is a personal/hobby project, **not** a security-audited product. Keep a
  backup of your 2FA secrets elsewhere.

---

## 🗺️ Roadmap

- [ ] Comma-safe label sanitization in the sync string
- [ ] Issuer-only label display option on the watch
- [ ] iOS TestFlight distribution

---

<div align="center">

Made with ⚡ for the wrist.

</div>
