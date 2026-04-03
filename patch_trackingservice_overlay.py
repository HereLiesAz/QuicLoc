with open('app/src/main/java/com/hereliesaz/quicloc/TrackingService.kt', 'r') as f:
    content = f.read()

import re

# Instead of just FullScreenIntent, let's start the activity immediately
start_logic = """
            Log.d(TAG, "Starting tracking for $sender via $source")

            // Show lock screen immediately via intent
            val lockIntent = Intent(this, TrackingLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(lockIntent)

            val pendingIntent = PendingIntent.getActivity(
                this, 0, lockIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(this, CHANNEL_ID)
"""

content = re.sub(
    r'Log\.d\(TAG, "Starting tracking for \$sender via \$source"\)\s*// Show lock screen immediately via Full Screen Intent\s*val lockIntent = Intent\(this, TrackingLockActivity::class\.java\)\.apply \{\s*addFlags\(Intent\.FLAG_ACTIVITY_NEW_TASK or Intent\.FLAG_ACTIVITY_CLEAR_TASK\)\s*\}\s*val pendingIntent = PendingIntent\.getActivity\(\s*this, 0, lockIntent, PendingIntent\.FLAG_UPDATE_CURRENT or PendingIntent\.FLAG_IMMUTABLE\s*\)\s*val notification = Notification\.Builder\(this, CHANNEL_ID\)',
    start_logic.strip(),
    content
)

with open('app/src/main/java/com/hereliesaz/quicloc/TrackingService.kt', 'w') as f:
    f.write(content)
