package com.waqar.institute.security

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.data.firestore.InstituteRepository
import com.example.ota.OtaDynamicManager
import com.example.security.SessionFingerprintManager
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.waqar.institute.updater.GitHubOtaUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Layer 4 — Central Remote Kill-Switch & 1-Tap Compromised Device Revocation Guard
 * (`DeviceRevocationGuard.kt`).
 *
 * Enforces two non-dismissible security lockdown conditions on app startup and `ON_RESUME`:
 * 1. **Force Update Kill-Switch**: Compares `BuildConfig.VERSION_CODE` against Firebase Remote Config
 *    `min_supported_version_code`. Outdated/vulnerable APKs are locked behind a Force Update screen
 *    pointing directly to `GitHubOtaUpdater`.
 * 2. **Compromised Device Revocation**: Queries Cloud Firestore `/revoked_devices/{deviceFingerprint}`
 *    for `revoked == true`. Revoked devices are immediately signed out and locked out.
 */
object DeviceRevocationGuard {

    private const val TAG = "DeviceRevocationGuard"

    sealed class DeviceLockdownState {
        data object Allowed : DeviceLockdownState()

        data class ForceUpdateRequired(
            val currentVersionCode: Int,
            val minSupportedVersionCode: Long,
            val latestReleaseInfo: GitHubOtaUpdater.GitHubReleaseInfo? = null
        ) : DeviceLockdownState()

        data class DeviceRevoked(
            val deviceFingerprint: String,
            val reason: String,
            val revokedAtMs: Long = 0L
        ) : DeviceLockdownState()
    }

    data class RevokedDeviceEntry(
        val deviceFingerprint: String,
        val revoked: Boolean,
        val reason: String,
        val revokedByUid: String,
        val updatedAtMs: Long
    )

    private val _lockdownState = MutableStateFlow<DeviceLockdownState>(DeviceLockdownState.Allowed)
    val lockdownState: StateFlow<DeviceLockdownState> = _lockdownState.asStateFlow()

    /**
     * Pure version comparator for Force-Update kill-switch checks and deterministic unit testing.
     */
    fun isForceUpdateRequired(
        currentVersionCode: Int,
        minSupportedVersionCode: Long
    ): Boolean = minSupportedVersionCode > 0L && currentVersionCode.toLong() < minSupportedVersionCode

    /**
     * Pure revocation document evaluator for deterministic unit testing.
     */
    fun evaluateRevocationDocument(
        deviceFingerprint: String,
        revoked: Boolean,
        reason: String?,
        revokedAtMs: Long = 0L
    ): DeviceLockdownState {
        return if (revoked) {
            DeviceLockdownState.DeviceRevoked(
                deviceFingerprint = deviceFingerprint,
                reason = reason?.takeIf { it.isNotBlank() }
                    ?: "This device has been revoked by the Institute Administrator.",
                revokedAtMs = revokedAtMs
            )
        } else {
            DeviceLockdownState.Allowed
        }
    }

    /**
     * Runs both Remote Config `min_supported_version_code` check and Firestore `/revoked_devices/{fp}`
     * check. Safe to call on app launch and `ON_RESUME`.
     */
    suspend fun evaluateStartupAndResume(context: Context): DeviceLockdownState = withContext(Dispatchers.IO) {
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (isRobolectric) {
            return@withContext _lockdownState.value
        }

        // 1. Remote Config Force-Update Kill-Switch
        val otaConfig = runCatching {
            withTimeoutOrNull(4_000L) { OtaDynamicManager.initializeAndFetchOtaConfig() }
        }.getOrNull()

        if (otaConfig != null && isForceUpdateRequired(BuildConfig.VERSION_CODE, otaConfig.minSupportedVersionCode)) {
            val latestRelease = runCatching { GitHubOtaUpdater.checkForUpdate() }.getOrNull()
            val forceState = DeviceLockdownState.ForceUpdateRequired(
                currentVersionCode = BuildConfig.VERSION_CODE,
                minSupportedVersionCode = otaConfig.minSupportedVersionCode,
                latestReleaseInfo = latestRelease
            )
            _lockdownState.value = forceState
            return@withContext forceState
        }

        // 2. Cloud Firestore Compromised Device Revocation Check (when authenticated)
        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        val deviceFp = runCatching {
            SessionFingerprintManager.captureCurrentDeviceFingerprint(context.applicationContext ?: context)
        }.getOrDefault("")

        if (currentUser != null && deviceFp.isNotBlank()) {
            val safeFp = deviceFp.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
            val revokedState = runCatching {
                withTimeoutOrNull(3_500L) {
                    val snap = InstituteRepository.getConfiguredFirestore()
                        .collection("revoked_devices")
                        .document(safeFp)
                        .get()
                        .await()
                    if (snap.exists() && snap.getBoolean("revoked") == true) {
                        val reason = snap.getString("reason")
                            ?: "Device access was revoked by the Institute Administrator."
                        val updatedMs = snap.getLong("updatedAtMs") ?: System.currentTimeMillis()
                        runCatching { FirebaseAuth.getInstance().signOut() }
                        DeviceLockdownState.DeviceRevoked(
                            deviceFingerprint = safeFp,
                            reason = reason,
                            revokedAtMs = updatedMs
                        )
                    } else {
                        null
                    }
                }
            }.onFailure { err ->
                Log.d(TAG, "Revocation lookup skipped: ${err.localizedMessage}")
            }.getOrNull()

            if (revokedState != null) {
                _lockdownState.value = revokedState
                return@withContext revokedState
            }
        }

        _lockdownState.value = DeviceLockdownState.Allowed
        DeviceLockdownState.Allowed
    }

    /**
     * Admin 1-Tap Compromised Device Revocation: writes `/revoked_devices/{deviceFp}` with `revoked = true`.
     */
    suspend fun revokeDevice(
        deviceFingerprint: String,
        reason: String,
        adminUid: String
    ): Result<RevokedDeviceEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val safeFp = deviceFingerprint.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
            require(safeFp.length >= 4) { "Invalid device fingerprint." }
            val nowMs = System.currentTimeMillis()
            val entry = RevokedDeviceEntry(
                deviceFingerprint = safeFp,
                revoked = true,
                reason = reason.trim().ifBlank { "Compromised device revoked by Admin" },
                revokedByUid = adminUid.ifBlank { "admin_root" },
                updatedAtMs = nowMs
            )
            val data = mapOf(
                "deviceFingerprint" to entry.deviceFingerprint,
                "revoked" to true,
                "reason" to entry.reason,
                "revokedByUid" to entry.revokedByUid,
                "updatedAtMs" to entry.updatedAtMs,
                "updatedAt" to Timestamp.now()
            )
            InstituteRepository.getConfiguredFirestore()
                .collection("revoked_devices")
                .document(safeFp)
                .set(data)
                .await()
            entry
        }
    }

    /**
     * Admin 1-Tap Device Unrevoke: sets `revoked = false` in `/revoked_devices/{deviceFp}`.
     */
    suspend fun unrevokeDevice(
        deviceFingerprint: String,
        adminUid: String
    ): Result<RevokedDeviceEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val safeFp = deviceFingerprint.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
            require(safeFp.length >= 4) { "Invalid device fingerprint." }
            val nowMs = System.currentTimeMillis()
            val entry = RevokedDeviceEntry(
                deviceFingerprint = safeFp,
                revoked = false,
                reason = "Restored by Admin",
                revokedByUid = adminUid.ifBlank { "admin_root" },
                updatedAtMs = nowMs
            )
            val data = mapOf(
                "deviceFingerprint" to entry.deviceFingerprint,
                "revoked" to false,
                "reason" to entry.reason,
                "revokedByUid" to entry.revokedByUid,
                "updatedAtMs" to entry.updatedAtMs,
                "updatedAt" to Timestamp.now()
            )
            InstituteRepository.getConfiguredFirestore()
                .collection("revoked_devices")
                .document(safeFp)
                .set(data)
                .await()
            entry
        }
    }

    /**
     * Loads the list of currently tracked revoked devices from Firestore (Admin-only).
     */
    suspend fun fetchRevokedDevices(): List<RevokedDeviceEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val snap = InstituteRepository.getConfiguredFirestore()
                .collection("revoked_devices")
                .limit(100)
                .get()
                .await()
            snap.documents.map { doc ->
                RevokedDeviceEntry(
                    deviceFingerprint = doc.getString("deviceFingerprint") ?: doc.id,
                    revoked = doc.getBoolean("revoked") == true,
                    reason = doc.getString("reason").orEmpty(),
                    revokedByUid = doc.getString("revokedByUid").orEmpty(),
                    updatedAtMs = doc.getLong("updatedAtMs") ?: 0L
                )
            }.sortedByDescending { it.updatedAtMs }
        }.getOrDefault(emptyList())
    }

    internal fun resetForTests() {
        _lockdownState.value = DeviceLockdownState.Allowed
    }
}
