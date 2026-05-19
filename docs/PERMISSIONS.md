# Permissions

Every permission declared in `AndroidManifest.xml`, what it's for, and any caveats.

## Install-time (granted by manifest declaration)

| Permission | Used for | Notes |
|---|---|---|
| `FOREGROUND_SERVICE` | Required to start any FGS on Android 9+ | Always granted, just needed in the manifest |
| `FOREGROUND_SERVICE_LOCATION` | Type-specific FGS permission, Android 14+ | Required for `LocationReplyService` and `TrackingService` |
| `FOREGROUND_SERVICE_CAMERA` | Type-specific FGS permission, Android 14+ | Required because `TrackingService` declares `foregroundServiceType="location\|camera"` |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | `BiometricPrompt` UI auth gate | `USE_FINGERPRINT` is the pre-API-28 name; we keep both for breadth |
| `INTERNET` | Required by Play Services (`play-services-location`, `play-services-auth`) | QuicLoc itself makes no HTTP calls — no backend |
| `ACCESS_NETWORK_STATE` | Required by Play Services | Same as above |
| `VIBRATE` | Haptic feedback on widget taps | |
| `RECEIVE_BOOT_COMPLETED` | `BootReceiver` re-posts the reminder notification after reboot | |
| `USE_FULL_SCREEN_INTENT` | `setFullScreenIntent` on the tracking notification (cover-screen fallback when Device Admin not granted) | Android 14+ requires *runtime* grant via Settings; we declare the manifest permission but don't currently prompt — see [LOCKDOWN.md](LOCKDOWN.md) |

## Runtime (must request from the user)

### Always-on requirements

| Permission | Why | When requested |
|---|---|---|
| `RECEIVE_SMS` | `SmsReceiver` parses incoming SMS for the trigger word | After biometric auth on first launch |
| `SEND_SMS` | `LocationHelper.sendSms` replies via `SmsManager` | Same |
| `ACCESS_FINE_LOCATION` | `LocationHelper.fetchLocation` GPS fix | Same |
| `ACCESS_COARSE_LOCATION` | Fallback when fine location unavailable | Same |
| `CAMERA` | `TrackingLockActivity` photo capture after 3 wrong PINs (panic mode) | Same — requested up front, not at panic time, because the lock activity can't show a permission dialog over the keyguard |
| `POST_NOTIFICATIONS` (API 33+) | All our notifications: FGS, tracking, reminder | Same |

These are all batched in a single `requestPermissions` call from `MainActivity.checkPermissions` after biometric auth passes.

### Background location

| Permission | Why |
|---|---|
| `ACCESS_BACKGROUND_LOCATION` | Lets `LocationHelper` work while the app is not in the foreground — i.e., the entire point of QuicLoc |

Requested **separately**, after the user has granted foreground location, because Android 11+ rejects bundling them. The user is sent to the system Location Settings screen and must explicitly tap "Allow all the time" — the app can't request this state directly.

Code: `MainActivity.checkBackgroundLocationPermission` → `backgroundLocationLauncher`.

### Contacts

| Permission | Why |
|---|---|
| `READ_CONTACTS` | Optional — only used by the "Pick from Contacts" button to populate the whitelist |

Requested on demand (when the user taps "Pick from Contacts") — `MainActivity.launchContactPicker`. If denied, the picker still opens via `ActivityResultContracts.PickContact()`; we just won't be able to enumerate the contact's phone numbers, only their display name.

## Special-access settings (must be granted in system Settings, no runtime API)

### Notification Access

`android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` is system-only; the user grants it via Settings → Apps → Special access → Notification access. We:

- Detect grant state via `Settings.Secure.getString(contentResolver, "enabled_notification_listeners")`.
- Show a prominent "Notification Access Required" banner with a "Grant Notification Access" button that opens `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.

Without this grant, the app still works for SMS but doesn't trigger from any chat app.

### Device Admin

`android.permission.BIND_DEVICE_ADMIN` is system-only; granted by launching `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN`. We:

- Show a Card in settings labeled "Real lockdown not enabled" with a "Grant Device Admin" button.
- Tapping the button shows a confirmation dialog first (`device_admin_explanation_body` string) so the user isn't blindsided by the system grant screen.
- Detect grant state via `QuicLocDeviceAdmin.isAdminActive(context)`.

Optional — without it, the find-my-phone path falls back to `TrackingLockActivity` cover-screen mode.

### USE_FULL_SCREEN_INTENT (Android 14+ runtime grant)

Manifest declaration alone is insufficient on API 34+. Grant is via Settings → Apps → QuicLoc → Notifications → "Full screen notifications". We declare it but currently don't prompt the user to enable it. `AppSettings.wasFullScreenIntentPrompted` exists as a placeholder for this flow but isn't wired up.

## What each path requires to actually work

| Path | Required runtime permissions | Required special access |
|---|---|---|
| SMS trigger | RECEIVE_SMS, SEND_SMS, *_LOCATION, ACCESS_BACKGROUND_LOCATION | — |
| Notification trigger | SEND_SMS *(implicit dependency through fallbacks)*, *_LOCATION, ACCESS_BACKGROUND_LOCATION | Notification Access |
| Widget tap | SEND_SMS, *_LOCATION, ACCESS_BACKGROUND_LOCATION, POST_NOTIFICATIONS | — |
| Passphrase / find-my-phone | RECEIVE_SMS, SEND_SMS, *_LOCATION, BACKGROUND_LOCATION, CAMERA | — for cover-screen mode; Device Admin for real lock |
| MMS panic photo | SEND_SMS, CAMERA | — |

## Permissions explicitly NOT requested

- `READ_PHONE_STATE` / `READ_PHONE_NUMBERS` — we use the Phone Number Hint API instead, which doesn't need permission.
- `READ_SMS` — we only receive new SMS, never read the user's SMS history.
- `WRITE_SMS` — `SmsManager.sendTextMessage` doesn't need it.
- `WAKE_LOCK` — FGS keeps the process alive; we don't need a manual wake lock.
- `SCHEDULE_EXACT_ALARM` — we use `Handler.postDelayed`, not `AlarmManager`.
- `MANAGE_OVERLAY_PERMISSION` / `SYSTEM_ALERT_WINDOW` — we don't draw overlays; the lock screen is a real `Activity`.

Adding any of these requires updating [DECLARATIONS.md](../DECLARATIONS.md) and the privacy policy.
