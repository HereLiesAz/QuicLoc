package com.hereliesaz.quicloc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TrackingLockActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuicLoc.TrackingLockActivity"
    }

    private var failCount = 0
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("FAIL_COUNT", failCount)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        failCount = savedInstanceState?.getInt("FAIL_COUNT", 0) ?: 0

        // Window flags for lock screen behavior
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
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

    override fun onBackPressed() {
        // Prevent back button
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
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


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun triggerPanicMode() {
        Toast.makeText(this, "Device Locked.", Toast.LENGTH_SHORT).show()
        val currentCapture = imageCapture
        if (currentCapture == null) {
            Log.e(TAG, "Image capture is null, entering panic mode without photo")
            TrackingService.enterPanicMode(this@TrackingLockActivity, null)
            return
        }

        val outputDirectory = externalMediaDirs.firstOrNull()
        if (outputDirectory == null) {
            Log.e(TAG, "No external media directory found, entering panic mode without photo")
            TrackingService.enterPanicMode(this@TrackingLockActivity, null)
            return
        }
        val photoFile = File(outputDirectory, "${System.currentTimeMillis()}_lock.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        currentCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    TrackingService.enterPanicMode(this@TrackingLockActivity, null)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")
                    TrackingService.enterPanicMode(this@TrackingLockActivity, photoFile.absolutePath)
                }
            })
    }
override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
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
