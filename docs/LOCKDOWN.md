# Find-my-phone / Lockdown

This is the passphrase-triggered emergency path. Distinct from the normal "loc" trigger flow.

> **Module boundary.** The entire lockdown feature — `TrackingService`, `TrackingLockActivity`,
> `LockdownController`, `QuicLocDeviceAdmin`, and the `IntruderCamera` capture — lives in the
> on-demand `:feature_findmyphone` dynamic feature module (package `com.hereliesaz.quicloc.lockdown`).
> The base install carries none of it (nor its `CAMERA` / `USE_FULL_SCREEN_INTENT` permissions, nor the
> Device Admin receiver) until find-my-phone is set up and the module is downloaded. The base reaches
> the module **only** through the `FindMyPhone` bridge (`app/.../FindMyPhone.kt`), which addresses it by
> `ComponentName`. The module fuses into sideload APKs (`dist:fusing dist:include="true"`); the Play AAB
> path keeps it on-demand. See [PLAY_PUBLISHING.md](PLAY_PUBLISHING.md). Module→base calls (e.g.
> `LocationHelper`, `WhitelistManager`) stay direct via `implementation(project(":app"))`.

## Setup

User sets, in the app:

- A **passphrase** between 10 and 150 characters (any text).
- A **6-digit PIN** they'll need to unlock tracking.

Both are stored in `EncryptedSharedPreferences` (`quicloc_secure_prefs`) and in the PIN-encrypted backup blob.

## Activation

The passphrase fires when any incoming SMS or messaging-app notification body equals `"loc <passphrase>"` or `"quicloc <passphrase>"`, **regardless of whitelist**. This is the only place QuicLoc accepts a trigger from a non-whitelisted number — the assumption is your phone has been stolen, you're texting from a friend's, and the passphrase is your proof of identity.

```
trigger detected
   │
   ▼
WhitelistManager.clearPassphraseSync()   ← commit() not apply(): single-use
   │                                       must survive a crash
   ▼
FindMyPhone.trigger(sender, "SMS")   or   FindMyPhone.trigger(sender, package)
   │     (starts the module's TrackingService by ComponentName;
   │      no-op if the :feature_findmyphone module was never installed)
   ▼
TrackingService.onStartCommand   (reads FindMyPhone.EXTRA_SENDER / EXTRA_SOURCE)
   │  startForeground with type LOCATION (Android 14+)
   │  saveState() to quicloc_tracking_state
   │  start trackingRunnable (5-min interval)
   │
   ▼
showForegroundNotification()
   │
   ├─ LockdownController.lockNow(this)
   │     │
   │     ├─ Device Admin granted? → DevicePolicyManager.lockNow()
   │     │                          returns true  ─────────────┐
   │     │                                                     │
   │     └─ Device Admin NOT granted → returns false           │
   │                                                           ▼
   ├─ if returns false:                          device is now locked
   │     setFullScreenIntent on the FGS notification
   │     try startActivity(TrackingLockActivity)
   │         (allowed when triggered from
   │          NotificationListener; usually blocked
   │          from SmsReceiver on Android 10+)
   │
   └─ either way, FGS keeps running and posting location updates
```

## Location updates while tracked

```
trackingRunnable
   │  sendLocationUpdate()
   │      LocationHelper.getCurrentLocationAndReply(sender)
   │  if isPanicMode and photoPathToSend != null:
   │      sendMmsPhoto(sender, photoPath)
   │  postDelayed(this, interval)
   │      interval = 60_000 ms (panic) or 300_000 ms (normal)
```

Updates continue until the user enters the correct PIN in `TrackingLockActivity` (or, on a real device-locked install, the user reopens QuicLoc and the activity gets resumed through the FGS notification's contentIntent).

## Service persistence

`TrackingService` returns `START_STICKY`. If the OS kills it under memory pressure, it restarts with a `null` intent — `onStartCommand` detects this, calls `loadState()` to read sender/source/panic-mode/photo-path from `quicloc_tracking_state` prefs, and resumes where it left off.

`quicloc_tracking_state` is plain `SharedPreferences` — it must work in Direct Boot conditions (post-reboot, pre-user-unlock).

## Wrong-PIN flow (panic mode)

```
TrackingLockActivity.onPinEntered
   │  if pin == expectedPin:
   │     TrackingService.stopTracking(this)
   │     finishAndRemoveTask()
   │  else:
   │     failCount++
   │     if failCount >= 3:
   │         triggerPanicMode()
   │     else:
   │         Toast("Incorrect PIN")
```

`triggerPanicMode`:

```
1. Capture front-camera photo via CameraX
   onError → enter panic mode without photo
   onImageSaved → enter panic mode WITH photo path
   
2. TrackingService.enterPanicMode(photoPath)
   │  isPanicMode = true
   │  photoPathToSend = path
   │  re-post trackingRunnable
   │  → next tick interval is 60_000 ms (1 min) not 300_000 ms
   │  → next tick also sends the photo via MMS
```

Photo is captured to `externalMediaDirs.firstOrNull()`. Excluded from Auto Backup via `data_extraction_rules.xml`.

## Why two lockdown mechanisms?

Two paths exist because **without Device Admin we can't actually lock the device**. The cover-screen `TrackingLockActivity` was the original approach; Device Admin is the upgrade. We keep both because:

- Device Admin is opt-in. Users who don't grant it still get *something* (the cover screen).
- The cover screen alone is real but weak — a thief can pull down the notification shade, tap an app, and use Recents. It's a deterrent, not a lock.
- Device Admin gives real lock via `DevicePolicyManager.lockNow()`. The thief needs the system unlock credential to do anything.

`LockdownController.lockNow` is the single decision point; callers don't have to know about both paths.

## Cover-screen activity gotchas

- `android:showWhenLocked="true"` + `android:turnScreenOn="true"` in the manifest let it appear over the keyguard.
- `FLAG_DISMISS_KEYGUARD` (deprecated but still works) tries to dismiss the keyguard if it's been "swiped away" but unlocked.
- `onUserLeaveHint` re-launches the activity when the user presses Home. On Android 10+, this background activity launch is restricted; works while the activity is foreground (the launching happens during transition) but isn't guaranteed.
- Requires `USE_FULL_SCREEN_INTENT` permission to bring itself up via the FGS notification on Android 14+. This permission is **declared in the `:feature_findmyphone` module manifest** (not the base), so it's present once the module is installed. On API 34+ the user must still grant the *runtime* full-screen-intent toggle separately in Settings (see below).

## Full-screen-intent on Android 14+

On API 34+, `setFullScreenIntent` requires the user to have granted `USE_FULL_SCREEN_INTENT` to your app, separately from the manifest declaration. Path: Settings → Apps → QuicLoc → Notifications → "Full screen notifications".

We declare the permission. We don't yet prompt the user to grant it — should be added. `AppSettings.wasFullScreenIntentPrompted` is the gate but it's not wired up to anything yet.

## Re-entering panic mode after restart

If the service was killed mid-panic and restarted via `START_STICKY`:

- `loadState()` reads `isPanicMode = true` and `photoPathToSend = "..."` from prefs.
- The runnable restarts on the panic 60 s interval.
- The next tick sends the location *and* the saved photo path's MMS.

The photo file persists on external media until the user clears it. We don't auto-delete because the user might want to see what the intruder looked like.

## MMS photo send (klinker library)

`TrackingService.sendMmsPhoto` uses the `com.klinkerapps:android-smsmms` library to send the panic photo. The library wraps the historically-painful MMS APIs. The `android-smsmms` dependency lives in the `:feature_findmyphone` module (only `TrackingService` uses it), not the base.

Notes:

- `useSystemSending = true` means we use the carrier's MMS APN, not our own.
- We downsample the photo to ~800×800 before sending (MMS carrier limits).
- All MMS work happens on a `Thread { ... }.start()` — never on the main thread.

## Things to watch out for

- **FGS start from background on Android 14+.** `TrackingService` uses `foregroundServiceType="location"` only — the intruder photo is captured by the visible `TrackingLockActivity` (CAMERA permission, no camera-typed FGS). `NotificationListenerService` grants a temporary background-start exemption; `SmsReceiver` may not. If you see FGS-start crashes on Android 14, this is the first place to look.
- **`startActivity` from a background-started Service.** Forbidden on Android 10+ without `SYSTEM_ALERT_WINDOW`. We wrap in try/catch and rely on the full-screen-intent path as the fallback.
- **PIN comparison.** Currently `==` on Strings. Not constant-time. The 3-strikes-then-photograph defense is what really discourages brute forcing.
- **Don't expand Device Admin policies casually.** We only ever request `force-lock`. Adding anything else (wipe, reset password, etc.) changes the trust story dramatically.
