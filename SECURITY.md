# Security Policy

SentryKey handles **TOTP secrets** — the same seed material behind your 2FA
codes. We take security reports seriously.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Instead, report privately via one of:

- GitHub's [private vulnerability reporting](https://github.com/chrisdfennell/SentryKey/security/advisories/new) (preferred), or
- Email the maintainer at the address on their GitHub profile.

Please include:

- A description of the issue and its impact
- Steps to reproduce (or a proof of concept)
- Affected component(s): watch app, Android companion, or iOS companion
- Affected version / release tag

We'll acknowledge your report as soon as we reasonably can and keep you updated
on remediation. Please give us a reasonable window to fix the issue before any
public disclosure.

## Scope & threat model

SentryKey is a **personal/hobby project** and has **not** undergone a formal
security audit. Keep that in mind for high-value accounts, and always keep a
backup of your 2FA secrets elsewhere.

Notable design points:

- **Secrets at rest:** iOS stores secrets in the **Keychain**
  (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`); the watch keeps them in app
  **Storage**. The Android companion currently uses app-private SharedPreferences.
- **Sync payload:** the phone→watch sync string is
  `label:secret,label:secret`. Treat it as **secret material** — it grants the
  same access as the original QR codes. It travels over the Garmin Connect IQ
  BLE channel.
- **No network:** the apps don't transmit your secrets to any server.

## Supported versions

This project is pre-1.0; only the **latest release** receives fixes.
