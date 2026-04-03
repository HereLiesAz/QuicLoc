# Plan

1. **Camera implementation**
   - The user wants a photo taken if the pin fails 3 times. We need to implement a background camera capture.
   - We'll create a `CameraHelper` class using Camera2 API or CameraX to capture an image without showing a preview. (Or wait, user mentioned "WTMP by MidnightDev", which indeed takes photos when a user attempts to unlock).

2. **MMS Implementation**
   - Sending MMS requires `SmsManager.sendMultimediaMessage`. It takes a URI to a PDU. Creating an MMS PDU manually is very complex.
   - Wait, if the app "WTMP" takes a photo and sends it... wait, does WTMP really send it via *MMS*? User says "we need full MMS. the app WTMP by MidnightDev was able to do it." We will just use `SmsManager.sendMultimediaMessage()` to the best of our ability, or utilize a lightweight library if needed, but since we are barebones, we will try to write a simple intent or background service to construct a minimal PDU or just use the device's default `SmsManager`.
   - Actually, Android has an API `SmsManager.sendMultimediaMessage(context, contentUri, locationUrl, configOverrides, sentIntent)`. We need to provide the `contentUri` that points to a valid MMS PDU. Android does not provide a built-in PDU builder!
   - Alternative: Use Klinker's `android-smsmms` library, but let's check if we can add it to gradle or just use `Intent(Intent.ACTION_SEND)` though that requires user interaction. The prompt states it should happen automatically in the background.

3. **Passphrase and PIN feature**
   - We need to add UI to set a 10-150 char passphrase and a 6-digit pin.
   - A single-use passphrase. When an SMS arrives with `quicloc <passphrase>` or `loc <passphrase>`, we check if it matches. If so, we clear the passphrase (single use) and enter a "Tracking Mode".
   - Tracking Mode: sends GPS location every 5 minutes to the sender.
   - To stop tracking, the user must enter the 6-digit pin on the device.
   - We will need a `LockScreenActivity` that pops up over everything (using `SYSTEM_ALERT_WINDOW` or just a standard Activity that brings itself to front).
   - If 6-digit pin is entered wrong 3 times:
     - Device "locks" (keep the lock screen up).
     - Takes a photo (front camera).
     - Sends the photo + location every 1 minute to the sender via MMS.

Let's refine the "Lock" mechanism:
- We can implement a custom `Activity` with `WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED` and `FLAG_DISMISS_KEYGUARD` and `FLAG_KEEP_SCREEN_ON`, or simply use `DevicePolicyManager.lockNow()` (but DPM requires user activation). User says "locks" which could just mean our Activity refuses to go away and hijacks the screen.

Let's check if we can add a simple PDU builder or library.
