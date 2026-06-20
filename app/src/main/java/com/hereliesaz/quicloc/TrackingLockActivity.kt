package com.hereliesaz.quicloc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.play.core.splitcompat.SplitCompat

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
 * 3 wrong PIN entries → panic mode:
 *
 *   1. Capture a front-camera photo via the on-demand `:feature_camera` module
 *      (skipped if it isn't installed).
 *   2. Hand the photo path to [TrackingService.enterPanicMode] which
 *      shortens the tracking interval to 1 min and sends the photo via MMS
 *      on the next tick.
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
 *   - The intruder-photo capture lives in the on-demand `:feature_camera`
 *     module ([IntruderCamera]). It's resolved here only if that split is
 *     installed; otherwise panic mode proceeds without a photo. The module is
 *     pre-downloaded during find-my-phone setup, and CAMERA can't be requested
 *     over the keyguard anyway, so we rely on it being granted up front.
 */
class TrackingLockActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuicLoc.TrackingLockActivity"
    }

    private var failCount = 0
    // Non-null only when the :feature_camera split is installed and loadable.
    private var intruderCamera: IntruderCamera? = null

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

        // Resolve the intruder camera only if its on-demand module is present.
        if (IntruderCameraLoader.isInstalled(this)) {
            intruderCamera = IntruderCameraLoader.load()
        }
        if (intruderCamera != null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                intruderCamera?.start(this)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
            }
        }

        setContent {
            MaterialTheme {
                LockScreenUI(
                    onPinEntered = { pin ->
                        val expectedPin = WhitelistManager(this).getPin()
                        if (pin == expectedPin) {
                            TrackingService.stopTracking(this)
                            finishAndRemoveTask()
                        } else {
                            failCount++
                            if (failCount >= 3) {
                                triggerPanicMode()
                            } else {
                                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }

    // Removed the invalid override fun super.onBackPressed()


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            intruderCamera?.start(this)
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
     * Fires after 3 wrong PIN entries. Captures a front-camera photo via the
     * on-demand camera module (if installed) and escalates [TrackingService]
     * into panic mode. The photo lives in [externalMediaDirs] — excluded from
     * Auto Backup via `data_extraction_rules.xml`. If the module isn't present,
     * panic mode still proceeds, just without a photo.
     */
    private fun triggerPanicMode() {
        Toast.makeText(this, "Device Locked.", Toast.LENGTH_SHORT).show()
        val cam = intruderCamera
        if (cam == null) {
            Log.w(TAG, "Camera module unavailable, entering panic mode without photo")
            TrackingService.enterPanicMode(this@TrackingLockActivity, null)
            return
        }
        cam.capture(this) { path ->
            TrackingService.enterPanicMode(this@TrackingLockActivity, path)
        }
    }
}

@Composable
fun LockScreenUI(onPinEntered: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize(),
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
                onValueChange = { pin = it },
                label = { Text("Enter 6-digit PIN to unlock") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { onPinEntered(pin) }) {
                Text("Unlock")
            }
        }
    }
}
