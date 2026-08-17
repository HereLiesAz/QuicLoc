# Google Play Declarations for QuicLoc

This document contains the text to submit for each sensitive permission declaration
required by Google Play. Navigate to **Play Console → App content** and complete
each section below.

---

## 1. SMS or Call Log Permissions Declaration

**Location in Play Console:**
> App content → Sensitive app permissions → SMS or Call Log

**Core functionalities:**
Select **ONLY**: `Physical safety / emergency alert apps (e.g., senior safety)`
*(Do NOT select Device Automation or any other checkboxes, as checking multiple unaligned use cases will cause a rejection).*

**Is your app's core functionality an SMS/MMS app, or does it require SMS access for a reason not available through the SMS Intent API?**
Select: **No — SMS access is needed but it is not a messaging app**

**Describe why your app needs SMS permissions:**

> QuicLoc is a physical-safety / emergency-alert app. Its core mechanism is the home-screen widget: the user taps it to send a safety-check or emergency alert with their GPS location to their chosen emergency contacts — this requires no SMS access at all. RECEIVE_SMS and SEND_SMS extend that same safety mechanism by letting a subset of those emergency contacts (the ones the user has explicitly marked as allowed to ask, not merely added to the emergency list) also request the location themselves by texting a trigger word, which the app answers with the same GPS-coordinate reply the widget sends. The SMS Retriever API cannot be used because it requires messages to contain an 11-character app hash, which is impossible for natural texts sent by human contacts. Intents cannot be used as the device may be unattended. No data is stored.

**Video instructions:**
> **CRITICAL:** You MUST provide a video link here, even though the form says it's optional. Google Play will automatically reject the declaration without one.
> Record a short screen recording showing, in this order:
> 1. Opening QuicLoc, adding an emergency contact, and demonstrating the home-screen widget itself — tap it 3 times for the safety-check alert and 4 times for the emergency alert, so the reviewer sees the app's core safety function working with no SMS involved at all.
> 2. Adding a phone number as a contact who can also request the location by text (the "can ask" toggle in the emergency contacts list).
> 3. Sending "loc" from that contact's device via SMS to trigger a response.
> 4. The automatic GPS location SMS reply arriving on the sending device.
>
> Leading with the widget matters: it shows RECEIVE_SMS/SEND_SMS are an optional extension of an already-complete safety-alert app, not the app's entire reason for existing.
> Upload this to YouTube (unlisted) and paste the link in the "Video instructions" field.



## 2. Notification Listener Permission Declaration

**Location in Play Console:**
> App content → Sensitive app permissions → Notification access

**Does your app use NotificationListenerService?**
Select: **Yes**

**Describe what your app does with notification access and why it is necessary:**

> QuicLoc uses NotificationListenerService to extend its location-sharing trigger detection to messaging apps beyond SMS (such as WhatsApp, Telegram, and Signal). The service reads only the notification title and body text to check whether they contain the trigger word "loc" or "quicloc" from a pre-approved contact. If the trigger is detected from an approved contact, the app replies via the notification's built-in inline reply action with the device's GPS location as a Google Maps link. No notification content is stored, logged, uploaded, or transmitted anywhere. The service does not read, record, or process notification content from any app or contact not on the user's encrypted whitelist.

---

## 3. Background Location Declaration (Location permissions)

**Location in Play Console:**
> App content → Sensitive app permissions → Background location

**Does your app access location in the background?**
Select: **Yes**

**Describe why your app needs background location access:**

> QuicLoc uses background location for two distinct, both user-visible and user-controlled, safety functions:
>
> 1. **Request-reply (always available).** When a trigger message is received from a whitelisted contact, including when the screen is off and the app is not in the foreground, QuicLoc obtains the device's current GPS coordinates to send a location reply. Location is read only at the moment of replying to a request, never on a schedule.
> 2. **Loc Notice (opt-in, off by default, separate switch from the rest of the app).** The user names a place, picks contacts, and chooses to be alerted on arrival and/or departure. While this feature is on, Android's Geofencing API (Google Play services) continuously monitors location in the background to detect boundary crossings for the user's saved places, and QuicLoc automatically texts the chosen contacts when one occurs. This is genuine ongoing background location use — the feature cannot work otherwise — and is fully disclosed to the user in-app before they turn it on, with its own on/off switch independent of every other function in the app.
>
> In both cases, location is used only to (1) generate the Google Maps link or arrival/departure text sent to the relevant contact(s), and (2) evaluate geofence boundaries on-device via the Android platform's Geofencing API. It is never obtained for any other purpose, never stored beyond what's needed for the feature to function (Loc Notice's place definitions — name, coordinates, radius — are stored locally, encrypted, so the app knows what to monitor; no location *fix* itself is ever logged or stored), and never transmitted to QuicLoc's developer or any server QuicLoc operates — QuicLoc has none.

**Provide a video demonstrating the background location use:**
> Record a short screen recording showing, in this order:
> 1. Opening QuicLoc, adding an emergency contact, and demonstrating the widget's safety-check (3 taps) and emergency (4 taps) alerts.
> 2. Adding a phone number as a contact who can also request the location by text.
> 3. Locking the device / closing the app.
> 4. Sending "loc" from that contact's device to trigger a response while the device is locked.
> 5. The automatic GPS reply arriving on the sending device.
> 6. Turning on Loc Notice, adding a place (walking through the address → Maps → paste-coordinates flow), picking a contact, and turning on "notify when I arrive."
> 7. Leaving and re-entering that place's radius (or using a location-simulation tool) and showing the automatic arrival text arrive on the contact's device, with no request sent and the app not open.
>
> Upload this to YouTube (unlisted) and paste the link in the Play Console form. Steps 6-7 are not optional padding — a reviewer who only sees the request-reply demo has no way to verify the *other* declared use of background location, and a mismatch between the written declaration and the demo video is a documented rejection reason.

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
**Yes — Location is shared (not collected).** Location is sent directly, device-to-device, to one or more of the user's own emergency contacts, in three ways: the user tapped the home-screen widget to send a safety-check or emergency alert; a contact the user has specifically allowed to ask requested it by texting the trigger word; or (if the user has turned on the opt-in Loc Notice feature) the user crossed the boundary of a place they configured, and QuicLoc automatically texted the contacts they chose for that place. This is the explicit function of the app. It is never collected, stored, or transmitted to the developer, an analytics service, or any other third party. See [`PLAY_PUBLISHING.md`](PLAY_PUBLISHING.md#data-safety--privacy).

### Data types to declare:
- **Location — Shared.** Purpose: App functionality. Required (it's the app's core function). For request-reply, obtained on demand, held in memory only long enough to send the reply, then discarded — never written to disk or a server. For the opt-in Loc Notice feature, monitored continuously in the background via the Android Geofencing API while that feature is on, so a boundary crossing can be detected and the configured contacts texted automatically — the *place definitions* (name, coordinates, radius, chosen contacts) are stored locally and encrypted on-device so the app knows what to watch, but individual location fixes are still never logged or stored, only evaluated against those boundaries in the moment.
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
**Yes** — All user data (the emergency contact list, and any Loc Notice places) is stored locally on-device and can be deleted at any time by removing entries in the app or uninstalling the app entirely.

---

## 9. App Category and Content Rating

**Category:** Tools / Utilities

**Content Rating Questionnaire:**
Answer **No** to all questions about violence, sexual content, and controlled substances.

The app will receive a rating of **Everyone**.

---

## Notes for Review

If Google Play reviewers contact you for additional clarification, use this response template:

> QuicLoc is a single-purpose personal safety tool built around a home-screen widget: the user taps it to send a safety-check or emergency alert, with their GPS location, to their chosen emergency contacts — no incoming message of any kind is required for this to work. SMS and notification access extend that same mechanism by additionally letting the user's pre-approved trusted contacts request the location themselves by sending a keyword, which the app answers automatically with a Google Maps link. QuicLoc also offers an opt-in feature, Loc Notice (off by default, its own switch), that automatically texts the user's chosen contacts when the user arrives at or leaves a place they've configured, using the Android Geofencing API. All sensitive permissions (SMS, notification access, background location) are used exclusively for these safety functions. No data is collected, stored server-side, or shared with any third party including the developer. The list of emergency contacts and any Loc Notice places are encrypted on-device using Android Keystore-backed AES-256 encryption and are protected by biometric authentication. The app has no network connectivity or backend of its own — it uses only Android system APIs and Google Play Services (GPS, and the Geofencing API for the opt-in Loc Notice feature).
