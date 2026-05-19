# Backup & Restore

## Why a custom blob

Android Auto Backup happily ships the files in `data/data/.../files/` and `shared_prefs/` to Google Drive (E2E-encrypted with the device PIN). For *plain* SharedPreferences this is fine — restore on a new device works transparently.

For `EncryptedSharedPreferences` it doesn't work, because the master key lives in the Android Keystore and **does not transfer to a new device**. Restoring the file produces undecryptable garbage.

Options considered:

1. **Move everything to plain prefs.** Easiest, gives up on-device Keystore protection.
2. **Implement a custom `BackupAgent` that dumps decrypted data into a backup-time payload and re-encrypts on restore.** Complex; uses pre-API-23 backup APIs.
3. **Keep encrypted prefs, additionally write a self-contained encrypted blob into `files/`, where Auto Backup picks it up naturally. Restore is in-app, after Google's restore.** ← what we built.

Option 3 keeps `EncryptedSharedPreferences` doing its on-device job *and* gives a working restore path, all without a custom `BackupAgent`.

## File format

```
Offset  Size  Content
------  ----  -----------------------------------------
0       1     Version byte (currently 1)
1       16    PBKDF2 salt (random per snapshot)
17      12    AES-GCM IV (random per snapshot)
29      N     AES-256-GCM ciphertext + 16-byte auth tag
```

Total minimum size: 29 + 16 = 45 bytes (header + empty-ciphertext + GCM tag).

The plaintext is a JSON object:

```json
{
  "version": 1,
  "whitelist":   ["...", "..."],
  "starred":     ["..."],
  "my_number":   "...",
  "passphrase":  null | "...",
  "pin":         "...",
  "onboarding_completed": true | false
}
```

Notably **not** included: request history, app-settings toggles (those go via Auto Backup of plain prefs), runtime tracking state, panic photos.

## Crypto

- **Key derivation:** `PBKDF2WithHmacSHA256(pin.toCharArray(), salt, 600_000 iterations, 256-bit output)`
- **Cipher:** `AES/GCM/NoPadding` with the derived key, 128-bit auth tag
- **Salt:** 16 random bytes per snapshot
- **IV:** 12 random bytes per snapshot
- **Randomness:** `java.security.SecureRandom`

The salt + IV being fresh per snapshot means snapshotting twice with the same PIN produces different blobs — no leak from blob equality.

## Snapshot lifecycle

### Triggers

Every mutation in `WhitelistManager` calls `BackupVault.snapshotAsync(appContext)`:

- `addNumber`, `removeNumber`, `replaceAllNumbers`
- `toggleStarred`, `replaceStarred`
- `setMyNumber`
- `setPassphrase`, `clearPassphraseSync`
- `setPin`
- `setOnboardingCompleted`

### Debouncing

Snapshots are debounced through a `ScheduledExecutorService` with a 250 ms window:

- Each `snapshotAsync` call schedules a task 250 ms in the future.
- It also `cancel(false)`s the previously-scheduled task.
- If another mutation lands within 250 ms, that task gets cancelled and rescheduled.
- Result: a burst of mutations (e.g., the 6+ writes during a restore) produces a single snapshot at the end.

Worst case if the previous task already started running: one extra snapshot. Acceptable.

### No-PIN behavior

If there's no PIN set when `snapshot` runs, the existing backup file is deleted and we return without creating a new one. No PIN means no key, means no point storing a blob that would be more dangerous than useful.

### Atomicity

Write goes to `quicloc_backup.qlb.tmp` first, then `renameTo(...)` to the real name. Atomic on local filesystems. If `renameTo` fails (rare — cross-fs), we fall back to a copy + delete.

A process kill mid-write thus leaves either the previous valid blob or a `.tmp` that the next snapshot will overwrite. Never a half-written real file.

### Flush

`BackupVault.flush(context)` runs any pending snapshot synchronously on the caller's thread. Useful for lifecycle hooks like `onPause` if you want to guarantee the latest state is on disk before the process can be killed. Not wired up in `MainActivity` currently — relies on the debounce window expiring before the user backgrounds the app for a while.

## Restore lifecycle

### Auto-Backup path

1. User installs QuicLoc on a new device after Google account restore (or via Pixel Switch / Smart Switch).
2. Android restores the app's files including `files/quicloc_backup.qlb` (and the plain `quicloc_app_settings.xml`, which restores transparently).
3. On first `MainActivity.onCreate`, we check:
   - `whitelistManager.getNumbers().isEmpty()`
   - `whitelistManager.getPin() == null`
   - `BackupVault.isAvailable(this)` returns true
4. If all three, `showLaunchRestoreState` is set and `RestoreFromBackupDialog` overlays the UI.
5. User enters previous PIN. If correct → blob decrypts → `applyJson` writes to the encrypted prefs (which are now Keystore-encrypted by the *new* device's Keystore).

### Manual import path

1. User taps "Import" in the Backup & Restore card.
2. SAF `OpenDocument` picker.
3. URI stored in `pendingImportUriState`.
4. `RestoreFromBackupDialog` overlays with body "Restore from imported file?".
5. PIN flow identical to Auto-Backup path.

### Manual export

1. User taps "Export" in the Backup & Restore card.
2. SAF `CreateDocument("application/octet-stream")` with default name `quicloc-backup.qlb`.
3. Current blob copied to the chosen URI verbatim. No re-encryption — same bytes, same PIN.

## Error categories

`BackupVault.RestoreException` has a `category` field:

| Category | When | `isRecoverable` |
|---|---|---|
| `NO_BACKUP` | Internal-path: no file on disk | false |
| `IO_ERROR` | Could not open or read the URI | false |
| `TRUNCATED` | File shorter than 45 bytes | false |
| `UNSUPPORTED_VERSION` | Version byte ≠ 1 | false |
| `WRONG_PIN` | AES-GCM auth failed (almost always wrong PIN) | **true** |
| `PARSE_ERROR` | Decrypted bytes don't parse as our JSON shape | false |
| `APPLY_ERROR` | Parsed JSON but writing to prefs threw | false |

The dialog (`RestoreFromBackupDialog`) keys off `isRecoverable`:

- **Recoverable (wrong PIN):** Field stays editable. As soon as the user types, the error clears so retry feels clean.
- **Unrecoverable (any file-level error):** Field disables, Restore button disables, Skip → "Close". Retrying isn't going to fix a truncated or version-mismatched file.

## Edge cases & known limitations

- **Skipping the launch-time restore overwrites the blob.** The first mutation after skipping triggers a snapshot, which writes the new (empty/post-onboarding) state. If the user wanted the data back, they had to not skip.
- **Wrong PIN vs corrupted file are cryptographically indistinguishable.** We label it `WRONG_PIN` because that's by far the most common cause and the only one the user can fix. The message says *"Incorrect PIN. (If you're certain the PIN is right, the backup file may have been damaged in transit.)"*.
- **Backup file restored without prefs.** If someone manually imports a blob *and* the encrypted prefs are present, the restored data overwrites the current data. This is intentional — you might be restoring to overwrite a stale local state.
- **History is never in the blob.** If you want history backed up, add it to `collectAll` / `applyJson` in `BackupVault` and accept the privacy implication.

## Forward compatibility

- The version byte is the only forward-compat mechanism. To add a v2 format, bump the constant, update `restoreFromBytes` to handle both, and ensure new fields use `optString`/`optBoolean` so older blobs still parse.
- The JSON shape's `version` field is currently redundant with the file header but exists for future intra-version variation.
