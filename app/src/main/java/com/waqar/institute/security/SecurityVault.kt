package com.waqar.institute.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.security.HardwareKeystoreVault
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import android.util.Base64
import java.security.SecureRandom

/**
 * Standalone Hardware-Backed Encrypted Vault (`SecurityVault.kt`).
 *
 * Capabilities:
 * 1. Hardware-backed `MasterKey` (`AES256_GCM`) preferring StrongBox Keymaster when available on the
 *    device, with automatic Trusted Execution Environment (TEE) fallback.
 * 2. `EncryptedSharedPreferences` (`PrefKeyEncryptionScheme.AES256_SIV`, `PrefValueEncryptionScheme.AES256_GCM`)
 *    for encrypted local key-value storage.
 * 3. SQLCipher 256-bit AES `SupportFactory` builder for Room databases using a 32-byte random passphrase
 *    sealed inside the Hardware Keystore vault.
 */
object SecurityVault {

    private const val VAULT_FILE_NAME = "wci_hardware_security_vault_prefs"
    private const val KEY_SQLCIPHER_PASSPHRASE_B64 = "wci_sqlcipher_aes256_key_b64"

    /**
     * Builds or retrieves an Android Keystore `MasterKey` backed by StrongBox (if supported by hardware)
     * or the device's Trusted Execution Environment (TEE).
     */
    fun getOrCreateHardwareMasterKey(context: Context): MasterKey {
        val appContext = context.applicationContext ?: context
        val hasStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } else {
            false
        }

        if (hasStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val strongBoxKey = runCatching {
                MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setRequestStrongBoxBacked(true)
                    .build()
            }.getOrNull()
            if (strongBoxKey != null) return strongBoxKey
        }

        return MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Opens the AES-256 `EncryptedSharedPreferences` instance backed by the Hardware Keystore.
     */
    fun openEncryptedPrefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return runCatching {
            val masterKey = getOrCreateHardwareMasterKey(appContext)
            EncryptedSharedPreferences.create(
                appContext,
                VAULT_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            HardwareKeystoreVault.openEncryptedPreferences(appContext)
        }
    }

    /**
     * Securely stores an encrypted String secret inside the hardware-backed vault.
     */
    fun putSecureString(context: Context, key: String, value: String) {
        if (key.isBlank()) return
        openEncryptedPrefs(context).edit().putString(key.trim(), value).apply()
    }

    /**
     * Reads and decrypts a String secret from the hardware-backed vault.
     */
    fun getSecureString(context: Context, key: String, defaultValue: String? = null): String? {
        if (key.isBlank()) return defaultValue
        return openEncryptedPrefs(context).getString(key.trim(), defaultValue)
    }

    /**
     * Removes a secret key from the hardware-backed vault.
     */
    fun removeSecureKey(context: Context, key: String) {
        if (key.isBlank()) return
        openEncryptedPrefs(context).edit().remove(key.trim()).apply()
    }

    /**
     * Returns or generates a 256-bit (32-byte) cryptographic passphrase for SQLCipher Room databases,
     * stored inside `EncryptedSharedPreferences`.
     */
    fun getOrCreateSqlCipherPassphraseBytes(context: Context): ByteArray {
        val prefs = openEncryptedPrefs(context)
        val existingB64 = prefs.getString(KEY_SQLCIPHER_PASSPHRASE_B64, null)
        if (!existingB64.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(existingB64, Base64.NO_WRAP) }.getOrNull()
            if (decoded != null && decoded.size >= 32) {
                return decoded
            }
        }
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val encoded = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
        prefs.edit().putString(KEY_SQLCIPHER_PASSPHRASE_B64, encoded).apply()
        return randomBytes
    }

    /**
     * Creates a SQLCipher 4.6.1 `SupportOpenHelperFactory` for encrypting Room databases with AES-256.
     */
    fun createSqlCipherSupportFactory(context: Context): SupportOpenHelperFactory {
        val appContext = context.applicationContext ?: context
        runCatching { System.loadLibrary("sqlcipher") }
        val passphrase = getOrCreateSqlCipherPassphraseBytes(appContext)
        return SupportOpenHelperFactory(passphrase)
    }
}
