# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses date-stamped pre-1.0 `v1.0.0-beta.N` tags. Each `v*` tag
publishes a [GitHub Release](https://github.com/chrisdfennell/SentryKey/releases)
with the watch binaries and Android APK.

## [Unreleased]

### Added
- **iOS companion app** (`companion-ios/`) — SwiftUI port: CryptoKit
  HMAC-SHA1 TOTP, Base32 decoder, `otpauth://` QR parsing, Keychain-backed
  vault, VisionKit scanner, and a Connect IQ Mobile SDK sync wrapper.
- **In-app update check (testing).** Android polls GitHub Releases and can
  download + install a newer APK in-app; iOS shows a notify-only banner linking
  to the release (the platform forbids self-install). Toggle via
  `AUTO_UPDATE_TEST_MODE` (Android) / `UpdateChecker` (iOS).
- Project documentation: top-level `README`, `CONTRIBUTING`, `SECURITY`,
  `CODE_OF_CONDUCT`, `CHANGELOG`, issue/PR templates.
- CI: iOS compile-check job (skips until the Xcode project is committed).

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

[Unreleased]: https://github.com/chrisdfennell/SentryKey/compare/v1.0.0-beta.14...HEAD
[v1.0.0-beta.14]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.14
[v1.0.0-beta.13]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.13
[v1.0.0-beta.12]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.12
[v1.0.0-beta.11]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.11
[v1.0.0-beta.10]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.10
[v1.0.0-beta.9]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.9
[v1.0.0-beta.8]: https://github.com/chrisdfennell/SentryKey/releases/tag/v1.0.0-beta.8
