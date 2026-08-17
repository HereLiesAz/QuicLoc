package com.hereliesaz.quicloc.lockdown

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.play.core.splitcompat.SplitCompat
import com.hereliesaz.quicloc.WhitelistManager

/**
 * Cover-screen lock activity shown by [TrackingService] when the passphrase
 * trigger fires. Acts as a fallback when Device Admin isn't granted — when
 * admin IS granted, [LockdownController.lockNow] handles the real lock and
 * this activity is just a place to enter the PIN to stop tracking.
 *
 * Window flags (`showWhenLocked`, `turnScreenOn`, etc.) let it draw over
 * the keyguard. `onUserLeaveHint` re-launches itself when the user presses
 * Home — though this background activity launch is restricted on Android
 * 10+ and may not always succeed.
 *
 * The 3rd wrong PIN entry (and only the 3rd — see [onPinEntered]) triggers
 * panic mode:
 *
 *   1. Lazily bind and capture a front-camera photo via [IntruderCamera]
 *      (returns no photo if CAMERA was never granted, or binding fails).
 *   2. Hand the photo path to [TrackingService.enterPanicMode], which
 *      shortens the tracking interval to 1 min and sends the photo via MMS
 *      exactly once.
 *
 * Correct PIN → [TrackingService.stopTracking] + `finishAndRemoveTask`.
 *
 * Known limitations:
 *
 *   - Without Device Admin, the screen is only *covered*, not locked. A
 *     thief can still pull the notification shade, tap other apps from
 *     there, or use Recents.
 *   - PIN comparison uses `==` (not constant-time). The 3-strikes rule is
 *     the practical defense.
 *   - CAMERA is never requested here — a runtime permission dialog shown to
 *     whoever is holding the phone would both tip them off and give them a
 *     one-tap way to permanently deny it. It must already be granted from
 *     the find-my-phone setup flow (MainActivity requests it right after the
 *     module installs); if it wasn't, panic mode still proceeds, just
 *     without a photo.
 */
class TrackingLockActivity : ComponentActivity() {

    private var failCount = 0
    // The camera lives in this same split, so it's always present here. Only
    // bound lazily, right before a capture — see IntruderCamera's class doc
    // for why binding it for the whole lock session would be worse.
    private val intruderCamera = IntruderCamera()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Make freshly-installed dynamic-feature code/resources available to
        // this Activity's classloader (no-op if already present).
        SplitCompat.installActivity(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("FAIL_COUNT", failCount)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        failCount = savedInstanceState?.getInt("FAIL_COUNT", 0) ?: 0

        // Show this activity over the keyguard and wake the screen, but DO
        // NOT dismiss the keyguard itself — the user must satisfy BOTH the
        // system unlock AND the QuicLoc PIN to stop tracking. A previous
        // version included FLAG_DISMISS_KEYGUARD, which on a non-secure
        // lockscreen actively bypassed the device lock; that's removed.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Handle back button to prevent exiting the lock screen
        onBackPressedDispatcher.addCallback(this) {
            // Do nothing to prevent back button
        }

        setContent {
            MaterialTheme {
                LockScreenUI(onPinEntered = ::onPinEntered)
            }
        }
    }

    private fun onPinEntered(pin: String) {
        val expectedPin = WhitelistManager(this).getPin()
        when (val outcome = PinAttemptDecision.evaluate(pin, expectedPin, failCount)) {
            is PinAttemptOutcome.Unlocked -> {
                TrackingService.stopTracking(this)
                finishAndRemoveTask()
            }
            is PinAttemptOutcome.WrongPin -> {
                failCount = outcome.failCount
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
            is PinAttemptOutcome.PanicTriggered -> {
                // Escalate exactly once, on the transition into the 3rd
                // wrong attempt — PinAttemptDecision returns AlreadyLocked
                // (not another PanicTriggered) for every attempt after this
                // one, so this branch fires at most once per lock session.
                failCount = outcome.failCount
                triggerPanicMode()
            }
            is PinAttemptOutcome.AlreadyLocked -> {
                failCount = outcome.failCount
                Toast.makeText(this, "Device Locked.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isFinishing) {
            val intent = android.content.Intent(this, TrackingLockActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    /**
     * Fires exactly once per lock session, on the 3rd wrong PIN. Binds the
     * front camera (if CAMERA is granted) and captures a photo, then
     * escalates [TrackingService] into panic mode regardless of whether the
     * photo succeeded. The photo lives in [externalMediaDirs] and is deleted
     * by [TrackingService] once it's been sent — see that class's
     * `sendMmsPhoto`.
     */
    private fun triggerPanicMode() {
        Toast.makeText(this, "Device Locked.", Toast.LENGTH_SHORT).show()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            intruderCamera.start(this) {
                intruderCamera.capture(this) { path ->
                    TrackingService.enterPanicMode(this@TrackingLockActivity, path)
                }
            }
        } else {
            TrackingService.enterPanicMode(this, null)
        }
    }
}

@Composable
fun LockScreenUI(onPinEntered: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Device Tracking Active",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                label = { Text("Enter 6-digit PIN to unlock") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { onPinEntered(pin) }) {
                Text("Unlock")
            }
        }
    }
}
