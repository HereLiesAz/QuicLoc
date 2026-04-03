with open('app/src/main/java/com/hereliesaz/quicloc/MainActivity.kt', 'r') as f:
    content = f.read()

import re

# We need to pass passphrase and pin from MainActivity to QuicLocScreen, and handle their saving
# Find where QuicLocScreen is called:
# `QuicLocScreen(...)`
# We also need state variables for them.

# Let's just use regex to replace things
# Search for the class MainActivity
content = re.sub(
    r'var numbersList by remember \{ mutableStateOf\(whitelistManager\.getNumbers\(\)\.toList\(\)\) \}',
    '''var numbersList by remember { mutableStateOf(whitelistManager.getNumbers().toList()) }
                            var currentPassphrase by remember { mutableStateOf(whitelistManager.getPassphrase() ?: "") }
                            var currentPin by remember { mutableStateOf(whitelistManager.getPin() ?: "") }''',
    content
)

content = re.sub(
    r'QuicLocScreen\(\s*modifier = Modifier.padding\(innerPadding\),\s*numbersList = numbersList,\s*notificationAccessGranted = notificationAccessGranted,\s*noLockScreenWarning = noLockScreenWarning,\s*onRequestNotificationAccess = \{\s*startActivity\(Intent\(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS\)\)\s*},\s*onAddNumber = \{ newNumber ->\s*whitelistManager.addNumber\(newNumber\)\s*numbersList = whitelistManager.getNumbers\(\).toList\(\)\s*},\s*onRemoveNumber = \{ numberToRemove ->\s*whitelistManager.removeNumber\(numberToRemove\)\s*numbersList = whitelistManager.getNumbers\(\).toList\(\)\s*},\s*onPickContact = \{ contactPickerLauncher.launch\(null\) \}\s*\)',
    '''QuicLocScreen(
                                modifier = Modifier.padding(innerPadding),
                                numbersList = numbersList,
                                notificationAccessGranted = notificationAccessGranted,
                                noLockScreenWarning = noLockScreenWarning,
                                onRequestNotificationAccess = {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                onAddNumber = { newNumber ->
                                    whitelistManager.addNumber(newNumber)
                                    numbersList = whitelistManager.getNumbers().toList()
                                },
                                onRemoveNumber = { numberToRemove ->
                                    whitelistManager.removeNumber(numberToRemove)
                                    numbersList = whitelistManager.getNumbers().toList()
                                },
                                onPickContact = { contactPickerLauncher.launch(null) },
                                currentPassphrase = currentPassphrase,
                                currentPin = currentPin,
                                onSavePassphrase = { newPassphrase, newPin ->
                                    whitelistManager.setPassphrase(newPassphrase)
                                    whitelistManager.setPin(newPin)
                                    currentPassphrase = newPassphrase
                                    currentPin = newPin
                                }
                            )''',
    content
)

# Now update QuicLocScreen parameters
content = re.sub(
    r'fun QuicLocScreen\(\s*modifier: Modifier = Modifier,\s*numbersList: List<String>,\s*notificationAccessGranted: Boolean,\s*noLockScreenWarning: Boolean,\s*onRequestNotificationAccess: \(\) -> Unit,\s*onAddNumber: \(String\) -> Unit,\s*onRemoveNumber: \(String\) -> Unit,\s*onPickContact: \(\) -> Unit\s*\)',
    '''fun QuicLocScreen(
    modifier: Modifier = Modifier,
    numbersList: List<String>,
    notificationAccessGranted: Boolean,
    noLockScreenWarning: Boolean,
    onRequestNotificationAccess: () -> Unit,
    onAddNumber: (String) -> Unit,
    onRemoveNumber: (String) -> Unit,
    onPickContact: () -> Unit,
    currentPassphrase: String = "",
    currentPin: String = "",
    onSavePassphrase: (String, String) -> Unit = { _, _ -> }
)''',
    content
)

# And add the UI fields in QuicLocScreen
ui_addition = '''
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Single-Use Tracking Passphrase",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        var passphraseInput by remember { mutableStateOf(currentPassphrase) }
        var pinInput by remember { mutableStateOf(currentPin) }

        OutlinedTextField(
            value = passphraseInput,
            onValueChange = { passphraseInput = it },
            label = { Text("Passphrase (10-150 chars)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = pinInput,
            onValueChange = { pinInput = it },
            label = { Text("6-Digit PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                if (passphraseInput.length in 10..150 && pinInput.length == 6 && pinInput.all { it.isDigit() }) {
                    onSavePassphrase(passphraseInput, pinInput)
                }
            },
            enabled = passphraseInput.length in 10..150 && pinInput.length == 6 && pinInput.all { it.isDigit() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Passphrase & PIN")
        }

        Spacer(modifier = Modifier.height(16.dp))
'''

content = content.replace(
    'Text(\n            text = "Whitelist a contact:",',
    ui_addition + '\n        Text(\n            text = "Whitelist a contact:",'
)

with open('app/src/main/java/com/hereliesaz/quicloc/MainActivity.kt', 'w') as f:
    f.write(content)
