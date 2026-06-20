package com.hereliesaz.quicloc.camera

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.hereliesaz.quicloc.IntruderCamera
import java.io.File

/**
 * CameraX implementation of [IntruderCamera], delivered in the on-demand
 * `:feature_camera` module. Instantiated reflectively by
 * `IntruderCameraLoader.load()` in the base once the split is installed.
 *
 * Lifted from the former in-base capture in `TrackingLockActivity`: bind the
 * front camera to the activity lifecycle, then write a JPEG to the activity's
 * external media dir on capture.
 */
class IntruderCameraImpl : IntruderCamera {

    private var imageCapture: ImageCapture? = null

    override fun start(activity: ComponentActivity) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val capture = ImageCapture.Builder().build()
            imageCapture = capture
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    activity, CameraSelector.DEFAULT_FRONT_CAMERA, capture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    override fun capture(activity: ComponentActivity, onResult: (String?) -> Unit) {
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
