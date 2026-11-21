package math_brahmastra_native

import android.content.Context
import java.security.SecureRandom

/**
 * 6. `math_brahmastra_native/MathShieldConnector.kt`
 * Single Non-Intrusive Bridge Facade for all 5 Pure-Mathematics Security Engines:
 * - `SiliconPufEngine` (Android Keystore StrongBox/TEE hardware attestation + timing jitter)
 * - `LorenzChaosCipher` (Google Tink AES-256-GCM AEAD + Lorenz seed AAD)
 * - `ShamirMemoryVault` (GF(2^521-1) 3-of-5 Shamir Secret Sharing + zeroization)
 * - `ZkpSchnorrAuth` (RFC 3526 1536-bit Fiat-Shamir Schnorr ZKP)
 * - `TremorFftSentinel` (Live `Sensor.TYPE_ACCELEROMETER` Radix-2 FFT — never synthetic `sin()`)
 */
object MathShieldConnector {

    data class MathBrahmastraState(
        val siliconFingerprintHex: String,
        val siliconVerified: Boolean,
        val shamirShareCount: Int,
        val tremorHumanVerified: Boolean
    )

    data class MathShieldSignedEnvelope(
        val originalPayload: String,
        val chaoticCiphertextBase64: String,
        val lorenzSeed: LorenzChaosCipher.LorenzSeedState,
        val shamirHmacSignatureHex: String,
        val zkpProof: ZkpSchnorrAuth.SchnorrZeroKnowledgeProof,
        val siliconFingerprintHex: String
    )

    @Volatile
    private var activeShamirShares: List<ShamirMemoryVault.SecretShare> = emptyList()
    @Volatile
    private var cachedSiliconHashHex: String = ""
    @Volatile
    private var lastLiveTremorVerified: Boolean = false

    /**
     * Initializes the Keystore-anchored Silicon PUF check, splits a fresh 256-bit session secret
     * into 5 Shamir polynomial shares in RAM (threshold = 3), and checks ONLY real buffered
     * hardware accelerometer samples in `TremorFftSentinel` (returns `false` until live sensor data arrives).
     */
    fun armMathBrahmastra(context: Context? = null): MathBrahmastraState {
        if (context != null) {
            LorenzChaosCipher.bindContext(context)
        }
        val puf = SiliconPufEngine.verifySiliconIntegrity()
        cachedSiliconHashHex = puf.siliconFingerprint256Hex

        val sessionSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val shares = ShamirMemoryVault.splitSecretIntoShares(
            secret = sessionSecret,
            totalShares = 5,
            threshold = 3
        )
        sessionSecret.fill(0.toByte())
        activeShamirShares = shares

        val liveTremorReport = TremorFftSentinel.analyzeLatestLiveWindow()
        val tremorOk = liveTremorReport?.isGenuineHumanHand == true
        lastLiveTremorVerified = tremorOk

        return MathBrahmastraState(
            siliconFingerprintHex = puf.siliconFingerprint256Hex,
            siliconVerified = puf.isGenuineSilicon,
            shamirShareCount = shares.size,
            tremorHumanVerified = tremorOk
        )
    }

    /**
     * Feeds a real live batch of `Sensor.TYPE_ACCELEROMETER` magnitude readings captured by
     * `BrahmastraUnifiedWiringOrchestrator` into `TremorFftSentinel`.
     */
    fun ingestLiveAccelerometerBatch(samples: DoubleArray): TremorFftSentinel.TremorSpectrumReport {
        TremorFftSentinel.ingestLiveBatch(samples)
        val report = TremorFftSentinel.analyzeLatestLiveWindow()
            ?: TremorFftSentinel.analyzeMicroTremorWindow(samples)
        lastLiveTremorVerified = report.isGenuineHumanHand
        return report
    }

    fun isLiveTremorVerified(): Boolean = lastLiveTremorVerified

    /**
     * Wraps any outgoing Login or Exam submission payload with:
     * 1. Schnorr Zero-Knowledge Proof (`ZkpSchnorrAuth`)
     * 2. Tink AES-256-GCM AEAD + Lorenz Seed AAD (`LorenzChaosCipher`)
     * 3. Ephemeral Shamir 3-of-5 Reconstructed HMAC-SHA256 Signature (`ShamirMemoryVault`)
     * 4. Hardware Silicon PUF Hash (`SiliconPufEngine`)
     */
    fun signWithMathShield(payload: String): MathShieldSignedEnvelope {
        if (activeShamirShares.isEmpty()) {
            armMathBrahmastra()
        }
        val lorenzPacket = LorenzChaosCipher.encryptWithLorenzChaos(payload)
        val shamirSig = ShamirMemoryVault.reconstructAndSign(
            shares = activeShamirShares.take(3),
            payload = payload
        )
        val scalar = ZkpSchnorrAuth.deriveSecretScalar(cachedSiliconHashHex.ifBlank { "WCI_MATH_SEED" })
        val zkp = ZkpSchnorrAuth.generateProof(
            secretScalar = scalar,
            challengeNonce = shamirSig.take(32)
        )
        return MathShieldSignedEnvelope(
            originalPayload = payload,
            chaoticCiphertextBase64 = lorenzPacket.ciphertextBase64,
            lorenzSeed = lorenzPacket.seed,
            shamirHmacSignatureHex = shamirSig,
            zkpProof = zkp,
            siliconFingerprintHex = cachedSiliconHashHex
        )
    }
}
