# SentryKey Privacy Policy

_Last updated: 2026-06-13_

SentryKey is a two-factor authentication (TOTP) app for Garmin watches with
Android and iOS companion apps. This policy explains what the apps do and don't
do with your data.

## Summary

**SentryKey does not collect, transmit, or sell any personal data.** Everything
stays on your devices.

## What data the app handles

- **2FA accounts (labels and secret keys).** You add these by scanning a QR code
  or entering them manually. They are used only on-device to generate your
  rotating codes.

## Where it's stored

- **Android:** in the app's private storage on your device.
- **iOS:** in the device **Keychain**.
- **Garmin watch:** in the app's on-device storage.

We do not operate any server. Your secrets are **never** sent to us or any third
party. The only transmission is the **direct Bluetooth sync** from your phone to
your own paired Garmin watch, via the Garmin Connect IQ SDK.

## Permissions and why

- **Camera** — to scan 2FA QR codes. Images are processed on-device and not stored
  or transmitted.
- **Bluetooth** — to sync your vault to your paired Garmin watch.
- **Internet** — only to check GitHub for app updates (debug/testing builds) and,
  on Android, to load service brand icons (favicons). No personal data is sent.

## Data sharing

- **Exporting** a backup or QR code is an action **you** initiate. Exported files
  contain your 2FA secrets in plaintext (the `otpauth://` standard). You are
  responsible for where you send or store them.

## Children's privacy

SentryKey is not directed to children and does not knowingly collect data from
anyone.

## Changes

Updates to this policy will be posted in this file in the project repository.

## Contact

Questions? Open an issue at
<https://github.com/chrisdfennell/SentryKey/issues> or contact the maintainer via
their GitHub profile.
