package com.hereliesaz.quicloc

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

/**
 * The single base→module bridge for the on-demand `:feature_findmyphone`
 * dynamic feature module, which holds the ENTIRE find-my-phone / lockdown
 * feature (tracking service, PIN-gate lock screen, Device Admin receiver,
 * intruder camera). The base declares none of that — nor its CAMERA /
 * USE_FULL_SCREEN_INTENT permissions, nor the Device Admin receiver — until
 * the module is downloaded during find-my-phone setup.
 *
 * Because the base can't compile-time reference classes that live in the
 * module, every entry point here addresses the module by `ComponentName`
 * string and degrades gracefully (returns `false`/no-op) when the split isn't
 * installed. The module itself does `implementation(project(":app"))`, so the
 * reverse direction (module calling base `LocationHelper`/`WhitelistManager`/…)
 * stays direct.
 */
object FindMyPhone {

    /**
     * Master kill switch for the entire find-my-phone / lockdown feature.
     *
     * When `false` (current state), every entry point below short-circuits, so
     * the base never tries to install, trigger, or query the module — the
     * passphrase trigger no-ops and the setup UI is hidden (see MainActivity).
     *
     * This flag is the *runtime* half of disabling the feature; the *packaging*
     * half is that `:feature_findmyphone` is removed from `settings.gradle.kts`
     * and the app's `dynamicFeatures`, so the module's manifest (CAMERA,
     * USE_FULL_SCREEN_INTENT, the Device Admin receiver, the tracking service)
     * is no longer merged into the shipped app and those permissions aren't
     * declared. To re-enable find-my-phone, flip this to `true` AND re-add the
     * module in both Gradle files. The module's code is kept intact in the repo.
     */
    const val ENABLED = false

    const val MODULE = "feature_findmyphone"

    private const val PKG = "com.hereliesaz.quicloc"
    private const val SERVICE_CLASS = "com.hereliesaz.quicloc.lockdown.TrackingService"
    private const val ADMIN_CLASS = "com.hereliesaz.quicloc.lockdown.QuicLocDeviceAdmin"
    private const val TAG = "QuicLoc.FindMyPhone"

    // Intent extras the module's TrackingService.onStartCommand reads. Defined
    // here and referenced by the module (via implementation(project(":app"))) so
    // the producer (trigger) and consumer can never drift apart.
    const val EXTRA_SENDER = "sender"
    const val EXTRA_SOURCE = "source"

    /** Whether the find-my-phone split is installed and its code is available. */
    fun isInstalled(context: Context): Boolean {
        if (!ENABLED) return false
        return try {
            SplitInstallManagerFactory.create(context.applicationContext)
                .installedModules.contains(MODULE)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Start the module's `TrackingService` for a passphrase trigger, addressing
     * it by `ComponentName` (the class isn't on the base classpath). Returns
     * `false` — and starts nothing — if the module isn't installed (the
     * component is absent from the merged manifest), so a `loc <passphrase>`
     * that arrives before the module is downloaded simply doesn't start
     * tracking. [source] is "SMS" or the originating app's package name.
     */
    fun trigger(context: Context, sender: String, source: String): Boolean {
        if (!ENABLED) return false
        if (!isInstalled(context)) {
            Log.w(TAG, "Find-my-phone module not installed — cannot start tracking")
            return false
        }
        return try {
            val intent = Intent().apply {
                component = ComponentName(PKG, SERVICE_CLASS)
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, source)
            }
            context.startForegroundService(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "TrackingService unavailable (module not installed?)", t)
            false
        }
    }

    /** The module's Device Admin receiver, addressed by name (no class ref). */
    fun adminComponent(): ComponentName = ComponentName(PKG, ADMIN_CLASS)

    /**
     * Whether QuicLoc's Device Admin is active. Works before the module is
     * installed — `DevicePolicyManager.isAdminActive` on a not-yet-real
     * component just returns `false` (the correct "not granted" state).
     */
    fun isAdminActive(context: Context): Boolean {
        if (!ENABLED) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return try {
            dpm.isAdminActive(adminComponent())
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Best-effort request to download the find-my-phone module. Called from the
     * find-my-phone setup flow (foreground) — you can't reliably download it at
     * trigger time. [onInstalled] runs (on a Play Core thread) when the module
     * is already/becomes installed; the caller marshals to the main thread. The
     * whole feature's permissions (CAMERA, USE_FULL_SCREEN_INTENT) and its
     * Device Admin receiver only become requestable once the split — and thus
     * its merged manifest — is part of the app, so setup must install first,
     * then prompt.
     */
    fun requestInstall(context: Context, onInstalled: () -> Unit = {}) {
        if (!ENABLED) return
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
                Log.w(TAG, "Find-my-phone module install request failed", it)
                manager.unregisterListener(listener)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not request find-my-phone module install", e)
        }
    }
}
