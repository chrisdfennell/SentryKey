# Testing

SentryKey shares one set of **RFC 6238 known-answer vectors** across every
platform that generates codes, so they provably agree (same TOTP table on the
watch, the phone, Wear OS, and the server/web).

| Platform | What's tested | Run it |
|---|---|---|
| **Server** (Node) | Zero-knowledge crypto vectors; SQLite account/session API; `users.json` → SQLite migration | `cd server && npm test` |
| **Android app** | RFC 6238 TOTP, RFC 4648 Base32, vault sync-string parser (pure JVM — no emulator) | `cd companion && ./gradlew :app:testGithubDebugUnitTest` |
| **Wear OS app** | RFC 6238 TOTP, Base32, vault parser (pure JVM) | `cd companion && ./gradlew :wear:testDebugUnitTest` |
| **Garmin watch** | Pure Monkey C **SHA-1**, **HMAC-SHA1** (RFC 2202), **Base32**, **RFC 6238 TOTP** | `./build.ps1 -Test` (builds + runs in the Connect IQ simulator) |

## In CI
`.github/workflows/build.yml` runs the **server** and **Android + Wear** suites on
every push/PR and **gates the Android build and Play release** on them (a failing
unit test blocks the build). The Garmin **unit-test build is compiled** in CI to
catch test rot; the tests themselves are executed locally (`./build.ps1 -Test`)
because the Connect IQ simulator isn't headless-friendly.

## Not yet covered
- **Android at-rest / cloud crypto** — `CryptoManager` (Keystore) + `CloudCrypto`
  use Android-only APIs, so they live in an instrumented test
  (`./gradlew connectedDebugAndroidTest`, needs a device/emulator); not yet in CI.
- **iOS** — blocked on creating the Xcode project; then XCTest on a macOS runner.
- **Web dashboard UI** — `crypto.js` is exercised by the server suite; no Playwright
  end-to-end test yet.
