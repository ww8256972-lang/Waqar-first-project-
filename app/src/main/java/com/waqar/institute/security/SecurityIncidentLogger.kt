package com.waqar.institute.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.data.firestore.InstituteRepository
import com.example.data.model.SecurityAuditLog
import com.google.firebase.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

/**
 * Real-Time Security Incident & User Identity Telemetry (`SecurityIncidentLogger.kt`).
 *
 * Dispatches non-blocking, high-priority security violation payloads to Cloud Firestore
 * (`auditLogs` collection) whenever a security rule is violated (Root/Magisk, Frida/Xposed,
 * MT Manager, Virtual Space, Concurrent Device Login, or Screen Recording attempt).
 *
 * Captures:
 * - Registered Student/User ID & Enrollment No
 * - Username & Full Name
 * - Registered Phone Number
 * - Exact Timestamp & Device Model
 * - Active Network Transport / VPN / Proxy state
 * - Exact Security Violation Type
 */
object SecurityIncidentLogger {

    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dispatchSecurityViolation(
        context: Context,
        repository: InstituteRepository,
        actorUid: String,
        username: String,
        fullName: String,
        phoneNumber: String,
        studentIdOrEnrollment: String,
        violationType: String,
        violationDetails: String
    ) {
        val networkState = inspectNetworkTransportState(context)
        val deviceDescriptor = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        val safeActorUid = actorUid.ifBlank { "cred_${username.ifBlank { "student" }.lowercase()}" }
        val actorIdentityEmail = "${username.ifBlank { "student" }}@waqarinstitute.edu"

        val formattedDetails = buildString {
            append("[VIOLATION: $violationType] ")
            append("User: $fullName (@$username) | ")
            append("ID: ${studentIdOrEnrollment.ifBlank { "N/A" }} | ")
            append("Phone: ${phoneNumber.ifBlank { "N/A" }} | ")
            append("Net: $networkState | ")
            append("Device: $deviceDescriptor | ")
            append(violationDetails)
        }.take(980)

        val log = SecurityAuditLog(
            id = "sec_${UUID.randomUUID().toString().take(8)}",
            actorUid = safeActorUid,
            actorEmail = actorIdentityEmail.take(150),
            action = "SECURITY_VIOLATION_${violationType.uppercase().take(40)}",
            module = "M22_SECURITY",
            targetId = studentIdOrEnrollment.ifBlank { username }.take(120),
            severity = "CRITICAL",
            details = formattedDetails,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000L))
        )

        telemetryScope.launch {
            runCatching {
                repository.logAuditEvent(log)
            }
        }
    }

    private fun inspectNetworkTransportState(context: Context): String {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching "UNKNOWN"
            val net = cm.activeNetwork ?: return@runCatching "OFFLINE"
            val caps = cm.getNetworkCapabilities(net) ?: return@runCatching "UNKNOWN"
            val transports = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WIFI")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("CELLULAR")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("VPN_TUNNEL")
            val proxyStatus = if (NetworkSecurityGuard.auditNetworkSecurity(context).proxyDetected) "PROXY_DETECTED" else "DIRECT_TLS"
            "${transports.joinToString("+").ifBlank { "ACTIVE" }}/$proxyStatus"
        }.getOrDefault("ACTIVE_TLS")
    }
}
