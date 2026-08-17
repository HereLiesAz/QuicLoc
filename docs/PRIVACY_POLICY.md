# Privacy Policy

**Effective Date:** August 17, 2026

QuicLoc ("we," "our," or "us") operates the QuicLoc Android application. We are committed to protecting your privacy and ensuring your personal information remains secure.

### Data Collection and Use

**QuicLoc does not collect, transmit, or share any personal user data with the developer or any third party.** It does store some data locally on your device, and it shares your location with your own trusted contacts when they ask for it — see below.

*   **No Analytics:** We do not use any third-party analytics or crash reporting services.
*   **No Servers:** QuicLoc operates entirely on your local device. We do not have any servers that receive, process, or store your location or contact information.
*   **Local Processing:** Your whitelist of trusted contacts, PIN, and passphrase are stored exclusively on your device, encrypted with AES-256-GCM backed by the Android Keystore. None of it is ever transmitted to us.
*   **Location is shared with your trusted contacts, not collected by us.** When a contact on your whitelist texts the trigger word, QuicLoc reads your device's current GPS location and sends it directly to that contact via SMS or an in-app-notification reply — the same as if you had texted it yourself. The location is held in memory only long enough to send that one reply and is never written to disk, logged, or sent anywhere else.
*   **SMS and notification content is read only to look for the trigger word.** QuicLoc's SMS receiver and notification listener read the text of incoming messages solely to check whether they contain the trigger word and come from a whitelisted contact. Non-matching messages, and all messages from senders not on your whitelist, are discarded immediately and never stored.
*   **Background location for request-reply** is used so QuicLoc can answer a request even when the app isn't open on screen — it's requested only so the trigger-and-reply function keeps working with the screen off. For this feature, location is read only at the moment of replying to a request, never on a schedule.
*   **Background location for Loc Notice (opt-in, off by default).** Loc Notice is a separate feature you turn on yourself, independent of the toggle above: you name a place (an address you type, and coordinates you confirm in the Google Maps app — QuicLoc never sends your address to a maps or geocoding service of its own), pick contacts, and choose whether to be notified on arrival and/or departure. While Loc Notice is on, Android's Geofencing API (part of Google Play services, running on your device) continuously watches your location in the background to detect when you cross a saved place's boundary — this is genuinely ongoing background location use, not "only when answering a request," and is the trade-off of the feature working at all. QuicLoc itself still never sends this location anywhere except the automatic text to your chosen contacts on a crossing; see "Third-Party Services" below for what Play services' geofencing does with location on Google's side. Turning Loc Notice off, or pausing/deleting a specific place, stops that place from being monitored.
*   **Permissions:** QuicLoc requires certain permissions to function (SMS, Location — including background location, Notification Access). These are used strictly to provide the functionality described above.

### Third-Party Services

QuicLoc does not use any third-party analytics, advertising, or crash-reporting SDKs, and has no backend of its own. If you turn on Loc Notice, it does rely on **Google Play services' Geofencing API** to detect when you enter or leave a saved place — this is part of the Android platform on your device, not something QuicLoc adds, but it means Play services itself (not QuicLoc) evaluates your location against your saved place boundaries. That processing is governed by Google Play services' own privacy terms, not this policy. QuicLoc never sends your address, coordinates, or contact list to any geocoding, maps, or analytics service — Loc Notice's address lookup happens entirely inside the Google Maps app you already have installed, via a clipboard handoff, not an API call QuicLoc makes.

### Your Choices

QuicLoc stores your whitelist, PIN, passphrase, and (if you use Loc Notice) your saved places locally on your device. You can delete any of it at any time — remove individual contacts, delete a Loc Notice place, or clear the PIN/passphrase from within the app, or uninstall the app to remove everything at once. None of this data ever leaves your device to us, so there is nothing for you to request from us or for us to delete on our end.

### Contact Us

If you have any questions or concerns about this privacy policy, please reach out via the contact information provided on the [QuicLoc GitHub repository](https://github.com/HereLiesAz/QuicLoc).

### Changes to This Privacy Policy

We may update this Privacy Policy from time to time. We will notify you of any changes by updating the "Effective Date" at the top of this policy.
