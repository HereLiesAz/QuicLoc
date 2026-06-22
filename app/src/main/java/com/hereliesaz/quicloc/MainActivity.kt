package com.hereliesaz.quicloc

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Top-level navigation state for the authenticated app. A 4-branch `when`
 * over this sealed type drives both the top-bar title/actions and the
 * Scaffold body — simpler than the Navigation library at this app's
 * size.
 *
 *   - [Config] — the settings screen ([QuicLocScreen]).
 *   - [History] — the request log ([HistoryScreen]).
 *   - [TutorialsHub] — list of all tutorials.
 *   - [TutorialDetail] — one tutorial's full body. [fromOnboarding] flips
 *     the confirm-button label ("Got it" vs "Done") and the back-target
 *     (return to Config to dismiss onboarding, vs return to hub).
 */
sealed class MainView {
    data object Config : MainView()
    data object History : MainView()
    data object Diagnostics : MainView()
    data object TutorialsHub : MainView()
    data class TutorialDetail(val tutorialId: String, val fromOnboarding: Boolean = false) : MainView()
}

/**
 * Single-activity Compose host for the entire QuicLoc UI. Extends
 * [FragmentActivity] because that's what [androidx.biometric.BiometricPrompt]
 * requires.
 *
 * Responsibilities:
 *
 *   - Biometric gate on resume ([BiometricHelper]). The background
 *     components (`SmsReceiver`, `NotificationListener`, foreground
 *     services) are NOT gated — only the configuration UI.
 *   - First-launch tutorial flow (auto-shows the "Why QuicLoc?" tutorial
 *     when [WhitelistManager.isOnboardingCompleted] is false).
 *   - Runtime permission orchestration in the right order: foreground
 *     batch → background location → notification listener prompt.
 *   - Optional flows: Phone Number Hint (auto-fill the user's own number),
 *     Device Admin grant, backup export/import.
 *   - Restore-on-launch detection: if a backup blob is present and the
 *     encrypted prefs are empty, show [RestoreFromBackupDialog].
 *
 * Posts the reminder notification on every launch via
 * [ReminderNotification.refresh] — a no-op if the user hasn't opted in.
 */
class MainActivity : FragmentActivity() {   // FragmentActivity required by BiometricPrompt

    private lateinit var whitelistManager: WhitelistManager

    // Tracks whether the user has passed biometric auth this session.
    // Set to false whenever the app is backgrounded, so re-auth is required on return.
    private var isAuthenticated = false

    /**
     * Static description for each runtime permission QuicLoc may ask for.
     * Order in [FIRST_LAUNCH_FLOW] determines the order the user sees them
     * in the one-at-a-time rationale chain.
     */
    private data class PermissionRationale(
        val permission: String,
        val title: String,
        val body: String,
    )

    private val RATIONALES: Map<String, PermissionRationale> = listOf(
        PermissionRationale(
            Manifest.permission.RECEIVE_SMS,
            "Receive SMS",
            "Lets QuicLoc detect the trigger word \"loc\" arriving from a whitelisted phone number. " +
                "Without it, an SMS trigger can never reach the app. QuicLoc never reads any other message content."
        ),
        PermissionRationale(
            Manifest.permission.SEND_SMS,
            "Send SMS",
            "Lets QuicLoc reply with a Google Maps link to your location. " +
                "Without it, QuicLoc can detect a request but can't answer it."
        ),
        PermissionRationale(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "Precise Location",
            "Gets a GPS fix accurate enough to be useful as a Maps link. " +
                "Used only while answering a request from a whitelisted contact — never proactively, never stored."
        ),
        PermissionRationale(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "Approximate Location",
            "Fallback when GPS isn't available (indoors, no sky view). " +
                "Used only while answering a request, alongside Precise Location."
        ),
        PermissionRationale(
            Manifest.permission.CAMERA,
            "Camera",
            "Powers panic-mode photo capture. If a thief enters the wrong PIN 3 times during find-my-phone mode, " +
                "QuicLoc silently takes one frame from the front camera and MMSes it to the requester. " +
                "Asked up front because the lock screen can't show a permission dialog at the moment it's needed."
        ),
        PermissionRationale(
            "android.permission.POST_NOTIFICATIONS",
            "Show Notifications",
            "Required on Android 13+ so QuicLoc can show the foreground-service notification while a reply is in flight, " +
                "plus the optional always-on reminder and the tracking-active alert. " +
                "Without it, Android silently kills the reply before it finishes."
        ),
        PermissionRationale(
            Manifest.permission.READ_CONTACTS,
            "Read Contacts",
            "Used only when you tap \"Pick from Contacts\" to read this contact's display name and phone numbers " +
                "and add them to your whitelist. Skip it and you can still add contacts by typing them in manually."
        ),
    ).associateBy { it.permission }

    /**
     * The order runtime permissions are presented in the first-launch chain.
     * READ_CONTACTS is intentionally NOT here — it's prompted only when the
     * user taps "Pick from Contacts".
     */
    private val FIRST_LAUNCH_FLOW: List<String> = listOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        // CAMERA is intentionally NOT here — it lives in the on-demand
        // :feature_camera module and is requested only after that module is
        // downloaded during find-my-phone setup (see onSavePassphrase).
    ).let {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            it + "android.permission.POST_NOTIFICATIONS"
        } else it
    }

    /** Set when the launcher fires, called once the system dialog dismisses. */
    private var afterPermissionResult: (granted: Boolean) -> Unit = {}

    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val next = afterPermissionResult
        afterPermissionResult = {}
        next(granted)
    }

    /**
     * State of the rationale modal — null when no dialog is showing.
     * The dialog is rendered in [setContent] alongside the other modals.
     */
    private data class RationaleDialogState(
        val title: String,
        val body: String,
        val confirmLabel: String,
        val onContinue: () -> Unit,
        val onSkip: () -> Unit,
    )

    private val pendingRationaleState = mutableStateOf<RationaleDialogState?>(null)

    /**
     * Show one rationale dialog, then on "Continue" launch the system grant
     * dialog for [permission]. On any result (grant or deny), invoke
     * [onAfter] so the caller can advance to the next step.
     */
    private fun promptRuntimePermission(permission: String, onAfter: (granted: Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            onAfter(true)
            return
        }
        val rationale = RATIONALES[permission] ?: run {
            // Unknown permission — fall back to launching directly with no rationale.
            afterPermissionResult = onAfter
            beginSystemFlow()
            singlePermissionLauncher.launch(permission)
            return
        }
        pendingRationaleState.value = RationaleDialogState(
            title = rationale.title,
            body = rationale.body,
            confirmLabel = "Grant",
            onContinue = {
                pendingRationaleState.value = null
                afterPermissionResult = onAfter
                beginSystemFlow()
                singlePermissionLauncher.launch(permission)
            },
            onSkip = {
                pendingRationaleState.value = null
                onAfter(false)
            }
        )
    }

    /** Walk through [queue] sequentially, then invoke [onDone]. */
    private fun runPermissionChain(queue: List<String>, onDone: () -> Unit) {
        if (queue.isEmpty()) {
            onDone()
            return
        }
        promptRuntimePermission(queue.first()) { _ ->
            runPermissionChain(queue.drop(1), onDone)
        }
    }

    // Compose state — hoisted so biometric callback and launchers can update it
    private var authState = mutableStateOf(false)
    private var numbersState = mutableStateOf<List<String>>(emptyList())
    private var starredState = mutableStateOf<Set<String>>(emptySet())
    private var myNumberState = mutableStateOf("")
    private var enabledState = mutableStateOf(true)
    private var reminderNotifState = mutableStateOf(false)
    private var deviceAdminState = mutableStateOf(false)
    private var fullScreenIntentState = mutableStateOf(true)
    private var permissionStatusesState = mutableStateOf<List<PermissionStatus>>(emptyList())

    /**
     * Four-state grant status for one permission row in [QuicLocScreen]'s
     * All Permissions panel.
     *
     *   - GRANTED — runtime/special perm user has explicitly granted; tap "Manage" to revoke.
     *   - AUTO_GRANTED — install-time, always-on; no button shown.
     *   - NOT_GRANTED — user can tap "Grant" to start the rationale → request flow.
     *   - UNKNOWN — we have no API to query the state (e.g. OEM autostart); tap "Open" opens the relevant settings page.
     */
    enum class PermStatus { GRANTED, AUTO_GRANTED, NOT_GRANTED, UNKNOWN }

    /**
     * One row in the All Permissions panel. [key] is the dispatch token
     * consumed by [dispatchPermissionAction] — either a Manifest permission
     * constant or one of the sentinel strings below.
     */
    data class PermissionStatus(
        val key: String,
        val label: String,
        val category: String,
        val state: PermStatus,
    )

    // Sentinel keys for entries that don't map to a single manifest permission.
    companion object {
        const val KEY_NOTIF_LISTENER = "special.notif_listener"
        const val KEY_DEVICE_ADMIN = "special.device_admin"
        const val KEY_FSI = "special.full_screen_intent"
        const val KEY_BATTERY = "protected.battery"
        const val KEY_AUTOSTART = "protected.autostart"
        const val KEY_NOTIF_CHANNELS = "protected.notif_channels"
    }

    /**
     * Snapshot of every permission QuicLoc declares, plus the three
     * "protected" background-reliability toggles. Refreshed in onCreate /
     * onResume so the user sees live status.
     */
    private fun buildPermissionStatuses(): List<PermissionStatus> {
        fun runtime(perm: String, label: String): PermissionStatus {
            val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            return PermissionStatus(
                key = perm,
                label = label,
                category = "Runtime",
                state = if (granted) PermStatus.GRANTED else PermStatus.NOT_GRANTED,
            )
        }
        fun special(key: String, label: String, granted: Boolean) = PermissionStatus(
            key = key,
            label = label,
            category = "Special access",
            state = if (granted) PermStatus.GRANTED else PermStatus.NOT_GRANTED,
        )
        fun installTime(label: String) = PermissionStatus(
            key = "install.$label",
            label = label,
            category = "Install-time",
            state = PermStatus.AUTO_GRANTED,
        )

        val list = mutableListOf<PermissionStatus>()

        // Runtime — user-prompted on first use
        list += runtime(Manifest.permission.RECEIVE_SMS, "Receive SMS")
        list += runtime(Manifest.permission.SEND_SMS, "Send SMS")
        list += runtime(Manifest.permission.ACCESS_FINE_LOCATION, "Precise Location")
        list += runtime(Manifest.permission.ACCESS_COARSE_LOCATION, "Approximate Location")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            list += runtime(Manifest.permission.ACCESS_BACKGROUND_LOCATION, "Background Location")
        }
        // CAMERA lives in the on-demand :feature_findmyphone module — only show
        // its status once that module is installed (otherwise it'd always read
        // as "not granted" because it isn't in the base manifest).
        if (FindMyPhone.isInstalled(this)) {
            list += runtime(Manifest.permission.CAMERA, "Camera")
        }
        list += runtime(Manifest.permission.READ_CONTACTS, "Read Contacts")
        list += runtime(Manifest.permission.READ_PHONE_NUMBERS, "Read Phone Number")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            list += runtime("android.permission.POST_NOTIFICATIONS", "Show Notifications")
        }

        // Special access — granted via system Settings
        list += special(KEY_NOTIF_LISTENER, "Notification Access", isNotificationListenerEnabled())
        // Device Admin and Full-Screen Notifications belong to the find-my-phone
        // module; hide their rows while that feature is disabled, since their
        // permissions are no longer declared in the shipped manifest.
        if (FindMyPhone.ENABLED) {
            list += special(KEY_DEVICE_ADMIN, "Device Admin", FindMyPhone.isAdminActive(this))
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                list += special(KEY_FSI, "Full Screen Notifications", canUseFullScreenIntent())
            }
        }

        // Protected — keep the app alive in the background
        list += PermissionStatus(
            key = KEY_BATTERY,
            label = "Battery Optimization Exemption",
            category = "Protected",
            state = if (isIgnoringBatteryOptimizations()) PermStatus.GRANTED else PermStatus.NOT_GRANTED,
        )
        list += PermissionStatus(
            key = KEY_AUTOSTART,
            label = "OEM Autostart",
            category = "Protected",
            // No public API to check — defer to the user to verify in OEM settings.
            state = PermStatus.UNKNOWN,
        )
        list += PermissionStatus(
            key = KEY_NOTIF_CHANNELS,
            label = "Notification Channels",
            category = "Protected",
            // We can't easily report a single bool here (multiple channels);
            // surface the row so the user can verify per-channel in Settings.
            state = PermStatus.UNKNOWN,
        )

        // Install-time — auto-granted by declaring in the manifest
        list += installTime("Foreground Service")
        list += installTime("Foreground Service – Location")
        list += installTime("Biometric")
        list += installTime("Fingerprint (legacy)")
        list += installTime("Internet (Play Services)")
        list += installTime("Network State (Play Services)")
        list += installTime("Vibrate")
        list += installTime("Boot Completed")

        return list
    }

    private fun refreshPermissionStatuses() {
        permissionStatusesState.value = buildPermissionStatuses()
    }

    // -------------------------------------------------------------------------
    // Per-row action dispatcher — wired to each row's Grant / Manage / Open
    // button in the All Permissions panel.
    // -------------------------------------------------------------------------

    private fun dispatchPermissionAction(key: String) {
        when (key) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> {
                if (ContextCompat.checkSelfPermission(this, key) == PackageManager.PERMISSION_GRANTED) {
                    openAppDetailsSettings()
                } else {
                    checkBackgroundLocationPermission()
                }
            }
            in RATIONALES.keys -> {
                if (ContextCompat.checkSelfPermission(this, key) == PackageManager.PERMISSION_GRANTED) {
                    openAppDetailsSettings()
                } else {
                    promptRuntimePermission(key) { refreshPermissionStatuses() }
                }
            }
            KEY_NOTIF_LISTENER -> {
                if (isNotificationListenerEnabled()) {
                    openAppDetailsSettings()
                } else {
                    checkNotificationListenerPermission()
                }
            }
            KEY_DEVICE_ADMIN -> {
                if (FindMyPhone.isAdminActive(this)) {
                    openAppDetailsSettings()
                } else {
                    showDeviceAdminRationale()
                }
            }
            KEY_FSI -> {
                if (canUseFullScreenIntent()) {
                    openAppNotificationSettings()
                } else {
                    checkFullScreenIntentPermission(forceShow = true)
                }
            }
            KEY_BATTERY -> {
                if (isIgnoringBatteryOptimizations()) {
                    openBatteryOptimizationList()
                } else {
                    requestBatteryOptimizationExemption()
                }
            }
            KEY_AUTOSTART -> openOemAutostartSettings()
            KEY_NOTIF_CHANNELS -> openAppNotificationSettings()
            else -> openAppDetailsSettings()
        }
    }

    private fun showDeviceAdminRationale() {
        pendingRationaleState.value = RationaleDialogState(
            title = getString(R.string.device_admin_explanation_title),
            body = getString(R.string.device_admin_explanation_body),
            confirmLabel = "Continue",
            onContinue = {
                pendingRationaleState.value = null
                requestDeviceAdmin()
            },
            onSkip = { pendingRationaleState.value = null }
        )
    }

    private fun openAppDetailsSettings() {
        beginSystemFlow()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open app settings on this device.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppNotificationSettings() {
        beginSystemFlow()
        val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            openAppDetailsSettings()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        pendingRationaleState.value = RationaleDialogState(
            title = "Battery Optimization Exemption",
            body = "Android's battery saver can suspend QuicLoc when the device is idle. When it " +
                "does, incoming \"loc\" triggers may be missed and the location reply may not " +
                "finish sending. This exemption keeps QuicLoc reachable in the background — " +
                "QuicLoc only does work when a whitelisted contact actually asks for your location, " +
                "so the battery cost is near zero. The next screen is the system grant dialog.",
            confirmLabel = "Continue",
            onContinue = {
                pendingRationaleState.value = null
                beginSystemFlow()
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    openBatteryOptimizationList()
                }
            },
            onSkip = { pendingRationaleState.value = null }
        )
    }

    private fun openBatteryOptimizationList() {
        beginSystemFlow()
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            openAppDetailsSettings()
        }
    }

    /**
     * Best-effort: each major Chinese OEM hides background autostart behind
     * an unrelated Activity in their pre-installed security app. We probe
     * the known component names in order and launch the first one the
     * PackageManager can resolve; fall back to app info if none match.
     */
    private val OEM_AUTOSTART_INTENTS: List<Pair<String, String>> = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
    )

    private fun openOemAutostartSettings() {
        for ((pkg, cls) in OEM_AUTOSTART_INTENTS) {
            val intent = Intent().apply { component = ComponentName(pkg, cls) }
            if (packageManager.resolveActivity(intent, 0) != null) {
                beginSystemFlow()
                try {
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    // Try the next candidate.
                }
            }
        }
        Toast.makeText(
            this,
            "No autostart settings detected for this device — opening app info instead.",
            Toast.LENGTH_LONG
        ).show()
        openAppDetailsSettings()
    }
    private var showLaunchRestoreState = mutableStateOf(false)
    private var pendingImportUriState = mutableStateOf<Uri?>(null)

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        deviceAdminState.value = FindMyPhone.isAdminActive(this)
        if (deviceAdminState.value) {
            Toast.makeText(this, "Lockdown enabled — QuicLoc can now lock the device.", Toast.LENGTH_SHORT).show()
        }
    }

    private val phoneHintLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            try {
                val number = Identity.getSignInClient(this).getPhoneNumberFromIntent(data)
                if (!number.isNullOrBlank()) {
                    whitelistManager.setMyNumber(number)
                    myNumberState.value = number
                }
            } catch (e: Exception) {
                android.util.Log.d("QuicLoc", "No phone number returned from hint", e)
            }
        }
    }

    private fun requestPhoneNumberHint() {
        AppSettings.markPhoneHintAutoPrompted(this)
        val request = GetPhoneNumberHintIntentRequest.builder().build()
        Identity.getSignInClient(this)
            .getPhoneNumberHintIntent(request)
            .addOnSuccessListener { pendingIntent ->
                try {
                    beginSystemFlow()
                    phoneHintLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent).build()
                    )
                } catch (e: Exception) {
                    android.util.Log.e("QuicLoc", "Failed to launch phone number hint intent", e)
                }
            }
            .addOnFailureListener { e ->
                // Common: Play Services missing, no SIM, no Google account with phone, etc.
                // Silently fall back to manual entry.
                android.util.Log.d("QuicLoc", "Phone number hint unavailable", e)
            }
    }

    // Auto-detect "my number" — entry point for the "Auto-detect from this device"
    // button and the first-show prompt. Prefers reading the SIM's own number
    // (needs READ_PHONE_NUMBERS); falls back to the Google Phone Number Hint sheet
    // when the permission is denied or the carrier didn't provision a number.
    private fun autoDetectMyNumber() {
        // Don't auto-prompt again on the next launch regardless of outcome.
        AppSettings.markPhoneHintAutoPrompted(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val sim = readSimPhoneNumber()
            if (sim.isNotBlank()) {
                applyDetectedNumber(sim)
            } else {
                // Permission held but no number available — try the hint sheet.
                requestPhoneNumberHint()
            }
            return
        }
        phoneNumberPermissionLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS)
    }

    private val phoneNumberPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val sim = if (granted) readSimPhoneNumber() else ""
        if (sim.isNotBlank()) {
            applyDetectedNumber(sim)
        } else {
            // Denied, or granted but the carrier didn't provision a number —
            // fall back to the Google Phone Number Hint sheet.
            requestPhoneNumberHint()
        }
    }

    private fun applyDetectedNumber(number: String) {
        whitelistManager.setMyNumber(number)
        myNumberState.value = number
        Toast.makeText(this, "Your number: $number", Toast.LENGTH_SHORT).show()
    }

    /**
     * Best-effort read of this device's own phone number from the SIM. Requires
     * READ_PHONE_NUMBERS (caller ensures it's granted). Returns "" when the
     * carrier didn't provision the number on the SIM (common) — callers fall back
     * to the Phone Number Hint sheet or manual entry.
     */
    @android.annotation.SuppressLint("MissingPermission", "HardwareIds")
    private fun readSimPhoneNumber(): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val sm = getSystemService(android.telephony.SubscriptionManager::class.java)
                    ?: return ""
                @Suppress("DEPRECATION")
                var subId = android.telephony.SmsManager.getDefaultSmsSubscriptionId()
                if (subId == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    subId = android.telephony.SubscriptionManager.getDefaultSubscriptionId()
                }
                if (subId == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) return ""
                sm.getPhoneNumber(subId)?.takeIf { it.isNotBlank() } ?: ""
            } else {
                val tm = getSystemService(android.telephony.TelephonyManager::class.java)
                    ?: return ""
                @Suppress("DEPRECATION")
                (tm.line1Number ?: "")
            }
        } catch (e: Exception) {
            android.util.Log.w("QuicLoc", "Could not read SIM phone number", e)
            ""
        }
    }

    // -------------------------------------------------------------------------
    // Backup export / import (manual SAF flow)
    // -------------------------------------------------------------------------

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(BackupVault.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val result = BackupVault.exportToUri(this, uri)
        val msg = result.fold(
            onSuccess = { "Backup exported." },
            onFailure = { "Export failed: ${it.message}" }
        )
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // Defer the actual restore until the user enters their PIN.
        pendingImportUriState.value = uri
    }

    private fun requestExport() {
        beginSystemFlow()
        exportBackupLauncher.launch("quicloc-backup.qlb")
    }

    private fun requestImport() {
        beginSystemFlow()
        importBackupLauncher.launch(arrayOf("*/*"))
    }

    private fun refreshAllStateAfterRestore() {
        numbersState.value = whitelistManager.getNumbers().toList()
        starredState.value = whitelistManager.getStarredNumbers()
        myNumberState.value = whitelistManager.getMyNumber()
        enabledState.value = AppSettings.isEnabled(this)
        reminderNotifState.value = AppSettings.isReminderNotificationEnabled(this)
        deviceAdminState.value = FindMyPhone.isAdminActive(this)
    }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) handleContactPicked(uri)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        whitelistManager = WhitelistManager(this)
        numbersState.value = whitelistManager.getNumbers().toList()
        starredState.value = whitelistManager.getStarredNumbers()
        myNumberState.value = whitelistManager.getMyNumber()
        enabledState.value = AppSettings.isEnabled(this)
        reminderNotifState.value = AppSettings.isReminderNotificationEnabled(this)
        deviceAdminState.value = FindMyPhone.isAdminActive(this)
        fullScreenIntentState.value = canUseFullScreenIntent()
        refreshPermissionStatuses()

        // Show the launch-time restore prompt if the encrypted prefs are
        // empty (fresh install) but a PIN-encrypted backup blob is present
        // (shipped over by Auto Backup or device transfer).
        val looksLikeFreshInstall =
            whitelistManager.getNumbers().isEmpty() && whitelistManager.getPin() == null
        showLaunchRestoreState.value =
            looksLikeFreshInstall && BackupVault.isAvailable(this)

        // No-op when the user hasn't opted into the reminder notification;
        // otherwise refresh so the notification reflects current state.
        ReminderNotification.refresh(this)

        setContent {
            QuicLocTheme {
                val authenticated by authState

                if (!authenticated) {
                    BiometricGateScreen(
                        onRetry = { promptBiometric() }
                    )
                } else {

                    val numbersList by numbersState
                    val starredSet by starredState
                    val myNumber by myNumberState
                    val enabled by enabledState
                    val reminderEnabled by reminderNotifState
                    val deviceAdminGranted by deviceAdminState
                    val fullScreenIntentGranted by fullScreenIntentState
                    val permissionStatuses by permissionStatusesState
                    var currentPassphrase by remember { mutableStateOf(whitelistManager.getPassphrase() ?: "") }
                    var currentPin by remember { mutableStateOf(whitelistManager.getPin() ?: "") }

                    var notificationAccessGranted by remember {
                        mutableStateOf(isNotificationListenerEnabled())
                    }
                    var view by remember {
                        mutableStateOf<MainView>(
                            if (!whitelistManager.isOnboardingCompleted())
                                MainView.TutorialDetail(Tutorials.MAIN_ID, fromOnboarding = true)
                            else
                                MainView.Config
                        )
                    }

                    val showLaunchRestore by showLaunchRestoreState
                    val pendingImportUri by pendingImportUriState

                    if (showLaunchRestore) {
                        RestoreFromBackupDialog(
                            title = "Restore previous backup?",
                            body = "QuicLoc found an encrypted backup from a previous install. Enter your previous PIN to restore your contacts and settings.",
                            onRestore = { pin ->
                                val result = BackupVault.restoreFromInternal(this@MainActivity, pin)
                                if (result.isSuccess) {
                                    refreshAllStateAfterRestore()
                                    showLaunchRestoreState.value = false
                                    // If the restored data marks onboarding
                                    // complete, jump straight to Config so the
                                    // user isn't pushed through the tutorial
                                    // again on the new device.
                                    if (whitelistManager.isOnboardingCompleted()) {
                                        view = MainView.Config
                                    }
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Restored ${result.getOrNull()?.whitelistCount ?: 0} contacts.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                result
                            },
                            onSkip = { showLaunchRestoreState.value = false }
                        )
                    }

                    if (pendingImportUri != null) {
                        val importUri = pendingImportUri!!
                        RestoreFromBackupDialog(
                            title = "Restore from imported file?",
                            body = "Enter the PIN that was set when this backup was made.",
                            onRestore = { pin ->
                                val result = BackupVault.restoreFromUri(this@MainActivity, importUri, pin)
                                if (result.isSuccess) {
                                    refreshAllStateAfterRestore()
                                    pendingImportUriState.value = null
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Restored ${result.getOrNull()?.whitelistCount ?: 0} contacts.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                result
                            },
                            onSkip = { pendingImportUriState.value = null }
                        )
                    }

                    // One-at-a-time permission rationale dialog. Set by
                    // promptRuntimePermission / checkBackgroundLocationPermission /
                    // checkNotificationListenerPermission. Drives both the
                    // first-launch chain and any later request.
                    val rationale by pendingRationaleState
                    rationale?.let { state ->
                        AlertDialog(
                            onDismissRequest = state.onSkip,
                            title = { Text(state.title) },
                            text = {
                                Text(
                                    text = state.body,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            confirmButton = {
                                Button(onClick = state.onContinue) { Text(state.confirmLabel) }
                            },
                            dismissButton = {
                                TextButton(onClick = state.onSkip) { Text("Skip") }
                            }
                        )
                    }

                    Scaffold(
                        topBar = {
                            val (title, navBack) = when (val v = view) {
                                MainView.Config -> "QuicLoc" to null
                                MainView.History -> "Request History" to { view = MainView.Config }
                                MainView.Diagnostics -> "Diagnostics" to { view = MainView.Config }
                                MainView.TutorialsHub -> "Tutorials" to { view = MainView.Config }
                                is MainView.TutorialDetail -> {
                                    val t = Tutorials.byId(v.tutorialId)
                                    val onBack: () -> Unit = {
                                        if (v.fromOnboarding) {
                                            whitelistManager.setOnboardingCompleted(true)
                                            view = MainView.Config
                                        } else {
                                            view = MainView.TutorialsHub
                                        }
                                    }
                                    (t?.title ?: "Tutorial") to onBack
                                }
                            }
                            TopAppBar(
                                title = { Text(title) },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                navigationIcon = {
                                    if (navBack != null) {
                                        IconButton(onClick = navBack) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    if (view is MainView.Config) {
                                        IconButton(onClick = { view = MainView.TutorialsHub }) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = "Tutorials",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        IconButton(onClick = { view = MainView.History }) {
                                            Icon(
                                                imageVector = Icons.Default.List,
                                                contentDescription = "View history",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        IconButton(onClick = { view = MainView.Diagnostics }) {
                                            Icon(
                                                imageVector = Icons.Default.Build,
                                                contentDescription = "Diagnostics",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        when (val v = view) {
                            MainView.History -> HistoryScreen(
                                modifier = Modifier.padding(innerPadding),
                                historyManager = RequestHistoryManager(this@MainActivity)
                            )
                            MainView.Diagnostics -> DiagnosticsScreen(
                                modifier = Modifier.padding(innerPadding),
                                notificationAccessGranted = notificationAccessGranted,
                                appEnabled = enabled,
                                whitelistCount = numbersList.size,
                                initialCaptureAll = AppSettings.isDiagCaptureAll(this@MainActivity),
                                onToggleCaptureAll = {
                                    AppSettings.setDiagCaptureAll(this@MainActivity, it)
                                },
                                onRequestNotificationAccess = { checkNotificationListenerPermission() },
                            )
                            MainView.TutorialsHub -> TutorialsHubScreen(
                                modifier = Modifier.padding(innerPadding),
                                tutorials = Tutorials.all,
                                onTutorialClick = { t ->
                                    view = MainView.TutorialDetail(t.id)
                                }
                            )
                            is MainView.TutorialDetail -> {
                                val tutorial = Tutorials.byId(v.tutorialId)
                                if (tutorial == null) {
                                    view = MainView.TutorialsHub
                                } else {
                                    TutorialDetailScreen(
                                        modifier = Modifier.padding(innerPadding),
                                        tutorial = tutorial,
                                        confirmLabel = if (v.fromOnboarding) "Got it" else "Done",
                                        onConfirm = {
                                            if (v.fromOnboarding) {
                                                whitelistManager.setOnboardingCompleted(true)
                                                view = MainView.Config
                                            } else {
                                                view = MainView.TutorialsHub
                                            }
                                        },
                                        onBrowseAll = if (v.fromOnboarding) {
                                            {
                                                whitelistManager.setOnboardingCompleted(true)
                                                view = MainView.TutorialsHub
                                            }
                                        } else null
                                    )
                                }
                            }
                            MainView.Config -> {
                            QuicLocScreen(
                                modifier = Modifier.padding(innerPadding),
                                numbersList = numbersList,
                                starredSet = starredSet,
                                myNumber = myNumber,
                                notificationAccessGranted = notificationAccessGranted,
                                noLockScreenWarning = !BiometricHelper.canAuthenticate(this@MainActivity),
                                enabled = enabled,
                                reminderNotificationEnabled = reminderEnabled,
                                deviceAdminGranted = deviceAdminGranted,
                                fullScreenIntentGranted = fullScreenIntentGranted,
                                onRequestFullScreenIntent = { openFullScreenIntentSettings() },
                                permissionStatuses = permissionStatuses,
                                onPermissionAction = { key -> dispatchPermissionAction(key) },
                                onToggleEnabled = { newState ->
                                    AppSettings.setEnabled(this@MainActivity, newState)
                                    enabledState.value = newState
                                    ReminderNotification.refresh(this@MainActivity)
                                },
                                onToggleReminderNotification = { newState ->
                                    AppSettings.setReminderNotificationEnabled(this@MainActivity, newState)
                                    reminderNotifState.value = newState
                                    ReminderNotification.refresh(this@MainActivity)
                                },
                                onAutoDetectMyNumber = { autoDetectMyNumber() },
                                autoDetectMyNumberOnFirstShow = !AppSettings.wasPhoneHintAutoPrompted(this@MainActivity),
                                onRequestDeviceAdmin = { requestDeviceAdmin() },
                                backupAvailable = BackupVault.isAvailable(this@MainActivity),
                                onRequestExportBackup = { requestExport() },
                                onRequestImportBackup = { requestImport() },
                                onRequestNotificationAccess = {
                                    // Re-use the same rationale dialog so the
                                    // banner button takes the user through the
                                    // explanation before opening Settings.
                                    checkNotificationListenerPermission()
                                },
                                onAddNumber = { number ->
                                    if (number.isNotBlank()) {
                                        whitelistManager.addNumber(number)
                                        numbersState.value = whitelistManager.getNumbers().toList()
                                        Toast.makeText(this, "Added to whitelist", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRemoveNumber = { number ->
                                    whitelistManager.removeNumber(number)
                                    numbersState.value = whitelistManager.getNumbers().toList()
                                    starredState.value = whitelistManager.getStarredNumbers()
                                },
                                onPickContact = { launchContactPicker() },
                                onToggleStar = { number ->
                                    val success = whitelistManager.toggleStarred(number)
                                    if (!success) {
                                        Toast.makeText(this@MainActivity, "You can only star up to 3 contacts.", Toast.LENGTH_SHORT).show()
                                    }
                                    starredState.value = whitelistManager.getStarredNumbers()
                                },
                                                                onMyNumberChanged = { number ->
                                    whitelistManager.setMyNumber(number)
                                    myNumberState.value = whitelistManager.getMyNumber()
                                },
                                currentPassphrase = currentPassphrase,
                                currentPin = currentPin,
                                onSavePassphrase = { newPassphrase, newPin ->
                                    whitelistManager.setPassphrase(newPassphrase)
                                    whitelistManager.setPin(newPin)
                                    currentPassphrase = newPassphrase
                                    currentPin = newPin

                                    // Find-my-phone is now armed. The whole lockdown
                                    // feature (tracking service, lock screen, Device
                                    // Admin receiver, intruder camera) lives in the
                                    // on-demand :feature_findmyphone module, so none of
                                    // its permissions/components exist until we download
                                    // it. Grant the core trigger permissions, then install
                                    // the module, then — once its manifest is merged in —
                                    // request CAMERA and prompt for Device Admin (neither
                                    // is requestable before the split is part of the app).
                                    val needed = listOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.SEND_SMS,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ).filter {
                                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                                    }
                                    runPermissionChain(needed) {
                                        FindMyPhone.requestInstall(applicationContext) {
                                            runOnUiThread {
                                                // The download is async — the activity may be
                                                // gone by now; touching a dialog/window then
                                                // would crash (BadTokenException).
                                                if (isFinishing || isDestroyed) return@runOnUiThread
                                                // CAMERA is now requestable (module manifest
                                                // merged in); the photo needs it granted before
                                                // any lock event, and it can't be requested over
                                                // the keyguard.
                                                val cameraChain =
                                                    if (ContextCompat.checkSelfPermission(
                                                            this@MainActivity, Manifest.permission.CAMERA
                                                        ) != PackageManager.PERMISSION_GRANTED
                                                    ) listOf(Manifest.permission.CAMERA) else emptyList()
                                                runPermissionChain(cameraChain) {
                                                    // Device Admin lives in the module too — only
                                                    // grantable now that its receiver is in the
                                                    // merged manifest.
                                                    if (!FindMyPhone.isAdminActive(this@MainActivity)) {
                                                        showDeviceAdminRationale()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Set to true right before we launch a system UI we expect to round-trip
     * through (permission dialog, settings screen, contact picker, biometric
     * prompt for grant, etc.). While set, onPause/onResume skip the re-lock
     * and re-auth — otherwise every system dialog would trap the user in a
     * fingerprint loop on return. Cleared on the next onResume.
     */
    private var expectingActivityReturn = false

    /** Call immediately before launching any system UI we expect to come back from. */
    private fun beginSystemFlow() {
        expectingActivityReturn = true
    }

    override fun onResume() {
        super.onResume()
        if (expectingActivityReturn) {
            // We just got back from a system UI we launched on purpose.
            // Stay authenticated and don't re-trigger the permission chain.
            expectingActivityReturn = false
        } else if (!isAuthenticated) {
            promptBiometric()
        }
        // User may have toggled state from the reminder notification or
        // granted/revoked device admin while we were paused.
        enabledState.value = AppSettings.isEnabled(this)
        reminderNotifState.value = AppSettings.isReminderNotificationEnabled(this)
        deviceAdminState.value = FindMyPhone.isAdminActive(this)
        fullScreenIntentState.value = canUseFullScreenIntent()
        refreshPermissionStatuses()
    }

    override fun onPause() {
        super.onPause()
        // Don't re-lock if we're round-tripping through a system UI we
        // launched intentionally — otherwise the user has to re-authenticate
        // after every permission dialog.
        if (expectingActivityReturn) return
        isAuthenticated = false
        authState.value = false
    }

    // -------------------------------------------------------------------------
    // Biometric prompt
    // -------------------------------------------------------------------------

    private fun promptBiometric() {
        BiometricHelper.authenticate(
            activity = this,
            onSuccess = {
                isAuthenticated = true
                authState.value = true
                checkPermissions()
            },
            onFailure = { reason ->
                isAuthenticated = false
                authState.value = false
                // User cancelled or failed — show the lock screen UI with retry
                Toast.makeText(this, "Authentication required: $reason", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // -------------------------------------------------------------------------
    // Contact picker
    // -------------------------------------------------------------------------

    private fun launchContactPicker() {
        // Either we already have READ_CONTACTS, or we route through the
        // rationale dialog first. The picker is launched whether the user
        // grants or skips — without the permission we still get the display
        // name back, just not the phone numbers.
        promptRuntimePermission(Manifest.permission.READ_CONTACTS) { _ ->
            beginSystemFlow()
            contactPickerLauncher.launch(null)
        }
    }

    private fun handleContactPicked(uri: Uri) {
        val contactProjection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        contentResolver.query(uri, contactProjection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
            val name = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            ) ?: return

            // Always store the display name — this is what notification-based apps send
            whitelistManager.addNumber(name)

            // Also store phone numbers for SMS matching
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id),
                    null
                )?.use { phoneCursor ->
                    while (phoneCursor.moveToNext()) {
                        val number = phoneCursor.getString(
                            phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        )
                        if (!number.isNullOrBlank()) whitelistManager.addNumber(number)
                    }
                }
            }

            numbersState.value = whitelistManager.getNumbers().toList()
            Toast.makeText(this, "Added $name to whitelist", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    /**
     * Walks the user through every still-missing permission, one rationale
     * dialog → one system dialog at a time, in the order defined by
     * [FIRST_LAUNCH_FLOW]. Then checks background location, then notification
     * access. Each step is independently skippable.
     */
    private fun checkPermissions() {
        val missing = FIRST_LAUNCH_FLOW.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        runPermissionChain(missing) {
            // Warn if the user skipped any critical perm — but don't block the chain.
            val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
            val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasSms || !hasLocation) {
                Toast.makeText(
                    this,
                    "SMS and Location are required for QuicLoc to answer requests.",
                    Toast.LENGTH_LONG
                ).show()
            }
            checkBackgroundLocationPermission()
        }
    }

    /**
     * Background location is requested *after* foreground location is granted,
     * via its own rationale dialog. Android 11+ sends the user to the system
     * Location Settings screen to flip "Allow all the time" — the launcher
     * can't ask directly. Returns to [checkNotificationListenerPermission]
     * once the result comes back, whether granted or not.
     */
    private fun checkBackgroundLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            checkNotificationListenerPermission()
            return
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            checkNotificationListenerPermission()
            return
        }
        pendingRationaleState.value = RationaleDialogState(
            title = "Background Location",
            body = "QuicLoc's whole point is to respond while the screen is off and the app is closed. " +
                "Android won't let us ask for this directly — the next screen is the system Location Settings page. " +
                "Please tap \"Allow all the time\" there. Without this, QuicLoc only works while you happen to have the app open.",
            confirmLabel = "Open Settings",
            onContinue = {
                pendingRationaleState.value = null
                afterPermissionResult = { granted ->
                    if (!granted) {
                        Toast.makeText(
                            this,
                            "Background location not granted — QuicLoc can't answer when the app is closed.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    checkNotificationListenerPermission()
                }
                beginSystemFlow()
                singlePermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
            onSkip = {
                pendingRationaleState.value = null
                checkNotificationListenerPermission()
            }
        )
    }

    /**
     * Notification Access is special-access (no runtime API), so the
     * rationale dialog hands the user off to system Settings via
     * ACTION_NOTIFICATION_LISTENER_SETTINGS. The result isn't observable
     * directly — we re-check in onResume. Chains to the Full-Screen Intent
     * check after either branch.
     */
    private fun checkNotificationListenerPermission() {
        if (isNotificationListenerEnabled()) {
            checkFullScreenIntentPermission()
            return
        }
        pendingRationaleState.value = RationaleDialogState(
            title = "Notification Access",
            body = "Extends the trigger to WhatsApp, Telegram, Signal, and other messaging apps. " +
                "QuicLoc only reads the title and body of incoming notifications, and only acts on whitelisted senders " +
                "sending the exact trigger word. The next screen is the system Notification Access settings page — " +
                "toggle QuicLoc on. Skip this and only SMS triggers will work.",
            confirmLabel = "Open Settings",
            onContinue = {
                pendingRationaleState.value = null
                beginSystemFlow()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                // Queue the next dialog so it surfaces on return from Settings.
                checkFullScreenIntentPermission()
            },
            onSkip = {
                pendingRationaleState.value = null
                checkFullScreenIntentPermission()
            }
        )
    }

    /**
     * On Android 14+ apps must obtain runtime grant for USE_FULL_SCREEN_INTENT
     * via Settings → Apps → QuicLoc → Notifications → "Full screen notifications".
     * QuicLoc needs this so the find-my-phone cover-screen fallback can come
     * over the lock screen when Device Admin isn't granted. On older Android,
     * the manifest declaration is sufficient and this check no-ops.
     *
     * `wasFullScreenIntentPrompted` ensures we don't auto-nag every launch —
     * the in-UI card stays available for the user to come back to.
     */
    private fun checkFullScreenIntentPermission(forceShow: Boolean = false) {
        // USE_FULL_SCREEN_INTENT ships only in the find-my-phone module; when
        // that feature is disabled the permission isn't declared, so don't prompt
        // for (or route the user to a Settings toggle for) a permission the app
        // doesn't have. This also covers the first-launch onboarding chain
        // (checkNotificationListenerPermission -> checkFullScreenIntentPermission).
        if (!FindMyPhone.ENABLED) return
        if (canUseFullScreenIntent()) return
        if (!forceShow && AppSettings.wasFullScreenIntentPrompted(this)) return
        pendingRationaleState.value = RationaleDialogState(
            title = "Full Screen Notifications",
            body = "Required only for the find-my-phone fallback. If a trigger fires while " +
                "Device Admin isn't granted, QuicLoc covers the screen with a lock activity " +
                "instead — but Android 14+ won't let that activity come over the lock screen " +
                "without this permission. The next page is the system Settings screen for " +
                "QuicLoc's notifications — toggle \"Allow full screen notifications\" on. " +
                "Skip if Device Admin is granted; you won't need the fallback.",
            confirmLabel = "Open Settings",
            onContinue = {
                pendingRationaleState.value = null
                AppSettings.markFullScreenIntentPrompted(this)
                openFullScreenIntentSettings()
            },
            onSkip = {
                pendingRationaleState.value = null
                AppSettings.markFullScreenIntentPrompted(this)
            }
        )
    }

    /** True if the device doesn't need the runtime grant, or it's already granted. */
    private fun canUseFullScreenIntent(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 34) return true
        val nm = getSystemService(android.app.NotificationManager::class.java)
        return nm?.canUseFullScreenIntent() ?: true
    }

    private fun openFullScreenIntentSettings() {
        beginSystemFlow()
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:$packageName")
            )
        } else {
            // Fallback — shouldn't be reachable since the card is hidden pre-34.
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not open Full Screen Notifications settings on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                FindMyPhone.adminComponent()
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description)
            )
        }
        AppSettings.markDeviceAdminPrompted(this)
        try {
            beginSystemFlow()
            deviceAdminLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not open Device Admin settings on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && packageName == cn.packageName) return true
            }
        }
        return false
    }
}

// -------------------------------------------------------------------------
// Lock screen shown before auth passes
// -------------------------------------------------------------------------

/**
 * Placeholder screen shown after [MainActivity.onCreate] but before
 * [BiometricHelper.authenticate] returns success. If the user cancels or
 * the biometric callback fires `onAuthenticationError`, [onRetry] re-fires
 * the prompt.
 */
@Composable
fun BiometricGateScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "🔒",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "QuicLoc",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Authentication required",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Unlock")
            }
        }
    }
}

// -------------------------------------------------------------------------
// Main UI
// -------------------------------------------------------------------------

/**
 * The settings / configuration screen — the bulk of the visible UI.
 *
 * Top-to-bottom layout (chosen deliberately for first-run flow):
 *
 *   1. Master enable/disable toggle (always at the very top).
 *   2. "Show reminder notification" opt-in switch.
 *   3. "Your Phone Number" card — set first; auto-detect via Phone Number
 *      Hint on first show; locks itself once a number is saved.
 *   4. "No lock screen" warning (only if the device has no PIN/pattern).
 *   5. Notification access banner (red if not granted).
 *   6. Passphrase + PIN section.
 *   7. Device Admin card — "Grant" affordance with a pre-prompt dialog.
 *   8. Backup & Restore card — manual export / import buttons.
 *   9. Widget tap guide + add-contact / pick-contact controls.
 *   10. Whitelisted contacts list with star and delete actions.
 *
 * The whole screen is in a [verticalScroll] so the user can reach every
 * contact (no nested `LazyColumn` collapse).
 */
@Composable
fun QuicLocScreen(
    modifier: Modifier = Modifier,
    numbersList: List<String>,
    starredSet: Set<String>,
    myNumber: String,
    notificationAccessGranted: Boolean,
    noLockScreenWarning: Boolean,
    enabled: Boolean,
    reminderNotificationEnabled: Boolean,
    deviceAdminGranted: Boolean,
    fullScreenIntentGranted: Boolean,
    onRequestFullScreenIntent: () -> Unit,
    permissionStatuses: List<MainActivity.PermissionStatus>,
    onPermissionAction: (key: String) -> Unit,
    autoDetectMyNumberOnFirstShow: Boolean,
    backupAvailable: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleReminderNotification: (Boolean) -> Unit,
    onAutoDetectMyNumber: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onRequestExportBackup: () -> Unit,
    onRequestImportBackup: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onAddNumber: (String) -> Unit,
    onRemoveNumber: (String) -> Unit,
    onPickContact: () -> Unit,
    currentPassphrase: String = "",
    currentPin: String = "",
    onSavePassphrase: (String, String) -> Unit = { _, _ -> },
    onToggleStar: (String) -> Unit = {},
    onMyNumberChanged: (String) -> Unit = {}
) {
    var phoneNumberInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {
        // Top-of-settings master toggle. Mirrors the reminder notification.
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (enabled)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (enabled) "QuicLoc is enabled" else "QuicLoc is disabled",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (enabled)
                            "Listening for SMS and notification triggers."
                        else
                            "All triggers are paused. Widget and tracking are off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled
                )
            }
        }

        // Opt-in: show a persistent notification that mirrors the master
        // toggle and lets the user flip it without opening the app.
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show reminder notification",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Persistent notification with a one-tap enable/disable button.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = reminderNotificationEnabled,
                    onCheckedChange = onToggleReminderNotification
                )
            }
        }

        Text(
            text = "QuicLoc Configuration",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Your own phone number — used by the 2-tap widget #Parking shortcut.
        // Lives high in the screen and locks once set so it can't be confused
        // with the whitelist input field further down. On first show with an
        // empty number, the Phone Number Hint sheet is auto-triggered so the
        // user can pick it from the device with one tap.
        var hasInteractedWithMyNumber by remember { mutableStateOf(false) }
        val myNumberLocked = myNumber.isNotEmpty() && !hasInteractedWithMyNumber
        LaunchedEffect(Unit) {
            if (autoDetectMyNumberOnFirstShow && myNumber.isEmpty()) {
                onAutoDetectMyNumber()
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Your Phone Number",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Where the 2-tap widget sends your location as a parking reminder. This is YOUR own number — not a whitelisted contact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = myNumber,
                        onValueChange = {
                            hasInteractedWithMyNumber = true
                            onMyNumberChanged(it)
                        },
                        label = { Text("Your number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        enabled = !myNumberLocked,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (myNumberLocked) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { hasInteractedWithMyNumber = true }) {
                            Text("Change")
                        }
                    }
                }
                if (!myNumberLocked) {
                    TextButton(
                        onClick = onAutoDetectMyNumber,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text("Auto-detect from this device")
                    }
                }
            }
        }

        // Warn if device has no lock screen (biometrics were bypassed)
        if (noLockScreenWarning) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "⚠ No lock screen set up. Set a PIN, pattern, or fingerprint in system settings to protect this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Notification access banner
        if (!notificationAccessGranted) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠ Notification Access Required",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "To respond in WhatsApp, Telegram, Signal, and other apps, QuicLoc needs Notification Access permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Button(
                        onClick = onRequestNotificationAccess,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Grant Notification Access")
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = "✓ Notification Access granted — QuicLoc will respond in all messaging apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }


        // Find-my-phone / lockdown setup UI (passphrase + PIN, Device Admin
        // opt-in, full-screen-intent grant). Hidden while the feature is
        // disabled (FindMyPhone.ENABLED == false); the block is kept intact so
        // re-enabling the feature is a one-line flip. Inner indentation is left
        // as-is to keep the disable diff minimal.
        if (FindMyPhone.ENABLED) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Single-Use Tracking Passphrase",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        var passphraseInput by remember(currentPassphrase) { mutableStateOf(currentPassphrase) }
        var pinInput by remember(currentPin) { mutableStateOf(currentPin) }

        OutlinedTextField(
            value = passphraseInput,
            onValueChange = { if (it.length <= 150) passphraseInput = it },
            label = { Text("Text 'loc' + this from ANY number to activate find my phone.") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            singleLine = false,
            minLines = 3
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = pinInput,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
            label = { Text("6-Digit PIN: Required to unlock your phone once find my phone is active.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onSavePassphrase(passphraseInput, pinInput) },
            enabled = passphraseInput.length in 10..150 && pinInput.length == 6 && pinInput.all { it.isDigit() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Passphrase & PIN")
        }

        // Real-lockdown opt-in. Without Device Admin, the lock screen
        // can only *cover* the display — the device isn't actually locked.
        Spacer(modifier = Modifier.height(12.dp))
        if (deviceAdminGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Real lockdown enabled — QuicLoc can lock the device when the passphrase fires.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        } else {
            var showAdminExplanation by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠ Real lockdown not enabled",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Without Device Admin, the passphrase only covers the screen — it can't actually lock the device. Grant Device Admin to enable true lockdown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Button(
                        onClick = { showAdminExplanation = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Grant Device Admin")
                    }
                }
            }

            if (showAdminExplanation) {
                AlertDialog(
                    onDismissRequest = { showAdminExplanation = false },
                    title = { Text(stringResource(R.string.device_admin_explanation_title)) },
                    text = {
                        Text(
                            text = stringResource(R.string.device_admin_explanation_body),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            showAdminExplanation = false
                            onRequestDeviceAdmin()
                        }) {
                            Text("Continue")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAdminExplanation = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        // Full-Screen Intent (Android 14+). Only relevant when the device
        // is on API 34+ AND the runtime grant is missing. The cover-screen
        // fallback (TrackingLockActivity) needs this to come over the lock
        // screen when Device Admin isn't granted; otherwise it's optional.
        if (android.os.Build.VERSION.SDK_INT >= 34 && !fullScreenIntentGranted) {
            var showFsiExplanation by remember { mutableStateOf(false) }
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠ Full Screen Notifications not enabled",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Without this, the find-my-phone cover-screen fallback can't come over the lock screen on Android 14+. Only matters when Device Admin is not granted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Button(
                        onClick = { showFsiExplanation = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Grant Full Screen Notifications")
                    }
                }
            }
            if (showFsiExplanation) {
                AlertDialog(
                    onDismissRequest = { showFsiExplanation = false },
                    title = { Text("Grant Full Screen Notifications?") },
                    text = {
                        Text(
                            text = "Required only for the find-my-phone fallback. If a trigger " +
                                "fires while Device Admin isn't granted, QuicLoc covers the screen " +
                                "with a lock activity instead — but Android 14+ won't let that " +
                                "activity come over the lock screen without this permission.\n\n" +
                                "The next page is the system Settings screen for QuicLoc's " +
                                "notifications — toggle \"Allow full screen notifications\" on. " +
                                "Skip if Device Admin is granted; you won't need the fallback.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            showFsiExplanation = false
                            onRequestFullScreenIntent()
                        }) {
                            Text("Continue")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showFsiExplanation = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
        } // end if (FindMyPhone.ENABLED) — find-my-phone setup UI

        // All-permissions overview. Lists every <uses-permission> in the
        // manifest plus the "Protected" background-reliability toggles,
        // grouped by category with live grant status. Each row has its own
        // Grant / Manage / Open button.
        Spacer(modifier = Modifier.height(12.dp))
        var permissionsExpanded by remember { mutableStateOf(false) }
        val grantableCount = permissionStatuses.count { it.state != MainActivity.PermStatus.AUTO_GRANTED }
        val grantedCount = permissionStatuses.count {
            it.state == MainActivity.PermStatus.GRANTED || it.state == MainActivity.PermStatus.AUTO_GRANTED
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { permissionsExpanded = !permissionsExpanded },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "All Permissions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$grantedCount of ${permissionStatuses.size} granted — tap to ${if (permissionsExpanded) "collapse" else "expand"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (permissionsExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // Android 13+ blocks special-access permissions for
                    // sideloaded apps until the user explicitly unlocks them
                    // from the app's system settings page. This note appears
                    // for everyone — Play Store installs can ignore it.
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Toggle greyed out in system Settings?",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "On Android 13+, sideloaded apps need you to unlock " +
                                    "restricted permissions before the toggles work. In QuicLoc's " +
                                    "system Settings page (the one a Grant or Manage button opens), " +
                                    "tap the ⋮ menu in the top-right corner and choose \"Allow " +
                                    "restricted settings\" (called \"Allow protected settings\" on " +
                                    "some devices). Then come back and try Grant again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    val grouped = permissionStatuses.groupBy { it.category }
                    listOf("Runtime", "Special access", "Protected", "Install-time").forEach { cat ->
                        val rows = grouped[cat] ?: return@forEach
                        Text(
                            text = cat,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        rows.forEach { status ->
                            val iconText: String
                            val iconColor: androidx.compose.ui.graphics.Color
                            val buttonLabel: String?
                            when (status.state) {
                                MainActivity.PermStatus.GRANTED -> {
                                    iconText = "✓"
                                    iconColor = MaterialTheme.colorScheme.primary
                                    buttonLabel = "Manage"
                                }
                                MainActivity.PermStatus.AUTO_GRANTED -> {
                                    iconText = "✓"
                                    iconColor = MaterialTheme.colorScheme.primary
                                    buttonLabel = null
                                }
                                MainActivity.PermStatus.NOT_GRANTED -> {
                                    iconText = "✗"
                                    iconColor = MaterialTheme.colorScheme.error
                                    buttonLabel = "Grant"
                                }
                                MainActivity.PermStatus.UNKNOWN -> {
                                    iconText = "?"
                                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    buttonLabel = "Open"
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = iconText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = iconColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = status.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (buttonLabel != null) {
                                    TextButton(onClick = { onPermissionAction(status.key) }) {
                                        Text(buttonLabel)
                                    }
                                } else {
                                    Text(
                                        text = "Auto-granted",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$grantableCount permissions need your action ($grantedCount currently granted, including install-time).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Backup & restore — PIN-encrypted blob. The Auto Backup path
        // (cloud + device-transfer) is automatic when a PIN is set; these
        // buttons cover the manual side: export anywhere via SAF, or
        // import from any file picker location.
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Backup & Restore",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (backupAvailable)
                        "Backup is active. Your settings are PIN-encrypted and automatically included in Android's cloud backup. You can also export a copy to any file location."
                    else
                        "Backup activates once you set a PIN. Your settings will then be PIN-encrypted and included in Android's cloud backup automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                Row {
                    OutlinedButton(
                        onClick = onRequestExportBackup,
                        enabled = backupAvailable,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onRequestImportBackup,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import")
                    }
                }
            }
        }

        // Whitelist a contact section
        Text(
            text = "Contacts & Widget",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Widget Tap Guide:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "• 2 Taps: Save Parking (Sends to your number)\n" +
                           "• 3 Taps: Safety Check (Sends to starred contacts)\n" +
                           "• 4 Taps: Emergency (Sends to all whitelisted)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Button(
            onClick = onPickContact,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text("Pick from Contacts")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = phoneNumberInput,
                onValueChange = { phoneNumberInput = it },
                label = { Text("Type name / number: Must match your contact list for SMS/Messenger replies.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                onAddNumber(phoneNumberInput)
                phoneNumberInput = ""
            }) {
                Text("Add")
            }
        }

        Text(
            text = "Whitelisted (${numbersList.size}):",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
        )

        if (numbersList.isEmpty()) {
            Text(
                text = "No contacts yet. Add a number above or pick from contacts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            // Rendered inline (not LazyColumn) so the parent verticalScroll
            // can scroll the entire page and reveal every contact. The list
            // is small (handfuls of trusted contacts), so non-virtualized
            // rendering is fine.
            numbersList.forEach { number ->
                val isStarred = starredSet.contains(number)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onToggleStar(number) }) {
                        Icon(
                            imageVector = if (isStarred) Icons.Default.Star else Icons.Outlined.Star,
                            contentDescription = if (isStarred) "Unstar" else "Star",
                            tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onRemoveNumber(number) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove"
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Tutorials
// -------------------------------------------------------------------------

/**
 * Browsable list of all tutorials. The "Why QuicLoc?" tutorial is rendered
 * with the primary color so it stands out — it's the recommended starting
 * point.
 */
@Composable
fun TutorialsHubScreen(
    modifier: Modifier = Modifier,
    tutorials: List<Tutorial>,
    onTutorialClick: (Tutorial) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Pick a tutorial",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Start with \"Why QuicLoc?\" if you're new — it explains the on-demand model. The rest cover individual features.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        tutorials.forEach { tutorial ->
            val isMain = tutorial.id == Tutorials.MAIN_ID
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onTutorialClick(tutorial) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isMain)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = tutorial.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isMain) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isMain)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = tutorial.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isMain)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Full text of a single tutorial in a scrollable column, with a sticky
 * confirm button at the bottom.
 *
 * @param confirmLabel "Got it" during onboarding, "Done" otherwise.
 * @param onBrowseAll Optional secondary action shown only on the
 *   first-launch tutorial — taps mark onboarding complete *and* jump to
 *   the tutorials hub so the user can read more if they want.
 */
@Composable
fun TutorialDetailScreen(
    modifier: Modifier = Modifier,
    tutorial: Tutorial,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onBrowseAll: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = tutorial.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                text = tutorial.body,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (onBrowseAll != null) {
            TextButton(
                onClick = onBrowseAll,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Browse all tutorials")
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(confirmLabel)
        }
    }
}

// -------------------------------------------------------------------------
// Backup restore dialog
// -------------------------------------------------------------------------

/**
 * Modal dialog used for both the launch-time restore prompt and the
 * post-import prompt. The supplied [onRestore] runs the actual decryption
 * + apply on the caller's side and returns a Result.
 *
 * Error UX is category-aware: a wrong-PIN failure keeps the dialog ready
 * for another attempt with a different PIN, while any file-level error
 * (truncated, unsupported version, parse, apply, IO) disables the Restore
 * button — retrying with another PIN can't fix those.
 */
@Composable
fun RestoreFromBackupDialog(
    title: String,
    body: String,
    onRestore: (String) -> Result<BackupVault.RestoreSummary>,
    onSkip: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorCategory by remember { mutableStateOf<BackupVault.RestoreException.Category?>(null) }
    var working by remember { mutableStateOf(false) }

    val isUnrecoverable = errorCategory != null &&
        errorCategory != BackupVault.RestoreException.Category.WRONG_PIN

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        // 6-digit PIN, but allow longer for forward-compatibility.
                        pin = it.filter(Char::isDigit).take(20)
                        // Editing the PIN only clears recoverable (WRONG_PIN)
                        // errors. File-level errors stay surfaced because they
                        // aren't going to be fixed by a different PIN.
                        if (errorCategory == BackupVault.RestoreException.Category.WRONG_PIN) {
                            errorMessage = null
                            errorCategory = null
                        }
                    },
                    label = { Text("Previous PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = errorMessage != null,
                    enabled = !isUnrecoverable,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    working = true
                    val result = onRestore(pin)
                    working = false
                    if (result.isFailure) {
                        val ex = result.exceptionOrNull()
                        errorMessage = ex?.message ?: "Restore failed."
                        errorCategory = (ex as? BackupVault.RestoreException)?.category
                    }
                },
                enabled = pin.length >= 4 && !working && !isUnrecoverable
            ) {
                Text(if (working) "Restoring…" else "Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(if (isUnrecoverable) "Close" else "Skip")
            }
        }
    )
}

// -------------------------------------------------------------------------
// Diagnostics screen
// -------------------------------------------------------------------------

/**
 * Debugging screen that answers "I messaged 'loc' — what did the app do with
 * it?". Shows the readiness checks first (Notification Access is the big one —
 * without it the whole chat-app path is dead), then a newest-first log of every
 * `loc` message and the exact decision QuicLoc reached, in plain English.
 *
 * Sourced from [DiagnosticLogManager]. The capture-all switch flips
 * [AppSettings.setDiagCaptureAll] so the listener logs *every* notification —
 * useful to catch an app whose message text is read out wrongly.
 */
@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    notificationAccessGranted: Boolean,
    appEnabled: Boolean,
    whitelistCount: Int,
    initialCaptureAll: Boolean,
    onToggleCaptureAll: (Boolean) -> Unit,
    onRequestNotificationAccess: () -> Unit,
) {
    val context = LocalContext.current
    // Built once (Keystore init is expensive) and kept across recompositions.
    val diagManager = remember { DiagnosticLogManager(context) }
    // Load + decrypt off the composition thread so navigating here never janks.
    var events by remember { mutableStateOf<List<DiagnosticEvent>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(diagManager) {
        events = withContext(Dispatchers.IO) { diagManager.getEvents() }
        loaded = true
    }
    var captureAll by remember { mutableStateOf(initialCaptureAll) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // --- Readiness status card ---
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(6.dp))
                StatusRow("Notification Access", notificationAccessGranted)
                if (!notificationAccessGranted) {
                    Text(
                        text = "Without this, QuicLoc can't see WhatsApp / Messenger / " +
                            "Google Voice messages at all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    TextButton(onClick = onRequestNotificationAccess) { Text("Grant access") }
                }
                StatusRow("QuicLoc enabled", appEnabled)
                StatusRow("Whitelist contacts", whitelistCount > 0, "$whitelistCount")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Chat apps match the contact NAME shown in the notification, " +
                        "not the phone number. If a sender shows as a name that isn't in " +
                        "your whitelist, the request is ignored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // --- Capture-all toggle ---
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Capture all notifications", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Logs every notification QuicLoc sees (noisy). Turn on " +
                            "briefly to debug one app, then off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = captureAll,
                    onCheckedChange = {
                        captureAll = it
                        onToggleCaptureAll(it)
                    }
                )
            }
        }

        // --- Actions ---
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            TextButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, diagManager.exportAsText())
                    }
                    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                }
            ) { Text("Copy / Share") }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    diagManager.clear()
                    events = emptyList()
                }
            ) { Text("Clear") }
        }

        // --- Event list ---
        if (events.isEmpty()) {
            if (loaded) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No 'loc' messages logged yet.\nSend yourself 'loc' to test.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(events) { event ->
                    val container = when (event.outcome) {
                        DiagOutcome.REPLY_SENT -> MaterialTheme.colorScheme.surfaceVariant
                        DiagOutcome.PASSPHRASE_TRIGGER,
                        DiagOutcome.DISPATCHED -> MaterialTheme.colorScheme.secondaryContainer
                        DiagOutcome.EVALUATED_NO_ACTION,
                        DiagOutcome.NOT_A_TRIGGER,
                        DiagOutcome.DUPLICATE_SUPPRESSED,
                        DiagOutcome.DEDUPED -> MaterialTheme.colorScheme.surface
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = container)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                text = if (event.rawSender.isBlank()) "(unknown sender)"
                                    else event.rawSender,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "${event.source} · ${event.formattedTime}" +
                                    (event.extractionPath?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (event.rawBody.isNotBlank()) {
                                Text(
                                    text = "\"${event.rawBody}\"",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${event.outcome}: ${event.reason}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A label + ✓/✗ status line for the Diagnostics status card. */
@Composable
private fun StatusRow(label: String, ok: Boolean, value: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value ?: if (ok) "✓" else "✗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

// -------------------------------------------------------------------------
// History screen
// -------------------------------------------------------------------------

/**
 * Newest-first log of every location request the app has handled.
 * Successful requests render on `surfaceVariant`; failures (location
 * timeout, missing reply action, etc.) render on `errorContainer` with a
 * `✗` marker so they stand out.
 *
 * Data source: [RequestHistoryManager.getHistory], read once on first
 * composition. Capped at 100 entries (oldest evicted on insert).
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    historyManager: RequestHistoryManager
) {
    val history = remember { historyManager.getHistory() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Request History Guide:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "This log shows all successful (✓) and failed (✗) location requests. You'll see who requested (Sender), which app was used (Source), and exactly when it happened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No location requests yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(history) { event ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (event.succeeded)
                                MaterialTheme.colorScheme.surfaceVariant
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = event.sender,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "${event.source} · ${event.formattedTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (event.succeeded) "✓" else "✗",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (event.succeeded)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
