package com.hereliesaz.quicloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QuicLoc.SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

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

            if (!whitelistManager.isWhitelisted(sender)) {
                Log.d(TAG, "Sender $sender not whitelisted, ignoring.")
                continue
            }

            if (body == "loc" || body == "quicloc") {
                Log.d(TAG, "Trigger word received from $sender via SMS")
                val pendingResult = goAsync()
                LocationHelper.getCurrentLocationAndReply(context, sender, pendingResult)
            }
        }
    }
}
