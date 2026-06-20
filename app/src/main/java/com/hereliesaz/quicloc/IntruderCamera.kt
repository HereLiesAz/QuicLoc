package com.hereliesaz.quicloc

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

/**
 * Contract for the panic-mode front-camera capture. The implementation lives
 * in the on-demand `:feature_camera` dynamic feature module so the base install
 * never declares the `CAMERA` permission until the user opts into find-my-phone.
 *
 * Only base types cross this boundary (no CameraX), so the base compiles and
 * runs without the module. The module's [IntruderCameraLoader.MODULE] impl is
 * resolved by reflection once the split is installed.
 */
interface IntruderCamera {
    /** Bind the front camera to [activity]'s lifecycle so a capture is ready. */
    fun start(activity: ComponentActivity)

    /**
     * Take a photo. [onResult] gets the saved file's absolute path, or `null`
     * if capture wasn't possible — callers must treat `null` as "no photo" and
     * carry on (the lock still works).
     */
    fun capture(activity: ComponentActivity, onResult: (String?) -> Unit)
}

/**
 * Bridges the base to the on-demand `:feature_camera` module via Play Feature
 * Delivery. All calls degrade gracefully (return `false`/`null`/no-op) when the
 * module isn't installed or Play Core isn't available — so a device that never
 * downloaded the module simply locks without an intruder photo.
 */
object IntruderCameraLoader {

    const val MODULE = "feature_camera"
    private const val IMPL = "com.hereliesaz.quicloc.camera.IntruderCameraImpl"
    private const val TAG = "QuicLoc.IntruderCamera"

    /** Whether the camera split is installed and its code is available. */
    fun isInstalled(context: Context): Boolean = try {
        SplitInstallManagerFactory.create(context.applicationContext)
            .installedModules.contains(MODULE)
    } catch (e: Throwable) {
        false
    }

    /** Reflectively instantiate the module's [IntruderCamera]; `null` if absent. */
    fun load(): IntruderCamera? = try {
        Class.forName(IMPL).getDeclaredConstructor().newInstance() as IntruderCamera
    } catch (e: Throwable) {
        Log.w(TAG, "Camera module not loadable (not installed yet?)", e)
        null
    }

    /**
     * Best-effort request to download the camera module. Called from the
     * find-my-phone setup flow (foreground) so the photo is available before a
     * panic event — you can't reliably download it at panic time. [onInstalled]
     * runs (on a Play Core thread) when the module is already/becomes installed;
     * the caller is responsible for marshalling to the main thread. It's used to
     * prompt for the CAMERA permission, which only becomes requestable once the
     * split — and thus its manifest permission — is part of the app.
     */
    fun requestInstall(context: Context, onInstalled: () -> Unit = {}) {
        try {
            val manager = SplitInstallManagerFactory.create(context.applicationContext)
            if (manager.installedModules.contains(MODULE)) {
                onInstalled()
                return
            }
            val listener = object : SplitInstallStateUpdatedListener {
                override fun onStateUpdate(state: SplitInstallSessionState) {
                    when (state.status()) {
                        SplitInstallSessionStatus.INSTALLED -> {
                            manager.unregisterListener(this)
                            onInstalled()
                        }
                        // Terminal failures: unregister so we don't leak the
                        // listener when the download fails or is canceled.
                        SplitInstallSessionStatus.FAILED,
                        SplitInstallSessionStatus.CANCELED -> {
                            manager.unregisterListener(this)
                        }
                        else -> { /* PENDING / DOWNLOADING / INSTALLING — keep waiting */ }
                    }
                }
            }
            manager.registerListener(listener)
            val request = SplitInstallRequest.newBuilder().addModule(MODULE).build()
            manager.startInstall(request).addOnFailureListener {
                Log.w(TAG, "Camera module install request failed", it)
                manager.unregisterListener(listener)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not request camera module install", e)
        }
    }
}
