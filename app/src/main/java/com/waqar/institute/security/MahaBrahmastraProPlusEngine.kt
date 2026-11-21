package com.waqar.institute.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.hardware.display.DisplayManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.Display
import android.view.accessibility.AccessibilityManager
import com.example.security.HardwareKeystoreVault
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Maha Brahmastra Pro+ Security Engine (Rules #6 to #10).
 *
 * Layered strictly on top of existing Maha Brahmastra Rules #1–#5 without modifying or
 * weakening any existing security control:
 * - Pro+ Rule #6: Native `/proc/self/maps` Executable Segment Integrity & Inline-Hook Scanner
 * - Pro+ Rule #7: Anti-Overlay, Screen-Share, VirtualDisplay & Remote-Control Accessibility Blocker
 * - Pro+ Rule #8: Per-Request Cryptographic Nonce + Monotonic Counter Anti-Replay Envelope
 * - Pro+ Rule #9: Canary Honeypot Trap Vault (Active Intrusion & Memory-Dump Detection)
 * - Pro+ Rule #10: Hardware Keystore Row-Level HMAC Seal (Anti-SQLite / Root Record Tampering)
 */
object MahaBrahmastraProPlusEngine {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val ROW_MAC_KEY_ALIAS = "wci_maha_brahmastra_row_mac_v1"
    private const val ENVELOPE_WINDOW_MS = 300_000L // 5-minute freshness window

    const val CANARY_KEY_MASTER = "__canary_root_master_key_v1"
    const val CANARY_KEY_EXAM_OVERRIDE = "__canary_exam_master_override"
    const val CANARY_KEY_SQLCIPHER_SEED = "__canary_sqlcipher_raw_seed"

    private const val CANARY_EXPECTED_MASTER = "WCI_CANARY_TRAP_9F8E7D6C5B4A3210"
    private const val CANARY_EXPECTED_EXAM = "WCI_CANARY_EXAM_0011223344556677"
    private const val CANARY_EXPECTED_SQL = "WCI_CANARY_SQL_AABBCCDDEEFF0099"

    private val SUSPICIOUS_MEMORY_HOOK_MARKERS = listOf(
        "frida-agent",
        "frida-gadget",
        "libfrida",
        "xposedbridge",
        "lsposed",
        "edxp",
        "zygisk",
        "riru",
        "libsubstrate",
        "libsandhook",
        "libpine",
        "libwhale",
        "libdobby"
    )

    private val REMOTE_CONTROL_PACKAGE_MARKERS = listOf(
        "com.anydesk",
        "com.teamviewer",
        "com.carriez.flutter_hbb", // RustDesk
        "com.sand.airdroid",
        "com.koushikdutta.vysor",
        "autoclicker",
        "clickassistant",
        "tapping.auto"
    )

    private val monotonicCounter = AtomicLong(1_000L)
    private val lastAcceptedCounterByActor = ConcurrentHashMap<String, Long>()
    private val consumedNonces = ConcurrentHashMap<String, Long>()
    private val canaryStoreFallback = ConcurrentHashMap<String, String>()

    // =========================================================================
    // Pro+ Rule #6: Native /proc/self/maps Executable Segment Integrity Scanner
    // =========================================================================

    data class MemoryIntegrityReport(
        val isClean: Boolean,
        val executableSegmentCount: Int,
        val executableSegmentsDigestHex: String,
        val suspiciousLibrariesDetected: List<String>
    )

    private fun allowTestOverrides(): Boolean =
        com.example.BuildConfig.DEBUG ||
            android.os.Build.FINGERPRINT?.equals("robolectric", ignoreCase = true) == true

    /**
     * Scans `/proc/self/maps` (or supplied maps lines in debug/unit tests only) for `r-xp`
     * executable memory pages, detects injected hooking frameworks (Frida, Zygisk, LSPosed,
     * SandHook, Dobby, Substrate), and computes a SHA-256 fingerprint of executable mappings.
     */
    fun auditNativeExecutableMemorySegments(
        overrideMapsLines: List<String>? = null
    ): MemoryIntegrityReport {
        val effectiveOverride = if (allowTestOverrides()) overrideMapsLines else null
        val lines: List<String> = effectiveOverride ?: runCatching {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                mapsFile.useLines { seq -> seq.take(4000).toList() }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val suspicious = mutableListOf<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        var execCount = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase(Locale.US)
            val isExecutable = lower.contains(" r-xp ") || lower.contains(" rwxp ")
            if (isExecutable) {
                execCount++
                val pathPart = line.substringAfterLast(' ', "").trim()
                digest.update(pathPart.toByteArray(Charsets.UTF_8))
            }
            for (marker in SUSPICIOUS_MEMORY_HOOK_MARKERS) {
                if (lower.contains(marker)) {
                    suspicious.add(marker)
                }
            }
            if (lower.contains(" rwxp ") && lower.contains("/memfd:")) {
                suspicious.add("anonymous_rwx_memfd_injection")
            }
        }

        val distinctSuspicious = suspicious.distinct()
        val digestHex = digest.digest().joinToString("") { "%02x".format(it) }
        return MemoryIntegrityReport(
            isClean = distinctSuspicious.isEmpty(),
            executableSegmentCount = execCount,
            executableSegmentsDigestHex = digestHex,
            suspiciousLibrariesDetected = distinctSuspicious
        )
    }

    // =========================================================================
    // Pro+ Rule #7: Anti-Overlay, Screen-Share & Remote-Control Exam Blocker
    // =========================================================================

    data class ScreenOverlayAndRemoteAuditReport(
        val isSafeForProctoredExam: Boolean,
        val externalOrVirtualDisplayCount: Int,
        val suspiciousAccessibilityPackages: List<String>,
        val blockingReason: String?
    )

    /**
     * Audits active displays (`DisplayManager`) for screen-mirroring/presentation/virtual displays
     * and inspects enabled `AccessibilityService` packages for remote-control or auto-clicker tools.
     * Simulation parameters are strictly ignored in Release builds.
     */
    fun auditOverlayScreenShareAndAccessibility(
        context: Context,
        simulatedExternalDisplayCount: Int? = null,
        simulatedAccessibilityPackages: List<String>? = null
    ): ScreenOverlayAndRemoteAuditReport {
        val allowSim = allowTestOverrides()
        val externalDisplays = (if (allowSim) simulatedExternalDisplayCount else null) ?: runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val displays = dm?.displays ?: emptyArray()
            displays.count { d ->
                d.displayId != Display.DEFAULT_DISPLAY &&
                    ((d.flags and Display.FLAG_PRESENTATION) != 0 || d.name.contains("Virtual", ignoreCase = true) || d.name.contains("Cast", ignoreCase = true))
            }
        }.getOrDefault(0)

        val enabledPackages = (if (allowSim) simulatedAccessibilityPackages else null) ?: runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val list = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
            list.mapNotNull { info -> info.resolveInfo?.serviceInfo?.packageName?.lowercase(Locale.US) }
        }.getOrDefault(emptyList())

        val flaggedAccessibility = enabledPackages.filter { pkg ->
            REMOTE_CONTROL_PACKAGE_MARKERS.any { marker -> pkg.contains(marker) }
        }.distinct()

        val reason = when {
            externalDisplays > 0 ->
                "Screen-mirroring or virtual display ($externalDisplays active) detected. Disconnect screen sharing before continuing."
            flaggedAccessibility.isNotEmpty() ->
                "Remote-control or automation accessibility service detected (${flaggedAccessibility.joinToString(", ")}). Disable it before continuing."
            else -> null
        }

        return ScreenOverlayAndRemoteAuditReport(
            isSafeForProctoredExam = reason == null,
            externalOrVirtualDisplayCount = externalDisplays,
            suspiciousAccessibilityPackages = flaggedAccessibility,
            blockingReason = reason
        )
    }

    // =========================================================================
    // Pro+ Rule #8: Per-Request Cryptographic Nonce + Monotonic Counter Envelope
    // =========================================================================

    data class SignedActionEnvelope(
        val actionType: String,
        val actorId: String,
        val payloadDigestHex: String,
        val nonceHex: String,
        val monotonicCounter: Long,
        val timestampEpochMs: Long,
        val hmacSealHex: String
    )

    /**
     * Issues a single-use cryptographic envelope with a CSPRNG 128-bit nonce, strictly increasing
     * monotonic counter, and Keystore HMAC-SHA256 seal.
     */
    fun issueSignedActionEnvelope(
        actionType: String,
        actorId: String,
        payloadJson: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): SignedActionEnvelope {
        val nonceBytes = ByteArray(16)
        SecureRandom().nextBytes(nonceBytes)
        val nonceHex = nonceBytes.joinToString("") { "%02x".format(it) }
        val counter = monotonicCounter.incrementAndGet()
        val payloadDigestHex = sha256Hex(payloadJson)
        val canonical = "$actionType|$actorId|$payloadDigestHex|$nonceHex|$counter|$nowEpochMs"
        val hmacSealHex = computeKeystoreHmacHex(canonical)
        return SignedActionEnvelope(
            actionType = actionType,
            actorId = actorId,
            payloadDigestHex = payloadDigestHex,
            nonceHex = nonceHex,
            monotonicCounter = counter,
            timestampEpochMs = nowEpochMs,
            hmacSealHex = hmacSealHex
        )
    }

    /**
     * Verifies and consumes a [SignedActionEnvelope]. Rejects replayed nonces, stale timestamps,
     * regressed monotonic counters, or tampered payloads/HMACs.
     */
    @Synchronized
    fun verifyAndConsumeActionEnvelope(
        envelope: SignedActionEnvelope,
        expectedPayloadJson: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (kotlin.math.abs(nowEpochMs - envelope.timestampEpochMs) > ENVELOPE_WINDOW_MS) {
            return false
        }
        val expectedPayloadDigest = sha256Hex(expectedPayloadJson)
        if (!MessageDigest.isEqual(
                expectedPayloadDigest.toByteArray(Charsets.UTF_8),
                envelope.payloadDigestHex.toByteArray(Charsets.UTF_8)
            )
        ) {
            return false
        }
        val canonical = "${envelope.actionType}|${envelope.actorId}|${envelope.payloadDigestHex}|${envelope.nonceHex}|${envelope.monotonicCounter}|${envelope.timestampEpochMs}"
        val expectedHmac = computeKeystoreHmacHex(canonical)
        if (!MessageDigest.isEqual(
                expectedHmac.toByteArray(Charsets.UTF_8),
                envelope.hmacSealHex.toByteArray(Charsets.UTF_8)
            )
        ) {
            return false
        }
        if (consumedNonces.putIfAbsent(envelope.nonceHex, nowEpochMs) != null) {
            return false // Replayed nonce blocked
        }
        val prevCounter = lastAcceptedCounterByActor[envelope.actorId] ?: 0L
        if (envelope.monotonicCounter <= prevCounter) {
            return false // Monotonic counter regression blocked
        }
        lastAcceptedCounterByActor[envelope.actorId] = envelope.monotonicCounter
        return true
    }

    // =========================================================================
    // Pro+ Rule #9: Canary Honeypot Trap Vault (Active Intrusion Trap)
    // =========================================================================

    data class CanaryAuditResult(
        val intact: Boolean,
        val triggeredTrapKey: String?,
        val alertMessage: String?
    )

    fun initializeCanaryHoneypotVault(context: Context?) {
        canaryStoreFallback[CANARY_KEY_MASTER] = CANARY_EXPECTED_MASTER
        canaryStoreFallback[CANARY_KEY_EXAM_OVERRIDE] = CANARY_EXPECTED_EXAM
        canaryStoreFallback[CANARY_KEY_SQLCIPHER_SEED] = CANARY_EXPECTED_SQL
        runCatching {
            val prefs = context?.let { HardwareKeystoreVault.openEncryptedPreferences(it.applicationContext) }
            prefs?.edit()
                ?.putString(CANARY_KEY_MASTER, CANARY_EXPECTED_MASTER)
                ?.putString(CANARY_KEY_EXAM_OVERRIDE, CANARY_EXPECTED_EXAM)
                ?.putString(CANARY_KEY_SQLCIPHER_SEED, CANARY_EXPECTED_SQL)
                ?.apply()
        }
    }

    fun simulateCanaryTamperForTest(context: Context?, key: String, tamperedValue: String) {
        canaryStoreFallback[key] = tamperedValue
        runCatching {
            val prefs = context?.let { HardwareKeystoreVault.openEncryptedPreferences(it.applicationContext) }
            prefs?.edit()?.putString(key, tamperedValue)?.commit()
        }
    }

    fun verifyCanaryIntactOrAlert(context: Context?): CanaryAuditResult {
        val prefs = runCatching {
            context?.let { HardwareKeystoreVault.openEncryptedPreferences(it.applicationContext) }
        }.getOrNull()

        val checks = listOf(
            CANARY_KEY_MASTER to CANARY_EXPECTED_MASTER,
            CANARY_KEY_EXAM_OVERRIDE to CANARY_EXPECTED_EXAM,
            CANARY_KEY_SQLCIPHER_SEED to CANARY_EXPECTED_SQL
        )

        for ((key, expected) in checks) {
            val actual = runCatching { prefs?.getString(key, null) }.getOrNull()
                ?: canaryStoreFallback[key]
            if (actual == null) {
                initializeCanaryHoneypotVault(context)
                continue
            }
            if (actual != expected) {
                return CanaryAuditResult(
                    intact = false,
                    triggeredTrapKey = key,
                    alertMessage = "CRITICAL_HONEYPOT_TRIGGERED: Decoy canary key '$key' was modified or probed in local vault."
                )
            }
        }
        return CanaryAuditResult(intact = true, triggeredTrapKey = null, alertMessage = null)
    }

    // =========================================================================
    // Pro+ Rule #10: Hardware Keystore Row-Level MAC (Anti-Root Record Tampering)
    // =========================================================================

    fun computeRecordRowMac(
        recordType: String,
        recordId: String,
        canonicalFields: String
    ): String {
        val payload = "ROW_MAC_V1|$recordType|$recordId|$canonicalFields"
        return computeKeystoreHmacHex(payload)
    }

    fun verifyRecordRowMac(
        recordType: String,
        recordId: String,
        canonicalFields: String,
        expectedMacHex: String
    ): Boolean {
        if (expectedMacHex.isBlank()) return false
        val actualMac = computeRecordRowMac(recordType, recordId, canonicalFields)
        return MessageDigest.isEqual(
            actualMac.toByteArray(Charsets.UTF_8),
            expectedMacHex.toByteArray(Charsets.UTF_8)
        )
    }

    fun resetStateForTests(context: Context? = null) {
        consumedNonces.clear()
        lastAcceptedCounterByActor.clear()
        monotonicCounter.set(1_000L)
        initializeCanaryHoneypotVault(context)
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun computeKeystoreHmacHex(data: String): String {
        val bytes = data.toByteArray(Charsets.UTF_8)
        return runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val secretKey = if (ks.containsAlias(ROW_MAC_KEY_ALIAS)) {
                (ks.getEntry(ROW_MAC_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    ROW_MAC_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).setDigests(KeyProperties.DIGEST_SHA256).build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(secretKey)
            mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
        }.getOrElse {
            val fallbackSecret = ("WCI_PRO_PLUS_KEYSTORE_HMAC_" + com.example.BuildConfig.EXAM_ANSWER_HMAC_PEPPER)
                .toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(fallbackSecret, "HmacSHA256"))
            mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
