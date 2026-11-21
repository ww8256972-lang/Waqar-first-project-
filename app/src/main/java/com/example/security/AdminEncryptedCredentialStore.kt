package com.example.security

import android.content.Context
import android.content.SharedPreferences
import com.example.auth.Argon2CredentialHasher
import org.lsposed.lsparanoid.Obfuscate
import java.security.MessageDigest
import java.util.Arrays

/**
 * Hardware-Backed Admin Credential Store (`AdminEncryptedCredentialStore.kt`).
 *
 * Security Guarantees:
 * 1. ZERO hardcoded default passwords or unsalted SHA-256 digests anywhere in the APK.
 * 2. Requires explicit First-Run Admin Setup (`completeFirstRunAdminSetup`) or Admin Settings
 *    update (`saveUpdatedAdminCredentials`), hashing passwords exclusively with memory-hard
 *    Argon2id (RFC 9106 + 16-byte CSPRNG salt) stored inside Android Keystore-backed
 *    `EncryptedSharedPreferences`.
 * 3. Supports runtime Admin username & password rotation from Admin Settings with constant-time
 *    verification (`Argon2CredentialHasher.verifyPasswordConstantTime`).
 */
@Obfuscate
object AdminEncryptedCredentialStore {

    private const val PLACEHOLDER_ADMIN_USERNAME = "waqar"
    private const val KEY_ADMIN_USERNAME = "wci_sec_admin_username_v3"
    private const val KEY_ADMIN_PASSWORD_ARGON2 = "wci_sec_admin_pwd_argon2_v3"
    private const val KEY_ADMIN_PREVIOUS_USERNAME = "wci_sec_admin_prev_username_v3"
    private const val KEY_ADMIN_CUSTOMIZED = "wci_sec_admin_customized_v3"
    private const val KEY_ADMIN_UPDATED_AT_MS = "wci_sec_admin_updated_at_ms_v3"
    private const val LEGACY_KEY_SHA256 = "wci_sec_admin_pwd_sha256_v2"

    sealed class AdminVerifyResult {
        data class Verified(
            val username: String,
            val isCustomized: Boolean
        ) : AdminVerifyResult()

        data class PasswordMismatch(
            val isCustomized: Boolean
        ) : AdminVerifyResult()

        data class UsernameNotMatched(
            val activeAdminUsername: String,
            val isCustomized: Boolean
        ) : AdminVerifyResult()

        data object NotConfigured : AdminVerifyResult()
    }

    private fun openVaultPrefs(context: Context): SharedPreferences =
        HardwareKeystoreVault.openEncryptedPreferences(context.applicationContext ?: context)

    fun normalizeAdminUsername(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_.\\-@]"), "_")

    fun sha256Hex(raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        return try {
            sha256Hex(bytes)
        } finally {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    fun sha256Hex(rawBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawBytes)
        return try {
            digest.joinToString("") { "%02x".format(it) }
        } finally {
            Arrays.fill(digest, 0.toByte())
        }
    }

    fun sha256HexChars(passwordChars: CharArray): String {
        val utf8 = String(passwordChars).toByteArray(Charsets.UTF_8)
        return try {
            sha256Hex(utf8)
        } finally {
            Arrays.fill(utf8, 0.toByte())
        }
    }

    /**
     * Purges any legacy unsalted SHA-256 entries without seeding any default credential.
     */
    fun ensureInitialized(context: Context) {
        val prefs = openVaultPrefs(context)
        if (prefs.contains(LEGACY_KEY_SHA256)) {
            prefs.edit().remove(LEGACY_KEY_SHA256).commit()
        }
    }

    /**
     * Returns `true` only after an explicit First-Run Admin Setup or Admin Settings password save
     * has stored a valid Argon2id hash in Android Keystore-backed EncryptedSharedPreferences.
     */
    fun isAdminConfigured(context: Context): Boolean {
        ensureInitialized(context)
        val prefs = openVaultPrefs(context)
        val username = prefs.getString(KEY_ADMIN_USERNAME, null)
        val hash = prefs.getString(KEY_ADMIN_PASSWORD_ARGON2, null)
        return !username.isNullOrBlank() && !hash.isNullOrBlank() && Argon2CredentialHasher.isArgon2idFormat(hash)
    }

    fun getActiveAdminUsername(context: Context): String {
        ensureInitialized(context)
        return openVaultPrefs(context)
            .getString(KEY_ADMIN_USERNAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: PLACEHOLDER_ADMIN_USERNAME
    }

    fun isCustomizedByAdmin(context: Context): Boolean {
        ensureInitialized(context)
        return isAdminConfigured(context) && openVaultPrefs(context).getBoolean(KEY_ADMIN_CUSTOMIZED, false)
    }

    fun getPreviousAdminUsername(context: Context): String? {
        return openVaultPrefs(context).getString(KEY_ADMIN_PREVIOUS_USERNAME, null)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Completes interactive First-Run Admin Setup using memory-hard Argon2id + 16-byte CSPRNG salt.
     */
    fun completeFirstRunAdminSetup(
        context: Context,
        adminUsername: String,
        passwordChars: CharArray
    ): Pair<String, String> {
        require(passwordChars.size >= 8) {
            "First-run Admin password must be at least 8 characters."
        }
        return saveUpdatedAdminCredentials(context, adminUsername, passwordChars)
    }

    /**
     * Constant-time Argon2id verification against Keystore-backed `EncryptedSharedPreferences`.
     * Returns [AdminVerifyResult.NotConfigured] if First-Run Admin Setup has not been completed yet
     * (never falls back to any hardcoded default password hash).
     */
    fun verifyAdminCredentialsInstant(
        context: Context,
        rawUsername: String,
        passwordChars: CharArray
    ): AdminVerifyResult {
        ensureInitialized(context)
        val prefs = openVaultPrefs(context)
        val storedUsername = prefs.getString(KEY_ADMIN_USERNAME, null)?.takeIf { it.isNotBlank() }
        val storedArgon2 = prefs.getString(KEY_ADMIN_PASSWORD_ARGON2, null)
            ?.takeIf { Argon2CredentialHasher.isArgon2idFormat(it) }

        if (storedUsername == null || storedArgon2 == null) {
            return AdminVerifyResult.NotConfigured
        }

        val customized = prefs.getBoolean(KEY_ADMIN_CUSTOMIZED, true)
        val cleanInputUser = normalizeAdminUsername(rawUsername)
        if (cleanInputUser != storedUsername) {
            return AdminVerifyResult.UsernameNotMatched(
                activeAdminUsername = storedUsername,
                isCustomized = customized
            )
        }

        val matched = Argon2CredentialHasher.verifyPasswordConstantTime(
            passwordChars = passwordChars,
            storedEncodedHash = storedArgon2
        )
        return if (matched) {
            AdminVerifyResult.Verified(username = storedUsername, isCustomized = customized)
        } else {
            AdminVerifyResult.PasswordMismatch(isCustomized = customized)
        }
    }

    /**
     * Updates Admin username and new password's salted Argon2id hash inside
     * `EncryptedSharedPreferences` / Android Keystore.
     */
    fun saveUpdatedAdminCredentials(
        context: Context,
        newUsername: String,
        newPasswordChars: CharArray,
        previousUsernameHint: String? = null
    ): Pair<String, String> {
        ensureInitialized(context)
        val prefs = openVaultPrefs(context)
        val prevUser = prefs.getString(KEY_ADMIN_USERNAME, null)?.takeIf { it.isNotBlank() }
            ?: previousUsernameHint?.let(::normalizeAdminUsername)?.takeIf { it.isNotBlank() }
        val cleanNewUser = normalizeAdminUsername(newUsername)
        require(cleanNewUser.length >= 3) { "Admin username must be at least 3 characters." }
        require(newPasswordChars.size >= 4) { "Admin password must be at least 4 characters." }

        val argonDigest = Argon2CredentialHasher.hashPasswordArgon2id(newPasswordChars)
        val editor = prefs.edit()
            .remove(LEGACY_KEY_SHA256)
            .putString(KEY_ADMIN_USERNAME, cleanNewUser)
            .putString(KEY_ADMIN_PASSWORD_ARGON2, argonDigest.encodedHash)
            .putBoolean(KEY_ADMIN_CUSTOMIZED, true)
            .putLong(KEY_ADMIN_UPDATED_AT_MS, System.currentTimeMillis())

        if (!prevUser.isNullOrBlank() && prevUser != cleanNewUser) {
            editor.putString(KEY_ADMIN_PREVIOUS_USERNAME, prevUser)
        }
        editor.commit()

        return cleanNewUser to argonDigest.encodedHash
    }

    /**
     * Clears Admin credential store back to an unconfigured first-run state for unit tests.
     */
    internal fun resetToDefaultForTest(context: Context) {
        openVaultPrefs(context).edit()
            .remove(KEY_ADMIN_PREVIOUS_USERNAME)
            .remove(KEY_ADMIN_USERNAME)
            .remove(KEY_ADMIN_PASSWORD_ARGON2)
            .remove(LEGACY_KEY_SHA256)
            .putBoolean(KEY_ADMIN_CUSTOMIZED, false)
            .commit()
    }
}
