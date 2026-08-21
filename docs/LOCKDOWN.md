# Find-my-phone / Lockdown

This is the passphrase-triggered emergency path. Distinct from the normal "loc" trigger flow.

> **Module boundary.** The entire lockdown feature — `TrackingService`, `TrackingLockActivity`,
> `LockdownController`, `QuicLocDeviceAdmin`, `TrackingAlarmReceiver`, `PinAttemptDecision`, and the
> `IntruderCamera` capture — lives in the on-demand `:feature_findmyphone` dynamic feature module
> (package `com.hereliesaz.quicloc.lockdown`). The base install carries none of it (nor its `CAMERA` /
> `USE_FULL_SCREEN_INTENT` permissions, nor the Device Admin receiver) until find-my-phone is set up
> and the module is downloaded. The base reaches the module **only** through the `FindMyPhone` bridge
> (`app/.../FindMyPhone.kt`), which addresses it by `ComponentName` — including `FindMyPhone.ENABLED`,
> the runtime master switch this whole feature is gated behind. The module fuses into sideload APKs
> (`dist:fusing dist:include="true"`, built as a universal APK via bundletool — see
> [PLAY_PUBLISHING.md](PLAY_PUBLISHING.md)); the Play AAB path keeps it on-demand. Module→base calls
> (e.g. `LocationHelper`, `WhitelistManager`) stay direct via `implementation(project(":app"))`.

## Setup

User sets, in the app:

- A **passphrase** between 10 and 150 characters (any text). Trimmed before storage
  (`WhitelistManager.setPassphrase`) — a stray leading/trailing space from the entry field is stripped
  rather than silently making the stored passphrase never match again; a whitespace-only value is
  stored as unset.
- A **6-digit PIN** they'll need to unlock tracking. The same PIN used to unlock the app and encrypt
  the backup — see [SECURITY.md](SECURITY.md).

Both are stored in `EncryptedSharedPreferences` (`quicloc_secure_prefs`) and in the PIN-encrypted backup blob.

Clearing the app PIN (a separate settings card, `AppPinCard`) while a passphrase is still armed also
clears the passphrase — `MainActivity.onClearAppPin` does both, and the confirmation dialog warns about
this before the user confirms. Without this, removing the PIN would leave an armed trigger that could
never be stopped: the stop-tracking PIN check in `TrackingLockActivity` compares against
`WhitelistManager.getPin()`, and a `null` expected PIN can never match any entered PIN by design (see
`PinAttemptDecision`), so a thief triggering the passphrase after the owner cleared their PIN would lock
and photograph the owner with no way back in.

## Activation

The passphrase fires when any incoming SMS or messaging-app notification body equals `"loc <passphrase>"`
or `"quicloc <passphrase>"`, **regardless of whitelist**. This is intentional, not an oversight: it is
the only place QuicLoc accepts a trigger from a non-whitelisted number, on the assumption your phone has
been stolen, you're texting from a friend's, and the passphrase — not your number — is your proof of
identity. `NotificationListener` additionally requires the notification's sender to contain at least one
digit before triggering; a chat app's display-name-only "sender" has no phone number QuicLoc could reply
to, so triggering on one would lock the device and start "tracking" that silently replies to nobody. SMS
triggers are unaffected — a raw phone number is always present there.

```
trigger detected (SmsReceiver / NotificationListener)
   │
   ▼
FindMyPhone.trigger(context, sender, source)
   │   starts TrackingService by ComponentName;
   │   returns false — and starts nothing — if the
   │   :feature_findmyphone module isn't installed yet
   │
   ├─ started == true:
   │     WhitelistManager.clearPassphraseSync()
   │         commit(), not apply() — must survive a crash between
   │         here and the next boot, now that tracking has genuinely begun
   │
   └─ started == false (module not installed / still downloading):
         FindMyPhone.requestInstall(context)
             best-effort: kick off the download so a retry (or a resend
             of the same trigger text) can succeed without redoing setup
         passphrase is left ARMED — burning it here would permanently
             disarm the feature for zero protective benefit, since nothing
             was actually intercepted-and-replayed to guard against
```

```
TrackingService.onStartCommand   (reads FindMyPhone.EXTRA_SENDER / EXTRA_SOURCE)
   │
   ├─ doTick()             fired first, before locking — async, just kicks off the
   │                        FusedLocationProvider request and returns, giving GPS a
   │                        head start while the screen is still on
   ├─ scheduleNextTick()    arms the first AlarmManager wakeup alarm
   │
   ├─ claimForeground()     idempotent — startForeground with type LOCATION
   │                        (Android 14+); safe to call again on every later tick,
   │                        it just refreshes the existing notification
   │
   └─ engageLockdown()      one-shot — only on a genuinely fresh session (the
         │                  initial trigger, or a freshly-recreated process
         │                  resuming a persisted one) — never on a routine tick,
         │                  which would otherwise relaunch the lock activity over
         │                  whatever the person on-screen was doing, including a
         │                  PIN they were mid-typing
         │
         ├─ startActivity(TrackingLockActivity)   best-effort direct launch;
         │      wrapped in try/catch — may be blocked by Android 10+'s
         │      background-activity-start restrictions depending on caller
         │
         └─ LockdownController.lockNow(this)
               ├─ Device Admin granted? → DevicePolicyManager.lockNow() → true
               └─ Device Admin NOT granted → false (the cover-screen activity
                                              above is the only lock that happens)
```

The tick after a fresh trigger (or a resumed session, on a null-intent restart / `TrackingAlarmReceiver`
tick / boot recovery) always calls `claimForeground()` first, then `engageLockdown()` only if the process
was freshly (re)created — see "Service persistence" below.

## Location updates while tracked

```
doTick()   (called once immediately on trigger, then once per scheduled AlarmManager tick)
   │  LocationHelper.getCurrentLocationAndReply(sender)   — async; returns immediately
   │  onResult(succeeded):
   │      if isPanicMode and photoPathToSend != null:
   │          photoPathToSend = null; saveState()   ← cleared BEFORE the send call, so
   │          sendMmsPhoto(sender, photo)              the photo goes out at most once per
   │                                                    panic escalation — not once a
   │                                                    minute, forever, until the correct
   │                                                    PIN is entered (the old behavior)

scheduleNextTick()
   │  intervalMs = 60_000 (panic mode) or 300_000 (normal)
   │  AlarmManager.set(ELAPSED_REALTIME_WAKEUP, now + intervalMs, TrackingAlarmReceiver)
```

Updates continue until the user enters the correct PIN in `TrackingLockActivity` (or, on a real
device-locked install, reopens QuicLoc and the activity is resumed through the FGS notification's
content intent) — see `TrackingService.stopTracking`.

## Ticking: AlarmManager, not Handler

Ticks are driven by `AlarmManager` wakeup alarms (`TrackingAlarmReceiver`, a manifest-registered
`exported=false` `BroadcastReceiver`), not `Handler.postDelayed`. `Handler.postDelayed` measures against
`SystemClock.uptimeMillis()`, which **stops advancing while the CPU is suspended** — on a locked,
screen-off device (exactly this service's normal operating state, especially once it's locked the phone
itself) that could silently stall the entire tracking cadence indefinitely, with no error and no log
line to notice by.

`scheduleNextTick()` calls `AlarmManager.set(ELAPSED_REALTIME_WAKEUP, ...)` — deliberately the **non-exact**
variant, not `setExactAndAllowWhileIdle`. Exact alarms require the user to grant
`SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, a permission this feature doesn't otherwise need. A non-exact
wakeup alarm still forces a real CPU wake during deep sleep — the actual fix, since `Handler` couldn't do
that at all — Doze may just defer delivery into the next maintenance window by some margin rather than
firing at the exact millisecond, which is an acceptable trade for a 1–5 minute tracking cadence.

Re-scheduling reuses the same stable request code/`PendingIntent`, so there is never more than one tick
alarm armed per session — a service instance being recreated mid-session can't end up with two
overlapping schedules ticking the same session twice. `TrackingService.onDestroy()` also cancels this
instance's scheduled alarm defensively, in case the OS reclaims the instance without an explicit
`stopTracking()` call.

## Service persistence

`TrackingService` is `START_STICKY`. Three independent things can interrupt a running session, and each
has its own recovery path:

1. **This service instance is killed** (OS memory pressure, still the same process). `START_STICKY`
   restarts it with a `null` intent; `onStartCommand` detects that, calls `loadState()` to read
   sender/source/panic-mode/photo-path back from the `quicloc_tracking_state` prefs file, and resumes
   (`claimForeground` → `engageLockdown` → `doTick` → `scheduleNextTick`).
2. **The whole process is killed** (not just this service instance). The next `TrackingAlarmReceiver`
   alarm still fires — alarms survive a process death — and restarts `TrackingService` in a fresh
   process, which rehydrates the same persisted state via `loadState()`. This is the same mechanism that
   makes the periodic ticking itself reliable; recovering from a process kill is really just what
   happens when the next scheduled tick lands on a service that has nothing loaded in memory yet.
3. **The device reboots.** Neither of the above applies — `START_STICKY` only restarts a killed
   *process*, and a reboot cancels every scheduled `AlarmManager` alarm along with everything else.
   `BootReceiver` calls `FindMyPhone.resumeTrackingAfterBoot(context)`, which checks
   `FindMyPhone.wasTrackingActive(context)` (a direct read of the same `quicloc_tracking_state` prefs
   file `TrackingService` itself writes, addressed without a module class reference — see `FindMyPhone`'s
   class doc) and, if a session was active, restarts `TrackingService` via a new shared
   `FindMyPhone.ACTION_TICK` action, which resumes exactly like a normal tick would. Without this, a
   thief power-cycling a tracked device — the single most obvious thing to try — would have ended
   protection permanently, since the passphrase that started the session was already single-use.

**This is not Direct Boot support.** `quicloc_tracking_state` is plain `SharedPreferences`, which lives
in credential-encrypted storage and is unreadable before the device's first post-reboot unlock — so
`resumeTrackingAfterBoot` can only resume tracking once the device has been unlocked at least once since
the reboot (ordinary `BOOT_COMPLETED` semantics), not before any unlock. Resuming *before* any post-reboot
unlock would additionally require the PIN/passphrase themselves — `WhitelistManager`'s Keystore-backed
`EncryptedSharedPreferences` — to be readable pre-unlock too, which they deliberately are not. Trading
that away for a narrower post-theft recovery window isn't worth the loss in at-rest protection for the
whitelist and PIN, so this gap is accepted and documented rather than silently assumed away.

## Wrong-PIN flow (panic mode)

The escalate-at-3 rule is a pure function, extracted so it's directly unit-testable without Compose or
Robolectric:

```
TrackingLockActivity.onPinEntered
   │  PinAttemptDecision.evaluate(pin, expectedPin, failCountSoFar)
   │
   ├─ Unlocked             → TrackingService.stopTracking(this); finishAndRemoveTask()
   ├─ WrongPin(n)           → Toast("Incorrect PIN")             [n < 3]
   ├─ PanicTriggered(n==3)  → triggerPanicMode()                 [fires exactly once —
   │                                                                only on the transition
   │                                                                into the 3rd wrong attempt]
   └─ AlreadyLocked(n > 3)  → Toast("Device Locked.")             [every wrong attempt after
                                                                     the 3rd — no new capture,
                                                                     no new escalation]
```

`PinAttemptDecision` (`feature_findmyphone/.../PinAttemptDecision.kt`) returns `PanicTriggered` only on
the exact transition into the 3rd wrong attempt; every wrong attempt after that returns `AlreadyLocked`,
not another `PanicTriggered`. This matters because `triggerPanicMode()` isn't idempotent — the old
`if (failCount >= 3)` check re-ran it (fresh camera capture, fresh panic-mode escalation) on every single
wrong PIN past the third, unboundedly.

`triggerPanicMode()`:

```
1. If CAMERA is granted:
      IntruderCamera.start(this) { onReady ->
          IntruderCamera.capture(this) { path -> TrackingService.enterPanicMode(this, path) }
      }
   else:
      TrackingService.enterPanicMode(this, null)   ← CAMERA is never requested from the lock
                                                       screen itself; if it wasn't granted during
                                                       setup, panic mode still proceeds, just
                                                       without a photo

2. TrackingService.enterPanicMode(photoPath)
   │  isPanicMode = true
   │  photoPathToSend = photoPath
   │  saveState()
   │  claimForeground()
   │  engageLockdown()                only if this is a freshly-recreated process
   │  scheduleNextTick(immediate = true)   ← fires almost immediately: the resulting tick
                                              both sends the location update and (since
                                              isPanicMode is now true) the one panic photo
```

`IntruderCamera.start()` is called lazily, from inside `triggerPanicMode()` — right before the single
capture it's for, not for the whole lock session. Binding a `CameraX` use case opens the camera device
immediately, lighting the OS's privacy indicator for as long as it stays bound; binding it up front (as
an earlier version did, in `onCreate`) would light that indicator the moment the lock screen appears,
telegraphing what's about to happen well before any capture. The indicator now only lights up right as
the photo is actually taken, matching this feature's "silently captures" framing.

Photo is captured to `externalMediaDirs.firstOrNull()`. Excluded from Auto Backup via
`data_extraction_rules.xml`. Deleted from disk immediately after its one successful MMS send — see "MMS
photo send" below — kept only on a decode/send failure, so a failed attempt doesn't lose the only copy.

## Why two lockdown mechanisms?

Two paths exist because **without Device Admin we can't actually lock the device**. The cover-screen
`TrackingLockActivity` was the original approach; Device Admin is the upgrade. We keep both because:

- Device Admin is opt-in. Users who don't grant it still get *something* (the cover screen).
- The cover screen alone is real but weak — a thief can pull down the notification shade, tap an app,
  and use Recents. It's a deterrent, not a lock.
- Device Admin gives real lock via `DevicePolicyManager.lockNow()`. The thief needs the system unlock
  credential to do anything.

`LockdownController.lockNow` is the single decision point; callers don't have to know about both paths.

## Cover-screen activity gotchas

- `android:showWhenLocked="true"` + `android:turnScreenOn="true"` (and the matching window flags) let it
  appear over the keyguard **without dismissing it** — the user must satisfy both the system unlock and
  the QuicLoc PIN to stop tracking. A previous version also set `FLAG_DISMISS_KEYGUARD`, which on a
  non-secure lock screen actively bypassed the device lock instead of merely covering it; that flag has
  been removed (`TrackingLockActivity.onCreate` documents why in a comment, so it doesn't get re-added by
  habit).
- `onUserLeaveHint` re-launches the activity when the user presses Home. On Android 10+, this background
  activity launch is restricted; works while the activity is still foreground (the launch happens during
  the transition) but isn't guaranteed.
- The PIN field matches the rest of the app's PIN fields: `KeyboardType.NumberPassword`,
  `PasswordVisualTransformation` (masked — not cleartext, since this screen is by design shown in
  public, over the keyguard, to whoever is holding the phone), a digit-only filter, and a 6-character
  cap.
- Requires `USE_FULL_SCREEN_INTENT` permission to bring itself up via the FGS notification on Android
  14+. This permission is **declared in the `:feature_findmyphone` module manifest** (not the base), so
  it's present once the module is installed. On API 34+ the user must still grant the *runtime*
  full-screen-intent toggle separately in Settings (see below).

## Full-screen-intent on Android 14+

On API 34+, `setFullScreenIntent` requires the user to have granted `USE_FULL_SCREEN_INTENT` to the app,
separately from the manifest declaration. Path: Settings → Apps → QuicLoc → Notifications → "Full screen
notifications".

This is prompted for, not just declared: `MainActivity.checkFullScreenIntentPermission()` runs at the end
of the first-launch permission chain (right after Notification Access), shows a rationale dialog, and on
"Continue" hands off to `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.
`AppSettings.wasFullScreenIntentPrompted` flags the auto-prompt as done so later launches don't re-nag. If
the grant is still missing later, a re-entry card in the main settings screen (API 34+ only) offers the
same flow again on demand — see [PERMISSIONS.md](PERMISSIONS.md#use_full_screen_intent-android-14-runtime-grant).

## MMS photo send (klinker library)

`TrackingService.sendMmsPhoto` uses the `com.klinkerapps:android-smsmms` library to send the panic photo.
The library wraps the historically-painful MMS APIs. Only `TrackingService` (in `:feature_findmyphone`)
calls into it, but the `android-smsmms` dependency is declared in **both** the base `:app` module and
`:feature_findmyphone` — see the comment on it in `app/build.gradle.kts`. It can't live in the on-demand
module alone: the AAR's own manifest declares a `<provider>` (`MmsFileProvider`), and content providers
aren't supported in on-demand dynamic feature modules — Android instantiates every declared provider
unconditionally at process start (`ActivityThread.installProvider`), regardless of whether the owning
split is installed. With the dependency only in the module, that provider merged into the base app's
manifest without its dex being guaranteed present, crashing **every** launch —
`ClassNotFoundException: com.klinker.android.send_message.MmsFileProvider` — for any user who hadn't
downloaded the module yet, i.e. anyone who hadn't set up find-my-phone. Declaring the same pinned version
in `:app` puts the provider (and its dex) in the base install unconditionally, so it's always resolvable.

Notes:

- `useSystemSending = true` means we use the carrier's MMS APN, not our own.
- `downsampleForMms()` caps the image at `MMS_MAX_DIMENSION_PX` (800px) on its longer side — a real cap,
  not "roughly": it prefilters with `BitmapFactory.inSampleSize` (which only supports power-of-two
  steps, and can alone leave an image up to ~2x the target on its long side depending on aspect ratio),
  then finishes with an exact `Bitmap.createScaledBitmap` pass so the result never exceeds 800px on
  either dimension. MMS carriers reject or re-compress oversized images.
- Decoding runs on a bare `Thread { ... }.start()` — never on the main thread — and the decode/scale path
  has a dedicated `catch (e: OutOfMemoryError)` alongside the usual `catch (e: Exception)`, since
  `OutOfMemoryError` is an `Error`, not caught by the latter. An uncaught `Error` on this thread would hit
  the default uncaught-exception handler and kill the whole process — taking the foreground service and
  the lock screen down with it, mid-response to a theft — so a very large source image degrading the
  send is handled instead of crashing.
- The local photo file is deleted right after its MMS send succeeds; kept on a decode/send failure so a
  retry or manual recovery is still possible.

## Things to watch out for

- **FGS start from background on Android 12+.** `TrackingService` uses `foregroundServiceType="location"`
  only — the intruder photo is captured by the visible `TrackingLockActivity` (CAMERA permission, no
  camera-typed FGS). `SmsReceiver` calling `startForegroundService` from a `BroadcastReceiver.onReceive`
  is on the Android 12+ background-FGS-start exemption allowlist (see `SmsReceiver`'s own class doc), and
  `TrackingAlarmReceiver`'s `onReceive` → `startForegroundService` call is exempt for the identical
  reason — a manifest-registered `BroadcastReceiver` reacting to a broadcast, in both cases. Both are the
  same shape, so if one is exempt the other is too; there's no remaining hedge about `SmsReceiver` here.
  `NotificationListenerService` gets its own background-start exemption while bound. If you see
  FGS-start crashes on a newer Android version, the first question is whether the *caller* of
  `startForegroundService` still qualifies for an exemption on that version.
- **`startActivity` from a background-started Service.** Forbidden on Android 10+ without
  `SYSTEM_ALERT_WINDOW`. `engageLockdown()` wraps the direct `startActivity` call in try/catch and relies
  on the full-screen-intent path as the fallback.
- **PIN comparison.** Currently `==` on Strings. Not constant-time. The 3-strikes-then-photograph defense
  is what really discourages brute forcing.
- **Don't expand Device Admin policies casually.** We only ever request `force-lock`. Adding anything
  else (wipe, reset password, etc.) changes the trust story dramatically.
