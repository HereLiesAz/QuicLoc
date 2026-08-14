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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
     * Static explanation for each runtime permission QuicLoc may ask for.
     *
     * Deliberately split into four fixed slots rather than one blob of prose,
     * so every permission dialog answers the same four questions in the same
     * order: what it's for, what breaks without it, what QuicLoc will never
     * do with it, and what the user is about to see. Order in
     * [FIRST_LAUNCH_FLOW] determines the order they're shown in.
     */
    private data class PermissionRationale(
        val permission: String,
        val title: String,
        /** One line: what this permission lets QuicLoc do. */
        val whatFor: String,
        /** One line: exactly what stops working if it's denied. */
        val ifSkipped: String,
        /** What QuicLoc will never do with it. Null when there's nothing to reassure about. */
        val limits: String? = null,
    )

    private val RATIONALES: Map<String, PermissionRationale> = listOf(
        PermissionRationale(
            permission = Manifest.permission.RECEIVE_SMS,
            title = "Read incoming text messages",
            whatFor = "Lets QuicLoc notice when a trusted contact texts you the word \"loc\".",
            ifSkipped = "A request sent by text can never reach the app. Chat apps would still " +
                "work, if you grant Notification Access later.",
            limits = "QuicLoc looks at who sent the message and whether it's the trigger word. " +
                "Nothing else is read, stored, or sent anywhere.",
        ),
        PermissionRationale(
            permission = Manifest.permission.SEND_SMS,
            title = "Send text messages",
            whatFor = "Lets QuicLoc text the Google Maps link back to whoever asked.",
            ifSkipped = "QuicLoc can hear the request but has no way to answer it. The widget's " +
                "parking, safety-check and emergency shortcuts also stop working.",
            limits = "Only ever sends to the person who asked, or to the contacts you picked " +
                "for the widget. Never to us — there is no server.",
        ),
        PermissionRationale(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            title = "Precise location",
            whatFor = "Gets a GPS fix accurate enough to be worth putting on a map.",
            ifSkipped = "QuicLoc has nothing useful to send. This is the one permission the app " +
                "cannot work without.",
            limits = "Read only in the seconds it takes to answer a request, then discarded. " +
                "No background tracking, no history, no server.",
        ),
        PermissionRationale(
            permission = Manifest.permission.ACCESS_COARSE_LOCATION,
            title = "Approximate location",
            whatFor = "The fallback for when GPS can't get a fix — indoors, underground, no view " +
                "of the sky.",
            ifSkipped = "Requests answered from inside a building may time out with no reply.",
            limits = "Same as precise location: read while answering, then discarded.",
        ),
        PermissionRationale(
            permission = Manifest.permission.CAMERA,
            title = "Camera",
            whatFor = "Powers the find-my-phone intruder photo: three wrong PINs and the front " +
                "camera takes one frame and sends it to whoever triggered the passphrase.",
            ifSkipped = "Find-my-phone still locks and reports location — you just don't get a " +
                "photo of whoever has your phone.",
            limits = "One frame, only after three wrong PIN attempts during an active " +
                "find-my-phone session. Asked now because a lock screen can't show a " +
                "permission dialog at the moment it's needed.",
        ),
        PermissionRationale(
            permission = "android.permission.POST_NOTIFICATIONS",
            title = "Show notifications",
            whatFor = "Lets QuicLoc show the brief notification Android requires while a reply " +
                "is being sent, plus the optional always-on reminder.",
            ifSkipped = "Android kills the reply halfway through sending it. Requests will " +
                "silently go unanswered.",
            limits = "Nothing is pushed at you — these notifications only appear while QuicLoc " +
                "is actually doing something, unless you switch on the reminder yourself.",
        ),
        PermissionRationale(
            permission = Manifest.permission.READ_PHONE_NUMBERS,
            title = "Read this phone's own number",
            whatFor = "Fills in your own number automatically, so the widget's 2-tap parking " +
                "reminder knows where to text you.",
            ifSkipped = "Nothing breaks — you'll be offered a picker, or you can type your " +
                "number in by hand.",
            limits = "Reads the number of the SIM in this phone. Not your call log, not your " +
                "contacts, not who you've been talking to.",
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

    /** Whether unlock() has already auto-walked the permission chain this process lifetime. */
    private var hasRunPermissionChainThisSession = false

    /** Set when the launcher fires, called once the system dialog dismisses. */
    private var afterPermissionResult: (granted: Boolean) -> Unit = {}

    /** Which permission the most recent [singlePermissionLauncher] launch was for. */
    private var lastRequestedPermission: String? = null

    /** Records [lastRequestedPermission], then launches the request. */
    private fun launchPermissionRequest(permission: String) {
        lastRequestedPermission = permission
        singlePermissionLauncher.launch(permission)
    }

    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val next = afterPermissionResult
        afterPermissionResult = {}
        // shouldShowRequestPermissionRationale() only returns false once
        // Android has decided it will no longer show its own request dialog
        // for this permission ("Don't ask again", or enough plain denials on
        // some OEMs) -- checked here, right after a request, it reliably
        // detects "that dialog we just tried to show never actually
        // appeared". Without this, re-tapping "Grant" from the rationale
        // dialog silently no-ops forever with zero visible feedback.
        val permission = lastRequestedPermission
        if (!granted && permission != null && !shouldShowRequestPermissionRationale(permission)) {
            Toast.makeText(
                this,
                "Android won't ask again for this one — grant it from the app's Settings page instead.",
                Toast.LENGTH_LONG
            ).show()
        }
        next(granted)
    }

    /**
     * State of the rationale modal — null when no dialog is showing. Every
     * permission QuicLoc asks for goes through one of these first, so the
     * user never meets a bare system dialog with no context.
     *
     * The dialog is rendered in [setContent] alongside the other modals.
     */
    private data class RationaleDialogState(
        val title: String,
        /** "Step 2 of 5" during the first-launch chain; null for one-offs. */
        val progress: String? = null,
        val whatFor: String,
        val ifSkipped: String? = null,
        val limits: String? = null,
        /** What the user is about to see once they tap the confirm button. */
        val nextStep: String? = null,
        val confirmLabel: String,
        val skipLabel: String = "Not now",
        val onContinue: () -> Unit,
        val onSkip: () -> Unit,
    )

    private val pendingRationaleState = mutableStateOf<RationaleDialogState?>(null)

    /**
     * Show one rationale dialog, then on "Allow" launch the system grant
     * dialog for [permission]. On any result (grant or deny), invoke
     * [onAfter] so the caller can advance to the next step.
     *
     * @param progress optional "Step N of M" label for the first-launch chain,
     *   so the user can see how much is left rather than facing an
     *   open-ended stream of dialogs.
     */
    private fun promptRuntimePermission(
        permission: String,
        progress: String? = null,
        onAfter: (granted: Boolean) -> Unit,
    ) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            onAfter(true)
            return
        }
        val rationale = RATIONALES[permission] ?: run {
            // Unknown permission — fall back to launching directly with no rationale.
            afterPermissionResult = onAfter
            beginSystemFlow()
            launchPermissionRequest(permission)
            return
        }
        pendingRationaleState.value = RationaleDialogState(
            title = rationale.title,
            progress = progress,
            whatFor = rationale.whatFor,
            ifSkipped = rationale.ifSkipped,
            limits = rationale.limits,
            nextStep = "Next you'll see Android's own permission dialog. Tap Allow there too.",
            confirmLabel = "Allow",
            onContinue = {
                pendingRationaleState.value = null
                afterPermissionResult = onAfter
                beginSystemFlow()
                launchPermissionRequest(permission)
            },
            onSkip = {
                pendingRationaleState.value = null
                onAfter(false)
            }
        )
    }

    /**
     * Walk through [queue] sequentially, then invoke [onDone]. Each dialog is
     * labelled "Step N of M" against the original queue length so the chain
     * never feels open-ended.
     */
    private fun runPermissionChain(
        queue: List<String>,
        total: Int = queue.size,
        index: Int = 1,
        onDone: () -> Unit,
    ) {
        if (queue.isEmpty()) {
            onDone()
            return
        }
        val progress = if (total > 1) "Step $index of $total" else null
        promptRuntimePermission(queue.first(), progress) { _ ->
            runPermissionChain(queue.drop(1), total, index + 1, onDone)
        }
    }

    // Compose state — hoisted so biometric callback and launchers can update it
    private var authState = mutableStateOf(false)
    // Hoisted to the activity, not a remember{} inside the composable tree:
    // `view` used to live in a remember{} inside the `else` branch of
    // `if (!authenticated) {...} else {...}`. Compose tears down and rebuilds
    // that whole branch's remembered state every time `authenticated` flips
    // false -> true, which happens on every ordinary re-authentication (a ​
    // plain Home-button press backgrounds the activity and clears authState;
    // see onPause). The result was silently landing back on the default
    // screen after every unlock, discarding whatever screen the user was on.
    // Living here instead survives that.
    private var viewState = mutableStateOf<MainView>(MainView.Config)
    private var numbersState = mutableStateOf<List<String>>(emptyList())
    // How many whitelist entries actually have a dialable phone number, as
    // opposed to numbersState.size (which counts name-only entries too —
    // those can't ever satisfy an SMS trigger or the widget's SMS fan-out).
    // Kept in lockstep with numbersState everywhere it's refreshed.
    private var dialableCountState = mutableStateOf(0)
    private var starredState = mutableStateOf<Set<String>>(emptySet())
    private var myNumberState = mutableStateOf("")
    private var enabledState = mutableStateOf(true)
    private var reminderNotifState = mutableStateOf(false)
    private var deviceAdminState = mutableStateOf(false)
    private var fullScreenIntentState = mutableStateOf(true)
    private var permissionStatusesState = mutableStateOf<List<PermissionStatus>>(emptyList())
    private var backupAvailableState = mutableStateOf(false)

    /**
     * True while the unlock screen should offer the QuicLoc PIN keypad rather
     * than (or as well as) the biometric prompt. Set when the device has no
     * lock screen of its own — the app PIN is then the only gate — or when
     * the user picks "Use QuicLoc PIN instead".
     */
    private var pinGateState = mutableStateOf(false)

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
        list += runtime(Manifest.permission.READ_PHONE_NUMBERS, "Read Phone Number")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            list += runtime("android.permission.POST_NOTIFICATIONS", "Show Notifications")
        }

        // Special access — granted via system Settings
        list += special(PermKeys.NOTIF_LISTENER, "Notification Access", isNotificationListenerEnabled())
        // Device Admin and Full-Screen Notifications belong to the find-my-phone
        // module; hide their rows while that feature is disabled, since their
        // permissions are no longer declared in the shipped manifest.
        if (FindMyPhone.ENABLED) {
            list += special(PermKeys.DEVICE_ADMIN, "Device Admin", FindMyPhone.isAdminActive(this))
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                list += special(PermKeys.FSI, "Full Screen Notifications", canUseFullScreenIntent())
            }
        }

        // Protected — keep the app alive in the background
        list += PermissionStatus(
            key = PermKeys.BATTERY,
            label = "Battery Optimization Exemption",
            category = "Protected",
            state = if (isIgnoringBatteryOptimizations()) PermStatus.GRANTED else PermStatus.NOT_GRANTED,
        )
        list += PermissionStatus(
            key = PermKeys.AUTOSTART,
            label = "OEM Autostart",
            category = "Protected",
            // No public API to check — defer to the user to verify in OEM settings.
            state = PermStatus.UNKNOWN,
        )
        list += PermissionStatus(
            key = PermKeys.NOTIF_CHANNELS,
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

    /**
     * Write the PIN-encrypted backup blob now (or delete it, if the PIN was
     * just removed) and update the UI. Runs off the main thread — the snapshot
     * is PBKDF2-bound and takes ~100-200ms.
     */
    private fun refreshBackupSnapshot() {
        Thread {
            BackupVault.flush(applicationContext)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    backupAvailableState.value = BackupVault.isAvailable(this)
                }
            }
        }.start()
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
            PermKeys.NOTIF_LISTENER -> {
                if (isNotificationListenerEnabled()) {
                    openAppDetailsSettings()
                } else {
                    checkNotificationListenerPermission()
                }
            }
            PermKeys.DEVICE_ADMIN -> {
                if (FindMyPhone.isAdminActive(this)) {
                    openAppDetailsSettings()
                } else {
                    showDeviceAdminRationale()
                }
            }
            PermKeys.FSI -> {
                if (canUseFullScreenIntent()) {
                    openAppNotificationSettings()
                } else {
                    checkFullScreenIntentPermission(forceShow = true)
                }
            }
            PermKeys.BATTERY -> {
                if (isIgnoringBatteryOptimizations()) {
                    openBatteryOptimizationList()
                } else {
                    requestBatteryOptimizationExemption()
                }
            }
            PermKeys.AUTOSTART -> openOemAutostartSettings()
            PermKeys.NOTIF_CHANNELS -> openAppNotificationSettings()
            else -> openAppDetailsSettings()
        }
    }

    private fun showDeviceAdminRationale() {
        pendingRationaleState.value = RationaleDialogState(
            title = getString(R.string.device_admin_explanation_title),
            whatFor = "Lets the find-my-phone passphrase genuinely lock the device, using the " +
                "same call Android makes when you press the power button.",
            ifSkipped = "The passphrase can only cover the screen with a lock activity. A " +
                "determined thief can get past that with the notification shade or Recents.",
            limits = "Locking the screen is the only admin power QuicLoc ever uses. It cannot " +
                "wipe your data, change your PIN, or prevent uninstall — and you can revoke it " +
                "any time in Settings → Security → Device admin apps.",
            nextStep = "Next you'll see Android's standard Device Admin grant screen.",
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
            title = "Ignore battery optimisation",
            whatFor = "Keeps QuicLoc reachable while the phone is idle, so a request that " +
                "arrives at 3am still wakes it.",
            ifSkipped = "Android can put QuicLoc to sleep. Requests may be missed entirely, or " +
                "the reply may be cut off halfway through sending.",
            limits = "QuicLoc does no work of its own in the background — it only ever runs when " +
                "someone actually asks for your location. The battery cost of this exemption is " +
                "as close to zero as it gets.",
            nextStep = "Next you'll see Android's own \"allow this app to run in the " +
                "background?\" dialog.",
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
    private fun autoDetectMyNumber(auto: Boolean = false) {
        // Never stomp on a rationale that's already on screen — the first-launch
        // permission chain owns that slot. Returning without marking the prompt
        // as spent means the auto-detect is simply offered again next launch.
        if (auto && pendingRationaleState.value != null) return
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
        // Explain before asking, like every other permission in the app.
        promptRuntimePermission(Manifest.permission.READ_PHONE_NUMBERS) { granted ->
            val sim = if (granted) readSimPhoneNumber() else ""
            if (sim.isNotBlank()) {
                applyDetectedNumber(sim)
            } else {
                // Denied, or granted but the carrier didn't provision a number —
                // fall back to the Google Phone Number Hint sheet, which needs
                // no permission at all.
                requestPhoneNumberHint()
            }
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
        dialableCountState.value = whitelistManager.getDialableNumbers().size
        starredState.value = whitelistManager.getStarredNumbers()
        myNumberState.value = whitelistManager.getMyNumber()
        enabledState.value = AppSettings.isEnabled(this)
        reminderNotifState.value = AppSettings.isReminderNotificationEnabled(this)
        deviceAdminState.value = FindMyPhone.isAdminActive(this)
        backupAvailableState.value = BackupVault.isAvailable(this)
    }

    private val contactPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleContactPicked(uri) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The unlock PIN keypad, the QuicLoc PIN/passphrase fields, and the
        // whitelist are all rendered somewhere in this activity -- without
        // FLAG_SECURE, Android's default screenshot-for-recents behavior
        // captures that content into the task-switcher thumbnail, visible to
        // anyone with physical access to an unlocked phone.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        whitelistManager = WhitelistManager(this)
        viewState.value = if (!whitelistManager.isOnboardingCompleted())
            MainView.TutorialDetail(Tutorials.MAIN_ID, fromOnboarding = true)
        else
            MainView.Config
        numbersState.value = whitelistManager.getNumbers().toList()
        dialableCountState.value = whitelistManager.getDialableNumbers().size
        starredState.value = whitelistManager.getStarredNumbers()
        myNumberState.value = whitelistManager.getMyNumber()
        enabledState.value = AppSettings.isEnabled(this)
        reminderNotifState.value = AppSettings.isReminderNotificationEnabled(this)
        deviceAdminState.value = FindMyPhone.isAdminActive(this)
        fullScreenIntentState.value = canUseFullScreenIntent()
        backupAvailableState.value = BackupVault.isAvailable(this)
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
                    val showPinEntry by pinGateState
                    BiometricGateScreen(
                        canUseBiometrics = BiometricHelper.canAuthenticate(this@MainActivity),
                        appPinSet = hasAppPin(),
                        showPinEntry = showPinEntry,
                        onRetry = {
                            pinGateState.value = false
                            promptBiometric()
                        },
                        onSubmitPin = { entered -> submitAppPin(entered) },
                    )
                } else {

                    val numbersList by numbersState
                    val dialableCount by dialableCountState
                    val starredSet by starredState
                    val myNumber by myNumberState
                    val enabled by enabledState
                    val reminderEnabled by reminderNotifState
                    val deviceAdminGranted by deviceAdminState
                    val fullScreenIntentGranted by fullScreenIntentState
                    val permissionStatuses by permissionStatusesState
                    var currentPassphrase by remember { mutableStateOf(whitelistManager.getPassphrase() ?: "") }
                    var currentPin by remember { mutableStateOf(whitelistManager.getPin() ?: "") }

                    // Derived from permissionStatuses (refreshed every onResume)
                    // instead of its own remember{} snapshot -- a standalone
                    // snapshot here never updated after granting/revoking the
                    // permission in system Settings, since returning from that
                    // Settings screen is a round-trip this activity treats as
                    // "expected" and doesn't rebuild this composable subtree.
                    // Deriving it means it can never disagree with the same
                    // list the Setup Checklist and All Permissions table read.
                    val notificationAccessGranted = permissionStatuses.any {
                        it.key == PermKeys.NOTIF_LISTENER && it.state == PermStatus.GRANTED
                    }
                    var view by viewState

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
                            title = {
                                Column {
                                    if (state.progress != null) {
                                        Text(
                                            text = state.progress,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(state.title)
                                }
                            },
                            text = {
                                // Same four slots every time — what it's for, what
                                // breaks without it, what QuicLoc won't do with it,
                                // and what the user is about to see.
                                Column(
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = state.whatFor,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    RationaleBlock("If you skip it", state.ifSkipped)
                                    RationaleBlock("What QuicLoc will never do", state.limits)
                                    RationaleBlock("What happens next", state.nextStep)
                                }
                            },
                            confirmButton = {
                                Button(onClick = state.onContinue) { Text(state.confirmLabel) }
                            },
                            dismissButton = {
                                TextButton(onClick = state.onSkip) { Text(state.skipLabel) }
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
                                tutorials = Tutorials.visible,
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
                                        confirmLabel = if (v.fromOnboarding) "Set QuicLoc up" else "Done",
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
                                dialableCount = dialableCount,
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
                                onAutoDetectMyNumber = { auto -> autoDetectMyNumber(auto) },
                                autoDetectMyNumberOnFirstShow = !AppSettings.wasPhoneHintAutoPrompted(this@MainActivity),
                                onRequestDeviceAdmin = { requestDeviceAdmin() },
                                backupAvailable = backupAvailableState.value,
                                onSetAppPin = { newPin ->
                                    whitelistManager.setPin(newPin)
                                    currentPin = newPin
                                    // Force the encrypted backup to be written now
                                    // rather than on the 250ms debounce, so the
                                    // Backup section flips to "on" immediately.
                                    refreshBackupSnapshot()
                                    Toast.makeText(
                                        this@MainActivity,
                                        "PIN set. You can now unlock QuicLoc with it, and your settings are backed up.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                onClearAppPin = {
                                    whitelistManager.setPin(null)
                                    currentPin = ""
                                    refreshBackupSnapshot()
                                    Toast.makeText(
                                        this@MainActivity,
                                        "PIN removed. The encrypted backup has been deleted.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
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
                                        dialableCountState.value = whitelistManager.getDialableNumbers().size
                                        Toast.makeText(this, "Added — they can now ask for your location", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRemoveNumber = { number ->
                                    whitelistManager.removeNumber(number)
                                    numbersState.value = whitelistManager.getNumbers().toList()
                                    dialableCountState.value = whitelistManager.getDialableNumbers().size
                                    starredState.value = whitelistManager.getStarredNumbers()
                                },
                                onPickContact = { launchContactPicker() },
                                onOpenTutorials = { view = MainView.TutorialsHub },
                                onOpenHistory = { view = MainView.History },
                                onOpenDiagnostics = { view = MainView.Diagnostics },
                                onToggleStar = { number ->
                                    val success = whitelistManager.toggleStarred(number)
                                    if (!success) {
                                        Toast.makeText(this@MainActivity, "Only 3 contacts can be starred — unstar one first.", Toast.LENGTH_SHORT).show()
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
        backupAvailableState.value = BackupVault.isAvailable(this)
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
    // Unlocking: biometrics / device credential, or the QuicLoc PIN
    // -------------------------------------------------------------------------

    /** Whether the user has set a QuicLoc PIN they can unlock the app with. */
    private fun hasAppPin(): Boolean = !whitelistManager.getPin().isNullOrBlank()

    private fun unlock() {
        isAuthenticated = true
        authState.value = true
        pinGateState.value = false
        // Only auto-walk the permission rationale chain once per process
        // lifetime, not on every unlock. unlock() runs on every successful
        // re-authentication -- an ordinary Home-button press backgrounds the
        // activity and clears authState (see onPause), so without this gate,
        // skipping (or simply not yet having reached) any one permission
        // meant the full "Step 1 of N" dialog sequence re-fired every single
        // time the user reopened the app. Missing permissions are still
        // reachable any time via the Setup Checklist / All Permissions panel.
        if (!hasRunPermissionChainThisSession) {
            hasRunPermissionChainThisSession = true
            checkPermissions()
        }
    }

    /**
     * Entry point for the lock screen. Three cases:
     *
     *   - Device has a lock screen → the system prompt (fingerprint / face /
     *     device PIN). The gate screen behind it also offers the QuicLoc PIN
     *     if one is set, so a failing fingerprint is never a dead end.
     *   - No device lock screen, but a QuicLoc PIN is set → the PIN is the
     *     only gate, so go straight to it. (Previously this case let anyone
     *     straight in.)
     *   - Neither → nothing to authenticate against; let the user in and warn
     *     in the settings UI.
     */
    private fun promptBiometric() {
        if (!BiometricHelper.canAuthenticate(this)) {
            if (hasAppPin()) {
                pinGateState.value = true
                return
            }
            unlock()
            return
        }
        BiometricHelper.authenticate(
            activity = this,
            onSuccess = { unlock() },
            onFailure = { reason ->
                isAuthenticated = false
                authState.value = false
                // User cancelled or failed — fall back to the gate screen,
                // which offers the QuicLoc PIN when one is set.
                if (hasAppPin()) pinGateState.value = true
                Toast.makeText(this, "Authentication required: $reason", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * Check an entered QuicLoc PIN. Returns true (and unlocks) on a match.
     * Constant-time comparison — cheap, and this is the only place a stored
     * secret is compared against user input.
     */
    private fun submitAppPin(entered: String): Boolean {
        val stored = whitelistManager.getPin() ?: return false
        val ok = java.security.MessageDigest.isEqual(
            stored.toByteArray(Charsets.UTF_8),
            entered.toByteArray(Charsets.UTF_8),
        )
        if (ok) unlock()
        return ok
    }

    // -------------------------------------------------------------------------
    // Contact picker
    // -------------------------------------------------------------------------

    private fun launchContactPicker() {
        // Pick a CONTACT, not a single phone row: ACTION_PICK against the
        // Phone content URI forces the system picker to resolve to exactly
        // one number, so a contact with a mobile AND a home number would only
        // ever get the one the user happened to tap. Picking the contact
        // itself lets handleContactPicked enumerate every number on file.
        val intent = android.content.Intent(
            android.content.Intent.ACTION_PICK,
            ContactsContract.Contacts.CONTENT_URI
        )
        beginSystemFlow()
        contactPickerLauncher.launch(intent)
    }

    private fun handleContactPicked(uri: Uri) {
        try {
            val contactCursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            ) ?: return
            contactCursor.use { cursor ->
                if (!cursor.moveToFirst()) return
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (idIdx < 0) return
                val contactId = cursor.getString(idIdx)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null

                val numbers = mutableListOf<String>()
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )?.use { phoneCursor ->
                    val numberIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIdx >= 0) {
                        while (phoneCursor.moveToNext()) {
                            phoneCursor.getString(numberIdx)?.takeIf { it.isNotBlank() }?.let { numbers.add(it) }
                        }
                    }
                }

                // One whitelist entry per number, all sharing this contact's
                // name -- WhitelistManager matches by name OR any of a
                // contact's numbers, and the UI still shows a single row for
                // the shared display name, so this doesn't create duplicates
                // on screen while still answering from any of their numbers.
                if (numbers.isNotEmpty()) {
                    for (number in numbers.distinct()) {
                        whitelistManager.addContact(name, number)
                    }
                } else if (!name.isNullOrBlank()) {
                    whitelistManager.addContact(name, "")
                }

                numbersState.value = whitelistManager.getNumbers().toList()
                dialableCountState.value = whitelistManager.getDialableNumbers().size
                val toastName = name?.takeIf { it.isNotBlank() } ?: numbers.firstOrNull() ?: "Contact"
                Toast.makeText(this, "Added $toastName — they can now ask for your location", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't read that contact — try again.", Toast.LENGTH_SHORT).show()
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
            title = "Location: \"Allow all the time\"",
            whatFor = "Lets QuicLoc answer while the phone is in your pocket — screen off, app " +
                "closed. This is the whole point of the app.",
            ifSkipped = "QuicLoc can only answer while you happen to have it open on screen. " +
                "Every other request goes unanswered.",
            limits = "\"All the time\" is about *when it may ask*, not how often it does. " +
                "Location is still only read in the seconds it takes to answer a request from " +
                "someone on your list.",
            nextStep = "Android won't let apps ask for this directly. The next screen is the " +
                "system Location page for QuicLoc — tap \"Allow all the time\" there.",
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
                launchPermissionRequest(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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
            whatFor = "Extends the trigger word to WhatsApp, Telegram, Signal, Messenger and " +
                "Google Messages — QuicLoc replies through the notification's own reply box.",
            ifSkipped = "Only plain text messages (SMS) will work. Everything else is invisible " +
                "to the app.",
            limits = "QuicLoc reads the sender and text of incoming notifications and acts only " +
                "when the sender is on your trusted list AND the message is exactly the trigger " +
                "word. Nothing is logged or sent anywhere.",
            nextStep = "The next screen is Android's Notification Access list — find QuicLoc and " +
                "switch it on.",
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
            title = "Full screen notifications",
            whatFor = "Lets the find-my-phone lock screen come up over the keyguard when Device " +
                "Admin isn't granted.",
            ifSkipped = "Nothing, if you've granted Device Admin — that path doesn't need this. " +
                "Without both, a triggered lockdown can't cover the screen on Android 14+.",
            nextStep = "The next screen is QuicLoc's notification settings — turn on \"Allow " +
                "full screen notifications\".",
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

/**
 * One labelled paragraph of a permission-rationale dialog. Renders nothing
 * when the slot is unused, so a permission with nothing to reassure about
 * doesn't get an empty heading.
 */
@Composable
private fun RationaleBlock(label: String, body: String?) {
    if (body == null) return
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// -------------------------------------------------------------------------
// Lock screen shown before auth passes
// -------------------------------------------------------------------------

/**
 * The lock screen shown after [MainActivity.onCreate] but before
 * authentication succeeds. Two ways in, either of which may be unavailable:
 *
 *   - the system prompt (fingerprint / face / device PIN), if the device has
 *     a lock screen — [onRetry] re-fires it after a cancel or failure;
 *   - the QuicLoc PIN, if the user has set one. This is the only way in on a
 *     device with no lock screen of its own, and the fallback when a
 *     fingerprint won't read.
 *
 * [onSubmitPin] returns true if the PIN was correct; the screen shows an
 * error and clears the field when it isn't.
 */
@Composable
fun BiometricGateScreen(
    canUseBiometrics: Boolean,
    appPinSet: Boolean,
    showPinEntry: Boolean,
    onRetry: () -> Unit,
    onSubmitPin: (String) -> Boolean,
) {
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
                text = "Unlock to change who can ask for your location",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "QuicLoc keeps answering requests in the background either way — this " +
                    "lock only protects the settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Start on whichever method the activity picked, but always let
            // the user switch — a fingerprint that won't read shouldn't be a
            // dead end, and neither should a PIN you've forgotten.
            var usePin by remember(showPinEntry) { mutableStateOf(showPinEntry || !canUseBiometrics) }

            if (canUseBiometrics && !usePin) {
                Button(onClick = onRetry) {
                    Text("Unlock with fingerprint or device PIN")
                }
                if (appPinSet) {
                    TextButton(onClick = { usePin = true }) {
                        Text("Use QuicLoc PIN instead")
                    }
                }
            } else if (appPinSet) {
                var pin by remember { mutableStateOf("") }
                var error by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(6)
                        error = false
                    },
                    label = { Text("QuicLoc PIN") },
                    singleLine = true,
                    isError = error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.padding(horizontal = 48.dp)
                )
                if (error) {
                    Text(
                        text = "Wrong PIN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (!onSubmitPin(pin)) {
                            error = true
                            pin = ""
                        }
                    },
                    enabled = pin.length >= 4,
                ) {
                    Text("Unlock")
                }
                if (canUseBiometrics) {
                    TextButton(onClick = {
                        usePin = false
                        onRetry()
                    }) {
                        Text("Use fingerprint or device PIN instead")
                    }
                }
            } else {
                // No device lock screen and no QuicLoc PIN — there is nothing
                // to check. The activity normally lets the user straight
                // through in this case; this is the belt-and-braces path.
                Text(
                    text = "This phone has no lock screen and no QuicLoc PIN, so there's " +
                        "nothing to unlock with. Set one inside, in the \"Your QuicLoc PIN\" " +
                        "section of settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onRetry) {
                    Text("Continue")
                }
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
 * Organised so a first-time user can answer three questions without leaving
 * the screen: *what is this app*, *is it going to work*, and *where do I
 * configure feature X*.
 *
 *   1. [WhatQuicLocDoesCard] — the pitch in three lines, plus the master
 *      on/off switch. Always first, always expanded.
 *   2. [SetupChecklistCard] — every required step with live ✓/✗ status and a
 *      one-tap fix. Auto-expanded until all required steps pass.
 *   3. One [SectionCard] per app function, each with its own heading,
 *      one-line purpose, and *only* the settings that belong to that
 *      function:
 *        1 Trusted contacts — who is allowed to ask
 *        2 The trigger word — what they send
 *        3 Home screen widget — your own number, tap patterns
 *        4 Find my phone — passphrase / Device Admin (hidden while
 *          [FindMyPhone.ENABLED] is false)
 *        5 Your QuicLoc PIN — set / change / remove
 *        6 App access & notifications — how you get in, reminder notification
 *        7 Permissions — the full grant table
 *        8 Backup & restore — export / import
 *        9 Help & troubleshooting — tutorials, history, diagnostics
 *
 *      Numbers come from `sectionNo()`, which derives them from the sections
 *      actually on screen, so hiding find-my-phone renumbers the rest.
 *
 * The whole screen is in a [verticalScroll] so the user can reach every
 * contact (no nested `LazyColumn` collapse).
 */
@Composable
fun QuicLocScreen(
    modifier: Modifier = Modifier,
    numbersList: List<String>,
    dialableCount: Int = numbersList.size,
    starredSet: Set<String>,
    myNumber: String,
    notificationAccessGranted: Boolean,
    noLockScreenWarning: Boolean,
    enabled: Boolean,
    reminderNotificationEnabled: Boolean,
    deviceAdminGranted: Boolean,
    fullScreenIntentGranted: Boolean,
    onRequestFullScreenIntent: () -> Unit,
    permissionStatuses: List<PermissionStatus>,
    onPermissionAction: (key: String) -> Unit,
    autoDetectMyNumberOnFirstShow: Boolean,
    backupAvailable: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleReminderNotification: (Boolean) -> Unit,
    onAutoDetectMyNumber: (auto: Boolean) -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onRequestExportBackup: () -> Unit,
    onRequestImportBackup: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onAddNumber: (String) -> Unit,
    onRemoveNumber: (String) -> Unit,
    onPickContact: () -> Unit,
    onOpenTutorials: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    currentPassphrase: String = "",
    currentPin: String = "",
    onSetAppPin: (String) -> Unit = {},
    onClearAppPin: () -> Unit = {},
    onSavePassphrase: (String, String) -> Unit = { _, _ -> },
    onToggleStar: (String) -> Unit = {},
    onMyNumberChanged: (String) -> Unit = {}
) {
    var phoneNumberInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val pinSet = currentPin.isNotBlank()

    // Same source of truth as the All Permissions table — the checklist and the
    // table can never disagree.
    val setupSteps = remember(enabled, numbersList.size, dialableCount, myNumber, permissionStatuses, pinSet) {
        Readiness.steps(
            enabled = enabled,
            whitelistCount = numbersList.size,
            dialableWhitelistCount = dialableCount,
            myNumber = myNumber,
            permissions = permissionStatuses,
            pinSet = pinSet,
        )
    }
    val ready = Readiness.isReady(setupSteps)

    // Section numbers are derived from what's actually on screen, so inserting
    // or hiding a section doesn't mean hand-renumbering everything after it.
    val sectionOrder = buildList {
        add("contacts")
        add("trigger")
        add("widget")
        if (FindMyPhone.ENABLED) add("findmyphone")
        add("pin")
        add("access")
        add("permissions")
        add("backup")
        add("help")
    }
    fun sectionNo(id: String): Int = sectionOrder.indexOf(id) + 1

    // The PIN checklist row jumps to the PIN section: bump the signal to expand
    // it, then scroll it into view. `pinSectionY` is its offset inside the
    // scrolling Column, captured on layout.
    var pinSectionY by remember { mutableStateOf(0) }
    var pinExpandSignal by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Checklist rows whose action is in-app state rather than a system
    // screen are handled here; everything else goes back to the activity.
    val onStepAction: (String) -> Unit = { key ->
        when (key) {
            PermKeys.TURN_ON -> onToggleEnabled(true)
            PermKeys.ADD_CONTACT -> onPickContact()
            PermKeys.MY_NUMBER -> onAutoDetectMyNumber(false)
            PermKeys.NOTIF_LISTENER -> onRequestNotificationAccess()
            PermKeys.SET_PIN -> {
                pinExpandSignal++
                scope.launch { scrollState.animateScrollTo(pinSectionY) }
            }
            else -> onPermissionAction(key)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {
        WhatQuicLocDoesCard(
            enabled = enabled,
            ready = ready,
            onToggleEnabled = onToggleEnabled,
        )

        Spacer(modifier = Modifier.height(12.dp))

        SetupChecklistCard(
            steps = setupSteps,
            onStepAction = onStepAction,
        )

        // ------------------------------------------------------------------
        // 1 — Trusted contacts
        // ------------------------------------------------------------------
        SectionCard(
            number = sectionNo("contacts"),
            title = "Trusted contacts",
            subtitle = "Who is allowed to ask where you are",
            statusText = if (numbersList.isEmpty())
                "Nobody yet — QuicLoc will ignore every request"
            else
                "${numbersList.size} allowed · ${starredSet.size} of 3 starred",
            statusOk = numbersList.isNotEmpty(),
        ) {
            Text(
                text = "Only the people listed here can trigger a reply. Anyone else who texts " +
                    "the trigger word is ignored silently — they aren't told the app exists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

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
            Text(
                text = "Easiest way — adds the contact's name and every number on file, which " +
                    "covers both text messages and chat apps at once.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = phoneNumberInput,
                    onValueChange = { phoneNumberInput = it },
                    label = { Text("Or type a number or name") },
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
                text = "A phone number matches text messages. A name matches chat apps — spell " +
                    "it exactly as it appears at the top of the chat notification.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Allowed to ask (${numbersList.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "★ marks a priority contact — the widget's 3-tap safety check goes to " +
                    "these (up to 3). Everyone here gets the 4-tap emergency alert.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )

            if (numbersList.isEmpty()) {
                Text(
                    text = "Empty. Add someone above — until you do, QuicLoc has nobody to " +
                        "answer and will do nothing at all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                                contentDescription = if (isStarred)
                                    "Remove priority star from $number"
                                else
                                    "Make $number a priority contact",
                                tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRemoveNumber(number) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove $number"
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 2 — The trigger word
        // ------------------------------------------------------------------
        SectionCard(
            number = sectionNo("trigger"),
            title = "The trigger word",
            subtitle = "What a trusted contact sends you",
            statusText = if (notificationAccessGranted)
                "Text messages and chat apps"
            else
                "Text messages only — chat apps need Notification Access",
            // Not a ✗: SMS-only is a perfectly working configuration, just a
            // narrower one. Only genuinely broken states get an error mark.
            statusOk = if (notificationAccessGranted) true else null,
        ) {
            Text(
                text = "They send you one of these, on its own, in any messaging app:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                TriggerWordChip("loc")
                Spacer(modifier = Modifier.width(8.dp))
                TriggerWordChip("quicloc")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Upper or lower case, doesn't matter. It has to be the whole message, " +
                    "though — \"loc?\" or \"send me your loc\" won't trigger anything.\n\n" +
                    "You get no prompt and no ring: QuicLoc grabs a GPS fix and texts back a " +
                    "Google Maps link, screen off, app closed, usually within a few seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            if (!notificationAccessGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Chat apps are off",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Right now only plain text messages (SMS) work. Notification " +
                                "Access is what lets QuicLoc see and answer a \"loc\" sent in " +
                                "WhatsApp, Telegram, Signal, Messenger or Google Messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                        Button(
                            onClick = onRequestNotificationAccess,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Turn on Notification Access")
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        text = "✓ Notification Access is on — WhatsApp, Telegram, Signal, " +
                            "Messenger and friends work too. (Apps that hide message text in " +
                            "notifications can't work: the trigger never reaches the phone.)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Test it: get someone on your list to text you \"loc\" — or add your own " +
                    "number and text yourself from another device. Then open Request History " +
                    "to see exactly what happened.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenHistory) { Text("Open Request History") }
        }

        // ------------------------------------------------------------------
        // 3 — Home screen widget
        // ------------------------------------------------------------------
        SectionCard(
            number = sectionNo("widget"),
            title = "Home screen widget",
            subtitle = "Send your location without being asked",
            statusText = "Long-press the home screen → Widgets → QuicLoc",
            statusOk = null,
        ) {
            Text(
                text = "The widget is for sending your own location on purpose — parking spot, " +
                    "safety check, emergency. Tap it repeatedly; the count decides who gets it. " +
                    "Each tap must land within about half a second of the last one, and buzzes " +
                    "to confirm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            WidgetTapRow(
                taps = "1 tap",
                action = "Opens this help",
                requirement = "Nothing needed",
                ok = null,
            )
            WidgetTapRow(
                taps = "2 taps",
                action = "Parking — texts your location to yourself, tagged #Parking",
                requirement = if (myNumber.isBlank())
                    "Needs your own number (set below)"
                else
                    "Sends to $myNumber",
                ok = myNumber.isNotBlank(),
            )
            WidgetTapRow(
                taps = "3 taps",
                action = "Safety check — texts your starred contacts, tagged #SafetyCheck",
                requirement = if (starredSet.isEmpty())
                    "Needs at least one ★ starred contact (section 1)"
                else
                    "Sends to ${starredSet.size} starred contact${if (starredSet.size == 1) "" else "s"}",
                ok = starredSet.isNotEmpty(),
            )
            WidgetTapRow(
                taps = "4 taps",
                action = "Emergency — texts everyone on your list, tagged #Emergency",
                requirement = if (numbersList.isEmpty())
                    "Needs at least one trusted contact (section 1)"
                else
                    "Sends to all ${numbersList.size} trusted contact${if (numbersList.size == 1) "" else "s"}",
                ok = numbersList.isNotEmpty(),
            )

            // Your own phone number lives here because the 2-tap parking
            // reminder is the only thing that uses it. It locks itself once
            // set so it can't be mistaken for the whitelist field in
            // section 1. On first show with an empty number the Phone Number
            // Hint sheet is auto-triggered so it can be picked in one tap.
            Spacer(modifier = Modifier.height(16.dp))
            var hasInteractedWithMyNumber by remember { mutableStateOf(false) }
            val myNumberLocked = myNumber.isNotEmpty() && !hasInteractedWithMyNumber
            LaunchedEffect(Unit) {
                if (autoDetectMyNumberOnFirstShow && myNumber.isEmpty()) {
                    onAutoDetectMyNumber(true)
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Your own phone number",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Used by the 2-tap parking reminder. QuicLoc also always treats " +
                            "this number as trusted — handy for testing the trigger word by " +
                            "texting yourself from another device you own. It's still just your " +
                            "own number: nobody else can use it to request your location.",
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
                            onClick = { onAutoDetectMyNumber(false) },
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text("Auto-detect from this device")
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 4 — Find my phone (hidden while the feature is disabled)
        // ------------------------------------------------------------------
        // The find-my-phone / lockdown setup UI (passphrase + PIN, Device
        // Admin opt-in, full-screen-intent grant) is kept intact so
        // re-enabling the feature is a one-line flip of FindMyPhone.ENABLED.
        if (FindMyPhone.ENABLED) {
            SectionCard(
                number = sectionNo("findmyphone"),
                title = "Find my phone",
                subtitle = "Lock and track this phone if it's lost or stolen",
                statusText = if (currentPassphrase.isBlank())
                    "No passphrase set — feature is off"
                else if (deviceAdminGranted)
                    "Armed, with real device lock"
                else
                    "Armed, but the screen is only covered — not locked",
                statusOk = currentPassphrase.isNotBlank() && deviceAdminGranted,
            ) {
                Text(
                    text = "This one works from ANY number, not just trusted contacts — so you " +
                        "can trigger it from a borrowed phone. Text \"loc \" followed by your " +
                        "passphrase. The phone locks, and its location is sent back to whatever " +
                        "number sent the passphrase every 5 minutes. Your PIN stops it. Three " +
                        "wrong PINs photographs whoever is holding it.\n\n" +
                        "The passphrase is single-use — set a new one after it fires.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                var passphraseInput by remember(currentPassphrase) { mutableStateOf(currentPassphrase) }
                var pinInput by remember(currentPin) { mutableStateOf(currentPin) }

                OutlinedTextField(
                    value = passphraseInput,
                    onValueChange = { if (it.length <= 150) passphraseInput = it },
                    label = { Text("Passphrase (10–150 characters)") },
                    supportingText = {
                        Text("Texting \"loc <this>\" from any number locks and tracks the phone.")
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    singleLine = false,
                    minLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
                    label = { Text("6-digit PIN") },
                    supportingText = {
                        Text(
                            "This is your one QuicLoc PIN: it stops tracking, unlocks the app, " +
                                "and encrypts your backup. Changing it here changes it everywhere."
                        )
                    },
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
                    Text("Save passphrase & PIN")
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
            }
        }

        // ------------------------------------------------------------------
        // Your QuicLoc PIN — its own section, because it is its own decision:
        // a second way into the app AND the key that makes backup possible.
        // It used to live inside the section below, collapsed, where nobody
        // found it.
        // ------------------------------------------------------------------
        Box(
            modifier = Modifier.onGloballyPositioned { coords ->
                pinSectionY = coords.positionInParent().y.toInt()
            }
        ) {
            SectionCard(
                number = sectionNo("pin"),
                title = "Your QuicLoc PIN",
                subtitle = "A second way in, and what makes backup possible",
                statusText = if (pinSet)
                    "PIN is set — unlocks the app, encrypts your backup"
                else
                    "No PIN — no backup, and no way in if a fingerprint fails",
                statusOk = pinSet,
                expandSignal = pinExpandSignal,
            ) {
                if (noLockScreenWarning && !pinSet) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = "⚠ This phone has no lock screen, so anyone who picks it up can " +
                                "open QuicLoc and read or change your trusted contacts. Setting a " +
                                "PIN here fixes that — or set a lock screen in system settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                AppPinCard(
                    pinSet = pinSet,
                    onSetAppPin = onSetAppPin,
                    onClearAppPin = onClearAppPin,
                )
            }
        }

        // ------------------------------------------------------------------
        // App access & notifications
        // ------------------------------------------------------------------
        SectionCard(
            number = sectionNo("access"),
            title = "App access & notifications",
            subtitle = "How you get in, and what shows in the shade",
            statusText = when {
                !noLockScreenWarning && pinSet ->
                    "Fingerprint, device PIN, or your QuicLoc PIN"
                !noLockScreenWarning -> "Locked behind your fingerprint, face or device PIN"
                pinSet -> "No device lock screen — locked by your QuicLoc PIN"
                else -> "Unlocked — this phone has no lock screen and no QuicLoc PIN"
            },
            statusOk = !noLockScreenWarning || pinSet,
            initiallyExpanded = false,
        ) {
            Text(
                text = "Opening QuicLoc needs your fingerprint, face or device PIN — or the " +
                    "QuicLoc PIN from section ${sectionNo("pin")}. Answering a request needs none " +
                    "of them: that keeps working while the phone is locked in your pocket, which " +
                    "is the entire point.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reminder notification",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "A silent, permanent notification showing whether QuicLoc is on, " +
                            "with a button to flip it without unlocking the app. Off by default.",
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

        // ------------------------------------------------------------------
        // 6 — Permissions
        // ------------------------------------------------------------------
        val grantableCount = permissionStatuses.count { it.state != PermStatus.AUTO_GRANTED }
        val grantedCount = permissionStatuses.count {
            it.state == PermStatus.GRANTED || it.state == PermStatus.AUTO_GRANTED
        }
        SectionCard(
            number = sectionNo("permissions"),
            title = "Permissions",
            subtitle = "Every permission QuicLoc uses, and its live status",
            statusText = "$grantedCount of ${permissionStatuses.size} granted",
            // Neutral on purpose — plenty of these are optional, and the
            // checklist above is what says whether anything is actually wrong.
            statusOk = null,
            initiallyExpanded = false,
        ) {
            Text(
                text = "The setup checklist at the top covers the ones that matter. This is the " +
                    "full list, for when you want to see or revoke something specific.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // Android 13+ blocks special-access permissions for sideloaded
            // apps until the user explicitly unlocks them from the app's
            // system settings page. This note appears for everyone — Play
            // Store installs can ignore it.
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
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                Text(
                    text = when (cat) {
                        "Runtime" -> "Asked with a pop-up the first time they're needed."
                        "Special access" -> "Granted by hand on a system Settings page."
                        "Protected" -> "Not permissions — device settings that keep QuicLoc awake enough to answer."
                        else -> "Granted automatically at install. Nothing to do."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                rows.forEach { status ->
                    val iconText: String
                    val iconColor: androidx.compose.ui.graphics.Color
                    val buttonLabel: String?
                    when (status.state) {
                        PermStatus.GRANTED -> {
                            iconText = "✓"
                            iconColor = MaterialTheme.colorScheme.primary
                            buttonLabel = "Manage"
                        }
                        PermStatus.AUTO_GRANTED -> {
                            iconText = "✓"
                            iconColor = MaterialTheme.colorScheme.primary
                            buttonLabel = null
                        }
                        PermStatus.NOT_GRANTED -> {
                            iconText = "✗"
                            iconColor = MaterialTheme.colorScheme.error
                            buttonLabel = "Grant"
                        }
                        PermStatus.UNKNOWN -> {
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

        // ------------------------------------------------------------------
        // 7 — Backup & restore
        // ------------------------------------------------------------------
        SectionCard(
            number = sectionNo("backup"),
            title = "Backup & restore",
            subtitle = "Move your setup to a new phone",
            statusText = if (backupAvailable)
                "Backup is on — encrypted with your QuicLoc PIN"
            else
                "No backup yet — set a QuicLoc PIN to switch it on",
            statusOk = backupAvailable,
            initiallyExpanded = false,
        ) {
            Text(
                text = if (backupAvailable)
                    "Your contacts and settings are encrypted with your QuicLoc PIN and ride " +
                        "along in Android's own cloud backup and device-to-device transfer. On " +
                        "a new phone, QuicLoc asks for that PIN on first launch and puts " +
                        "everything back. Export writes the same encrypted file anywhere you " +
                        "like."
                else
                    "There is nothing to back up until you set a QuicLoc PIN — the PIN is the " +
                        "encryption key, and without one there's no way to protect the file. " +
                        "Set one in section ${sectionNo("pin")}, \"Your QuicLoc PIN\", and your " +
                        "contacts and settings then travel with Android's cloud backup and " +
                        "device-to-device transfer automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
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

        // ------------------------------------------------------------------
        // 8 — Help & troubleshooting
        // ------------------------------------------------------------------
        SectionCard(
            number = sectionNo("help"),
            title = "Help & troubleshooting",
            subtitle = "Tutorials, what happened, and why nothing happened",
            statusText = null,
            statusOk = null,
        ) {
            HelpLinkRow(
                title = "Tutorials",
                detail = "Start with \"Why QuicLoc?\" — every feature explained in plain English.",
                onClick = onOpenTutorials,
            )
            HelpLinkRow(
                title = "Request history",
                detail = "Every request QuicLoc answered (✓) or failed (✗), with who and when.",
                onClick = onOpenHistory,
            )
            HelpLinkRow(
                title = "Diagnostics",
                detail = "You texted \"loc\" and nothing happened? This shows the message arriving " +
                    "and the exact reason QuicLoc did or didn't act.",
                onClick = onOpenDiagnostics,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Nothing happens when they text you? In order of likelihood: the sender " +
                    "isn't on your trusted list (or is listed under a different name than the " +
                    "chat app shows); location isn't set to \"Allow all the time\"; Notification " +
                    "Access is off and they used a chat app; or the phone's battery saver has " +
                    "QuicLoc asleep. The checklist at the top of this screen covers all four.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// -------------------------------------------------------------------------
// Settings-screen building blocks
// -------------------------------------------------------------------------

/**
 * The always-first card: what the app is for, in three numbered lines, plus
 * the master on/off switch. Someone who has never seen QuicLoc should be able
 * to read this alone and know what the app does.
 */
@Composable
private fun WhatQuicLocDoesCard(
    enabled: Boolean,
    ready: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled && ready)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "QuicLoc answers \"where are you?\" for you",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1.  You list the people you trust.\n" +
                    "2.  One of them texts you the word  loc\n" +
                    "3.  Your phone texts back a Google Maps link — by itself, screen off, " +
                    "app closed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your location is fetched only at that moment, sent only to that person, " +
                    "and never stored anywhere. No server, no tracking, no account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (enabled) "QuicLoc is ON" else "QuicLoc is OFF",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (enabled)
                            "Listening for requests by text and in chat apps."
                        else
                            "Every trigger is ignored — texts, chat apps and the widget.",
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
    }
}

/**
 * The "will this actually work?" card. Required steps first, recommended
 * ones under their own heading. Starts expanded until every required step
 * passes, then collapses to a single green line the user can forget about.
 */
@Composable
private fun SetupChecklistCard(
    steps: List<SetupStep>,
    onStepAction: (String) -> Unit,
) {
    val required = steps.filter { it.required }
    val optional = steps.filterNot { it.required }
    val requiredLeft = Readiness.requiredRemaining(steps)
    val optionalLeft = Readiness.optionalRemaining(steps)
    val ready = requiredLeft == 0

    // Expanded while anything required is outstanding. `ready` as the key
    // means finishing the last step collapses it, and a permission going
    // missing later pops it back open.
    var expanded by remember(ready) { mutableStateOf(!ready) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (ready)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = Readiness.headline(steps),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (ready)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = if (ready && optionalLeft == 0)
                            "Setup complete. Everything below is optional tuning."
                        else if (ready)
                            "$optionalLeft optional improvement${if (optionalLeft == 1) "" else "s"} suggested — tap to see."
                        else
                            "Tap to see what's missing. Until then, QuicLoc will not answer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ready)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse setup checklist" else "Expand setup checklist",
                    tint = if (ready)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "REQUIRED — QuicLoc cannot answer a request without these",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer,
                )
                required.forEach { SetupStepRow(it, onStepAction) }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "RECOMMENDED — skip these and QuicLoc still works, just less often",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer,
                )
                optional.forEach { SetupStepRow(it, onStepAction) }
            }
        }
    }
}

/** One ✓/✗ line of [SetupChecklistCard], with its own fix button. */
@Composable
private fun SetupStepRow(
    step: SetupStep,
    onStepAction: (String) -> Unit,
) {
    val done = step.state == StepState.DONE
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (step.state) {
                    StepState.DONE -> "✓"
                    StepState.TODO -> "✗"
                    StepState.UNKNOWN -> "?"
                },
                style = MaterialTheme.typography.titleMedium,
                color = when (step.state) {
                    StepState.DONE -> MaterialTheme.colorScheme.primary
                    StepState.TODO -> if (step.required)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                    StepState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (done) FontWeight.Normal else FontWeight.SemiBold,
                )
                if (!done) {
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (!done) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onStepAction(step.actionKey) }) {
                    Text(step.actionLabel)
                }
            }
        }
    }
}

/**
 * A numbered, collapsible group of settings that all belong to one feature.
 * The header alone answers "what is this for" and "is it set up" — the body
 * is the only place that feature's settings live.
 */
@Composable
private fun SectionCard(
    number: Int,
    title: String,
    subtitle: String,
    statusText: String?,
    statusOk: Boolean?,
    initiallyExpanded: Boolean = true,
    /**
     * Bump this to force the section open — used when something elsewhere on
     * the screen (a setup-checklist row) needs to send the user here.
     */
    expandSignal: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(number) { mutableStateOf(initiallyExpanded) }
    // Skip the initial composition: only a *change* means someone asked.
    LaunchedEffect(expandSignal) {
        if (expandSignal > 0) expanded = true
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$number",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (statusText != null) {
                        Text(
                            text = when (statusOk) {
                                true -> "✓ $statusText"
                                false -> "✗ $statusText"
                                null -> statusText
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (statusOk) {
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * Set / change / remove the QuicLoc PIN. One PIN, two jobs, both stated
 * outright: it unlocks the app when a fingerprint isn't available, and it's
 * the key that encrypts the backup. (When find-my-phone is enabled it's the
 * same PIN that stops tracking — that section says so too.)
 */
@Composable
private fun AppPinCard(
    pinSet: Boolean,
    onSetAppPin: (String) -> Unit,
    onClearAppPin: () -> Unit,
) {
    var editing by remember(pinSet) { mutableStateOf(!pinSet) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    val valid = pin.length == 6 && pin.all(Char::isDigit)
    val matches = pin == confirm

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "QuicLoc PIN",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "A 6-digit PIN of your own. It does two things: unlocks this screen when " +
                    "a fingerprint won't read — or when the phone has no lock screen at all — " +
                    "and encrypts your backup, which is what lets your contacts survive a move " +
                    "to a new phone. Optional, but with no PIN there is no backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            if (pinSet && !editing) {
                Text(
                    text = "✓ PIN is set.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row {
                    TextButton(onClick = {
                        pin = ""
                        confirm = ""
                        editing = true
                    }) { Text("Change PIN") }
                    TextButton(onClick = { showRemoveConfirm = true }) { Text("Remove PIN") }
                }
            } else {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = { Text("New 6-digit PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(6) },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    isError = confirm.isNotEmpty() && !matches,
                    supportingText = {
                        if (confirm.isNotEmpty() && !matches) {
                            Text("The two PINs don't match.")
                        } else {
                            Text("Nobody can recover this for you — there's no reset.")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            onSetAppPin(pin)
                            pin = ""
                            confirm = ""
                            editing = false
                        },
                        enabled = valid && matches,
                    ) {
                        Text(if (pinSet) "Save new PIN" else "Set PIN")
                    }
                    if (pinSet) {
                        TextButton(onClick = {
                            pin = ""
                            confirm = ""
                            editing = false
                        }) { Text("Cancel") }
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove your QuicLoc PIN?") },
            text = {
                Text(
                    text = "You'll no longer be able to unlock QuicLoc with a PIN, and your " +
                        "encrypted backup is deleted — the PIN is its only key. Your trusted " +
                        "contacts and settings on this phone are untouched.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveConfirm = false
                        onClearAppPin()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove PIN") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Keep it") }
            }
        )
    }
}

/** The trigger word itself, rendered as something you'd type. */
@Composable
private fun TriggerWordChip(word: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = word,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** One row of the widget tap table: how many taps, what it does, what it needs. */
@Composable
private fun WidgetTapRow(
    taps: String,
    action: String,
    requirement: String,
    ok: Boolean?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(
            text = taps,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = when (ok) {
                    true -> "✓ $requirement"
                    false -> "✗ $requirement"
                    null -> requirement
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (ok) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** A tappable "go to this screen, here's why you'd want to" row. */
@Composable
private fun HelpLinkRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    // New events are written by SmsReceiver/NotificationListener/
    // LocationReplyService — all background components this screen has no
    // direct signal from. Poll while the screen is open so a trigger that
    // arrives while the user is already looking at this screen (exactly the
    // troubleshooting workflow this screen exists for) actually shows up,
    // instead of only appearing after navigating away and back.
    LaunchedEffect(diagManager) {
        while (true) {
            events = withContext(Dispatchers.IO) { diagManager.getEvents() }
            loaded = true
            delay(2000)
        }
    }
    var captureAll by remember { mutableStateOf(initialCaptureAll) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Someone texted \"loc\" and nothing happened?",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Every trigger QuicLoc saw is listed below, newest first, with the exact " +
                "decision it reached. If the message isn't here at all, it never reached the " +
                "app — check the three things in the box below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

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
 * Data source: [RequestHistoryManager.getHistory], polled while this screen
 * is open so a request handled by a background component (SmsReceiver,
 * NotificationListener, LocationReplyService) while the user already has
 * this screen open still shows up, not just on the next navigation here.
 * Capped at 100 entries (oldest evicted on insert).
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    historyManager: RequestHistoryManager
) {
    var history by remember { mutableStateOf(historyManager.getHistory()) }
    LaunchedEffect(historyManager) {
        while (true) {
            delay(2000)
            history = historyManager.getHistory()
        }
    }

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
