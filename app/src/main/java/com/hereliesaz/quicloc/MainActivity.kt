package com.hereliesaz.quicloc

import android.Manifest
import androidx.appcompat.app.AppCompatDelegate
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var whitelistManager: WhitelistManager

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

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
        // After location permissions, prompt for notification access
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force dark mode regardless of system setting
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        whitelistManager = WhitelistManager(this)

        setContent {
            MaterialTheme {
                var numbersList by remember { mutableStateOf(whitelistManager.getNumbers().toList()) }
                var notificationAccessGranted by remember {
                    mutableStateOf(isNotificationListenerEnabled())
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("QuicLoc") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        )
                    }
                ) { innerPadding ->
                    QuicLocScreen(
                        modifier = Modifier.padding(innerPadding),
                        numbersList = numbersList,
                        notificationAccessGranted = notificationAccessGranted,
                        onRequestNotificationAccess = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onAddNumber = { number ->
                            if (number.isNotBlank()) {
                                whitelistManager.addNumber(number)
                                numbersList = whitelistManager.getNumbers().toList()
                                Toast.makeText(this, "Added to whitelist", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRemoveNumber = { number ->
                            whitelistManager.removeNumber(number)
                            numbersList = whitelistManager.getNumbers().toList()
                        }
                    )
                }
            }
        }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-check notification access when returning from settings
    }

    private fun checkPermissions() {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasSms || !hasLocation) {
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
            // The UI will show a prompt — user taps the button to go to settings
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

@Composable
fun QuicLocScreen(
    modifier: Modifier = Modifier,
    numbersList: List<String>,
    notificationAccessGranted: Boolean,
    onRequestNotificationAccess: () -> Unit,
    onAddNumber: (String) -> Unit,
    onRemoveNumber: (String) -> Unit
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

        // Notification access warning banner
        if (!notificationAccessGranted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Grant Notification Access")
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = "✓ Notification Access granted — QuicLoc will respond in all messaging apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Text(
            text = "Whitelist a number or contact name:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = phoneNumberInput,
                onValueChange = { phoneNumberInput = it },
                label = { Text("Phone number or contact name") },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
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
