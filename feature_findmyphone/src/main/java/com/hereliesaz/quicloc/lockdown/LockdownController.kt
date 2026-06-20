package com.hereliesaz.quicloc.lockdown

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log

/**
 * Single entry point for locking the device when the panic / find-my-device
 * flow is activated. Uses Device Admin's [DevicePolicyManager.lockNow] when
 * the user has granted admin rights; otherwise the caller is expected to
 * fall back to showing [TrackingLockActivity] over the keyguard.
 */
object LockdownController {
    private const val TAG = "QuicLoc.Lockdown"

    /**
     * Attempts to immediately lock the device.
     *
     * @return true if the device was actually locked via DevicePolicyManager,
     *         false if Device Admin is not granted. In the false case, the
     *         caller should also show [TrackingLockActivity] (the cover-screen
     *         fallback) so something is on screen.
     */
    fun lockNow(context: Context): Boolean {
        if (!QuicLocDeviceAdmin.isAdminActive(context)) {
            Log.w(TAG, "Device Admin not granted — cannot lock device. Caller should fall back to cover-screen.")
            return false
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            Log.e(TAG, "DevicePolicyManager unavailable")
            return false
        }
        return try {
            dpm.lockNow()
            Log.i(TAG, "Device locked via DevicePolicyManager")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "lockNow() denied", e)
            false
        }
    }
}
