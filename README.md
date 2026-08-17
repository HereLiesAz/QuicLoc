# QuicLoc

**QuicLoc lets people you trust check that you're safe, without you touching your phone.**

1. You list the people you trust.
2. One of them texts you the word **`loc`** — to check in on you, or because you didn't answer.
3. Your phone texts back a Google Maps link — by itself, screen off, app closed.

Your location is fetched only at that moment, sent only to that person, and never stored anywhere. No server, no account, no tracking.

---

## Is this the app you want?

**Yes, if** you want a partner or parent to be able to confirm you got home safe without you touching your phone, want a panic button that texts your location to people you've already chosen, or just want to stop typing "on my way" every time someone checks on you.

**No, if** you want a live map of where someone is over time. That's Google Maps Live Location, and QuicLoc deliberately doesn't do it — nothing is streamed, logged, or kept.

The trade-off is the whole design: **someone has to ask.** Between requests, QuicLoc does nothing at all — no polling, no background GPS, effectively zero battery. The one exception is Loc Notice (below): an entirely separate, opt-in feature, off by default, that watches your location in the background to alert people when you arrive at or leave places you've named — turning it on is a deliberate trade of some battery for that convenience, and it has nothing to do with the ask-and-answer design described above.

---

## Setup — four things, about five minutes

The app has a live checklist at the top of its settings screen that tracks all of this. This is the same list.

| # | Step | Why it matters |
|---|---|---|
| 1 | **Turn QuicLoc on** | The master switch, first thing on the settings screen. While it's off, every trigger is ignored. |
| 2 | **Grant text messages + location** | QuicLoc reads incoming texts to spot the trigger word, and texts back a maps link. |
| 3 | **Set location to "Allow all the time"** | *The step people miss.* Android asks twice. "While using the app" means QuicLoc can only answer while you're looking at it — which defeats the point. |
| 4 | **Add at least one emergency contact** | Nobody else can trigger anything. An empty list means QuicLoc ignores the entire world. |

Then **test it**: have someone on your list text you `loc`. A Google Maps link should come back within a few seconds. If it doesn't, open **Diagnostics** in the app — it shows the message arriving and the exact reason QuicLoc did or didn't act.

### Worth doing next

- **Notification Access** — extends the trigger to WhatsApp, Telegram, Signal, Messenger and Google Messages. Without it, only plain text messages work.
- **Battery optimisation exemption** — so a request at 3am isn't slept through. QuicLoc does no background work, so this costs no battery.
- **Your own number + the home-screen widget** — for the safety-check / emergency / parking shortcuts below.

Install from [Releases](../../releases). On first launch you'll authenticate with your fingerprint or device PIN, then be walked through the permissions one at a time — "Step 2 of 5", with a plain-English explanation of what each one is for, what breaks if you skip it, and what QuicLoc will never do with it. Skipping is fine — the checklist lets you come back to any of them.

---

## What's in the app

Settings are grouped by function, one numbered section each, so nothing lives somewhere surprising:

| Section | What's in it |
|---|---|
| **Setup checklist** | Live ✓/✗ for everything QuicLoc needs, each with a one-tap fix |
| **1 · Emergency contacts** | Who gets alerted, who's also allowed to ask, priority ★ stars, add by contact / number / name |
| **2 · The trigger word** | What they send, which apps work, Notification Access |
| **3 · Home screen widget** | Your own number, the tap patterns and what each one needs |
| **4 · Loc Notice** | Passive arrival/departure alerts for saved places — its own on/off switch, independent of the master switch above |
| **5 · Your QuicLoc PIN** | Set / change / remove the PIN that unlocks the app and encrypts your backup |
| **6 · App access & notifications** | How you get in (fingerprint / device PIN / QuicLoc PIN), the optional reminder notification |
| **7 · Permissions** | Every permission with live status and a Grant/Manage button |
| **8 · Backup & restore** | Export / import your setup |
| **9 · Help & troubleshooting** | Tutorials, request history, diagnostics |

(Numbering shifts by one when the find-my-phone feature is enabled — see below.)

---

## Features

- **Answers automatically** — no prompt, no ring, no tap. Works with the screen off and the app closed.
- **Emergency contacts only** — anyone not on your list is ignored silently; they're never told the app exists. Every contact gets the widget's safety-check and emergency alerts; you separately choose which of them, if any, can also request your location themselves by texting the trigger word.
- **Works across messaging apps** — SMS always; WhatsApp, Telegram, Signal, Google Messages, Messenger and any app with an inline notification reply once Notification Access is granted.
- **Home screen widget** — tap it three times for a safety check to up to 3 starred contacts (`#SafetyCheck`), four times for an emergency alert to everyone on your list (`#Emergency`), or twice to just text your own location to yourself, e.g. to find a parked car (`#Parking`). One tap opens a help screen showing which of those are ready to use. Taps must land within about half a second of each other.
- **Master on/off switch** — pauses every trigger without uninstalling. Optionally mirrored in a persistent notification with a one-tap toggle.
- **Loc Notice** — passive, Life360-style place alerts: name a location, pick who gets told, and chosen contacts get a text the moment you arrive or leave — no request needed. A separate master switch from the one above, since passive background watching is a different privacy trade-off from answering when asked. Defining a place never touches a Maps/Places API or needs a billing account — you type an address, confirm it in the Maps app, and copy its coordinates back.
- **Encrypted contact list** — emergency contacts, your own number and starred contacts are stored with AES-256-GCM keyed by the Android Keystore. The data never leaves your device.
- **Locked settings, two ways in** — fingerprint, face or device PIN, *or* a 6-digit QuicLoc PIN of your own for when a fingerprint won't read or the phone has no lock screen at all. The same PIN encrypts your backup. Background answering is deliberately *not* gated, so it keeps working while the phone is locked.
- **Every permission explains itself before it's asked** — what it's for, exactly what breaks if you skip it, what QuicLoc will never do with it, and what screen you're about to see. Skipping is always allowed; the checklist lets you come back.
- **Reliable location** — three-stage fallback (fresh GPS fix → cached location → forced update) so a request answers even from a cold start indoors.
- **Diagnostics** — every trigger QuicLoc saw and the exact decision it reached, in plain English. Built for "I texted loc and nothing happened".
- **No data collection** — no analytics, no crash reporters, no servers. Nothing leaves your device except the location reply, sent directly to the person who asked.

### Temporarily disabled: find my phone

**Passphrase lockdown**, **intruder photo** and **real device lock (Device Admin)** are built but **not shipped in the current build** — the whole feature lives in an on-demand module that is currently excluded from the app, and its UI, tutorials and permissions are hidden along with it. See [docs/LOCKDOWN.md](docs/LOCKDOWN.md). When enabled, a single-use passphrase texted from *any* number locks the phone and reports its location back every 5 minutes.

---

## Nothing happens when someone texts me

In order of how often each one is the cause:

1. **The sender isn't on your list — or is listed differently than they appear.** Texts arrive as a phone number; chat apps arrive as the display name at the top of the notification. A contact saved as `+1 555 0100` will not match a WhatsApp message showing "Mum". Add both — "Pick from Contacts" does it in one tap.
2. **The message wasn't exactly the trigger word.** It has to be `loc` or `quicloc` and nothing else. Case doesn't matter; `loc?` doesn't count.
3. **Location isn't "Allow all the time".**
4. **Notification Access is off and they used a chat app.** Apps that hide message text in their notifications (Signal in private-notification mode, Telegram secret chats) can't work even with it — the text never reaches the phone.
5. **The phone put QuicLoc to sleep.** Battery optimisation, or an OEM "autostart"/"protected apps" list on Xiaomi, Huawei, Oppo and Vivo devices.

**Diagnostics** (in the app, under Help & troubleshooting) tells you which of these it was: if the message isn't in the log at all it never reached the app; if it is, the log states the reason outright.

---

## Permissions

Every permission maps to one feature, and the app shows its live status with a Grant/Manage button per row.

| Permission | Purpose |
|---|---|
| `RECEIVE_SMS` | Detect the trigger word in incoming SMS |
| `SEND_SMS` | Send the location reply |
| `ACCESS_FINE_LOCATION` | Obtain a precise GPS location |
| `ACCESS_COARSE_LOCATION` | Fallback when GPS is unavailable |
| `ACCESS_BACKGROUND_LOCATION` | Answer while the app is closed — the point of the app. Also what Loc Notice uses to detect arrivals/departures. |
| `POST_NOTIFICATIONS` | Show the foreground-service notification while a reply is in flight (Android 13+) |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | Lock the settings screen (a QuicLoc PIN works as an alternative) |
| `READ_PHONE_NUMBERS` | Only to auto-fill your own number for the parking widget — optional |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Detect the trigger in non-SMS messaging apps |

Full reasoning for each, including the Android 13 "restricted settings" trap for sideloaded installs: [docs/PERMISSIONS.md](docs/PERMISSIONS.md), or the Permissions tutorial in the app.

---

## Privacy

QuicLoc does not collect, store, transmit, or share any user data with the developer or any third party. See [PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md) for the full policy.

---

## For developers

### Architecture

Kotlin + Jetpack Compose, single activity, no backend.

- **`SmsReceiver`** — `BroadcastReceiver` for incoming SMS; matches trigger word against the whitelist, hands off to a foreground service.
- **`NotificationListener`** — `NotificationListenerService` that watches messaging-app notifications and replies through their inline reply action.
- **`LocationHelper`** — Fused Location Provider with a four-stage fallback: `getCurrentLocation()` → `lastLocation` → `requestLocationUpdates()`, racing a network/cell fix once GPS is slow — within a 60-second timeout.
- **`WhitelistManager`** — trusted contacts in `EncryptedSharedPreferences` (AES-256-GCM, Android Keystore), with migration of any legacy plaintext data.
- **`Readiness`** — pure-Kotlin derivation of the setup checklist from the live permission snapshot; the same data drives the Permissions table, so the two can't disagree.
- **`BiometricHelper`** — wraps `BiometricPrompt` to gate the UI. Background components are unaffected by auth state.

More: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/TRIGGER-FLOW.md](docs/TRIGGER-FLOW.md).

### Building

Standard Gradle Android project, `compileSdk` 37, `minSdk` 26 (Android 8.0).

```bash
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
```

### Versioning

Versions follow **A.B.C.D**, tracked in `app/version.properties`:

| Segment | Meaning |
|---|---|
| `A` | Major — bumped manually |
| `B` | Feature — bumped when significant features land |
| `C` | Build count within the current `B` |
| `D` | Total build count — used as `versionCode` |

### CI/CD

GitHub Actions builds and tests every push to `main`. Pushing a version tag (`v1.0.0`) builds a **signed** release APK and AAB, verifies the signature against the expected certificate, and publishes them to GitHub Releases. PR builds are unsigned by design (fork PRs can't read secrets); no unsigned artifact is ever published — the publishing jobs fail instead. Secrets and the signing flow: [docs/PLAY_PUBLISHING.md](docs/PLAY_PUBLISHING.md).

### Google Play

Sensitive permissions (`NotificationListenerService`, background location, SMS) require manual Play review — declaration text and Data Safety guidance in [docs/DECLARATIONS.md](docs/DECLARATIONS.md).

Play publishing follows the GitHub release automatically — the Play job is chained onto the release job, so the sideload APK goes out first and Play follows. It uploads the bundle once and puts that same `versionCode` in every track: **rolled out on internal testing, staged as a draft** on closed testing, open testing and production — so promoting a draft later ships the exact artifact internal testers used. Setup and failure modes in [docs/PLAY_PUBLISHING.md](docs/PLAY_PUBLISHING.md).

---

## License

This is free and unencumbered software released into the public domain. See [LICENSE](./LICENSE) for details.
