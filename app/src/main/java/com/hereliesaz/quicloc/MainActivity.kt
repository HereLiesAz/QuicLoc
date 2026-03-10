package com.hereliesaz.quicloc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.res.stringResource
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
            Toast.makeText(this, "Background location permission is required for QuicLoc to function when the app is closed.", Toast.LENGTH_LONG).show()
        }
    }

    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = REQUIRED_PERMISSIONS.all { permissions[it] == true }
        if (allGranted) {
            checkBackgroundLocationPermission()
        } else {
            Toast.makeText(this, "All permissions are required for QuicLoc to function.", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        whitelistManager = WhitelistManager(this)

        setContent {
            MaterialTheme {
                var numbersList by remember { mutableStateOf(whitelistManager.getNumbers().toList()) }

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
                        onAddNumber = { number ->
                            if (number.isNotBlank()) {
                                whitelistManager.addNumber(number)
                                numbersList = whitelistManager.getNumbers().toList()
                                Toast.makeText(this, "Number added to whitelist", Toast.LENGTH_SHORT).show()
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

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            multiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }
}

@Composable
fun QuicLocScreen(
    modifier: Modifier = Modifier,
    numbersList: List<String>,
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
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = phoneNumberInput,
                onValueChange = { phoneNumberInput = it },
                label = { Text("Enter phone number to whitelist") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    onAddNumber(phoneNumberInput)
                    phoneNumberInput = ""
                }
            ) {
                Text("Add")
            }
        }

        Text(
            text = "Whitelisted Numbers:",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(numbersList) { number ->
                WhitelistItem(
                    number = number,
                    onDeleteClick = { onRemoveNumber(number) }
                )
            }
        }
    }
}

@Composable
fun WhitelistItem(number: String, onDeleteClick: () -> Unit) {
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
        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(id = R.string.remove_whitelist_entry)
            )
        }
    }
}
