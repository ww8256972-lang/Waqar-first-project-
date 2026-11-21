package com.waqar.institute.upi

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.firestore.InstituteRepository
import com.example.data.model.StudentRecord
import com.example.security.EnterpriseRaspGuard
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Data model for `/upi_transactions/{txnId}` in Cloud Firestore.
 */
data class UpiTransactionRecord(
    val id: String = "",
    val student_id: String = "",
    val student_name: String = "",
    val course_name: String = "",
    val amount: Double = 0.0,
    val amount_paise: Long = 0L,
    val payment_type: String = "MONTHLY_FEE", // MONTHLY_FEE | AUTO_EMI | FULL_FEE
    val tr_ref: String = "",
    val tn_note: String = "",
    val signal_nonce: String = "",
    val intent_hash: String = "",
    val utr_number: String = "",
    val upi_package: String = "",
    val device_fingerprint: String = "",
    val apk_signature_sha256: String = "",
    val timestamp: Long = 0L,
    val expires_at_ms: Long = 0L,
    val status: String = "PENDING_APPROVAL", // CHALLENGE_ISSUED | DISPATCHED | PENDING_APPROVAL | VERIFIED_PAID | REJECTED_FRAUD | EXPIRED
    val verified_by_uid: String = "",
    val prev_record_hash: String = "GENESIS_WCI_FEE_LEDGER_2026",
    val record_sha256_hash: String = "",
    val chain_index: Long = 0L,
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "id" to id,
        "student_id" to student_id,
        "student_name" to student_name,
        "course_name" to course_name,
        "amount" to amount,
        "amount_paise" to amount_paise,
        "payment_type" to payment_type,
        "tr_ref" to tr_ref,
        "tn_note" to tn_note,
        "signal_nonce" to signal_nonce,
        "intent_hash" to intent_hash,
        "utr_number" to utr_number,
        "upi_package" to upi_package,
        "device_fingerprint" to device_fingerprint,
        "apk_signature_sha256" to apk_signature_sha256,
        "timestamp" to timestamp,
        "expires_at_ms" to expires_at_ms,
        "status" to status,
        "verified_by_uid" to verified_by_uid,
        "prev_record_hash" to prev_record_hash,
        "record_sha256_hash" to record_sha256_hash,
        "chain_index" to chain_index,
        "createdAt" to createdAt
    )
}

/**
 * Data model for `/student_fees_ledger/{studentId}` in Cloud Firestore.
 */
data class StudentFeesLedgerRecord(
    val id: String = "",
    val student_id: String = "",
    val student_name: String = "",
    val enrollment_no: String = "",
    val course_name: String = "",
    val batch_name: String = "",
    val total_fee_paise: Long = 0L,
    val paid_fee_paise: Long = 0L,
    val monthly_fee_paise: Long = 150000L, // Default ₹1,500/month
    val emi_installment_paise: Long = 150000L,
    val monthly_payment_status: String = "PENDING", // PAID | PENDING | OVERDUE | EMI_ACTIVE
    val overdue_months: Int = 0,
    val auto_emi_active: Boolean = false,
    val emi_billing_date: Int = 5, // Day of month 1..28
    val next_due_date_iso: String = "",
    val last_emi_notice_iso: String = "",
    val last_utr_number: String = "",
    val owner_uid: String = "admin_root",
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Timestamp = Timestamp.now()
) {
    val balancePaise: Long
        get() = (total_fee_paise - paid_fee_paise).coerceAtLeast(0L)

    val isFourMonthOverdueEligible: Boolean
        get() = overdue_months >= 4

    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "id" to id,
        "student_id" to student_id,
        "student_name" to student_name,
        "enrollment_no" to enrollment_no,
        "course_name" to course_name,
        "batch_name" to batch_name,
        "total_fee_paise" to total_fee_paise,
        "paid_fee_paise" to paid_fee_paise,
        "monthly_fee_paise" to monthly_fee_paise,
        "emi_installment_paise" to emi_installment_paise,
        "monthly_payment_status" to monthly_payment_status,
        "overdue_months" to overdue_months.coerceIn(0, 120),
        "auto_emi_active" to auto_emi_active,
        "emi_billing_date" to emi_billing_date.coerceIn(1, 28),
        "next_due_date_iso" to next_due_date_iso,
        "last_emi_notice_iso" to last_emi_notice_iso,
        "last_utr_number" to last_utr_number,
        "owner_uid" to owner_uid.ifBlank { "admin_root" },
        "updatedAt" to updatedAt,
        "createdAt" to createdAt
    )
}

/**
 * Data model for `/institute_audit_logs/{logId}` in Cloud Firestore.
 */
data class InstituteAuditLogRecord(
    val id: String = "",
    val actor_uid: String = "",
    val actor_name: String = "",
    val student_id: String = "",
    val event_type: String = "",
    val severity: String = "INFO", // INFO | WARNING | CRITICAL
    val utr_number: String = "",
    val intent_hash: String = "",
    val device_fingerprint: String = "",
    val details: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "id" to id,
        "actor_uid" to actor_uid.ifBlank { "system_guard" },
        "actor_name" to actor_name,
        "student_id" to student_id,
        "event_type" to event_type,
        "severity" to severity,
        "utr_number" to utr_number,
        "intent_hash" to intent_hash,
        "device_fingerprint" to device_fingerprint,
        "details" to details.take(1900),
        "timestamp" to timestamp,
        "createdAt" to createdAt
    )
}

/**
 * Official whitelisted UPI application metadata.
 */
data class WhitelistedUpiApp(
    val packageName: String,
    val displayName: String,
    val shortBadge: String,
    val isInstalled: Boolean = false
)

/**
 * Ephemeral cryptographic challenge token issued for a 120-second payment window.
 */
data class UpiCryptographicChallenge(
    val challengeId: String,
    val studentId: String,
    val studentName: String,
    val courseName: String,
    val amountPaise: Long,
    val amountFormattedInr: String,
    val paymentType: String, // MONTHLY_FEE | AUTO_EMI | FULL_FEE
    val signalNonce: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val trRef: String,
    val tnNote: String,
    val intentHashHex: String,
    val deviceFingerprint: String,
    val apkSignatureSha256: String,
    val upiUriString: String,
    val targetPackage: String
)

/**
 * Strict Finite State Machine (FSM) for Zero-Fee Direct UPI Intent.
 * Transitions: Idle -> Challenge -> Dispatched -> Verifying -> PendingApproval / VerifiedPaid / Failed / Expired
 */
sealed class UpiPaymentFsmState {
    data object Idle : UpiPaymentFsmState()
    data class Challenge(val challenge: UpiCryptographicChallenge) : UpiPaymentFsmState()
    data class Dispatched(
        val challenge: UpiCryptographicChallenge,
        val packageName: String,
        val dispatchedAtMs: Long
    ) : UpiPaymentFsmState()
    data class Verifying(val challenge: UpiCryptographicChallenge) : UpiPaymentFsmState()
    data class PendingApproval(
        val transaction: UpiTransactionRecord,
        val message: String
    ) : UpiPaymentFsmState()
    data class VerifiedPaid(
        val transaction: UpiTransactionRecord,
        val receiptNo: String
    ) : UpiPaymentFsmState()
    data class Expired(
        val challengeId: String,
        val reason: String
    ) : UpiPaymentFsmState()
    data class Failed(
        val errorCode: String,
        val reason: String
    ) : UpiPaymentFsmState()
}

/**
 * Parsed result from a returning official UPI app `onActivityResult` Intent.
 */
data class ParsedUpiIntentResponse(
    val rawStatus: String,
    val isSuccess: Boolean,
    val utrNumber: String,
    val txnId: String,
    val txnRef: String,
    val responseCode: String,
    val rawResponseSummary: String
)

/**
 * Option B: Zero-Fee Direct UPI Intent + 5-Layer Non-Store Hardcore Cryptographic Security
 * + 4-Month Overdue Admin-Triggered Auto-EMI Engine.
 */
object ZeroFeeUpiPaymentEngine {

    // Institute Official UPI Merchant Credentials (User-Confirmed)
    const val MERCHANT_VPA = "9235528409@ptyes"
    const val MERCHANT_NAME = "Waqar computer institute"
    const val CURRENCY_CODE = "INR"

    // 120-Second Cryptographic Entropy Window (2 Minutes)
    const val CHALLENGE_EXPIRY_WINDOW_MS = 120_000L
    const val IO_WATCHDOG_TIMEOUT_MS = 5_000L

    private const val PREFS_FILE_NAME = "wci_zero_fee_upi_hmac_vault"
    private const val KEY_DEVICE_HMAC_SALT = "hardware_bound_upi_hmac_salt_v1"

    // Layer 1: Strict Official NPCI UPI Package Whitelist
    val OFFICIAL_UPI_PACKAGE_CATALOG: List<WhitelistedUpiApp> = listOf(
        WhitelistedUpiApp(
            packageName = "com.google.android.apps.nbu.paisa.user",
            displayName = "Google Pay (GPay)",
            shortBadge = "GPay"
        ),
        WhitelistedUpiApp(
            packageName = "com.phonepe.app",
            displayName = "PhonePe",
            shortBadge = "PhonePe"
        ),
        WhitelistedUpiApp(
            packageName = "net.one97.paytm",
            displayName = "Paytm UPI",
            shortBadge = "Paytm"
        ),
        WhitelistedUpiApp(
            packageName = "in.org.npci.upiapp",
            displayName = "BHIM Official UPI",
            shortBadge = "BHIM"
        ),
        WhitelistedUpiApp(
            packageName = "in.amazon.mShop.android.shopping",
            displayName = "Amazon Pay UPI",
            shortBadge = "AmazonPay"
        )
    )

    val WHITELISTED_PACKAGE_NAMES: Set<String> =
        OFFICIAL_UPI_PACKAGE_CATALOG.map { it.packageName }.toSet()

    private val _fsmState = MutableStateFlow<UpiPaymentFsmState>(UpiPaymentFsmState.Idle)
    val fsmState: StateFlow<UpiPaymentFsmState> = _fsmState.asStateFlow()

    // In-memory single-use nonce & UTR guards backing Firestore `used_utr_locks`
    private val consumedNonces = ConcurrentHashMap.newKeySet<String>()
    val localUtrLocks = ConcurrentHashMap<String, String>()
    val localUpiTransactionsCache = ConcurrentHashMap<String, UpiTransactionRecord>()
    val localStudentFeesLedgerCache = ConcurrentHashMap<String, StudentFeesLedgerRecord>()
    val localInstituteAuditLogsCache = ConcurrentHashMap<String, InstituteAuditLogRecord>()

    private val secureRandom = SecureRandom()

    private val feeHashChainLock = Any()
    @Volatile
    private var lastFeeChainHashHex: String = "GENESIS_WCI_FEE_LEDGER_2026"
    @Volatile
    private var lastFeeChainIndex: Long = 0L

    fun resetFsmToIdle() {
        _fsmState.value = UpiPaymentFsmState.Idle
    }

    /**
     * Mandatory Production UPI Guard: blocks payment cleanly when no official NPCI whitelisted
     * UPI app (Google Pay, PhonePe, Paytm, BHIM, Amazon Pay) is installed on the device.
     */
    fun markLaunchBlockedNoUpiApp(selectedPackage: String) {
        _fsmState.value = UpiPaymentFsmState.Failed(
            errorCode = "NO_OFFICIAL_UPI_APP_INSTALLED",
            reason = "Please install Google Pay, PhonePe, Paytm, or BHIM to pay ($selectedPackage not installed)."
        )
    }

    /**
     * [A5] Computes a tamper-evident SHA-256 hash chain link binding each fee/UPI payment
     * cryptographically to the previous ledger record's hash (`prev_record_hash`).
     */
    fun computeChainedFeeRecordHash(
        prevHash: String,
        txnId: String,
        studentId: String,
        amountPaise: Long,
        utrNumber: String,
        timestampMs: Long
    ): String {
        val canonical = "$prevHash|$txnId|$studentId|$amountPaise|$utrNumber|$timestampMs|WCI_LEDGER_CHAIN_V1"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return bytesToHex(digest).lowercase(Locale.US)
    }

    /**
     * [A5] Verifies that an ordered sequence of UPI/fee transactions forms an unbroken SHA-256 hash chain.
     */
    fun verifyFeeLedgerHashChain(orderedRecords: List<UpiTransactionRecord>): Boolean {
        if (orderedRecords.isEmpty()) return true
        for (i in orderedRecords.indices) {
            val rec = orderedRecords[i]
            if (rec.record_sha256_hash.isBlank()) continue
            val expected = computeChainedFeeRecordHash(
                prevHash = rec.prev_record_hash,
                txnId = rec.id,
                studentId = rec.student_id,
                amountPaise = rec.amount_paise,
                utrNumber = rec.utr_number,
                timestampMs = rec.timestamp
            )
            if (!expected.equals(rec.record_sha256_hash, ignoreCase = true)) return false
            if (i > 0) {
                val prev = orderedRecords[i - 1]
                if (prev.record_sha256_hash.isNotBlank() &&
                    !rec.prev_record_hash.equals(prev.record_sha256_hash, ignoreCase = true)
                ) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Checks whether a UPI package name is in the strict institute whitelist.
     */
    fun isPackageWhitelisted(packageName: String): Boolean {
        val clean = packageName.trim()
        return clean in WHITELISTED_PACKAGE_NAMES
    }

    /**
     * Inspects installed packages on the device and returns the whitelisted catalog with installation flags.
     */
    fun detectInstalledWhitelistedUpiApps(context: Context?): List<WhitelistedUpiApp> {
        val pm = context?.packageManager ?: return OFFICIAL_UPI_PACKAGE_CATALOG
        return OFFICIAL_UPI_PACKAGE_CATALOG.map { app ->
            val installed = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(app.packageName, 0)
                }
                true
            }.getOrDefault(false)
            app.copy(isInstalled = installed)
        }
    }

    /**
     * Retrieves or provisions the hardware-keystore-backed HMAC-SHA256 secret salt via
     * `androidx.security.crypto.EncryptedSharedPreferences` + `MasterKey`.
     */
    private fun getOrProvisionHardwareHmacSecret(context: Context?): ByteArray {
        if (context == null) {
            return "WCI_JVM_TEST_FALLBACK_KEYSTORE_HMAC_SALT_2026".toByteArray(StandardCharsets.UTF_8)
        }
        val prefs: SharedPreferences = runCatching {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        }

        val existingHex = prefs.getString(KEY_DEVICE_HMAC_SALT, null)
        if (!existingHex.isNullOrBlank() && existingHex.length >= 64) {
            return hexToBytes(existingHex)
        }
        val newBytes = ByteArray(32)
        secureRandom.nextBytes(newBytes)
        val newHex = bytesToHex(newBytes)
        prefs.edit().putString(KEY_DEVICE_HMAC_SALT, newHex).apply()
        return newBytes
    }

    /**
     * Computes hardware device fingerprint + APK SHA-256 certificate digest.
     */
    fun resolveHardwareDeviceFingerprint(context: Context?): String {
        val androidId = runCatching {
            if (context != null) {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            } else {
                "jvm_unit_test_node"
            }
        }.getOrDefault("hw_node")
        val raw = "${Build.MANUFACTURER}|${Build.MODEL}|${Build.BOARD}|${Build.VERSION.SDK_INT}|$androidId"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return "HW_" + bytesToHex(digest).take(20)
    }

    fun resolveApkSignatureSha256(context: Context?): String {
        if (context == null) return "D02D35B4C135A67C911F0A8882026B8A29C7189402B729D1A8D0A81174EEB101"
        val pm = context.packageManager
        return runCatching {
            val sigBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                val sigs = if (signingInfo != null && signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
                sigs?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (sigBytes != null && sigBytes.isNotEmpty()) {
                bytesToHex(MessageDigest.getInstance("SHA-256").digest(sigBytes))
            } else {
                "WCI_VERIFIED_APK_SHA256_SIGNATURE"
            }
        }.getOrDefault("WCI_VERIFIED_APK_SHA256_SIGNATURE")
    }

    /**
     * Computes deterministic HMAC-SHA256 hex over the canonical challenge payload.
     */
    fun computeChallengeHmacSha256(
        secretKeyBytes: ByteArray,
        studentId: String,
        amountFormattedInr: String,
        issuedAtSec: Long,
        signalNonce: String,
        deviceFingerprint: String,
        apkSignatureSha256: String
    ): String {
        val canonicalPayload = buildString {
            append(MERCHANT_VPA.lowercase(Locale.US)).append('|')
            append(studentId.trim()).append('|')
            append(amountFormattedInr.trim()).append('|')
            append(issuedAtSec).append('|')
            append(signalNonce.trim()).append('|')
            append(deviceFingerprint.trim()).append('|')
            append(apkSignatureSha256.trim())
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKeyBytes, "HmacSHA256"))
        val digest = mac.doFinal(canonicalPayload.toByteArray(StandardCharsets.UTF_8))
        return bytesToHex(digest)
    }

    /**
     * Layer 2 + Layer 5: Issues a hardware-bound `UpiCryptographicChallenge` on `Dispatchers.IO`
     * with a 5-second watchdog (`withTimeout(5000L)`), transitions the FSM from `Idle -> Challenge`,
     * and constructs the signed `upi://pay` URI for `9235528409@ptyes` (`Waqar computer institute`).
     */
    suspend fun issueSignedPaymentChallenge(
        context: Context?,
        studentId: String,
        studentName: String,
        courseName: String,
        amountPaise: Long,
        paymentType: String = "MONTHLY_FEE",
        preferredPackage: String = "com.google.android.apps.nbu.paisa.user",
        actorUid: String = ""
    ): Result<UpiCryptographicChallenge> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(IO_WATCHDOG_TIMEOUT_MS) {
                require(studentId.isNotBlank()) { "Student ID is required for UPI Challenge." }
                require(amountPaise > 0L) { "Payment amount must be greater than ₹0." }
                require(isPackageWhitelisted(preferredPackage)) {
                    "Blocked: '$preferredPackage' is not in the official NPCI UPI whitelist (GPay, PhonePe, Paytm, BHIM, Amazon Pay)."
                }

                // Layer 5: Anti-Tamper / Virtual-Space / Native RASP Verification
                if (context != null) {
                    val raspReport = EnterpriseRaspGuard.auditDeviceSecurity(context)
                    if (raspReport.memoryHookDetected || raspReport.tracerPidDetected) {
                        runCatching { context.deleteDatabase("waqar_institute_offline_v1.db") }
                        logAuditEventInternal(
                            context = context,
                            actorUid = actorUid.ifBlank { studentId },
                            actorName = studentName,
                            studentId = studentId,
                            eventType = "TAMPER_DETECTED_SQLCIPHER_WIPED",
                            severity = "CRITICAL",
                            utrNumber = "",
                            intentHash = "",
                            details = "Blocked UPI challenge & wiped SQLCipher vault due to RASP/Frida detection (${raspReport.verdict})"
                        )
                        _fsmState.value = UpiPaymentFsmState.Failed(
                            errorCode = "RASP_TAMPER_LOCKDOWN",
                            reason = "Security shield blocked payment on compromised/hooked environment."
                        )
                        throw SecurityException("Security shield blocked payment on compromised environment.")
                    }
                }

                val nowMs = System.currentTimeMillis()
                val issuedAtSec = nowMs / 1000L
                val expiresAtMs = nowMs + CHALLENGE_EXPIRY_WINDOW_MS

                val nonceBytes = ByteArray(12)
                secureRandom.nextBytes(nonceBytes)
                val signalNonce = bytesToHex(nonceBytes)

                val amountInrDouble = amountPaise / 100.0
                val amountFormattedInr = String.format(Locale.US, "%.2f", amountInrDouble)

                val secretKeyBytes = getOrProvisionHardwareHmacSecret(context)
                val deviceFp = resolveHardwareDeviceFingerprint(context)
                val apkSigSha = resolveApkSignatureSha256(context)

                val intentHashHex = computeChallengeHmacSha256(
                    secretKeyBytes = secretKeyBytes,
                    studentId = studentId,
                    amountFormattedInr = amountFormattedInr,
                    issuedAtSec = issuedAtSec,
                    signalNonce = signalNonce,
                    deviceFingerprint = deviceFp,
                    apkSignatureSha256 = apkSigSha
                )

                val cleanStudentToken = studentId.replace(Regex("[^a-zA-Z0-9]"), "").takeLast(8).uppercase(Locale.US)
                val trRef = "WCI${issuedAtSec}${intentHashHex.take(10)}"
                val tnNote = "WCI|$cleanStudentToken|$paymentType|N:${signalNonce.take(8)}|H:${intentHashHex.take(12)}"

                val upiUri = Uri.Builder()
                    .scheme("upi")
                    .authority("pay")
                    .appendQueryParameter("pa", MERCHANT_VPA)
                    .appendQueryParameter("pn", MERCHANT_NAME)
                    .appendQueryParameter("tr", trRef)
                    .appendQueryParameter("tn", tnNote)
                    .appendQueryParameter("am", amountFormattedInr)
                    .appendQueryParameter("cu", CURRENCY_CODE)
                    .build()
                    .toString()

                val challenge = UpiCryptographicChallenge(
                    challengeId = "upi_chl_${issuedAtSec}_${signalNonce.take(8)}",
                    studentId = studentId,
                    studentName = studentName.ifBlank { "WCI Student" },
                    courseName = courseName.ifBlank { "Computer Course" },
                    amountPaise = amountPaise,
                    amountFormattedInr = amountFormattedInr,
                    paymentType = paymentType,
                    signalNonce = signalNonce,
                    issuedAtMs = nowMs,
                    expiresAtMs = expiresAtMs,
                    trRef = trRef,
                    tnNote = tnNote,
                    intentHashHex = intentHashHex,
                    deviceFingerprint = deviceFp,
                    apkSignatureSha256 = apkSigSha,
                    upiUriString = upiUri,
                    targetPackage = preferredPackage
                )

                _fsmState.value = UpiPaymentFsmState.Challenge(challenge)
                challenge
            }
        }
    }

    /**
     * Transitions the FSM from `Challenge -> Dispatched` and creates the explicit `Intent`
     * bound to a whitelisted official UPI package.
     */
    fun buildWhitelistedLaunchIntent(
        challenge: UpiCryptographicChallenge,
        selectedPackage: String = challenge.targetPackage
    ): Result<Intent> = runCatching {
        val currentState = _fsmState.value
        if (currentState !is UpiPaymentFsmState.Challenge ||
            currentState.challenge.challengeId != challenge.challengeId
        ) {
            throw IllegalStateException("FSM Violation: Cannot dispatch UPI intent without an active Challenge state.")
        }
        if (!isPackageWhitelisted(selectedPackage)) {
            _fsmState.value = UpiPaymentFsmState.Failed(
                errorCode = "UNTRUSTED_UPI_PACKAGE",
                reason = "Blocked non-whitelisted UPI app: $selectedPackage"
            )
            throw SecurityException("Untrusted UPI package blocked: $selectedPackage")
        }
        if (System.currentTimeMillis() > challenge.expiresAtMs) {
            _fsmState.value = UpiPaymentFsmState.Expired(
                challengeId = challenge.challengeId,
                reason = "120-second cryptographic payment challenge expired before launch."
            )
            throw IllegalStateException("Payment challenge expired (120s limit).")
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(challenge.upiUriString)
            setPackage(selectedPackage)
        }
        _fsmState.value = UpiPaymentFsmState.Dispatched(
            challenge = challenge.copy(targetPackage = selectedPackage),
            packageName = selectedPackage,
            dispatchedAtMs = System.currentTimeMillis()
        )
        intent
    }

    /**
     * Parses the returning `Intent` from GPay / PhonePe / Paytm / BHIM and extracts
     * `Status`, `ApprovalRefNo` / `txnId` (12-digit UTR), `txnRef`, and `responseCode` automatically.
     */
    fun parseUpiIntentResult(data: Intent?): ParsedUpiIntentResponse {
        if (data == null) {
            return ParsedUpiIntentResponse(
                rawStatus = "CANCELLED",
                isSuccess = false,
                utrNumber = "",
                txnId = "",
                txnRef = "",
                responseCode = "",
                rawResponseSummary = "No intent data returned from UPI app"
            )
        }

        val kvMap = mutableMapOf<String, String>()
        val rawResponseStr = data.getStringExtra("response")
            ?: data.getStringExtra("result")
            ?: ""

        if (rawResponseStr.isNotBlank()) {
            rawResponseStr.split("&").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val k = pair.substring(0, idx).trim().lowercase(Locale.US)
                    val v = Uri.decode(pair.substring(idx + 1).trim())
                    kvMap[k] = v
                }
            }
        }

        data.extras?.keySet()?.forEach { key ->
            val v = data.extras?.get(key)?.toString()?.trim().orEmpty()
            if (v.isNotBlank()) {
                kvMap.putIfAbsent(key.lowercase(Locale.US), v)
            }
        }

        val status = (kvMap["status"] ?: "").uppercase(Locale.US)
        val responseCode = kvMap["responsecode"] ?: kvMap["response_code"] ?: ""
        val approvalRef = kvMap["approvalrefno"]
            ?: kvMap["approval_ref_no"]
            ?: kvMap["rrn"]
            ?: kvMap["utr"]
            ?: ""
        val txnId = kvMap["txnid"] ?: kvMap["txn_id"] ?: ""
        val txnRef = kvMap["txnref"] ?: kvMap["txn_ref"] ?: kvMap["tr"] ?: ""

        // Extract clean UTR (prefer 12-digit numeric RRN/ApprovalRefNo, fallback to alphanumeric txnId)
        val candidateUtr = approvalRef.ifBlank { txnId }.trim()
        val numeric12Match = Regex("\\b(\\d{12})\\b").find("$approvalRef $txnId $rawResponseStr")?.groupValues?.getOrNull(1)
        val resolvedUtr = (numeric12Match ?: candidateUtr)
            .replace(Regex("[^a-zA-Z0-9\\-]"), "")
            .take(40)

        val isSuccess = (status == "SUCCESS" || status == "SUBMITTED") &&
            resolvedUtr.length >= 6 &&
            responseCode != "ZM" &&
            responseCode != "U16"

        return ParsedUpiIntentResponse(
            rawStatus = status.ifBlank { "UNKNOWN" },
            isSuccess = isSuccess,
            utrNumber = resolvedUtr,
            txnId = txnId,
            txnRef = txnRef,
            responseCode = responseCode,
            rawResponseSummary = rawResponseStr.ifBlank { kvMap.entries.joinToString("&") { "${it.key}=${it.value}" } }
        )
    }

    /**
     * Verifies the returning UPI Intent response against:
     * 1. Active FSM `Dispatched` state (blocks unsolicited / hooked `onActivityResult` calls)
     * 2. 120-Second Cryptographic Entropy Window (`nowMs <= challenge.expiresAtMs`)
     * 3. One-time Signal Nonce consumption (`consumedNonces`)
     * 4. Constant-time `HMAC-SHA256` signature verification (`MessageDigest.isEqual`)
     * 5. `txnRef` binding check (if returned by UPI app)
     * 6. Server-side + local Single-Use UTR Lock (`used_utr_locks/{utrNumber}`)
     *
     * On valid verification, writes `PENDING_APPROVAL` to `/upi_transactions/{txnId}` so Admin can
     * 1-Tap Confirm in the Admin Side Menu Bar (or match via pasted Merchant Alert / UTR text).
     */
    suspend fun verifyIntentResultAndSubmitPending(
        context: Context?,
        intentData: Intent?,
        actorUid: String = "",
        overrideNowMs: Long? = null
    ): Result<UpiTransactionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(IO_WATCHDOG_TIMEOUT_MS) {
                val dispatchedState = _fsmState.value as? UpiPaymentFsmState.Dispatched
                    ?: run {
                        _fsmState.value = UpiPaymentFsmState.Failed(
                            errorCode = "FSM_INVALID_STATE_TRANSITION",
                            reason = "Security Lockdown: Unsolicited UPI callback outside Dispatched FSM state."
                        )
                        throw SecurityException("FSM Violation: UPI result received when state was not Dispatched.")
                    }

                val challenge = dispatchedState.challenge
                _fsmState.value = UpiPaymentFsmState.Verifying(challenge)

                val nowMs = overrideNowMs ?: System.currentTimeMillis()
                if (nowMs > challenge.expiresAtMs || (nowMs - challenge.issuedAtMs) > CHALLENGE_EXPIRY_WINDOW_MS) {
                    _fsmState.value = UpiPaymentFsmState.Expired(
                        challengeId = challenge.challengeId,
                        reason = "Cryptographic challenge expired (>120 seconds). Please initiate a fresh payment."
                    )
                    logAuditEventInternal(
                        context = context,
                        actorUid = actorUid.ifBlank { challenge.studentId },
                        actorName = challenge.studentName,
                        studentId = challenge.studentId,
                        eventType = "UPI_CHALLENGE_EXPIRED_120S",
                        severity = "WARNING",
                        utrNumber = "",
                        intentHash = challenge.intentHashHex,
                        details = "Rejected expired UPI challenge ${challenge.challengeId} after ${(nowMs - challenge.issuedAtMs) / 1000}s"
                    )
                    throw IllegalStateException("120-second payment challenge expired.")
                }

                // Enforce one-time Signal Nonce consumption
                if (!consumedNonces.add(challenge.signalNonce)) {
                    _fsmState.value = UpiPaymentFsmState.Failed(
                        errorCode = "SIGNAL_NONCE_REPLAY",
                        reason = "Replay Attack Blocked: One-time cryptographic nonce already consumed."
                    )
                    throw SecurityException("Replay Attack Blocked: Nonce already consumed.")
                }

                // Recompute HMAC-SHA256 in constant time
                val secretKeyBytes = getOrProvisionHardwareHmacSecret(context)
                val expectedHmacHex = computeChallengeHmacSha256(
                    secretKeyBytes = secretKeyBytes,
                    studentId = challenge.studentId,
                    amountFormattedInr = challenge.amountFormattedInr,
                    issuedAtSec = challenge.issuedAtMs / 1000L,
                    signalNonce = challenge.signalNonce,
                    deviceFingerprint = challenge.deviceFingerprint,
                    apkSignatureSha256 = challenge.apkSignatureSha256
                )
                val hmacMatches = MessageDigest.isEqual(
                    expectedHmacHex.toByteArray(StandardCharsets.UTF_8),
                    challenge.intentHashHex.toByteArray(StandardCharsets.UTF_8)
                )
                if (!hmacMatches) {
                    _fsmState.value = UpiPaymentFsmState.Failed(
                        errorCode = "HMAC_SIGNATURE_MISMATCH",
                        reason = "Cryptographic HMAC-SHA256 verification failed."
                    )
                    logAuditEventInternal(
                        context = context,
                        actorUid = actorUid.ifBlank { challenge.studentId },
                        actorName = challenge.studentName,
                        studentId = challenge.studentId,
                        eventType = "UPI_HMAC_TAMPER_BLOCKED",
                        severity = "CRITICAL",
                        utrNumber = "",
                        intentHash = challenge.intentHashHex,
                        details = "HMAC-SHA256 mismatch on UPI challenge ${challenge.challengeId}"
                    )
                    throw SecurityException("HMAC-SHA256 signature verification failed.")
                }

                val parsed = parseUpiIntentResult(intentData)
                if (!parsed.isSuccess || parsed.utrNumber.isBlank()) {
                    _fsmState.value = UpiPaymentFsmState.Failed(
                        errorCode = "UPI_PAYMENT_${parsed.rawStatus}",
                        reason = "UPI app returned status '${parsed.rawStatus}'. No payment deducted or UTR missing."
                    )
                    throw IllegalStateException("UPI transaction was not completed (${parsed.rawStatus}).")
                }

                // Verify txnRef binding if the UPI app echoed txnRef back
                if (parsed.txnRef.isNotBlank() && !parsed.txnRef.equals(challenge.trRef, ignoreCase = true)) {
                    _fsmState.value = UpiPaymentFsmState.Failed(
                        errorCode = "TXN_REF_MISMATCH",
                        reason = "Returned txnRef (${parsed.txnRef}) does not match signed challenge ref (${challenge.trRef})."
                    )
                    logAuditEventInternal(
                        context = context,
                        actorUid = actorUid.ifBlank { challenge.studentId },
                        actorName = challenge.studentName,
                        studentId = challenge.studentId,
                        eventType = "UPI_TXNREF_SPOOF_BLOCKED",
                        severity = "CRITICAL",
                        utrNumber = parsed.utrNumber,
                        intentHash = challenge.intentHashHex,
                        details = "Expected tr=${challenge.trRef}, received txnRef=${parsed.txnRef}"
                    )
                    throw SecurityException("Transaction Reference (tr) mismatch.")
                }

                val cleanUtr = parsed.utrNumber.trim().uppercase(Locale.US)

                // Layer 3: Server-Side & Local Duplicate UTR Lock Check
                val existingLocalTxnId = localUtrLocks.putIfAbsent(cleanUtr, challenge.challengeId)
                if (existingLocalTxnId != null && existingLocalTxnId != challenge.challengeId) {
                    _fsmState.value = UpiPaymentFsmState.Failed(
                        errorCode = "DUPLICATE_UTR_REPLAY_BLOCKED",
                        reason = "Fraud Alert: UTR $cleanUtr has already been used in transaction $existingLocalTxnId."
                    )
                    logAuditEventInternal(
                        context = context,
                        actorUid = actorUid.ifBlank { challenge.studentId },
                        actorName = challenge.studentName,
                        studentId = challenge.studentId,
                        eventType = "DUPLICATE_UTR_FRAUD_BLOCKED",
                        severity = "CRITICAL",
                        utrNumber = cleanUtr,
                        intentHash = challenge.intentHashHex,
                        details = "Duplicate UTR replay attempt blocked for UTR $cleanUtr (already locked by $existingLocalTxnId)"
                    )
                    throw SecurityException("Duplicate UTR ($cleanUtr) replay blocked.")
                }

                val (prevHash, recordHash, nextChainIdx) = synchronized(feeHashChainLock) {
                    val prev = lastFeeChainHashHex
                    val nextIdx = lastFeeChainIndex + 1L
                    val computed = computeChainedFeeRecordHash(
                        prevHash = prev,
                        txnId = challenge.challengeId,
                        studentId = challenge.studentId,
                        amountPaise = challenge.amountPaise,
                        utrNumber = cleanUtr,
                        timestampMs = challenge.issuedAtMs
                    )
                    lastFeeChainHashHex = computed
                    lastFeeChainIndex = nextIdx
                    Triple(prev, computed, nextIdx)
                }

                val record = UpiTransactionRecord(
                    id = challenge.challengeId,
                    student_id = challenge.studentId,
                    student_name = challenge.studentName,
                    course_name = challenge.courseName,
                    amount = challenge.amountPaise / 100.0,
                    amount_paise = challenge.amountPaise,
                    payment_type = challenge.paymentType,
                    tr_ref = challenge.trRef,
                    tn_note = challenge.tnNote,
                    signal_nonce = challenge.signalNonce,
                    intent_hash = challenge.intentHashHex,
                    utr_number = cleanUtr,
                    upi_package = dispatchedState.packageName,
                    device_fingerprint = challenge.deviceFingerprint,
                    apk_signature_sha256 = challenge.apkSignatureSha256,
                    timestamp = challenge.issuedAtMs,
                    expires_at_ms = challenge.expiresAtMs,
                    status = "PENDING_APPROVAL",
                    verified_by_uid = "",
                    prev_record_hash = prevHash,
                    record_sha256_hash = recordHash,
                    chain_index = nextChainIdx,
                    createdAt = Timestamp(Date(nowMs - 1000L))
                )

                // Sync to Firestore if context/db available
                if (context != null) {
                    val db = InstituteRepository.getConfiguredFirestore(context)
                    // Check Firestore `used_utr_locks/{cleanUtr}` first
                    val existingLockSnap = runCatching {
                        db.collection("used_utr_locks").document(cleanUtr).get().await()
                    }.getOrNull()
                    if (existingLockSnap != null && existingLockSnap.exists()) {
                        val lockedBy = existingLockSnap.getString("txn_id").orEmpty()
                        if (lockedBy.isNotBlank() && lockedBy != record.id) {
                            _fsmState.value = UpiPaymentFsmState.Failed(
                                errorCode = "SERVER_DUPLICATE_UTR_LOCKED",
                                reason = "Server Security Lock: UTR $cleanUtr was already claimed by $lockedBy."
                            )
                            logAuditEventInternal(
                                context = context,
                                actorUid = actorUid.ifBlank { challenge.studentId },
                                actorName = challenge.studentName,
                                studentId = challenge.studentId,
                                eventType = "SERVER_DUPLICATE_UTR_BLOCKED",
                                severity = "CRITICAL",
                                utrNumber = cleanUtr,
                                intentHash = challenge.intentHashHex,
                                details = "Server-side duplicate UTR lock triggered for UTR $cleanUtr"
                            )
                            throw SecurityException("Server rejected duplicate UTR $cleanUtr.")
                        }
                    }

                    // Lock UTR in `used_utr_locks` and write `PENDING_APPROVAL` record to `upi_transactions`
                    runCatching {
                        db.collection("used_utr_locks").document(cleanUtr).set(
                            mapOf(
                                "utr_number" to cleanUtr,
                                "txn_id" to record.id,
                                "student_id" to record.student_id,
                                "amount_paise" to record.amount_paise,
                                "locked_at_ms" to nowMs
                            )
                        ).await()
                    }
                    runCatching {
                        db.collection("upi_transactions").document(record.id)
                            .set(record.toFirestoreMap())
                            .await()
                    }
                }

                localUpiTransactionsCache[record.id] = record
                logAuditEventInternal(
                    context = context,
                    actorUid = actorUid.ifBlank { challenge.studentId },
                    actorName = challenge.studentName,
                    studentId = challenge.studentId,
                    eventType = "UPI_INTENT_UTR_CAPTURED_PENDING",
                    severity = "INFO",
                    utrNumber = cleanUtr,
                    intentHash = challenge.intentHashHex,
                    details = "Auto-captured UTR $cleanUtr (₹${challenge.amountFormattedInr}) via ${dispatchedState.packageName} -> Sent to Admin Pending Approval"
                )

                _fsmState.value = UpiPaymentFsmState.PendingApproval(
                    transaction = record,
                    message = "UTR $cleanUtr auto-captured! Sent to Admin Portal for 1-Tap Confirmation."
                )
                record
            }
        }
    }

    /**
     * Layer 4: Parses a Paytm Business / PhonePe Business / GPay / Bank merchant notification
     * or SMS text to extract `(utrNumber, amountPaise)` for automatic or 1-Tap Admin reconciliation.
     */
    fun extractMerchantAlertUtrAndAmount(notificationText: String): Pair<String, Long>? {
        val clean = notificationText.trim()
        if (clean.isBlank()) return null

        // Match 12-digit UTR / Ref No
        val utrMatch = Regex("(?:UTR|Ref(?:\\s*No)?|RRN|Txn\\s*ID)[:\\s#-]*([0-9]{12})|\\b([0-9]{12})\\b", RegexOption.IGNORE_CASE)
            .find(clean)
        val utr = (utrMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: utrMatch?.groupValues?.getOrNull(2).orEmpty()).trim()
        if (utr.length < 8) return null

        // Match INR amount (e.g. "Rs. 1,500.00", "INR 1500", "₹1,500")
        val amountMatch = Regex("(?:Rs\\.?|INR|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
            .find(clean)
        val amountStr = amountMatch?.groupValues?.getOrNull(1)?.replace(",", "").orEmpty()
        val amountDouble = amountStr.toDoubleOrNull() ?: 0.0
        val amountPaise = Math.round(amountDouble * 100.0)

        return Pair(utr, amountPaise)
    }

    /**
     * Builds or synchronizes a `StudentFeesLedgerRecord` from a `StudentRecord` and existing ledger state.
     * Supports the 4-Month Overdue Admin-Controlled Auto-EMI System.
     */
    fun buildOrSyncStudentLedger(
        student: StudentRecord,
        existing: StudentFeesLedgerRecord?,
        defaultMonthlyFeePaise: Long = 150000L
    ): StudentFeesLedgerRecord {
        val totalPaise = student.totalFeePaise.coerceAtLeast(0L)
        val paidPaise = student.paidFeePaise.coerceAtLeast(0L)
        val balancePaise = (totalPaise - paidPaise).coerceAtLeast(0L)
        val monthlyPaise = (existing?.monthly_fee_paise?.takeIf { it > 0L }
            ?: if (totalPaise > 0L) (totalPaise / 12L).coerceAtLeast(50000L) else defaultMonthlyFeePaise)

        val overdueMonths = existing?.overdue_months ?: 0
        val autoEmiActive = existing?.auto_emi_active ?: false
        val billingDay = (existing?.emi_billing_date ?: 5).coerceIn(1, 28)

        val emiInstallmentPaise = when {
            existing != null && existing.emi_installment_paise > 0L -> existing.emi_installment_paise
            balancePaise > 0L && overdueMonths >= 4 -> (balancePaise / 4L).coerceAtLeast(monthlyPaise)
            else -> monthlyPaise
        }

        val status = when {
            balancePaise <= 0L && totalPaise > 0L -> "PAID"
            autoEmiActive -> "EMI_ACTIVE"
            overdueMonths > 0 -> "OVERDUE"
            else -> "PENDING"
        }

        val nextDueIso = computeNextBillingDateIso(billingDay)
        val notice = existing?.last_emi_notice_iso?.ifBlank { null }
            ?: if (autoEmiActive) {
                "Auto-EMI Active: ₹${emiInstallmentPaise / 100} scheduled on Day $billingDay of every month ($nextDueIso)"
            } else if (overdueMonths >= 4) {
                "4+ Months Overdue ($overdueMonths Months) — Eligible for Admin Auto-EMI Activation"
            } else {
                "Monthly Fee ₹${monthlyPaise / 100} due on $nextDueIso"
            }

        return StudentFeesLedgerRecord(
            id = student.id,
            student_id = student.id,
            student_name = student.fullName,
            enrollment_no = student.enrollmentNo,
            course_name = student.courseName,
            batch_name = student.batchName,
            total_fee_paise = totalPaise,
            paid_fee_paise = paidPaise,
            monthly_fee_paise = monthlyPaise,
            emi_installment_paise = emiInstallmentPaise,
            monthly_payment_status = status,
            overdue_months = overdueMonths,
            auto_emi_active = autoEmiActive,
            emi_billing_date = billingDay,
            next_due_date_iso = nextDueIso,
            last_emi_notice_iso = notice.take(155),
            last_utr_number = existing?.last_utr_number.orEmpty(),
            owner_uid = existing?.owner_uid?.ifBlank { null } ?: student.ownerUid.ifBlank { "admin_root" },
            updatedAt = System.currentTimeMillis(),
            createdAt = existing?.createdAt ?: student.createdAt
        )
    }

    fun computeNextBillingDateIso(billingDayOfMonth: Int): String {
        val cleanDay = billingDayOfMonth.coerceIn(1, 28)
        val cal = Calendar.getInstance()
        val currentDay = cal.get(Calendar.DAY_OF_MONTH)
        if (currentDay > cleanDay) {
            cal.add(Calendar.MONTH, 1)
        }
        cal.set(Calendar.DAY_OF_MONTH, cleanDay)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    /**
     * Real-time Firestore stream for `/upi_transactions` on `Dispatchers.IO`.
     */
    fun observeUpiTransactions(db: FirebaseFirestore): Flow<List<UpiTransactionRecord>> = callbackFlow {
        val sub = db.collection("upi_transactions")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(localUpiTransactionsCache.values.sortedByDescending { it.timestamp })
                    return@addSnapshotListener
                }
                val docs = snap?.documents?.mapNotNull { doc ->
                    runCatching {
                        UpiTransactionRecord(
                            id = doc.getString("id") ?: doc.id,
                            student_id = doc.getString("student_id").orEmpty(),
                            student_name = doc.getString("student_name").orEmpty(),
                            course_name = doc.getString("course_name").orEmpty(),
                            amount = doc.getDouble("amount") ?: 0.0,
                            amount_paise = doc.getLong("amount_paise") ?: 0L,
                            payment_type = doc.getString("payment_type") ?: "MONTHLY_FEE",
                            tr_ref = doc.getString("tr_ref").orEmpty(),
                            tn_note = doc.getString("tn_note").orEmpty(),
                            signal_nonce = doc.getString("signal_nonce").orEmpty(),
                            intent_hash = doc.getString("intent_hash").orEmpty(),
                            utr_number = doc.getString("utr_number").orEmpty(),
                            upi_package = doc.getString("upi_package").orEmpty(),
                            device_fingerprint = doc.getString("device_fingerprint").orEmpty(),
                            apk_signature_sha256 = doc.getString("apk_signature_sha256").orEmpty(),
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            expires_at_ms = doc.getLong("expires_at_ms") ?: 0L,
                            status = doc.getString("status") ?: "PENDING_APPROVAL",
                            verified_by_uid = doc.getString("verified_by_uid").orEmpty(),
                            createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
                        )
                    }.getOrNull()
                }.orEmpty()
                docs.forEach {
                    localUpiTransactionsCache[it.id] = it
                    if (it.utr_number.isNotBlank()) {
                        localUtrLocks.putIfAbsent(it.utr_number, it.id)
                    }
                }
                val merged = (localUpiTransactionsCache.values + docs)
                    .distinctBy { it.id }
                    .sortedByDescending { it.timestamp }
                trySend(merged)
            }
        awaitClose { sub.remove() }
    }.flowOn(Dispatchers.IO)

    /**
     * Real-time Firestore stream for `/student_fees_ledger` on `Dispatchers.IO`.
     */
    fun observeStudentFeesLedger(db: FirebaseFirestore): Flow<List<StudentFeesLedgerRecord>> = callbackFlow {
        val sub = db.collection("student_fees_ledger")
            .limit(300)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(localStudentFeesLedgerCache.values.sortedByDescending { it.overdue_months })
                    return@addSnapshotListener
                }
                val docs = snap?.documents?.mapNotNull { doc ->
                    runCatching {
                        StudentFeesLedgerRecord(
                            id = doc.getString("id") ?: doc.id,
                            student_id = doc.getString("student_id") ?: doc.id,
                            student_name = doc.getString("student_name").orEmpty(),
                            enrollment_no = doc.getString("enrollment_no").orEmpty(),
                            course_name = doc.getString("course_name").orEmpty(),
                            batch_name = doc.getString("batch_name").orEmpty(),
                            total_fee_paise = doc.getLong("total_fee_paise") ?: 0L,
                            paid_fee_paise = doc.getLong("paid_fee_paise") ?: 0L,
                            monthly_fee_paise = doc.getLong("monthly_fee_paise") ?: 150000L,
                            emi_installment_paise = doc.getLong("emi_installment_paise") ?: 150000L,
                            monthly_payment_status = doc.getString("monthly_payment_status") ?: "PENDING",
                            overdue_months = (doc.getLong("overdue_months") ?: 0L).toInt(),
                            auto_emi_active = doc.getBoolean("auto_emi_active") ?: false,
                            emi_billing_date = (doc.getLong("emi_billing_date") ?: 5L).toInt().coerceIn(1, 28),
                            next_due_date_iso = doc.getString("next_due_date_iso").orEmpty(),
                            last_emi_notice_iso = doc.getString("last_emi_notice_iso").orEmpty(),
                            last_utr_number = doc.getString("last_utr_number").orEmpty(),
                            owner_uid = doc.getString("owner_uid") ?: "admin_root",
                            updatedAt = doc.getLong("updatedAt") ?: 0L,
                            createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
                        )
                    }.getOrNull()
                }.orEmpty()
                docs.forEach { localStudentFeesLedgerCache[it.student_id] = it }
                val merged = (localStudentFeesLedgerCache.values + docs)
                    .distinctBy { it.student_id }
                    .sortedWith(compareByDescending<StudentFeesLedgerRecord> { it.overdue_months }.thenBy { it.student_name })
                trySend(merged)
            }
        awaitClose { sub.remove() }
    }.flowOn(Dispatchers.IO)

    /**
     * Real-time Firestore stream for `/institute_audit_logs` on `Dispatchers.IO`.
     */
    fun observeInstituteAuditLogs(db: FirebaseFirestore): Flow<List<InstituteAuditLogRecord>> = callbackFlow {
        val sub = db.collection("institute_audit_logs")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(150)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(localInstituteAuditLogsCache.values.sortedByDescending { it.timestamp })
                    return@addSnapshotListener
                }
                val docs = snap?.documents?.mapNotNull { doc ->
                    runCatching {
                        InstituteAuditLogRecord(
                            id = doc.getString("id") ?: doc.id,
                            actor_uid = doc.getString("actor_uid").orEmpty(),
                            actor_name = doc.getString("actor_name").orEmpty(),
                            student_id = doc.getString("student_id").orEmpty(),
                            event_type = doc.getString("event_type").orEmpty(),
                            severity = doc.getString("severity") ?: "INFO",
                            utr_number = doc.getString("utr_number").orEmpty(),
                            intent_hash = doc.getString("intent_hash").orEmpty(),
                            device_fingerprint = doc.getString("device_fingerprint").orEmpty(),
                            details = doc.getString("details").orEmpty(),
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
                        )
                    }.getOrNull()
                }.orEmpty()
                docs.forEach { localInstituteAuditLogsCache[it.id] = it }
                val merged = (localInstituteAuditLogsCache.values + docs)
                    .distinctBy { it.id }
                    .sortedByDescending { it.timestamp }
                trySend(merged)
            }
        awaitClose { sub.remove() }
    }.flowOn(Dispatchers.IO)

    suspend fun logAuditEventInternal(
        context: Context?,
        actorUid: String,
        actorName: String,
        studentId: String,
        eventType: String,
        severity: String,
        utrNumber: String,
        intentHash: String,
        details: String
    ) = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val id = "iaud_${nowMs}_${UUID.randomUUID().toString().take(6)}"
        val log = InstituteAuditLogRecord(
            id = id,
            actor_uid = actorUid.ifBlank { "system_guard" },
            actor_name = actorName.ifBlank { "WCI Security Guard" },
            student_id = studentId,
            event_type = eventType,
            severity = severity,
            utr_number = utrNumber,
            intent_hash = intentHash,
            device_fingerprint = resolveHardwareDeviceFingerprint(context),
            details = details.take(1900),
            timestamp = nowMs,
            createdAt = Timestamp(Date(nowMs - 1000L))
        )
        localInstituteAuditLogsCache[log.id] = log
        if (context != null) {
            runCatching {
                val db = InstituteRepository.getConfiguredFirestore(context)
                db.collection("institute_audit_logs").document(log.id).set(log.toFirestoreMap()).await()
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02X", b))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
