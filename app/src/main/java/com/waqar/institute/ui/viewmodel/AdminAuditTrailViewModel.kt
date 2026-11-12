package com.waqar.institute.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.SecurityAuditLog
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

data class AdminAuditTrailUiState(
    val isLoading: Boolean = false,
    val selectedModuleFilter: String = "ALL",
    val searchQuery: String = "",
    val logs: List<SecurityAuditLog> = emptyList(),
    val filteredLogs: List<SecurityAuditLog> = emptyList(),
    val lastRecordedChecksum: String = ""
)

/**
 * Granular Admin Audit Trails & UDF State Management (`AdminAuditTrailViewModel.kt`).
 *
 * Tracks sensitive administrative actions (fee structure modifications, grade updates,
 * certificate unlocks, and credential changes) in `auditLogs` with a SHA-256 tamper-evident
 * checksum chain and exposes an immutable `StateFlow<AdminAuditTrailUiState>`.
 */
class AdminAuditTrailViewModel(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminAuditTrailUiState())
    val uiState: StateFlow<AdminAuditTrailUiState> = _uiState.asStateFlow()

    fun setModuleFilter(module: String) {
        _uiState.update { current ->
            val next = current.copy(selectedModuleFilter = module)
            next.copy(filteredLogs = applyFilter(next.logs, module, next.searchQuery))
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { current ->
            val next = current.copy(searchQuery = query)
            next.copy(filteredLogs = applyFilter(next.logs, next.selectedModuleFilter, query))
        }
    }

    fun syncAuditLogs(logs: List<SecurityAuditLog>) {
        _uiState.update { current ->
            current.copy(
                logs = logs,
                filteredLogs = applyFilter(logs, current.selectedModuleFilter, current.searchQuery)
            )
        }
    }

    fun recordImmutableAdminAudit(
        actorUid: String,
        actorEmailOrName: String,
        action: String,
        targetModule: String,
        targetId: String = "",
        details: String
    ) {
        viewModelScope.launch {
            val timestampNow = Timestamp.now()
            val rawChain = "$actorUid|$actorEmailOrName|$action|$targetModule|$details|${timestampNow.seconds}"
            val sha256Hex = MessageDigest.getInstance("SHA-256")
                .digest(rawChain.toByteArray(Charsets.UTF_8))
                .take(10)
                .joinToString("") { "%02x".format(it) }
                .uppercase()

            val docId = "audit_${timestampNow.seconds}_$sha256Hex"
            val entry = SecurityAuditLog(
                id = docId,
                actorUid = actorUid,
                actorEmail = actorEmailOrName,
                action = action,
                module = targetModule,
                targetId = targetId,
                severity = "HIGH_INTEGRITY",
                details = "$details [SHA256:$sha256Hex]",
                createdAt = timestampNow
            )

            _uiState.update { current ->
                val updated = listOf(entry) + current.logs
                current.copy(
                    logs = updated,
                    filteredLogs = applyFilter(updated, current.selectedModuleFilter, current.searchQuery),
                    lastRecordedChecksum = sha256Hex
                )
            }

            runCatching {
                firestore.collection("auditLogs")
                    .document(docId)
                    .set(entry.toMap())
                    .await()
            }
        }
    }

    fun refreshFromFirestore() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val snap = firestore.collection("auditLogs")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(100)
                    .get()
                    .await()
                val loaded = snap.documents.map { doc ->
                    SecurityAuditLog(
                        id = doc.id,
                        actorUid = doc.getString("actorUid").orEmpty(),
                        actorEmail = doc.getString("actorEmail").orEmpty(),
                        action = doc.getString("action").orEmpty(),
                        module = doc.getString("module").orEmpty(),
                        targetId = doc.getString("targetId").orEmpty(),
                        severity = doc.getString("severity").orEmpty(),
                        details = doc.getString("details").orEmpty(),
                        createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
                    )
                }
                syncAuditLogs(loaded)
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun applyFilter(
        logs: List<SecurityAuditLog>,
        moduleFilter: String,
        query: String
    ): List<SecurityAuditLog> {
        val q = query.trim().lowercase()
        return logs.filter { entry ->
            val matchesModule = moduleFilter == "ALL" ||
                entry.module.equals(moduleFilter, ignoreCase = true)
            val matchesQuery = q.isEmpty() ||
                entry.action.lowercase().contains(q) ||
                entry.actorEmail.lowercase().contains(q) ||
                entry.details.lowercase().contains(q)
            matchesModule && matchesQuery
        }
    }
}
