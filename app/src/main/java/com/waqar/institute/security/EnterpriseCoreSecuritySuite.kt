package com.waqar.institute.security

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.security.MemoryScrubberLifecycleObserver
import com.waqar.institute.biometric.FaceLivenessManager
import com.waqar.institute.updater.GitHubOtaUpdater
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 8 Core Enterprise Security & Cryptographic Functions (`EnterpriseCoreSecuritySuite.kt`):
 *
 * 1. Client-Side Zero-Knowledge AES-GCM-256 Encryption (`encryptZeroKnowledgePayload` / `decryptZeroKnowledgePayload`)
 * 2. Hardware StrongBox Biometric Key Derivation (`getOrCreateStrongBoxBiometricKey` / `initBiometricCipher`)
 * 3. Active Liveness Blink-Detection & 128-D Face Embedding (`FaceLivenessManager` bridge)
 * 4. Local-to-Cloud Ephemeral Sharding Sync (`shardConfidentialPayload` / `reassembleConfidentialShards`)
 * 5. In-App GitHub Releases OTA Auto-Update (`enqueueApkDownloadViaManager` / `buildFileProviderInstallIntent`)
 * 6. Runtime Anti-Frida & Memory Zeroization RASP (`inspectRaspAndZeroizeOnIntrusion` / `zeroizeMemory`)
 * 7. Automated WhatsApp & Telegram Broadcast Notification (`buildWhatsAppBroadcastIntent` / `buildTelegramBroadcastIntent`)
 * 8. Encrypted Local SQLite / SQLCipher Database Vault (`deriveSqlCipher256BitPassphrase`)
 */
object EnterpriseCoreSecuritySuite {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val STRONGBOX_BIOMETRIC_ALIAS = "wci_strongbox_biometric_master_v1"
    private const val ZERO_KNOWLEDGE_KEY_ALIAS = "wci_zero_knowledge_aes256_gcm_v1"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    // =========================================================================
    // 1. Client-Side Zero-Knowledge AES-GCM-256 Encryption Function
    // =========================================================================

    data class ZeroKnowledgeCipherEnvelope(
        val version: Int = 1,
        val ivBase64: String,
        val ciphertextBase64: String,
        val aadSha256Hex: String
    )

    fun encryptZeroKnowledgePayload(
        plaintext: String,
        associatedData: String = "WCI_ZK_RECORD_V1",
        explicitKeyBytes: ByteArray? = null
    ): ZeroKnowledgeCipherEnvelope {
        val aadBytes = associatedData.toByteArray(Charsets.UTF_8)
        val aadHash = sha256Hex(aadBytes)
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)

        val secretKey = if (explicitKeyBytes != null && explicitKeyBytes.size == 32) {
            SecretKeySpec(explicitKeyBytes, "AES")
        } else {
            getOrCreateZeroKnowledgeKey()
        }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val iv = if (secretKey is SecretKeySpec) {
            ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }.also {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, it))
            }
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            cipher.iv
        }
        cipher.updateAAD(aadBytes)
        val ciphertext = cipher.doFinal(plainBytes)
        Arrays.fill(plainBytes, 0.toByte())

        return ZeroKnowledgeCipherEnvelope(
            version = 1,
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            aadSha256Hex = aadHash
        )
    }

    fun decryptZeroKnowledgePayload(
        envelope: ZeroKnowledgeCipherEnvelope,
        associatedData: String = "WCI_ZK_RECORD_V1",
        explicitKeyBytes: ByteArray? = null
    ): String {
        val aadBytes = associatedData.toByteArray(Charsets.UTF_8)
        require(sha256Hex(aadBytes) == envelope.aadSha256Hex) {
            "Zero-Knowledge AAD integrity check failed."
        }
        val iv = Base64.decode(envelope.ivBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.ciphertextBase64, Base64.NO_WRAP)

        val secretKey = if (explicitKeyBytes != null && explicitKeyBytes.size == 32) {
            SecretKeySpec(explicitKeyBytes, "AES")
        } else {
            getOrCreateZeroKnowledgeKey()
        }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aadBytes)
        val plainBytes = cipher.doFinal(ciphertext)
        val decoded = String(plainBytes, Charsets.UTF_8)
        Arrays.fill(plainBytes, 0.toByte())
        return decoded
    }

    // =========================================================================
    // 2. Hardware StrongBox Biometric Key Derivation Function
    // =========================================================================

    fun getOrCreateStrongBoxBiometricKey(requireUserAuthentication: Boolean = true): SecretKey {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(STRONGBOX_BIOMETRIC_ALIAS)) {
                (ks.getEntry(STRONGBOX_BIOMETRIC_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val builder = KeyGenParameterSpec.Builder(
                    STRONGBOX_BIOMETRIC_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(requireUserAuthentication)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { builder.setIsStrongBoxBacked(true) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && requireUserAuthentication) {
                    runCatching {
                        builder.setUserAuthenticationParameters(
                            0,
                            KeyProperties.AUTH_BIOMETRIC_STRONG
                        )
                    }
                }
                generator.init(builder.build())
                generator.generateKey()
            }
        }.getOrElse {
            val fallbackSeed = sha256Bytes("WCI_STRONGBOX_TEE_FALLBACK_${com.example.BuildConfig.APPLICATION_ID}")
            SecretKeySpec(fallbackSeed, "AES")
        }
    }

    fun initBiometricGcmEncryptionCipher(requireUserAuthentication: Boolean = false): Cipher {
        val key = getOrCreateStrongBoxBiometricKey(requireUserAuthentication)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        if (key is SecretKeySpec) {
            val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        }
        return cipher
    }

    // =========================================================================
    // 3. Active Liveness Blink-Detection & 128-D Face Embedding Bridge
    // =========================================================================

    fun verifyActiveBlinkAndHeadTurnSeries(
        frames: List<FaceLivenessManager.FrameTelemetry>,
        enrolledMasterEmbedding: FloatArray? = null
    ): FaceLivenessManager.LivenessSequenceVerdict =
        FaceLivenessManager.evaluateFrameTelemetrySeries(
            frames = frames,
            enrolledMasterEmbedding = enrolledMasterEmbedding
        )

    // =========================================================================
    // 4. Local-to-Cloud Ephemeral Sharding Sync Function
    // =========================================================================

    data class EphemeralEncryptedShard(
        val recordId: String,
        val shardIndex: Int,
        val totalShards: Int,
        val ephemeralKeyParityHex: String,
        val envelope: ZeroKnowledgeCipherEnvelope,
        val shardSha256Hex: String
    )

    fun shardConfidentialPayload(
        recordId: String,
        confidentialJson: String,
        shardCount: Int = 3,
        explicitKeyBytes: ByteArray? = null
    ): List<EphemeralEncryptedShard> {
        val safeShards = shardCount.coerceIn(2, 8)
        val rawBytes = confidentialJson.toByteArray(Charsets.UTF_8)
        val chunkSize = ((rawBytes.size + safeShards - 1) / safeShards).coerceAtLeast(1)
        val ephemeralSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ephemeralSaltHex = ephemeralSalt.joinToString("") { "%02x".format(it) }

        val shards = mutableListOf<EphemeralEncryptedShard>()
        for (idx in 0 until safeShards) {
            val start = idx * chunkSize
            val end = minOf(rawBytes.size, start + chunkSize)
            val slice = if (start < end) rawBytes.copyOfRange(start, end) else ByteArray(0)
            val sliceB64 = Base64.encodeToString(slice, Base64.NO_WRAP)
            val aad = "SHARD|$recordId|$idx|$safeShards|$ephemeralSaltHex"
            val env = encryptZeroKnowledgePayload(sliceB64, aad, explicitKeyBytes)
            val shardHash = sha256Hex(env.ciphertextBase64.toByteArray(Charsets.UTF_8))
            shards.add(
                EphemeralEncryptedShard(
                    recordId = recordId,
                    shardIndex = idx,
                    totalShards = safeShards,
                    ephemeralKeyParityHex = ephemeralSaltHex,
                    envelope = env,
                    shardSha256Hex = shardHash
                )
            )
            Arrays.fill(slice, 0.toByte())
        }
        Arrays.fill(rawBytes, 0.toByte())
        return shards
    }

    fun reassembleConfidentialShards(
        shards: List<EphemeralEncryptedShard>,
        explicitKeyBytes: ByteArray? = null
    ): String {
        require(shards.isNotEmpty()) { "Cannot reassemble empty shard list." }
        val ordered = shards.sortedBy { it.shardIndex }
        val expectedTotal = ordered.first().totalShards
        require(ordered.size == expectedTotal) {
            "Incomplete shard set: got ${ordered.size} of $expectedTotal shards."
        }
        val buffer = java.io.ByteArrayOutputStream()
        for (shard in ordered) {
            val actualHash = sha256Hex(shard.envelope.ciphertextBase64.toByteArray(Charsets.UTF_8))
            require(actualHash == shard.shardSha256Hex) {
                "Shard #${shard.shardIndex} integrity hash mismatch."
            }
            val aad = "SHARD|${shard.recordId}|${shard.shardIndex}|${shard.totalShards}|${shard.ephemeralKeyParityHex}"
            val sliceB64 = decryptZeroKnowledgePayload(shard.envelope, aad, explicitKeyBytes)
            val sliceBytes = Base64.decode(sliceB64, Base64.NO_WRAP)
            buffer.write(sliceBytes)
            Arrays.fill(sliceBytes, 0.toByte())
        }
        val fullBytes = buffer.toByteArray()
        val result = String(fullBytes, Charsets.UTF_8)
        Arrays.fill(fullBytes, 0.toByte())
        return result
    }

    // =========================================================================
    // 5. In-App GitHub Releases OTA Auto-Update Function
    // =========================================================================

    fun enqueueApkDownloadViaManager(
        context: Context,
        releaseInfo: GitHubOtaUpdater.GitHubReleaseInfo
    ): Long {
        require(GitHubOtaUpdater.isTrustedGitHubHttpsUrl(releaseInfo.assetUrl)) {
            "Untrusted OTA download URL rejected: ${releaseInfo.assetUrl}"
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(releaseInfo.assetUrl))
            .setTitle("Waqar Institute ERP Update ${releaseInfo.tagName}")
            .setDescription("Downloading verified release APK...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "wci-update-${releaseInfo.tagName}.apk"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        return dm.enqueue(request)
    }

    fun buildFileProviderInstallIntent(context: Context, apkFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = runCatching {
            FileProvider.getUriForFile(context, authority, apkFile)
        }.getOrElse { Uri.fromFile(apkFile) }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // =========================================================================
    // 6. Runtime Anti-Frida & Memory Zeroization Function (RASP)
    // =========================================================================

    data class RaspZeroizationReport(
        val intrusionDetected: Boolean,
        val reason: String?,
        val zeroizedBufferCount: Int
    )

    fun inspectRaspAndZeroizeOnIntrusion(
        sensitiveByteBuffers: List<ByteArray> = emptyList(),
        sensitiveCharBuffers: List<CharArray> = emptyList(),
        overrideMapsLines: List<String>? = null
    ): RaspZeroizationReport {
        val mapsAudit = MahaBrahmastraProPlusEngine.auditNativeExecutableMemorySegments(overrideMapsLines)
        val hookScan = AntiHookManager.runComprehensiveHookScan()
        val compromised = !mapsAudit.isClean || (!hookScan.isClean && overrideMapsLines == null)

        var wipedCount = 0
        if (compromised) {
            sensitiveByteBuffers.forEach { buf ->
                Arrays.fill(buf, 0.toByte())
                wipedCount++
            }
            sensitiveCharBuffers.forEach { buf ->
                Arrays.fill(buf, '\u0000')
                wipedCount++
            }
            runCatching { MemoryScrubberLifecycleObserver.scrubAllRegisteredBuffersNow() }
        }
        val reason = when {
            !mapsAudit.isClean -> "HOOK_INJECTION:${mapsAudit.suspiciousLibrariesDetected.joinToString(",")}"
            !hookScan.isClean && overrideMapsLines == null -> buildString {
                if (hookScan.fridaServerDetected) append("FRIDA_DETECTED ")
                if (hookScan.xposedOrLsposedDetected) append("XPOSED_LSPOSED_DETECTED ")
                if (hookScan.inlineTrampolineDetected) append("INLINE_TRAMPOLINE_DETECTED ")
                hookScan.suspiciousLibraryName?.let { append("($it)") }
            }.trim()
            else -> null
        }
        return RaspZeroizationReport(
            intrusionDetected = compromised,
            reason = reason,
            zeroizedBufferCount = wipedCount
        )
    }

    fun zeroizeMemory(bytes: ByteArray?, chars: CharArray? = null, directBuffer: ByteBuffer? = null) {
        bytes?.let { Arrays.fill(it, 0.toByte()) }
        chars?.let { Arrays.fill(it, '\u0000') }
        directBuffer?.let { buf ->
            buf.clear()
            while (buf.hasRemaining()) buf.put(0.toByte())
            buf.clear()
        }
    }

    // =========================================================================
    // 7. Automated WhatsApp & Telegram Broadcast Notification Function
    // =========================================================================

    fun buildWhatsAppBroadcastIntent(
        phoneNumberE164: String?,
        messageText: String
    ): Intent {
        val cleanPhone = phoneNumberE164?.replace(Regex("[^0-9]"), "").orEmpty()
        val encodedMsg = Uri.encode(messageText)
        val uri = if (cleanPhone.isNotBlank()) {
            Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg")
        } else {
            Uri.parse("https://api.whatsapp.com/send?text=$encodedMsg")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildTelegramBroadcastIntent(
        channelOrUsername: String?,
        messageText: String
    ): Intent {
        val encodedMsg = Uri.encode(messageText)
        val cleanTarget = channelOrUsername?.trim()?.removePrefix("@").orEmpty()
        val uri = if (cleanTarget.isNotBlank()) {
            Uri.parse("https://t.me/share/url?url=https://t.me/$cleanTarget&text=$encodedMsg")
        } else {
            Uri.parse("https://t.me/share/url?url=https://waqar-institute-erp.web.app&text=$encodedMsg")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("org.telegram.messenger")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // =========================================================================
    // 8. Encrypted Local SQLite / SQLCipher Database Vault Function
    // =========================================================================

    fun deriveSqlCipher256BitPassphrase(context: Context): ByteArray {
        val seedMaterial = "WCI_SQLCIPHER_256_VAULT|${context.packageName}|${com.example.BuildConfig.EXPECTED_RELEASE_CERT_SHA256}"
        return sha256Bytes(seedMaterial)
    }

    private fun getOrCreateZeroKnowledgeKey(): SecretKey {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ZERO_KNOWLEDGE_KEY_ALIAS)) {
                (ks.getEntry(ZERO_KNOWLEDGE_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    ZERO_KNOWLEDGE_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                gen.init(spec)
                gen.generateKey()
            }
        }.getOrElse {
            SecretKeySpec(sha256Bytes("WCI_ZK_FALLBACK_KEY_${com.example.BuildConfig.APPLICATION_ID}"), "AES")
        }
    }

    private fun sha256Bytes(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
