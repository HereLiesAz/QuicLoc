with open('app/src/main/java/com/hereliesaz/quicloc/NotificationListener.kt', 'r') as f:
    content = f.read()

import re

new_logic = """
        val whitelistManager = WhitelistManager(applicationContext)
        val passphrase = whitelistManager.getPassphrase()
        val isPassphraseTrigger = passphrase != null && passphrase.isNotEmpty() &&
            (body == "loc ${passphrase.lowercase()}" || body == "quicloc ${passphrase.lowercase()}")

        if (isPassphraseTrigger) {
            Log.d(TAG, "Passphrase trigger from '$title' via $packageName — starting TrackingService")
            whitelistManager.setPassphrase(null) // single-use

            // Start tracking service
            TrackingService.startForNotification(
                applicationContext,
                sender = title,
                source = appName
            )
            return
        }

        if (body != "loc" && body != "quicloc") return

        // Check whitelist — use title as sender identifier (display name or number)
        if (!whitelistManager.isWhitelistedByName(title)) {
            Log.d(TAG, "Sender '$title' not whitelisted, ignoring.")
            return
        }
"""

content = re.sub(
    r'if \(body != "loc" && body != "quicloc"\) return\n\n\s*// Check whitelist — use title as sender identifier \(display name or number\)\n\s*val whitelistManager = WhitelistManager\(applicationContext\)\n\s*if \(!whitelistManager\.isWhitelistedByName\(title\)\) \{\n\s*Log\.d\(TAG, "Sender \'\$title\' not whitelisted, ignoring\."\)\n\s*return\n\s*\}',
    new_logic.strip(),
    content
)

with open('app/src/main/java/com/hereliesaz/quicloc/NotificationListener.kt', 'w') as f:
    f.write(content)
