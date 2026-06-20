package com.hereliesaz.quicloc.lockdown

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
 * Lives in the on-demand `:feature_findmyphone` module, so the base install
 * carries no Device Admin receiver until find-my-phone is set up. The base
 * checks admin status via [com.hereliesaz.quicloc.FindMyPhone.isAdminActive],
 * which constructs this receiver's [ComponentName] by name (no class
 * reference) so it works before the module is installed.
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
