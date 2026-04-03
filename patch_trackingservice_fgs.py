with open('app/src/main/java/com/hereliesaz/quicloc/TrackingService.kt', 'r') as f:
    content = f.read()

import re

# Fix intent == null
content = re.sub(
    r'if \(intent == null\) return START_STICKY',
    'if (intent == null) return START_NOT_STICKY',
    content
)

# Fix OOM crash by downsampling
mms_logic = """
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
"""

content = re.sub(
    r'val file = java\.io\.File\(photoPath\)\n\s*if \(file\.exists\(\)\) \{\n\s*val bitmap = android\.graphics\.BitmapFactory\.decodeFile\(file\.absolutePath\)\n\s*message\.setImage\(bitmap\)\n\s*transaction\.sendNewMessage\(message, com\.klinker\.android\.send_message\.Transaction\.NO_THREAD_ID\)\n\s*Log\.d\(TAG, "MMS enqueued via library"\)\n\s*\} else \{\n\s*Log\.e\(TAG, "Photo file not found: \$photoPath"\)\n\s*\}',
    mms_logic.strip(),
    content
)

with open('app/src/main/java/com/hereliesaz/quicloc/TrackingService.kt', 'w') as f:
    f.write(content)
