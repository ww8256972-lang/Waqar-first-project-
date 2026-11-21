package com.institute.gateway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class TupleKey(
    val user: String,
    val relation: String,
    val `object`: String,
)

@Serializable
data class CheckReq(
    val tuple_key: TupleKey,
    val authorization_model_id: String,
)

@Serializable
data class CheckRes(
    val allowed: Boolean = false,
)

interface AuthorizationChecker {
    suspend fun check(user: String, relation: String, obj: String): Boolean
}

class FgaClient(
    private val http: HttpClient,
    private val base: String,
    private val storeId: String,
    private val modelId: String,
    private val apiKey: String,
) : AuthorizationChecker {
    override suspend fun check(user: String, relation: String, obj: String): Boolean = try {
        if (storeId.isBlank() || modelId.isBlank()) {
            false
        } else {
            http.post("${base.trimEnd('/')}/stores/$storeId/check") {
                if (apiKey.isNotBlank()) {
                    bearerAuth(apiKey)
                }
                contentType(ContentType.Application.Json)
                setBody(CheckReq(TupleKey(user, relation, obj), modelId))
            }.body<CheckRes>().allowed
        }
    } catch (_: Exception) {
        false // FAIL CLOSED: any OpenFGA/network error denies access
    }
}
