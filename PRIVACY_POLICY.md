# Privacy Policy for QuicLoc

**Last updated:** March 10, 2026
**App:** QuicLoc (`com.hereliesaz.quicloc`)
**Developer:** HereLiesAz

---

## Overview

QuicLoc is a personal utility that allows trusted contacts to request your location by sending a specific trigger word via SMS or any messaging app. This policy explains exactly what data the app accesses, what it does with it, and what it never does.

The short version: **QuicLoc does not collect, store, transmit, or share any of your data with anyone — including the developer.** Everything stays on your device.

---

## Data the App Accesses

### 1. SMS Messages
QuicLoc receives incoming SMS messages to check whether they contain the trigger words `loc` or `quicloc`. It reads only the sender's phone number and the message body. No message content is stored, logged, or transmitted anywhere. Messages from numbers not on your whitelist are ignored immediately and discarded.

### 2. Notifications
QuicLoc listens to incoming notifications from messaging apps (such as WhatsApp, Telegram, and Signal) to detect the same trigger words. It reads only the notification title (used as the sender identifier) and the notification text. No notification content is stored, logged, or transmitted anywhere. Notifications from contacts not on your whitelist are ignored immediately and discarded.

### 3. Location
When a valid trigger is received from a whitelisted contact, QuicLoc obtains the device's current GPS location. This location is used solely to generate a Google Maps link, which is sent back to the requesting contact via SMS or inline notification reply. The location is never stored, logged, or sent anywhere other than directly to the requesting contact.

### 4. Whitelist (Phone Numbers and Contact Names)
The whitelist of trusted contacts you configure is stored locally on your device using Android's `EncryptedSharedPreferences`, backed by the Android Keystore with AES-256-GCM encryption. This data never leaves your device.

---

## Data the App Does NOT Access

- Your contacts list
- Your call history
- Your camera or microphone
- Your files or photos
- Any account credentials
- Any data from messaging apps beyond detecting the trigger word in a notification

---

## Data Sharing

QuicLoc shares **no data** with the developer, third parties, analytics services, advertising networks, or any external server. There are no SDKs, no analytics libraries, no crash reporters, and no network calls made by the app other than the Google Play Services location API (used locally on-device to obtain GPS coordinates).

The only outbound data is the Google Maps location link sent as an SMS reply or notification reply directly to the contact who requested it — which is the entire intended function of the app.

---

## Data Retention

QuicLoc retains no data beyond what is explicitly stored by you:

- **Whitelist entries** — stored on-device, encrypted, until you delete them via the app UI.
- **Location** — obtained on demand, used to construct a reply, then immediately discarded. Never written to disk.
- **Messages and notifications** — read in memory, checked against the whitelist, then immediately discarded. Never written to disk.

---

## Security

- The whitelist is encrypted using `EncryptedSharedPreferences` with AES-256-GCM (values) and AES-256-SIV (keys), backed by the Android Keystore.
- The app requires biometric authentication (fingerprint, face, or device PIN) to open and modify the whitelist.
- Background location and SMS/notification processing occur without displaying any UI, and cannot be accessed or modified without passing biometric authentication.

---

## Permissions Explained

| Permission | Why it is needed |
|---|---|
| `RECEIVE_SMS` | To detect the trigger word in incoming SMS messages |
| `SEND_SMS` | To send the location reply via SMS |
| `ACCESS_FINE_LOCATION` | To obtain a precise GPS location to share |
| `ACCESS_COARSE_LOCATION` | Fallback if fine location is unavailable |
| `ACCESS_BACKGROUND_LOCATION` | To obtain location while the app is not in the foreground (required for the core function) |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | To authenticate the user before allowing access to the whitelist |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | To detect the trigger word in notifications from non-SMS messaging apps |

---

## Children's Privacy

QuicLoc is not directed at children under the age of 13 and does not knowingly collect any information from children.

---

## Changes to This Policy

If this policy is updated, the "Last updated" date at the top will change. Continued use of the app after any changes constitutes acceptance of the updated policy.

---

## Contact

If you have questions about this privacy policy, please open an issue at:
**https://github.com/HereLiesAz/QuicLoc**
