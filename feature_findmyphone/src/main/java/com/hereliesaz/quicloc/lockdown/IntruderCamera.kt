package com.hereliesaz.quicloc.lockdown

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Front-camera intruder-photo capture for panic mode. Lives in the on-demand
 * `:feature_findmyphone` module alongside its only caller,
 * [TrackingLockActivity], so it's a plain class — no interface, no reflection.
 *
 * Bind the front camera to the activity lifecycle, then write a JPEG to the
 * activity's external media dir on capture. Every call degrades gracefully
 * (returns `null`) when the camera isn't ready, so a failed capture never
 * blocks the lock.
 *
 * [start] is deliberately called lazily — right before the capture it's for,
 * not for the whole lock session — because binding a `CameraX` use case
 * opens the camera device immediately, lighting the OS privacy indicator for
 * as long as it stays bound. Binding it for the entire session would light
 * that indicator well before any capture actually happens, telegraphing the
 * trap to whoever is holding the phone.
 */
class IntruderCamera {

    private var imageCapture: ImageCapture? = null

    /**
     * Bind the front camera to [activity]'s lifecycle so a capture is ready.
     * [onReady] runs once binding finishes (success or failure) — [capture]
     * called before that would just find nothing ready and report no photo,
     * so callers should wait for it.
     */
    fun start(activity: ComponentActivity, onReady: () -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val capture = ImageCapture.Builder().build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    activity, CameraSelector.DEFAULT_FRONT_CAMERA, capture
                )
                imageCapture = capture
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
            onReady()
        }, ContextCompat.getMainExecutor(activity))
    }

    /**
     * Take a photo. [onResult] gets the saved file's absolute path, or `null`
     * if capture wasn't possible — callers must treat `null` as "no photo" and
     * carry on (the lock still works).
     */
    fun capture(activity: ComponentActivity, onResult: (String?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            Log.e(TAG, "Image capture not ready")
            onResult(null)
            return
        }
        val outputDirectory = activity.externalMediaDirs.firstOrNull()
        if (outputDirectory == null) {
            Log.e(TAG, "No external media directory")
            onResult(null)
            return
        }
        val photoFile = File(outputDirectory, "${System.currentTimeMillis()}_lock.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    onResult(null)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")
                    onResult(photoFile.absolutePath)
                }
            }
        )
    }

    companion object {
        private const val TAG = "QuicLoc.IntruderCamera"
    }
}
