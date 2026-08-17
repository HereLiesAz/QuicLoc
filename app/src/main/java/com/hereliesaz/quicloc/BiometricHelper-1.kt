package com.hereliesaz.quicloc

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricHelper {

    // Allow fingerprint, face, iris — fall back to PIN/pattern/password
    // if no biometric is enrolled. This way the app is never locked out.
    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * Returns true if this device can authenticate at all.
     * If false, skip biometric gating entirely (don't lock users out on
     * devices with no security set up).
     */
    fun canAuthenticate(activity: FragmentActivity): Boolean {
        val manager = BiometricManager.from(activity)
        return when (manager.canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Show the biometric prompt. Calls [onSuccess] if authenticated,
     * [onFailure] with a human-readable reason if not.
     *
     * Falls back to PIN/pattern/password automatically if no biometric
     * is enrolled — the user will never be hard-locked out.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Verify it's you",
        subtitle: String = "Authenticate to access QuicLoc",
        onSuccess: () -> Unit,
        onFailure: (reason: String) -> Unit
    ) {
        if (!canAuthenticate(activity)) {
            // Device has no lock screen set up at all — let them in
            // but show a warning (handled in MainActivity)
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled or too many attempts — don't let them in
                onFailure(errString.toString())
            }

            override fun onAuthenticationFailed() {
                // Single failed attempt (wrong finger etc.) — prompt stays open,
                // the system handles retry UI, so no action needed here
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        // This is a UI gate on the settings screen, not a CryptoObject-backed
        // unlock of a stored secret — deliberately. The whitelist/PIN data it
        // protects also has to be readable by SmsReceiver/NotificationListener
        // with no UI and no biometric session, to answer a trigger while the
        // screen is off. Binding this prompt to a CryptoObject would make
        // that background path (the app's actual purpose) impossible, in
        // exchange for hardening a much smaller threat (someone with the
        // unlocked phone in hand editing the whitelist) that the OS-level
        // authenticate() result already stops for any non-instrumented
        // attacker.
        // codeql[java/android/insecure-local-authentication]
        prompt.authenticate(promptInfo)
    }
}
