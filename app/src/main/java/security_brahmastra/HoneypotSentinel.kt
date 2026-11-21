package security_brahmastra

import android.content.Context
import android.os.Build
import com.example.data.firestore.InstituteRepository
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1. `security_brahmastra/HoneypotSentinel.kt`
 * Cyber-Deception & Silent Shadow-Ban Engine.
 *
 * When tampering, Frida hooks, or biometric spoofing is detected, silently activates
 * `isShadowSession = true` via [triggerShadowMode], serves decoy exam questions and shadow
 * confirmation receipts on the UI, records an append-only audit entry in memory, AND flushes
 * real intrusion alerts asynchronously to authenticated Firestore `/securityAlerts`.
 */
object HoneypotSentinel {

    data class IntrusionHoneypotLog(
        val id: String,
        val reason: String,
        val actorId: String,
        val deviceFingerprint: String,
        val ipAddress: String,
        val snapshotDigestHex: String,
        val metadata: Map<String, String>,
        val timestampEpochMs: Long
    )

    data class DecoyExamQuestion(
        val questionId: String,
        val questionText: String,
        val options: List<String>
    )

    private val shadowSessionActive = AtomicBoolean(false)
    private val appendOnlyHoneypotLogs = CopyOnWriteArrayList<IntrusionHoneypotLog>()
    private val alertScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext ?: context
    }

    val isShadowSession: Boolean
        get() = shadowSessionActive.get()

    fun triggerShadowMode(
        reason: String,
        metadata: Map<String, String> = emptyMap()
    ): IntrusionHoneypotLog {
        shadowSessionActive.set(true)
        val now = System.currentTimeMillis()
        val entry = IntrusionHoneypotLog(
            id = "hp_${now}_${appendOnlyHoneypotLogs.size + 1}",
            reason = reason.trim().ifBlank { "UNSPECIFIED_INTRUSION" },
            actorId = metadata["actorId"] ?: "unknown_actor",
            deviceFingerprint = metadata["deviceFingerprint"] ?: "${Build.MANUFACTURER}_${Build.MODEL}",
            ipAddress = metadata["ip"] ?: "0.0.0.0",
            snapshotDigestHex = metadata["snapshotDigestHex"] ?: "",
            metadata = metadata.toMap(),
            timestampEpochMs = now
        )
        appendOnlyHoneypotLogs.add(entry)
        flushIncidentToFirestoreIfAuthenticated(entry)
        return entry
    }

    private fun flushIncidentToFirestoreIfAuthenticated(entry: IntrusionHoneypotLog) {
        val ctx = appContext ?: return
        if (Build.FINGERPRINT?.equals("robolectric", ignoreCase = true) == true) return
        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() ?: return

        alertScope.launch {
            runCatching {
                val firestore = InstituteRepository.getConfiguredFirestore(ctx)
                val alertPayload = mapOf(
                    "id" to entry.id,
                    "actorUid" to currentUser.uid,
                    "actorId" to entry.actorId.take(120),
                    "reason" to entry.reason.take(200),
                    "deviceFingerprint" to entry.deviceFingerprint.take(160),
                    "snapshotDigestHex" to entry.snapshotDigestHex.take(128),
                    "severity" to "CRITICAL",
                    "source" to "HoneypotSentinel",
                    "createdAt" to Timestamp(Date(entry.timestampEpochMs))
                )
                firestore.collection("securityAlerts")
                    .document(entry.id)
                    .set(alertPayload)
            }
        }
    }

    fun getDecoyExamQuestions(): List<DecoyExamQuestion> = listOf(
        DecoyExamQuestion(
            questionId = "DECOY_Q1",
            questionText = "Which OSI layer handles physical bitstream framing in legacy mainframe topologies?",
            options = listOf("Layer 1 (Physical)", "Layer 2 (Data Link)", "Layer 4 (Transport)", "Layer 7 (Application)")
        ),
        DecoyExamQuestion(
            questionId = "DECOY_Q2",
            questionText = "What is the default administrative port for standard local loopback diagnostics?",
            options = listOf("Port 8080", "Port 443", "Port 22", "Port 3306")
        )
    )

    fun buildFakeShadowSubmissionConfirmation(examId: String, studentId: String): Map<String, Any> = mapOf(
        "status" to "SUBMITTED_OK",
        "examId" to examId,
        "studentId" to studentId,
        "receiptCode" to "SHADOW-RCPT-${System.currentTimeMillis() % 100000}",
        "shadowDeception" to true
    )

    fun getIntrusionHoneypotLogs(): List<IntrusionHoneypotLog> = appendOnlyHoneypotLogs.toList()

    fun resetForTests() {
        shadowSessionActive.set(false)
        appendOnlyHoneypotLogs.clear()
    }
}
