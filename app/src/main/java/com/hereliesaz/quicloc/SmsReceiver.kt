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

        if (!AppSettings.isEnabled(context)) {
            Log.d(TAG, "Ignoring incoming SMS — QuicLoc disabled")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

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

            val passphrase = whitelistManager.getPassphrase()
            val isPassphraseTrigger = passphrase != null && passphrase.isNotEmpty() &&
                (body == "loc ${passphrase.lowercase()}" || body == "quicloc ${passphrase.lowercase()}")

            if (isPassphraseTrigger) {
                Log.d(TAG, "Passphrase trigger from $sender — starting TrackingService")
                // Commit synchronously so a crash between now and the next boot
                // can't leave the single-use passphrase usable a second time.
                whitelistManager.clearPassphraseSync()
                TrackingService.startForSms(context, sender)
                continue
            }

            if (!whitelistManager.isWhitelisted(sender)) {
                Log.d(TAG, "Sender $sender not whitelisted, ignoring.")
                continue
            }

            if (body == "loc" || body == "quicloc") {
                Log.d(TAG, "Trigger from $sender — starting LocationReplyService")
                // Hand off to the foreground service. The receiver returns in
                // milliseconds; the service does the GPS wait + reply.
                LocationReplyService.startForSms(context, sender)
            }
        }
    }
}
