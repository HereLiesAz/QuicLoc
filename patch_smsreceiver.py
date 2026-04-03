with open('app/src/main/java/com/hereliesaz/quicloc/SmsReceiver.kt', 'r') as f:
    content = f.read()

import re

new_logic = """
            val passphrase = whitelistManager.getPassphrase()
            val isPassphraseTrigger = passphrase != null && passphrase.isNotEmpty() &&
                (body == "loc ${passphrase.lowercase()}" || body == "quicloc ${passphrase.lowercase()}")

            if (isPassphraseTrigger) {
                Log.d(TAG, "Passphrase trigger from $sender — starting TrackingService")
                // Invalidate passphrase (single-use)
                whitelistManager.setPassphrase(null)

                // Start tracking service
                TrackingService.startForSms(context, sender)
                continue
            }

            if (!whitelistManager.isWhitelisted(sender)) {
                Log.d(TAG, "Sender $sender not whitelisted, ignoring.")
                continue
            }

            if (body == "loc" || body == "quicloc") {
                Log.d(TAG, "Trigger from $sender — starting LocationReplyService")
                // Hand off to the foreground service immediately.
                // The receiver returns in milliseconds; the service does the
                // actual GPS wait and reply with no time limit.
                LocationReplyService.startForSms(context, sender)
            }
"""

content = re.sub(
    r'if \(!whitelistManager\.isWhitelisted\(sender\)\) \{\n\s*Log\.d\(TAG, "Sender \$sender not whitelisted, ignoring\."\)\n\s*continue\n\s*\}\n\n\s*if \(body == "loc" \|\| body == "quicloc"\) \{\n\s*Log\.d\(TAG, "Trigger from \$sender — starting LocationReplyService"\)\n\s*// Hand off to the foreground service immediately\.\n\s*// The receiver returns in milliseconds; the service does the\n\s*// actual GPS wait and reply with no time limit\.\n\s*LocationReplyService\.startForSms\(context, sender\)\n\s*\}',
    new_logic.strip(),
    content
)

with open('app/src/main/java/com/hereliesaz/quicloc/SmsReceiver.kt', 'w') as f:
    f.write(content)
