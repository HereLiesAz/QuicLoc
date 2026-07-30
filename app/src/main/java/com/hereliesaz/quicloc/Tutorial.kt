package com.hereliesaz.quicloc

/**
 * A single tutorial — title, one-line summary for the hub list, and full body.
 * Bodies are plain text with `•` bullets and blank lines for paragraph breaks;
 * the renderer just shows them in a Text composable so no markdown processing
 * is needed.
 *
 * @param requiresFindMyPhone true for tutorials that document the on-demand
 *   find-my-phone / lockdown feature. Those are filtered out of [Tutorials.visible]
 *   while [FindMyPhone.ENABLED] is false, so the hub never explains a feature
 *   that isn't in the shipped app.
 */
data class Tutorial(
    val id: String,
    val title: String,
    val summary: String,
    val body: String,
    val requiresFindMyPhone: Boolean = false,
)

object Tutorials {

    const val MAIN_ID = "why-quicloc"

    /**
     * The flagship tutorial — shown on first launch. Pitches QuicLoc's
     * on-demand model against Google Maps' continuous live-location sharing.
     */
    private val why = Tutorial(
        id = MAIN_ID,
        title = "Why QuicLoc?",
        summary = "How QuicLoc differs from continuous location sharing.",
        body = """
QuicLoc shares your location on demand — never continuously.

Google Maps "Live Location" (and most other location-sharing apps) streams your position to a server the whole time it's enabled. Anyone you've shared with — and Google itself — has a continuous trail of where you've been. It drains your battery, and the data lives on a server you don't control.

QuicLoc works the other way around:

• Your location is fetched ONLY when a trusted contact asks for it by sending the trigger word.
• The coordinates are sent straight from your phone to theirs as a Google Maps link, then discarded.
• Nothing is stored. No server. No analytics. No background polling.

That means:

• Battery: zero overhead between requests. The GPS only turns on for the few seconds it takes to answer a request.
• Privacy: your contacts know where you are when they ask, not where you've been. There's no log on any server. If you uninstall QuicLoc, there's nothing left to delete.
• Safety: it works when you're the one who needs help, too. The home-screen widget sends your location to your starred contacts, or to everyone on your list, without you having to unlock anything or type a word.

The trade-off: someone has to actively ask. QuicLoc isn't for "let's see who's closer to the meeting spot in real time" — that's still Google Maps' job. It's for "answer when asked, otherwise stay invisible."

To set it up, read "Getting started in 5 minutes" next — or just follow the checklist at the top of the settings screen, which tracks the same steps live.
""".trim(),
    )

    /**
     * The do-this-now companion to [why] — the shortest path from "installed"
     * to "a contact texts loc and it answers".
     */
    private val gettingStarted = Tutorial(
        id = "getting-started",
        title = "Getting started in 5 minutes",
        summary = "The four things to do before QuicLoc will answer anyone.",
        body = """
QuicLoc does nothing until four things are true. The checklist at the top of the settings screen tracks all of them live — this is the same list, with the reasoning.

1. QuicLoc is switched ON.

The master switch is the first thing on the settings screen. While it's off, every trigger is ignored — texts, chat apps, and the widget.

2. Permissions are granted.

• Text messages: QuicLoc reads incoming texts to spot the trigger word, and sends the reply back by text.
• Location, set to "Allow all the time": this is the one people miss. Android asks twice — once for location, then again for background access. If you only grant "While using the app", QuicLoc can answer only while you happen to be looking at it, which defeats the point.
• Notifications (Android 13+): QuicLoc posts a short-lived notification while a reply is being sent. Without permission, Android kills the reply halfway.

If you tap Skip on any of these, nothing is lost — go to the checklist and tap the button on that row to be asked again.

3. At least one trusted contact is on your list.

Nobody who isn't on the list can trigger anything. With an empty list, QuicLoc ignores the entire world. Use "Pick from Contacts" — it adds the person's name *and* their numbers, which covers texts and chat apps in one go.

4. Test it.

Get someone on the list to text you "loc". You should get a Google Maps link back within a few seconds — no prompt, no sound, nothing to tap.

If nothing comes back, open Diagnostics (in Help & troubleshooting). It shows the message arriving and the exact reason QuicLoc did or didn't act — no guessing.

Worth doing next:

• Notification Access, if your people use WhatsApp / Telegram / Signal / Messenger rather than plain texts.
• Battery optimisation exemption, so a request at 3am isn't slept through.
• Your own phone number and the home-screen widget, if you want the parking / safety-check / emergency shortcuts.
""".trim(),
    )

    private val trustedContacts = Tutorial(
        id = "trusted-contacts",
        title = "Trusted Contacts (Whitelist)",
        summary = "Who can trigger QuicLoc, and how matching works.",
        body = """
Only people on your whitelist can request your location.

You can add a contact in three ways:

• Pick from Contacts — adds both the display name and every phone number on file.
• Type a phone number — matched against incoming SMS.
• Type a name — matched against the sender's name in chat-app notifications (WhatsApp, Telegram, Signal, etc.).

Why both? SMS arrives with a phone number. Chat apps post notifications with the sender's display name from your address book. Adding a contact via the picker covers both.

Star up to 3 contacts as priority for the widget's 3-tap Safety Check.

Nothing is shared with these contacts unless they explicitly ask. They send "loc" or "quicloc" — QuicLoc answers with a Google Maps link.
""".trim(),
    )

    private val trigger = Tutorial(
        id = "trigger-word",
        title = "The Trigger Word",
        summary = "What to text, and which apps work.",
        body = """
A whitelisted contact sends you the message "loc" or "quicloc" (case-insensitive). QuicLoc fetches your location and replies with a Google Maps link.

Works in:

• SMS / MMS (always)
• Any messaging app that posts a notification with an inline "Reply" action — WhatsApp, Telegram, Signal, Google Messages, Samsung Messages, Facebook Messenger, Instagram DMs, and most others.

Doesn't work in:

• Apps that hide message content in notifications by default (Signal in private-notification mode, Telegram secret chats). The trigger never reaches QuicLoc because the system never sees the message text.
• Email clients without inline reply actions.

To enable chat-app support, grant "Notification Access" when prompted. QuicLoc only reads the title and body of incoming notifications, and only acts when both the sender is whitelisted and the message is exactly the trigger word.
""".trim(),
    )

    private val findMyPhone = Tutorial(
        id = "find-my-phone",
        title = "Find My Phone (Single-Use Passphrase)",
        summary = "Lock and locate your phone from any number.",
        body = """
For emergencies — like a stolen or lost phone — QuicLoc has a passphrase mode that works even from numbers NOT on your whitelist.

Setup:

• Pick a passphrase between 10 and 150 characters. Make it something only you'd know.
• Pick a 6-digit PIN. You'll need this to unlock the phone if the passphrase fires.

To activate from any phone:

• Text "loc <your passphrase>" — for example: "loc my passphrase here".

What happens next:

• Your phone enters tracking mode. Location is sent back to the number that triggered, every 5 minutes.
• The screen is locked. If Device Admin is granted, the device is actually locked; otherwise QuicLoc covers the screen.
• Your PIN is required to stop tracking.

If someone enters the wrong PIN 3 times:

• The front camera takes a photo of them.
• Tracking escalates to every 1 minute.
• The photo is sent to the same number via MMS.

The passphrase is single-use — once triggered, it's cleared. Set a new one before the next emergency.
""".trim(),
        requiresFindMyPhone = true,
    )

    private val realLockdown = Tutorial(
        id = "real-lockdown",
        title = "Real Lockdown (Device Admin)",
        summary = "The difference between covering the screen and locking the device.",
        body = """
Without Device Admin rights, QuicLoc's lockdown can only show an Activity over the screen. A determined thief can pull down the notification shade, tap an app from there, or use Recents to bypass it.

With Device Admin rights, QuicLoc calls Android's DevicePolicyManager.lockNow() — the same call the system uses when you press the power button. The device is genuinely locked, and the thief needs the system PIN (not just QuicLoc's PIN) to use it at all.

What QuicLoc can do as Device Admin:

• Lock the screen. That's it.

What QuicLoc cannot do, ever:

• Wipe your data
• Change your PIN or password
• Prevent uninstall
• Read anything else on the device

You can revoke admin rights at any time in Settings → Security → Device admin apps. Granting it is optional — but the find-my-phone feature is much weaker without it.
""".trim(),
        requiresFindMyPhone = true,
    )

    private val widget = Tutorial(
        id = "widget",
        title = "Homescreen Widget",
        summary = "Tap counts for parking, safety check, and emergency.",
        body = """
The widget is the other direction: it sends your location because YOU decided to, without anyone asking.

To add it: long-press an empty spot on your home screen, choose "Widgets", find QuicLoc, and drag it out.

Tap pattern — each tap has to land within about half a second of the last one, so tap briskly:

• 1 tap — opens the widget help screen. Nothing is sent.
• 2 taps — Parking: texts your location with #Parking to your own number (set "Your own phone number" in section 3 of settings)
• 3 taps — Safety Check: texts your location with #SafetyCheck to your starred contacts (up to 3)
• 4 taps — Emergency: texts your location with #Emergency to every trusted contact

The widget vibrates on each tap to confirm, and shows the action label briefly once the taps stop.

Each pattern fetches a fresh GPS fix before sending — even if you're indoors with poor signal, the three-stage fallback (cached → fresh → forced) usually gets a position within 15 seconds.

If a tap count has nothing to send to — no own number, no starred contacts, no trusted contacts — nothing is sent. The widget help screen (1 tap) shows which of the four are ready.
""".trim(),
    )

    private val toggle = Tutorial(
        id = "toggle",
        title = "Enable/Disable Toggle",
        summary = "Pause all triggers without uninstalling.",
        body = """
The master switch in the first card of the settings screen pauses every QuicLoc trigger:

• SMS triggers are ignored.
• Notification triggers are ignored.
• Widget taps still vibrate but don't send anything.

Use this when:

• You're handing the phone to someone temporarily.
• You're in a meeting and don't want noise from a #Parking tap.
• You're traveling somewhere you don't want even trusted contacts pinging you.

For one-tap access without opening the app, turn on "Reminder notification" under "App access & notifications" in settings — a permanent silent notification with an Enable/Disable button appears in the shade. It's opt-in (off by default).

The setting persists across reboots.
""".trim(),
    )

    private val appLock = Tutorial(
        id = "app-lock",
        title = "Unlocking the app (fingerprint or PIN)",
        summary = "Two ways in, and why the background half isn't locked at all.",
        body = """
Opening QuicLoc's settings requires proof it's you. Answering a location request does not — that has to keep working while the phone is locked in your pocket, which is the entire point of the app.

There are two ways in, and you can use either:

• Your phone's own lock — fingerprint, face, or the device PIN/pattern. This is the default when your phone has a lock screen set up.
• Your QuicLoc PIN — a 6-digit PIN that belongs to this app. Set it in the "Your QuicLoc PIN" section of settings, which has its own numbered step on the settings screen.

Why bother with a QuicLoc PIN?

• Fingerprints fail. Wet hands, cold weather, a cracked sensor. The PIN is always available as a fallback — tap "Use QuicLoc PIN instead" on the unlock screen.
• Some phones have no lock screen at all. Without a QuicLoc PIN, QuicLoc has nothing to check and simply lets whoever is holding the phone straight into your trusted-contact list. With one, it doesn't.
• The same PIN encrypts your backup. No PIN means no backup, and no way to carry your contacts to a new phone.

Things to know:

• There is no reset. Nobody — not us, not Google — can recover a forgotten QuicLoc PIN. If you forget it and your phone has a lock screen, you can still get in with your fingerprint and change it.
• Removing the PIN deletes the encrypted backup along with it, because the PIN is the only key it has.
• The background components — the SMS receiver, the notification listener, the reply service — are deliberately not gated by any of this. They answer whether the app is locked, closed, or has never been opened today.
""".trim(),
    )

    private val backup = Tutorial(
        id = "backup-restore",
        title = "Backup & Restore",
        summary = "How your settings survive a new phone.",
        body = """
Your settings (whitelist, starred contacts, PIN, passphrase, your number) are stored encrypted on-device using a key held in the Android Keystore. That key does not transfer to a new phone — so an extra layer is needed for backups to work.

QuicLoc solves this by writing a second copy of your settings, encrypted with your QuicLoc PIN (set in the "Your QuicLoc PIN" section of settings), into a file at app data. That file is included in Android's automatic backup (Google Drive) and in device-to-device transfer (Pixel Switch, Smart Switch, Quick Start).

How restore works:

• Install QuicLoc on the new device after restoring from Google backup or via device transfer.
• On first launch, QuicLoc detects the encrypted blob and prompts you to enter your previous PIN.
• Decrypt → your contacts, starred list, your own number, PIN, and passphrase all come back as if nothing happened.

If you skip the restore prompt, the blob gets overwritten as soon as you set up the new install — so only skip if you actually don't want the old data.

Manual export / import:

• "Export" writes the same encrypted blob to a location you choose (Drive, email, USB, etc.). Use this if you want a copy somewhere outside Google's backup.
• "Import" reads a blob you select and prompts for the PIN that was set when it was made.

Both share the same format, so a manual export can be imported into an Auto-Backup-restored install and vice versa.

Notes:

• The backup is only created once you set a PIN — no PIN, no encryption key, no backup.
• If you change your PIN, the backup is immediately re-encrypted with the new one.
• A wrong PIN at restore is indistinguishable from a corrupted file (cryptographically, that's the same failure). Triple-check your PIN before assuming the backup is bad.
""".trim(),
    )

    private val permissions = Tutorial(
        id = "permissions",
        title = "Permissions",
        summary = "Why QuicLoc needs each permission.",
        body = """
QuicLoc asks for several sensitive permissions. Each one is tied to a specific feature — none is collected for analytics or sent off the device. Here's the full list and exactly why each must be granted:

Messaging

• Receive SMS: the SmsReceiver inspects incoming texts for the trigger word. Without it, SMS-based requests can't be detected at all.
• Send SMS: the reply with your Maps link is sent through SmsManager. Without it, QuicLoc can detect a request but can't answer.
• Notification Access (special access, granted in Settings): reads only the title and body of incoming notifications so the trigger works inside WhatsApp, Telegram, Signal, etc. QuicLoc ignores everything not from a whitelisted sender containing the exact trigger word.

Location

• Fine Location: gets a GPS fix accurate enough to be useful as a Maps link. Used only while answering a request.
• Coarse Location: fallback when GPS isn't available (indoors, denied sky view). Same usage window as Fine.
• Background Location ("Allow all the time"): the entire point of QuicLoc is to respond while the screen is off and the app is closed. Android 11+ won't let apps ask for this directly, so you'll be sent to the system Location Settings screen to flip it manually. Without it, QuicLoc only works while you happen to have the app open.

Camera

• Camera: in find-my-phone (passphrase) mode, after 3 failed PIN attempts the front camera silently captures one frame of whoever is holding your phone and sends it to the requester via MMS. This is delivered as an optional add-on module that downloads only when you set up find-my-phone — so QuicLoc doesn't ask for camera access at all unless you use that feature. Granted right after the download (before the lock screen can ever need it).

Authentication

• Biometric: gates the QuicLoc configuration UI behind your fingerprint or face. The background services keep working regardless, but no one can change your whitelist or PIN without unlocking the app.
• Use Fingerprint: the legacy (pre-Android 9) name for the same capability. Both are declared so older devices are covered.

Foreground services (keep work alive when the screen is off)

• Foreground Service: required by Android to start any service that needs to run while the app isn't visible. Both the location-reply service and the tracking service rely on this.
• Foreground Service – Location (Android 14+): the type-specific grant that tells Android "this foreground service is allowed to use location." Without it, Android 14+ would kill the reply mid-fetch.

Notifications

• Post Notifications (Android 13+): required to show the foreground-service notification while a reply is in flight, the optional always-on reminder, and the tracking-active alert. Without it, services would be killed silently by the system.
• Full-Screen Intent: lets the find-my-phone trigger surface a full-screen lock activity (cover-screen mode) when Device Admin isn't granted. On Android 14+ you'll also need to enable "Full screen notifications" for QuicLoc in system Settings.

System integration

• Boot Completed: lets QuicLoc re-post the persistent reminder notification after a reboot if you've opted into it. It does not auto-start any services beyond that notification.
• Vibrate: short haptic confirmation when you tap the home-screen widget, so you know the request actually fired.
• Internet + Network State: required transitively by Google Play Services (the fused location provider and the Phone Number Hint API). QuicLoc itself makes no HTTP calls — there is no backend.

Contacts (optional)

• Read Contacts: only used by the "Pick from Contacts" button when you're populating the whitelist. Skip it and you can still type numbers and names manually.

Device Admin (optional, special access)

• Bind Device Admin: only consumed by lockNow() so the find-my-phone passphrase can actually lock the screen instead of just covering it. Cannot wipe data, change your PIN, or block uninstall. Revocable any time in Settings → Security → Device admin apps.

Protected (background reliability)

These keep QuicLoc reachable when the device is idle. Granting them is the difference between "QuicLoc answers a request at 3am while your phone is in your bag" and "QuicLoc answers if you happen to have the app open."

• Battery Optimization Exemption: Android puts apps to sleep to save battery. While QuicLoc is asleep, incoming triggers can be missed and the reply may not finish sending. Granting the exemption keeps QuicLoc reachable. QuicLoc does no proactive background work — battery cost is near zero.
• OEM Autostart: some manufacturers (Xiaomi, Huawei, Oppo, Vivo, etc.) ship a "Protected apps" or "Autostart" list that overrides Android's defaults. If QuicLoc isn't on it, the SmsReceiver stops firing after a reboot or when the device idles. The "Open" button tries to take you directly to the relevant screen; if it can't find one, it opens app info and you can navigate from there.
• Notification Channels: a per-channel "show notifications" toggle controlled by you. If the foreground-service channel is disabled, Android may kill the reply mid-fetch. The "Open" button takes you straight to QuicLoc's notification settings so you can verify every channel is on.

If a toggle in system Settings is greyed out

On Android 13 and newer, sideloaded apps (anything not installed via the Play Store) have certain "restricted" permissions — Notification Access, Device Admin, Full Screen Notifications, and some others — blocked until you explicitly unlock them. The system permission toggle will look greyed-out or just refuse to flip.

To unlock them:

• When QuicLoc opens its page in system Settings (whichever Grant or Manage button took you there), tap the ⋮ menu in the top-right corner.
• Choose "Allow restricted settings" — on some OEM builds this reads "Allow protected settings".
• Return to the toggle. It will now work.

You only need to do this once. After that all restricted toggles for QuicLoc behave normally.

Nothing in this list is sent to any server. No analytics, no crash reporters, no QuicLoc backend. Every permission corresponds to a feature you can see and revoke.
""".trim(),
    )

    private val notWorking = Tutorial(
        id = "not-working",
        title = "It isn't answering — what to check",
        summary = "The five reasons a request goes unanswered, in order.",
        body = """
Someone texted you "loc" and nothing came back. Work down this list — it's ordered by how often each one is the culprit.

First, open Diagnostics (Help & troubleshooting, at the bottom of the settings screen). It logs every message QuicLoc saw and the exact decision it made. If the message isn't in the log at all, it never reached the app — that's causes 1, 2 or 5. If it IS in the log, the log states the reason outright.

1. The sender isn't on your trusted list — or is listed differently than they appear.

The most common failure by a distance. Texts arrive as a phone number; chat apps arrive as the display name shown at the top of the notification. A contact added as "+1 555 0100" will not match a WhatsApp message that shows "Mum". Add both. "Pick from Contacts" does this in one tap.

2. The message wasn't exactly the trigger word.

It has to be "loc" or "quicloc" and nothing else. "loc?" doesn't count. "hey send me your loc" doesn't count. Case doesn't matter.

3. Location isn't set to "Allow all the time".

If location is set to "While using the app", QuicLoc can only answer while the app is on screen. Android splits this into two separate grants, and the second one is easy to miss. The setup checklist shows it as its own line.

4. Notification Access is off, and they used a chat app.

Plain texts work without it. WhatsApp, Telegram, Signal, Messenger and the rest do not — that path exists only through Notification Access.

Some apps can't work even with it: anything that hides message text in its notifications (Signal in private-notification mode, Telegram secret chats) never shows QuicLoc the message to begin with.

5. The phone put QuicLoc to sleep.

Battery optimisation, and on Xiaomi / Huawei / Oppo / Vivo devices a separate "autostart" or "protected apps" list, can stop QuicLoc being woken for an incoming message. Both are in the setup checklist under Recommended. QuicLoc does no background work of its own, so exempting it costs no battery.

Still stuck?

• Check the master switch is on — it's the first thing on the settings screen.
• Check Request History: a ✗ entry means QuicLoc tried and failed (usually a location timeout indoors), which is a completely different problem from never having been triggered.
• Turn on "Capture all notifications" in Diagnostics, have them send the message again, then turn it back off. Every notification QuicLoc saw is logged, so you can see exactly how their name and message text reached the app.
""".trim(),
    )

    /**
     * Everything in the catalogue, including tutorials for features that
     * aren't currently shipped. [visible] is what the hub renders.
     */
    val all: List<Tutorial> = listOf(
        why,
        gettingStarted,
        trustedContacts,
        trigger,
        widget,
        toggle,
        appLock,
        notWorking,
        findMyPhone,
        realLockdown,
        backup,
        permissions,
    )

    /**
     * The hub's list — [all] minus anything documenting the find-my-phone
     * feature while it's disabled. Explaining a feature the user can't find
     * anywhere in the app is worse than not mentioning it.
     */
    val visible: List<Tutorial>
        get() = all.filter { FindMyPhone.ENABLED || !it.requiresFindMyPhone }

    fun byId(id: String): Tutorial? = all.firstOrNull { it.id == id }
}
