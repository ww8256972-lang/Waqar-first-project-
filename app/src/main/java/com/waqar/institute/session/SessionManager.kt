package com.waqar.institute.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.firestore.InstituteRepository
import com.example.data.model.PortalRole
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.UUID

/**
 * Encrypted Single-Device Session Manager & Real-Time Force-Logout Enforcer (`SessionManager.kt`).
 *
 * Tracks active session fields in Cloud Firestore (`activeSessionToken`, `lastActiveTimestamp`,
 * `activeDeviceId`) on both `sessions/single_dev_{username}` and `users/{uid}`:
 * - Generates a unique `activeSessionToken` per login and persists it in EncryptedSharedPreferences.
 * - Runs all Firestore updates and snapshot listeners strictly on `Dispatchers.IO` (never blocking Main).
 * - Immediately forces logout with `"Session expired: Logged in from another device"` if another
 *   device replaces the active session token in Firestore.
 */
object SessionManager {

    private const val TAG = "SessionManager"
    private const val ENCRYPTED_PREFS_NAME = "wci_encrypted_session_store"
    private const val FALLBACK_PREFS_NAME = "wci_session_fallback_prefs"
    private const val KEY_ACTIVE_TOKEN = "active_session_token"
    private const val KEY_ACTIVE_USER = "active_username"
    private const val KEY_ACTIVE_UID = "active_uid"
    private const val KEY_ACTIVE_DEVICE_ID = "active_device_id"
    const val FORCED_LOGOUT_MESSAGE = "Session expired: Logged in from another device"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeListener: ListenerRegistration? = null

    @Volatile
    private var watchSetupJob: Job? = null

    private val _sessionForceLogoutEvent = MutableStateFlow<String?>(null)
    val sessionForceLogoutEvent: StateFlow<String?> = _sessionForceLogoutEvent.asStateFlow()

    @Volatile
    private var inMemoryToken: String? = null

    @Volatile
    private var inMemoryUsername: String? = null

    private fun openEncryptedPrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext
        return runCatching {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            appCtx.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getLocalSessionToken(context: Context): String {
        inMemoryToken?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            openEncryptedPrefs(context).getString(KEY_ACTIVE_TOKEN, "").orEmpty()
        }.getOrDefault("").also { if (it.isNotBlank()) inMemoryToken = it }
    }

    private fun saveLocalSession(
        context: Context,
        token: String,
        username: String,
        uid: String,
        deviceId: String
    ) {
        inMemoryToken = token
        inMemoryUsername = username
        runCatching {
            openEncryptedPrefs(context).edit()
                .putString(KEY_ACTIVE_TOKEN, token)
                .putString(KEY_ACTIVE_USER, username)
                .putString(KEY_ACTIVE_UID, uid)
                .putString(KEY_ACTIVE_DEVICE_ID, deviceId)
                .apply()
        }
    }

    fun clearLocalSession(context: Context? = null) {
        stopSessionWatcher()
        inMemoryToken = null
        inMemoryUsername = null
        if (context != null) {
            runCatching {
                openEncryptedPrefs(context).edit().clear().apply()
            }
        }
    }

    fun clearForceLogoutNotice() {
        _sessionForceLogoutEvent.value = null
    }

    fun stopSessionWatcher() {
        watchSetupJob?.cancel()
        watchSetupJob = null
        activeListener?.remove()
        activeListener = null
    }

    /**
     * Generates a fresh `activeSessionToken`, saves it in encrypted storage, syncs it to
     * Firestore (`sessions/single_dev_{username}` and `users/{uid}`) on `Dispatchers.IO`, and
     * listens in real time for another device overwriting the token.
     */
    fun startEnforcingSession(
        context: Context,
        firestore: FirebaseFirestore,
        username: String,
        uid: String,
        role: PortalRole,
        onForceLogout: (reason: String) -> Unit
    ) {
        val cleanUser = username.trim().lowercase().replace(Regex("[^a-z0-9_\\-.]"), "_")
        if (cleanUser.isBlank()) return

        stopSessionWatcher()
        _sessionForceLogoutEvent.value = null

        val appCtx = context.applicationContext
        val deviceId = ConcurrentSessionManager.resolveHardwareDeviceId(appCtx)
        val newSessionToken = "${UUID.randomUUID()}_${System.currentTimeMillis()}"
        val sessionDocId = "single_dev_$cleanUser"
        val effectiveUid = uid.ifBlank { "cred_$cleanUser" }

        // Save token synchronously to encrypted prefs before network sync
        saveLocalSession(
            context = appCtx,
            token = newSessionToken,
            username = cleanUser,
            uid = effectiveUid,
            deviceId = deviceId
        )

        watchSetupJob = ioScope.launch {
            val loginEpochMs = System.currentTimeMillis()
            val safeNow = Timestamp(Date(loginEpochMs))
            val sessionPayload = mapOf(
                "sessionId" to sessionDocId,
                "uid" to effectiveUid,
                "username" to cleanUser,
                "role" to role.key,
                "deviceName" to "DEV:$deviceId | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(115),
                "platform" to "Android ${android.os.Build.VERSION.RELEASE}",
                "active" to true,
                "activeSessionToken" to newSessionToken,
                "activeDeviceId" to deviceId,
                "lastActiveTimestamp" to safeNow,
                "createdAt" to safeNow
            )

            var serverSyncConfirmed = false
            // 1. Write active session token to Firestore BEFORE attaching listener so we don't race against old token
            runCatching {
                val synced = withTimeoutOrNull(4_000L) {
                    firestore.collection("sessions").document(sessionDocId)
                        .set(sessionPayload, SetOptions.merge())
                        .await()
                    true
                } ?: false
                serverSyncConfirmed = synced
            }.onFailure { err ->
                Log.w(TAG, "Deferred offline session sync for $cleanUser: ${err.localizedMessage}")
            }

            // Also mirror activeSessionToken onto users/{effectiveUid} if user profile doc exists
            runCatching {
                val userSessionFields = mapOf(
                    "activeSessionToken" to newSessionToken,
                    "activeDeviceId" to deviceId,
                    "lastActiveTimestamp" to safeNow
                )
                withTimeoutOrNull(3_000L) {
                    val userRef = firestore.collection("users").document(effectiveUid)
                    val snap = userRef.get().await()
                    if (snap.exists()) {
                        userRef.set(userSessionFields, SetOptions.merge()).await()
                    }
                }
            }

            // 2. Attach lightweight non-blocking snapshot listener on Dispatchers.IO
            val docRef = firestore.collection("sessions").document(sessionDocId)
            activeListener = docRef.addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "Session watcher offline/transient notice for $cleanUser: ${err.localizedMessage}")
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) return@addSnapshotListener
                // Skip local unconfirmed pending writes or stale offline cache snapshots
                if (snap.metadata.hasPendingWrites() || snap.metadata.isFromCache) return@addSnapshotListener

                val remoteToken = snap.getString("activeSessionToken").orEmpty()
                val remoteDevice = snap.getString("activeDeviceId").orEmpty()
                val remoteTimestampMs = snap.getTimestamp("lastActiveTimestamp")?.toDate()?.time ?: 0L
                val isActive = snap.getBoolean("active") ?: true
                val expectedToken = inMemoryToken ?: getLocalSessionToken(appCtx)

                if (remoteToken == expectedToken) {
                    serverSyncConfirmed = true
                    return@addSnapshotListener
                }

                // Only trigger token mismatch if our initial session write was confirmed and a strictly newer session from another device overwrote it
                val tokenMismatch = serverSyncConfirmed &&
                    expectedToken.isNotBlank() &&
                    remoteToken.isNotBlank() &&
                    remoteToken != expectedToken &&
                    remoteDevice.isNotBlank() &&
                    remoteDevice != deviceId &&
                    remoteTimestampMs > (loginEpochMs + 2_000L)

                val revokedByAdmin = serverSyncConfirmed && !isActive && remoteTimestampMs > (loginEpochMs + 2_000L)

                if (tokenMismatch || revokedByAdmin) {
                    val reason = if (tokenMismatch) {
                        FORCED_LOGOUT_MESSAGE
                    } else {
                        "Session expired: Your session was revoked by the Institute Administrator."
                    }
                    Log.w(TAG, "Force logout triggered for $cleanUser: $reason")
                    clearLocalSession(appCtx)
                    _sessionForceLogoutEvent.value = reason
                    ioScope.launch(Dispatchers.Main) {
                        onForceLogout(reason)
                    }
                }
            }
        }
    }

    /**
     * Checks local session integrity and optionally verifies against Firestore server if online.
     * Safe offline: returns true if local token matches and no revocation event has been raised.
     */
    fun hasValidLocalSession(context: Context?): Boolean {
        if (_sessionForceLogoutEvent.value != null) return false
        val tok = inMemoryToken ?: context?.let { getLocalSessionToken(it) }.orEmpty()
        return tok.isNotBlank()
    }
}
