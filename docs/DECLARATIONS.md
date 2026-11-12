# Google Play Declarations for QuicLoc

This document contains the text to submit for each sensitive permission declaration
required by Google Play. Navigate to **Play Console → App content** and complete
each section below.

---

## 1. SMS or Call Log Permissions Declaration

QuicLoc declares **SEND_SMS** for user-configured widget alerts, trusted-contact location replies, Loc Notice alerts, and opt-in find-my-phone tracking. It also declares **RECEIVE_SMS**, only for an optional direct carrier-text backup. The latter is never prompted during first-launch onboarding; users can grant it later from the Permissions panel. The preferred incoming path remains Notification Access, where posted SMS/chat notifications contain message text and an inline reply action.

**Play release blocker:** Optional runtime consent does **not** waive the manifest declaration or Play policy review. Google excludes *family or device locator* and *remote control of user phone* as use cases, and its physical-safety exception allows `SEND_SMS` but **not** `RECEIVE_SMS`. There is no verified exception for QuicLoc's passive receive behavior. Do not submit an approval claim without an affirmative decision from Google. To publish without such an exception, the *Play artifact* must remove `RECEIVE_SMS` from its merged manifest and disable direct receipt. The common manifest currently retains this optional permission as requested, so a regular bundle is **not cleared for Play submission**.

Policy: https://support.google.com/googleplay/android-developer/answer/10208820

---

## 2. Notification Listener Permission Declaration

**Location in Play Console:**
> App content → Sensitive app permissions → Notification access

**Does your app use NotificationListenerService?**
Select: **Yes**

**Describe what your app does with notification access and why it is necessary:**

> QuicLoc uses NotificationListenerService for two purposes, both triggered only by an exact keyword match against a notification's title/body text. First, it detects SMS and other messaging-app location requests through posted notifications (such as WhatsApp, Telegram, and Signal): if the trigger word "loc" or "quicloc" is detected from a pre-approved contact, the app replies via the notification's built-in inline-reply action with the device's GPS location as a Google Maps link. Second, it powers the same opt-in anti-theft feature described in the SMS declaration: if the notification body is exactly `loc <passphrase>` or `quicloc <passphrase>` against the user's own configured passphrase, QuicLoc locks the device and starts sending its location back to whoever sent that message. Like the SMS path, this passphrase check is deliberately not limited to the whitelist — a locked-out owner may be texting from an app or contact QuicLoc has never seen before. (If the notification's sender can't be resolved to an actual phone number QuicLoc can reply to — some chat apps post only a display name — this second path does not fire; it logs why and does nothing further.) No notification content is stored, logged, uploaded, or transmitted anywhere except the location reply sent directly to whoever the app is responding to. Passphrase text itself is never written to logs or the in-app diagnostic history — it's redacted before either. The service does not otherwise read, record, or process notification content from any app or contact not on the user's encrypted whitelist.

---

## 3. Background Location Declaration (Location permissions)

**Location in Play Console:** App content → Sensitive app permissions → Background location.

**Does your app access location in the background?** Yes.

**One feature to enter in the background-location declaration** (subject to the actual app behavior and Play review):

> QuicLoc lets a user respond automatically to an incoming location request from a trusted, explicitly authorized contact while QuicLoc is closed or the phone screen is off. When the authorized contact texts the configured request word, QuicLoc obtains the phone's current location and sends a map link back by SMS without requiring the phone's owner to open QuicLoc. This background location access is essential to unattended location replies; granting only while-in-use access would not support that use case. The user configures the trusted contacts and can disable the feature. Location is requested in response to a trigger, not continuously for this feature.

**Important:** That description is about *one* background-location feature. It does not erase other background-location uses that must be transparently disclosed to users and documented in the privacy policy: **Loc Notice** uses geofences for optional arrival/departure messages; **find-my-phone** performs periodic background fixes during an active, passphrase-triggered tracking session. Approval for background location does not establish permission to use restricted SMS APIs.

**Prominent in-app disclosure and video:** Record an actual Android device showing the app's in-app background-location disclosure, the runtime/system permission flow, a trusted-contact request arriving while QuicLoc is closed, and the resulting reply. Google recommends 30 seconds or less, and requires the video to demonstrate the **single feature entered in the form**. The in-app disclosure must still name *all* background-location uses, including find-my-phone. Confirm the shipped build matches the recording.

**Review checklist:** Check the published privacy-policy URL, in-app privacy-policy link, store listing description, accessible video link, and *every active track* before resubmitting. Google Play may reject an ineligible underlying use case regardless of disclosure quality.

Official guidance: https://support.google.com/googleplay/android-developer/answer/9799150

---

## 4. Camera Permission Declaration

**Location in Play Console:**
> App content → App access

**Describe why your app needs camera access:**

> QuicLoc uses the device's front-facing camera solely for its find-my-phone Panic Mode security feature. If an unauthorized user attempts to bypass the device's lock screen by entering an incorrect QuicLoc PIN 3 times, the app captures one photo of whoever is holding the phone and sends it once, via MMS, to whoever sent the passphrase message that started tracking (see the Background Location declaration — this is not necessarily an existing contact, since a stolen phone's owner may be texting from an unfamiliar number). The camera is opened only at the moment of that single capture, not for the rest of the locked session, so the OS's camera-in-use indicator only appears right as the photo is taken. CAMERA is never requested from the lock screen itself — it is requested once, during find-my-phone setup, before the device is ever locked — so no camera-permission dialog is ever shown to whoever is holding a stolen phone; if it was never granted, panic mode still locks the device, just without a photo. The photo is captured locally, transmitted directly to the triggering phone number, and deleted from the device immediately once that MMS send succeeds (kept only if the send fails, so a failed attempt doesn't lose the only copy). It is never uploaded to a server, and never shared with the developer or any third party.

---

## 5. Full-Screen Intent Permission Declaration

**Location in Play Console:**
> App content → Sensitive app permissions → Full-screen intent permission

**Does your app use the USE_FULL_SCREEN_INTENT permission?**
Select: **Yes**

**Describe why your app needs full-screen intent:**

> QuicLoc declares USE_FULL_SCREEN_INTENT solely for its find-my-phone (passphrase-triggered) safety feature. When the user has set a passphrase and PIN and a trigger message containing that passphrase is received, QuicLoc attempts to launch `TrackingLockActivity` directly, and separately sets a full-screen intent on the tracking-status notification pointing at the same activity. The full-screen intent is what reliably brings the PIN-gate lock screen up over the keyguard on Android versions where a background-started service is blocked from launching an Activity directly — it is wired into every tracking session's notification regardless of whether the device's real lock (`DevicePolicyManager.lockNow()`, via Device Admin, when granted) also succeeds, since the PIN-gate activity is what the owner ultimately stops tracking through either way. This is the only path that fires the full-screen intent, and it is entirely gated on user setup — no passphrase configured, no full-screen intent, ever. The activity displays a PIN prompt and, after 3 failed attempts, triggers panic mode (a single photo capture sent once via MMS, plus a shortened location-update interval). No advertising, notification spam, or non-safety use case relies on this permission.

> **Module placement (maintainer note):** the `USE_FULL_SCREEN_INTENT` permission and `TrackingLockActivity` are declared in the on-demand `:feature_findmyphone` dynamic feature module, not the base. They are merged into the App Bundle's manifest (Play's restricted-permission review sees the full bundle including feature modules), and only reach the *base install's* effective manifest once the user sets up find-my-phone and the module is delivered (or via the fused sideload APK).

---

## 6. Device Admin Permission Declaration

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
**Yes — Location (and, for find-my-phone's Panic Mode, a photo) is shared, not collected.** Location is sent directly, device-to-device, in four ways, all of them explicit app functions the user sets up: the user tapped the home-screen widget to send a safety-check or emergency alert to their own emergency contacts; a contact the user has specifically allowed to ask requested it by texting the trigger word; the user turned on the opt-in Loc Notice feature and crossed the boundary of a place they configured, so QuicLoc automatically texted the contacts they chose for that place; or the user set up the opt-in find-my-phone feature and a message matching their own configured passphrase arrived, so QuicLoc began texting the device's location back to whoever sent that message (see [`DECLARATIONS.md` §3](#3-background-location-declaration-location-permissions) — deliberately not restricted to existing contacts, since a stolen phone's owner may be texting from an unfamiliar number). Find-my-phone's Panic Mode additionally captures and MMS-sends one front-camera photo to that same recipient after 3 wrong unlock-PIN attempts. None of this is ever collected, stored, or transmitted to the developer, an analytics service, or any other third party. See [`PLAY_PUBLISHING.md`](PLAY_PUBLISHING.md#data-safety--privacy).

### Data types to declare:
- **Location — Shared.** Purpose: App functionality. Required (it's the app's core function). For request-reply, obtained on demand, held in memory only long enough to send the reply, then discarded — never written to disk or a server. For the opt-in Loc Notice feature, monitored continuously in the background via the Android Geofencing API while that feature is on, so a boundary crossing can be detected and the configured contacts texted automatically — the *place definitions* (name, coordinates, radius, chosen contacts) are stored locally and encrypted on-device so the app knows what to watch, but individual location fixes are still never logged or stored, only evaluated against those boundaries in the moment. For the opt-in find-my-phone feature, obtained on a repeating timer (every 5 minutes, every 1 minute after panic mode triggers) for as long as tracking is active, and texted to whoever sent the triggering passphrase message; each fix is discarded immediately after it's sent, never written to disk.
- **Photos and videos — Shared.** Purpose: App functionality (find-my-phone's Panic Mode only). One front-facing-camera photo is captured after 3 wrong unlock-PIN attempts on a device find-my-phone has locked, sent once via MMS to whoever sent the triggering passphrase message, and deleted from the device immediately once that send succeeds. Never uploaded to a server, never used for any purpose besides that single send, and never shared with the developer, an analytics service, or any other third party.
- Personal info — not collected
- Financial info — not collected
- Health and fitness — not collected
- Messages — not collected
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

> QuicLoc is a single-purpose personal safety tool built around a home-screen widget: the user taps it to send a safety-check or emergency alert, with their GPS location, to their chosen emergency contacts — no incoming message of any kind is required for this to work. SMS and notification access extend that same mechanism by additionally letting the user's pre-approved trusted contacts request the location themselves by sending a keyword, which the app answers automatically with a Google Maps link. QuicLoc also offers two further opt-in features, each off by default with its own switch: Loc Notice, which automatically texts the user's chosen contacts when the user arrives at or leaves a place they've configured, using the Android Geofencing API; and find-my-phone, an anti-theft feature where the user sets a private passphrase and PIN, and a message matching that passphrase (from any sender — this is intentional, since a stolen phone's owner may be texting from an unfamiliar number) locks the device, begins texting its location back to whoever sent it, and, after repeated wrong unlock attempts, sends one front-camera photo of whoever is holding it via MMS. All sensitive permissions (SMS, notification access, background location, camera, device admin, full-screen intent) are used exclusively for these safety functions. No data is collected, stored server-side, or shared with any third party including the developer. The list of emergency contacts, the find-my-phone passphrase/PIN, and any Loc Notice places are encrypted on-device using Android Keystore-backed AES-256 encryption and are protected by biometric authentication. The app has no network connectivity or backend of its own — it uses only Android system APIs and Google Play Services (GPS, and the Geofencing API for the opt-in Loc Notice feature).
