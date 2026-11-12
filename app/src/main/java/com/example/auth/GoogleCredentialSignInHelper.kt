package com.example.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Jetpack Credential Manager helper for interactive and silent Google Sign-In
 * (`GetSignInWithGoogleOption` & `GetGoogleIdOption`).
 */
object GoogleCredentialSignInHelper {

    private const val FALLBACK_WEB_CLIENT_ID =
        "790218222135-o9csvfk8ji1k4hg8hfj88ofm433mka86.apps.googleusercontent.com"

    @SuppressLint("DiscouragedApi")
    fun resolveServerClientId(context: Context): String {
        val resId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )
        if (resId != 0) {
            val value = runCatching { context.getString(resId) }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return FALLBACK_WEB_CLIENT_ID
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    suspend fun signInInteractive(
        activityContext: Context,
        firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    ): Result<FirebaseUser> = runCatching {
        val activity = activityContext.findActivity()
            ?: throw IllegalStateException("Activity context required for CredentialManager UI.")
        val credentialManager = CredentialManager.create(activity)
        val serverClientId = resolveServerClientId(activityContext)

        val googleSignInOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleSignInOption)
            .build()

        val result = try {
            credentialManager.getCredential(
                request = request,
                context = activity
            )
        } catch (e: NoCredentialException) {
            throw IllegalStateException("No Google account available on this device.", e)
        }

        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            val authResult = firebaseAuth.signInWithCredential(firebaseCred).await()
            authResult.user ?: error("Firebase user is null after Google credential exchange.")
        } else {
            error("Unsupported credential type returned by CredentialManager: ${credential.type}")
        }
    }

    suspend fun signInSilentlyIfAuthorized(
        activityContext: Context,
        firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    ): FirebaseUser? = runCatching {
        firebaseAuth.currentUser?.let { return@runCatching it }
        val activity = activityContext.findActivity() ?: return@runCatching null
        val credentialManager = CredentialManager.create(activity)
        val serverClientId = resolveServerClientId(activityContext)
        if (serverClientId.isBlank()) return@runCatching null

        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = try {
            credentialManager.getCredential(activity, request)
        } catch (_: NoCredentialException) {
            return@runCatching null
        }
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            firebaseAuth.signInWithCredential(firebaseCred).await().user
        } else null
    }.getOrNull()

    suspend fun clearCredentials(
        context: Context,
        firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    ) {
        firebaseAuth.signOut()
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}
