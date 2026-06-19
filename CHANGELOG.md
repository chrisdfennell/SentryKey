# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Each `v*` tag publishes a
[GitHub Release](https://github.com/chrisdfennell/SentryKey/releases) with the
watch binaries and Android APK.

## [Unreleased]

## [v1.2.3] - 2026-06-19

### Changed
- **Open cloud registration.** Removed the invite-code / "Server Access Passphrase"
  gate from the cloud server, web dashboard, and both phone apps — anyone can create
  a cloud account. Self-hosters who want to limit abuse can enable reCAPTCHA instead
  (see Added). The `SERVER_ACCESS_PASSPHRASE` env var is gone.
- **SentryKey is free forever — donation-supported, no paid tiers.** Removed all
  paid-tier scaffolding added in v1.2.2: the `PLANS` map, the admin set-plan
  endpoint, and the plan UI in the dashboard and admin panel. Every account now
  gets the same generous cloud-backup retention. Admin moderation tools
  (suspend / delete / revoke sessions / stats) stay.
- **Dependencies.** Bumped GitHub Actions (`checkout`, `setup-java`,
  `upload-artifact`). Held `androidx.core` back from 1.19.0 (it requires
  compileSdk 37 / Android 17 preview) until we deliberately move to that SDK.

### Added
- **Optional bot protection (reCAPTCHA v3).** The website's registration, login, and
  account-recovery actions can be guarded by Google reCAPTCHA v3 — set
  `RECAPTCHA_SECRET` to enable (off by default, so self-hosting stays frictionless),
  with a configurable score floor (`RECAPTCHA_MIN_SCORE`). The native phone apps,
  which can't run reCAPTCHA, authenticate with a shared `APP_API_KEY` header baked
  into release builds. Disclosed in the privacy policy.
- **Donations.** GitHub Sponsors + Ko-fi links on the landing page, the dashboard
  account panel, and the README; `.github/FUNDING.yml` adds a "Sponsor" button to
  the repository.
- **Garmin Connect IQ Store listing is live.** The watch app is published and
  linked from the landing page and a README badge.

### Removed
- The standalone GitHub Pages site (`site/`) and its deploy workflow. The privacy
  policy is now served from `sentrykey.app/privacy.html`.

## [v1.2.2] - 2026-06-18

### Added
- **Conflict-safe multi-device cloud sync.** Uploads carry an `X-Base-Rev` so two
  devices editing the same vault auto-merge instead of silently overwriting each
  other; a stale base revision returns `409` with the current revision.
- **Admin panel for the managed service.** Operator view of account metadata and
  moderation (suspend / delete / revoke sessions / server stats), reachable from a
  sidebar Admin link. Vault contents stay encrypted and are never visible.
- **SQLite account + session store.** Accounts and sessions moved off
  `users.json` to SQLite (WAL) for atomic, crash-safe writes, with a one-time
  legacy import.

### Changed
- **Headless test gate in CI.** Server suite, Android JVM unit tests (cloud / sync
  / backup / recovery crypto via Robolectric), watch + Wear RFC 6238 TOTP vector
  tests, and a Playwright E2E for the zero-knowledge dashboard now gate the build.
- The Garmin build no longer fails when the signing key is absent (Dependabot /
  forked-PR runs skip the compile gracefully).

## [v1.2.1] - 2026-06-17

### Added
- CI auto-publishes the signed Play AAB to the closed testing track on every
  version tag; release notes are version-neutral for auto-publish.

## [v1.2.0] - 2026-06-17

### Added
- **Zero-knowledge account recovery** across web, Android, and iOS: a one-time
  recovery key wraps the vault key (the server can't decrypt), with an optional
  email (SMTP) / SMS (Twilio) second factor.
- **App-like web dashboard** — auto-loads your latest cloud vault on login,
  auto-saves encrypted on every change, full account management — plus a polished
  product landing page.
- **Frictionless auto-sync (Android)** — silent cloud backup + watch push on vault
  change, cloud login on app open (stay signed in, biometric-aware), and a
  home-screen TOTP widget (tap-to-reveal).
- **Wear OS companion app** with phone→Wear vault sync.
- **iOS cloud-backup catch-up** — zero-knowledge crypto, login-on-open, auto-sync.
- **100+ round Garmin watches supported** (was 4); CI auto-trims the device list
  to the watches its SDK knows. Connect IQ store listing copy + assets added.

### Fixed
- Web logo wordmark no longer split by the flex gap ("Sentry Key" → "SentryKey").

## [v1.1.0] - 2026-06-17

### Added
- **Zero-knowledge encrypted cloud backup (Android)**, byte-interoperable with the
  web dashboard.
- **Cloud backup server** with a turnkey Docker Compose + Caddy (automatic HTTPS)
  deploy.
- Store listing copy for Google Play, Apple App Store, and the Garmin Connect IQ
  Store, plus a refreshed submission checklist.

## [v1.0.0] - 2026-06-17

### Added
- **Vault encryption at rest** and optional end-to-end sync encryption; `play` /
  `github` build flavors split the self-update mechanism (Play In-App Updates vs.
  GitHub-Releases sideload).
- Privacy policy page and Dependabot coverage for Gradle (Android) and GitHub
  Actions.

### Fixed
- Watch `SyncCrypto` compile error (`utf8ArrayToString` needs `Array<Number>`).

## [v1.0.0-beta.20] - 2026-06-14

### Added
- **Restore from Watch** — recover the vault from the watch back to a new or reset
  phone.

## [v1.0.0-beta.19] - 2026-06-13

### Added
- **Import from Google Authenticator** (Android + iOS) via the
  `otpauth-migration://` export QR, with a multi-batch warning when the export is
  split across several QR codes.
- **Import a QR from an image** (Android) — decode a saved screenshot via ML Kit.
- **Drag-to-reorder accounts** (long-press), plus Rename and "Make first" actions
  that set which account the watch shows first.
- **Screenshot protection** (`FLAG_SECURE`) blocks screenshots / screen recording
  and hides codes in the app-switcher preview.
- Custom on-brand scan modals replacing the plain toasts.
- iOS app lock (Face ID / Touch ID / passcode) — parity with Android.

## [v1.0.0-beta.18] - 2026-06-13

### Added
- **App lock (Android)** — optional biometric / device-credential unlock before
  codes are shown, with re-lock when the app is backgrounded.

## [v1.0.0-beta.17] - 2026-06-13

### Added
- Export "Save to file" via the system document picker (Android), not just
  Share-to-app.

### Changed
- **Store-readiness prep.** Android `applicationId` is `com.fennell.sentrykey`;
  the in-app self-updater is debug-only (Play forbids APK self-updates); iOS gets
  `ITSAppUsesNonExemptEncryption=false`. Added privacy and store docs.

## [v1.0.0-beta.16] - 2026-06-13

### Added
- **Export / import (otpauth standard).** Export the vault as a JSON backup (with
  `otpauth://` URIs) or per-account QR codes scannable by any authenticator;
  import from a JSON/otpauth file or by scanning a QR. Android + iOS.

## [v1.0.0-beta.15] - 2026-06-13

### Added
- **In-app update check (testing).** Android polls GitHub Releases and can
  download + install a newer APK in-app; iOS shows a notify-only banner (the
  platform forbids self-install).
- **iOS companion app** (`companion-ios/`) — SwiftUI port: CryptoKit HMAC-SHA1
  TOTP, Base32 decoder, `otpauth://` QR parsing, Keychain-backed vault, VisionKit
  scanner, and a Connect IQ Mobile SDK sync wrapper.
- Project documentation (README, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT,
  issue/PR templates) and a CI iOS compile-check job (skips until the Xcode
  project is committed).

## [v1.0.0-beta.14] - 2026-06-13

### Changed
- **Premium dark UI for the watch.** Unified the MIP and AMOLED layouts into a
  single dark, brand-orange theme: a perimeter countdown ring that turns red in
  the final 5 seconds, soft-gray auto-fitting label, bold white code, a
  seconds-remaining readout, and page dots for multi-account vaults. Replaces
  the old white-background MIP layout.

## [v1.0.0-beta.13] - 2026-06-13

### Fixed
- Long account labels (e.g. `Discord:user@example.com`) no longer overflow the
  round screen — the label shrinks through MEDIUM→SMALL→TINY→XTINY to fit and
  ellipsizes if still too wide.

## [v1.0.0-beta.12] - 2026-06-13

### Fixed
- **Wrong codes for QR-scanned accounts.** Vault entries are now split on the
  *last* colon instead of the first, so labels containing a colon
  (`Discord:username`) no longer corrupt the secret. Removed a temporary debug
  readout used during diagnosis.

## [v1.0.0-beta.11] - 2026-06-13

### Added
- Temporary on-watch epoch (`t=`) readout used to isolate clock skew from an
  algorithm bug (removed in beta.12).

## [v1.0.0-beta.10] - 2026-06-13

### Fixed
- **Vault wiped on restart.** An empty `cryptoSeeds` settings string no longer
  overwrites the BLE-synced vault in storage on app launch.

## [v1.0.0-beta.9] - 2026-06-13

### Fixed
- **Crash on sync / code generation.** Replaced `Toybox.Cryptography`'s SHA-1
  (which faults uncatchably on fenix 8 firmware) with a self-contained pure
  Monkey C SHA-1 + HMAC-SHA1, validated bit-exact against reference vectors.

## [v1.0.0-beta.8] - 2026-06-13 and earlier

### Added
- Initial Garmin watch authenticator with on-device TOTP, BLE vault sync, and
  app-settings paste-in support.
- Support and release builds for fenix 8 / fenix 8 Solar (43/47/51mm).
- Persistent code signing and a premium app launcher icon.

### Changed
- Defensive programming and `try/catch` safety on the message receiver and TOTP
  generator.

[Unreleased]: https://github.com/chrisdfennell/SentryKey/compare/v1.2.2...HEAD
[v1.2.2]: https://github.com/chrisdfennell/SentryKey/compare/v1.2.1...v1.2.2
[v1.2.1]: https://github.com/chrisdfennell/SentryKey/compare/v1.2.0...v1.2.1
[v1.2.0]: https://github.com/chrisdfennell/SentryKey/compare/v1.1.0...v1.2.0
[v1.1.0]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0...v1.1.0
[v1.0.0]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0-beta.20...v1.0.0
[v1.0.0-beta.20]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0-beta.19...v1.0.0-beta.20
[v1.0.0-beta.19]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0-beta.18...v1.0.0-beta.19
[v1.0.0-beta.18]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0-beta.17...v1.0.0-beta.18
[v1.0.0-beta.17]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0-beta.16...v1.0.0-beta.17
[v1.0.0-beta.16]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0-beta.15...v1.0.0-beta.16
[v1.0.0-beta.15]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0-beta.14...v1.0.0-beta.15
[v1.0.0-beta.14]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.14
[v1.0.0-beta.13]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.13
[v1.0.0-beta.12]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.12
[v1.0.0-beta.11]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.11
[v1.0.0-beta.10]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.10
[v1.0.0-beta.9]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.9
[v1.0.0-beta.8]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.8
