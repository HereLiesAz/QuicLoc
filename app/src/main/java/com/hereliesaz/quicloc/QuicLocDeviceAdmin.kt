package com.hereliesaz.quicloc

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Device Admin receiver that lets QuicLoc actually lock the device (rather
 * than just covering the screen with an Activity). The user must explicitly
 * grant admin rights from system settings; until then [LockdownController]
 * falls back to the cover-screen behavior.
 *
 * Policy requested: force-lock only. We never wipe, never change passwords.
 */
class QuicLocDeviceAdmin : DeviceAdminReceiver() {
    // No additional callbacks needed — we just need the admin registration
    // so DevicePolicyManager.lockNow() will succeed.

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, QuicLocDeviceAdmin::class.java)

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return dpm.isAdminActive(componentName(context))
        }
    }
}
