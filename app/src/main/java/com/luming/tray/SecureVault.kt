package com.luming.tray

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound encrypted storage for API/session secrets.
 *
 * Secrets are never written to SharedPreferences in plaintext. Values are encrypted with
 * AES-256-GCM using a non-exportable key held by Android Keystore. On devices with StrongBox,
 * key generation is attempted there first and transparently falls back to the normal Android
 * hardware-backed/TEE Keystore if StrongBox is unavailable.
 */
object SecureVault {
    private const val PREFS_NAME = "luming_secure_vault_v1"
    private const val KEY_ALIAS = "luming_tray_aes_gcm_v1"
    private const val FORMAT_PREFIX = "v1"

    val secretKeys = listOf(
        "apiKey",
        "accessToken",
        "consoleCookie",
        "webAuthToken",
        "webRefreshToken"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun put(context: Context, name: String, plaintext: String): Boolean {
        if (plaintext.isBlank()) {
            remove(context, name)
            return true
        }

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(context))
            cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val encoded = buildString {
                append(FORMAT_PREFIX)
                append('|')
                append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                append('|')
                append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            }
            prefs(context).edit().putString(name, encoded).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun get(context: Context, name: String): String {
        val encoded = prefs(context).getString(name, null) ?: return ""
        return try {
            val parts = encoded.split('|', limit = 3)
            if (parts.size != 3 || parts[0] != FORMAT_PREFIX) return ""

            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(context), GCMParameterSpec(128, iv))
            cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun remove(context: Context, name: String) {
        prefs(context).edit().remove(name).apply()
    }

    /**
     * One-time migration from the old plaintext preference keys.
     * If encryption unexpectedly fails, the plaintext value is still deleted rather than kept
     * around indefinitely; the user can simply log in again.
     */
    @Synchronized
    fun migrateLegacy(context: Context, legacy: SharedPreferences) {
        val editor = legacy.edit()
        var changed = false

        for (name in secretKeys) {
            val oldPlaintext = legacy.getString(name, null)
            if (oldPlaintext != null) {
                if (oldPlaintext.isNotBlank() && get(context, name).isBlank()) {
                    put(context, name, oldPlaintext)
                }
                editor.remove(name)
                changed = true
            }
        }

        if (changed) {
            editor.putBoolean("secureVaultMigratedV1", true).apply()
        }
    }

    fun isStrongBoxAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    fun securityLabel(context: Context): String =
        if (isStrongBoxAvailable(context)) {
            "Android Keystore · AES-256-GCM · StrongBox 优先"
        } else {
            "Android Keystore · AES-256-GCM"
        }

    private fun getOrCreateKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        if (Build.VERSION.SDK_INT >= 28 && isStrongBoxAvailable(context)) {
            try {
                return generateKey(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // Fall through to the regular Android Keystore.
            } catch (_: Exception) {
                // Some vendors advertise StrongBox but fail during key generation.
            }
        }

        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= 28 && strongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        generator.init(builder.build())
        return generator.generateKey()
    }
}
