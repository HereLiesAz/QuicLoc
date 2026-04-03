with open('app/src/main/java/com/hereliesaz/quicloc/MainActivity.kt', 'r') as f:
    content = f.read()

import re

# Update MainActivity to request CAMERA and SYSTEM_ALERT_WINDOW when saving passphrase
save_logic = """
                                onSavePassphrase = { newPassphrase, newPin ->
                                    whitelistManager.setPassphrase(newPassphrase)
                                    whitelistManager.setPin(newPin)
                                    currentPassphrase = newPassphrase
                                    currentPin = newPin

                                    // Request CAMERA permission if not granted
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        androidx.core.app.ActivityCompat.requestPermissions(this@MainActivity, arrayOf(android.Manifest.permission.CAMERA), 10)
                                    }
                                    // Request SYSTEM_ALERT_WINDOW if not granted
                                    if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                                        startActivity(intent)
                                    }
                                }
"""

content = re.sub(
    r'onSavePassphrase = \{ newPassphrase, newPin ->\s*whitelistManager\.setPassphrase\(newPassphrase\)\s*whitelistManager\.setPin\(newPin\)\s*currentPassphrase = newPassphrase\s*currentPin = newPin\s*\}',
    save_logic.strip(),
    content
)

with open('app/src/main/java/com/hereliesaz/quicloc/MainActivity.kt', 'w') as f:
    f.write(content)
