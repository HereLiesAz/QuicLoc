package com.hereliesaz.quicloc

import android.os.Build
import android.telephony.SmsManager
import android.util.Log

/**
 * The one place QuicLoc calls [SmsManager]. Extracted from [LocationHelper]
 * so [GeofenceBroadcastReceiver]'s automatic arrival/departure texts reuse
 * the exact same send path (multipart handling, API-level lookup,
 * synchronous-failure handling) instead of reimplementing it.
 */
object SmsSender {
    private const val TAG = "QuicLoc.SmsSender"

    /**
     * @return Whether the send call was actually issued to the radio without
     *   throwing. `sentIntent` is intentionally null (no PendingIntent-based
     *   delivery-report wiring exists), so this does NOT confirm carrier-level
     *   delivery — only that we didn't fail synchronously (SmsManager missing,
     *   a malformed destination address, SEND_SMS revoked, etc).
     */
    fun send(context: android.content.Context, phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            if (smsManager != null) {
                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                }
                true
            } else {
                Log.e(TAG, "Failed to get SmsManager")
                false
            }
        } catch (e: Exception) {
            // Deliberately not logging the destination number -- logcat is
            // readable by any app holding READ_LOGS on older/rooted devices,
            // and this is exactly the "who's on the whitelist" metadata the
            // rest of the app goes out of its way to keep encrypted at rest.
            Log.e(TAG, "Failed to send SMS", e)
            false
        }
    }
}
