package com.institute.gateway

import com.institute.crypto.HybridKem
import com.nimbusds.jose.jwk.ECKey
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class ErrorResponse(val error: String, val status: Int)

@Serializable
data class SessionExchangeRequest(
    val idToken: String,
    val deviceId: String,
)

@Serializable
data class SessionExchangeResponse(
    val accessToken: String,
    val tokenType: String = "DPoP",
    val expiresIn: Long,
)

@Serializable
data class AuthzCheckRequest(
    val relation: String,
    val `object`: String,
)

@Serializable
data class AuthzCheckResponse(
    val allowed: Boolean,
)

@Serializable
data class ShadowStatusResponse(
    val status: String,
    val enforce: Boolean,
    val userId: String,
    val deviceId: String,
    val tier: String,
)

fun Application.securityGatewayModule(deps: Deps) {
    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<Denied> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.status),
                ErrorResponse(error = cause.msg, status = cause.status),
            )
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "enforce" to deps.enforce.toString()))
        }

        get("/v1/device/challenge") {
            call.respond(ChallengeResponse(nonce = deps.challenges.issue()))
        }

        post("/v1/device/enroll") {
            val req = call.receive<EnrollRequest>()
            if (!deps.challenges.consume(req.nonce)) {
                throw Denied(401, "invalid or expired challenge nonce")
            }

            val proof = call.request.header("DPoP") ?: throw Denied(401, "no DPoP proof")
            val jkt = deps.dpop.verify(
                proof = proof,
                method = call.request.httpMethod.value,
                url = deps.publicUrl(call),
                accessToken = null,
                expectedJkt = null,
            )

            val userId = deps.upstreamAuth.verifyUserId(req.idToken)
            val attested = AttestationVerifier.verify(
                certChainBase64 = req.certChain,
                expectedChallenge = req.nonce.toByteArray(Charsets.UTF_8),
                submittedJwk = req.jwk,
                trustedRootSpkiSha256 = deps.trustedAttestationRoots,
            )

            val ecJwk = ECKey.parse(req.jwk)
            if (ecJwk.computeThumbprint().toString() != jkt) {
                throw Denied(403, "DPoP proof key mismatch with enrollment JWK")
            }

            val saved = deps.devices.upsert(
                EnrolledDevice(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    pubkeyJkt = jkt,
                    pubkeyJwkJson = ecJwk.toJSONString(),
                    attestationLevel = attested.level,
                    osPatchLevel = req.osPatchLevel ?: attested.osPatchLevel?.toString(),
                    enrolledAt = Instant.now(),
                    lastSeen = Instant.now(),
                    revokedAt = null,
                ),
            )
            deps.audit.log(userId, saved.id, "/v1/device/enroll", "device:${saved.id}", "ALLOW", Tier.BASIC)
            call.respond(
                EnrollResponse(
                    deviceId = saved.id,
                    attestationLevel = saved.attestationLevel.name,
                    pubkeyJkt = saved.pubkeyJkt,
                ),
            )
        }

        post("/v1/session/exchange") {
            val req = call.receive<SessionExchangeRequest>()
            val userId = deps.upstreamAuth.verifyUserId(req.idToken)
            val device = deps.devices.find(req.deviceId) ?: throw Denied(401, "unknown device")
            if (device.userId != userId) throw Denied(403, "device owner mismatch")
            if (device.revokedAt != null) throw Denied(403, "device revoked")

            val proof = call.request.header("DPoP") ?: throw Denied(401, "no DPoP proof")
            deps.dpop.verify(
                proof = proof,
                method = call.request.httpMethod.value,
                url = deps.publicUrl(call),
                accessToken = null,
                expectedJkt = device.pubkeyJkt,
            )

            val (jwt, expiresIn) = deps.jwt.issue(userId = userId, deviceId = device.id, cnfJkt = device.pubkeyJkt)
            deps.audit.log(userId, device.id, "/v1/session/exchange", null, "ALLOW", Tier.BASIC)
            call.respond(SessionExchangeResponse(accessToken = jwt, expiresIn = expiresIn))
        }

        post("/v1/authz/check") {
            val me = call.gate(deps, Policy(minTier = Tier.BASIC))
            val req = call.receive<AuthzCheckRequest>()
            val allowed = deps.fga.check("user:${me.userId}", req.relation, req.`object`)
            call.respond(AuthzCheckResponse(allowed = allowed))
        }

        get("/v1/courses/{courseId}/notes") {
            val me = call.gate(
                deps,
                Policy(Tier.BASIC, "can_view_content") { "course:${it.parameters["courseId"]}" },
            )
            call.respond(ShadowStatusResponse("ok", deps.enforce, me.userId, me.deviceId, me.tier.name))
        }

        get("/v1/fees/{feeId}") {
            val me = call.gate(
                deps,
                Policy(
                    minTier = Tier.DEVICE,
                    relation = "can_view",
                    enforceOverride = true,
                ) { "fee_record:${it.parameters["feeId"]}" },
            )
            call.respond(ShadowStatusResponse("ok", true, me.userId, me.deviceId, me.tier.name))
        }

        post("/v1/fees/{feeId}") {
            val me = call.gate(
                deps,
                Policy(
                    minTier = Tier.STRONG,
                    relation = "can_edit",
                    maxIntegrityAgeSec = 300L,
                    requireHardwareAttestation = true,
                    enforceOverride = true,
                ) { "fee_record:${it.parameters["feeId"]}" },
            )
            call.respond(ShadowStatusResponse("ok", true, me.userId, me.deviceId, me.tier.name))
        }

        post("/v1/attendance/{batchId}/mark") {
            val me = call.gate(
                deps,
                Policy(
                    minTier = Tier.DEVICE,
                    relation = "can_mark_attendance",
                    requireHardwareAttestation = true,
                    enforceOverride = true,
                ) { "batch:${it.parameters["batchId"]}" },
            )
            call.respond(ShadowStatusResponse("ok", true, me.userId, me.deviceId, me.tier.name))
        }

        post("/v1/attendance/{batchId}/face") {
            val me = call.gate(
                deps,
                Policy(
                    minTier = Tier.DEVICE,
                    relation = "can_mark_attendance",
                    requireHardwareAttestation = true,
                    enforceOverride = true,
                ) { "batch:${it.parameters["batchId"]}" },
            )
            val body = call.receive<FaceVerifyRequest>()
            call.respond(deps.enclave.verifyFace(body, me))
        }

        post("/v1/admin/devices/{deviceId}/revoke") {
            val me = call.gate(
                deps,
                Policy(
                    minTier = Tier.STRONG,
                    relation = "admin",
                    maxIntegrityAgeSec = 300L,
                    requireHardwareAttestation = true,
                    enforceOverride = true,
                ) { "institute:main" },
            )
            val targetDevice = call.parameters["deviceId"] ?: throw Denied(400, "missing deviceId")
            val revoked = deps.devices.revoke(targetDevice)
            if (!revoked) throw Denied(404, "device not found")
            deps.audit.log(me.userId, me.deviceId, call.request.uri, "device:$targetDevice", "ALLOW", me.tier)
            call.respond(mapOf("revoked" to revoked.toString(), "deviceId" to targetDevice))
        }

        post("/v1/fees/{feeId}/sealed") {
            val feeId = call.parameters["feeId"] ?: throw Denied(400, "missing feeId")
            val me = call.gate(
                deps,
                Policy(
                    minTier = Tier.STRONG,
                    relation = "can_edit",
                    maxIntegrityAgeSec = 300L,
                    requireHardwareAttestation = true,
                    enforceOverride = true,
                ) { "fee_record:$feeId" },
            )
            val pqc = deps.pqc ?: throw Denied(503, "PQC keyring not configured")
            val body = call.receive<SealedRequest>()
            val plain = pqc.openVerified("fee_edit", "fee_record:$feeId", body.aad, body.sealed)
            deps.audit.log(me.userId, me.deviceId, call.request.uri, "fee_record:$feeId", "ALLOW", me.tier)
            call.respond(mapOf("accepted" to "true", "kid" to body.sealed.kid, "bytes" to plain.size.toString()))
        }

        post("/v1/face-templates/enroll") {
            val me = call.gate(
                deps,
                Policy(
                    minTier = Tier.DEVICE,
                    requireHardwareAttestation = true,
                    enforceOverride = true,
                ),
            )
            val pqc = deps.pqc ?: throw Denied(503, "PQC keyring not configured")
            val body = call.receive<SealedRequest>()
            // Verify PQC envelope opens and authenticates before storing sealed template blob
            pqc.openVerified("face_enroll", "user:${me.userId}", body.aad, body.sealed)
            deps.faceTemplates.upsert(me.userId, body.sealed.kid, Json.encodeToString(body.sealed))
            deps.audit.log(me.userId, me.deviceId, call.request.uri, "face_template:${me.userId}", "ALLOW", me.tier)
            call.respond(mapOf("stored" to "true", "kid" to body.sealed.kid))
        }

        post("/v1/admin/users/{userId}/device-reset") {
            val me = call.gate(
                deps,
                Policy(
                    minTier = Tier.STRONG,
                    relation = "admin",
                    maxIntegrityAgeSec = 300L,
                    requireHardwareAttestation = true,
                    enforceOverride = true,
                ) { "institute:main" },
            )
            val targetUser = call.parameters["userId"] ?: throw Denied(400, "missing userId")
            val resetCount = deps.devices.resetUserDevices(targetUser)
            deps.audit.log(me.userId, me.deviceId, call.request.uri, "user:$targetUser", "ALLOW", me.tier)
            call.respond(mapOf("userId" to targetUser, "revokedCount" to resetCount.toString()))
        }
    }
}

private fun createDataSourceIfConfigured(): DataSource? {
    val dbUrl = System.getenv("SECURITY_DB_URL").orEmpty().trim()
    if (dbUrl.isEmpty()) return null
    val cfg = HikariConfig().apply {
        jdbcUrl = dbUrl
        username = System.getenv("SECURITY_DB_USER") ?: "security_gw"
        password = System.getenv("SECURITY_DB_PASSWORD") ?: ""
        maximumPoolSize = 10
    }
    return HikariDataSource(cfg)
}

fun main() {
    val env: (String) -> String? = { name -> System.getenv(name) }
    val enforce = env("ENFORCE")?.equals("false", ignoreCase = true) != true // Phase 2: enforced by default unless ENFORCE=false
    val port = env("PORT")?.toIntOrNull() ?: 8080
    val publicBase = env("GATEWAY_PUBLIC_URL")?.trimEnd('/') ?: "http://localhost:$port"
    val jwtSecret = (env("JWT_SIGNING_SECRET") ?: "dev-only-change-in-prod-minimum-32-bytes").toByteArray(Charsets.UTF_8)
    val firebaseProjectId = env("FIREBASE_PROJECT_ID") ?: ""
    val playPackageName = env("PLAY_PACKAGE_NAME") ?: "com.aistudio.waqarinstitute.wciapp"

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    val ds = createDataSourceIfConfigured()
    val devices: DeviceRepository = if (ds != null) PostgresDeviceRepository(ds) else InMemoryDeviceRepository()
    val nonces: ReplayNonceStore = if (ds != null) PostgresReplayNonceStore(ds) else InMemoryReplayNonceStore()
    val audit: AuditLogger = if (ds != null) PostgresAuditLogger(ds) else InMemoryAuditLogger()
    val faceTemplates: FaceTemplateRepository =
        if (ds != null) PostgresFaceTemplateRepository(ds) else InMemoryFaceTemplateRepository()

    val keyringKeys = buildList {
        val activeKid = env("PQC_ACTIVE_KID").orEmpty().trim()
        val mlkemB64 = env("PQC_ACTIVE_MLKEM_PRIV_B64").orEmpty().trim()
        val xB64 = env("PQC_ACTIVE_X25519_PRIV_B64").orEmpty().trim()
        if (activeKid.isNotEmpty() && mlkemB64.isNotEmpty() && xB64.isNotEmpty()) {
            val dec = Base64.getDecoder()
            add(HybridKem.RecipientPrivate(activeKid, dec.decode(mlkemB64), dec.decode(xB64)))
        }
        val prevKid = env("PQC_PREV_KID").orEmpty().trim()
        val prevMlkemB64 = env("PQC_PREV_MLKEM_PRIV_B64").orEmpty().trim()
        val prevXB64 = env("PQC_PREV_X25519_PRIV_B64").orEmpty().trim()
        if (prevKid.isNotEmpty() && prevMlkemB64.isNotEmpty() && prevXB64.isNotEmpty()) {
            val dec = Base64.getDecoder()
            add(HybridKem.RecipientPrivate(prevKid, dec.decode(prevMlkemB64), dec.decode(prevXB64)))
        }
    }
    val pqcService = PqcEnvelopeService(PqcKeyring(keyringKeys), nonces)

    val deps = Deps(
        enforce = enforce,
        jwt = HmacGatewayJwtService(jwtSecret),
        upstreamAuth = FirebaseJwksTokenVerifier(firebaseProjectId),
        dpop = DpopVerifier(nonces),
        devices = devices,
        challenges = InMemoryChallengeStore(),
        integrity = IntegrityVerifier(GooglePlayIntegrityDecoder(httpClient, playPackageName), playPackageName),
        risk = RiskEngine(),
        fga = FgaClient(
            http = httpClient,
            base = env("OPENFGA_URL") ?: "http://localhost:8080",
            storeId = env("OPENFGA_STORE_ID") ?: "",
            modelId = env("OPENFGA_MODEL_ID") ?: "",
            apiKey = env("OPENFGA_API_KEY") ?: "",
        ),
        enclave = EnclaveClient(httpClient, env("ENCLAVE_URL") ?: ""),
        audit = audit,
        publicUrl = { call -> "$publicBase${call.request.uri.substringBefore("?")}" },
        pqc = pqcService,
        faceTemplates = faceTemplates,
    )

    embeddedServer(Netty, port = port) {
        securityGatewayModule(deps)
    }.start(wait = true)
}
