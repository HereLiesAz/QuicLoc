package com.hereliesaz.quicloc

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {   // FragmentActivity required by BiometricPrompt

    private lateinit var whitelistManager: WhitelistManager

    // Tracks whether the user has passed biometric auth this session.
    // Set to false whenever the app is backgrounded, so re-auth is required on return.
    private var isAuthenticated = false

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA
    ).let {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            it + "android.permission.POST_NOTIFICATIONS"
        } else it
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Background location permission is required for QuicLoc to work when the app is closed.",
                Toast.LENGTH_LONG
            ).show()
        }
        checkNotificationListenerPermission()
    }

    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasSms = permissions[Manifest.permission.RECEIVE_SMS] == true &&
                permissions[Manifest.permission.SEND_SMS] == true
        val hasLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (hasSms && hasLocation) {
            checkBackgroundLocationPermission()
        } else {
            Toast.makeText(
                this,
                "SMS and Location permissions are required for QuicLoc to function.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Compose state — hoisted so biometric callback and launchers can update it
    private var authState = mutableStateOf(false)
    private var numbersState = mutableStateOf<List<String>>(emptyList())
    private var starredState = mutableStateOf<Set<String>>(emptySet())
    private var myNumberState = mutableStateOf("")

    private val readContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether granted or not, open the picker — we'll get name regardless,
        // and phone numbers only if READ_CONTACTS was granted.
        contactPickerLauncher.launch(null)
    }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) handleContactPicked(uri)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        whitelistManager = WhitelistManager(this)
        numbersState.value = whitelistManager.getNumbers().toList()
        starredState.value = whitelistManager.getStarredNumbers()
        myNumberState.value = whitelistManager.getMyNumber()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val authenticated by authState

                if (!authenticated) {
                    BiometricGateScreen(
                        onRetry = { promptBiometric() }
                    )
                } else {

                    val numbersList by numbersState
                    val starredSet by starredState
                    val myNumber by myNumberState
                    var currentPassphrase by remember { mutableStateOf(whitelistManager.getPassphrase() ?: "") }
                    var currentPin by remember { mutableStateOf(whitelistManager.getPin() ?: "") }

                    var notificationAccessGranted by remember {
                        mutableStateOf(isNotificationListenerEnabled())
                    }
                    var showHistory by remember { mutableStateOf(false) }
                    var showOnboarding by remember { mutableStateOf(!whitelistManager.isOnboardingCompleted()) }

                    if (showOnboarding) {
                        OnboardingDialog(onDismiss = {
                            whitelistManager.setOnboardingCompleted(true)
                            showOnboarding = false
                        })
                    }

                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(if (showHistory) "Request History" else "QuicLoc") },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                actions = {
                                    IconButton(onClick = { showOnboarding = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Show help",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    IconButton(onClick = { showHistory = !showHistory }) {
                                        Icon(
                                            imageVector = Icons.Default.List,
                                            contentDescription = if (showHistory) "Back to config" else "View history",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        if (showHistory) {
                            HistoryScreen(
                                modifier = Modifier.padding(innerPadding),
                                historyManager = RequestHistoryManager(this@MainActivity)
                            )
                        } else {
                            QuicLocScreen(
                                modifier = Modifier.padding(innerPadding),
                                numbersList = numbersList,
                                starredSet = starredSet,
                                myNumber = myNumber,
                                notificationAccessGranted = notificationAccessGranted,
                                noLockScreenWarning = !BiometricHelper.canAuthenticate(this@MainActivity),
                                onRequestNotificationAccess = {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                onAddNumber = { number ->
                                    if (number.isNotBlank()) {
                                        whitelistManager.addNumber(number)
                                        numbersState.value = whitelistManager.getNumbers().toList()
                                        Toast.makeText(this, "Added to whitelist", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRemoveNumber = { number ->
                                    whitelistManager.removeNumber(number)
                                    numbersState.value = whitelistManager.getNumbers().toList()
                                    starredState.value = whitelistManager.getStarredNumbers()
                                },
                                onPickContact = { launchContactPicker() },
                                onToggleStar = { number ->
                                    val success = whitelistManager.toggleStarred(number)
                                    if (!success) {
                                        Toast.makeText(this@MainActivity, "You can only star up to 3 contacts.", Toast.LENGTH_SHORT).show()
                                    }
                                    starredState.value = whitelistManager.getStarredNumbers()
                                },
                                                                onMyNumberChanged = { number ->
                                    whitelistManager.setMyNumber(number)
                                    myNumberState.value = whitelistManager.getMyNumber()
                                },
                                currentPassphrase = currentPassphrase,
                                currentPin = currentPin,
                                onSavePassphrase = { newPassphrase, newPin ->
                                    whitelistManager.setPassphrase(newPassphrase)
                                    whitelistManager.setPin(newPin)
                                    currentPassphrase = newPassphrase
                                    currentPin = newPin

                                    val perms = mutableListOf<String>()
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        perms.add(android.Manifest.permission.CAMERA)
                                    }
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        perms.add(android.Manifest.permission.RECEIVE_SMS)
                                    }
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        perms.add(android.Manifest.permission.SEND_SMS)
                                    }
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        perms.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                                    }
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        perms.add(android.Manifest.permission.READ_CONTACTS)
                                    }

                                    if (perms.isNotEmpty()) {
                                        androidx.core.app.ActivityCompat.requestPermissions(this@MainActivity, perms.toTypedArray(), 10)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-auth every time the app comes to the foreground
        if (!isAuthenticated) {
            promptBiometric()
        }
    }

    override fun onPause() {
        super.onPause()
        // Lock the app when it goes to the background
        isAuthenticated = false
        authState.value = false
    }

    // -------------------------------------------------------------------------
    // Biometric prompt
    // -------------------------------------------------------------------------

    private fun promptBiometric() {
        BiometricHelper.authenticate(
            activity = this,
            onSuccess = {
                isAuthenticated = true
                authState.value = true
                checkPermissions()
            },
            onFailure = { reason ->
                isAuthenticated = false
                authState.value = false
                // User cancelled or failed — show the lock screen UI with retry
                Toast.makeText(this, "Authentication required: $reason", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // -------------------------------------------------------------------------
    // Contact picker
    // -------------------------------------------------------------------------

    private fun launchContactPicker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            readContactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            contactPickerLauncher.launch(null)
        }
    }

    private fun handleContactPicked(uri: Uri) {
        val contactProjection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        contentResolver.query(uri, contactProjection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
            val name = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            ) ?: return

            // Always store the display name — this is what notification-based apps send
            whitelistManager.addNumber(name)

            // Also store phone numbers for SMS matching
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id),
                    null
                )?.use { phoneCursor ->
                    while (phoneCursor.moveToNext()) {
                        val number = phoneCursor.getString(
                            phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        )
                        if (!number.isNullOrBlank()) whitelistManager.addNumber(number)
                    }
                }
            }

            numbersState.value = whitelistManager.getNumbers().toList()
            Toast.makeText(this, "Added $name to whitelist", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun checkPermissions() {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasNotif = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasSms || !hasLocation || !hasCamera || !hasNotif) {
            multiplePermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        } else {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }
        checkNotificationListenerPermission()
    }

    private fun checkNotificationListenerPermission() {
        if (!isNotificationListenerEnabled()) {
            Toast.makeText(
                this,
                "Grant Notification Access so QuicLoc can respond in WhatsApp, Telegram, and other apps.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && packageName == cn.packageName) return true
            }
        }
        return false
    }
}

// -------------------------------------------------------------------------
// Lock screen shown before auth passes
// -------------------------------------------------------------------------

@Composable
fun BiometricGateScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "🔒",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "QuicLoc",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Authentication required",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Unlock")
            }
        }
    }
}

// -------------------------------------------------------------------------
// Main UI
// -------------------------------------------------------------------------

@Composable
fun QuicLocScreen(
    modifier: Modifier = Modifier,
    numbersList: List<String>,
    starredSet: Set<String>,
    myNumber: String,
    notificationAccessGranted: Boolean,
    noLockScreenWarning: Boolean,
    onRequestNotificationAccess: () -> Unit,
    onAddNumber: (String) -> Unit,
    onRemoveNumber: (String) -> Unit,
    onPickContact: () -> Unit,
    currentPassphrase: String = "",
    currentPin: String = "",
    onSavePassphrase: (String, String) -> Unit = { _, _ -> },
    onToggleStar: (String) -> Unit = {},
    onMyNumberChanged: (String) -> Unit = {}
) {
    var phoneNumberInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "QuicLoc Configuration",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Warn if device has no lock screen (biometrics were bypassed)
        if (noLockScreenWarning) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "⚠ No lock screen set up. Set a PIN, pattern, or fingerprint in system settings to protect this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Notification access banner
        if (!notificationAccessGranted) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "⚠ Notification Access Required",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "To respond in WhatsApp, Telegram, Signal, and other apps, QuicLoc needs Notification Access permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Button(
                        onClick = onRequestNotificationAccess,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Grant Notification Access")
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = "✓ Notification Access granted — QuicLoc will respond in all messaging apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }


        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Single-Use Tracking Passphrase",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        var passphraseInput by remember(currentPassphrase) { mutableStateOf(currentPassphrase) }
        var pinInput by remember(currentPin) { mutableStateOf(currentPin) }

        OutlinedTextField(
            value = passphraseInput,
            onValueChange = { if (it.length <= 150) passphraseInput = it },
            label = { Text("Text 'loc' + this from ANY number to activate find my phone.") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            singleLine = false,
            minLines = 3
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = pinInput,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
            label = { Text("6-Digit PIN: Required to unlock your phone once find my phone is active.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onSavePassphrase(passphraseInput, pinInput) },
            enabled = passphraseInput.length in 10..150 && pinInput.length == 6 && pinInput.all { it.isDigit() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Passphrase & PIN")
        }

        // Whitelist a contact section
        Text(
            text = "Contacts & Widget",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Widget Tap Guide:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "• 2 Taps: Save Parking (Sends to your number)\n" +
                           "• 3 Taps: Safety Check (Sends to starred contacts)\n" +
                           "• 4 Taps: Emergency (Sends to all whitelisted)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        OutlinedTextField(
            value = myNumber,
            onValueChange = { onMyNumberChanged(it) },
            label = { Text("Your Phone Number: Receives your location when the widget is tapped twice.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            singleLine = true
        )

        Button(
            onClick = onPickContact,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text("Pick from Contacts")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = phoneNumberInput,
                onValueChange = { phoneNumberInput = it },
                label = { Text("Type name / number: Must match your contact list for SMS/Messenger replies.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                onAddNumber(phoneNumberInput)
                phoneNumberInput = ""
            }) {
                Text("Add")
            }
        }

        Text(
            text = "Whitelisted:",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(numbersList) { number ->
                val isStarred = starredSet.contains(number)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onToggleStar(number) }) {
                        Icon(
                            imageVector = if (isStarred) Icons.Default.Star else Icons.Outlined.Star,
                            contentDescription = if (isStarred) "Unstar" else "Star",
                            tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onRemoveNumber(number) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove"
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Onboarding
// -------------------------------------------------------------------------

@Composable
fun OnboardingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welcome to QuicLoc") },
        text = {
            Column {
                Text("QuicLoc lets you securely share your location with trusted contacts.\n")
                Text("Ways to share:", style = MaterialTheme.typography.titleSmall)
                Text("1. SMS / Message: Reply 'loc' from a whitelisted contact.")
                Text("2. Remote Lock: Text your secret passphrase to lock your phone and start tracking.")
                Text("3. Widget: Tap the home screen widget:")
                Text("   • 2 Taps: Save Parking")
                Text("   • 3 Taps: Safety Check")
                Text("   • 4 Taps: Emergency\n")
                Text("Getting Started:", style = MaterialTheme.typography.titleSmall)
                Text("• Set a 10+ character passphrase and a 6-digit PIN.")
                Text("• Whitelist contacts you trust.")
                Text("• Star up to 3 'Emergency' contacts.")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Got it!")
            }
        }
    )
}

// -------------------------------------------------------------------------
// History screen
// -------------------------------------------------------------------------

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    historyManager: RequestHistoryManager
) {
    val history = remember { historyManager.getHistory() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Request History Guide:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "This log shows all successful (✓) and failed (✗) location requests. You'll see who requested (Sender), which app was used (Source), and exactly when it happened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No location requests yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(history) { event ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (event.succeeded)
                                MaterialTheme.colorScheme.surfaceVariant
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = event.sender,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "${event.source} · ${event.formattedTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (event.succeeded) "✓" else "✗",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (event.succeeded)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
