package com.example.data.firestore

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType {
    CREATE, UPDATE, DELETE, LIST, GET, WRITE
}

data class FirestoreErrorInfo(
    val error: String,
    val operationType: String,
    val path: String?,
    val authInfo: AuthInfo
) {
    data class AuthInfo(
        val userId: String?,
        val email: String?,
        val emailVerified: Boolean?,
        val isAnonymous: Boolean?,
        val tenantId: String?,
        val providerInfo: List<ProviderInfo>
    )

    data class ProviderInfo(
        val providerId: String,
        val displayName: String?,
        val email: String?,
        val photoUrl: String?
    )

    fun toJsonString(): String {
        return JSONObject().apply {
            put("error", error)
            put("operationType", operationType)
            put("path", path ?: JSONObject.NULL)
            put("authInfo", JSONObject().apply {
                put("userId", authInfo.userId ?: JSONObject.NULL)
                put("email", authInfo.email ?: JSONObject.NULL)
                put("emailVerified", authInfo.emailVerified ?: JSONObject.NULL)
                put("isAnonymous", authInfo.isAnonymous ?: JSONObject.NULL)
                put("tenantId", authInfo.tenantId ?: JSONObject.NULL)
                put("providerInfo", JSONArray().apply {
                    authInfo.providerInfo.forEach { p ->
                        put(JSONObject().apply {
                            put("providerId", p.providerId)
                            put("displayName", p.displayName ?: JSONObject.NULL)
                            put("email", p.email ?: JSONObject.NULL)
                            put("photoUrl", p.photoUrl ?: JSONObject.NULL)
                        })
                    }
                })
            })
        }.toString()
    }
}

class FirestoreOperationException(message: String, cause: Throwable) : Exception(message, cause)

fun handleFirestoreError(
    error: Throwable,
    operationType: OperationType,
    path: String?,
    auth: FirebaseAuth = FirebaseAuth.getInstance()
): Nothing {
    val currentUser = auth.currentUser
    val errorInfo = FirestoreErrorInfo(
        error = error.message ?: error.toString(),
        operationType = operationType.name.lowercase(),
        path = path,
        authInfo = FirestoreErrorInfo.AuthInfo(
            userId = currentUser?.uid,
            email = currentUser?.email,
            emailVerified = currentUser?.isEmailVerified,
            isAnonymous = currentUser?.isAnonymous,
            tenantId = currentUser?.tenantId,
            providerInfo = currentUser?.providerData?.map { profile ->
                FirestoreErrorInfo.ProviderInfo(
                    providerId = profile.providerId,
                    displayName = profile.displayName,
                    email = profile.email,
                    photoUrl = profile.photoUrl?.toString()
                )
            } ?: emptyList()
        )
    )

    val jsonPayload = errorInfo.toJsonString()
    Log.e("FirestoreError", jsonPayload, error)
    throw FirestoreOperationException(jsonPayload, error)
}

fun Throwable.isPermissionDenied(): Boolean =
    (this as? FirebaseFirestoreException)?.code ==
        FirebaseFirestoreException.Code.PERMISSION_DENIED ||
    (cause as? FirebaseFirestoreException)?.code ==
        FirebaseFirestoreException.Code.PERMISSION_DENIED
