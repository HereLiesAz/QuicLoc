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
     * When `false`, every entry point below short-circuits, so the base never
     * tries to install, trigger, or query the module — the passphrase trigger
     * no-ops and the setup UI is hidden (see MainActivity).
     *
     * This flag is the *runtime* half of enabling the feature; the *packaging*
     * half is `:feature_findmyphone` being included in `settings.gradle.kts`
     * and the app's `dynamicFeatures`, so the module's manifest (CAMERA,
     * USE_FULL_SCREEN_INTENT, the Device Admin receiver, the tracking service)
     * is merged into the app and those permissions are declared. To disable
     * find-my-phone again, flip this to `false` — removing the module from
     * both Gradle files as well is optional (it just stops the module's code
     * from being compiled/shipped at all; `ENABLED = false` alone already
     * makes every entry point a no-op).
     */
    const val ENABLED = true

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

    /**
     * Action TrackingService treats as "resume an already-persisted tracking
     * session" — used by both the module's own TrackingAlarmReceiver (each
     * periodic tick) and [resumeTrackingAfterBoot] here in the base. Defined
     * here for the same reason as [EXTRA_SENDER]/[EXTRA_SOURCE].
     */
    const val ACTION_TICK = "com.hereliesaz.quicloc.TICK_TRACKING"

    private const val TRACKING_STATE_PREFS = "quicloc_tracking_state"

    /**
     * Whether the find-my-phone module's code is actually present and
     * loadable right now.
     *
     * Resolves [SERVICE_CLASS] through [android.content.pm.PackageManager]
     * rather than asking `SplitInstallManager.installedModules` alone:
     * `installedModules` is Play Feature Delivery bookkeeping keyed by split
     * name, and a sideload build has no splits at all — the module is fused
     * into one monolithic APK by `dist:fusing` (see
     * `.github/actions/build-universal-apk`). On a fused install
     * `installedModules` would report the module absent even though its
     * classes and manifest entries are right there, which used to make this
     * whole feature permanently unreachable for every sideload user. Package
     * Manager reports the real, current merged-manifest truth for both
     * shapes of install (fused sideload APK and Play on-demand split), which
     * is exactly the "can I actually start `TrackingService` right now?"
     * question every caller here is really asking.
     */
    fun isInstalled(context: Context): Boolean {
        if (!ENABLED) return false
        val resolvable = try {
            context.packageManager.getServiceInfo(ComponentName(PKG, SERVICE_CLASS), 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
        if (resolvable) return true
        // Fallback for the Play on-demand path: covers the narrow window
        // right after a SplitInstall download completes, in case the merged
        // manifest PackageManager just queried hasn't caught up to it yet on
        // this exact frame.
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
        if (isInstalled(context)) {
            // Covers the fused sideload build (no Play Feature Delivery
            // involved at all) as well as an already-downloaded Play split.
            onInstalled()
            return
        }
        try {
            val manager = SplitInstallManagerFactory.create(context.applicationContext)
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

    /**
     * Whether `TrackingService` believed it was actively tracking the last
     * time its state was persisted. Reads `quicloc_tracking_state` directly
     * — the same plain-`SharedPreferences` file/key `TrackingService` itself
     * writes — rather than through a module class reference, for the same
     * reason every other entry point here addresses the module by name (see
     * the class doc). Used by [resumeTrackingAfterBoot].
     *
     * `internal` rather than `private` only so it's directly unit-testable
     * from this module's own test source set — Robolectric currently can't
     * bootstrap `:feature_findmyphone`'s own test environment (its
     * dynamic-feature resource package ID hits an unrelated Robolectric
     * limitation), so this bridge logic's coverage lives here in the base
     * instead. `:feature_findmyphone` has no reason to call this directly
     * and shouldn't.
     */
    internal fun wasTrackingActive(context: Context): Boolean {
        if (!ENABLED) return false
        return context.getSharedPreferences(TRACKING_STATE_PREFS, Context.MODE_PRIVATE)
            .getString("sender", null) != null
    }

    /**
     * Resumes tracking after a reboot, if [wasTrackingActive]; no-op
     * otherwise. `TrackingService` is `START_STICKY`, which the OS uses to
     * restart a *killed process* — it does nothing across an actual reboot,
     * so without this call a thief power-cycling a tracked device (the
     * single most obvious thing to try) would end protection permanently,
     * with no way to re-arm since the passphrase that started this session
     * was already consumed as single-use.
     *
     * Reads plain `SharedPreferences`, which live in credential-encrypted
     * storage: this can only resume tracking once the device has been
     * unlocked at least once since the reboot (i.e. from [BootReceiver]'s
     * normal `BOOT_COMPLETED`, not before). That is not full Direct Boot
     * support — genuinely resuming *before* any post-reboot unlock would
     * additionally require `WhitelistManager`'s PIN/passphrase (Keystore-backed
     * `EncryptedSharedPreferences`) to be readable pre-unlock too, which
     * they are not, and re-architecting that trades away real security
     * elsewhere for a narrower theft window. Documented here rather than
     * silently assumed.
     */
    fun resumeTrackingAfterBoot(context: Context) {
        if (!ENABLED || !isInstalled(context) || !wasTrackingActive(context)) return
        try {
            val intent = Intent().apply {
                component = ComponentName(PKG, SERVICE_CLASS)
                action = ACTION_TICK
            }
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resume tracking after boot", t)
        }
    }
}
