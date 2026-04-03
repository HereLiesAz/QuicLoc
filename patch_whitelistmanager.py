with open('app/src/main/java/com/hereliesaz/quicloc/WhitelistManager.kt', 'r') as f:
    content = f.read()

# Add getPassphrase, setPassphrase, getPin, setPin
additions = """
    private const val KEY_PASSPHRASE = "passphrase"
    private const val KEY_PIN = "pin"

    fun getPassphrase(): String? {
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
    }
"""

if "KEY_PASSPHRASE" not in content:
    content = content.replace(
        'private const val KEY_WHITELIST = "whitelist"',
        'private const val KEY_WHITELIST = "whitelist"\n' + additions.split('\n', 1)[1].split('\n\n    fun getPassphrase()')[0]
    )
    content = content.replace(
        'fun getNumbers(): Set<String> {',
        additions.split('\n\n    fun getPassphrase()')[1].replace('    fun', '    fun getPassphrase()') + '\n    fun getNumbers(): Set<String> {'
    )

with open('app/src/main/java/com/hereliesaz/quicloc/WhitelistManager.kt', 'w') as f:
    f.write(content)
