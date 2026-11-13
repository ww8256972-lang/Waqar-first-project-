package com.institute.gateway

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class DecodeIntegrityRequest(
    val integrity_token: String,
)

@Serializable
data class RequestDetailsPayload(
    val requestHash: String? = null,
    val timestampMillis: String? = null,
)

@Serializable
data class AppIntegrityPayload(
    val appRecognitionVerdict: String? = null,
    val packageName: String? = null,
)

@Serializable
data class DeviceIntegrityPayload(
    val deviceRecognitionVerdict: List<String> = emptyList(),
)

@Serializable
data class TokenPayloadExternal(
    val requestDetails: RequestDetailsPayload? = null,
    val appIntegrity: AppIntegrityPayload? = null,
    val deviceIntegrity: DeviceIntegrityPayload? = null,
)

@Serializable
data class DecodeIntegrityResponse(
    val tokenPayloadExternal: TokenPayloadExternal? = null,
)

interface PlayIntegrityDecoder {
    suspend fun decode(integrityToken: String): TokenPayloadExternal?
}

class GooglePlayIntegrityDecoder(
    private val http: HttpClient,
    private val packageName: String,
    private val credentialsProvider: () -> GoogleCredentials? = {
        runCatching {
            GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/playintegrity")
        }.getOrNull()
    },
) : PlayIntegrityDecoder {
    override suspend fun decode(integrityToken: String): TokenPayloadExternal? {
        if (packageName.isBlank() || integrityToken.isBlank()) return null
        val creds = credentialsProvider() ?: return null
        val accessToken = runCatching {
            creds.refreshIfExpired()
            creds.accessToken?.tokenValue
        }.getOrNull() ?: return null

        return runCatching {
            val resp = http.post("https://playintegrity.googleapis.com/v1/$packageName:decodeIntegrityToken") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(DecodeIntegrityRequest(integrityToken))
            }
            if (!resp.status.isSuccess()) return null
            resp.body<DecodeIntegrityResponse>().tokenPayloadExternal
        }.getOrNull()
    }
}

class IntegrityVerifier(
    private val decoder: PlayIntegrityDecoder,
    private val expectedPackageName: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Evaluates Play Integrity verdict + device hardware attestation level into an ordered [Tier].
     * Fails closed to [Tier.UNTRUSTED] if the integrity token is missing, malformed, expired,
     * unrecognized by Play, or bound to the wrong package.
     */
    suspend fun tierFor(
        device: EnrolledDevice,
        integrityTokenHeader: String?,
        maxIntegrityAgeSec: Long,
        expectedRequestHash: String? = null,
    ): Tier {
        if (integrityTokenHeader.isNullOrBlank()) {
            return Tier.UNTRUSTED
        }

        val payload = decoder.decode(integrityTokenHeader) ?: return Tier.UNTRUSTED
        val reqDetails = payload.requestDetails ?: return Tier.UNTRUSTED

        val tsMillis = reqDetails.timestampMillis?.toLongOrNull() ?: return Tier.UNTRUSTED
        val ageSec = (nowMs() - tsMillis) / 1000L
        if (ageSec < -60L || ageSec > maxIntegrityAgeSec) {
            return Tier.UNTRUSTED
        }

        if (!expectedRequestHash.isNullOrBlank() && reqDetails.requestHash != expectedRequestHash) {
            return Tier.UNTRUSTED
        }

        val appVerdict = payload.appIntegrity?.appRecognitionVerdict
        if (appVerdict != "PLAY_RECOGNIZED") {
            return Tier.UNTRUSTED
        }
        val tokenPkg = payload.appIntegrity.packageName
        if (!tokenPkg.isNullOrBlank() && expectedPackageName.isNotBlank() && tokenPkg != expectedPackageName) {
            return Tier.UNTRUSTED
        }

        val verdicts = payload.deviceIntegrity?.deviceRecognitionVerdict.orEmpty().toSet()
        val playTier = when {
            "MEETS_STRONG_INTEGRITY" in verdicts -> Tier.STRONG
            "MEETS_DEVICE_INTEGRITY" in verdicts -> Tier.DEVICE
            "MEETS_BASIC_INTEGRITY" in verdicts -> Tier.BASIC
            else -> Tier.UNTRUSTED
        }

        // Hardware attestation floor: STRONG requires hardware-backed (TEE or StrongBox) enrollment
        return if (playTier == Tier.STRONG &&
            device.attestationLevel != AttestationLevel.STRONGBOX &&
            device.attestationLevel != AttestationLevel.TRUSTED_ENVIRONMENT
        ) {
            Tier.DEVICE
        } else {
            playTier
        }
    }

    fun fallbackDeviceBaseline(device: EnrolledDevice): Tier = when (device.attestationLevel) {
        AttestationLevel.STRONGBOX, AttestationLevel.TRUSTED_ENVIRONMENT -> Tier.BASIC
        AttestationLevel.SOFTWARE -> Tier.UNTRUSTED
    }
}

enum class AttestationLevel {
    SOFTWARE,
    TRUSTED_ENVIRONMENT,
    STRONGBOX,
}

data class EnrolledDevice(
    val id: String,
    val userId: String,
    val pubkeyJkt: String,
    val pubkeyJwkJson: String,
    val attestationLevel: AttestationLevel,
    val osPatchLevel: String?,
    val enrolledAt: Instant,
    val lastSeen: Instant?,
    val revokedAt: Instant?,
)
