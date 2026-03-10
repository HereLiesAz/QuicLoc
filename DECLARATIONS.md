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

## 4. Data Safety Section

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

## 5. App Category and Content Rating

**Category:** Tools / Utilities

**Content Rating Questionnaire:**
Answer **No** to all questions about violence, sexual content, and controlled substances.

The app will receive a rating of **Everyone**.

---

## Notes for Review

If Google Play reviewers contact you for additional clarification, use this response template:

> QuicLoc is a single-purpose personal safety tool. Its only function is to allow a user's pre-approved trusted contacts to request the user's location by sending a keyword. The app responds automatically with a Google Maps link. All sensitive permissions (SMS, notification access, background location) are used exclusively for this single function. No data is collected, stored server-side, or shared with any third party including the developer. The whitelist of approved contacts is encrypted on-device using Android Keystore-backed AES-256 encryption and is protected by biometric authentication. The app has no network connectivity of its own — it uses only Android system APIs and Google Play Services for GPS.
