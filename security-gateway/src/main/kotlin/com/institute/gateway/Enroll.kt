package com.institute.gateway

import com.nimbusds.jose.jwk.ECKey
import kotlinx.serialization.Serializable
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.ASN1Integer
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

@Serializable
data class ChallengeResponse(
    val nonce: String,
    val expiresInSec: Long = 300L,
)

@Serializable
data class EnrollRequest(
    val idToken: String,
    val nonce: String,
    val certChain: List<String>,
    val jwk: Map<String, String>,
    val osPatchLevel: String? = null,
)

@Serializable
data class EnrollResponse(
    val deviceId: String,
    val attestationLevel: String,
    val pubkeyJkt: String,
)

interface ChallengeStore {
    fun issue(ttlSec: Long = 300L): String
    fun consume(nonce: String): Boolean
}

class InMemoryChallengeStore(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ChallengeStore {
    private val rng = SecureRandom()
    private val entries = ConcurrentHashMap<String, Long>()

    override fun issue(ttlSec: Long): String {
        val raw = ByteArray(32).also(rng::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        entries[nonce] = nowMs() + ttlSec * 1000L
        return nonce
    }

    override fun consume(nonce: String): Boolean {
        val exp = entries.remove(nonce) ?: return false
        return nowMs() <= exp
    }
}

interface DeviceRepository {
    fun find(deviceId: String): EnrolledDevice?
    fun findByThumbprint(pubkeyJkt: String): EnrolledDevice?
    fun upsert(device: EnrolledDevice): EnrolledDevice
    fun touchLastSeen(deviceId: String, at: Instant = Instant.now())
    fun revoke(deviceId: String, at: Instant = Instant.now()): Boolean
    fun resetUserDevices(userId: String, at: Instant = Instant.now()): Int
}

class InMemoryDeviceRepository : DeviceRepository {
    private val byId = ConcurrentHashMap<String, EnrolledDevice>()

    override fun find(deviceId: String): EnrolledDevice? = byId[deviceId]

    override fun findByThumbprint(pubkeyJkt: String): EnrolledDevice? =
        byId.values.firstOrNull { it.pubkeyJkt == pubkeyJkt }

    override fun upsert(device: EnrolledDevice): EnrolledDevice {
        val existing = findByThumbprint(device.pubkeyJkt)
        val stored = if (existing != null) {
            device.copy(id = existing.id, enrolledAt = existing.enrolledAt, revokedAt = existing.revokedAt)
        } else {
            device
        }
        byId[stored.id] = stored
        return stored
    }

    override fun touchLastSeen(deviceId: String, at: Instant) {
        byId.computeIfPresent(deviceId) { _, d -> d.copy(lastSeen = at) }
    }

    override fun revoke(deviceId: String, at: Instant): Boolean {
        val updated = byId.computeIfPresent(deviceId) { _, d -> d.copy(revokedAt = at) }
        return updated != null
    }

    override fun resetUserDevices(userId: String, at: Instant): Int {
        var count = 0
        byId.keys.forEach { key ->
            byId.computeIfPresent(key) { _, d ->
                if (d.userId == userId && d.revokedAt == null) {
                    count++
                    d.copy(revokedAt = at)
                } else {
                    d
                }
            }
        }
        return count
    }
}

class PostgresDeviceRepository(private val ds: DataSource) : DeviceRepository {
    override fun find(deviceId: String): EnrolledDevice? {
        val uuid = runCatching { UUID.fromString(deviceId) }.getOrNull() ?: return null
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, pubkey_jkt, pubkey_jwk::text, attestation_lvl, os_patch_level,
                       enrolled_at, last_seen, revoked_at
                FROM devices WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, uuid)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return EnrolledDevice(
                        id = rs.getObject(1).toString(),
                        userId = rs.getString(2),
                        pubkeyJkt = rs.getString(3),
                        pubkeyJwkJson = rs.getString(4),
                        attestationLevel = AttestationLevel.valueOf(rs.getString(5)),
                        osPatchLevel = rs.getString(6),
                        enrolledAt = rs.getTimestamp(7).toInstant(),
                        lastSeen = rs.getTimestamp(8)?.toInstant(),
                        revokedAt = rs.getTimestamp(9)?.toInstant(),
                    )
                }
            }
        }
    }

    override fun findByThumbprint(pubkeyJkt: String): EnrolledDevice? {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, pubkey_jkt, pubkey_jwk::text, attestation_lvl, os_patch_level,
                       enrolled_at, last_seen, revoked_at
                FROM devices WHERE pubkey_jkt = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, pubkeyJkt)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return EnrolledDevice(
                        id = rs.getObject(1).toString(),
                        userId = rs.getString(2),
                        pubkeyJkt = rs.getString(3),
                        pubkeyJwkJson = rs.getString(4),
                        attestationLevel = AttestationLevel.valueOf(rs.getString(5)),
                        osPatchLevel = rs.getString(6),
                        enrolledAt = rs.getTimestamp(7).toInstant(),
                        lastSeen = rs.getTimestamp(8)?.toInstant(),
                        revokedAt = rs.getTimestamp(9)?.toInstant(),
                    )
                }
            }
        }
    }

    override fun upsert(device: EnrolledDevice): EnrolledDevice {
        val uuid = UUID.fromString(device.id)
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO devices (id, user_id, pubkey_jkt, pubkey_jwk, attestation_lvl, os_patch_level, enrolled_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, now())
                ON CONFLICT (pubkey_jkt) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    pubkey_jwk = EXCLUDED.pubkey_jwk,
                    attestation_lvl = EXCLUDED.attestation_lvl,
                    os_patch_level = EXCLUDED.os_patch_level,
                    last_seen = now()
                RETURNING id, enrolled_at, revoked_at
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, uuid)
                ps.setString(2, device.userId)
                ps.setString(3, device.pubkeyJkt)
                ps.setString(4, device.pubkeyJwkJson)
                ps.setString(5, device.attestationLevel.name)
                ps.setString(6, device.osPatchLevel)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return device.copy(
                        id = rs.getObject(1).toString(),
                        enrolledAt = rs.getTimestamp(2).toInstant(),
                        revokedAt = rs.getTimestamp(3)?.toInstant(),
                    )
                }
            }
        }
    }

    override fun touchLastSeen(deviceId: String, at: Instant) {
        val uuid = runCatching { UUID.fromString(deviceId) }.getOrNull() ?: return
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE devices SET last_seen = ? WHERE id = ?").use { ps ->
                ps.setTimestamp(1, java.sql.Timestamp.from(at))
                ps.setObject(2, uuid)
                ps.executeUpdate()
            }
        }
    }

    override fun revoke(deviceId: String, at: Instant): Boolean {
        val uuid = runCatching { UUID.fromString(deviceId) }.getOrNull() ?: return false
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE devices SET revoked_at = ? WHERE id = ?").use { ps ->
                ps.setTimestamp(1, java.sql.Timestamp.from(at))
                ps.setObject(2, uuid)
                return ps.executeUpdate() > 0
            }
        }
    }

    override fun resetUserDevices(userId: String, at: Instant): Int {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE devices SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
            ).use { ps ->
                ps.setTimestamp(1, java.sql.Timestamp.from(at))
                ps.setString(2, userId)
                return ps.executeUpdate()
            }
        }
    }
}

data class VerifiedAttestation(
    val level: AttestationLevel,
    val publicKey: ECPublicKey,
    val osPatchLevel: Int?,
)

object AttestationVerifier {
    private const val ANDROID_KEY_ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"

    // Google Hardware Attestation Root CAs (RSA-4096 & EC P-384 roots, SHA-256 of DER-encoded SubjectPublicKeyInfo)
    val GOOGLE_ATTESTATION_ROOT_SPKI_SHA256 = setOf(
        // Can be overridden/augmented via trustedRootSpkiSha256 for staging or unit tests
        "f8d38d1680747d3d4a3c3568a04b4a796e2a17315ef89d1305c92b0c6e5538bb",
        "1f2f4183892b6c28981f6215c2452f0043862f2626575c9f159e5086a3eb7f9a",
    )

    fun verify(
        certChainBase64: List<String>,
        expectedChallenge: ByteArray,
        submittedJwk: Map<String, String>,
        trustedRootSpkiSha256: Set<String> = emptySet(),
    ): VerifiedAttestation {
        if (certChainBase64.isEmpty()) throw Denied(400, "empty certificate chain")

        val cf = CertificateFactory.getInstance("X.509")
        val certs = certChainBase64.map { b64 ->
            val der = runCatching { Base64.getDecoder().decode(b64) }.getOrElse { throw Denied(400, "bad cert base64") }
            cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        // Verify signature chain from leaf up to root
        for (i in 0 until certs.size - 1) {
            runCatching { certs[i].verify(certs[i + 1].publicKey) }
                .getOrElse { throw Denied(403, "certificate chain signature invalid at index $i") }
        }
        val root = certs.last()
        runCatching { root.verify(root.publicKey) }
            .getOrElse { throw Denied(403, "root certificate self-signature invalid") }

        if (trustedRootSpkiSha256.isNotEmpty()) {
            val spkiHex = MessageDigest.getInstance("SHA-256")
                .digest(root.publicKey.encoded)
                .joinToString("") { "%02x".format(it) }
            if (spkiHex !in trustedRootSpkiSha256) {
                throw Denied(403, "untrusted attestation root")
            }
        }

        val leaf = certs.first()
        val leafPub = leaf.publicKey as? ECPublicKey
            ?: throw Denied(400, "leaf certificate key is not EC P-256")

        // Verify leaf public key matches the DPoP JWK
        val submittedEc = runCatching { ECKey.parse(submittedJwk) }.getOrElse { throw Denied(400, "invalid DPoP JWK") }
        val submittedPub = submittedEc.toECPublicKey()
        if (leafPub.w != submittedPub.w) {
            throw Denied(403, "attested key mismatch with DPoP JWK")
        }

        val extBytes = leaf.getExtensionValue(ANDROID_KEY_ATTESTATION_OID)
            ?: throw Denied(403, "missing Android key attestation extension")

        val octets = ASN1InputStream(extBytes).use { (it.readObject() as ASN1OctetString).octets }
        val keyDesc = ASN1InputStream(octets).use { it.readObject() as ASN1Sequence }
        if (keyDesc.size() < 8) throw Denied(403, "malformed KeyDescription")

        val attSecLevel = (keyDesc.getObjectAt(1) as ASN1Enumerated).value.toInt()
        val challenge = (keyDesc.getObjectAt(4) as ASN1OctetString).octets
        if (!challenge.contentEquals(expectedChallenge)) {
            throw Denied(403, "attestationChallenge mismatch")
        }

        val level = when (attSecLevel) {
            2 -> AttestationLevel.STRONGBOX
            1 -> AttestationLevel.TRUSTED_ENVIRONMENT
            else -> AttestationLevel.SOFTWARE
        }

        // Parse optional osPatchLevel (tag 706) from teeEnforced (idx 7) or softwareEnforced (idx 6)
        val patchLevel = extractPatchLevel(keyDesc.getObjectAt(7) as ASN1Sequence)
            ?: extractPatchLevel(keyDesc.getObjectAt(6) as ASN1Sequence)

        return VerifiedAttestation(level = level, publicKey = leafPub, osPatchLevel = patchLevel)
    }

    private fun extractPatchLevel(authList: ASN1Sequence): Int? {
        for (item in authList) {
            val tagged = item as? ASN1TaggedObject ?: continue
            if (tagged.tagNo == 706) {
                val integer = tagged.baseObject as? ASN1Integer ?: continue
                return integer.value.toInt()
            }
        }
        return null
    }
}
