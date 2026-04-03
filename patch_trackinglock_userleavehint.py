with open('app/src/main/java/com/hereliesaz/quicloc/TrackingLockActivity.kt', 'r') as f:
    content = f.read()

import re

# Remove onPause and use onUserLeaveHint
on_pause_logic = """
    override fun onPause() {
        super.onPause()
        // If not finishing, bring it back to the front
        if (!isFinishing) {
            val intent = android.content.Intent(this, TrackingLockActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }
"""

replacement = """
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isFinishing) {
            val intent = android.content.Intent(this, TrackingLockActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }
"""

content = content.replace(on_pause_logic.strip(), replacement.strip())

with open('app/src/main/java/com/hereliesaz/quicloc/TrackingLockActivity.kt', 'w') as f:
    f.write(content)
