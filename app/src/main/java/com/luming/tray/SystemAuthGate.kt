package com.luming.tray

import android.os.SystemClock
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/**
 * Short-lived system-authentication grant for sensitive credential operations.
 * LuMing never stores a PIN/password/biometric secret; Android owns the authentication UI.
 */
object SystemAuthGate {
    private const val GRANT_MS = 60_000L
    @Volatile private var grantUntilElapsed = 0L

    fun authenticate(
        activity: FragmentActivity,
        reason: String,
        onSuccess: () -> Unit,
        onFailure: ((String) -> Unit)? = null
    ) {
        if (SystemClock.elapsedRealtime() < grantUntilElapsed) {
            onSuccess()
            return
        }

        val executor = Executor { command -> activity.runOnUiThread(command) }
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    grantUntilElapsed = SystemClock.elapsedRealtime() + GRANT_MS
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onFailure?.invoke(errString.toString())
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("确认身份")
            .setSubtitle(reason)
            .setDeviceCredentialAllowed(true)
            .build()
        prompt.authenticate(info)
    }

    fun clearGrant() {
        grantUntilElapsed = 0L
    }
}