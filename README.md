<div align="center">

# 🛡️ SentryKey

### Your 2FA codes, on your wrist.

**SentryKey** is a self-contained TOTP (two-factor authentication) authenticator for **Garmin** watches, with companion apps for **Android** and **iOS**. Scan your 2FA QR codes on your phone, sync them to your watch over Bluetooth, and read your rotating codes right from your wrist — no phone required after sync.

[![Build](https://github.com/chrisdfennell/SentryKey/actions/workflows/build.yml/badge.svg)](https://github.com/chrisdfennell/SentryKey/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/chrisdfennell/SentryKey?include_prereleases&color=ff8800)](https://github.com/chrisdfennell/SentryKey/releases)
[![Connect IQ Store](https://img.shields.io/badge/Garmin-Connect%20IQ%20Store-007cc3?logo=garmin&logoColor=white)](https://apps.garmin.com/apps/5b059618-6481-4a1e-a0f4-9acd1a2a4ac3)
![Platform](https://img.shields.io/badge/platform-Garmin%20%7C%20Android%20%7C%20iOS-ff8800)
![Monkey C](https://img.shields.io/badge/watch-Monkey%20C-orange)
![Kotlin](https://img.shields.io/badge/android-Kotlin%20%2B%20Compose-7F52FF)
![Swift](https://img.shields.io/badge/ios-Swift%20%2B%20SwiftUI-F05138)

</div>

---

## 🧪 Beta testers wanted!

I'm looking for testers for the **SentryKey Android app** — now in **closed beta on Google Play**. It's free; try it out, kick the tires, and tell me what breaks or feels confusing.

**Become a tester — 3 quick steps:**

1. **Join the testers group:** **[groups.google.com/g/sentrykey-testers](https://groups.google.com/g/sentrykey-testers)** → click **Join group**
2. **Opt in on Google Play:** **[play.google.com/apps/testing/com.fennell.sentrykey](https://play.google.com/apps/testing/com.fennell.sentrykey)** → tap **Become a tester**
3. **Install** SentryKey from the Play Store and give it a spin 🙌

> ⚠️ Do **both** steps 1 and 2 — joining the group alone doesn't opt you in, and the Play opt-in only works once you're a group member.

Hit a bug or have feedback? **[Open an issue](https://github.com/chrisdfennell/SentryKey/issues)** — it genuinely helps. 🙏

> Prefer to sideload? Grab the APK from the [latest release](https://github.com/chrisdfennell/SentryKey/releases/latest).

---

## ✨ Features

- ⌚ **Standalone watch authenticator** — RFC 6238 TOTP codes generated **on-device**, even with no phone nearby.
- 🔐 **Fully self-contained crypto** — Base32, HMAC-SHA1, and SHA-1 implemented in pure Monkey C (no flaky native dependency).
- 📷 **QR scanning** — add accounts by scanning `otpauth://` QR codes on your phone.
- 🔄 **One-tap Bluetooth sync** — push your vault from phone to watch via the Garmin Connect IQ SDK.
- 🎨 **Premium dark UI** — black background, brand-orange countdown ring that turns red in the final 5 seconds, auto-fitting account labels, and multi-account page dots.
- 🗄️ **Persistent & secure** — vault survives watch restarts; phone secrets are encrypted at rest (Android Keystore AES-256-GCM / iOS Keychain).
- 🔑 **Encrypted backups** — export a passphrase-locked backup (PBKDF2 + AES-256-GCM) that's useless without the passphrase; plaintext export still available with a warning.

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

Runs on **100+ round Garmin watches** (Connect IQ API 3.2+) — the whole UI is
resolution-independent (proportional countdown ring, round-screen-aware labels,
auto-scaling fonts), so it adapts from 218px to 454px screens. Square/rectangular
devices are intentionally excluded (the ring UI is built for round faces).

Families covered include **fenix** (5 Plus → 8), **epix**, **Forerunner**
(55/165/2xx/9xx…), **venu** (1/2/3/441/445), **vivoactive** (3→6), **Instinct**
(2/3/Crossover/E), **MARQ** (1/2), **Descent**, **Enduro**, **D2**, **Approach
S7x**, and the **tactix / legacy hero** series.

The full device list is in [`manifest.xml`](manifest.xml); add or remove products there.

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

The Android app ships in two **distribution flavors** that differ only in how
they self-update:

| Flavor | Update mechanism | Goes to |
|---|---|---|
| `play` | Google Play In-App Updates (no install permission) | Google Play |
| `github` | Self-installs the APK from GitHub Releases (sideload) | GitHub Releases |

```bash
cd companion
./gradlew assembleGithubDebug   # sideload build → app/build/outputs/apk/github/debug/
./gradlew assemblePlayDebug     # Play build (uses Play In-App Updates)
```

Only the `github` flavor carries the `REQUEST_INSTALL_PACKAGES` permission, so
the Play upload stays clean. CI builds and publishes the `github` flavor to
GitHub Releases.

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

- TOTP secrets are sensitive. On **Android** the vault is encrypted at rest with
  an AES-256-GCM key held in the **Android Keystore** (hardware-backed where the
  device supports it); on **iOS** they're stored in the **Keychain**. On the
  **watch** they live in app **Storage** in plaintext (Connect IQ has no keystore).
- **Backups** can be exported encrypted: the passphrase is stretched with
  PBKDF2-HMAC-SHA256 (210k iterations) and the vault sealed with AES-256-GCM.
  There's no passphrase recovery — lose it and the backup is gone. A plaintext
  JSON/`otpauth` export is still available behind an explicit warning for moving
  accounts to other authenticators.
- The `label:secret` BLE **sync string** can optionally be encrypted end-to-end:
  set a **sync passphrase** in the phone app and the same passphrase in the
  watch's Connect IQ settings. The payload is then sealed with an HMAC-SHA1-based
  passphrase scheme (PBKDF2 → keystream + MAC, marker `SKENC1:`) that all three
  platforms implement from the same primitive. With no passphrase set, sync stays
  plaintext (and should be treated as secret material). This is defense-in-depth
  over an already-link-encrypted BLE channel, not an audited protocol.
- This is a personal/hobby project, **not** a security-audited product. Keep a
  backup of your 2FA secrets elsewhere.

---

## 🗺️ Roadmap

- [ ] Comma-safe label sanitization in the sync string
- [ ] Issuer-only label display option on the watch
- [ ] iOS TestFlight distribution

## ❤️ Support

SentryKey is **free forever** — no paid tiers, no subscriptions, no ads. If it's
useful to you, a donation funds new features and keeps the managed cloud at
[sentrykey.app](https://sentrykey.app) running:

- 💖 [GitHub Sponsors](https://github.com/sponsors/chrisdfennell)
- ☕ [Ko-fi](https://ko-fi.com/chrisdfennell)

Not a donor? Starring the repo and filing good bug reports helps just as much.

---

<div align="center">

Made with ⚡ for the wrist.

</div>
