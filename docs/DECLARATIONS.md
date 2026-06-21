# Google Play Declarations for QuicLoc

This document contains the text to submit for each sensitive permission declaration
required by Google Play. Navigate to **Play Console → App content** and complete
each section below.

---

## 1. SMS or Call Log Permissions Declaration

**Location in Play Console:**
> App content → Sensitive app permissions → SMS or Call Log

**Is your app's core functionality an SMS/MMS app, or does it require SMS access for a reason not available through the SMS Intent API?**
Select: **No — SMS access is needed but it is not a messaging app**

**Describe why your app needs SMS permissions:**

> QuicLoc is a personal safety utility. It monitors incoming SMS messages solely to detect a user-defined trigger word ("loc" or "quicloc") sent by phone numbers the user has explicitly pre-approved in a private, encrypted whitelist. When the trigger word is detected from an approved number, the app automatically replies with the device's current GPS location as a Google Maps link. No SMS content is stored, logged, uploaded, or shared. The app never reads, modifies, or transmits any SMS message content other than detecting the trigger word and sending a single automated reply. SMS intent APIs cannot be used because the app must function passively in the background while the device is idle.

---

## 2. Notification Listener Permission Declaration

**Location in Play Console:**
> App content → Sensitive app permissions → Notification access

**Does your app use NotificationListenerService?**
Select: **Yes**

**Describe what your app does with notification access and why it is necessary:**

> QuicLoc uses NotificationListenerService to extend its location-sharing trigger detection to messaging apps beyond SMS (such as WhatsApp, Telegram, and Signal). The service reads only the notification title and body text to check whether they contain the trigger word "loc" or "quicloc" from a pre-approved contact. If the trigger is detected from an approved contact, the app replies via the notification's built-in inline reply action with the device's GPS location as a Google Maps link. No notification content is stored, logged, uploaded, or transmitted anywhere. The service does not read, record, or process notification content from any app or contact not on the user's encrypted whitelist.

---

## 3. Background Location Declaration

**Location in Play Console:**
> App content → Sensitive app permissions → Background location

**Does your app access location in the background?**
Select: **Yes**

**Describe why your app needs background location access:**

> QuicLoc's core function is to respond to incoming location requests automatically, including when the device screen is off and the app is not in the foreground. When a trigger message is received from a whitelisted contact, the app must obtain the device's current GPS coordinates to send a location reply. This is the sole purpose of background location access. The location is used only to generate a Google Maps link sent to the requesting contact. Location is never obtained proactively, stored, logged, or transmitted to any server or third party.

**Provide a video demonstrating the background location use:**
> Record a short screen recording showing:
> 1. Opening QuicLoc and adding a phone number to the whitelist
> 2. Locking the device / closing the app
> 3. Sending "loc" from another device to trigger a response
> 4. The automatic GPS reply arriving on the sending device
>
> Upload this to YouTube (unlisted) and paste the link in the Play Console form.

---

---

## 4. Camera Permission Declaration

**Location in Play Console:**
> App content → App access

**Describe why your app needs camera access:**

> QuicLoc uses the device's front-facing camera solely for its Panic Mode security feature. If an unauthorized user attempts to bypass the device lock screen by entering an incorrect PIN 3 times, QuicLoc silently captures a photo of the intruder and sends it to the trusted contact who initiated the location request via MMS. The photo is captured locally, transmitted directly to the trusted contact's phone number, and is never uploaded, stored on external servers, or shared with the developer or third parties.

---

## 5. Full-Screen Intent Permission Declaration

> **Currently N/A — the find-my-phone feature is disabled.** `USE_FULL_SCREEN_INTENT` ships only in
> the `:feature_findmyphone` module, which is excluded from the build (`FindMyPhone.ENABLED == false`),
> so the permission is not in the app and this declaration is **not required** for now. The text below
> applies if/when the feature is re-enabled.

**Location in Play Console:**
> App content → Sensitive app permissions → Full-screen intent permission

**Does your app use the USE_FULL_SCREEN_INTENT permission?**
Select: **Yes**

**Describe why your app needs full-screen intent:**

> QuicLoc declares USE_FULL_SCREEN_INTENT solely for its find-my-phone (single-use passphrase) safety feature. When the user has set a passphrase and a trigger message containing that passphrase is received, QuicLoc launches `TrackingLockActivity` as a full-screen intent so the lock screen is covered immediately, regardless of whether the device is asleep, in a call, or showing another full-screen activity. This is the only path that fires the full-screen intent, and it is gated on user setup (no passphrase = no full-screen intent ever). The activity displays a PIN prompt and, after 3 failed attempts, transitions the device into panic mode. No advertising, notification spam, or non-safety use case relies on this permission. Device Admin (`lockNow()`) is the preferred lockdown path; the full-screen intent is the fallback when Device Admin is not granted.

> **Module placement (maintainer note):** the `USE_FULL_SCREEN_INTENT` permission and `TrackingLockActivity` are declared in the on-demand `:feature_findmyphone` dynamic feature module, not the base. They are merged into the App Bundle's manifest (Play's restricted-permission review sees the full bundle including feature modules), and only reach the *base install's* effective manifest once the user sets up find-my-phone and the module is delivered (or via the fused sideload APK).

---

## 6. Device Admin Permission Declaration

> **Currently N/A — the find-my-phone feature is disabled.** The `QuicLocDeviceAdmin` receiver and
> `BIND_DEVICE_ADMIN` ship only in the `:feature_findmyphone` module, which is excluded from the build
> (`FindMyPhone.ENABLED == false`), so the app uses no Device Admin API and this declaration is **not
> required** for now. The text below applies if/when the feature is re-enabled.

**Location in Play Console:**
> App content → Restricted permissions → Device Administration API

**Does your app use the Device Administration API?**
Select: **Yes**

**Describe why your app needs Device Admin and which policies it uses:**

> QuicLoc registers `QuicLocDeviceAdmin` as a DeviceAdminReceiver solely so it can call `DevicePolicyManager.lockNow()` when the user's pre-configured find-my-phone passphrase is received in a trigger message. This is the only DPM API the app invokes. The app does not call `wipeData()`, `resetPassword()`, `setPasswordQuality()`, `setMaximumFailedPasswordsForWipe()`, `setUninstallBlocked()`, or any other Device Admin policy. The Device Admin grant is optional — when not granted, QuicLoc falls back to a cover-screen activity (`TrackingLockActivity`) that achieves the same intent without administrative privilege. The user is shown a custom in-app explanation dialog (`device_admin_explanation_body`) before the system grant screen is launched, and the grant is revocable at any time in Settings → Security → Device admin apps. Use case: personal device security, lost/stolen phone recovery.

> **Module placement (maintainer note):** the `QuicLocDeviceAdmin` receiver is declared in the on-demand `:feature_findmyphone` dynamic feature module, not the base. The base checks admin status via `FindMyPhone.isAdminActive` (which builds the receiver's `ComponentName` by name). Because the receiver must be in the merged manifest before `ACTION_ADD_DEVICE_ADMIN` can resolve it, find-my-phone setup downloads the module first, then prompts for the Device Admin grant.

---

## 7. Battery Optimization Exemption Declaration

**Location in Play Console:**
> App content → Restricted permissions → All files access / High-power features
>
> (Submitted under "Foreground services" / "Background work" prominent disclosure if Play surfaces a form for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.)

**Does your app request the user to disable battery optimizations?**
Select: **Yes**

**Describe why your app needs the exemption:**

> QuicLoc is a personal safety utility whose entire function is to respond to incoming "loc" requests from pre-approved contacts when the device is idle. Android's adaptive battery / Doze can suspend the SmsReceiver and NotificationListenerService, causing the trigger to be missed and the location reply to never send. The exemption ensures the trigger path remains reachable, which is core to the safety use case. QuicLoc performs no proactive background work — it only does anything when a whitelisted contact actually sends the trigger word, so the battery cost of the exemption is negligible in practice. The exemption is requested only with explicit user consent through an in-app rationale dialog that explains exactly what it does and why; the user can deny or revoke at any time in system Settings.

---

## 8. Data Safety Section

**Location in Play Console:**
> App content → Data safety

Fill in the Data safety form as follows:

### Does your app collect or share any of the required user data types?
**No** — QuicLoc does not collect any data. It does not transmit any user data to the developer or any third party. The only outbound data is the location reply sent directly from the device to the requesting contact via SMS or notification reply, which is the explicit intended function of the app.

### Data types to declare as NOT collected:
- Location — not collected (obtained on demand, used in-memory, discarded)
- Personal info — not collected
- Financial info — not collected
- Health and fitness — not collected
- Messages — not collected
- Photos and videos — not collected
- Audio files — not collected
- Files and docs — not collected
- Calendar — not collected
- Contacts — not collected
- App activity — not collected
- Web browsing — not collected
- App info and performance — not collected
- Device or other IDs — not collected

### Is all data encrypted in transit?
**N/A** — No data is transmitted to any server. The only transmission is the SMS/notification reply sent directly to the requesting contact through standard Android system APIs.

### Can users request data deletion?
**Yes** — All user data (the whitelist) is stored locally on-device and can be deleted at any time by removing entries in the app or uninstalling the app entirely.

---

## 9. App Category and Content Rating

**Category:** Tools / Utilities

**Content Rating Questionnaire:**
Answer **No** to all questions about violence, sexual content, and controlled substances.

The app will receive a rating of **Everyone**.

---

## Notes for Review

If Google Play reviewers contact you for additional clarification, use this response template:

> QuicLoc is a single-purpose personal safety tool. Its only function is to allow a user's pre-approved trusted contacts to request the user's location by sending a keyword. The app responds automatically with a Google Maps link. All sensitive permissions (SMS, notification access, background location) are used exclusively for this single function. No data is collected, stored server-side, or shared with any third party including the developer. The whitelist of approved contacts is encrypted on-device using Android Keystore-backed AES-256 encryption and is protected by biometric authentication. The app has no network connectivity of its own — it uses only Android system APIs and Google Play Services for GPS.
