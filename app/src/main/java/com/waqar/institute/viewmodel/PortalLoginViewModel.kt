package com.waqar.institute.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.auth.FirebaseAuthServiceProvider
import com.example.data.firestore.InstituteRepository
import com.example.data.model.PortalRole
import com.waqar.institute.security.MemoryScrubberManager
import com.waqar.institute.security.NetworkSecurityGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow

/**
 * Enterprise Portal Login ViewModel with Exponential Backoff Rate Limiting (5-Attempt Brute-Force Lockout),
 * MITM Proxy Verification, and Automatic Sensitive RAM Scrubbing after Authentication.
 */
data class PortalLoginSecurityState(
    val isAuthenticating: Boolean = false,
    val failedAttemptCount: Int = 0,
    val lockoutUntilEpochMillis: Long = 0L,
    val statusMessage: String? = null,
    val isAuthenticated: Boolean = false
) {
    fun isCurrentlyLockedOut(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis < lockoutUntilEpochMillis

    fun remainingLockoutSeconds(nowMillis: Long = System.currentTimeMillis()): Long =
        ((lockoutUntilEpochMillis - nowMillis).coerceAtLeast(0L) + 999L) / 1000L
}

class PortalLoginViewModel : ViewModel() {

    companion object {
        private const val MAX_FREE_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 300_000L // 5-minute block on 6th request in 60s window
        private const val MAX_LOCKOUT_MS = 300_000L  // 5 minutes cap
    }

    private val _securityState = MutableStateFlow(PortalLoginSecurityState())
    val securityState: StateFlow<PortalLoginSecurityState> = _securityState.asStateFlow()

    /**
     * Computes 5-minute (300,000 ms) lockout duration once attempts exceed [MAX_FREE_ATTEMPTS].
     */
    fun computeBackoffLockoutMs(failedAttempts: Int): Long {
        if (failedAttempts <= MAX_FREE_ATTEMPTS) return 0L
        return MAX_LOCKOUT_MS
    }

    fun authenticatePortalUser(
        context: Context,
        role: PortalRole,
        usernameInput: String,
        passwordChars: CharArray,
        onAuthenticated: (FirebaseAuthServiceProvider.AuthenticatedPortalSession) -> Unit,
        onError: (String) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val identityKey = "${role.key}:${usernameInput.trim().lowercase()}"
        val rateStatus = com.example.security.EnterpriseRateLimiter.inspectStatus(
            context = context,
            scope = com.example.security.EnterpriseRateLimiter.RateLimitScope.LOGIN,
            identityKey = identityKey,
            nowEpochMs = now
        )
        val current = _securityState.value
        if (!rateStatus.allowed || current.isCurrentlyLockedOut(now)) {
            val secs = if (!rateStatus.allowed) rateStatus.retryAfterSeconds else current.remainingLockoutSeconds(now)
            val msg = "HTTP 429 Too Many Requests: Rate limit exceeded (max 5 requests per 60s). Please wait ${secs}s before retrying."
            _securityState.update { state ->
                state.copy(
                    lockoutUntilEpochMillis = maxOf(state.lockoutUntilEpochMillis, rateStatus.blockedUntilEpochMs),
                    statusMessage = msg
                )
            }
            MemoryScrubberManager.scrubCharArray(passwordChars)
            onError(msg)
            return
        }

        viewModelScope.launch {
            _securityState.update { state -> state.copy(isAuthenticating = true, statusMessage = null) }
            try {
                // 1. Verify network security posture (detect active MITM proxy / injected user CAs)
                val networkReport = NetworkSecurityGuard.auditNetworkSecurity(context)
                if (!networkReport.isConnectionTrusted) {
                    val err = "Untrusted network proxy or custom CA certificate detected (${networkReport.reason})."
                    _securityState.update { state -> state.copy(isAuthenticating = false, statusMessage = err) }
                    onError(err)
                    return@launch
                }

                // 2. Authenticate against Cloud Firestore / PBKDF2-HMAC-SHA256 / Encrypted Keystore credential store
                val passwordString = String(passwordChars)
                val repository = InstituteRepository.create(context.applicationContext)
                val authProvider = FirebaseAuthServiceProvider(repository = repository, context = context)
                val result: Result<FirebaseAuthServiceProvider.AuthenticatedPortalSession> =
                    authProvider.signInToPortalWithCredentials(
                        activityContext = context,
                        selectedRole = role,
                        rawUsername = usernameInput,
                        rawPassword = passwordString
                    )

                result.fold(
                    onSuccess = { session: FirebaseAuthServiceProvider.AuthenticatedPortalSession ->
                        _securityState.update { state ->
                            state.copy(
                                isAuthenticating = false,
                                failedAttemptCount = 0,
                                lockoutUntilEpochMillis = 0L,
                                isAuthenticated = true,
                                statusMessage = null
                            )
                        }
                        onAuthenticated(session)
                    },
                    onFailure = { throwable: Throwable ->
                        val postStatus = com.example.security.EnterpriseRateLimiter.inspectStatus(
                            context = context,
                            scope = com.example.security.EnterpriseRateLimiter.RateLimitScope.LOGIN,
                            identityKey = identityKey
                        )
                        val updatedFailures = maxOf(_securityState.value.failedAttemptCount + 1, postStatus.attemptCountInWindow)
                        val lockoutUntil = if (!postStatus.allowed) {
                            postStatus.blockedUntilEpochMs
                        } else {
                            val d = computeBackoffLockoutMs(updatedFailures)
                            if (d > 0L) System.currentTimeMillis() + d else 0L
                        }
                        val message = throwable.localizedMessage ?: "Invalid username or password."
                        _securityState.update { state ->
                            state.copy(
                                isAuthenticating = false,
                                failedAttemptCount = updatedFailures,
                                lockoutUntilEpochMillis = lockoutUntil,
                                statusMessage = message
                            )
                        }
                        onError(message)
                    }
                )
            } finally {
                // 4. Immediately zero-fill password buffer in RAM
                MemoryScrubberManager.scrubCharArray(passwordChars)
            }
        }
    }
}
