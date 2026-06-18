# Store Submission Guide

SentryKey ships to three independent stores. This file holds the **listing copy**
and the **prep checklist** for each.

- **Android applicationId:** `com.fennell.sentrykey`
- **iOS bundle ID (planned):** `com.fennell.sentrykey` (set when the Xcode project is created)
- **Privacy policy (live):** <https://chrisdfennell.github.io/SentryKey/>
  (served from [`site/index.html`](site/index.html) via GitHub Pages; source content in [PRIVACY.md](PRIVACY.md))

---

## 🤖 Google Play (Android companion)

### Listing copy

**Short description** (70 / 80 chars):

```
Your 2FA codes, on your wrist — private TOTP authenticator for Garmin.
```

**Full description** (~2,650 / 4,000 chars):

```
SentryKey puts your two-factor authentication codes where you'll always have them — on your wrist.

SentryKey is a self-contained TOTP (time-based one-time password) authenticator for Garmin watches, and this Android app is its companion. Scan your 2FA QR codes on your phone, sync them to your watch over Bluetooth, and read your rotating 6-digit codes straight from your wrist. After the first sync, your watch generates codes entirely on its own — no phone required.

KEY FEATURES

• Codes on your wrist — RFC 6238 TOTP codes generated on the watch itself, even when your phone is nowhere nearby.
• Quick setup — scan any otpauth:// QR code with your phone's camera, or add accounts manually.
• One-tap Bluetooth sync — push your vault from phone to watch through the Garmin Connect IQ SDK.
• Import from other apps — bring accounts over from Google Authenticator and other authenticators using the standard otpauth format.
• Encrypted on every device — secrets are encrypted at rest with the Android Keystore on your phone, the Keychain on iOS, and on-device storage on your watch.
• Passphrase-protected backups — export an encrypted backup that's useless to anyone without your passphrase, or a standard plaintext backup for moving to another app. Your choice.
• Optional end-to-end sync encryption — set a sync passphrase and your vault is encrypted on its way to the watch over Bluetooth.
• Biometric app lock — require a fingerprint or face unlock to open the app.
• Privacy by design — codes are hidden from screenshots and the app switcher.
• Premium dark interface — a clean layout with a countdown ring that turns red in the final seconds before each code rotates.
• Restore from your watch — recover your vault from the watch back to your phone if you lose or replace your device.

YOUR PRIVACY

SentryKey does not collect, transmit, or sell any data. There are no accounts, no servers, and no tracking — everything stays on your devices. The only transmission is the direct Bluetooth sync between your phone and your own paired Garmin watch. Your 2FA secrets are never sent to us or any third party.

Full privacy policy: https://chrisdfennell.github.io/SentryKey/

SUPPORTED WATCHES

Built for the Garmin fenix 8 / tactix family (Connect IQ API 3.2+), including fenix 8 (AMOLED) and fenix 8 Solar (MIP) models. The companion SentryKey watch app must be installed from the Garmin Connect IQ Store.

GETTING STARTED

1. Install the SentryKey watch app on your Garmin from the Connect IQ Store.
2. Add your 2FA accounts in this app by scanning their QR codes.
3. Tap Sync to send them to your watch over Bluetooth.
4. Read your codes from your wrist — anytime, phone or not.

SentryKey is an independent project and is not affiliated with or endorsed by Garmin.
```

### Code prep (done in-repo)
- [x] `applicationId = com.fennell.sentrykey` (no more `com.example.*`).
- [x] **Distribution flavors** split the update mechanism: the **`play`** flavor uses
      Google Play In-App Updates and carries **no** install permission; the
      **`github`** flavor self-installs from GitHub Releases and is the only one with
      `REQUEST_INSTALL_PACKAGES`. Upload the **`play`** build to Play.
- [x] **Signed release App Bundle** via `./gradlew bundlePlayRelease`, signed from the
      `RELEASE_KEYSTORE_*` env/secrets (never the committed debug key). CI job
      `build-android-play` produces the `.aab` artifact.
- [x] **Auto-versioning**: `versionCode` = CI run number (monotonic — Play requires an
      increasing code per upload), `versionName` from the `vX.Y.Z` tag.
- [x] Privacy policy hosted (URL above).

### Status: live in closed testing
- [x] **Google Play Developer account** created.
- [x] **First upload** done — **Play App Signing** accepted; the app is live on the
      **Closed testing** track (`com.fennell.sentrykey`).
- [x] **Auto-publish from CI** — the `publish-play` job in [build.yml](.github/workflows/build.yml)
      uploads the signed AAB to the closed track on every `vX.Y.Z` tag. Requires the
      `PLAY_SERVICE_ACCOUNT_JSON` repo **secret** (a Play service-account JSON key with
      "Release apps to testing tracks"). Release notes come from
      [`distribution/whatsnew/whatsnew-en-US`](distribution/whatsnew/whatsnew-en-US).
      The track defaults to `alpha`; set a `PLAY_TRACK` repo **variable** to override if
      your closed track has a custom name.

### Still to do
- [ ] Replace placeholder launcher icons with final brand icons.
- [ ] Confirm **Data safety**, content rating, and target audience are complete before
      promoting to production.
- [ ] Confirm target SDK meets Play's current minimum.
- [ ] Note: store listing copy below still says "fenix 8 / tactix family" — update to
      "100+ round Garmin watches" when refreshing the listing.

---

## 🍎 Apple App Store (iOS companion)

### Listing copy

**App name** (23 / 30 chars): `SentryKey Authenticator`

**Subtitle** (23 / 30 chars): `2FA codes on your wrist`

**Promotional text** (~120 / 170 chars):

```
Your two-factor codes, generated right on your Garmin watch. Scan on your phone, sync over Bluetooth, and leave the phone behind.
```

**Keywords** (96 / 100 chars):

```
2FA,authenticator,TOTP,Garmin,watch,OTP,one-time,password,security,fenix,two-factor,verify,code
```

**Description** (~2,500 / 4,000 chars):

```
SentryKey puts your two-factor authentication codes where you'll always have them — on your wrist.

SentryKey is a self-contained TOTP (time-based one-time password) authenticator for Garmin watches, and this iPhone app is its companion. Scan your 2FA QR codes on your phone, sync them to your watch over Bluetooth, and read your rotating 6-digit codes straight from your wrist. After the first sync, your watch generates codes entirely on its own — no phone required.

KEY FEATURES

• Codes on your wrist — RFC 6238 TOTP codes generated on the watch itself, even when your iPhone is nowhere nearby.
• Quick setup — scan any otpauth:// QR code with your camera, or add accounts manually.
• One-tap Bluetooth sync — send your vault from iPhone to watch through the Garmin Connect IQ SDK.
• Import from other apps — bring accounts over from Google Authenticator and other authenticators using the standard otpauth format.
• Secrets stay protected — accounts are stored in the iOS Keychain on your phone and in on-device storage on your watch.
• Passphrase-protected backups — export an encrypted backup that's useless without your passphrase, or a standard plaintext backup for moving to another app.
• Optional end-to-end sync encryption — set a sync passphrase and your vault is encrypted on its way to the watch over Bluetooth.
• Face ID app lock — require Face ID or Touch ID to open the app.
• Restore from your watch — recover your vault from the watch back to your phone if you lose or replace your device.

YOUR PRIVACY

SentryKey does not collect, transmit, or sell any data. There are no accounts, no servers, and no tracking — everything stays on your devices. The only transmission is the direct Bluetooth sync between your phone and your own paired Garmin watch. Your 2FA secrets are never sent to us or any third party.

Full privacy policy: https://chrisdfennell.github.io/SentryKey/

SUPPORTED WATCHES

Built for the Garmin fenix 8 / tactix family (Connect IQ API 3.2+). The companion SentryKey watch app must be installed from the Garmin Connect IQ Store.

SentryKey is an independent project and is not affiliated with or endorsed by Garmin or Apple.
```

### Code prep (done in-repo)
- [x] `ITSAppUsesNonExemptEncryption = false` in Info.plist (standard crypto only).
- [x] Camera + Bluetooth usage strings present.
- [x] Face ID app lock + encrypted backups implemented.

### Still to do
- [ ] **Apple Developer Program** ($99/yr).
- [ ] Create the **Xcode project**, add the `.swift` sources + `ConnectIQ.framework`
      (Embed & Sign). Set bundle ID `com.fennell.sentrykey`.
- [ ] App icon (1024px set) + launch screen.
- [ ] App Store Connect record; **App Privacy** label → "Data Not Collected".
- [ ] **Review notes:** explain the Garmin-watch sync (reviewers lack a watch); note the
      companion role and attach a demo video.
- [ ] Archive → upload → TestFlight → submit for review.

---

## ⌚ Garmin Connect IQ Store (watch app)

### Listing copy

**App name:** `SentryKey`

**Short description** (one line):

```
Your 2FA codes on your wrist — a standalone TOTP authenticator for Garmin.
```

**Description:**

```
SentryKey is a standalone two-factor authentication (TOTP) authenticator for your Garmin watch.

Add your 2FA accounts in the free SentryKey companion app (Android or iPhone) by scanning their QR codes, then sync them to your watch over Bluetooth. From then on your watch generates your rotating 6-digit codes entirely on its own — no phone required.

• On-device RFC 6238 TOTP — codes are computed on the watch.
• A clean, dark interface with a countdown ring that turns red in the final seconds before a code rotates.
• Multiple accounts with auto-fitting labels and page navigation.
• Optional encrypted sync — set a matching passphrase in the companion app and the watch settings to encrypt the vault sent over Bluetooth.
• Restore-to-phone — send your vault back to a new or reset phone, gated by an on-watch confirmation.

Requires the free SentryKey companion app to add and sync accounts. Not affiliated with or endorsed by Garmin.
```

### Already in place
- [x] Persistent signing key, `.iq` packaging via `./build.ps1 -Export` and CI.
- [x] CI publishes `bin/SentryKey.iq` + per-device `.prg` to GitHub Releases on `v*` tags.

### Still to do
- [ ] **Connect IQ developer account** (free) at the Connect IQ store dashboard.
- [ ] Store listing: name, description (above), category, screenshots for each device.
- [ ] Upload `bin/SentryKey.iq`, select supported devices, set as free.
- [ ] Privacy policy URL (same as above).

---

## Cross-cutting
- [x] Privacy policy hosted on GitHub Pages and reused across all listings.
- [ ] Decide final consistent launcher/app icons across all three stores.
- [ ] Capture screenshots: phone app (Android + iOS) and watch faces per device.
- [ ] Because the app handles 2FA secrets, do a security pass before release
      (see [SECURITY.md](SECURITY.md)). Note the optional sync-crypto on watch/iOS is
      unit-pinned on Android but still needs device/simulator interop verification.
```
