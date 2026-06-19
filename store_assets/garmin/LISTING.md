# Garmin Connect IQ Store — Listing

> **LIVE:** <https://apps.garmin.com/apps/5b059618-6481-4a1e-a0f4-9acd1a2a4ac3>
>
> Connect IQ store fields are short. Targets below are conservative; if the portal
> rejects, trim to fit. App name is the `manifest.xml` name; the rest is set in the
> Connect IQ developer dashboard.

## Title / App name (≤ ~30 chars)
```
SentryKey
```
Alt (if a longer store title is allowed): `SentryKey — 2FA Authenticator`

## Description
```
SentryKey puts your two-factor authentication codes on your wrist.

It's a standalone TOTP authenticator for your Garmin watch. Add your 2FA accounts
in the free SentryKey companion app (Android or iPhone) by scanning their QR codes,
then sync them to your watch over Bluetooth. From then on your watch generates your
rotating 6-digit codes entirely on its own — no phone required.

• On-device codes — RFC 6238 TOTP computed on the watch itself.
• Premium dark interface with a countdown ring that turns red in the final seconds
  before a code rotates.
• Multiple accounts with auto-fitting labels and page navigation.
• Optional end-to-end encrypted sync — set a matching passphrase in the companion
  app and the watch settings.
• Restore to phone — send your vault back to a new or reset phone, gated by an
  on-watch confirmation.

Open source and privacy-respecting: your secrets stay on your devices. Requires the
free SentryKey companion app to add and sync accounts.

Not affiliated with or endorsed by Garmin.
```

## What's new (v1.0.0)
```
v1.0.0 — Initial release
• Standalone, on-device TOTP codes synced from the SentryKey phone app.
• Optional encrypted Bluetooth sync (set a matching passphrase on both ends).
• Restore-from-watch recovery to a new or reset phone.
```

## Image assets (this folder)
| File | Use | Spec met |
|---|---|---|
| hero_1440x720.png | Hero image | 1440×720, 246 KB (< 2048 KB) |
| cover_500x500.png | Cover image | 500×500, 138 KB (< 300 KB) |
| icon_128_rgba32.png | Device icon (high color) | 128×128, 32-bit RGBA |
| icon_128_rgb24.png | Device icon (24-bit) | 128×128, 24-bit RGB |
