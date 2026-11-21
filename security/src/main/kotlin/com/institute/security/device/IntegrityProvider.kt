package com.institute.security.device

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class IntegrityProvider(
    private val ctx: Context,
    private val cloudProject: Long,
) {
    @Volatile
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    suspend fun prepare() {
        if (provider != null || cloudProject <= 0L) return
        provider = IntegrityManagerFactory.createStandard(ctx)
            .prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProject)
                    .build(),
            )
            .await()
    }

    /**
     * Requests a Standard Play Integrity token bound to [requestHash].
     * Typically requestHash = base64/hex SHA-256 of "action|objectId|timestamp|nonce".
     */
    suspend fun token(requestHash: String): String {
        prepare()
        val p = provider ?: throw IllegalStateException("Play Integrity provider not configured")
        return p.request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build(),
        ).await().token()
    }

    suspend fun tokenOrNull(requestHash: String): String? =
        runCatching { token(requestHash) }.getOrNull()

    companion object {
        fun computeRequestHash(action: String, objectId: String, timestampSec: Long, nonce: String): String {
            val raw = "$action|$objectId|$timestampSec|$nonce".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            return DpopSigner.b64u(digest)
        }
    }
}
