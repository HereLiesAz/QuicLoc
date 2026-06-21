# Permissions

Every permission declared in `AndroidManifest.xml`, what it's for, and any caveats.

## Install-time (granted by manifest declaration)

| Permission | Used for | Notes |
|---|---|---|
| `FOREGROUND_SERVICE` | Required to start any FGS on Android 9+ | Always granted, just needed in the manifest |
| `FOREGROUND_SERVICE_LOCATION` | Type-specific FGS permission, Android 14+ | Required for `LocationReplyService`. The `TrackingService` (now in the on-demand `:feature_findmyphone` module) also relies on this base-declared, app-global permission. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | `BiometricPrompt` UI auth gate | `USE_FINGERPRINT` is the pre-API-28 name; we keep both for breadth |
| `INTERNET` | Required by Play Services (`play-services-location`, `play-services-auth`) | QuicLoc itself makes no HTTP calls — no backend |
| `ACCESS_NETWORK_STATE` | Required by Play Services | Same as above |
| `VIBRATE` | Haptic feedback on widget taps | |
| `RECEIVE_BOOT_COMPLETED` | `BootReceiver` re-posts the reminder notification after reboot | |

> `USE_FULL_SCREEN_INTENT` is **no longer a base permission** — it moved into the on-demand
> `:feature_findmyphone` module along with the tracking notification that uses it. See the
> On-demand section below.

## Runtime (must request from the user)

### Always-on requirements

| Permission | Why | When requested |
|---|---|---|
| `RECEIVE_SMS` | `SmsReceiver` parses incoming SMS for the trigger word | After biometric auth on first launch |
| `SEND_SMS` | `LocationHelper.sendSms` replies via `SmsManager` | Same |
| `ACCESS_FINE_LOCATION` | `LocationHelper.fetchLocation` GPS fix | Same |
| `ACCESS_COARSE_LOCATION` | Fallback when fine location unavailable | Same |
| `POST_NOTIFICATIONS` (API 33+) | All our notifications: FGS, tracking, reminder | Same |

These are all batched in a single `requestPermissions` call from `MainActivity.checkPermissions` after biometric auth passes.

### On-demand (dynamic feature module) — currently DISABLED

> **Status: the find-my-phone / lockdown feature is currently disabled and not shipped.**
> `:feature_findmyphone` is excluded from the build (`settings.gradle.kts` + the app's
> `dynamicFeatures`) and `FindMyPhone.ENABLED` is `false`, so the module's manifest is **not merged**
> and the app declares **no `CAMERA`, no `USE_FULL_SCREEN_INTENT`, and no `BIND_DEVICE_ADMIN`** —
> none of these need to be declared to Google Play while the feature is off. The setup UI is hidden
> and the passphrase trigger no-ops. The module's code is kept in the repo; re-enable by re-adding the
> module in both Gradle files and flipping `FindMyPhone.ENABLED` to `true`. The section below
> describes the feature's permission model **when enabled**.

The **entire** find-my-phone / lockdown feature lives in the on-demand `:feature_findmyphone`
module: the `TrackingService` (FGS), `TrackingLockActivity` (lock screen), `QuicLocDeviceAdmin`
(Device Admin receiver), and the `IntruderCamera` capture. The base declares **none** of the
feature's permissions or components until the user sets up find-my-phone and the module is
downloaded via `SplitInstall` (`FindMyPhone.requestInstall`). The module fuses into sideload APKs
(`dist:fusing dist:include="true"`), so APK users get it at install time; the Play AAB path keeps
it on-demand.

| Permission | Why | When |
|---|---|---|
| `CAMERA` | Panic-mode intruder photo (module-internal `IntruderCamera`) after 3 wrong PINs | **Not in the base install.** Declared in `:feature_findmyphone`. Requested right after the module installs (foreground), because the lock activity can't show a permission dialog over the keyguard. If CAMERA isn't granted, panic mode still locks — just without a photo. |
| `USE_FULL_SCREEN_INTENT` | `setFullScreenIntent` on the tracking notification (surfaces the lock screen over the keyguard) | **Not in the base install.** Declared in `:feature_findmyphone`. Android 14+ also requires a *runtime* grant via Settings — see the dedicated section below. |

Because Device Admin, CAMERA, and the tracking components only exist once the module is merged in,
find-my-phone setup is **install-first**: `MainActivity` downloads the module, then (on the installed
callback) requests CAMERA and prompts for Device Admin. This is how the base install avoids declaring
`CAMERA`, `USE_FULL_SCREEN_INTENT`, and the Device Admin receiver until the user opts into find-my-phone.

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
- Detect grant state via `FindMyPhone.isAdminActive(context)` (constructs the module receiver's `ComponentName` by name, so it works — returning `false` — even before the module is installed).

Optional — without it, the find-my-phone path falls back to `TrackingLockActivity` cover-screen mode. Note the receiver lives in the on-demand `:feature_findmyphone` module, so Device Admin is only *grantable* after that module is installed (find-my-phone setup installs it first, then prompts).

### USE_FULL_SCREEN_INTENT (Android 14+ runtime grant)

Manifest declaration alone is insufficient on API 34+. Grant is via Settings → Apps → QuicLoc → Notifications → "Full screen notifications".

Detection: `NotificationManager.canUseFullScreenIntent()` (API 34+; older Android auto-grants and the check returns true).

Request path: `MainActivity.checkFullScreenIntentPermission()` runs at the end of the first-launch permission chain (after notification listener). It shows a rationale `AlertDialog`, then on Continue hands off to system Settings via `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` with a `package:` URI. `AppSettings.wasFullScreenIntentPrompted` flags the auto-prompt as done so subsequent launches don't re-nag.

Re-entry: a `⚠ Full Screen Notifications not enabled` Card is shown in [QuicLocScreen] (only on API 34+ when the runtime grant is missing) with its own pre-prompt `AlertDialog` and a "Grant Full Screen Notifications" button that calls `openFullScreenIntentSettings()`.

Without this grant, the find-my-phone cover-screen fallback (`TrackingLockActivity`) cannot come over the lock screen on Android 14+. Device Admin is the preferred path and renders this optional.

## Protected (background reliability)

These aren't manifest `<uses-permission>` entries but are surfaced as a "Protected" category in [QuicLocScreen]'s All Permissions panel so the user can grant them in one place.

| Setting | Detection | Request path |
|---|---|---|
| Battery Optimization Exemption | `PowerManager.isIgnoringBatteryOptimizations(packageName)` | Rationale dialog → `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in manifest; fallback to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` list if direct grant intent throws). |
| OEM Autostart | No public API — surfaced as `UNKNOWN` | Best-effort probe of vendor security-app components (Xiaomi, Huawei, Oppo, Vivo, Samsung, Asus, etc.) in `OEM_AUTOSTART_INTENTS`. First resolvable component is launched; otherwise app info as fallback. |
| Notification Channels | No single-bool API (multiple channels) — surfaced as `UNKNOWN` | `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE` (API 26+); fallback to app details settings. |

The battery optimization exemption requires a Play Console disclosure — see [DECLARATIONS.md §7](../DECLARATIONS.md).

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
