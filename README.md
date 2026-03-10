# QuicLoc

**QuicLoc** is an Android utility that lets trusted contacts request your real-time GPS location by sending a single keyword — via SMS or any messaging app. It responds automatically, even when your screen is off.

---

## How It Works

1. You add a contact's phone number or display name to an encrypted, biometric-protected whitelist.
2. That contact sends you the message `loc` or `quicloc` (case-insensitive) from any messaging app.
3. QuicLoc detects the trigger, fetches your current GPS location, and replies automatically with a Google Maps link.

No app needs to be open. No buttons need to be pressed.

---

## Features

- **Works across messaging apps** — responds to SMS, WhatsApp, Telegram, Signal, Google Messages, Messenger, and any app that supports notification inline replies.
- **Encrypted whitelist** — trusted contacts are stored using AES-256-GCM encryption backed by the Android Keystore. The data never leaves your device.
- **Biometric protection** — the app requires fingerprint, face unlock, or device PIN to open. Falls back to PIN/pattern if no biometric is enrolled.
- **Reliable location** — uses a three-stage fallback (GPS fix → cached location → forced update) to ensure a location is returned even from a cold start.
- **Fully background** — operates silently when the screen is off. Auto-replies work without the app being open or unlocked.
- **No data collection** — no analytics, no crash reporters, no servers. Nothing leaves your device except the location reply sent directly to the requesting contact.

---

## Permissions

| Permission | Purpose |
|---|---|
| `RECEIVE_SMS` | Detect the trigger word in incoming SMS messages |
| `SEND_SMS` | Send the location reply via SMS |
| `ACCESS_FINE_LOCATION` | Obtain a precise GPS location |
| `ACCESS_COARSE_LOCATION` | Fallback if fine location is unavailable |
| `ACCESS_BACKGROUND_LOCATION` | Obtain location while the app is not in the foreground |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | Authenticate the user before allowing whitelist access |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Detect the trigger word in non-SMS messaging apps |

---

## Setup

1. Install the APK (download from [Releases](../../releases)).
2. Open QuicLoc and authenticate with your fingerprint or PIN.
3. Grant all requested permissions. When prompted, also grant **Notification Access** in system settings — this enables responses in WhatsApp, Telegram, and other apps.
4. Add the phone numbers or contact names of people you want to allow to request your location.
5. Done. QuicLoc runs silently in the background.

### First-time permission note

Android requires **Background Location** to be granted separately after foreground location. The app will walk you through this. On Android 11+, you must tap *"Allow all the time"* in the location settings screen — the app cannot request this directly.

---

## Privacy

QuicLoc does not collect, store, transmit, or share any user data with the developer or any third party. See [PRIVACY_POLICY.md](./PRIVACY_POLICY.md) for the full policy.

---

## Architecture

QuicLoc is written in Kotlin using Jetpack Compose for the UI. Core components:

- **`SmsReceiver`** — `BroadcastReceiver` that intercepts incoming SMS and checks for the trigger word from whitelisted numbers. Uses `goAsync()` for safe background work.
- **`NotificationListener`** — `NotificationListenerService` that monitors incoming notifications from all messaging apps and replies via their inline reply action.
- **`LocationHelper`** — obtains the device's GPS location using Fused Location Provider with a three-stage fallback: `getCurrentLocation()` → `lastLocation` → `requestLocationUpdates()` with a 15-second timeout.
- **`WhitelistManager`** — manages trusted contacts using `EncryptedSharedPreferences` (AES-256-GCM, Android Keystore). Automatically migrates any existing plaintext data on first upgrade.
- **`BiometricHelper`** — wraps `BiometricPrompt` to gate UI access with fingerprint, face, or device PIN. The background components are unaffected by authentication state.

---

## Building

QuicLoc is a standard Gradle Android project targeting API 36 with a minimum of API 26 (Android 8.0).

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/`.

### Versioning

Versions follow the scheme **A.B.C.D**:

| Segment | Meaning |
|---|---|
| `A` | Major version — incremented manually by the developer |
| `B` | Feature version — incremented when significant features are added |
| `C` | Build count within the current `B` version — auto-increments on every build |
| `D` | Absolute total build count — auto-increments on every build, used as `versionCode` |

Version state is tracked in `app/version.properties`.

---

## Google Play

This app uses sensitive permissions (`NotificationListenerService`, background location, SMS) that require manual review by Google Play. See [DECLARATIONS.md](./DECLARATIONS.md) for the full Play Console declaration text and Data Safety form guidance.

---

## License

This is free and unencumbered software released into the public domain. See [LICENSE](./LICENSE) for details.
