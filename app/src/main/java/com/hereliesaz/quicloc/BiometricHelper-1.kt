package com.hereliesaz.quicloc

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object BiometricHelper {

    private const val TAG = "QuicLoc.BiometricHelper"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "quicloc_biometric_gate"
    private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"

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
     *
     * This is a UI gate on the settings screen, not an unlock of a stored
     * secret — the whitelist/PIN data it protects also has to be readable by
     * SmsReceiver/NotificationListener with no UI and no biometric session,
     * to answer a trigger while the screen is off, so the gate can't be the
     * *only* thing standing between an attacker and that data the way a
     * CryptoObject normally implies. What a bare `authenticate(promptInfo)`
     * call (no CryptoObject) *is* missing, though, is tamper resistance: the
     * "succeeded" callback is a plain method invocation an attacker with
     * instrumentation (Frida/Xposed on a rooted or debuggable device) could
     * call directly, bypassing the prompt entirely. [cryptoGate], when
     * available, closes that gap — the callback only fires with a
     * Keystore-backed [BiometricPrompt.CryptoObject] the OS itself refuses
     * to authorize without a genuine match, which is far harder to forge
     * from userspace. It protects nothing else; the cipher inside is never
     * used to encrypt or decrypt anything.
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

        val crypto = cryptoGate()
        if (crypto != null) {
            prompt.authenticate(promptInfo, crypto)
        } else {
            // No key available (below API 30 — see cryptoGate's doc — or a
            // Keystore/cipher error). Never a reason to lock the user out:
            // fall back to the same plain prompt this always used.
            prompt.authenticate(promptInfo)
        }
    }

    /**
     * Builds a [BiometricPrompt.CryptoObject] wrapping a dedicated,
     * Keystore-resident AES key that exists solely to make [authenticate]
     * tamper-resistant — see that function's doc. Returns null (caller falls
     * back to a non-crypto prompt) when this can't be done safely rather
     * than ever risking a lock-out:
     *
     *   - Below API 30: the platform doesn't support combining a
     *     `DEVICE_CREDENTIAL`-inclusive authenticator set with a
     *     `CryptoObject` at all (`BiometricPrompt` throws
     *     `IllegalArgumentException` if asked to). Since [AUTHENTICATORS]
     *     always includes `DEVICE_CREDENTIAL` — dropping it here to force
     *     the crypto path would defeat the whole "never lock out a user
     *     with no biometric enrolled" design — this path is simply
     *     unavailable pre-API 30, same as today's plain-prompt behavior.
     *   - The Keystore key was permanently invalidated (new fingerprint/face
     *     enrolled since it was created) — deletes it so the next call
     *     generates a fresh one, and falls back for *this* call.
     *   - Any other Keystore/cipher failure — logged, falls back.
     */
    private fun cryptoGate(): BiometricPrompt.CryptoObject? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            BiometricPrompt.CryptoObject(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "Biometric gate key invalidated (new enrollment) — regenerating for next attempt", e)
            deleteKey()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not prepare crypto-backed biometric gate — falling back to plain prompt", e)
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            // 0-second timeout = a fresh authentication is required for
            // every single use, matching BiometricPrompt's own per-call
            // model. AUTH_DEVICE_CREDENTIAL alongside AUTH_BIOMETRIC_STRONG
            // is what makes this key acceptable to unlock via the device
            // credential fallback too (API 30+ only — see cryptoGate).
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun deleteKey() {
        try {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete invalidated biometric gate key", e)
        }
    }
}
