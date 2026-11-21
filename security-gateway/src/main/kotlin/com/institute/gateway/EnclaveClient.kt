package com.institute.gateway

import com.institute.crypto.HybridKem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

@Serializable
data class SealedEnvelopeDto(
    val kid: String,
    val kemCt: String,
    val eph: String,
    val nonce: String,
    val ct: String,
)

@Serializable
data class SealedRequest(
    val aad: String,
    val sealed: SealedEnvelopeDto,
)

@Serializable
data class FaceVerifyRequest(
    val aad: String,
    val probe: SealedEnvelopeDto,
)

@Serializable
data class EnclaveVerifyPayload(
    val aad: String,
    val probe: SealedEnvelopeDto,
    val template: SealedEnvelopeDto? = null,
)

@Serializable
data class FaceVerifyResponse(
    val match: Boolean = false,
    val score: Float = 0f,
)

/**
 * Supports 90-day PQC key rotation (Section 7.4): holds up to 2 recent recipient private keys
 * (active + previous) indexed by key ID (`kid`).
 */
class PqcKeyring(keys: List<HybridKem.RecipientPrivate> = emptyList()) {
    private val keysByKid: Map<String, HybridKem.RecipientPrivate> =
        keys.take(2).associateBy { it.kid }

    fun get(kid: String): HybridKem.RecipientPrivate? = keysByKid[kid]

    fun hasKeys(): Boolean = keysByKid.isNotEmpty()
}

/**
 * Gateway-side Phase 3 PQC envelope validator and decryptor (hybrid ML-KEM-768 + X25519 + AES-GCM).
 * Enforces AAD structure ("action|objectId|tsSec|nonce"), 10-minute timestamp window,
 * single-use replay_nonces tracking, and authenticated AEAD decryption.
 */
class PqcEnvelopeService(
    private val keyring: PqcKeyring,
    private val nonces: ReplayNonceStore,
    private val maxAgeSec: Long = 600L,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    fun openVerified(
        expectedAction: String,
        expectedObjectId: String,
        aad: String,
        dto: SealedEnvelopeDto,
    ): ByteArray {
        val parts = aad.split("|")
        if (parts.size != 4) throw Denied(400, "malformed PQC aad")
        val (action, objId, tsRaw, nonce) = parts
        if (action != expectedAction || objId != expectedObjectId) {
            throw Denied(403, "PQC aad context mismatch")
        }

        val tsSec = tsRaw.toLongOrNull() ?: throw Denied(400, "invalid PQC aad timestamp")
        val now = nowSec()
        if (kotlin.math.abs(now - tsSec) > maxAgeSec) {
            throw Denied(401, "expired PQC envelope")
        }
        if (nonce.isBlank() || !nonces.recordIfFresh("pqc:$nonce")) {
            throw Denied(401, "replayed PQC envelope nonce")
        }

        val priv = keyring.get(dto.kid) ?: throw Denied(400, "unknown PQC kid: ${dto.kid}")
        val dec = Base64.getDecoder()
        val sealed = runCatching {
            HybridKem.Sealed(
                kid = dto.kid,
                kemCt = dec.decode(dto.kemCt),
                ephX25519 = dec.decode(dto.eph),
                nonce = dec.decode(dto.nonce),
                ct = dec.decode(dto.ct),
            )
        }.getOrElse { throw Denied(400, "malformed PQC envelope base64") }

        return runCatching {
            HybridKem.open(sealed, aad.toByteArray(Charsets.UTF_8), priv)
        }.getOrElse {
            throw Denied(403, "PQC decryption or AAD authentication failed")
        }
    }
}

interface FaceTemplateRepository {
    fun upsert(userId: String, kid: String, sealedJson: String)
    fun find(userId: String): Pair<String, String>?
}

class InMemoryFaceTemplateRepository : FaceTemplateRepository {
    private val store = ConcurrentHashMap<String, Pair<String, String>>()

    override fun upsert(userId: String, kid: String, sealedJson: String) {
        store[userId] = kid to sealedJson
    }

    override fun find(userId: String): Pair<String, String>? = store[userId]
}

class PostgresFaceTemplateRepository(private val ds: DataSource) : FaceTemplateRepository {
    override fun upsert(userId: String, kid: String, sealedJson: String) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO face_templates (user_id, kid, sealed, updated_at)
                VALUES (?, ?, ?::jsonb, now())
                ON CONFLICT (user_id) DO UPDATE SET
                    kid = EXCLUDED.kid,
                    sealed = EXCLUDED.sealed,
                    updated_at = now()
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, kid)
                ps.setString(3, sealedJson)
                ps.executeUpdate()
            }
        }
    }

    override fun find(userId: String): Pair<String, String>? {
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT kid, sealed::text FROM face_templates WHERE user_id = ?").use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.getString(1) to rs.getString(2)
                }
            }
        }
    }
}

interface EnclaveVerifier {
    suspend fun verifyFace(req: FaceVerifyRequest, principal: Principal): FaceVerifyResponse
}

class EnclaveClient(
    private val http: HttpClient,
    private val enclaveBaseUrl: String,
) : EnclaveVerifier {
    override suspend fun verifyFace(req: FaceVerifyRequest, principal: Principal): FaceVerifyResponse {
        if (enclaveBaseUrl.isBlank()) {
            return FaceVerifyResponse(match = false, score = 0f)
        }
        return try {
            val resp = http.post("${enclaveBaseUrl.trimEnd('/')}/face/verify") {
                contentType(ContentType.Application.Json)
                setBody(EnclaveVerifyPayload(aad = req.aad, probe = req.probe))
            }
            if (!resp.status.isSuccess()) {
                FaceVerifyResponse(match = false, score = 0f)
            } else {
                resp.body<FaceVerifyResponse>()
            }
        } catch (_: Exception) {
            FaceVerifyResponse(match = false, score = 0f)
        }
    }
}
