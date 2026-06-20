# Architecture

## At a glance

```
                 ┌───────────────────────┐
                 │      Trigger source   │
                 │ ──────────────────── │
                 │  SmsReceiver         │
                 │  NotificationListener│
                 │  Widget (1/2/3/4 tap)│
                 └──────────┬────────────┘
                            │  AppSettings.isEnabled?
                            ▼
                 ┌───────────────────────┐
                 │   WhitelistManager    │
                 │   isWhitelisted(...)  │
                 └──────────┬────────────┘
                            │  match
                            ▼
            ┌───────────────────────────────┐
            │  LocationReplyService (FGS)   │ ← single trigger path
            │  TrackingService     (FGS)    │ ← passphrase / panic
            └──────────┬────────────────────┘
                       │ uses
                       ▼
            ┌──────────────────────┐
            │   LocationHelper     │ 3-stage GPS fetch w/ 30s deadline
            └──────────┬───────────┘
                       │
                       ▼
            ┌──────────────────────┐
            │   SmsManager  OR     │
            │   Notification.Action│ inline reply via RemoteInput
            │   MMS (panic photo)  │
            └──────────────────────┘
                       │
                       ▼
                 RequestHistoryManager.record(...)
                 BackupVault.snapshotAsync(...)  (debounced 250 ms)
```

## Component map

### Triggers (entry points)

| Component | File | Purpose |
|---|---|---|
| `SmsReceiver` | [SmsReceiver.kt](../app/src/main/java/com/hereliesaz/quicloc/SmsReceiver.kt) | `BroadcastReceiver` for `SMS_RECEIVED`. Reassembles multipart SMS, checks for `loc` / `quicloc` / `loc <passphrase>`, dispatches to the right FGS. |
| `NotificationListener` | [NotificationListener.kt](../app/src/main/java/com/hereliesaz/quicloc/NotificationListener.kt) | `NotificationListenerService` for chat apps. Uses `Notification.MessagingStyle` to pull per-message sender + body. Has a 60 s dedupe window because messaging apps re-post notifications constantly. |
| `QuicLocWidgetProvider` | [QuicLocWidgetProvider.kt](../app/src/main/java/com/hereliesaz/quicloc/QuicLocWidgetProvider.kt) | Home-screen widget. Each tap fires `LocationReplyService.ACTION_WIDGET_TAP`; the service counts taps within 400 ms. |
| `ToggleReceiver` | [ToggleReceiver.kt](../app/src/main/java/com/hereliesaz/quicloc/ToggleReceiver.kt) | The "Enable/Disable" action button on the reminder notification. |
| `BootReceiver` | [BootReceiver.kt](../app/src/main/java/com/hereliesaz/quicloc/BootReceiver.kt) | Re-posts the reminder notification after reboot. |

### Foreground services

| Service | File | Type | When started |
|---|---|---|---|
| `LocationReplyService` | [LocationReplyService.kt](../app/src/main/java/com/hereliesaz/quicloc/LocationReplyService.kt) | `location` | Whitelisted contact sent the trigger word, or the user tapped the widget. One-shot: fetch → reply → `stopSelf`. |
| `TrackingService` | [TrackingService.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/TrackingService.kt) | `location` | Passphrase fired. Lives in the on-demand `:feature_findmyphone` module; started from the base via `FindMyPhone.trigger` (`ComponentName`). Persistent: posts location every 5 min (1 min in panic mode), survives restart via `quicloc_tracking_state` prefs. |

### UI

| Activity | File | Role |
|---|---|---|
| `MainActivity` | [MainActivity.kt](../app/src/main/java/com/hereliesaz/quicloc/MainActivity.kt) | Single-activity Compose app. Navigation is a `sealed class MainView` (Config / History / TutorialsHub / TutorialDetail). Biometric gate on resume. |
| `TrackingLockActivity` | [TrackingLockActivity.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/TrackingLockActivity.kt) | In the on-demand `:feature_findmyphone` module. Fallback cover-screen when Device Admin isn't granted. After 3 wrong PINs, captures an intruder photo via the module-internal [IntruderCamera](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/IntruderCamera.kt) (no photo if CAMERA wasn't granted); otherwise locks without a photo. |
| `WidgetHelpActivity` | [WidgetHelpActivity.kt](../app/src/main/java/com/hereliesaz/quicloc/WidgetHelpActivity.kt) | Transparent activity that pops up when the widget is tapped exactly once. Three-step "tap-to-advance" hint. |

### Data layer

| Component | File | Storage |
|---|---|---|
| `WhitelistManager` | [WhitelistManager.kt](../app/src/main/java/com/hereliesaz/quicloc/WhitelistManager.kt) | `EncryptedSharedPreferences` (`quicloc_secure_prefs`). Holds whitelist, starred, my_number, passphrase, PIN, onboarding flag. Mutations trigger `BackupVault.snapshotAsync`. |
| `AppSettings` | [AppSettings.kt](../app/src/main/java/com/hereliesaz/quicloc/AppSettings.kt) | Plain `SharedPreferences` (`quicloc_app_settings`). Holds non-sensitive toggles + "already prompted" flags. Auto-backs-up via Android Auto Backup. |
| `RequestHistoryManager` | [RequestHistoryManager.kt](../app/src/main/java/com/hereliesaz/quicloc/RequestHistoryManager.kt) | `EncryptedSharedPreferences` (`quicloc_history`). JSON-serialized list of recent location-request events. |
| `BackupVault` | [BackupVault.kt](../app/src/main/java/com/hereliesaz/quicloc/BackupVault.kt) | PIN-encrypted blob at `files/quicloc_backup.qlb`. Auto-backs-up via Android Auto Backup (the file is inside the app's `files/` dir). |

### Cross-cutting helpers

| Helper | File | Role |
|---|---|---|
| `LocationHelper` | [LocationHelper.kt](../app/src/main/java/com/hereliesaz/quicloc/LocationHelper.kt) | Three-stage GPS fetch (current → cached → forced) with a single 30 s deadline. Also has the SMS-send and notification-reply primitives. |
| `BiometricHelper` | [BiometricHelper-1.kt](../app/src/main/java/com/hereliesaz/quicloc/BiometricHelper-1.kt) | Wraps `BiometricPrompt`. Falls back to device credential if no biometric is enrolled. |
| `FindMyPhone` | [FindMyPhone.kt](../app/src/main/java/com/hereliesaz/quicloc/FindMyPhone.kt) | The base→module bridge for `:feature_findmyphone`. Addresses the module by `ComponentName` string: `requestInstall` (SplitInstall download), `trigger` (start `TrackingService`), `isAdminActive`/`adminComponent` (Device Admin status without referencing the receiver class). Degrades gracefully when the split isn't installed. |
| `LockdownController` | [LockdownController.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/LockdownController.kt) | In `:feature_findmyphone`. Single decision point: `DevicePolicyManager.lockNow()` if Device Admin is granted, otherwise let the caller fall back to `TrackingLockActivity`. |
| `QuicLocDeviceAdmin` | [QuicLocDeviceAdmin.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/QuicLocDeviceAdmin.kt) | In `:feature_findmyphone`. `DeviceAdminReceiver` registered with `force-lock` policy only. |
| `ReminderNotification` | [ReminderNotification.kt](../app/src/main/java/com/hereliesaz/quicloc/ReminderNotification.kt) | Opt-in persistent notification with a one-tap toggle action. |
| `Tutorials` | [Tutorial.kt](../app/src/main/java/com/hereliesaz/quicloc/Tutorial.kt) | Static list of 9 in-app tutorials. The "Why QuicLoc?" tutorial auto-shows on first launch. |

## Data flow: the three trigger paths

See [TRIGGER-FLOW.md](TRIGGER-FLOW.md) for the step-by-step. Quick summary:

1. **SMS trigger.** `SmsReceiver` → `WhitelistManager.isWhitelisted(phone)` → `LocationReplyService.startForSms` → `LocationHelper.getCurrentLocationAndReply` → `SmsManager.sendTextMessage`.
2. **Notification trigger.** `NotificationListener` → `WhitelistManager.isWhitelistedByName(senderName)` → `LocationReplyService.startForNotification(action)` → `LocationHelper.getCurrentLocationAndReplyViaNotification` → `Notification.Action.actionIntent.send(...)`.
3. **Passphrase trigger** (any number). `SmsReceiver` or `NotificationListener` → `WhitelistManager.clearPassphraseSync()` → `FindMyPhone.trigger(...)` (starts the `TrackingService` in the on-demand `:feature_findmyphone` module by `ComponentName`; no-op if the module was never installed) → `LockdownController.lockNow()` (DevicePolicyManager.lockNow if admin else cover-screen Activity + full-screen-intent notification) → 5-min interval location updates until the user enters PIN.

## Key architectural choices

- **No backend.** Everything happens on-device. The only outbound traffic is the location reply itself, sent directly to the requesting contact via SMS/inline-reply/MMS. This is explicit in the privacy policy and Play Store declarations.
- **Foreground services for replies, not `goAsync()`.** GPS cold-start can take 20–30 s; `BroadcastReceiver.goAsync()` only buys 10 s before Android kills the process. An FGS with `foregroundServiceType=location` is the only reliable way to wait for a fix from a background trigger.
- **Two-layer encryption.** `EncryptedSharedPreferences` for on-device storage (Keystore-backed). PIN-derived AES-GCM for the backup blob, because Keystore keys don't survive device restore. See [BACKUP.md](BACKUP.md) and [SECURITY.md](SECURITY.md).
- **MessagingStyle-aware notification parsing.** Chat apps (WhatsApp, Telegram, Signal, etc.) post messages as `Notification.MessagingStyle`. Naive parsing of `EXTRA_TEXT` fails for group chats and multi-message bundles. We parse `MessagingStyle.messages` and take the latest, with the sender from `Person.name`.
- **`MainView` sealed-class navigation.** No Navigation library — the project is small enough that a 4-branch `when` is clearer than a nav graph.
- **Snapshot debouncing.** Every encrypted-prefs mutation calls `BackupVault.snapshotAsync`. The debounce window (250 ms) collapses the 6+ mutations during a restore into a single encrypt-write.
