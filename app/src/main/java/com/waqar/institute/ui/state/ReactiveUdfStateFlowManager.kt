package com.waqar.institute.ui.state

import com.example.data.model.PortalRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UDF State Snapshot for all 4 Portals (Admin, Teacher, Student, Parent).
 */
data class EnterprisePortalUdfState(
    val activeRole: PortalRole = PortalRole.ADMIN,
    val activeRouteId: String = "M01_DASHBOARD",
    val isSyncingBackground: Boolean = false,
    val pendingOfflineMutationsCount: Int = 0,
    val lastSyncedEpochMs: Long = System.currentTimeMillis(),
    val integrityVerdictBadge: String = "MEETS_STRONG_INTEGRITY",
    val activeSessionTokenHash: String = ""
)

sealed interface EnterprisePortalIntent {
    data class SwitchPortalRole(val role: PortalRole) : EnterprisePortalIntent
    data class NavigateToRoute(val routeId: String) : EnterprisePortalIntent
    data class UpdatePendingMutations(val count: Int) : EnterprisePortalIntent
    data class MarkBackgroundSyncCompleted(val timestampMs: Long) : EnterprisePortalIntent
    data class UpdateIntegrityVerdict(val verdict: String) : EnterprisePortalIntent
}

sealed interface EnterprisePortalSideEffect {
    data class ShowToastBanner(val message: String) : EnterprisePortalSideEffect
    data class ForceSessionReauth(val reason: String) : EnterprisePortalSideEffect
}

/**
 * Reactive Unidirectional Data Flow (UDF) StateFlow Manager (`ReactiveUdfStateFlowManager.kt`).
 *
 * Enforces strict single-source-of-truth state transitions (`Intent -> Reducer -> Immutable StateFlow`)
 * and one-shot `SharedFlow` side effects across the Admin, Teacher, Student, and Parent portals.
 */
object ReactiveUdfStateFlowManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(EnterprisePortalUdfState())
    val state: StateFlow<EnterprisePortalUdfState> = _state.asStateFlow()

    private val _sideEffects = MutableSharedFlow<EnterprisePortalSideEffect>(extraBufferCapacity = 16)
    val sideEffects: SharedFlow<EnterprisePortalSideEffect> = _sideEffects.asSharedFlow()

    fun dispatch(intent: EnterprisePortalIntent) {
        _state.update { current -> reduce(current, intent) }
    }

    fun emitSideEffect(effect: EnterprisePortalSideEffect) {
        scope.launch {
            _sideEffects.emit(effect)
        }
    }

    private fun reduce(
        current: EnterprisePortalUdfState,
        intent: EnterprisePortalIntent
    ): EnterprisePortalUdfState {
        return when (intent) {
            is EnterprisePortalIntent.SwitchPortalRole -> current.copy(
                activeRole = intent.role,
                activeRouteId = "M01_DASHBOARD"
            )
            is EnterprisePortalIntent.NavigateToRoute -> current.copy(
                activeRouteId = intent.routeId
            )
            is EnterprisePortalIntent.UpdatePendingMutations -> current.copy(
                pendingOfflineMutationsCount = intent.count.coerceAtLeast(0)
            )
            is EnterprisePortalIntent.MarkBackgroundSyncCompleted -> current.copy(
                isSyncingBackground = false,
                lastSyncedEpochMs = intent.timestampMs
            )
            is EnterprisePortalIntent.UpdateIntegrityVerdict -> current.copy(
                integrityVerdictBadge = intent.verdict
            )
        }
    }
}
