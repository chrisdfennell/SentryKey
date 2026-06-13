# Store Submission Guide

SentryKey ships to three independent stores. This is the prep checklist for each.

- **Bundle/app ID:** `com.chrisdfennell.sentrykey`
- **Privacy policy:** host [PRIVACY.md](PRIVACY.md) somewhere public (e.g. GitHub
  Pages) and use that URL in the store listings.

---

## 🤖 Google Play (Android companion)

### Code prep (done in-repo)
- [x] `applicationId = com.chrisdfennell.sentrykey` (no more `com.example.*`).
- [x] **Self-updater is debug-only.** Play forbids downloading/installing APKs to
      self-update. `AUTO_UPDATE_TEST_MODE = BuildConfig.DEBUG`, and
      `REQUEST_INSTALL_PACKAGES` lives in `src/debug/AndroidManifest.xml`, so
      release/Play builds never include it. **Do not re-enable it for Play.**

### Still to do
- [ ] **Google Play Developer account** ($25 one-time).
- [ ] Set a real **versionName**/**versionCode** scheme; every upload needs a
      higher `versionCode`.
- [ ] Build a **signed release App Bundle**: `./gradlew bundleRelease` with a
      release keystore (not the committed debug one), and enroll in **Play App
      Signing**.
- [ ] Replace the debug `applicationId` icon set with final launcher icons.
- [ ] Play Console: **Data safety** form (declare "no data collected/shared"),
      content rating questionnaire, privacy policy URL, store listing +
      screenshots, target-audience.
- [ ] Confirm target SDK meets Play's current minimum.
- [ ] Consider renaming the code package from `com.example.sentrykey` for cleanliness
      (optional — Play only checks `applicationId`).

---

## 🍎 Apple App Store (iOS companion)

### Code prep (done in-repo)
- [x] `ITSAppUsesNonExemptEncryption = false` in Info.plist (standard crypto only).
- [x] Update checker disabled by default (`testingEnabled = false`).
- [x] Camera + Bluetooth usage strings present.

### Still to do
- [ ] **Apple Developer Program** ($99/yr).
- [ ] Create the **Xcode project**, add the `.swift` sources + `ConnectIQ.framework`
      (Embed & Sign). Set bundle ID `com.chrisdfennell.sentrykey`.
- [ ] App icon (1024px set) + launch screen.
- [ ] App Store Connect record; **App Privacy** label → "Data Not Collected".
- [ ] **Review notes:** explain the Garmin-watch sync (reviewers lack a watch) and
      note the app's TOTP works standalone on the phone; attach a demo video.
- [ ] Archive → upload → TestFlight → submit for review.

---

## ⌚ Garmin Connect IQ Store (watch app)

### Already in place
- [x] Persistent signing key, `.iq` packaging via `./build.ps1 -Export`.

### Still to do
- [ ] **Connect IQ developer account** (free) at the Connect IQ store dashboard.
- [ ] Store listing: name, description, category, screenshots for each device.
- [ ] Upload `bin/SentryKey.iq`, select supported devices, set as free.
- [ ] Privacy policy URL (same as above).

---

## Cross-cutting
- [ ] Decide final app **name/branding** and consistent icons across stores.
- [ ] Host [PRIVACY.md](PRIVACY.md) and reuse the URL everywhere.
- [ ] Because the app handles 2FA secrets, do a security pass before release
      (see [SECURITY.md](SECURITY.md)).
