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
}
