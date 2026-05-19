# Security model

## Threat model

QuicLoc is a single-purpose personal-safety utility. The threats it tries to defend against, in priority order:

1. **A thief with physical access to the device.** Wants to disable tracking, read the whitelist, learn the PIN. The find-my-phone flow exists specifically for this scenario.
2. **A casual snoop with brief physical access** (unattended phone on a desk). Wants to add themselves to the whitelist or read the PIN.
3. **A network attacker.** Almost nothing to attack: QuicLoc makes no HTTP calls, has no backend, and sends only system SMS/MMS/notification replies through standard Android APIs.
4. **A rooted-device attacker who has obtained a full app-data backup.** Wants to read encrypted prefs or the backup blob offline.

What QuicLoc explicitly does **not** defend against:

- A nation-state-level adversary with custody of the unlocked device.
- An attacker who already controls the user's Google account *and* knows the device PIN (they can read the Auto Backup blob and decrypt it).
- A user who shares their PIN or passphrase with someone untrusted.

## Authentication boundaries

| Boundary | Mechanism | Bypassed by |
|---|---|---|
| UI access | `BiometricPrompt` with `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` | Successful biometric / device PIN |
| Background work (receivers, services) | None — runs without app auth | N/A by design — must work when phone is locked |
| Find-my-phone unlock | 6-digit PIN | 3 wrong attempts → photo capture + panic mode |

The background components (`SmsReceiver`, `NotificationListener`, `LocationReplyService`) do *not* require app auth. This is deliberate: the entire point is to answer location requests while the device is locked. Authentication only gates the **configuration UI**.

## On-device storage encryption

Two prefs files contain sensitive data:

| File | Class | Encryption |
|---|---|---|
| `quicloc_secure_prefs` | `WhitelistManager` | `EncryptedSharedPreferences` (AES-256-GCM values, AES-256-SIV keys, master key from Android Keystore) |
| `quicloc_history` | `RequestHistoryManager` | Same as above |

The master key is held in the hardware-backed Android Keystore (when available — falls back to software Keystore on older devices). The key never leaves the secure element on Pixels/most flagships.

Non-sensitive state (`quicloc_app_settings`, `quicloc_tracking_state`) is in plain `SharedPreferences`. The toggle, reminder-notification preference, and "already prompted" flags are not worth encrypting and *do* need to back up.

### Fallback path

If `EncryptedSharedPreferences.create()` throws (Keystore unavailable, Direct Boot before user unlock, etc.) we fall back to a plain `quicloc_secure_prefs_fallback` file so the app doesn't hard-crash. Data written to fallback is **not** encrypted at rest. This is rare in practice and self-heals after the next user unlock.

## Backup encryption

`EncryptedSharedPreferences` keys are bound to the device's Keystore and **do not survive a backup/restore to a new device**. So a second layer is needed for the backup story.

`BackupVault` writes a self-contained encrypted blob to `files/quicloc_backup.qlb`:

- KDF: PBKDF2-HMAC-SHA256, 600 000 iterations
- Cipher: AES-256-GCM
- Per-snapshot random salt (16 B) and IV (12 B)
- Key material: the user's PIN

See [BACKUP.md](BACKUP.md) for the full format and rationale.

### PIN entropy

A 6-digit PIN is 10⁶ ≈ 2²⁰ entropy. PBKDF2 with 600k iterations slows offline guessing to ~50 ms/attempt on a single mobile core; on a strong GPU rig, ~1 ms/attempt. Full brute force takes ~17 minutes.

This is weak by modern standards. We accept it because:

- The attacker also needs the blob, which lives in `files/` — only reachable through Auto Backup (Google E2E-encrypted with device PIN) or device-transfer (similarly protected).
- An attacker who already has the user's Google account *and* device PIN can already read the encrypted blob's plaintext-equivalent (the prefs themselves on the source device).
- A longer/alphanumeric "Backup Password" was considered and rejected — see [BACKUP.md](BACKUP.md) for the trade-off discussion.

## What's in each backup pathway

| Data | Plain SharedPreferences (auto-backed-up) | PIN-encrypted blob (auto-backed-up) | Not backed up |
|---|---|---|---|
| Master enable toggle | ✓ | | |
| Reminder notification toggle | ✓ | | |
| "Prompted" flags (device admin, phone hint, full-screen) | ✓ | | |
| Whitelist (numbers + display names) | | ✓ | |
| Starred contacts | | ✓ | |
| Your phone number | | ✓ | |
| Find-my-phone PIN | | ✓ | |
| Find-my-phone passphrase | | ✓ | |
| Onboarding-completed flag | | ✓ | |
| Request history | | | ✓ (privacy choice — see below) |
| Active tracking state | | | ✓ (runtime only) |
| Intruder photos (panic mode) | | | ✓ (forensic, ephemeral) |

History is intentionally excluded from backup. It's a log of who's been asking for your location; restoring it to a new device leaks that log into the user's backup chain. Since it's just a UI convenience, dropping it is the safer default.

## Code paths reviewed for security

- **`SmsReceiver`** — no parsing of attacker-controlled content beyond a case-insensitive equality check against the trigger word. No SQL, no `exec`, no reflection on input.
- **`NotificationListener`** — same. Sender name and message body are matched against the whitelist with simple string comparison + `PhoneNumberUtils.compare`.
- **`LocationHelper.sendSms`** — caller-supplied phone number flows into `SmsManager.sendTextMessage` which is parameterized; no template injection risk.
- **`BackupVault`** — only deserializes data the user previously serialized. JSON parsing is bounded by `JSONObject.optString` etc.; no class-loading from input.
- **PIN comparison** — currently uses `==` on `String`. Not constant-time. The threat is timing-side-channel attacks against the unlock screen; given the activity also captures a photo after 3 wrong PINs, this is acceptable.

## Things to remember if you change this code

- Don't move PIN or passphrase to plain prefs without re-considering the on-device threat model.
- Don't lower PBKDF2 iterations to "make snapshot faster". The cost is the point.
- Don't try to encrypt the backup with the Keystore master key. It defeats the whole purpose — Keystore keys don't transfer.
- Don't add network calls without updating the Play Store data-safety declaration and the privacy policy.
