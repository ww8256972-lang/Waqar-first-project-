package com.waqar.institute.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firestore.InstituteRepository
import com.example.data.model.SecurityAuditLog
import com.waqar.institute.security.SecurityIncidentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Admin Security Alerts & Account Lock ViewModel (`AdminSecurityAlertsViewModel.kt`).
 *
 * Streams real-time security violation logs for the Admin Portal Security Command Center
 * and enables 1-tap locking/revocation of compromised Student credentials or sessions.
 */
class AdminSecurityAlertsViewModel(
    private val repository: InstituteRepository
) : ViewModel() {

    private val _securityAlerts = MutableStateFlow<List<SecurityAuditLog>>(emptyList())
    val securityAlerts: StateFlow<List<SecurityAuditLog>> = _securityAlerts.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAuditLogs()
                .catch { }
                .collect { logs ->
                    _securityAlerts.value = logs.filter {
                        it.severity == "CRITICAL" ||
                            it.severity == "WARNING" ||
                            it.action.startsWith("SECURITY_")
                    }
                }
        }
    }

    fun triggerSimulatedOrLiveSecurityAuditAlert(
        context: Context,
        actorUid: String,
        username: String,
        fullName: String,
        phone: String,
        studentId: String,
        violationType: String,
        details: String
    ) {
        SecurityIncidentLogger.dispatchSecurityViolation(
            context = context,
            repository = repository,
            actorUid = actorUid,
            username = username,
            fullName = fullName,
            phoneNumber = phone,
            studentIdOrEnrollment = studentId,
            violationType = violationType,
            violationDetails = details
        )
    }

    /** Diagnostic self-test alias for Admin Telemetry Verification Drill. */
    fun runTelemetryDiagnosticDrill(
        context: Context,
        actorUid: String,
        username: String,
        fullName: String,
        phone: String,
        studentId: String,
        violationType: String,
        details: String
    ) = triggerSimulatedOrLiveSecurityAuditAlert(
        context = context,
        actorUid = actorUid,
        username = username,
        fullName = fullName,
        phone = phone,
        studentId = studentId,
        violationType = violationType,
        details = details
    )

    private val _revokedDevices = MutableStateFlow<List<com.waqar.institute.security.DeviceRevocationGuard.RevokedDeviceEntry>>(emptyList())
    val revokedDevices: StateFlow<List<com.waqar.institute.security.DeviceRevocationGuard.RevokedDeviceEntry>> = _revokedDevices.asStateFlow()

    fun refreshRevokedDevices() {
        viewModelScope.launch {
            _revokedDevices.value = com.waqar.institute.security.DeviceRevocationGuard.fetchRevokedDevices()
        }
    }

    /**
     * Layer 4 — Admin 1-Tap Compromised Device Revocation writing `/revoked_devices/{deviceFp}`.
     */
    fun revokeDeviceFingerprint(
        deviceFingerprint: String,
        reason: String,
        adminUid: String,
        onComplete: (Result<com.waqar.institute.security.DeviceRevocationGuard.RevokedDeviceEntry>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = com.waqar.institute.security.DeviceRevocationGuard.revokeDevice(
                deviceFingerprint = deviceFingerprint,
                reason = reason,
                adminUid = adminUid
            )
            if (res.isSuccess) {
                refreshRevokedDevices()
            }
            onComplete(res)
        }
    }

    /**
     * Layer 4 — Admin 1-Tap Restore of a previously revoked device fingerprint.
     */
    fun unrevokeDeviceFingerprint(
        deviceFingerprint: String,
        adminUid: String,
        onComplete: (Result<com.waqar.institute.security.DeviceRevocationGuard.RevokedDeviceEntry>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = com.waqar.institute.security.DeviceRevocationGuard.unrevokeDevice(
                deviceFingerprint = deviceFingerprint,
                adminUid = adminUid
            )
            if (res.isSuccess) {
                refreshRevokedDevices()
            }
            onComplete(res)
        }
    }

    /**
     * Unlocks a student's 24-hour AI Tutor Security Cage in Firestore `ai_tutor_locks/{studentUid}`
     * and local monotonic storage (Admin-only action from Admin Security Alerts).
     */
    fun unlockStudentAiTutor(
        context: Context,
        studentUid: String,
        onComplete: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = com.waqar.institute.ai.AiRateLimiterManager.unlockStudentAiTutorByAdmin(
                context = context,
                studentUid = studentUid
            )
            onComplete(res)
        }
    }
}

