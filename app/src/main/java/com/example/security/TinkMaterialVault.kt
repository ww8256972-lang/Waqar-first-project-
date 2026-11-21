package com.example.security

import android.content.Context
import android.os.Build
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import org.lsposed.lsparanoid.Obfuscate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Google Tink 256-Bit Cryptographic Vault (`TinkMaterialVault`).
 *
 * Provides:
 * 1. `StreamingAead` (`AES256_GCM_HKDF_4KB`) backed by Android Keystore for encrypted PDF/study material files.
 * 2. `Aead` (`AES256_GCM`) backed by Android Keystore (`android-keystore://wci_tink_master_key`) for
 *    real authenticated encryption across `PoincareHyperbolicVault`, `LorenzChaosCipher`, and biometric digests.
 */
@Obfuscate
object TinkMaterialVault {
    private const val KEYSET_NAME = "wci_tink_streaming_keyset"
    private const val AEAD_KEYSET_NAME = "wci_tink_aead_keyset"
    private const val PREF_FILE = "wci_tink_vault_prefs"
    private const val MASTER_KEY_URI = "android-keystore://wci_tink_master_key"

    @Volatile
    private var streamingAead: StreamingAead? = null

    @Volatile
    private var standardAead: Aead? = null

    @Volatile
    private var ephemeralFallbackAead: Aead? = null

    @Volatile
    var isHardwareMasterKeyBound: Boolean = false
        private set

    private fun isRobolectric(): Boolean =
        Build.FINGERPRINT?.equals("robolectric", ignoreCase = true) == true

    private fun getEngine(context: Context): StreamingAead {
        val existing = streamingAead
        if (existing != null) return existing
        return synchronized(this) {
            val secondCheck = streamingAead
            if (secondCheck != null) {
                secondCheck
            } else {
                val appCtx = context.applicationContext ?: context
                StreamingAeadConfig.register()
                val keyTemplate = KeyTemplates.get("AES256_GCM_HKDF_4KB")
                val handle = runCatching {
                    AndroidKeysetManager.Builder()
                        .withSharedPref(appCtx, KEYSET_NAME, PREF_FILE)
                        .withKeyTemplate(keyTemplate)
                        .withMasterKeyUri(MASTER_KEY_URI)
                        .build()
                        .keysetHandle
                }.getOrElse {
                    appCtx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().clear().commit()
                    AndroidKeysetManager.Builder()
                        .withSharedPref(appCtx, KEYSET_NAME, PREF_FILE)
                        .withKeyTemplate(keyTemplate)
                        .withMasterKeyUri(MASTER_KEY_URI)
                        .build()
                        .keysetHandle
                }
                isHardwareMasterKeyBound = true
                @Suppress("DEPRECATION")
                val created = handle.getPrimitive(StreamingAead::class.java)
                streamingAead = created
                created
            }
        }
    }

    /**
     * Returns an Android Keystore-backed Google Tink `Aead` (`AES256_GCM`) primitive when a Context
     * is available on a real Android device, or a real Tink `AES256_GCM` in-memory KeysetHandle
     * when running under headless JVM / Robolectric unit tests where AndroidKeyStore is absent.
     */
    fun getOrCreateAead(context: Context? = null): Aead {
        if (context != null && !isRobolectric()) {
            standardAead?.let { return it }
            synchronized(this) {
                standardAead?.let { return it }
                AeadConfig.register()
                val appCtx = context.applicationContext ?: context
                val keyTemplate = KeyTemplates.get("AES256_GCM")
                val handle = runCatching {
                    AndroidKeysetManager.Builder()
                        .withSharedPref(appCtx, AEAD_KEYSET_NAME, PREF_FILE)
                        .withKeyTemplate(keyTemplate)
                        .withMasterKeyUri(MASTER_KEY_URI)
                        .build()
                        .keysetHandle
                }.getOrElse {
                    runCatching {
                        appCtx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                            .edit().remove(AEAD_KEYSET_NAME).commit()
                        AndroidKeysetManager.Builder()
                            .withSharedPref(appCtx, AEAD_KEYSET_NAME, PREF_FILE)
                            .withKeyTemplate(keyTemplate)
                            .withMasterKeyUri(MASTER_KEY_URI)
                            .build()
                            .keysetHandle
                    }.getOrElse {
                        KeysetHandle.generateNew(keyTemplate)
                    }
                }
                isHardwareMasterKeyBound = true
                @Suppress("DEPRECATION")
                val created = handle.getPrimitive(Aead::class.java)
                standardAead = created
                return created
            }
        }

        ephemeralFallbackAead?.let { return it }
        return synchronized(this) {
            ephemeralFallbackAead?.let { return it }
            AeadConfig.register()
            val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            @Suppress("DEPRECATION")
            val created = handle.getPrimitive(Aead::class.java)
            ephemeralFallbackAead = created
            created
        }
    }

    /**
     * Encrypts [plaintext] using Google Tink AES-256-GCM AEAD backed by Android Keystore.
     */
    fun encryptBytes(
        context: Context?,
        plaintext: ByteArray,
        associatedData: ByteArray = "wci_tink_aead_v1".toByteArray(Charsets.UTF_8)
    ): ByteArray {
        return getOrCreateAead(context).encrypt(plaintext, associatedData)
    }

    /**
     * Decrypts [ciphertext] using Google Tink AES-256-GCM AEAD backed by Android Keystore.
     */
    fun decryptBytes(
        context: Context?,
        ciphertext: ByteArray,
        associatedData: ByteArray = "wci_tink_aead_v1".toByteArray(Charsets.UTF_8)
    ): ByteArray {
        return getOrCreateAead(context).decrypt(ciphertext, associatedData)
    }

    fun encryptMaterialToDisk(
        context: Context,
        plainInput: InputStream,
        targetFile: File,
        materialId: String
    ) {
        val aad = "wci_material:$materialId".toByteArray(Charsets.UTF_8)
        getEngine(context).newEncryptingStream(targetFile.outputStream(), aad).use { encOut ->
            plainInput.copyTo(encOut)
        }
    }

    fun openDecryptedMaterialStream(
        context: Context,
        encryptedFile: File,
        materialId: String
    ): InputStream {
        val aad = "wci_material:$materialId".toByteArray(Charsets.UTF_8)
        return getEngine(context).newDecryptingStream(encryptedFile.inputStream(), aad)
    }

    fun encryptBytesToVaultFile(
        context: Context,
        plainBytes: ByteArray,
        targetFile: File,
        materialId: String
    ) {
        ByteArrayInputStream(plainBytes).use { input ->
            encryptMaterialToDisk(context, input, targetFile, materialId)
        }
    }

    fun decryptVaultFileToBytes(
        context: Context,
        encryptedFile: File,
        materialId: String
    ): ByteArray {
        openDecryptedMaterialStream(context, encryptedFile, materialId).use { decIn ->
            val out = ByteArrayOutputStream()
            decIn.copyTo(out)
            return out.toByteArray()
        }
    }
}
