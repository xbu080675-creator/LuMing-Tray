package com.luming.tray

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
 * Secrets are encrypted with AES-256-GCM using a non-exportable Android Keystore key. Legacy
 * plaintext values are removed only after a verified encrypted write succeeds; a vendor Keystore
 * failure must never silently destroy the user's only usable credential.
 */
object SecureVault {
    private const val PREFS_NAME = "luming_secure_vault_v1"
    private const val KEY_ALIAS = "luming_tray_aes_gcm_v1"
    private const val FORMAT_PREFIX = "v1"
    private const val KEY_LAST_ERROR = "__last_error"
    private const val KEY_LAST_ERROR_AT = "__last_error_at"

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
            val ok = prefs(context).edit().remove(name).commit()
            if (!ok) markError(context, "remove:$name") else clearError(context)
            return ok
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
            val ok = prefs(context).edit().putString(name, encoded).commit()
            if (!ok) markError(context, "write:$name") else clearError(context)
            ok
        } catch (e: Exception) {
            markError(context, "write:$name:${e.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun get(context: Context, name: String): String {
        val encoded = prefs(context).getString(name, null) ?: return ""
        return try {
            val parts = encoded.split('|', limit = 3)
            if (parts.size != 3 || parts[0] != FORMAT_PREFIX) {
                markError(context, "format:$name")
                return ""
            }

            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(context), GCMParameterSpec(128, iv))
            cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
            val result = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            clearError(context)
            result
        } catch (e: Exception) {
            markError(context, "read:$name:${e.javaClass.simpleName}")
            ""
        }
    }

    fun remove(context: Context, name: String): Boolean =
        prefs(context).edit().remove(name).commit()

    /**
     * One-time migration from legacy plaintext preferences. A plaintext value is deleted only when
     * it is blank or when the encrypted copy can be read back successfully.
     */
    @Synchronized
    fun migrateLegacy(context: Context, legacy: SharedPreferences) {
        val editor = legacy.edit()
        var changed = false
        var allMigrated = true

        for (name in secretKeys) {
            val oldPlaintext = legacy.getString(name, null) ?: continue
            if (oldPlaintext.isBlank()) {
                editor.remove(name)
                changed = true
                continue
            }

            val existing = get(context, name)
            val migrated = if (existing == oldPlaintext) {
                true
            } else {
                put(context, name, oldPlaintext) && get(context, name) == oldPlaintext
            }

            if (migrated) {
                editor.remove(name)
                changed = true
            } else {
                // Keep the legacy value for a future retry. Losing a credential is worse than
                // temporarily retaining the already-existing legacy plaintext.
                allMigrated = false
                markError(context, "migration:$name")
            }
        }

        if (changed || !allMigrated) {
            editor.putBoolean("secureVaultMigratedV1", allMigrated).commit()
        }
    }

    fun lastError(context: Context): String =
        prefs(context).getString(KEY_LAST_ERROR, "").orEmpty()

    fun lastErrorAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ERROR_AT, 0L)

    fun isStrongBoxAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    fun securityLabel(context: Context): String =
        if (isStrongBoxAvailable(context)) {
            "Android Keystore · AES-256-GCM · StrongBox 优先"
        } else {
            "Android Keystore · AES-256-GCM"
        }

    private fun markError(context: Context, message: String) {
        prefs(context).edit()
            .putString(KEY_LAST_ERROR, message.take(160))
            .putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())
            .commit()
    }

    private fun clearError(context: Context) {
        if (!prefs(context).contains(KEY_LAST_ERROR)) return
        prefs(context).edit().remove(KEY_LAST_ERROR).remove(KEY_LAST_ERROR_AT).commit()
    }

    private fun getOrCreateKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        if (Build.VERSION.SDK_INT >= 28 && isStrongBoxAvailable(context)) {
            try {
                return generateKey(strongBox = true)
            } catch (_: Exception) {
                // Some vendors advertise StrongBox but fail during key generation. Fall back.
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
