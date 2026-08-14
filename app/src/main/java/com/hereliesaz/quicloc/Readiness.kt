package com.hereliesaz.quicloc

/**
 * Four-state grant status for one permission row in the All Permissions panel.
 *
 *   - GRANTED — runtime/special perm the user has explicitly granted; tap "Manage" to revoke.
 *   - AUTO_GRANTED — install-time, always-on; no button shown.
 *   - NOT_GRANTED — user can tap "Grant" to start the rationale → request flow.
 *   - UNKNOWN — we have no API to query the state (e.g. OEM autostart); tap "Open"
 *     opens the relevant settings page.
 */
enum class PermStatus { GRANTED, AUTO_GRANTED, NOT_GRANTED, UNKNOWN }

/**
 * One row in the All Permissions panel. [key] is the dispatch token consumed by
 * `MainActivity.dispatchPermissionAction` — either a Manifest permission
 * constant or one of the sentinels in [PermKeys].
 */
data class PermissionStatus(
    val key: String,
    val label: String,
    val category: String,
    val state: PermStatus,
)

/**
 * Dispatch tokens that don't map to a single `<uses-permission>` entry.
 *
 * The `special.*` / `protected.*` keys are handled by
 * `MainActivity.dispatchPermissionAction`; the `setup.*` keys are handled
 * inside `QuicLocScreen` itself (they toggle in-app state rather than opening
 * a system screen).
 */
object PermKeys {
    const val NOTIF_LISTENER = "special.notif_listener"
    const val DEVICE_ADMIN = "special.device_admin"
    const val FSI = "special.full_screen_intent"
    const val BATTERY = "protected.battery"
    const val AUTOSTART = "protected.autostart"
    const val NOTIF_CHANNELS = "protected.notif_channels"

    /** Flip the master enable switch on. */
    const val TURN_ON = "setup.turn_on"
    /** Jump to the contact picker so the whitelist stops being empty. */
    const val ADD_CONTACT = "setup.add_contact"
    /** Auto-detect / prompt for the user's own phone number. */
    const val MY_NUMBER = "setup.my_number"
    /** Open the QuicLoc PIN section and scroll to it. */
    const val SET_PIN = "setup.set_pin"
}

/** Whether a setup step is satisfied, still needs the user, or can't be checked. */
enum class StepState { DONE, TODO, UNKNOWN }

/**
 * One line of the "is QuicLoc actually going to work?" checklist.
 *
 * @param title imperative and specific — what the user does, not what the
 *   system calls it.
 * @param detail what breaks if the step is skipped.
 * @param required true if QuicLoc cannot answer a location request at all
 *   without it. Optional steps widen coverage or improve reliability.
 * @param actionKey dispatch token — a Manifest permission string or a
 *   [PermKeys] sentinel.
 */
data class SetupStep(
    val id: String,
    val title: String,
    val detail: String,
    val state: StepState,
    val required: Boolean,
    val actionKey: String,
    val actionLabel: String,
)

/**
 * Turns the raw permission snapshot plus a little app state into the ordered,
 * plain-English setup checklist shown at the top of the settings screen.
 *
 * Deliberately pure Kotlin (no `android.*` imports, permission names as string
 * literals) so it is unit-testable without Robolectric, and so the checklist
 * can never disagree with the All Permissions panel — both read the same
 * [PermissionStatus] list.
 */
object Readiness {

    const val PERM_RECEIVE_SMS = "android.permission.RECEIVE_SMS"
    const val PERM_SEND_SMS = "android.permission.SEND_SMS"
    const val PERM_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val PERM_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val PERM_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * Build the checklist. Steps whose underlying permission isn't present in
     * [permissions] (background location below Android 10, notifications below
     * Android 13) are skipped entirely rather than reported as missing.
     *
     * @param whitelistCount Total whitelist entries, including name-only ones
     *   (added by typing a handle with no phone number) -- shown for context.
     * @param dialableWhitelistCount Whitelist entries that have an actual
     *   phone number. Defaults to [whitelistCount] for callers (and tests)
     *   that don't distinguish, but real callers should pass the true count:
     *   a whitelist made up entirely of name-only entries can never satisfy
     *   an SMS trigger (SmsReceiver only number-matches) or the widget's SMS
     *   fan-out, so it should not be reported as "done".
     */
    fun steps(
        enabled: Boolean,
        whitelistCount: Int,
        myNumber: String,
        permissions: List<PermissionStatus>,
        pinSet: Boolean = false,
        dialableWhitelistCount: Int = whitelistCount,
    ): List<SetupStep> {
        val byKey = permissions.associateBy { it.key }
        fun state(key: String): PermStatus? = byKey[key]?.state
        fun granted(key: String): Boolean =
            state(key).let { it == PermStatus.GRANTED || it == PermStatus.AUTO_GRANTED }

        val steps = mutableListOf<SetupStep>()

        steps += SetupStep(
            id = "enabled",
            title = "Turn QuicLoc on",
            detail = "The master switch above. While it's off, every trigger — text, chat app, " +
                "and widget — is ignored.",
            state = if (enabled) StepState.DONE else StepState.TODO,
            required = true,
            actionKey = PermKeys.TURN_ON,
            actionLabel = "Turn on",
        )

        // Text messages — both halves are needed: one to hear the request, one
        // to answer it. Reported as a single step because half of it is useless.
        val smsMissing = listOf(PERM_RECEIVE_SMS, PERM_SEND_SMS).firstOrNull { !granted(it) }
        steps += SetupStep(
            id = "sms",
            title = "Allow text messages",
            detail = "QuicLoc reads incoming texts to spot the word \"loc\", and sends the reply " +
                "back by text. Without both, a texted request can't be heard or answered.",
            state = if (smsMissing == null) StepState.DONE else StepState.TODO,
            required = true,
            actionKey = smsMissing ?: PERM_RECEIVE_SMS,
            actionLabel = "Allow",
        )

        val locationMissing = !granted(PERM_FINE_LOCATION) && !granted(PERM_COARSE_LOCATION)
        steps += SetupStep(
            id = "location",
            title = "Allow location",
            detail = "The location that gets sent back as a Google Maps link. Only read while " +
                "answering a request — never in the background, never stored.",
            state = if (locationMissing) StepState.TODO else StepState.DONE,
            required = true,
            actionKey = if (!granted(PERM_FINE_LOCATION)) PERM_FINE_LOCATION else PERM_COARSE_LOCATION,
            actionLabel = "Allow",
        )

        // Only exists on Android 10+; absent from the list below that.
        if (byKey.containsKey(PERM_BACKGROUND_LOCATION)) {
            steps += SetupStep(
                id = "background-location",
                title = "Set location to \"Allow all the time\"",
                detail = "This is the one people miss. Without it QuicLoc only answers while you " +
                    "happen to have the app open on screen — which defeats the point.",
                state = if (granted(PERM_BACKGROUND_LOCATION)) StepState.DONE else StepState.TODO,
                required = true,
                actionKey = PERM_BACKGROUND_LOCATION,
                actionLabel = "Allow",
            )
        }

        // Android 13+ only.
        if (byKey.containsKey(PERM_POST_NOTIFICATIONS)) {
            steps += SetupStep(
                id = "post-notifications",
                title = "Allow notifications",
                detail = "QuicLoc shows a short-lived notification while a reply is being sent, " +
                    "as Android requires for this kind of background work. Without it the reply " +
                    "still goes out, but the notification itself won't show.",
                state = if (granted(PERM_POST_NOTIFICATIONS)) StepState.DONE else StepState.TODO,
                required = true,
                actionKey = PERM_POST_NOTIFICATIONS,
                actionLabel = "Allow",
            )
        }

        steps += SetupStep(
            id = "whitelist",
            title = "Add at least one trusted contact",
            detail = if (whitelistCount > 0 && dialableWhitelistCount == 0)
                "Every contact on your list was added by name only, with no phone number. " +
                    "That can't answer a text or the widget — add one with a real number, " +
                    "e.g. via \"Pick from Contacts\"."
            else
                "Only people on your list can ask. With an empty list QuicLoc ignores " +
                    "everyone, including you.",
            state = if (dialableWhitelistCount > 0) StepState.DONE else StepState.TODO,
            required = true,
            actionKey = PermKeys.ADD_CONTACT,
            actionLabel = "Add",
        )

        // Required, not just recommended: Android's Doze/App Standby can put a
        // sleeping app's broadcast receivers and background work on ice, and a
        // request that arrives while that's happening can simply be missed.
        // For a safety app, "ready" has to mean this is actually exempted, not
        // just that the user hasn't been shown the option yet.
        steps += SetupStep(
            id = "battery",
            title = "Exempt QuicLoc from battery optimisation",
            detail = "Android puts sleeping apps on ice; a request that arrives while QuicLoc is " +
                "asleep can be missed entirely. QuicLoc does no background work of its own, so " +
                "the exemption costs you nothing.",
            state = if (granted(PermKeys.BATTERY)) StepState.DONE else StepState.TODO,
            required = true,
            actionKey = PermKeys.BATTERY,
            actionLabel = "Allow",
        )

        // ---- Recommended, not required -------------------------------------

        steps += SetupStep(
            id = "notification-access",
            title = "Turn on Notification Access",
            detail = "Optional. Adds WhatsApp, Telegram, Signal, Messenger and friends. " +
                "Skip it and only plain text messages work.",
            state = if (granted(PermKeys.NOTIF_LISTENER)) StepState.DONE else StepState.TODO,
            required = false,
            actionKey = PermKeys.NOTIF_LISTENER,
            actionLabel = "Turn on",
        )

        steps += SetupStep(
            id = "app-pin",
            title = "Set a QuicLoc PIN",
            detail = "Optional. A 6-digit PIN that unlocks the app when a fingerprint won't read, " +
                "and encrypts your backup — with no PIN there is no backup, so your contacts " +
                "don't survive a new phone.",
            state = if (pinSet) StepState.DONE else StepState.TODO,
            required = false,
            actionKey = PermKeys.SET_PIN,
            actionLabel = "Set",
        )

        steps += SetupStep(
            id = "my-number",
            title = "Set your own phone number",
            detail = "Optional. Only used by the widget's 2-tap parking reminder, which texts " +
                "your location to you.",
            state = if (myNumber.isNotBlank()) StepState.DONE else StepState.TODO,
            required = false,
            actionKey = PermKeys.MY_NUMBER,
            actionLabel = "Set",
        )

        return steps
    }

    /** Required steps the user still has to do. Zero means QuicLoc will answer. */
    fun requiredRemaining(steps: List<SetupStep>): Int =
        steps.count { it.required && it.state != StepState.DONE }

    /** Recommended steps still outstanding — shown, but never block "ready". */
    fun optionalRemaining(steps: List<SetupStep>): Int =
        steps.count { !it.required && it.state != StepState.DONE }

    /** True when every required step is satisfied. */
    fun isReady(steps: List<SetupStep>): Boolean = requiredRemaining(steps) == 0

    /** One-line headline for the status card. */
    fun headline(steps: List<SetupStep>): String {
        val missing = requiredRemaining(steps)
        return when {
            missing == 0 -> "QuicLoc is ready"
            missing == 1 -> "1 step left before QuicLoc works"
            else -> "$missing steps left before QuicLoc works"
        }
    }
}
