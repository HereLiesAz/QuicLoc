package com.hereliesaz.quicloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (smsMessage in messages) {
                val messageBody = smsMessage.messageBody
                val senderNum = smsMessage.displayOriginatingAddress

                if (senderNum != null) {
                    val whitelistManager = WhitelistManager(context)

                    if (whitelistManager.isWhitelisted(senderNum)) {
                        val bodyLower = messageBody.trim().lowercase()
                        if (bodyLower == "loc" || bodyLower == "quicloc") {
                            // Valid trigger word from a whitelisted number!
                            val pendingResult = goAsync()
                            LocationHelper.getCurrentLocationAndReply(context, senderNum, pendingResult)
                        }
                    }
                }
            }
        }
    }
}
