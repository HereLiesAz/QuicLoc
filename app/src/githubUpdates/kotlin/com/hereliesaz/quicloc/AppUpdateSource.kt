package com.hereliesaz.quicloc

import android.app.Activity
import com.hereliesaz.aznavrail.updates.AzUpdateChecker

/** Queries the installed distribution for updates without accessing QuicLoc safety data. */
internal fun checkAppUpdates(activity: Activity) {
    AzUpdateChecker.check(activity, "HereLiesAz/QuicLoc")
}
