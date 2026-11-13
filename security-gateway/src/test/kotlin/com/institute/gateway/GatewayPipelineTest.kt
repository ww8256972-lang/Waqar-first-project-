package com.institute.gateway

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import com.institute.crypto.HybridKem
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.uri
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayPipelineTest {

    private fun testDeps(
        enforce: Boolean,
        fgaAllowed: Boolean = true,
        integrityTierVerdict: String = "MEETS_DEVICE_INTEGRITY",
        audit: InMemoryAuditLogger = InMemoryAuditLogger(),
        devices: InMemoryDeviceRepository = InMemoryDeviceRepository(),
    ): Deps {
        val jwt = HmacGatewayJwtService("unit-test-secret-key-minimum-32-bytes-long".toByteArray())
        val decoder = object : PlayIntegrityDecoder {
            override suspend fun decode(integrityToken: String): TokenPayloadExternal {
                return TokenPayloadExternal(
                    requestDetails = RequestDetailsPayload(
                        requestHash = "hash",
                        timestampMillis = System.currentTimeMillis().toString(),
                    ),
                    appIntegrity = AppIntegrityPayload(
                        appRecognitionVerdict = "PLAY_RECOGNIZED",
                        packageName = "com.aistudio.waqarinstitute.wciapp",
                    ),
                    deviceIntegrity = DeviceIntegrityPayload(
                        deviceRecognitionVerdict = listOf(integrityTierVerdict),
                    ),
                )
            }
        }
        return Deps(
            enforce = enforce,
            jwt = jwt,
            upstreamAuth = object : UpstreamTokenVerifier {
                override fun verifyUserId(idToken: String): String = "user_rahul"
            },
            dpop = DpopVerifier(InMemoryReplayNonceStore()),
            devices = devices,
            challenges = InMemoryChallengeStore(),
            integrity = IntegrityVerifier(decoder, "com.aistudio.waqarinstitute.wciapp"),
            risk = RiskEngine(),
            fga = object : AuthorizationChecker {
                override suspend fun check(user: String, relation: String, obj: String): Boolean = fgaAllowed
            },
            enclave = object : EnclaveVerifier {
                override suspend fun verifyFace(req: FaceVerifyRequest, principal: Principal): FaceVerifyResponse =
                    FaceVerifyResponse(match = true, score = 0.91f)
            },
            audit = audit,
            publicUrl = { call -> "http://localhost${call.request.uri.substringBefore("?")}" },
            trustedAttestationRoots = emptySet(),
        )
    }

    private fun dpopProof(
        ecKey: com.nimbusds.jose.jwk.ECKey,
        method: String,
        url: String,
        accessToken: String?,
        jti: String = UUID.randomUUID().toString(),
    ): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("dpop+jwt"))
            .jwk(ecKey.toPublicJWK())
            .build()
        val claimsBuilder = JWTClaimsSet.Builder()
            .claim("htm", method)
            .claim("htu", url)
            .issueTime(Date())
            .jwtID(jti)
        if (!accessToken.isNullOrBlank()) {
            val ath = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.UTF_8)),
            )
            claimsBuilder.claim("ath", ath)
        }
        return SignedJWT(header, claimsBuilder.build()).apply { sign(ECDSASigner(ecKey)) }.serialize()
    }

    @Test
    fun shadowMode_logsWouldDenyWithoutBlockingNonEnforcedRoute() = testApplication {
        val audit = InMemoryAuditLogger()
        val deps = testDeps(enforce = false, audit = audit)
        application { securityGatewayModule(deps) }

        val resp = client.get("/v1/courses/cpp101/notes")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(1, audit.entries.size)
        assertEquals("WOULD_DENY", audit.entries.first().decision)

        // Phase 2: fee routes ALWAYS enforce even when global flag is shadow
        val feeResp = client.get("/v1/fees/rahul_nov")
        assertEquals(HttpStatusCode.Unauthorized, feeResp.status)
    }

    @Test
    fun phase2_attendanceHardwareAttestation_feeEditStrongTier_andDeviceReset() = testApplication {
        val audit = InMemoryAuditLogger()
        val devices = InMemoryDeviceRepository()
        val devKey = ECKeyGenerator(Curve.P_256).generate()
        val jkt = devKey.computeThumbprint().toString()
        val swDeviceId = UUID.randomUUID().toString()
        val teeDeviceId = UUID.randomUUID().toString()

        devices.upsert(
            EnrolledDevice(
                id = swDeviceId,
                userId = "teacher_01",
                pubkeyJkt = jkt,
                pubkeyJwkJson = devKey.toPublicJWK().toJSONString(),
                attestationLevel = AttestationLevel.SOFTWARE,
                osPatchLevel = "2026-10-05",
                enrolledAt = Instant.now(),
                lastSeen = Instant.now(),
                revokedAt = null,
            ),
        )
        val teeKey = ECKeyGenerator(Curve.P_256).generate()
        val teeJkt = teeKey.computeThumbprint().toString()
        devices.upsert(
            EnrolledDevice(
                id = teeDeviceId,
                userId = "anita",
                pubkeyJkt = teeJkt,
                pubkeyJwkJson = teeKey.toPublicJWK().toJSONString(),
                attestationLevel = AttestationLevel.TRUSTED_ENVIRONMENT,
                osPatchLevel = "2026-10-05",
                enrolledAt = Instant.now(),
                lastSeen = Instant.now(),
                revokedAt = null,
            ),
        )

        var tokenTimestampMs = System.currentTimeMillis()
        var activeVerdict = "MEETS_DEVICE_INTEGRITY"
        val jwt = HmacGatewayJwtService("unit-test-secret-key-minimum-32-bytes-long".toByteArray())
        val fga = object : AuthorizationChecker {
            override suspend fun check(user: String, relation: String, obj: String): Boolean {
                return when {
                    user == "user:teacher_01" && relation == "can_mark_attendance" && obj == "batch:cpp2026" -> true
                    user == "user:anita" && relation == "can_edit" && obj == "fee_record:rahul_nov" -> true
                    user == "user:anita" && relation == "admin" && obj == "institute:main" -> true
                    else -> false
                }
            }
        }
        val decoder = object : PlayIntegrityDecoder {
            override suspend fun decode(integrityToken: String): TokenPayloadExternal {
                return TokenPayloadExternal(
                    requestDetails = RequestDetailsPayload("hash", tokenTimestampMs.toString()),
                    appIntegrity = AppIntegrityPayload("PLAY_RECOGNIZED", "com.aistudio.waqarinstitute.wciapp"),
                    deviceIntegrity = DeviceIntegrityPayload(listOf(activeVerdict)),
                )
            }
        }
        val deps = Deps(
            enforce = true,
            jwt = jwt,
            upstreamAuth = object : UpstreamTokenVerifier {
                override fun verifyUserId(idToken: String): String = "anita"
            },
            dpop = DpopVerifier(InMemoryReplayNonceStore()),
            devices = devices,
            challenges = InMemoryChallengeStore(),
            integrity = IntegrityVerifier(decoder, "com.aistudio.waqarinstitute.wciapp"),
            risk = RiskEngine(),
            fga = fga,
            enclave = object : EnclaveVerifier {
                override suspend fun verifyFace(req: FaceVerifyRequest, principal: Principal): FaceVerifyResponse =
                    FaceVerifyResponse(match = true, score = 0.91f)
            },
            audit = audit,
            publicUrl = { call -> "http://localhost${call.request.uri.substringBefore("?")}" },
            trustedAttestationRoots = emptySet(),
        )

        application { securityGatewayModule(deps) }

        val (teacherJwt, _) = jwt.issue("teacher_01", swDeviceId, jkt)
        val (adminJwt, _) = jwt.issue("anita", teeDeviceId, teeJkt)

        // 1. Software-only attestation device is denied on attendance route
        val swResp = client.post("/v1/attendance/cpp2026/mark") {
            header("Authorization", "DPoP $teacherJwt")
            header("DPoP", dpopProof(devKey, "POST", "http://localhost/v1/attendance/cpp2026/mark", teacherJwt))
            header("X-Integrity", "tok")
        }
        assertEquals(HttpStatusCode.Forbidden, swResp.status)

        // 2. Fee edit requires STRONG integrity; DEVICE tier is rejected (403)
        val lowTierFeeEdit = client.post("/v1/fees/rahul_nov") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(teeKey, "POST", "http://localhost/v1/fees/rahul_nov", adminJwt))
            header("X-Integrity", "tok")
        }
        assertEquals(HttpStatusCode.Forbidden, lowTierFeeEdit.status)

        // 3. Stale integrity token (>5 min old) on STRONG route is rejected (403)
        activeVerdict = "MEETS_STRONG_INTEGRITY"
        tokenTimestampMs = System.currentTimeMillis() - 400_000L
        val staleTokenFeeEdit = client.post("/v1/fees/rahul_nov") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(teeKey, "POST", "http://localhost/v1/fees/rahul_nov", adminJwt))
            header("X-Integrity", "tok")
        }
        assertEquals(HttpStatusCode.Forbidden, staleTokenFeeEdit.status)

        // 4. Fresh STRONG integrity succeeds on fee edit (200)
        tokenTimestampMs = System.currentTimeMillis()
        val okFeeEdit = client.post("/v1/fees/rahul_nov") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(teeKey, "POST", "http://localhost/v1/fees/rahul_nov", adminJwt))
            header("X-Integrity", "tok")
        }
        assertEquals(HttpStatusCode.OK, okFeeEdit.status)

        // 5. Support device reset revokes all active devices for teacher_01
        val resetResp = client.post("/v1/admin/users/teacher_01/device-reset") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(teeKey, "POST", "http://localhost/v1/admin/users/teacher_01/device-reset", adminJwt))
            header("X-Integrity", "tok")
        }
        assertEquals(HttpStatusCode.OK, resetResp.status)
        assertEquals(true, devices.find(swDeviceId)?.revokedAt != null)
    }

    @Test
    fun enforcedMode_allowsValidDpopDeviceAndRejectsReplayOrRevokedOrWrongKey() = testApplication {
        val audit = InMemoryAuditLogger()
        val devices = InMemoryDeviceRepository()
        val deps = testDeps(enforce = true, fgaAllowed = true, audit = audit, devices = devices)

        val devKey = ECKeyGenerator(Curve.P_256).generate()
        val otherKey = ECKeyGenerator(Curve.P_256).generate()
        val jkt = devKey.computeThumbprint().toString()
        val deviceId = UUID.randomUUID().toString()

        devices.upsert(
            EnrolledDevice(
                id = deviceId,
                userId = "rahul",
                pubkeyJkt = jkt,
                pubkeyJwkJson = devKey.toPublicJWK().toJSONString(),
                attestationLevel = AttestationLevel.TRUSTED_ENVIRONMENT,
                osPatchLevel = "2026-10-05",
                enrolledAt = Instant.now(),
                lastSeen = Instant.now(),
                revokedAt = null,
            ),
        )
        val (accessToken, _) = deps.jwt.issue("rahul", deviceId, jkt)

        application { securityGatewayModule(deps) }

        // 1. Valid request succeeds
        val jti = "unique-jti-1"
        val url = "http://localhost/v1/fees/rahul_nov"
        val validProof = dpopProof(devKey, "GET", url, accessToken, jti = jti)
        val okResp = client.get("/v1/fees/rahul_nov") {
            header("Authorization", "DPoP $accessToken")
            header("DPoP", validProof)
            header("X-Integrity", "valid-integrity-token")
        }
        assertEquals(HttpStatusCode.OK, okResp.status)
        assertEquals("ALLOW", audit.entries.last().decision)

        // 2. Replaying same jti fails closed with 401
        val replayResp = client.get("/v1/fees/rahul_nov") {
            header("Authorization", "DPoP $accessToken")
            header("DPoP", validProof)
            header("X-Integrity", "valid-integrity-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, replayResp.status)

        // 3. Stolen JWT paired with wrong DPoP key fails closed with 401
        val wrongKeyProof = dpopProof(otherKey, "GET", url, accessToken)
        val wrongKeyResp = client.get("/v1/fees/rahul_nov") {
            header("Authorization", "DPoP $accessToken")
            header("DPoP", wrongKeyProof)
            header("X-Integrity", "valid-integrity-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongKeyResp.status)

        // 4. Revoked device is immediately denied with 403
        devices.revoke(deviceId)
        val revokedProof = dpopProof(devKey, "GET", url, accessToken)
        val revokedResp = client.get("/v1/fees/rahul_nov") {
            header("Authorization", "DPoP $accessToken")
            header("DPoP", revokedProof)
            header("X-Integrity", "valid-integrity-token")
        }
        assertEquals(HttpStatusCode.Forbidden, revokedResp.status)
    }

    @Test
    fun fgaClient_failsClosedWhenOpenFgaIsDown() = kotlinx.coroutines.runBlocking {
        val failingHttp = HttpClient(MockEngine) {
            install(ClientJson) { json(Json { ignoreUnknownKeys = true }) }
            engine {
                addHandler { respondError(HttpStatusCode.ServiceUnavailable) }
            }
        }
        val fga = FgaClient(failingHttp, "http://openfga:8080", "store-1", "model-1", "key")
        val allowed = fga.check("user:teacher_01", "can_mark_attendance", "batch:cpp2026")
        assertEquals(false, allowed)
    }

    @Test
    fun phase3_pqcSealedPayload_rotationReplayAndAadTamper() = testApplication {
        val (pubPrev, privPrev) = HybridKem.generateRecipient("k-2026-08")
        val (pubActive, privActive) = HybridKem.generateRecipient("k-2026-11")
        val nonces = InMemoryReplayNonceStore()
        val pqcService = PqcEnvelopeService(PqcKeyring(listOf(privActive, privPrev)), nonces)
        val faceTemplates = InMemoryFaceTemplateRepository()
        val devices = InMemoryDeviceRepository()
        val audit = InMemoryAuditLogger()

        val adminKey = ECKeyGenerator(Curve.P_256).generate()
        val adminJkt = adminKey.computeThumbprint().toString()
        val adminDevId = UUID.randomUUID().toString()
        devices.upsert(
            EnrolledDevice(
                id = adminDevId,
                userId = "anita",
                pubkeyJkt = adminJkt,
                pubkeyJwkJson = adminKey.toPublicJWK().toJSONString(),
                attestationLevel = AttestationLevel.STRONGBOX,
                osPatchLevel = "2026-10-05",
                enrolledAt = Instant.now(),
                lastSeen = Instant.now(),
                revokedAt = null,
            ),
        )

        val jwt = HmacGatewayJwtService("unit-test-secret-key-minimum-32-bytes-long".toByteArray())
        val decoder = object : PlayIntegrityDecoder {
            override suspend fun decode(integrityToken: String): TokenPayloadExternal = TokenPayloadExternal(
                requestDetails = RequestDetailsPayload("hash", System.currentTimeMillis().toString()),
                appIntegrity = AppIntegrityPayload("PLAY_RECOGNIZED", "com.aistudio.waqarinstitute.wciapp"),
                deviceIntegrity = DeviceIntegrityPayload(listOf("MEETS_STRONG_INTEGRITY")),
            )
        }
        val deps = Deps(
            enforce = true,
            jwt = jwt,
            upstreamAuth = object : UpstreamTokenVerifier {
                override fun verifyUserId(idToken: String): String = "anita"
            },
            dpop = DpopVerifier(nonces),
            devices = devices,
            challenges = InMemoryChallengeStore(),
            integrity = IntegrityVerifier(decoder, "com.aistudio.waqarinstitute.wciapp"),
            risk = RiskEngine(),
            fga = object : AuthorizationChecker {
                override suspend fun check(user: String, relation: String, obj: String): Boolean =
                    user == "user:anita" && relation == "can_edit" && obj == "fee_record:rahul_nov"
            },
            enclave = object : EnclaveVerifier {
                override suspend fun verifyFace(req: FaceVerifyRequest, principal: Principal): FaceVerifyResponse =
                    FaceVerifyResponse(match = false, score = 0f)
            },
            audit = audit,
            publicUrl = { call -> "http://localhost${call.request.uri.substringBefore("?")}" },
            trustedAttestationRoots = emptySet(),
            pqc = pqcService,
            faceTemplates = faceTemplates,
        )

        application { securityGatewayModule(deps) }

        val (adminJwt, _) = jwt.issue("anita", adminDevId, adminJkt)
        val nowSec = System.currentTimeMillis() / 1000L
        val b64 = Base64.getEncoder()

        fun buildReq(action: String, obj: String, ts: Long, nonce: String, pub: HybridKem.RecipientPublic): SealedRequest {
            val aad = "$action|$obj|$ts|$nonce"
            val s = HybridKem.seal("""{"fee":5000}""".toByteArray(), aad.toByteArray(), pub)
            return SealedRequest(
                aad = aad,
                sealed = SealedEnvelopeDto(
                    kid = s.kid,
                    kemCt = b64.encodeToString(s.kemCt),
                    eph = b64.encodeToString(s.ephX25519),
                    nonce = b64.encodeToString(s.nonce),
                    ct = b64.encodeToString(s.ct),
                ),
            )
        }

        val url = "http://localhost/v1/fees/rahul_nov/sealed"

        // 1. Active key succeeds (200)
        val activeReq = buildReq("fee_edit", "fee_record:rahul_nov", nowSec, "pqc-nonce-1", pubActive)
        val okActive = client.post("/v1/fees/rahul_nov/sealed") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(adminKey, "POST", url, adminJwt))
            header("X-Integrity", "tok")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(activeReq))
        }
        assertEquals(HttpStatusCode.OK, okActive.status)

        // 2. Rotated previous key also succeeds within 2-key window (200)
        val prevReq = buildReq("fee_edit", "fee_record:rahul_nov", nowSec, "pqc-nonce-2", pubPrev)
        val okPrev = client.post("/v1/fees/rahul_nov/sealed") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(adminKey, "POST", url, adminJwt))
            header("X-Integrity", "tok")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(prevReq))
        }
        assertEquals(HttpStatusCode.OK, okPrev.status)

        // 3. Replaying same PQC nonce fails with 401
        val replayResp = client.post("/v1/fees/rahul_nov/sealed") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(adminKey, "POST", url, adminJwt))
            header("X-Integrity", "tok")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(activeReq))
        }
        assertEquals(HttpStatusCode.Unauthorized, replayResp.status)

        // 4. Tampering 1 character in sealed ciphertext fails AEAD check (403)
        val good = buildReq("fee_edit", "fee_record:rahul_nov", nowSec, "pqc-nonce-3", pubActive)
        val tamperedCt = Base64.getDecoder().decode(good.sealed.ct).apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val tamperedReq = good.copy(sealed = good.sealed.copy(ct = b64.encodeToString(tamperedCt)))
        val tamperResp = client.post("/v1/fees/rahul_nov/sealed") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(adminKey, "POST", url, adminJwt))
            header("X-Integrity", "tok")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(tamperedReq))
        }
        assertEquals(HttpStatusCode.Forbidden, tamperResp.status)

        // 5. Expired AAD timestamp (>10 min old) fails (401)
        val staleReq = buildReq("fee_edit", "fee_record:rahul_nov", nowSec - 900L, "pqc-nonce-4", pubActive)
        val staleResp = client.post("/v1/fees/rahul_nov/sealed") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(adminKey, "POST", url, adminJwt))
            header("X-Integrity", "tok")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(staleReq))
        }
        assertEquals(HttpStatusCode.Unauthorized, staleResp.status)

        // 6. Face template enrollment stores sealed blob under userId
        val faceReq = buildReq("face_enroll", "user:anita", nowSec, "pqc-nonce-5", pubActive)
        val faceResp = client.post("/v1/face-templates/enroll") {
            header("Authorization", "DPoP $adminJwt")
            header("DPoP", dpopProof(adminKey, "POST", "http://localhost/v1/face-templates/enroll", adminJwt))
            header("X-Integrity", "tok")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(faceReq))
        }
        assertEquals(HttpStatusCode.OK, faceResp.status)
        assertEquals("k-2026-11", faceTemplates.find("anita")?.first)
    }
}
