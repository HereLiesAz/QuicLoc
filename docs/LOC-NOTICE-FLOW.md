# Loc Notice flow

How a saved place turns into an automatic "arrived"/"left" text — no request needed. Unlike the [request-reply paths](TRIGGER-FLOW.md), this one runs entirely in the background, driven by Play Services' `GeofencingClient` rather than an incoming message.

## Master gates

Two independent switches, both must be satisfied:

- `AppSettings.isLocNoticeEnabled(context)` — Loc Notice's own master switch, separate from `AppSettings.isEnabled` (the request-reply master switch). A user can have one on without the other.
- Each `GeofenceEntry.enabled` — a location can be individually paused without deleting it.

`GeofenceRegistrar.sync(context)` is the single place both are checked before anything is registered with Play Services.

## Registration side: saving a location

```
User saves a location (LocNoticeEditScreen)
        │
        ▼
GeofenceStore.add / .update
        │  EncryptedSharedPreferences("quicloc_locnotice_prefs")
        │  BackupVault.snapshotAsync (portable copy — see below)
        │
        ▼
GeofenceRegistrar.sync(context)
        │  removeGeofences(pendingIntent)         ← full reconciliation,
        │  ↓                                        not incremental diffing
        │  isLocNoticeEnabled? and background location granted?
        │  entries = GeofenceStore.getAll().filter { enabled }.take(100)
        │  addGeofences(GeofencingRequest{
        │      setInitialTrigger(0)                ← suppress "already inside"
        │      each entry → ENTER | EXIT            (both directions always;
        │  }, pendingIntent)                          see Notable details)
```

`sync()` also runs from: `BootReceiver` (geofences don't survive reboot), `BackupVault.applyJson` (a restore takes effect immediately), and whenever the master toggle or a per-location enabled switch flips.

## Firing side: a crossing happens

```
Play Services detects a crossing
        │
        ▼
GeofenceBroadcastReceiver.onReceive (ACTION_GEOFENCE_EVENT)
        │  goAsync() — see Notable details for why this is safe here
        │  GeofencingEvent.fromIntent(intent)
        │
        │  for each triggeringGeofence:
        │      GeofenceStateStore.evaluate(id, transition)
        ├─ DUPLICATE          → silently skip (Play Services redelivery)
        ├─ FLAP_SUPPRESSED    → log LOCNOTICE_FLAP_SUPPRESSED, no text sent
        └─ PROCESS
              │  GeofenceStore.get(id) → missing/disabled? log + skip
              │  direction (enter/exit) off for this entry? log + skip
              │  resolve entry.contactTokens against WhitelistManager.getContacts()
              │      (dialable numbers only — dangling/name-only tokens skipped)
              │  no resolvable contacts? log LOCNOTICE_NO_CONTACTS, record history(false)
              │  else:
              │      SmsSender.send to each contact:
              │        "QuicLoc Loc Notice: arrived at \"<name>\"" / "left \"<name>\""
              │      RequestHistoryManager.record(name, "Loc Notice (arrived/left)", succeeded)
              │      DiagnosticLogManager.record(LOCNOTICE_SENT / LOCNOTICE_SEND_FAILED)
```

## Defining a location: the clipboard handoff

Loc Notice never calls a Maps/Places/Geocoding API — no API key, no billing account. Both directions go through `MapsHandoff`:

- **Forward (address → Maps).** The user types an address; `MapsHandoff.openInMaps` copies it to the clipboard and launches `ACTION_VIEW` on a `geo:0,0?q=<address>` URI, landing in Maps pre-searched (or the system chooser if no maps app resolves) so the user can visually confirm/refine the pin themselves.
- **Backward (Maps pin → coordinates).** The user locates/drops a pin in Maps and uses Maps' own "copy coordinates" action, then taps "Paste location" back in QuicLoc — `MapsHandoff.pasteFromClipboard` reads the clipboard and hands it to `CoordinateParser`, a pure-Kotlin `"lat, lng"` parser.

The paste step is a manual button tap, not an automatic read-on-resume: Android 10+ restricts clipboard reads to the foreground app, and QuicLoc *is* foreground again once the user switches back — so the manual tap works reliably, and an auto-detect alternative couldn't distinguish "just copied from Maps" from unrelated clipboard content anyway.

## Notable details

- **`setInitialTrigger(0)`.** Without this, Play Services fires a synthetic "already inside" callback the moment a geofence is registered. Since registration re-runs on every reboot (via `BootReceiver`), an unsuppressed initial trigger would refire "arrived home" for anyone already at a monitored place when their phone restarts. Suppressing it means this alerts on genuine crossings only, matching a Life360-style place-alert model rather than a "tell me my current status" one.
- **Both directions always registered.** `GeofencingRequest.setInitialTrigger`/transition types are a single bitmask for the whole batched `addGeofences` call, not per-geofence — so a location's own `notifyOnEnter`/`notifyOnExit` preference is filtered downstream in `GeofenceBroadcastReceiver`, not at registration. This also means flipping a direction toggle never needs a re-sync.
- **`PendingIntent.FLAG_MUTABLE` is required.** Play Services fills in the `GeofencingEvent` extras itself when it delivers the broadcast; `FLAG_IMMUTABLE` would silently break delivery.
- **`goAsync()`, not a foreground service.** `LocationReplyService` needs an FGS because a fresh GPS fix can take 20-60s. A geofence transition doesn't need a fresh fetch — Play Services has already determined the crossing — so `GeofenceBroadcastReceiver`'s `goAsync()` budget (~10s) is comfortably enough for prefs reads and SMS sends.
- **Full remove-then-re-add reconciliation.** `GeofenceRegistrar.sync` doesn't diff adds/removes — it removes everything under its `PendingIntent`, then re-adds the current desired set. At Loc Notice's scale (a handful of named places) this is cheap, and it means every mutation site is just "mutate the store, then sync," with no way for an OS-level registration to drift from the store (e.g. a deleted place that keeps firing).
- **60s anti-flap cooldown.** GPS jitter right at a boundary can cause rapid ENTER/EXIT flapping. `GeofenceStateStore` still records the true state on a flap, but suppresses the text within a 60s window per (geofence, direction) so a contact doesn't get several texts for one real crossing.
- **100-geofence cap.** Android limits `GeofencingClient` to 100 active geofences per app. `GeofenceRegistrar.sync` defensively truncates rather than letting `addGeofences` throw; the location list UI warns as the count approaches the limit.
- **Backup.** Location definitions (name, coordinates, radius, contacts, flags) round-trip through `BackupVault`'s PIN-encrypted blob, same as the whitelist. The live `quicloc_locnotice_prefs` `EncryptedSharedPreferences` file and the ephemeral `quicloc_geofence_state` transition bookkeeping are both excluded from Android Auto Backup — the former because its Keystore key doesn't survive restore (the vault is the real portable copy), the latter because mid-flight state is meaningless on another device.
