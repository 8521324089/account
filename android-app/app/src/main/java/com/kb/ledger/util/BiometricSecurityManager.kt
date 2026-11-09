package com.kb.ledger.util

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Manages optional application security using Android BiometricPrompt and Device Credentials.
 *
 * Invariants:
 * - Never stores biometric templates, credentials, or pins locally.
 * - Supports device credentials (PIN/Pattern/Password) as fallback alongside fingerprint/face.
 * - Never forces app lock on unsupported hardware.
 * - Prevents infinite re-authentication loops by tracking in-memory unlock state per foreground session.
 */
class BiometricSecurityManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "ledger_security_prefs"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        // Session lock state
        var isSessionUnlocked = false
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAppLockEnabled(): Boolean {
        return prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
    }

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        if (!enabled) {
            isSessionUnlocked = true
        }
    }

    fun canAuthenticate(): Int {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(AUTHENTICATORS)
    }

    fun isBiometricAvailable(): Boolean {
        return when (canAuthenticate()) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Ledger",
        subtitle: String = "Authenticate to access your accounts",
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isSessionUnlocked = true
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // System prompt handles retry UI internally
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        prompt.authenticate(promptInfo)
    }
}
