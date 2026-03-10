package com.hereliesaz.quicloc

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.location.Location
import android.telephony.SmsManager
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

object LocationHelper {
    private const val TAG = "QuicLoc"

    @SuppressLint("MissingPermission")
    fun getCurrentLocationAndReply(context: Context, phoneNumber: String, pendingResult: BroadcastReceiver.PendingResult) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    sendLocationSms(phoneNumber, location)
                } else {
                    sendErrorSms(phoneNumber, "Unable to determine current location.")
                }
            }.addOnFailureListener { exception ->
                Log.e(TAG, "Failed to get location", exception)
                sendErrorSms(phoneNumber, "Error retrieving location.")
            }.addOnCompleteListener {
                pendingResult.finish()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permissions", e)
            sendErrorSms(phoneNumber, "Missing location permissions.")
            pendingResult.finish()
        }
    }

    private fun sendLocationSms(phoneNumber: String, location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val mapsLink = "https://maps.google.com/?q=$lat,$lng"
        val message = "QuicLoc Location:\n$mapsLink"

        sendSms(phoneNumber, message)
    }

    private fun sendErrorSms(phoneNumber: String, errorMessage: String) {
        val message = "QuicLoc Error: $errorMessage"
        sendSms(phoneNumber, message)
    }

    private fun sendSms(phoneNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
        }
    }
}
