with open('app/src/main/java/com/hereliesaz/quicloc/WhitelistManager.kt', 'r') as f:
    content = f.read()

import re
content = re.sub(r'    : String\? \{\n        return prefs.getString\(KEY_PASSPHRASE, null\)\n    \}\n\n    fun getPassphrase\(\) setPassphrase\(passphrase: String\?\) \{\n        prefs.edit\(\).putString\(KEY_PASSPHRASE, passphrase\).apply\(\)\n    \}\n\n    fun getPassphrase\(\) getPin\(\): String\? \{\n        return prefs.getString\(KEY_PIN, null\)\n    \}\n\n    fun getPassphrase\(\) setPin\(pin: String\?\) \{\n        prefs.edit\(\).putString\(KEY_PIN, pin\).apply\(\)\n    \}',
'''    fun getPassphrase(): String? {
        return prefs.getString(KEY_PASSPHRASE, null)
    }

    fun setPassphrase(passphrase: String?) {
        prefs.edit().putString(KEY_PASSPHRASE, passphrase).apply()
    }

    fun getPin(): String? {
        return prefs.getString(KEY_PIN, null)
    }

    fun setPin(pin: String?) {
        prefs.edit().putString(KEY_PIN, pin).apply()
    }''', content)

with open('app/src/main/java/com/hereliesaz/quicloc/WhitelistManager.kt', 'w') as f:
    f.write(content)
