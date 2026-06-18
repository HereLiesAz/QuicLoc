package com.hereliesaz.quicloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * BroadcastReceiver for `SMS_RECEIVED`. The SMS trigger entry point.
 *
 * Responsibilities:
 *
 *   1. Master-toggle gate ([AppSettings.isEnabled]) — short-circuit if off.
 *   2. Reassemble multipart SMS by sender (long messages arrive as multiple
 *      `SmsMessage` parts).
 *   3. Match against either:
 *        - the single-use passphrase → hand off to [TrackingService]
 *        - the trigger word `loc` / `quicloc` + whitelist match
 *          → hand off to [LocationReplyService]
 *   4. For passphrase matches, clear the passphrase synchronously
 *      ([WhitelistManager.clearPassphraseSync]) so a crash between trigger
 *      and reboot can't leave it reusable.
 *
 * No `goAsync()` — we don't need it. Everything heavy (GPS, SMS send) is
 * delegated to a foreground service. The receiver itself returns in
 * milliseconds.
 *
 * Calling `startForegroundService` from a `BroadcastReceiver` is exempt
 * from the Android 12+ FGS background-start restrictions.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QuicLoc.SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val captureAll = AppSettings.isDiagCaptureAll(context)
        // Lazy so the EncryptedSharedPreferences / Keystore init only happens
        // when we actually record a diagnostic — not for every passing SMS.
        val diag by lazy { DiagnosticLogManager(context) }

        if (!AppSettings.isEnabled(context)) {
            Log.d(TAG, "Ignoring incoming SMS — QuicLoc disabled")
            if (captureAll) diag.record(buildEvent("", "", DiagOutcome.APP_DISABLED,
                "QuicLoc master toggle is off"))
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            if (captureAll) diag.record(buildEvent("", "", DiagOutcome.NO_MESSAGES_EXTRACTED,
                "No SMS messages in intent"))
            return
        }

        // Reassemble multi-part SMS by sender
        val messagesBySender = mutableMapOf<String, StringBuilder>()
        for (smsMessage in messages) {
            val sender = smsMessage.displayOriginatingAddress ?: continue
            messagesBySender.getOrPut(sender) { StringBuilder() }
                .append(smsMessage.messageBody)
        }

        val whitelistManager = WhitelistManager(context)

        for ((sender, bodyBuilder) in messagesBySender) {
            val body = bodyBuilder.toString().trim().lowercase()
            Log.d(TAG, "SMS from $sender: '$body'")
            val looksLikeTrigger = body.contains("loc")

            val passphrase = whitelistManager.getPassphrase()
            val isPassphraseTrigger = passphrase != null && passphrase.isNotEmpty() &&
                (body == "loc ${passphrase.lowercase()}" || body == "quicloc ${passphrase.lowercase()}")

            if (isPassphraseTrigger) {
                Log.d(TAG, "Passphrase trigger from $sender — starting TrackingService")
                diag.record(buildEvent(sender, body, DiagOutcome.PASSPHRASE_TRIGGER,
                    "Find-my-phone passphrase matched — starting tracking", triggerMatched = true))
                // Commit synchronously so a crash between now and the next boot
                // can't leave the single-use passphrase usable a second time.
                whitelistManager.clearPassphraseSync()
                TrackingService.startForSms(context, sender)
                continue
            }

            if (!whitelistManager.isWhitelisted(sender)) {
                Log.d(TAG, "Sender $sender not whitelisted, ignoring.")
                if (looksLikeTrigger) diag.record(buildEvent(sender, body,
                    DiagOutcome.WHITELIST_NO_MATCH_BY_NUMBER,
                    "Trigger '$body' from $sender, but number not in whitelist. " +
                        "Whitelist: [${whitelistManager.getNumbers().joinToString(", ")}]",
                    triggerMatched = true, whitelistMatched = false))
                continue
            }

            if (body == "loc" || body == "quicloc") {
                // The same carrier SMS also reaches NotificationListener via the
                // default SMS app's notification; dedupe so we only reply once.
                if (TriggerDedupe.wasRecentlyHandled(listOf(sender))) {
                    Log.d(TAG, "Duplicate trigger from $sender — already handled, skipping")
                    diag.record(buildEvent(sender, body, DiagOutcome.DUPLICATE_SUPPRESSED,
                        "Already handled this request via the notification path — not replying twice",
                        triggerMatched = true, whitelistMatched = true))
                    continue
                }
                TriggerDedupe.markHandled(sender)
                Log.d(TAG, "Trigger from $sender — starting LocationReplyService")
                val diagId = java.util.UUID.randomUUID().toString()
                diag.record(buildEvent(sender, body, DiagOutcome.DISPATCHED,
                    "Trigger matched, sender whitelisted — fetching location to reply",
                    id = diagId, triggerMatched = true, whitelistMatched = true))
                // Hand off to the foreground service. The receiver returns in
                // milliseconds; the service does the GPS wait + reply.
                LocationReplyService.startForSms(context, sender, diagId = diagId)
            } else if (captureAll) {
                diag.record(buildEvent(sender, body, DiagOutcome.NOT_A_TRIGGER,
                    "Whitelisted sender, but message isn't a trigger word"))
            }
        }
    }

    /** Builds an SMS-channel diagnostic event with the common fields filled. */
    private fun buildEvent(
        sender: String,
        body: String,
        outcome: DiagOutcome,
        reason: String,
        id: String = java.util.UUID.randomUUID().toString(),
        triggerMatched: Boolean = false,
        whitelistMatched: Boolean? = null,
    ): DiagnosticEvent = DiagnosticEvent(
        id = id,
        timestamp = System.currentTimeMillis(),
        channel = DiagChannel.SMS,
        source = "SMS",
        rawSender = sender,
        rawBody = body.take(80),
        triggerMatched = triggerMatched,
        whitelistMatched = whitelistMatched,
        extractionPath = null,
        outcome = outcome,
        reason = reason,
    )
}
