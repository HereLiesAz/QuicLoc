# Testing

QuicLoc has light unit-test coverage and depends heavily on manual end-to-end testing because most behavior involves system services that can't be faithfully mocked.

## What's automated

Run with: `./gradlew testDebugUnitTest`

All unit tests live in `app/src/test/java/com/hereliesaz/quicloc/`. Most use Robolectric (`@Config(sdk = [34])`); `TutorialsTest` is pure JVM JUnit.

| Test class | Covers |
|---|---|
| [BackupVaultTest](../app/src/test/java/com/hereliesaz/quicloc/BackupVaultTest.kt) | Snapshot lifecycle (no-PIN deletes file, PIN change regenerates with new salt/IV), full round-trip (encrypt → wipe prefs → restore → assert every field), all four testable error categories (NO_BACKUP, TRUNCATED, UNSUPPORTED_VERSION, WRONG_PIN), `isRecoverable` correctness, manual export via URI, export-fails-without-PIN guard |
| [WhitelistManagerTest](../app/src/test/java/com/hereliesaz/quicloc/WhitelistManagerTest.kt) | Add/remove/dedup, normalization, starred 3-cap + slot reuse, removing a number un-stars it, name-vs-number matching (`isWhitelistedByName`), bulk `replaceAllNumbers`/`replaceStarred` (used by restore), my-number / passphrase / PIN / onboarding round-trips, persistence across new manager instances |
| [AppSettingsTest](../app/src/test/java/com/hereliesaz/quicloc/AppSettingsTest.kt) | All defaults (`isEnabled=true`, `reminderNotificationEnabled=false`, all "prompted" flags = false), all setter round-trips, prompted flags are independent |
| [RequestHistoryManagerTest](../app/src/test/java/com/hereliesaz/quicloc/RequestHistoryManagerTest.kt) | Empty default, newest-first ordering, 100-entry cap with correct eviction (sender0…sender19 evicted, sender20…sender119 kept), `clearHistory`, `formattedTime` renders, cross-instance persistence |
| [TutorialsTest](../app/src/test/java/com/hereliesaz/quicloc/TutorialsTest.kt) | `MAIN_ID` resolvable, `byId` unknown returns null, every tutorial has non-empty title/summary/body, IDs are unique + kebab-case, the catalog contains the expected 12 tutorials, the find-my-phone/real-lockdown tutorials (and the Camera/Device Admin/Full-Screen-Intent sections of the permissions tutorial) are present when `FindMyPhone.ENABLED` and absent when it isn't — both directions asserted |
| [ManifestRegistrationTest](../app/src/test/java/com/hereliesaz/quicloc/ManifestRegistrationTest.kt) | Every **base-manifest** receiver (widget, SMS, toggle, boot), service (location reply, notification listener), and activity (main, widget-help) is declared, exported correctly, and where needed declares the right `permission`. Backup XML is wired up in the application tag. **Does not cover `QuicLocDeviceAdmin`, `TrackingService`, or `TrackingLockActivity`** — those live in the on-demand `:feature_findmyphone` module, so they're absent from the base merged manifest Robolectric reads here; the test file has explicit comments marking each as intentionally skipped for that reason. There is currently no manifest-registration test running against the module's own (merged-in) manifest — see "What's NOT automated" below. |

When a unit test fails, that's the first thing to check before assuming the underlying behavior changed.

### `:feature_findmyphone` module tests

The on-demand module has its own test source set (`feature_findmyphone/src/test/java/com/hereliesaz/quicloc/lockdown/`), separate from the base app's. It includes at least
[PinAttemptDecisionTest](../feature_findmyphone/src/test/java/com/hereliesaz/quicloc/lockdown/PinAttemptDecisionTest.kt),
a plain-JUnit test of `PinAttemptDecision.evaluate()` — the pure function `TrackingLockActivity` delegates
wrong-PIN/panic-escalation decisions to (see [LOCKDOWN.md](LOCKDOWN.md#wrong-pin-flow-panic-mode)) — that
pins down the escalate-exactly-once-at-the-3rd-wrong-attempt contract (correct PIN always unlocks
regardless of fail count, a removed/`null` PIN never matches, and every wrong attempt past the third is
`AlreadyLocked` rather than a repeated `PanicTriggered`). Additional Robolectric-based coverage for
`TrackingService` itself is also expected in this same test source set; check the directory for the
current set of test classes rather than relying on this doc to enumerate them, since they're expected to
grow independently of this file.

Everything below has to be exercised on a real device or emulator.

## What's NOT automated (and probably shouldn't be)

- Foreground service lifecycle (start, foreground transition, stop)
- Location fetch (Fused Location Provider behavior)
- Notification listener (depends on a real `NotificationManager` + chat apps)
- SMS receive / send (depends on a SIM)
- MMS send (depends on carrier APN)
- Device Admin grant + `lockNow()`
- CameraX capture
- Auto Backup + restore
- Biometric prompt

## Manual test plan

### 1. SMS trigger (smoke test)

**Pre:** Phone A has QuicLoc installed and Phone B's number whitelisted. Both phones have SMS.

| Step | Expected |
|---|---|
| From Phone B, send `loc` to Phone A | Phone A replies within ~10–30 s with `QuicLoc Location:\nhttps://maps.google.com/?q=…` |
| Send `LOC`, `Loc`, `quicloc` | All trigger (case-insensitive, both keywords) |
| Send `loc me` (extra text) | No reply (must be exact match) |
| Send `loc` from a number NOT whitelisted | No reply |
| Phone A: disable master toggle, repeat | No reply, log line says "QuicLoc disabled" |

### 2. Notification trigger (chat apps)

**Pre:** Phone A has QuicLoc + Notification Access granted, with Phone B's display name in the whitelist (use "Pick from Contacts"). Both phones have WhatsApp.

| Step | Expected |
|---|---|
| From Phone B's WhatsApp, send `loc` to Phone A | Phone A replies in WhatsApp via inline reply with `QuicLoc: https://maps.google.com/?q=…` |
| Repeat with Telegram, Signal, Google Messages | Same |
| From a non-whitelisted contact, send `loc` | No reply |
| In a group chat where Phone B is whitelisted, B sends `loc` | Should still reply (group title may be group name but message sender is B) |
| Send the same `loc` again within 60 s | No duplicate reply (dedupe) |
| Test with content-hidden notifications (Signal private notifications) | No reply — documented limitation |

### 3. Widget

**Pre:** QuicLoc widget on home screen. "My number" set. Two contacts starred. Three more whitelisted.

| Step | Expected |
|---|---|
| Single tap | Widget Help activity opens |
| Double tap quickly | After ~400 ms: widget label "Parking", SMS sent to your own number with `#Parking` |
| Triple tap | Label "Safety Check", SMS to 2 starred contacts with `#SafetyCheck` |
| Quad tap | Label "Emergency", SMS to all 5 whitelisted with `#Emergency` |
| Widget tap with master toggle off | Vibrates but no SMS sent |

### 4. Find-my-phone (cover-screen mode, Device Admin NOT granted)

**Pre:** Passphrase + PIN set, Device Admin NOT granted, app backgrounded or device locked.

| Step | Expected |
|---|---|
| From any phone, send `loc <passphrase>` | Tracking starts, cover-screen lock activity attempts to appear (may not on Android 10+ from SMS trigger — known limitation) |
| Same trigger from a WhatsApp message (with Notification Access) | Cover screen DOES appear (Notification Listener grants BAL) |
| Send the same passphrase again from anywhere | No effect (single-use, was cleared on first fire) |
| Enter wrong PIN 3× | Photo captured, sent via MMS to the triggering number, tracking interval shortens to 1 min |
| Enter correct PIN | Tracking stops, screen unlocks |
| Force-stop the QuicLoc process during tracking | Service restarts via START_STICKY, restores state, continues tracking |

### 5. Find-my-phone (Device Admin granted)

**Pre:** Same as above but Device Admin granted in Settings.

| Step | Expected |
|---|---|
| Send `loc <passphrase>` | Device locks IMMEDIATELY via `DevicePolicyManager.lockNow()` |
| Unlock via system credential | QuicLoc tracking is still running in background; lock screen Activity does not appear because the device is genuinely locked |
| The system unlock alone doesn't stop tracking — user must enter QuicLoc PIN in the activity that's launched from the FGS notification | |

### 6. Backup & restore

**Pre:** Two devices (or fresh install on the same device after clearing data). PIN is set.

| Step | Expected |
|---|---|
| Set up Phone A with whitelist of 5, starred 2, your number, PIN, passphrase | All persisted |
| Trigger Auto Backup: `adb shell bmgr backupnow com.hereliesaz.quicloc` | Backup completes |
| Reset Phone A's app data: Settings → Apps → QuicLoc → Storage → Clear data | Data cleared |
| Trigger restore: `adb shell bmgr restore com.hereliesaz.quicloc` | Restore completes |
| Open QuicLoc, auth, observe | "Restore previous backup?" dialog appears |
| Enter correct PIN | All 5 contacts restored, both starred, your number restored, dialog dismisses |
| Enter wrong PIN | "Incorrect PIN" error, field stays editable for retry |
| Tamper with the file: `adb shell echo "garbage" > /data/data/com.hereliesaz.quicloc/files/quicloc_backup.qlb` (with root) | "This backup file is too short to be valid…" error, Restore button disables, Skip → Close |
| Tap Export, choose a SAF location | File written, toast confirms |
| Tap Import, choose the exported file | Dialog appears, PIN flow same as above |

### 7. Master toggle + reminder notification

| Step | Expected |
|---|---|
| Toggle off in settings | All triggers stop |
| Enable reminder notification | Persistent low-priority notification appears with "Disable" button |
| Tap the notification's "Disable" / "Enable" button | Toggle flips; notification text + action label update |
| Reboot device | Reminder notification reappears (via BootReceiver) — if enabled |
| Disable reminder notification | Notification disappears |

### 8. First-launch / onboarding

**Pre:** Fresh install or cleared data.

| Step | Expected |
|---|---|
| Open app | Biometric prompt |
| Auth | "Why QuicLoc?" tutorial appears full-screen |
| Tap "Got it" | Tutorial dismisses, Config screen appears |
| Tap Info icon → pick another tutorial | Detail screen, Back arrow returns to hub |

### 9. Phone number hint

**Pre:** Fresh install, "My number" empty.

| Step | Expected |
|---|---|
| Finish onboarding, reach Config | System bottom-sheet appears with the phone numbers Google has for you, one-tap to fill |
| Cancel the sheet | Field stays empty, "Auto-detect from this device" button visible |
| Tap the button | Sheet reappears |

## Device-specific gotchas

- **Pixel** — Notification Listener grant survives reboot. Battery optimizer doesn't kill the FGS aggressively.
- **Samsung** — Aggressive battery optimization. User must add QuicLoc to the "Never sleeping apps" list, otherwise the listener and FGS get killed within minutes of backgrounding.
- **Xiaomi/Redmi/Poco (MIUI)** — Notoriously kills background services. Same as above, plus "Autostart" must be enabled in Security app.
- **Huawei** — Similar to Xiaomi. "Protected apps" must include QuicLoc.
- **Android 14+ (any)** — `USE_FULL_SCREEN_INTENT` runtime grant. `FOREGROUND_SERVICE_TYPE_CAMERA` background-start restrictions.

## Logs to watch

Filter by these tags:

```
adb logcat -s QuicLoc.SmsReceiver QuicLoc.NotifListener QuicLoc.ReplyService \
           QuicLoc.TrackingService QuicLoc.LocationHelper QuicLoc.Whitelist \
           QuicLoc.History QuicLoc.Lockdown QuicLoc.Backup QuicLoc.TrackingLockActivity
```

The most useful diagnostic line in normal operation is the trigger detection:

- `SMS from <number>: '<body>'` — incoming SMS body received
- `msg from <sender> via <package>: '<body>'` — incoming chat-app notification parsed
- `Trigger from <sender> — starting LocationReplyService` — match passed, FGS starting
- `Sender <X> not whitelisted, ignoring.` — match failed (most common false alarm)
