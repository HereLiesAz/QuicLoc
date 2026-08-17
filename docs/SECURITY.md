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
| UI access | `BiometricPrompt` with `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL`, **or** the QuicLoc PIN | Successful biometric / device credential / QuicLoc PIN |
| Background work (receivers, services) | None — runs without app auth | N/A by design — must work when phone is locked |
| Find-my-phone unlock | The same QuicLoc PIN | 3 wrong attempts → photo capture + panic mode |

The background components (`SmsReceiver`, `NotificationListener`, `LocationReplyService`) do *not* require app auth. This is deliberate: the entire point is to answer location requests while the device is locked. Authentication only gates the **configuration UI**.

### The QuicLoc PIN as a second unlock method

There is one 6-digit QuicLoc PIN (`WhitelistManager.getPin`/`setPin`), and it does three jobs: it unlocks the settings UI, it is the PBKDF2 input for the backup blob, and — when find-my-phone is enabled — it stops tracking.

`MainActivity.promptBiometric` picks the gate:

| Device state | Gate |
|---|---|
| Has a lock screen | System prompt, with "Use QuicLoc PIN instead" as a fallback when a PIN is set |
| No lock screen, QuicLoc PIN set | The PIN, checked by `submitAppPin` (constant-time `MessageDigest.isEqual`) |
| No lock screen, no PIN | None — the user is let straight in, and the settings screen says so in as many words |

That last row is a deliberate non-lockout, unchanged from before; what *is* new is that a user on a phone without a lock screen now has a way out of it. The PIN is stored in `EncryptedSharedPreferences` rather than hashed, because `BackupVault` needs the raw value to derive the backup key — so its at-rest protection is the Keystore-backed master key, and its threat model is the same as the whitelist it protects. There is no reset path: forgetting the PIN on a device with no lock screen means reinstalling.

## On-device storage encryption

Sensitive data lives in `EncryptedSharedPreferences`:

| File | Class | Encryption |
|---|---|---|
| `quicloc_secure_prefs` | `WhitelistManager` | `EncryptedSharedPreferences` (AES-256-GCM values, AES-256-SIV keys, master key from Android Keystore) |
| `quicloc_history` | `RequestHistoryManager` | Same as above |
| `quicloc_diagnostics` | `DiagnosticLogManager` | Same as above |
| `quicloc_locnotice_prefs` | `GeofenceStore` | Same as above — place names, coordinates, radius, and which contacts get told are at least as sensitive as the whitelist itself (they reveal home/work addresses tied to specific people) |

The master key is held in the hardware-backed Android Keystore (when available — falls back to software Keystore on older devices). The key never leaves the secure element on Pixels/most flagships.

Non-sensitive state (`quicloc_app_settings`, `quicloc_tracking_state`, `quicloc_geofence_state`) is in plain `SharedPreferences`. The toggle, reminder-notification preference, "already prompted" flags, and Loc Notice's ephemeral per-geofence transition bookkeeping are not worth encrypting; the first group *does* need to back up, the second (like tracking state) deliberately does not — see the backup-pathway table below.

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

This is weak by modern standards, and got weaker in practice once Loc Notice shipped: the blob now also carries saved-place names and exact coordinates (home, work, anywhere else configured), not just contact numbers. We accept it because:

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
| Loc Notice master switch | ✓ | | |
| Loc Notice locations (name, coordinates, radius, contacts) | | ✓ | |
| Request history | | | ✓ (privacy choice — see below) |
| Diagnostic log | | | ✓ (privacy choice — see below) |
| Active tracking state | | | ✓ (runtime only) |
| Loc Notice transition state (last enter/exit per place) | | | ✓ (runtime only) |
| Intruder photos (panic mode) | | | ✓ (forensic, ephemeral) |

History and the diagnostic log are intentionally excluded from backup. Both are logs of who's been interacting with your location (who asked, or which app/number triggered what) — restoring either to a new device leaks that log into the user's backup chain. Since both are just in-app troubleshooting/UI conveniences, dropping them is the safer default.

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
