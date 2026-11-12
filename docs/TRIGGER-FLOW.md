# Trigger flow

How a single "loc" turns into a Google Maps link in the requester's hand, broken down by trigger source.

## Master gate

Before any path runs, `AppSettings.isEnabled(context)` is checked. When disabled:

- `SmsReceiver` ignores incoming SMS.
- `NotificationListener` ignores notifications.
- `LocationReplyService` aborts non-widget intents on start.
- Widget taps still vibrate (user-initiated) but don't send.

## Path 1: SMS or chat notification (requires Notification Access)

```
SMS/chat app posts a notification containing a message
        │
        ▼
NotificationListener.onNotificationPosted
        │   master switch, ignore own/ongoing notifications
        │   extract MessagingStyle / TextLines / PlainText
        │   latest sender + body only; 60s repost dedupe
        │
        ├─ exact "loc <passphrase>" / "quicloc <passphrase>"?
        │     FindMyPhone.trigger(sender phone number, app package)
        │     ├─ started  → clearPassphraseSync()
        │     └─ failed   → requestInstall(); leave passphrase armed
        │
        ├─ exact "loc" / "quicloc" and sender whitelisted?
        │     ├─ default SMS app + uniquely identified trusted number
        │     │     → LocationReplyService.startForSms(number)
        │     └─ supported notification inline-reply action
        │           → LocationReplyService.startForNotification(action)
        │
        └─ otherwise ignore / record diagnostic failure
```

No SMS broadcast receiver is registered. `RECEIVE_SMS` is not requested. `SEND_SMS` remains for outgoing replies, widget alerts, Loc Notice and find-my-phone. A notification-only SMS request may be missed if the default messaging app suppresses notifications, hides the text, or does not expose a reliably identified sender. For unknown contact names without a reliable phone number, QuicLoc does not guess the recipient.

**Identity and safety:** For default-SMS-app notifications, number matching uses `PhoneNumbers.match`; a trusted contact name is considered only when the default SMS app supplies it and that name maps to exactly one whitelisted dialable number. For chat apps, an arbitrary display name is not sufficient to authorize a reply without a verified contact identity. A passphrase-triggered find-my-phone request still needs a usable sender phone number.

**Duplicate prevention:** The listener drops reposts with the same notification key, sender and body within 60 seconds. The former cross-intake SMS broadcast deduplicator is gone because there is only one inbound path.

**Reply transport:** With a unique trusted number from the default SMS app, reply using `SmsManager`; otherwise use a real `RemoteInput` inline-reply action where available. Missing both yields a diagnostic failure, never an invented phone number.

## Path 2: Widget tap

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
- **History recording.** Both success and failure are recorded with the source (`"SMS"` for a default-app SMS reply, the notification package name, or `"Widget (N taps)"`), so the History tab can tell the user what happened.
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
