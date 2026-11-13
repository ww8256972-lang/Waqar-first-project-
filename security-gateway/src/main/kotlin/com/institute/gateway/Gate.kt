package com.institute.gateway

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.header
import io.ktor.server.request.uri
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

data class GatewayTokenClaims(
    val sub: String,
    val deviceId: String,
    val cnfJkt: String,
    val expEpochSec: Long,
)

interface GatewayJwtService {
    fun issue(userId: String, deviceId: String, cnfJkt: String, ttlSec: Long = 600L): Pair<String, Long>
    fun verify(token: String): GatewayTokenClaims
}

class HmacGatewayJwtService(
    secretBytes: ByteArray,
    private val issuer: String = "institute-security-gateway",
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : GatewayJwtService {
    private val key: ByteArray = if (secretBytes.size >= 32) {
        secretBytes
    } else {
        MessageDigest.getInstance("SHA-256").digest(secretBytes)
    }

    override fun issue(userId: String, deviceId: String, cnfJkt: String, ttlSec: Long): Pair<String, Long> {
        val clampedTtl = ttlSec.coerceIn(60L, 600L) // Max 10 min lifetime per BeyondCorp Layer 2
        val issuedAt = Date(nowMs())
        val expiresAt = Date(issuedAt.time + clampedTtl * 1000L)
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(userId)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(issuedAt)
            .expirationTime(expiresAt)
            .claim("did", deviceId)
            .claim("cnf", mapOf("jkt" to cnfJkt))
            .build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        jwt.sign(MACSigner(key))
        return jwt.serialize() to clampedTtl
    }

    override fun verify(token: String): GatewayTokenClaims {
        val jwt = runCatching { SignedJWT.parse(token) }.getOrElse { throw Denied(401, "malformed token") }
        val ok = runCatching { jwt.verify(MACVerifier(key)) }.getOrDefault(false)
        if (!ok) throw Denied(401, "invalid token signature")

        val claims = jwt.jwtClaimsSet
        val exp = claims.expirationTime ?: throw Denied(401, "token missing exp")
        val now = nowMs()
        if (exp.time <= now) throw Denied(401, "token expired")
        val iat = claims.issueTime
        if (iat != null && (exp.time - iat.time) > 605_000L) {
            throw Denied(401, "token lifetime exceeds 10 min")
        }

        val sub = claims.subject?.takeIf { it.isNotBlank() } ?: throw Denied(401, "token missing sub")
        val did = claims.getStringClaim("did")?.takeIf { it.isNotBlank() } ?: throw Denied(401, "token missing deviceId")
        @Suppress("UNCHECKED_CAST")
        val cnf = claims.getJSONObjectClaim("cnf") ?: throw Denied(401, "token missing cnf")
        val jkt = (cnf["jkt"] as? String)?.takeIf { it.isNotBlank() } ?: throw Denied(401, "token missing cnf.jkt")

        return GatewayTokenClaims(sub = sub, deviceId = did, cnfJkt = jkt, expEpochSec = exp.time / 1000L)
    }
}

interface UpstreamTokenVerifier {
    fun verifyUserId(idToken: String): String
}

class FirebaseJwksTokenVerifier(
    private val firebaseProjectId: String,
    private val jwksUrl: String = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com",
) : UpstreamTokenVerifier {
    private val processor by lazy {
        val source = JWKSourceBuilder.create<SecurityContext>(URI(jwksUrl).toURL()).build()
        DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, source)
        }
    }

    override fun verifyUserId(idToken: String): String {
        if (idToken.isBlank()) throw Denied(401, "missing upstream idToken")
        if (firebaseProjectId.isBlank()) throw Denied(500, "FIREBASE_PROJECT_ID not configured")
        val claims = runCatching { processor.process(idToken, null) }
            .getOrElse { throw Denied(401, "invalid upstream idToken") }
        val expectedIssuer = "https://securetoken.google.com/$firebaseProjectId"
        if (claims.issuer != expectedIssuer || firebaseProjectId !in claims.audience) {
            throw Denied(401, "upstream idToken issuer/audience mismatch")
        }
        val exp = claims.expirationTime ?: throw Denied(401, "upstream idToken missing exp")
        if (exp.before(Date())) throw Denied(401, "upstream idToken expired")
        return claims.subject?.takeIf { it.isNotBlank() } ?: throw Denied(401, "upstream idToken missing sub")
    }
}

interface ReplayNonceStore {
    fun recordIfFresh(nonce: String): Boolean
}

class InMemoryReplayNonceStore(
    private val ttlMs: Long = 600_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ReplayNonceStore {
    private val seen = ConcurrentHashMap<String, Long>()

    override fun recordIfFresh(nonce: String): Boolean {
        val now = nowMs()
        seen.entries.removeIf { now - it.value > ttlMs }
        return seen.putIfAbsent(nonce, now) == null
    }
}

class PostgresReplayNonceStore(private val ds: DataSource) : ReplayNonceStore {
    override fun recordIfFresh(nonce: String): Boolean {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO replay_nonces (nonce, seen_at) VALUES (?, now()) ON CONFLICT (nonce) DO NOTHING",
            ).use { ps ->
                ps.setString(1, nonce)
                return ps.executeUpdate() == 1
            }
        }
    }
}

class DpopVerifier(
    private val nonces: ReplayNonceStore,
    private val maxSkewSec: Long = 120L,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    /**
     * Verifies RFC 9449 DPoP proof signature, header jwk thumbprint, htm/htu binding,
     * optional ath = base64url(sha256(accessToken)), and single-use jti replay protection.
     * Returns the verified RFC 7638 JWK thumbprint.
     */
    fun verify(
        proof: String,
        method: String,
        url: String,
        accessToken: String?,
        expectedJkt: String?,
    ): String {
        val jwt = runCatching { SignedJWT.parse(proof) }
            .getOrElse { throw Denied(401, "malformed DPoP proof") }

        val header = jwt.header
        if (header.type != JOSEObjectType("dpop+jwt")) throw Denied(401, "invalid DPoP typ")
        if (header.algorithm != JWSAlgorithm.ES256) throw Denied(401, "invalid DPoP alg")

        val ecJwk = (header.jwk as? ECKey) ?: throw Denied(401, "DPoP missing EC jwk")
        val thumbprint = ecJwk.computeThumbprint().toString()
        if (!expectedJkt.isNullOrBlank() && thumbprint != expectedJkt) {
            throw Denied(401, "DPoP key mismatch with token cnf.jkt")
        }

        val sigOk = runCatching { jwt.verify(ECDSAVerifier(ecJwk)) }.getOrDefault(false)
        if (!sigOk) throw Denied(401, "invalid DPoP signature")

        val claims = jwt.jwtClaimsSet
        val htm = claims.getStringClaim("htm") ?: throw Denied(401, "DPoP missing htm")
        val htu = claims.getStringClaim("htu") ?: throw Denied(401, "DPoP missing htu")
        if (!htm.equals(method, ignoreCase = true)) throw Denied(401, "DPoP htm mismatch")
        if (htu.substringBefore("?") != url.substringBefore("?")) throw Denied(401, "DPoP htu mismatch")

        val iat = claims.issueTime?.time?.div(1000L) ?: throw Denied(401, "DPoP missing iat")
        val now = nowSec()
        if (kotlin.math.abs(now - iat) > maxSkewSec) throw Denied(401, "DPoP iat outside window")

        val jti = claims.jwtid?.takeIf { it.isNotBlank() } ?: throw Denied(401, "DPoP missing jti")
        if (!nonces.recordIfFresh("dpop:$thumbprint:$jti")) {
            throw Denied(401, "replayed DPoP jti")
        }

        if (!accessToken.isNullOrBlank()) {
            val expectedAth = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.UTF_8)),
            )
            val actualAth = claims.getStringClaim("ath") ?: throw Denied(401, "DPoP missing ath")
            if (actualAth != expectedAth) throw Denied(401, "DPoP ath mismatch")
        }

        return thumbprint
    }
}

class RiskEngine {
    private val lastIpByUser = ConcurrentHashMap<String, Pair<String, Long>>()

    fun assess(userId: String, device: EnrolledDevice, remoteIp: String): Map<String, String> {
        val now = System.currentTimeMillis()
        val prev = lastIpByUser.put(userId, remoteIp to now)
        val signals = mutableMapOf<String, String>()
        if (prev != null && prev.first != remoteIp && (now - prev.second) < 60_000L) {
            signals["rapidIpSwitch"] = "${prev.first}->$remoteIp"
        }
        if (device.attestationLevel == AttestationLevel.SOFTWARE) {
            signals["softwareKeystore"] = "true"
        }
        return signals
    }
}

data class AuditEntry(
    val userId: String?,
    val deviceId: String?,
    val action: String,
    val obj: String?,
    val decision: String,
    val tier: Tier,
    val ip: String?,
    val detailJson: String?,
)

interface AuditLogger {
    fun log(
        userId: String?,
        deviceId: String?,
        action: String,
        obj: String?,
        decision: String,
        tier: Tier,
        ip: String? = null,
        detailJson: String? = null,
    )
}

class InMemoryAuditLogger : AuditLogger {
    val entries = mutableListOf<AuditEntry>()

    @Synchronized
    override fun log(
        userId: String?,
        deviceId: String?,
        action: String,
        obj: String?,
        decision: String,
        tier: Tier,
        ip: String?,
        detailJson: String?,
    ) {
        entries.add(AuditEntry(userId, deviceId, action, obj, decision, tier, ip, detailJson))
    }
}

class PostgresAuditLogger(private val ds: DataSource) : AuditLogger {
    override fun log(
        userId: String?,
        deviceId: String?,
        action: String,
        obj: String?,
        decision: String,
        tier: Tier,
        ip: String?,
        detailJson: String?,
    ) {
        runCatching {
            val did = deviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO audit_log (user_id, device_id, action, object, decision, tier, ip, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?::jsonb)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.setObject(2, did)
                    ps.setString(3, action)
                    ps.setString(4, obj)
                    ps.setString(5, decision)
                    ps.setString(6, tier.name)
                    ps.setString(7, ip?.takeIf { it.isNotBlank() && it != "localhost" })
                    ps.setString(8, detailJson)
                    ps.executeUpdate()
                }
            }
        }
    }
}

class Deps(
    val enforce: Boolean,
    val jwt: GatewayJwtService,
    val upstreamAuth: UpstreamTokenVerifier,
    val dpop: DpopVerifier,
    val devices: DeviceRepository,
    val challenges: ChallengeStore,
    val integrity: IntegrityVerifier,
    val risk: RiskEngine,
    val fga: AuthorizationChecker,
    val enclave: EnclaveVerifier,
    val audit: AuditLogger,
    val publicUrl: (ApplicationCall) -> String,
    val trustedAttestationRoots: Set<String> = AttestationVerifier.GOOGLE_ATTESTATION_ROOT_SPKI_SHA256,
    val pqc: PqcEnvelopeService? = null,
    val faceTemplates: FaceTemplateRepository = InMemoryFaceTemplateRepository(),
)

suspend fun ApplicationCall.gate(d: Deps, p: Policy): Principal {
    val ip = request.origin.remoteHost
    val targetObj = p.objectOf?.invoke(this)
    var userId: String? = null
    var deviceId: String? = null
    var tier = Tier.UNTRUSTED

    try {
        val auth = request.header("Authorization")
            ?.takeIf { it.startsWith("DPoP ") }
            ?.removePrefix("DPoP ")
            ?: throw Denied(401, "no token")

        val claims = d.jwt.verify(auth)
        userId = claims.sub
        deviceId = claims.deviceId

        val proof = request.header("DPoP") ?: throw Denied(401, "no proof")
        d.dpop.verify(
            proof = proof,
            method = request.httpMethod.value,
            url = d.publicUrl(this),
            accessToken = auth,
            expectedJkt = claims.cnfJkt,
        )

        val device = d.devices.find(claims.deviceId) ?: throw Denied(401, "unknown device")
        if (device.revokedAt != null) throw Denied(403, "device revoked")
        if (p.requireHardwareAttestation && device.attestationLevel == AttestationLevel.SOFTWARE) {
            throw Denied(403, "hardware-backed key attestation required")
        }
        d.devices.touchLastSeen(device.id)

        tier = d.integrity.tierFor(device, request.header("X-Integrity"), p.maxIntegrityAgeSec)
        if (tier < p.minTier) throw Denied(403, "device tier too low")

        val riskSignals = d.risk.assess(claims.sub, device, ip)

        if (p.relation != null && targetObj != null) {
            if (!d.fga.check("user:${claims.sub}", p.relation, targetObj)) {
                throw Denied(403, "no permission")
            }
        }

        val detail = if (riskSignals.isEmpty()) null else {
            val entries = riskSignals.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            "{$entries}"
        }
        d.audit.log(claims.sub, device.id, request.uri, targetObj ?: p.relation, "ALLOW", tier, ip, detail)
        return Principal(claims.sub, device.id, tier)
    } catch (denied: Denied) {
        val effectiveEnforce = p.enforceOverride ?: d.enforce
        val escapedMsg = denied.msg.replace("\"", "\\\"")
        val detailJson = """{"status":${denied.status},"reason":"$escapedMsg","enforce":$effectiveEnforce}"""
        if (!effectiveEnforce) {
            // Shadow mode: record WOULD_DENY in audit_log instead of blocking requests
            d.audit.log(userId, deviceId, request.uri, targetObj ?: p.relation, "WOULD_DENY", tier, ip, detailJson)
            return Principal(userId ?: "shadow_unverified", deviceId ?: "shadow_device", tier)
        }
        d.audit.log(userId, deviceId, request.uri, targetObj ?: p.relation, "DENY", tier, ip, detailJson)
        throw denied
    }
}
