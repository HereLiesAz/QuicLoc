package com.hereliesaz.quicloc.lockdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hereliesaz.quicloc.FindMyPhone

/**
 * Fired by [android.app.AlarmManager] to drive [TrackingService]'s periodic
 * ticks even while the device is fully suspended — `Handler.postDelayed`
 * cannot fire at all during CPU suspend, which used to silently stall the
 * tracking cadence on a pocketed, screen-off stolen phone. AlarmManager
 * wakeup alarms are the platform's actual mechanism for this; this receiver
 * just forwards the wakeup to the service, which does the real work (see
 * [TrackingService.scheduleNextTick]) and reschedules the next one.
 *
 * Also the entry point that recovers from the *service's own process* being
 * killed (as opposed to a reboot, which [FindMyPhone.resumeTrackingAfterBoot]
 * handles separately): the alarm still fires as long as this manifest
 * receiver is registered, restarting the service, which reloads its
 * persisted session from `quicloc_tracking_state` and resumes.
 */
class TrackingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svcIntent = Intent(context, TrackingService::class.java).apply {
            action = FindMyPhone.ACTION_TICK
        }
        // The SMS broadcast receiver no longer exists. AlarmManager delivery
        // wakes this receiver, but a broadcast alone does not guarantee an
        // Android 12+ background FGS start exemption. Test this path on devices
        // with battery optimisation enabled and disabled.
        context.startForegroundService(svcIntent)
    }
}
