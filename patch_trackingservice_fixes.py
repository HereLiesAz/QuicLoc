with open('app/src/main/java/com/hereliesaz/quicloc/TrackingService.kt', 'r') as f:
    content = f.read()

import re

# 1. In sendLocationUpdate, trigger sendMmsPhoto even if succeeded is false.
loc_update = """
    @SuppressLint("MissingPermission")
    private fun sendLocationUpdate() {
        val targetSender = sender ?: return
        Log.d(TAG, "Fetching location for tracking update...")
        LocationHelper.getCurrentLocationAndReply(
            context = this,
            phoneNumber = targetSender,
            onResult = { succeeded ->
                // Also send photo if in panic mode, regardless of location success
                if (isPanicMode && photoPathToSend != null) {
                    sendMmsPhoto(targetSender, photoPathToSend!!)
                }
            }
        )
    }
"""

content = re.sub(
    r'@SuppressLint\("MissingPermission"\)\n\s*private fun sendLocationUpdate\(\) \{.*?(?=private fun sendMmsPhoto)',
    loc_update.strip() + '\n\n    ',
    content,
    flags=re.DOTALL
)

# 2. In sendMmsPhoto, move to a background thread
mms_logic = """
    private fun sendMmsPhoto(targetSender: String, photoPath: String) {
        // Run on background thread to avoid ANR
        Thread {
            Log.d(TAG, "Sending MMS photo to $targetSender from $photoPath")
            try {
                val settings = com.klinker.android.send_message.Settings().apply {
                    useSystemSending = true
                }
                val transaction = com.klinker.android.send_message.Transaction(this, settings)
                val message = com.klinker.android.send_message.Message("QuicLoc Lock Image", targetSender)

                // Add image
                val file = java.io.File(photoPath)
                if (file.exists()) {
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)

                    // Calculate inSampleSize
                    var inSampleSize = 1
                    val reqWidth = 800
                    val reqHeight = 800
                    if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                        val halfHeight = options.outHeight / 2
                        val halfWidth = options.outWidth / 2
                        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                            inSampleSize *= 2
                        }
                    }

                    options.inJustDecodeBounds = false
                    options.inSampleSize = inSampleSize

                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    message.setImage(bitmap)
                    transaction.sendNewMessage(message, com.klinker.android.send_message.Transaction.NO_THREAD_ID)
                    Log.d(TAG, "MMS enqueued via library")
                } else {
                    Log.e(TAG, "Photo file not found: $photoPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send MMS", e)
            }
        }.start()
    }
"""

content = re.sub(
    r'private fun sendMmsPhoto\(targetSender: String, photoPath: String\) \{.*?(?=private fun stopTracking\(\))',
    mms_logic.strip() + '\n\n    ',
    content,
    flags=re.DOTALL
)

with open('app/src/main/java/com/hereliesaz/quicloc/TrackingService.kt', 'w') as f:
    f.write(content)
