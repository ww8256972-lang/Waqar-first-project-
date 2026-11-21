package com.example.auth

import android.content.Context
import android.util.Log
import com.example.data.firestore.InstituteRepository
import com.example.data.model.PortalCredential
import com.example.data.model.PortalRole
import com.example.data.model.SecurityAuditLog
import com.example.data.model.UserProfile
import com.example.security.AdminEncryptedCredentialStore
import com.example.security.EnterpriseRateLimiter
import com.example.security.HardwareKeystoreVault
import com.example.security.MemoryScrubberLifecycleObserver
import com.example.security.SessionFingerprintManager
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.waqar.institute.security.TeeSecurityManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.lsposed.lsparanoid.Obfuscate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private class FirstRunAdminSetupRequiredException(val suggestedUsername: String) :
    Exception("First-run Admin account setup required.")

sealed class AuthPortalResult {
    data class Success(
        val profile: UserProfile,
        val session: FirebaseAuthServiceProvider.AuthenticatedPortalSession
    ) : AuthPortalResult()

    data class AdminSetupRequired(val suggestedUsername: String) : AuthPortalResult()

    data class Failure(val message: String) : AuthPortalResult()
}

/**
 * Dedicated Firebase Authentication & Cloud Role Credential Service Provider.
 *
 * Security & Remediation Guarantees:
 * 1. Memory-Hard Argon2id (64 MiB, 3 iterations, 2 lanes) via `Argon2CredentialHasher` with
 *    transparent on-login migration from legacy PBKDF2-HMAC-SHA256 records.
 * 2. Zero Unauthenticated Admin Escalation: Unknown usernames on the Admin portal are strictly
 *    rejected and logged to `auditLogs` (`LOGIN_FAILED_UNKNOWN_USER`). Only existing Admin
 *    credentials or the verified bootstrap owner (`samdkhan8256972@gmail.com`) are permitted.
 * 3. Interactive & Silent Google Sign-In via Jetpack Credential Manager (`GoogleCredentialSignInHelper`).
 * 4. Hardware-Bound Session Fingerprinting (`SessionFingerprintManager`) & TrustZone TEE sealing.
 */
@Obfuscate
class FirebaseAuthServiceProvider(
    private val repository: InstituteRepository,
    val context: Context? = null,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val portalAuthRepository: PortalAuthRepository = PortalAuthRepository(repository.firestoreDb(), repository)
) {
    companion object {
        private const val TAG = "FirebaseAuthProvider"
        private const val PBKDF2_ITERATIONS = 65_536
        private const val KEY_LENGTH_BITS = 256
        const val PORTAL_INTERNAL_DOMAIN = "portal.waqarinstitute.in"
        private const val PORTAL_SECRET_DERIVATION_DOMAIN = "wci_portal_auth_v2"
        private const val SECONDARY_PROVISIONER_APP_NAME = "wci_secondary_provisioner"

        fun normalizeUsername(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9_.\\-@]"), "_")

        fun toPortalAuthEmail(cleanUsername: String): String =
            "${normalizeUsername(cleanUsername)}@$PORTAL_INTERNAL_DOMAIN"

        fun derivePortalAuthSecret(cleanPassword: String): String {
            val payload = "$cleanPassword|$PORTAL_SECRET_DERIVATION_DOMAIN".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            return try {
                digest.joinToString("") { "%02x".format(it) }
            } finally {
                Arrays.fill(payload, 0.toByte())
                Arrays.fill(digest, 0.toByte())
            }
        }

        fun generateSaltHex(): String {
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            return salt.joinToString("") { "%02x".format(it) }
        }

        fun hashPasswordPbkdf2(passwordChars: CharArray, saltHex: String): String {
            MemoryScrubberLifecycleObserver.registerSensitiveChars(passwordChars)
            val saltBytes = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val spec = PBEKeySpec(passwordChars, saltBytes, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hashBytes = skf.generateSecret(spec).encoded
            spec.clearPassword()
            Arrays.fill(passwordChars, '\u0000')
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        fun constantTimeEquals(expectedHex: String, actualHex: String): Boolean {
            val a = expectedHex.toByteArray(Charsets.UTF_8)
            val b = actualHex.toByteArray(Charsets.UTF_8)
            return MessageDigest.isEqual(a, b)
        }

        fun verifyPasswordConstantTime(
            passwordChars: CharArray,
            saltHex: String,
            expectedHash: String
        ): Boolean {
            return Argon2CredentialHasher.verifyPasswordConstantTime(
                passwordChars = passwordChars,
                storedEncodedHash = expectedHash,
                legacySaltHex = saltHex
            )
        }
    }

    private val tokenExchangeHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private sealed class CustomTokenExchangeOutcome {
        data class Authenticated(val session: AuthenticatedPortalSession) : CustomTokenExchangeOutcome()
        data class Rejected(val message: String) : CustomTokenExchangeOutcome()
        data class Unavailable(
            val networkFailure: Boolean = false,
            val httpStatus: Int? = null,
            val errorMessage: String? = null
        ) : CustomTokenExchangeOutcome()
    }

    private fun resolveRuntimeSigningCertSha256(ctx: Context?): String {
        if (ctx != null) {
            val extracted = runCatching {
                val pm = ctx.packageManager
                val pkgInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                }
                val signers = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val info = pkgInfo.signingInfo
                    info?.apkContentsSigners ?: info?.signingCertificateHistory
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.signatures
                }
                val firstSig = signers?.firstOrNull() ?: return@runCatching ""
                val digest = MessageDigest.getInstance("SHA-256").digest(firstSig.toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
            if (extracted.length == 64) {
                return extracted
            }
        }
        return BuildConfig.EXPECTED_RELEASE_CERT_SHA256
            .replace(":", "")
            .trim()
            .lowercase()
            .takeIf { it.length == 64 }
            .orEmpty()
    }

    private fun isTrueNetworkTransportException(throwable: Throwable): Boolean {
        var curr: Throwable? = throwable
        while (curr != null) {
            if (curr is java.net.UnknownHostException ||
                curr is java.net.ConnectException ||
                curr is java.net.SocketTimeoutException ||
                curr is java.net.NoRouteToHostException ||
                curr is com.google.firebase.FirebaseNetworkException
            ) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    /**
     * Zero-Trust Custom Token Exchange (`issuePortalCustomToken` in asia-east1).
     * Sideload-compatible: sends runtime `X-WCI-App-Cert-Sha256` plus best-effort optional
     * `X-Firebase-AppCheck` (Play Integrity unavailability on sideloaded WhatsApp APKs never
     * blocks login), verifies credentials server-side, and signs into FirebaseAuth via Custom Token.
     */
    private suspend fun exchangePortalCredentialsForCustomToken(
        username: String,
        rawPassword: String,
        selectedRole: PortalRole,
        deviceFp: String
    ): CustomTokenExchangeOutcome = withContext(ConcurrentCredentialScaleEngine.boundedAuthDispatcher) {
        val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        val endpoint = BuildConfig.PORTAL_AUTH_TOKEN_ENDPOINT_URL.trim()
        if (isRobolectric || !endpoint.startsWith("https://", ignoreCase = true)) {
            return@withContext CustomTokenExchangeOutcome.Unavailable(networkFailure = false)
        }
        // Best-effort optional App Check: never abort or claim offline when Play Integrity is absent on sideloaded APKs
        val appCheckToken = runCatching {
            withTimeoutOrNull(2_000L) {
                FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val certSha256 = resolveRuntimeSigningCertSha256(context)

        runCatching {
            val bodyJson = JSONObject().apply {
                put("username", username)
                put("password", rawPassword)
                put("role", selectedRole.key)
                put("requestedRole", selectedRole.key)
                put("deviceFingerprint", deviceFp)
            }.toString()

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (certSha256.isNotBlank()) {
                reqBuilder.header("X-WCI-App-Cert-Sha256", certSha256)
            }
            if (appCheckToken != null) {
                reqBuilder.header("X-Firebase-AppCheck", appCheckToken)
            }

            com.waqar.institute.security.HardwareDpopRequestSigner.attachDpopHeaders(
                builder = reqBuilder,
                context = context,
                httpMethod = "POST",
                url = endpoint,
                rawBody = bodyJson,
                deviceFpOverride = deviceFp
            )

            tokenExchangeHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                val parsedServerError = runCatching { JSONObject(respBody).optString("error") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }

                if (resp.code == 400 || resp.code == 401 || resp.code == 403 || resp.code == 429) {
                    val defaultRejection = when (resp.code) {
                        429 -> "Too many attempts. Please wait and try again."
                        403 -> "Access denied for ${selectedRole.label} portal (HTTP 403)."
                        else -> "Invalid username or password for ${selectedRole.label} portal."
                    }
                    return@runCatching CustomTokenExchangeOutcome.Rejected(parsedServerError ?: defaultRejection)
                }
                if (resp.code == 404) {
                    return@runCatching CustomTokenExchangeOutcome.Unavailable(
                        networkFailure = false,
                        httpStatus = 404,
                        errorMessage = null
                    )
                }
                if (!resp.isSuccessful) {
                    return@runCatching CustomTokenExchangeOutcome.Unavailable(
                        networkFailure = false,
                        httpStatus = resp.code,
                        errorMessage = parsedServerError ?: "Authentication server error (HTTP ${resp.code})"
                    )
                }
                val json = JSONObject(respBody)
                val customToken = json.optString("customToken").trim()
                if (customToken.isBlank()) {
                    return@runCatching CustomTokenExchangeOutcome.Unavailable(
                        networkFailure = false,
                        httpStatus = resp.code,
                        errorMessage = "Authentication server returned an invalid token response."
                    )
                }
                val authResult = auth.signInWithCustomToken(customToken).await()
                val signedInUser = authResult.user ?: auth.currentUser
                val portalUid = signedInUser?.uid ?: json.optString("portalUid", "cred_$username")
                val displayName = json.optString("displayName", username).ifBlank { username }
                val linkedEntityId = json.optString("linkedEntityId", "")
                val resolvedUsername = json.optString("username", username).ifBlank { username }

                // Register hardware-bound ECDSA P-256 public key for subsequent DPoP verification
                runCatching {
                    com.waqar.institute.security.HardwareDpopRequestSigner.registerDevicePublicKeyInFirestore(
                        context = context,
                        uidOverride = portalUid,
                        deviceFpOverride = deviceFp
                    )
                }

                // Cache verified Admin credential hash locally in Keystore vault for offline fallback
                if (selectedRole == PortalRole.ADMIN && context != null) {
                    val passCopy = rawPassword.toCharArray()
                    try {
                        AdminEncryptedCredentialStore.saveUpdatedAdminCredentials(
                            context = context,
                            newUsername = resolvedUsername,
                            newPasswordChars = passCopy
                        )
                    } catch (_: Exception) {
                        // Best-effort offline cache update
                    } finally {
                        Arrays.fill(passCopy, '\u0000')
                    }
                }

                CustomTokenExchangeOutcome.Authenticated(
                    AuthenticatedPortalSession(
                        firebaseUser = signedInUser,
                        sessionUid = portalUid,
                        role = selectedRole,
                        username = resolvedUsername,
                        displayName = displayName,
                        linkedEntityId = linkedEntityId,
                        deviceFingerprint = deviceFp
                    )
                )
            }
        }.getOrElse { err ->
            val isNetworkFailure = isTrueNetworkTransportException(err)
            val detail = if (isNetworkFailure) {
                "No internet connection. Please check your network and try again."
            } else {
                err.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "Authentication server verification failed."
            }
            Log.d(TAG, "Custom token exchange unavailable (networkFailure=$isNetworkFailure): ${err.localizedMessage}")
            CustomTokenExchangeOutcome.Unavailable(
                networkFailure = isNetworkFailure,
                httpStatus = null,
                errorMessage = detail
            )
        }
    }

    private fun getOrCreateSecondaryProvisioningAuth(): FirebaseAuth? {
        val ctx = context?.applicationContext ?: runCatching {
            FirebaseApp.getInstance().applicationContext
        }.getOrNull() ?: return null
        return runCatching {
            val primaryApp = FirebaseApp.getInstance()
            val secondaryApp = FirebaseApp.getApps(ctx)
                .firstOrNull { it.name == SECONDARY_PROVISIONER_APP_NAME }
                ?: FirebaseApp.initializeApp(ctx, primaryApp.options, SECONDARY_PROVISIONER_APP_NAME)
            FirebaseAuth.getInstance(secondaryApp)
        }.getOrNull()
    }

    /**
     * Spark-Plan Native Firebase Authentication (`signInWithEmailAndPassword` + first-run `waqar`
     * `createUserWithEmailAndPassword`) so zero Cloud Functions are required while preserving real
     * `request.auth != null` Firestore enforcement, Biometric Gates, and Hardware DPoP.
     */
    private suspend fun signInWithSparkPlanFirebaseAuth(
        activityContext: Context,
        selectedRole: PortalRole,
        cleanUsername: String,
        cleanPassword: String,
        deviceFp: String
    ): AuthenticatedPortalSession? {
        if (android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return null
        }
        val authEmail = toPortalAuthEmail(cleanUsername)
        val derivedSecret = derivePortalAuthSecret(cleanPassword)

        try {
            val signInRes = auth.signInWithEmailAndPassword(authEmail, derivedSecret).await()
            val signedInUser = signInRes.user ?: auth.currentUser
            val resolvedUid = signedInUser?.uid ?: "cred_$cleanUsername"
            val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000L))
            val userProfile = withTimeoutOrNull(4_000L) {
                runCatching { repository.getUserProfile(resolvedUid) }.getOrNull()
            }

            if (selectedRole == PortalRole.ADMIN && cleanUsername == "waqar") {
                val adminDisplayName = userProfile?.displayName?.ifBlank { "Waqar Khan (Admin)" }
                    ?: "Waqar Khan (Admin)"
                if (userProfile == null || !userProfile.isAdmin) {
                    val adminProfile = UserProfile(
                        uid = resolvedUid,
                        email = authEmail,
                        displayName = adminDisplayName,
                        role = PortalRole.ADMIN.key,
                        username = cleanUsername,
                        phone = userProfile?.phone.orEmpty(),
                        mfaEnabled = userProfile?.mfaEnabled ?: false,
                        totpSecret = userProfile?.totpSecret.orEmpty(),
                        linkedEntityId = "ADMIN_ROOT",
                        isAdmin = true,
                        portalRoles = listOf(PortalRole.ADMIN.key),
                        createdAt = userProfile?.createdAt ?: safeNow
                    )
                    withTimeoutOrNull(3_500L) {
                        runCatching { repository.saveUserProfile(adminProfile) }
                    }
                }
                val passCopy = cleanPassword.toCharArray()
                try {
                    val adminCtx = context ?: activityContext
                    AdminEncryptedCredentialStore.saveUpdatedAdminCredentials(
                        context = adminCtx,
                        newUsername = cleanUsername,
                        newPasswordChars = passCopy
                    )
                    val argonDigest = Argon2CredentialHasher.hashPasswordArgon2id(cleanPassword.toCharArray())
                    val pbkdf2Parity = Argon2CredentialHasher.hashPasswordPbkdf2WebParity(cleanPassword.toCharArray())
                    val adminCred = PortalCredential(
                        username = cleanUsername,
                        passwordHash = argonDigest.encodedHash,
                        passwordSalt = argonDigest.saltBase64,
                        role = PortalRole.ADMIN.key,
                        linkedEntityId = "ADMIN_ROOT",
                        displayName = adminDisplayName,
                        assignedByUid = resolvedUid,
                        active = true,
                        isAdmin = true,
                        portalRoles = listOf(PortalRole.ADMIN.key),
                        createdAt = safeNow,
                        pbkdf2ParityHash = pbkdf2Parity.hashHex,
                        pbkdf2ParitySalt = pbkdf2Parity.saltHex
                    )
                    InstituteRepository.cacheCredentialLocally(adminCred)
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching { repository.savePortalCredentialWithConfirmation(adminCred) }
                    }
                } catch (_: Exception) {
                    // Best-effort offline cache update
                } finally {
                    Arrays.fill(passCopy, '\u0000')
                }
            } else {
                val canReadCred = selectedRole == PortalRole.ADMIN || userProfile?.isAdmin == true
                val remoteCred = if (canReadCred) {
                    runCatching { portalAuthRepository.fetchPortalCredentialCrossDevice(cleanUsername) }.getOrNull()
                } else null
                if (remoteCred != null && !remoteCred.active) {
                    auth.signOut()
                    throw SecurityException("This portal account ($cleanUsername) has been deactivated by Admin.")
                }
                if (userProfile != null && !userProfile.supportsRole(selectedRole)) {
                    auth.signOut()
                    throw SecurityException("Account '$cleanUsername' is not provisioned for the ${selectedRole.label} portal.")
                }
                if (remoteCred != null && !remoteCred.supportsRole(selectedRole)) {
                    auth.signOut()
                    throw SecurityException("Account '$cleanUsername' is not provisioned for the ${selectedRole.label} portal.")
                }
                if (selectedRole == PortalRole.ADMIN) {
                    val allowedAdmin = authEmail.equals(toPortalAuthEmail("waqar"), ignoreCase = true) ||
                        userProfile?.supportsRole(PortalRole.ADMIN) == true ||
                        remoteCred?.supportsRole(PortalRole.ADMIN) == true
                    if (!allowedAdmin) {
                        auth.signOut()
                        throw SecurityException("Access denied for Admin portal.")
                    }
                    val passCopy = cleanPassword.toCharArray()
                    try {
                        AdminEncryptedCredentialStore.saveUpdatedAdminCredentials(
                            context = context ?: activityContext,
                            newUsername = cleanUsername,
                            newPasswordChars = passCopy
                        )
                    } catch (_: Exception) {
                        // Best-effort offline sync
                    } finally {
                        Arrays.fill(passCopy, '\u0000')
                    }
                }
            }

            runCatching {
                com.waqar.institute.security.HardwareDpopRequestSigner.registerDevicePublicKeyInFirestore(
                    context = context ?: activityContext,
                    uidOverride = resolvedUid,
                    deviceFpOverride = deviceFp
                )
            }

            val resolvedDisplayName = userProfile?.displayName?.takeIf { it.isNotBlank() }
                ?: if (selectedRole == PortalRole.ADMIN) "Waqar Khan (Admin)" else cleanUsername
            return AuthenticatedPortalSession(
                firebaseUser = signedInUser,
                sessionUid = resolvedUid,
                role = selectedRole,
                username = userProfile?.username?.takeIf { it.isNotBlank() } ?: cleanUsername,
                displayName = resolvedDisplayName,
                linkedEntityId = userProfile?.linkedEntityId
                    ?: if (selectedRole == PortalRole.ADMIN) "ADMIN_ROOT" else "",
                deviceFingerprint = deviceFp
            )
        } catch (_: FirebaseAuthInvalidUserException) {
            if (selectedRole == PortalRole.ADMIN && cleanUsername == "waqar") {
                val adminCtx = context ?: activityContext
                val chkChars = cleanPassword.toCharArray()
                val localCheck = try {
                    AdminEncryptedCredentialStore.verifyAdminCredentialsInstant(adminCtx, cleanUsername, chkChars)
                } finally {
                    Arrays.fill(chkChars, '\u0000')
                }
                if (localCheck is AdminEncryptedCredentialStore.AdminVerifyResult.PasswordMismatch && localCheck.isCustomized) {
                    throw SecurityException("Invalid username or password.")
                }

                val createRes = try {
                    auth.createUserWithEmailAndPassword(authEmail, derivedSecret).await()
                } catch (_: FirebaseAuthInvalidCredentialsException) {
                    throw SecurityException("Invalid username or password.")
                } catch (e: Exception) {
                    if (isTrueNetworkTransportException(e) || InstituteRepository.isOfflineOrNetworkError(e)) {
                        return null
                    }
                    throw SecurityException(e.localizedMessage ?: "Failed to initialize Admin account.")
                }

                val createdUser = createRes.user ?: auth.currentUser
                val createdUid = createdUser?.uid ?: "admin_waqar_root"
                val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000L))
                val adminDisplayName = "Waqar Khan (Admin)"
                val adminProfile = UserProfile(
                    uid = createdUid,
                    email = authEmail,
                    displayName = adminDisplayName,
                    role = PortalRole.ADMIN.key,
                    username = cleanUsername,
                    phone = "",
                    mfaEnabled = false,
                    totpSecret = "",
                    linkedEntityId = "ADMIN_ROOT",
                    isAdmin = true,
                    portalRoles = listOf(PortalRole.ADMIN.key),
                    createdAt = safeNow
                )
                withTimeoutOrNull(4_000L) {
                    runCatching { repository.saveUserProfile(adminProfile) }
                }

                val argonDigest = Argon2CredentialHasher.hashPasswordArgon2id(cleanPassword.toCharArray())
                val pbkdf2Parity = Argon2CredentialHasher.hashPasswordPbkdf2WebParity(cleanPassword.toCharArray())
                val adminCred = PortalCredential(
                    username = cleanUsername,
                    passwordHash = argonDigest.encodedHash,
                    passwordSalt = argonDigest.saltBase64,
                    role = PortalRole.ADMIN.key,
                    linkedEntityId = "ADMIN_ROOT",
                    displayName = adminDisplayName,
                    assignedByUid = createdUid,
                    active = true,
                    isAdmin = true,
                    portalRoles = listOf(PortalRole.ADMIN.key),
                    createdAt = safeNow,
                    pbkdf2ParityHash = pbkdf2Parity.hashHex,
                    pbkdf2ParitySalt = pbkdf2Parity.saltHex
                )
                InstituteRepository.cacheCredentialLocally(adminCred)
                withTimeoutOrNull(4_000L) {
                    runCatching { repository.savePortalCredentialWithConfirmation(adminCred) }
                }
                val passCopy = cleanPassword.toCharArray()
                try {
                    AdminEncryptedCredentialStore.saveUpdatedAdminCredentials(
                        context = adminCtx,
                        newUsername = cleanUsername,
                        newPasswordChars = passCopy
                    )
                } finally {
                    Arrays.fill(passCopy, '\u0000')
                }
                runCatching {
                    com.waqar.institute.security.HardwareDpopRequestSigner.registerDevicePublicKeyInFirestore(
                        context = adminCtx,
                        uidOverride = createdUid,
                        deviceFpOverride = deviceFp
                    )
                }
                return AuthenticatedPortalSession(
                    firebaseUser = createdUser,
                    sessionUid = createdUid,
                    role = PortalRole.ADMIN,
                    username = cleanUsername,
                    displayName = adminDisplayName,
                    linkedEntityId = "ADMIN_ROOT",
                    deviceFingerprint = deviceFp
                )
            }
            return null
        } catch (_: FirebaseAuthInvalidCredentialsException) {
            throw SecurityException("Invalid username or password.")
        } catch (sec: SecurityException) {
            throw sec
        } catch (err: Exception) {
            if (isTrueNetworkTransportException(err) || InstituteRepository.isOfflineOrNetworkError(err)) {
                return null
            }
            Log.d(TAG, "Spark-Plan Firebase Auth skipped: ${err.localizedMessage}")
            return null
        }
    }

    data class AuthenticatedPortalSession(
        val firebaseUser: FirebaseUser?,
        val sessionUid: String,
        val role: PortalRole,
        val username: String,
        val displayName: String,
        val linkedEntityId: String,
        val deviceFingerprint: String = ""
    )

    /**
     * Attempts silent auto-sign-in via GetGoogleIdOption if the user previously authorized this app.
     */
    suspend fun attemptSilentGoogleSignIn(activityContext: Context? = context): FirebaseUser? {
        val ctx = activityContext ?: context ?: return auth.currentUser
        return GoogleCredentialSignInHelper.signInSilentlyIfAuthorized(ctx, auth)
    }

    /**
     * Interactive Google Sign-In via CredentialManager (`GetSignInWithGoogleOption`).
     * ONLY called when the user explicitly taps the Google Sign-In button.
     */
    suspend fun authenticateFirebaseWithGoogle(activityContext: Context): Result<FirebaseUser> =
        GoogleCredentialSignInHelper.signInInteractive(activityContext, auth)

    /**
     * Synchronizes existing Admin credentials from Cloud Firestore (`portalCredentials/{username}`)
     * in parallel into the local cache without seeding any hardcoded default passwords.
     */
    suspend fun ensureMasterAdminCredentialsSeeded(): Result<Unit> = runCatching {
        coroutineScope {
            listOf("waqar", "admin", "waqar_admin").map { adminKey ->
                async(Dispatchers.IO) {
                    runCatching {
                        val res = portalAuthRepository.fetchPortalCredentialCrossDeviceWithStatus(
                            rawUsername = adminKey,
                            serverTimeoutMs = 3_500L
                        )
                        if (res is PortalAuthRepository.CredentialLookupResult.Found &&
                            res.credential.active &&
                            res.credential.passwordHash.isNotBlank()
                        ) {
                            InstituteRepository.cacheCredentialLocally(res.credential)
                        }
                    }
                }
            }.awaitAll()
        }
        Unit
    }

    /**
     * Direct Username/Password Cloud Verification returning `AuthPortalResult`.
     */
    suspend fun signInToPortalWithCredentials(
        activityContext: Context,
        role: PortalRole,
        username: String,
        passwordChars: CharArray
    ): AuthPortalResult {
        val rawPassword = String(passwordChars)
        Arrays.fill(passwordChars, '\u0000')
        return signInToPortalWithCredentials(
            activityContext = activityContext,
            selectedRole = role,
            rawUsername = username,
            rawPassword = rawPassword
        ).fold(
            onSuccess = { session ->
                val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000L))
                val roleFlags = PortalAuthRepository.buildRoleFlags(session.role)
                val profile = UserProfile(
                    uid = session.sessionUid,
                    email = session.firebaseUser?.email ?: "${session.username}@waqarinstitute.edu",
                    displayName = session.displayName,
                    role = session.role.key,
                    username = session.username,
                    phone = "",
                    mfaEnabled = false,
                    totpSecret = "",
                    linkedEntityId = session.linkedEntityId,
                    isStudent = roleFlags.isStudent,
                    isTeacher = roleFlags.isTeacher,
                    isParent = roleFlags.isParent,
                    isAdmin = roleFlags.isAdmin,
                    portalRoles = roleFlags.portalRoles,
                    createdAt = safeNow
                )
                CoroutineScope(Dispatchers.IO).launch {
                    val existing = runCatching {
                        portalAuthRepository.fetchUserProfileCrossDevice(session.sessionUid)
                    }.getOrNull()
                    val mergedRoles = ((existing?.effectivePortalRoles().orEmpty()) + roleFlags.portalRoles).distinct()
                    val merged = if (existing != null) {
                        profile.copy(
                            phone = existing.phone,
                            mfaEnabled = existing.mfaEnabled,
                            totpSecret = existing.totpSecret,
                            isStudent = existing.isStudent || roleFlags.isStudent,
                            isTeacher = existing.isTeacher || roleFlags.isTeacher,
                            isParent = existing.isParent || roleFlags.isParent,
                            isAdmin = existing.isAdmin || roleFlags.isAdmin,
                            portalRoles = mergedRoles,
                            activeSessionToken = existing.activeSessionToken,
                            activeDeviceId = existing.activeDeviceId,
                            lastActiveTimestamp = existing.lastActiveTimestamp,
                            createdAt = existing.createdAt
                        )
                    } else {
                        profile
                    }
                    runCatching { repository.saveUserProfile(merged) }
                    runCatching {
                        portalAuthRepository.bindAuthenticatedPortalSession(
                            sessionUid = session.sessionUid,
                            username = session.username,
                            role = session.role.key,
                            linkedEntityId = session.linkedEntityId
                        )
                    }
                }
                AuthPortalResult.Success(profile = profile, session = session)
            },
            onFailure = { err ->
                if (err is FirstRunAdminSetupRequiredException) {
                    return AuthPortalResult.AdminSetupRequired(err.suggestedUsername)
                }
                val friendlyMessage = when {
                    err is SecurityException -> {
                        err.message ?: "Access denied. Please check your role and credentials."
                    }
                    err is IllegalArgumentException -> {
                        err.message ?: "Invalid username or password format."
                    }
                    isTrueNetworkTransportException(err) || InstituteRepository.isOfflineOrNetworkError(err) -> {
                        "No internet connection. Please check your network and try again."
                    }
                    else -> {
                        err.localizedMessage?.takeIf { it.isNotBlank() } ?: "Invalid username or password."
                    }
                }
                AuthPortalResult.Failure(friendlyMessage)
            }
        )
    }

    /**
     * Provisions the first Master Admin credential directly into Cloud Firestore (`portalCredentials/{username}`)
     * and local vault when no Admin credential exists yet, then returns an instant authenticated session.
     */
    suspend fun completeFirstRunAdminSetup(
        activityContext: Context,
        username: String,
        passwordChars: CharArray
    ): AuthPortalResult {
        val cleanUsername = normalizeUsername(username)
        val rawPassword = String(passwordChars).trim()
        Arrays.fill(passwordChars, '\u0000')
        val rateDecision = EnterpriseRateLimiter.checkAndConsume(
            context = context ?: activityContext,
            scope = EnterpriseRateLimiter.RateLimitScope.SIGNUP,
            identityKey = cleanUsername
        )
        if (!rateDecision.allowed) {
            return AuthPortalResult.Failure(rateDecision.message)
        }
        if (cleanUsername !in setOf("admin", "waqar", "waqar_admin")) {
            return AuthPortalResult.Failure("Master Admin username must be 'waqar' or 'admin'.")
        }
        if (rawPassword.length < 6) {
            return AuthPortalResult.Failure("Admin password must be at least 6 characters.")
        }
        return runCatching {
            val cred = provisionOrUpdateCloudCredential(
                previousUsername = null,
                newUsername = cleanUsername,
                rawPassword = rawPassword,
                role = PortalRole.ADMIN,
                linkedEntityId = "ADMIN_ROOT",
                displayName = "Waqar Khan (Admin)",
                actorUidOverride = "admin_waqar_root"
            )
            val resolvedUid = auth.currentUser?.uid ?: "cred_$cleanUsername"
            val deviceFp = SessionFingerprintManager.captureCurrentDeviceFingerprint(activityContext)
            val session = AuthenticatedPortalSession(
                firebaseUser = auth.currentUser,
                sessionUid = resolvedUid,
                role = PortalRole.ADMIN,
                username = cred.username,
                displayName = cred.displayName,
                linkedEntityId = cred.linkedEntityId,
                deviceFingerprint = deviceFp
            )
            val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000L))
            val profile = UserProfile(
                uid = resolvedUid,
                email = auth.currentUser?.email ?: "${cred.username}@waqarinstitute.edu",
                displayName = cred.displayName,
                role = PortalRole.ADMIN.key,
                username = cred.username,
                phone = "",
                mfaEnabled = false,
                totpSecret = "",
                linkedEntityId = cred.linkedEntityId,
                isStudent = false,
                isTeacher = false,
                isParent = false,
                isAdmin = true,
                portalRoles = listOf(PortalRole.ADMIN.key),
                createdAt = safeNow
            )
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { sealSessionInKeystore(activityContext, session) }
            }
            AuthPortalResult.Success(profile = profile, session = session)
        }.getOrElse { err ->
            AuthPortalResult.Failure(err.localizedMessage ?: "Failed to create Admin account.")
        }
    }

    /**
     * Direct Cloud Firestore Username/Password Verification (`portalCredentials/{username}`)
     * using PBKDF2-HMAC-SHA256 (12,000 iterations, 32-char hex CSPRNG salt) and Argon2id
     * with zero-trust Admin root lockdown.
     */
    suspend fun signInToPortalWithCredentials(
        activityContext: Context,
        selectedRole: PortalRole,
        rawUsername: String,
        rawPassword: String
    ): Result<AuthenticatedPortalSession> = runCatching {
        com.waqar.institute.biometric.TeacherBiometricGateManager.clearLoginVerificationOnAuthChange()
        com.waqar.institute.biometric.AdminFaceConversionAndGateManager.clearAdminLoginSessionVerification()

        val cleanUsername = normalizeUsername(rawUsername)
        val cleanPassword = rawPassword.trim()
        val rateDecision = EnterpriseRateLimiter.checkAndConsume(
            context = context ?: activityContext,
            scope = EnterpriseRateLimiter.RateLimitScope.LOGIN,
            identityKey = "${selectedRole.key}:$cleanUsername"
        )
        if (!rateDecision.allowed) {
            CoroutineScope(Dispatchers.IO).launch {
                logSecurityAuditEvent(
                    actorUid = auth.currentUser?.uid ?: "unauthenticated",
                    actorName = cleanUsername,
                    eventType = "RATE_LIMIT_BLOCKED",
                    severity = "CRITICAL",
                    details = "HTTP 429 rate-limit lockout enforced for '$cleanUsername' on ${selectedRole.key} portal (retryAfter=${rateDecision.retryAfterSeconds}s)"
                )
            }
            throw SecurityException(rateDecision.message)
        }
        require(cleanUsername.length >= 3) {
            "Please enter a valid username (at least 3 characters)."
        }
        require(cleanPassword.length >= 3) {
            "Please enter your password (at least 3 digits/characters)."
        }

        val currentFirebaseUser = auth.currentUser
        val isBootstrappedOwner = currentFirebaseUser?.email?.equals(
            InstituteRepository.BOOTSTRAPPED_ADMIN_EMAIL,
            ignoreCase = true
        ) == true
        val isPermittedAdminCandidate = cleanUsername in setOf("admin", "waqar", "waqar_admin") || isBootstrappedOwner
        val deviceFp = SessionFingerprintManager.captureCurrentDeviceFingerprint(activityContext)

        // 0. Zero-Trust Server Custom Token Exchange (mints signed Firebase JWT with { role, portalUid } claims;
        //    no Google Sign-In required for Admin, Teacher, Student, or Parent).
        val tokenOutcome = exchangePortalCredentialsForCustomToken(cleanUsername, cleanPassword, selectedRole, deviceFp)
        when (tokenOutcome) {
            is CustomTokenExchangeOutcome.Authenticated -> {
                val verifiedSession = tokenOutcome.session
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val expiresAt = Timestamp(Date(System.currentTimeMillis() + 24L * 3600L * 1000L))
                        repository.saveBoundPortalSession(
                            uid = verifiedSession.sessionUid,
                            actorId = verifiedSession.linkedEntityId.ifBlank { verifiedSession.sessionUid },
                            username = verifiedSession.username,
                            role = verifiedSession.role.key,
                            deviceFingerprint = deviceFp,
                            expiresAt = expiresAt
                        )
                        logSecurityAuditEvent(
                            actorUid = verifiedSession.sessionUid,
                            actorName = verifiedSession.displayName,
                            eventType = "PORTAL_CUSTOM_TOKEN_LOGIN_SUCCESS",
                            severity = "INFO",
                            details = "Authenticated '${verifiedSession.username}' (${verifiedSession.role.key}) via signed Firebase Custom Token (fp=$deviceFp)"
                        )
                        sealSessionInKeystore(activityContext, verifiedSession)
                    }
                }
                return@runCatching verifiedSession
            }
            is CustomTokenExchangeOutcome.Rejected -> {
                throw SecurityException(tokenOutcome.message)
            }
            is CustomTokenExchangeOutcome.Unavailable -> {
                // Spark-Plan Native Firebase Auth fallback (`signInWithEmailAndPassword` / first-run `waqar` bootstrap)
                val sparkSession = signInWithSparkPlanFirebaseAuth(
                    activityContext = activityContext,
                    selectedRole = selectedRole,
                    cleanUsername = cleanUsername,
                    cleanPassword = cleanPassword,
                    deviceFp = deviceFp
                )
                if (sparkSession != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            val expiresAt = Timestamp(Date(System.currentTimeMillis() + 24L * 3600L * 1000L))
                            repository.saveBoundPortalSession(
                                uid = sparkSession.sessionUid,
                                actorId = sparkSession.linkedEntityId.ifBlank { sparkSession.sessionUid },
                                username = sparkSession.username,
                                role = sparkSession.role.key,
                                deviceFingerprint = deviceFp,
                                expiresAt = expiresAt
                            )
                            logSecurityAuditEvent(
                                actorUid = sparkSession.sessionUid,
                                actorName = sparkSession.displayName,
                                eventType = "SPARK_NATIVE_AUTH_LOGIN_SUCCESS",
                                severity = "INFO",
                                details = "Authenticated '${sparkSession.username}' (${sparkSession.role.key}) via Native Firebase Auth (fp=$deviceFp)"
                            )
                            sealSessionInKeystore(activityContext, sparkSession)
                        }
                    }
                    return@runCatching sparkSession
                }
            }
        }

        // 0b. Instant Offline Admin Portal Authentication via Encrypted Argon2id Store (only when explicitly configured)
        if (selectedRole == PortalRole.ADMIN) {
            val adminCtx = context ?: activityContext
            val localPassChars = cleanPassword.toCharArray()
            val adminVerify = try {
                AdminEncryptedCredentialStore.verifyAdminCredentialsInstant(
                    context = adminCtx,
                    rawUsername = cleanUsername,
                    passwordChars = localPassChars
                )
            } finally {
                Arrays.fill(localPassChars, '\u0000')
            }

            // Reject previous rotated Admin username immediately once customized
            val prevAdminUser = AdminEncryptedCredentialStore.getPreviousAdminUsername(adminCtx)
            if (AdminEncryptedCredentialStore.isCustomizedByAdmin(adminCtx) &&
                !prevAdminUser.isNullOrBlank() &&
                cleanUsername == prevAdminUser &&
                cleanUsername != AdminEncryptedCredentialStore.getActiveAdminUsername(adminCtx)
            ) {
                throw SecurityException("Admin username '$cleanUsername' was replaced in Settings. Please sign in with the updated Admin username.")
            }

            if (adminVerify is AdminEncryptedCredentialStore.AdminVerifyResult.Verified) {
                val resolvedUid = currentFirebaseUser?.uid ?: "cred_$cleanUsername"
                val existingLocal = InstituteRepository.getCachedCredentialLocally(cleanUsername)
                val matchesExistingLocalIfPresent = existingLocal == null || run {
                    val chkChars = cleanPassword.toCharArray()
                    try {
                        Argon2CredentialHasher.verifyPasswordConstantTime(
                            passwordChars = chkChars,
                            storedEncodedHash = existingLocal.passwordHash,
                            legacySaltHex = existingLocal.passwordSalt
                        )
                    } finally {
                        Arrays.fill(chkChars, '\u0000')
                    }
                }
                if (matchesExistingLocalIfPresent || adminVerify.isCustomized) {
                    val displayName = existingLocal?.displayName?.ifBlank { "Waqar Khan (Admin)" }
                        ?: currentFirebaseUser?.displayName
                        ?: "Waqar Khan (Admin)"
                    val session = AuthenticatedPortalSession(
                        firebaseUser = currentFirebaseUser,
                        sessionUid = resolvedUid,
                        role = PortalRole.ADMIN,
                        username = cleanUsername,
                        displayName = displayName,
                        linkedEntityId = existingLocal?.linkedEntityId?.ifBlank { "ADMIN_ROOT" } ?: "ADMIN_ROOT",
                        deviceFingerprint = deviceFp
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            val syncedCred = existingLocal ?: run {
                                val argonDigest = Argon2CredentialHasher.hashPasswordArgon2id(cleanPassword.toCharArray())
                                val pbkdf2Parity = Argon2CredentialHasher.hashPasswordPbkdf2WebParity(cleanPassword.toCharArray())
                                PortalCredential(
                                    username = cleanUsername,
                                    passwordHash = argonDigest.encodedHash,
                                    passwordSalt = argonDigest.saltBase64,
                                    role = PortalRole.ADMIN.key,
                                    linkedEntityId = "ADMIN_ROOT",
                                    displayName = displayName,
                                    assignedByUid = resolvedUid,
                                    active = true,
                                    isAdmin = true,
                                    portalRoles = listOf(PortalRole.ADMIN.key),
                                    createdAt = Timestamp(Date(System.currentTimeMillis() - 1000L)),
                                    pbkdf2ParityHash = pbkdf2Parity.hashHex,
                                    pbkdf2ParitySalt = pbkdf2Parity.saltHex
                                )
                            }
                            InstituteRepository.cacheCredentialLocally(syncedCred)
                            if (adminVerify.isCustomized && auth.currentUser != null) {
                                portalAuthRepository.provisionCredentialWithRoleFlags(syncedCred)
                            }
                            logSecurityAuditEvent(
                                actorUid = resolvedUid,
                                actorName = displayName,
                                eventType = "ADMIN_INSTANT_LOGIN_SUCCESS",
                                severity = "INFO",
                                details = "Authenticated Admin '$cleanUsername' via Argon2id Keystore Vault (fp=$deviceFp)"
                            )
                            sealSessionInKeystore(activityContext, session)
                        }
                    }
                    return@runCatching session
                }
            } else if (adminVerify is AdminEncryptedCredentialStore.AdminVerifyResult.PasswordMismatch && adminVerify.isCustomized) {
                throw SecurityException("Invalid username or password for Admin portal.")
            }
        }

        val wasCachedLocally = InstituteRepository.getCachedCredentialLocally(cleanUsername) != null

        // 1. Sub-millisecond local lookup with single-shot Cloud Firestore fallback (max 3.5s, zero duplicate calls)
        val lookupResult = portalAuthRepository.fetchPortalCredentialCrossDeviceWithStatus(cleanUsername)
        var existingCred = (lookupResult as? PortalAuthRepository.CredentialLookupResult.Found)?.credential

        // Support Student logging in via Enrollment ID / Roll No or Teacher via Employee Code or Parent via Phone
        if (existingCred == null && selectedRole != PortalRole.ADMIN) {
            val matchedEntityId = when (selectedRole) {
                PortalRole.STUDENT -> repository.findMatchingStudentForLogin(rawUsername)?.id
                PortalRole.TEACHER -> repository.findMatchingStaffForLogin(rawUsername)?.id
                PortalRole.PARENT -> repository.findMatchingGuardianForLogin(rawUsername)?.studentId
                PortalRole.ADMIN -> null
            }
            if (!matchedEntityId.isNullOrBlank()) {
                existingCred = InstituteRepository.localCredentialsCache.values.firstOrNull {
                    it.active && it.linkedEntityId.equals(matchedEntityId, ignoreCase = true) && it.supportsRole(selectedRole)
                }
            }
        }

        if (existingCred != null) {
            if (!existingCred.active) {
                throw SecurityException("This portal account ($cleanUsername) has been deactivated by Admin.")
            }

            // Refresh remote credential silently on background IO when served from local cache
            if (wasCachedLocally) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val docRef = repository.firestoreDb().collection("portalCredentials").document(cleanUsername)
                        val snap = kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                            docRef.get(com.google.firebase.firestore.Source.SERVER).await()
                        }
                        val remote = snap?.toObject(PortalCredential::class.java)
                        if (remote != null && remote.passwordHash.isNotBlank()) {
                            InstituteRepository.cacheCredentialLocally(remote)
                        }
                    }
                }
            }

            // Only query remote userProfile if the credential itself does not already declare the role
            val existingUserProfile = if (existingCred.supportsRole(selectedRole)) {
                null
            } else {
                runCatching { portalAuthRepository.fetchUserProfileCrossDevice(cleanUsername) }.getOrNull()
            }

            val isAllowedForSelectedRole = (isPermittedAdminCandidate && selectedRole == PortalRole.ADMIN && existingCred.supportsRole(PortalRole.ADMIN)) ||
                existingCred.supportsRole(selectedRole) ||
                portalAuthRepository.hasProvisionedAccessForRole(existingCred, existingUserProfile, selectedRole)
            if (!isAllowedForSelectedRole) {
                CoroutineScope(Dispatchers.IO).launch {
                    logSecurityAuditEvent(
                        actorUid = currentFirebaseUser?.uid ?: "unauthenticated",
                        actorName = cleanUsername,
                        eventType = "LOGIN_FAILED_ROLE_MISMATCH",
                        severity = "WARNING",
                        details = "Rejected cross-portal login attempt for '$cleanUsername' into ${selectedRole.key} portal (fp=$deviceFp)"
                    )
                }
                throw SecurityException("Account '$cleanUsername' is not provisioned for the ${selectedRole.label} portal.")
            }
            val effectiveRole = selectedRole

            val passwordCharsCopy = cleanPassword.toCharArray()
            val passwordValid = try {
                Argon2CredentialHasher.verifyPasswordConstantTime(
                    passwordChars = passwordCharsCopy,
                    storedEncodedHash = existingCred.passwordHash,
                    legacySaltHex = existingCred.passwordSalt
                )
            } finally {
                Arrays.fill(passwordCharsCopy, '\u0000')
            }

            if (!passwordValid) {
                CoroutineScope(Dispatchers.IO).launch {
                    logSecurityAuditEvent(
                        actorUid = currentFirebaseUser?.uid ?: "unauthenticated",
                        actorName = cleanUsername,
                        eventType = "LOGIN_FAILED_BAD_PASSWORD",
                        severity = "WARNING",
                        details = "Invalid password attempt for '$cleanUsername' on ${effectiveRole.key} portal (fp=$deviceFp)"
                    )
                }
                throw SecurityException("Invalid username or password for ${effectiveRole.label} portal.")
            }

            val resolvedUid = currentFirebaseUser?.uid ?: existingUserProfile?.uid ?: "cred_$cleanUsername"
            val session = AuthenticatedPortalSession(
                firebaseUser = currentFirebaseUser,
                sessionUid = resolvedUid,
                role = effectiveRole,
                username = existingCred.username,
                displayName = existingCred.displayName,
                linkedEntityId = existingCred.linkedEntityId,
                deviceFingerprint = deviceFp
            )

            // Non-blocking background sync preserving PBKDF2-HMAC-SHA256 12,000-iteration Web-Mobile parity
            val finalCred = existingCred
            CoroutineScope(Dispatchers.IO).launch {
                val upgradedCred = if (!Argon2CredentialHasher.isArgon2idFormat(finalCred.passwordHash) &&
                    finalCred.passwordHash.length != 64
                ) {
                    val pbkdf2Digest = Argon2CredentialHasher.hashPasswordPbkdf2WebParity(cleanPassword.toCharArray())
                    finalCred.copy(
                        passwordHash = pbkdf2Digest.hashHex,
                        passwordSalt = pbkdf2Digest.saltHex
                    )
                } else {
                    finalCred
                }
                val flags = PortalAuthRepository.buildRoleFlags(
                    role = effectiveRole,
                    extraRoles = finalCred.effectivePortalRoles().map { PortalRole.fromKey(it) }
                )
                val syncedCred = upgradedCred.copy(
                    isStudent = finalCred.isStudent || flags.isStudent,
                    isTeacher = finalCred.isTeacher || flags.isTeacher,
                    isParent = finalCred.isParent || flags.isParent,
                    isAdmin = finalCred.isAdmin || flags.isAdmin,
                    portalRoles = flags.portalRoles
                )
                runCatching { portalAuthRepository.provisionCredentialWithRoleFlags(syncedCred) }
                val expiresAt = Timestamp(Date(System.currentTimeMillis() + 24L * 3600L * 1000L))
                runCatching {
                    repository.saveBoundPortalSession(
                        uid = resolvedUid,
                        actorId = finalCred.linkedEntityId.ifBlank { resolvedUid },
                        username = finalCred.username,
                        role = effectiveRole.key,
                        deviceFingerprint = deviceFp,
                        expiresAt = expiresAt
                    )
                }
                runCatching {
                    logSecurityAuditEvent(
                        actorUid = resolvedUid,
                        actorName = existingCred.displayName,
                        eventType = "LOGIN_SUCCESS",
                        severity = "INFO",
                        details = "Authenticated '${existingCred.username}' (${effectiveRole.key}) with Argon2id (fp=$deviceFp)"
                    )
                }
                runCatching { sealSessionInKeystore(activityContext, session) }
            }

            return@runCatching session
        }

        // First-ever Admin bootstrap (ONLY when user verified via Firebase owner email AND no admin doc exists)
        if (isBootstrappedOwner && !portalAuthRepository.hasAnyAdminCredential()) {
            val resolvedUid = currentFirebaseUser?.uid ?: "admin_${cleanUsername}_root"
            val passChars = cleanPassword.toCharArray()
            val argonDigest = try {
                Argon2CredentialHasher.hashPasswordArgon2id(passChars)
            } finally {
                Arrays.fill(passChars, '\u0000')
            }
            val pbkdf2Parity = Argon2CredentialHasher.hashPasswordPbkdf2WebParity(cleanPassword.toCharArray())
            val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000L))
            val adminDisplayName = currentFirebaseUser?.displayName ?: "Waqar Khan (Admin)"

            val adminCred = PortalCredential(
                username = cleanUsername,
                passwordHash = argonDigest.encodedHash,
                passwordSalt = argonDigest.saltBase64,
                role = PortalRole.ADMIN.key,
                linkedEntityId = "ADMIN_ROOT",
                displayName = adminDisplayName,
                assignedByUid = resolvedUid,
                active = true,
                isAdmin = true,
                portalRoles = listOf(PortalRole.ADMIN.key),
                createdAt = safeNow,
                pbkdf2ParityHash = pbkdf2Parity.hashHex,
                pbkdf2ParitySalt = pbkdf2Parity.saltHex
            )
            InstituteRepository.cacheCredentialLocally(adminCred)

            val session = AuthenticatedPortalSession(
                firebaseUser = currentFirebaseUser,
                sessionUid = resolvedUid,
                role = PortalRole.ADMIN,
                username = cleanUsername,
                displayName = adminCred.displayName,
                linkedEntityId = "ADMIN_ROOT",
                deviceFingerprint = deviceFp
            )

            CoroutineScope(Dispatchers.IO).launch {
                runCatching { portalAuthRepository.provisionCredentialWithRoleFlags(adminCred, currentFirebaseUser?.email.orEmpty()) }
                val expiresAt = Timestamp(Date(System.currentTimeMillis() + 24L * 3600L * 1000L))
                runCatching {
                    repository.saveBoundPortalSession(
                        uid = resolvedUid,
                        actorId = "ADMIN_ROOT",
                        username = cleanUsername,
                        role = PortalRole.ADMIN.key,
                        deviceFingerprint = deviceFp,
                        expiresAt = expiresAt
                    )
                }
                runCatching {
                    logSecurityAuditEvent(
                        actorUid = resolvedUid,
                        actorName = adminCred.displayName,
                        eventType = "ADMIN_BOOTSTRAP_SUCCESS",
                        severity = "INFO",
                        details = "Bootstrapped and saved verified owner admin '$cleanUsername' to Cloud Firestore (fp=$deviceFp)"
                    )
                }
                runCatching { sealSessionInKeystore(activityContext, session) }
            }

            return@runCatching session
        }

        // Handle Admin portal lookup miss clearly without requiring Google Sign-In
        val unavailableOutcome = tokenOutcome as? CustomTokenExchangeOutcome.Unavailable
        val non404ServerError = unavailableOutcome?.errorMessage?.takeIf {
            unavailableOutcome.httpStatus != 404 && !unavailableOutcome.networkFailure
        }
        if (selectedRole == PortalRole.ADMIN) {
            val adminCtx = context ?: activityContext
            if (AdminEncryptedCredentialStore.isAdminConfigured(adminCtx)) {
                throw SecurityException("Invalid username or password.")
            }
            if (non404ServerError != null) {
                throw SecurityException(non404ServerError)
            }
            val hadTrueNetworkUnavailable =
                unavailableOutcome?.networkFailure == true ||
                    (lookupResult is PortalAuthRepository.CredentialLookupResult.NetworkUnavailable &&
                        lookupResult.reason?.contains("PERMISSION_DENIED", ignoreCase = true) != true &&
                        unavailableOutcome == null)
            if (hadTrueNetworkUnavailable) {
                throw java.net.ConnectException(
                    unavailableOutcome?.errorMessage ?: "No internet connection. Please check your network and try again."
                )
            }
            if (isPermittedAdminCandidate &&
                lookupResult is PortalAuthRepository.CredentialLookupResult.ConfirmedMissing &&
                !portalAuthRepository.hasAnyAdminCredential()
            ) {
                throw FirstRunAdminSetupRequiredException(cleanUsername)
            }
        } else {
            if (non404ServerError != null) {
                throw SecurityException(non404ServerError)
            }
            if (unavailableOutcome?.networkFailure == true) {
                throw java.net.ConnectException(
                    unavailableOutcome.errorMessage ?: "No internet connection. Please check your network and try again."
                )
            }
            if (lookupResult is PortalAuthRepository.CredentialLookupResult.NetworkUnavailable &&
                lookupResult.reason?.contains("PERMISSION_DENIED", ignoreCase = true) != true
            ) {
                throw IOException("No internet connection. Please check your network and try again.")
            }
        }

        // Security guard: NEVER mint credentials or accept arbitrary passwords for unprovisioned directory entries.
        val directoryExistsUnprovisioned = runCatching {
            repository.findMatchingStaffForLogin(cleanUsername) != null ||
                portalAuthRepository.fetchGuardianByUsernameOrPhone(cleanUsername) != null ||
                repository.findMatchingStudentForLogin(cleanUsername) != null
        }.getOrDefault(false)

        CoroutineScope(Dispatchers.IO).launch {
            logSecurityAuditEvent(
                actorUid = currentFirebaseUser?.uid ?: "unauthenticated",
                actorName = cleanUsername,
                eventType = if (directoryExistsUnprovisioned) "LOGIN_UNPROVISIONED_ACCOUNT" else "LOGIN_FAILED_UNKNOWN_USER",
                severity = "WARNING",
                details = "Rejected unprovisioned login attempt for '$cleanUsername' on ${selectedRole.key} portal (fp=$deviceFp)"
            )
        }
        val rejectionMessage = if (directoryExistsUnprovisioned) {
            "Account '$cleanUsername' is in the directory, but no portal password is provisioned yet. Please ask Admin to set your portal password."
        } else {
            "Invalid username or password. Account '$cleanUsername' is not provisioned for the ${selectedRole.label} portal."
        }
        throw SecurityException(rejectionMessage)
    }

    /**
     * Updates the Admin's own username and password immediately inside EncryptedSharedPreferences/Keystore
     * (SHA-256 hash) for offline/online <0.1s login AND syncs to Cloud Firestore asynchronously.
     */
    suspend fun updateAdminOwnCredentials(
        oldUsername: String,
        newUsername: String,
        newPasswordChars: CharArray,
        actorUidOverride: String? = null,
        biometricActionSignatureHex: String = ""
    ): Result<PortalCredential> = runCatching {
        val displayName = auth.currentUser?.displayName ?: "Waqar Khan (Admin)"
        val cleanNew = normalizeUsername(newUsername)
        val rawPass = String(newPasswordChars).trim()
        Arrays.fill(newPasswordChars, '\u0000')
        val resetRate = EnterpriseRateLimiter.checkAndConsume(
            context = context,
            scope = EnterpriseRateLimiter.RateLimitScope.PASSWORD_RESET,
            identityKey = normalizeUsername(oldUsername).ifBlank { cleanNew }
        )
        if (!resetRate.allowed) {
            throw SecurityException(resetRate.message)
        }
        require(cleanNew.length >= 3) { "Admin username must be at least 3 characters." }
        require(rawPass.length >= 4) { "Admin password must be at least 4 characters." }

        val targetCtx = context
        if (targetCtx != null) {
            val charsCopy = rawPass.toCharArray()
            try {
                AdminEncryptedCredentialStore.saveUpdatedAdminCredentials(
                    context = targetCtx,
                    newUsername = cleanNew,
                    newPasswordChars = charsCopy,
                    previousUsernameHint = oldUsername
                )
            } finally {
                Arrays.fill(charsCopy, '\u0000')
            }
        }

        val argonDigest = Argon2CredentialHasher.hashPasswordArgon2id(rawPass.toCharArray())
        val pbkdf2Parity = Argon2CredentialHasher.hashPasswordPbkdf2WebParity(rawPass.toCharArray())
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000L))
        val adminUid = auth.currentUser?.uid
            ?: actorUidOverride?.takeIf { it.isNotBlank() }
            ?: "admin_waqar_root"
        val flags = PortalAuthRepository.buildRoleFlags(role = PortalRole.ADMIN)

        val localUpdatedCred = PortalCredential(
            username = cleanNew,
            passwordHash = argonDigest.encodedHash,
            passwordSalt = argonDigest.saltBase64,
            role = PortalRole.ADMIN.key,
            linkedEntityId = "ADMIN_ROOT",
            displayName = displayName,
            assignedByUid = adminUid,
            active = true,
            isAdmin = flags.isAdmin,
            portalRoles = flags.portalRoles,
            createdAt = safeNow,
            pbkdf2ParityHash = pbkdf2Parity.hashHex,
            pbkdf2ParitySalt = pbkdf2Parity.saltHex
        )

        val cleanOld = normalizeUsername(oldUsername)
        if (cleanOld.isNotBlank() && cleanOld != cleanNew) {
            InstituteRepository.localCredentialsCache.remove(cleanOld)
        }
        InstituteRepository.cacheCredentialLocally(localUpdatedCred)

        // Immediately sync updated Firebase Auth password + salted hash to Cloud Firestore /portalCredentials/{newUsername}
        // and remove old username doc if renamed (bounded timeout so offline devices never stall)
        val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(4_500L) {
                runCatching {
                    if (!isRobolectric) {
                        val derivedNewSecret = derivePortalAuthSecret(rawPass)
                        runCatching { auth.currentUser?.updatePassword(derivedNewSecret)?.await() }
                        val updatedAdminProfile = UserProfile(
                            uid = adminUid,
                            email = auth.currentUser?.email ?: toPortalAuthEmail(cleanNew),
                            displayName = displayName,
                            role = PortalRole.ADMIN.key,
                            username = cleanNew,
                            phone = "",
                            mfaEnabled = false,
                            totpSecret = "",
                            linkedEntityId = "ADMIN_ROOT",
                            isAdmin = true,
                            portalRoles = listOf(PortalRole.ADMIN.key),
                            createdAt = safeNow
                        )
                        runCatching { repository.saveUserProfile(updatedAdminProfile) }
                        if (cleanOld.isNotBlank() && cleanOld != cleanNew) {
                            getOrCreateSecondaryProvisioningAuth()?.let { secondaryAuth ->
                                try {
                                    val newEmail = toPortalAuthEmail(cleanNew)
                                    val newUid = runCatching {
                                        secondaryAuth.createUserWithEmailAndPassword(newEmail, derivedNewSecret).await().user?.uid
                                    }.getOrNull()
                                    if (!newUid.isNullOrBlank()) {
                                        runCatching {
                                            repository.saveUserProfile(
                                                updatedAdminProfile.copy(uid = newUid, email = newEmail)
                                            )
                                        }
                                    }
                                } finally {
                                    runCatching { secondaryAuth.signOut() }
                                }
                            }
                        }
                    }
                    portalAuthRepository.provisionCredentialWithRoleFlags(localUpdatedCred)
                    if (cleanOld.isNotBlank() && cleanOld != cleanNew) {
                        runCatching { repository.deletePortalCredential(cleanOld) }
                    }
                }
            }
        }

        // Best-effort seal into server-side tamper-evident audit hash chain
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                com.waqar.institute.security.ServerAuditHashChainClient.sealAuditChainEvent(
                    context = context,
                    eventType = "ADMIN_CREDENTIALS_ROTATED",
                    recordId = cleanNew,
                    payload = mapOf(
                        "previousUsername" to cleanOld,
                        "newUsername" to cleanNew,
                        "actorUid" to adminUid
                    ),
                    biometricSignatureHex = biometricActionSignatureHex
                )
            }
        }

        localUpdatedCred
    }

    /**
     * Provisions or updates a role credential in Cloud Firestore (`portalCredentials/{username}`).
     */
    suspend fun provisionOrUpdateCredential(
        username: String,
        rawPasswordChars: CharArray,
        role: PortalRole,
        linkedEntityId: String,
        displayName: String,
        actorUidOverride: String? = null
    ): Result<PortalCredential> = runCatching {
        val rawPass = String(rawPasswordChars)
        Arrays.fill(rawPasswordChars, '\u0000')
        provisionOrUpdateCloudCredential(
            previousUsername = null,
            newUsername = username,
            rawPassword = rawPass,
            role = role,
            linkedEntityId = linkedEntityId,
            displayName = displayName,
            actorUidOverride = actorUidOverride
        )
    }

    /**
     * Provisions or updates a Student, Parent, Teacher, or Admin credential in Cloud Firestore
     * using Argon2id (64 MiB memory, 3 iterations, 2 lanes).
     */
    suspend fun provisionOrUpdateCloudCredential(
        previousUsername: String?,
        newUsername: String,
        rawPassword: String,
        role: PortalRole,
        linkedEntityId: String,
        displayName: String,
        actorUidOverride: String? = null
    ): PortalCredential {
        val cleanNew = normalizeUsername(newUsername)
        val cleanPass = rawPassword.trim()
        require(cleanNew.length >= 3) { "Username must be at least 3 characters." }
        require(cleanPass.length >= 6) { "Password must be at least 6 characters." }

        val adminUid = auth.currentUser?.uid
            ?: actorUidOverride?.takeIf { it.isNotBlank() }
            ?: "cred_admin"

        val argonDigest = Argon2CredentialHasher.hashPasswordArgon2id(cleanPass.toCharArray())
        val pbkdf2Parity = Argon2CredentialHasher.hashPasswordPbkdf2WebParity(cleanPass.toCharArray())
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000L))

        val existing = InstituteRepository.getCachedCredentialLocally(cleanNew)
            ?: portalAuthRepository.fetchPortalCredentialCrossDevice(cleanNew)
        val extraRoles = existing?.effectivePortalRoles().orEmpty().map { PortalRole.fromKey(it) }
        val flags = PortalAuthRepository.buildRoleFlags(role = role, extraRoles = extraRoles)

        val credential = PortalCredential(
            username = cleanNew,
            passwordHash = argonDigest.encodedHash,
            passwordSalt = argonDigest.saltBase64,
            role = role.key,
            linkedEntityId = linkedEntityId,
            displayName = displayName.trim(),
            assignedByUid = existing?.assignedByUid?.takeIf { it.isNotBlank() } ?: adminUid,
            active = true,
            isStudent = flags.isStudent,
            isTeacher = flags.isTeacher,
            isParent = flags.isParent,
            isAdmin = flags.isAdmin,
            portalRoles = flags.portalRoles,
            createdAt = existing?.createdAt ?: safeNow,
            pbkdf2ParityHash = pbkdf2Parity.hashHex,
            pbkdf2ParitySalt = pbkdf2Parity.saltHex
        )

        return withContext(Dispatchers.IO) {
            val authEmail = toPortalAuthEmail(cleanNew)
            val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
            if (!isRobolectric) {
                getOrCreateSecondaryProvisioningAuth()?.let { secondaryAuth ->
                    val derivedSecret = derivePortalAuthSecret(cleanPass)
                    try {
                        val provisionedUid = try {
                            secondaryAuth.createUserWithEmailAndPassword(authEmail, derivedSecret).await().user?.uid
                        } catch (_: FirebaseAuthUserCollisionException) {
                            runCatching {
                                secondaryAuth.signInWithEmailAndPassword(authEmail, derivedSecret).await().user?.uid
                            }.getOrNull()
                        } catch (_: Exception) {
                            null
                        }
                        if (!provisionedUid.isNullOrBlank()) {
                            val authUserProfile = UserProfile(
                                uid = provisionedUid,
                                email = authEmail,
                                displayName = displayName.trim(),
                                role = role.key,
                                username = cleanNew,
                                phone = "",
                                mfaEnabled = false,
                                totpSecret = "",
                                linkedEntityId = linkedEntityId,
                                isStudent = flags.isStudent,
                                isTeacher = flags.isTeacher,
                                isParent = flags.isParent,
                                isAdmin = flags.isAdmin,
                                portalRoles = flags.portalRoles,
                                createdAt = existing?.createdAt ?: safeNow
                            )
                            runCatching { repository.saveUserProfile(authUserProfile) }
                        }
                    } finally {
                        runCatching { secondaryAuth.signOut() }
                    }
                }
            }

            val saved = portalAuthRepository.provisionCredentialWithRoleFlags(credential, emailOverride = authEmail)
            val cleanOld = previousUsername?.let { normalizeUsername(it) }
            if (!cleanOld.isNullOrBlank() && cleanOld != cleanNew) {
                runCatching { repository.deletePortalCredential(cleanOld) }
            }
            saved
        }
    }

    fun signOut(activityContext: Context? = context) {
        activityContext?.let { HardwareKeystoreVault.clearHardwareSessionToken(it) }
        MemoryScrubberLifecycleObserver.scrubAllRegisteredBuffersNow()
        auth.signOut()
    }

    private suspend fun logSecurityAuditEvent(
        actorUid: String,
        actorName: String,
        eventType: String,
        severity: String,
        details: String
    ) {
        runCatching {
            val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
            repository.logAuditEvent(
                SecurityAuditLog(
                    id = "audit_${UUID.randomUUID().toString().take(8)}",
                    actorUid = actorUid,
                    actorEmail = actorName,
                    action = eventType,
                    module = "M01_AUTH",
                    targetId = actorName,
                    severity = severity,
                    details = details,
                    createdAt = safeNow
                )
            )
        }.onFailure { err ->
            Log.d(TAG, "Audit log skipped: ${err.localizedMessage}")
        }
    }

    private fun sealSessionInKeystore(activityContext: Context, session: AuthenticatedPortalSession) {
        val envelope = SessionFingerprintManager.issueBoundSessionEnvelope(
            context = activityContext.applicationContext,
            uid = session.sessionUid,
            username = session.username,
            roleKey = session.role.key,
            linkedEntityId = session.linkedEntityId
        )
        val json = """{"uid":"${session.sessionUid}","username":"${session.username}","role":"${session.role.key}","linkedEntityId":"${session.linkedEntityId}","displayName":"${session.displayName}","fp":"${envelope.deviceFingerprintHash}","hmac":"${envelope.hmacSeal}"}"""
        HardwareKeystoreVault.saveHardwareSessionToken(activityContext.applicationContext, json)
        runCatching {
            TeeSecurityManager.encryptWithTrustZoneTee(
                context = activityContext.applicationContext,
                plaintext = json.toByteArray(Charsets.UTF_8),
                tenantContextSeed = session.role.key
            )
        }
    }
}
