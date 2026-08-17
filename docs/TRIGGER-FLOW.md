# Trigger flow

How a single "loc" turns into a Google Maps link in the requester's hand, broken down by trigger source.

## Master gate

Before any path runs, `AppSettings.isEnabled(context)` is checked. When disabled:

- `SmsReceiver` ignores incoming SMS.
- `NotificationListener` ignores notifications.
- `LocationReplyService` aborts non-widget intents on start.
- Widget taps still vibrate (user-initiated) but don't send.

## Path 1: SMS

```
ACTION_SMS_RECEIVED broadcast
        │
        ▼
SmsReceiver.onReceive
        │  AppSettings.isEnabled?
        │  Telephony.Sms.Intents.getMessagesFromIntent(...)
        │  reassemble multipart by sender
        │
        ├─ if body == "loc <passphrase>"    (FindMyPhone.ENABLED gate first)
        │     FindMyPhone.trigger(sender, "SMS")
        │       (starts the :feature_findmyphone TrackingService by
        │        ComponentName; returns false — starts nothing — if
        │        the module isn't installed)
        │     ├─ true  → whitelist.clearPassphraseSync()  (commit, not apply —
        │     │             only burned once tracking has actually started;
        │     │             a trigger that never took effect leaves nothing to
        │     │             guard against, so it isn't worth disarming for)
        │     └─ false → FindMyPhone.requestInstall(context)  (kick off the
        │                   module download; passphrase left ARMED so the
        │                   same "loc <passphrase>" text can retry once it
        │                   finishes)
        │     → see LOCKDOWN.md
        │
        ├─ if WhitelistManager.isWhitelisted(sender)
        │       (PhoneNumberUtils.compare against stored numbers)
        │   and body == "loc" or "quicloc"
        │     LocationReplyService.startForSms(sender)
        │
        └─ else
              ignore
```

### Notable details

- **Multi-part reassembly.** A long SMS arrives as multiple `SmsMessage` parts. We concatenate `messageBody` per `displayOriginatingAddress` so the trigger word match works whether the user sent "loc" in 1 part or 30.
- **Number normalization.** `WhitelistManager.cleanPhoneNumber` strips everything but digits and `+`. `PhoneNumberUtils.compare` handles country-code differences (so `+15551234` matches `15551234` matches `(555) 123-4`).
- **No `goAsync()`.** The receiver returns in ms; the heavy lifting (GPS, SMS send) is in `LocationReplyService`. Calling `startForegroundService` from a broadcast receiver is exempt from the FGS background-start restrictions on Android 12+.
- **Passphrase never logged or recorded raw.** A passphrase-trigger match logs `'[passphrase redacted]'` instead of the real body, and the same redacted string is what's written to the diagnostics log (not gated by the "capture all" diagnostics setting, unlike every other diagnostic row) — the diagnostics screen has a "Share" action that can export the log via any app, so the raw passphrase must never end up in it.

## Path 2: Chat-app notification

```
NotificationManager posts a notification
        │
        ▼
NotificationListener.onNotificationPosted(sbn)
        │  sbn.packageName == ours? → ignore  (self-guard)
        │  sbn.isOngoing → ignore             (system noise)
        │  AppSettings.isEnabled?
        │
        │  extractMessages(notification)
        │      try Notification.MessagingStyle.extractMessagingStyleFromNotification
        │          → per-message [sender, body] from style.messages
        │      else try EXTRA_TEXT_LINES
        │          → split each line on first ':' into sender + body
        │      else fall back to EXTRA_TEXT / EXTRA_BIG_TEXT
        │          → notif title is sender, text is body
        │  take only latest message (older ones already handled)
        │
        │  dedupeKey = "{sbn.key}|{sender}|{body}"
        │  skip if seen within last 60 s
        │
        ├─ if body == "loc <passphrase>"    (FindMyPhone.ENABLED gate first)
        │     sender.any { it.isDigit() }?
        │       no  → log + record why, do nothing further (a chat-app
        │               display name has no phone number to reply to —
        │               starting tracking would lock the device and
        │               "track" a reply that goes nowhere)
        │       yes ↓
        │     FindMyPhone.trigger(sender, packageName)
        │       (starts the :feature_findmyphone TrackingService by
        │        ComponentName; returns false — starts nothing — if
        │        the module isn't installed)
        │     ├─ true  → whitelist.clearPassphraseSync()  (only once tracking
        │     │             has actually started — see the SMS path's identical
        │     │             reasoning)
        │     └─ false → FindMyPhone.requestInstall(context)  (passphrase left
        │                   ARMED so the same message can retry once installed)
        │
        ├─ if WhitelistManager.isWhitelistedByName(sender)
        │       (case-insensitive name match OR PhoneNumberUtils.compare)
        │   and body == "loc" or "quicloc"
        │     replyAction = first action with non-empty remoteInputs
        │     LocationReplyService.startForNotification(sender, package, replyAction)
        │     → if no replyAction: log + record failure
        │
        └─ else
              ignore
```

### Notable details

- **MessagingStyle is the right shape for chat apps.** WhatsApp/Telegram/Signal/Google Messages all post messages as `Notification.MessagingStyle`. The previous naive `EXTRA_TEXT` extraction returned the wrong thing for group chats (title = group name, text = concatenated unreads) and is the root of why "only SMS used to work".
- **Dedupe window.** Messaging apps re-post notifications constantly (read receipts, presence updates). Without dedupe a single "loc" message would fire the trigger every time the app updates the notification. The 60 s window with `sbn.key + sender + body` as the dedupe key is enough.
- **Self-guard.** `if (sbn.packageName == packageName) return` prevents our own foreground-service notifications from being parsed.
- **Inline reply.** We don't open the chat app. We call `Notification.Action.actionIntent.send(...)` with a `RemoteInput` Bundle holding `"QuicLoc: <maps link>"`. The chat app receives it as a normal "type and send" event.
- **Hidden-content notifications never trigger.** Signal in private-notification mode shows "You have a new message" with no body. The trigger string is gone before we see it. We document this in the Trigger Word tutorial.
- **Passphrase never logged or recorded raw.** Same redaction as the SMS path — `'[passphrase redacted]'` in both the log line and the diagnostics record for a passphrase match.

## Path 3: Widget tap

```
PendingIntent.getForegroundService(ACTION_WIDGET_TAP)
        │
        ▼
LocationReplyService.onStartCommand
        │  widgetTapCount++
        │  performHapticFeedback()
        │  postDelayed(widgetTapRunnable, 400 ms)   ← cancels previous post
        │
        │  (after 400 ms of no more taps)
        ▼
widgetTapRunnable
        │  count = widgetTapCount; widgetTapCount = 0
        │  updateWidgetStatus(status text, fade after 3 s)
        │
        ├─ 1 → "Help"   → open WidgetHelpActivity
        ├─ 2 → "Parking" → LocationHelper.handleWidgetTaps(2, ...) — sends to my_number with #Parking
        ├─ 3 → "Safety Check" → ... to starred contacts with #SafetyCheck
        ├─ 4 → "Emergency" → ... to entire whitelist with #Emergency
        └─ else → no-op
```

### Notable details

- **400 ms tap-coalescing window.** The handler is `removeCallbacks`'d and re-`postDelayed`'d on each tap. So 4 taps within 1.6 s land all four; the runnable fires 400 ms after the last tap.
- **Per-count widget label.** The widget's `TextView` is briefly updated with "Parking" / "Safety Check" / "Emergency" so the user knows which message was just sent. Fades after 3 s.
- **Single-tap help.** The 1-tap case opens `WidgetHelpActivity` instead of sending — taught us users will accidentally tap and we don't want to spam #Parking to themselves.

## Common downstream: `LocationReplyService`

```
LocationReplyService.onStartCommand
        │  startForeground(NOTIF_ID, "Fetching location…")
        │      foregroundServiceType = LOCATION (Android 14+)
        │  AppSettings.isEnabled? → abort if disabled (except widget)
        │
        │  switch on EXTRA_REPLY_MODE
        ├─ "sms" → LocationHelper.getCurrentLocationAndReply(sender)
        │              → SmsManager.sendTextMessage
        └─ "notification" → look up Notification.Action via EXTRA_ACTION_TOKEN
                            → LocationHelper.getCurrentLocationAndReplyViaNotification(action)
                                → action.actionIntent.send(...) with RemoteInput
        │
        │  on result (success or failure):
        │      RequestHistoryManager.record(sender, source, succeeded)
        │      stopSelf(startId)
```

### Notable details

- **Per-trigger `Notification.Action` token.** The action can't be put in an intent extra (Parcelable but trims `RemoteInput` data in transit on some OEMs). We hold it in a `ConcurrentHashMap<UUID, Notification.Action>` keyed by a fresh UUID and pass the UUID in `EXTRA_ACTION_TOKEN`. Avoids the static-singleton race where two concurrent triggers clobber each other.
- **History recording.** Both success and failure are recorded with the source string (`"SMS"`, package name, or `"Widget (N taps)"`), so the History tab can tell the user what happened.
- **Stops itself.** Single-shot — no `START_STICKY`. If we're killed mid-fetch, the system doesn't restart us because there's nothing meaningful to resume.

## Location fetch: `LocationHelper.fetchLocation`

Four-stage fallback with a single 60 s deadline:

```
Stage 1  fusedClient.getCurrentLocation(HIGH_ACCURACY)
            └─ on null → Stage 2
Stage 2  fusedClient.lastLocation
            └─ on null → Stage 3 (with remaining time budget)
Stage 3  fusedClient.requestLocationUpdates(1 update, HIGH_ACCURACY)
            └─ onLocationResult → reply
            └─ onLocationAvailability(false) → fail fast
            └─ still pending after 15 s → also start Stage 4, racing it
Stage 4  fusedClient.getCurrentLocation(BALANCED_POWER_ACCURACY)
            └─ resolves first → reply (marked approximate), Stage 3 cancelled
            └─ Stage 3 resolves first → Stage 4 cancelled
60 s deadline timer
   └─ if it fires → fail with "Location timed out"
```

Stage 4 exists so a slow or unavailable GPS fix means "approximate reply", not "no reply": a network/cell-based fix can resolve indoors or with no sky view where GPS can't. Replies built from a fix coarser than ~100m accuracy, or older than a minute (Stage 2's cache), get an inline note (`"(approximate — accurate to ~NNNm)"` and/or `"(as of ~N min ago)"`) so the recipient doesn't mistake it for a live, precise position.

Only if every stage fails do we send an error SMS/reply back to the requester *("QuicLoc Error: Location timed out…")* so they aren't left wondering whether you got the message. Failure is recorded in history.

## Tracking path (passphrase): see [LOCKDOWN.md](LOCKDOWN.md)
