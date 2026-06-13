# Contributing to SentryKey

Thanks for your interest in improving SentryKey! This project has three parts —
a Garmin watch app, an Android companion, and an iOS companion — and
contributions to any of them are welcome.

## Table of contents

- [Code of conduct](#code-of-conduct)
- [Ways to contribute](#ways-to-contribute)
- [Development setup](#development-setup)
- [Branching & commits](#branching--commits)
- [Pull requests](#pull-requests)
- [Coding style](#coding-style)
- [Releases](#releases)
- [Security issues](#security-issues)

## Code of conduct

By participating, you agree to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to contribute

- 🐛 **Report bugs** — open a [bug report](https://github.com/chrisdfennell/SentryKey/issues/new/choose) with the crash log / steps to reproduce.
- 💡 **Suggest features** — open a feature request describing the use case.
- 🔧 **Send fixes** — pick an open issue or fix something you hit.
- ⌚ **Add device support** — extend the product list in [`manifest.xml`](manifest.xml).

## Development setup

### Garmin watch app (Monkey C)

- Install the [Connect IQ SDK](https://developer.garmin.com/connect-iq/sdk/) and create a developer key.
- Configure `build_config.json` with your `JavaHome` and `SdkDir` (created automatically on first run).

```powershell
./build.ps1 -Device fenix8solar51mm        # build a .prg
./build.ps1 -Device fenix8solar51mm -Run   # build + launch the simulator
./build.ps1 -Export                         # package a store .iq
```

### Android companion (Kotlin + Compose)

```bash
cd companion
./gradlew assembleDebug
```

### iOS companion (Swift + SwiftUI)

Requires macOS + Xcode. See [`companion-ios/README.md`](companion-ios/README.md).

## Branching & commits

- Branch off `main`: `fix/…`, `feat/…`, `docs/…`, `ci/…`.
- Use [Conventional Commits](https://www.conventionalcommits.org/) for messages:
  - `fix:` a bug fix
  - `feat:` a new feature
  - `docs:` documentation only
  - `chore:` / `ci:` / `refactor:` tooling and internals
- Keep commits focused and explain **why**, not just what.

## Pull requests

1. Make sure each component you touched **builds** (watch `.prg`, Android APK, iOS compile).
2. Fill out the PR template.
3. Reference any related issue (`Closes #123`).
4. For UI changes, attach a screenshot or photo (watch faces especially!).

CI (`SentryKey Build CI`) must pass before merge.

## Coding style

- **Monkey C:** match the existing style — typed declarations, defensive
  `try/catch` around external APIs, and pure-Monkey C crypto (no
  `Toybox.Cryptography` for hashing — see the note in `SentryKeyView.mc`).
- **Kotlin:** idiomatic Compose; keep TOTP/crypto logic pure and testable.
- **Swift:** SwiftUI + idiomatic Swift; secrets go in the Keychain.
- Keep the three TOTP implementations **behaviorally identical** (RFC 6238).
  If you change one, verify codes still match across phone and watch.

## Releases

Releases are tag-driven. Pushing a `v*` tag builds all targets and publishes a
GitHub Release. See [CHANGELOG.md](CHANGELOG.md).

```bash
git tag -a v1.0.0-beta.X -m "notes"
git push origin v1.0.0-beta.X
```

## Security issues

Please **do not** open public issues for vulnerabilities. Follow
[SECURITY.md](SECURITY.md) instead.
