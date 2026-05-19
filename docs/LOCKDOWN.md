# Find-my-phone / Lockdown

This is the passphrase-triggered emergency path. Distinct from the normal "loc" trigger flow.

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
TrackingService.startForSms(sender)   or  startForNotification(sender, package)
   │
   ▼
TrackingService.onStartCommand
   │  startForeground with type LOCATION | CAMERA (Android 14+)
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
- Requires `USE_FULL_SCREEN_INTENT` permission to bring itself up via the FGS notification on Android 14+. The user must grant this separately in Settings — we don't currently route them there. **Known gap.**

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

`TrackingService.sendMmsPhoto` uses the `com.klinkerapps:android-smsmms` library to send the panic photo. The library wraps the historically-painful MMS APIs.

Notes:

- `useSystemSending = true` means we use the carrier's MMS APN, not our own.
- We downsample the photo to ~800×800 before sending (MMS carrier limits).
- All MMS work happens on a `Thread { ... }.start()` — never on the main thread.

## Things to watch out for

- **Camera FGS start from background on Android 14+.** `foregroundServiceType="location|camera"` was set so we could start a camera-using FGS from the trigger. On Android 14+, starting a camera-type FGS from background is normally restricted; `NotificationListenerService` grants a temporary exemption, but `SmsReceiver` may not. If you see crashes on Android 14, this is the first place to look.
- **`startActivity` from a background-started Service.** Forbidden on Android 10+ without `SYSTEM_ALERT_WINDOW`. We wrap in try/catch and rely on the full-screen-intent path as the fallback.
- **PIN comparison.** Currently `==` on Strings. Not constant-time. The 3-strikes-then-photograph defense is what really discourages brute forcing.
- **Don't expand Device Admin policies casually.** We only ever request `force-lock`. Adding anything else (wipe, reset password, etc.) changes the trust story dramatically.
