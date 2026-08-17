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
            │   LocationHelper     │ 4-stage GPS+network fetch w/ 60s deadline
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
| `BootReceiver` | [BootReceiver.kt](../app/src/main/java/com/hereliesaz/quicloc/BootReceiver.kt) | Re-posts the reminder notification after reboot, re-registers Loc Notice's geofences (they don't survive reboot), and calls `FindMyPhone.resumeTrackingAfterBoot` to resume an in-progress find-my-phone tracking session (neither `START_STICKY` nor the `TrackingAlarmReceiver` alarm survives an actual reboot — see [LOCKDOWN.md](LOCKDOWN.md#service-persistence)). |
| `GeofenceBroadcastReceiver` | [GeofenceBroadcastReceiver.kt](../app/src/main/java/com/hereliesaz/quicloc/GeofenceBroadcastReceiver.kt) | Fires on `ACTION_GEOFENCE_EVENT` when a Loc Notice location is entered/exited. Not request-driven — see [LOC-NOTICE-FLOW.md](LOC-NOTICE-FLOW.md). |
| `TrackingAlarmReceiver` | [TrackingAlarmReceiver.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/TrackingAlarmReceiver.kt) | In the on-demand `:feature_findmyphone` module, `exported=false`. Fired by an `AlarmManager` wakeup alarm to drive each `TrackingService` tick (see below) — including while the device is asleep, which `Handler.postDelayed` cannot do. Also the recovery path when `TrackingService`'s *process* (not just the service instance) is killed: the next alarm still fires and restarts it. |

### Foreground services

| Service | File | Type | When started |
|---|---|---|---|
| `LocationReplyService` | [LocationReplyService.kt](../app/src/main/java/com/hereliesaz/quicloc/LocationReplyService.kt) | `location` | Whitelisted contact sent the trigger word, or the user tapped the widget. One-shot: fetch → reply → `stopSelf`. |
| `TrackingService` | [TrackingService.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/TrackingService.kt) | `location` | Passphrase fired. Lives in the on-demand `:feature_findmyphone` module; started from the base via `FindMyPhone.trigger` (`ComponentName`). Persistent: posts location every 5 min (1 min in panic mode), ticks driven by `AlarmManager` wakeup alarms via `TrackingAlarmReceiver` (not a `Handler`, which can't fire during CPU suspend), survives restart via `quicloc_tracking_state` prefs. `onStartCommand` splits the old combined "notify + lock" step into `claimForeground()` (idempotent, safe on every tick) and `engageLockdown()` (one-shot per session — launches `TrackingLockActivity` + `LockdownController.lockNow()`, never on a routine tick). |

### UI

| Activity | File | Role |
|---|---|---|
| `MainActivity` | [MainActivity.kt](../app/src/main/java/com/hereliesaz/quicloc/MainActivity.kt) | Single-activity Compose app. Navigation is a `sealed class MainView` (Config / History / Diagnostics / TutorialsHub / TutorialDetail / LocNoticeList / LocNoticeEdit). Unlock gate on resume: biometric / device credential, or the QuicLoc PIN. The Config screen is a purpose card, then the [Readiness](../app/src/main/java/com/hereliesaz/quicloc/Readiness.kt) setup checklist, then one collapsible `SectionCard` per app function (including Loc Notice's own master switch). |
| `TrackingLockActivity` | [TrackingLockActivity.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/TrackingLockActivity.kt) | In the on-demand `:feature_findmyphone` module. Fallback cover-screen when Device Admin isn't granted. After 3 wrong PINs, captures an intruder photo via the module-internal [IntruderCamera](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/IntruderCamera.kt) (no photo if CAMERA wasn't granted); otherwise locks without a photo. |
| `WidgetHelpActivity` | [WidgetHelpActivity.kt](../app/src/main/java/com/hereliesaz/quicloc/WidgetHelpActivity.kt) | Transparent activity that pops up when the widget is tapped exactly once. Lists all four tap counts with a live ✓/✗ for whether each one has somewhere to send to. |

### Data layer

| Component | File | Storage |
|---|---|---|
| `WhitelistManager` | [WhitelistManager.kt](../app/src/main/java/com/hereliesaz/quicloc/WhitelistManager.kt) | `EncryptedSharedPreferences` (`quicloc_secure_prefs`). Holds whitelist, starred, my_number, passphrase, PIN, onboarding flag. Mutations trigger `BackupVault.snapshotAsync`. |
| `AppSettings` | [AppSettings.kt](../app/src/main/java/com/hereliesaz/quicloc/AppSettings.kt) | Plain `SharedPreferences` (`quicloc_app_settings`). Holds non-sensitive toggles + "already prompted" flags. Auto-backs-up via Android Auto Backup. |
| `RequestHistoryManager` | [RequestHistoryManager.kt](../app/src/main/java/com/hereliesaz/quicloc/RequestHistoryManager.kt) | `EncryptedSharedPreferences` (`quicloc_history`). JSON-serialized list of recent location-request events. |
| `BackupVault` | [BackupVault.kt](../app/src/main/java/com/hereliesaz/quicloc/BackupVault.kt) | PIN-encrypted blob at `files/quicloc_backup.qlb`. Auto-backs-up via Android Auto Backup (the file is inside the app's `files/` dir). |
| `GeofenceStore` | [GeofenceStore.kt](../app/src/main/java/com/hereliesaz/quicloc/GeofenceStore.kt) | `EncryptedSharedPreferences` (`quicloc_locnotice_prefs`). Loc Notice place definitions (name, coordinates, radius, enter/exit flags, contact tokens). Mutations trigger `BackupVault.snapshotAsync`; has zero Play Services dependency — that's `GeofenceRegistrar`'s job. |
| `GeofenceStateStore` | [GeofenceStateStore.kt](../app/src/main/java/com/hereliesaz/quicloc/GeofenceStateStore.kt) | Plain `SharedPreferences` (`quicloc_geofence_state`). Ephemeral last-transition bookkeeping per geofence, for redelivery/flap dedupe. Excluded from Android Auto Backup, same reasoning as `quicloc_tracking_state`. |

### Cross-cutting helpers

| Helper | File | Role |
|---|---|---|
| `Readiness` | [Readiness.kt](../app/src/main/java/com/hereliesaz/quicloc/Readiness.kt) | Pure Kotlin (no `android.*`): turns the live permission snapshot plus whitelist/own-number state into the ordered setup checklist. Shares its `PermissionStatus` input with the All Permissions table, so the two views can't disagree. Unit-tested without Robolectric. |
| `LocationHelper` | [LocationHelper.kt](../app/src/main/java/com/hereliesaz/quicloc/LocationHelper.kt) | Four-stage location fetch (current GPS → cached → forced GPS, racing a network/cell fallback once GPS is slow) with a single 60 s deadline. Also has the SMS-send and notification-reply primitives. |
| `BiometricHelper` | [BiometricHelper-1.kt](../app/src/main/java/com/hereliesaz/quicloc/BiometricHelper-1.kt) | Wraps `BiometricPrompt`. Falls back to device credential if no biometric is enrolled. When the device has no lock screen at all, `MainActivity` gates on the QuicLoc PIN instead (and only lets the user straight through when neither exists). |
| `FindMyPhone` | [FindMyPhone.kt](../app/src/main/java/com/hereliesaz/quicloc/FindMyPhone.kt) | The base→module bridge for `:feature_findmyphone`. Addresses the module by `ComponentName` string: `requestInstall` (SplitInstall download), `trigger` (start `TrackingService`), `isAdminActive`/`adminComponent` (Device Admin status without referencing the receiver class), `wasTrackingActive`/`resumeTrackingAfterBoot` (read `quicloc_tracking_state` directly and, if a session was active, restart `TrackingService` after a reboot — called from `BootReceiver`; not full Direct Boot support, see [LOCKDOWN.md](LOCKDOWN.md#service-persistence)). `isInstalled` resolves via `PackageManager.getServiceInfo` against the merged manifest — not solely `SplitInstallManager.installedModules`, which reports nothing for a fused/sideload install — so it correctly detects both a fused sideload APK and a Play on-demand split. Degrades gracefully when the module isn't installed. |
| `LockdownController` | [LockdownController.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/LockdownController.kt) | In `:feature_findmyphone`. Single decision point: `DevicePolicyManager.lockNow()` if Device Admin is granted, otherwise let the caller fall back to `TrackingLockActivity`. |
| `QuicLocDeviceAdmin` | [QuicLocDeviceAdmin.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/QuicLocDeviceAdmin.kt) | In `:feature_findmyphone`. `DeviceAdminReceiver` registered with `force-lock` policy only. |
| `PinAttemptDecision` | [PinAttemptDecision.kt](../feature_findmyphone/src/main/java/com/hereliesaz/quicloc/lockdown/PinAttemptDecision.kt) | In `:feature_findmyphone`. Pure function `TrackingLockActivity.onPinEntered` delegates to, in the same spirit as `Readiness` and `GeofenceStateStore.evaluate` above — no Android/Compose dependency, so the escalate-exactly-once-at-the-3rd-wrong-attempt rule is directly unit-testable. |
| `ReminderNotification` | [ReminderNotification.kt](../app/src/main/java/com/hereliesaz/quicloc/ReminderNotification.kt) | Opt-in persistent notification with a one-tap toggle action. |
| `Tutorials` | [Tutorial.kt](../app/src/main/java/com/hereliesaz/quicloc/Tutorial.kt) | Static list of 12 in-app tutorials (2 of them, find-my-phone and real-lockdown, only listed while `FindMyPhone.ENABLED`). The "Why QuicLoc?" tutorial auto-shows on first launch. |

## Data flow: the three trigger paths

See [TRIGGER-FLOW.md](TRIGGER-FLOW.md) for the step-by-step. Quick summary:

1. **SMS trigger.** `SmsReceiver` → `WhitelistManager.isWhitelisted(phone)` → `LocationReplyService.startForSms` → `LocationHelper.getCurrentLocationAndReply` → `SmsManager.sendTextMessage`.
2. **Notification trigger.** `NotificationListener` → `WhitelistManager.isWhitelistedByName(senderName)` → `LocationReplyService.startForNotification(action)` → `LocationHelper.getCurrentLocationAndReplyViaNotification` → `Notification.Action.actionIntent.send(...)`.
3. **Passphrase trigger** (any number). `SmsReceiver` or `NotificationListener` → `WhitelistManager.clearPassphraseSync()` → `FindMyPhone.trigger(...)` (starts the `TrackingService` in the on-demand `:feature_findmyphone` module by `ComponentName`; no-op if the module was never installed) → `LockdownController.lockNow()` (DevicePolicyManager.lockNow if admin else cover-screen Activity + full-screen-intent notification) → 5-min interval location updates until the user enters PIN.

Loc Notice is not request-driven and runs separately from all three paths above — see [LOC-NOTICE-FLOW.md](LOC-NOTICE-FLOW.md): `GeofenceRegistrar` registers named places with `GeofencingClient`; `GeofenceBroadcastReceiver` fires on a crossing and texts the configured contacts directly, with no incoming message involved.

## Key architectural choices

- **No backend.** Everything happens on-device. The only outbound traffic is the location reply itself, sent directly to the requesting contact via SMS/inline-reply/MMS. This is explicit in the privacy policy and Play Store declarations.
- **Foreground services for replies, not `goAsync()`.** GPS cold-start can take 20–30 s; `BroadcastReceiver.goAsync()` only buys 10 s before Android kills the process. An FGS with `foregroundServiceType=location` is the only reliable way to wait for a fix from a background trigger.
- **Two-layer encryption.** `EncryptedSharedPreferences` for on-device storage (Keystore-backed). PIN-derived AES-GCM for the backup blob, because Keystore keys don't survive device restore. See [BACKUP.md](BACKUP.md) and [SECURITY.md](SECURITY.md).
- **MessagingStyle-aware notification parsing.** Chat apps (WhatsApp, Telegram, Signal, etc.) post messages as `Notification.MessagingStyle`. Naive parsing of `EXTRA_TEXT` fails for group chats and multi-message bundles. We parse `MessagingStyle.messages` and take the latest, with the sender from `Person.name`.
- **`MainView` sealed-class navigation.** No Navigation library — the project is small enough that a 4-branch `when` is clearer than a nav graph.
- **Snapshot debouncing.** Every encrypted-prefs mutation calls `BackupVault.snapshotAsync`. The debounce window (250 ms) collapses the 6+ mutations during a restore into a single encrypt-write.
- **Loc Notice needs no new API key or billing.** Defining a place is a two-way clipboard handoff into the system Maps app (see [LOC-NOTICE-FLOW.md](LOC-NOTICE-FLOW.md)) rather than a Places/Geocoding API call — consistent with "no backend" above. `GeofencingClient` itself comes from `play-services-location`, already a dependency for `LocationHelper`.
