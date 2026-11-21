package math_brahmastra_native

import android.content.Context
import android.util.Base64
import com.example.security.TinkMaterialVault
import kotlin.math.abs
import kotlin.math.floor

/**
 * 2. `math_brahmastra_native/LorenzChaosCipher.kt`
 * Authenticated Encryption Vault backed by Google Tink AES-256-GCM AEAD + Android Keystore
 * (`TinkMaterialVault`), binding the 4th-Order Runge-Kutta (RK4) 3D Lorenz Attractor seed
 * as Associated Authenticated Data (AAD) so ciphertext and seed are both cryptographically
 * authenticated against tampering or chosen-ciphertext attacks.
 */
object LorenzChaosCipher {

    private const val SIGMA = 10.0
    private const val RHO = 28.0
    private const val BETA = 8.0 / 3.0
    private const val DT = 0.005

    @Volatile
    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext ?: context
    }

    data class LorenzSeedState(
        val x0: Double,
        val y0: Double,
        val z0: Double
    )

    data class ChaoticCipherPacket(
        val seed: LorenzSeedState,
        val ciphertextBase64: String
    )

    fun deriveSeedFromEntropy(
        nanoTimestamp: Long = System.nanoTime(),
        sensorEntropy: Float = 0.13579f
    ): LorenzSeedState {
        val normNano = ((nanoTimestamp and 0xFFFFFL).toDouble() / 1_048_576.0).coerceIn(0.001, 0.999)
        val normSensor = (abs(sensorEntropy.toDouble()) % 1.0).coerceIn(0.001, 0.999)
        return LorenzSeedState(
            x0 = 0.1 + normNano * 5.0,
            y0 = 1.0 + normSensor * 5.0,
            z0 = 1.05 + ((normNano + normSensor) * 2.5)
        )
    }

    /**
     * Generates a deterministic chaotic keystream of [length] bytes from [seed] using 4th-order Runge-Kutta
     * (used for non-cryptographic entropy mixing / diagnostics; production encryption uses Tink AES-256-GCM AEAD).
     */
    fun generateChaoticKeystream(seed: LorenzSeedState, length: Int): ByteArray {
        var x = seed.x0
        var y = seed.y0
        var z = seed.z0

        fun dx(px: Double, py: Double): Double = SIGMA * (py - px)
        fun dy(px: Double, py: Double, pz: Double): Double = px * (RHO - pz) - py
        fun dz(px: Double, py: Double, pz: Double): Double = px * py - BETA * pz

        // Warm-up 100 steps into chaotic attractor basin
        repeat(100) {
            val next = rk4Step(x, y, z, ::dx, ::dy, ::dz)
            x = next[0]; y = next[1]; z = next[2]
        }

        val out = ByteArray(length.coerceAtLeast(0))
        for (i in out.indices) {
            val next = rk4Step(x, y, z, ::dx, ::dy, ::dz)
            x = next[0]; y = next[1]; z = next[2]
            val fracX = abs(x * 10_000.0) - floor(abs(x * 10_000.0))
            val fracY = abs(y * 10_000.0) - floor(abs(y * 10_000.0))
            val fracZ = abs(z * 10_000.0) - floor(abs(z * 10_000.0))
            val mixed = ((fracX * 256).toInt() xor (fracY * 256).toInt() xor (fracZ * 256).toInt()) and 0xFF
            out[i] = mixed.toByte()
        }
        return out
    }

    /**
     * Encrypts [plaintext] using Google Tink AES-256-GCM AEAD backed by Android Keystore,
     * binding the Lorenz seed state as Associated Authenticated Data (AAD).
     */
    fun encryptWithLorenzChaos(
        plaintext: String,
        seed: LorenzSeedState = deriveSeedFromEntropy()
    ): ChaoticCipherPacket {
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        val aad = buildSeedAad(seed)
        val cipherBytes = TinkMaterialVault.encryptBytes(
            context = appContext,
            plaintext = plainBytes,
            associatedData = aad
        )
        return ChaoticCipherPacket(
            seed = seed,
            ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        )
    }

    /**
     * Decrypts [packet] using Google Tink AES-256-GCM AEAD backed by Android Keystore,
     * verifying integrity of both ciphertext and Lorenz seed AAD.
     */
    fun decryptWithLorenzChaos(packet: ChaoticCipherPacket): String {
        val cipherBytes = Base64.decode(packet.ciphertextBase64, Base64.NO_WRAP)
        val aad = buildSeedAad(packet.seed)
        val plainBytes = TinkMaterialVault.decryptBytes(
            context = appContext,
            ciphertext = cipherBytes,
            associatedData = aad
        )
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun buildSeedAad(seed: LorenzSeedState): ByteArray =
        "wci_lorenz_aead_v2|${seed.x0.toBits()}|${seed.y0.toBits()}|${seed.z0.toBits()}"
            .toByteArray(Charsets.UTF_8)

    private inline fun rk4Step(
        x: Double,
        y: Double,
        z: Double,
        dx: (Double, Double) -> Double,
        dy: (Double, Double, Double) -> Double,
        dz: (Double, Double, Double) -> Double
    ): DoubleArray {
        val k1x = dx(x, y)
        val k1y = dy(x, y, z)
        val k1z = dz(x, y, z)

        val x2 = x + 0.5 * DT * k1x
        val y2 = y + 0.5 * DT * k1y
        val z2 = z + 0.5 * DT * k1z
        val k2x = dx(x2, y2)
        val k2y = dy(x2, y2, z2)
        val k2z = dz(x2, y2, z2)

        val x3 = x + 0.5 * DT * k2x
        val y3 = y + 0.5 * DT * k2y
        val z3 = z + 0.5 * DT * k2z
        val k3x = dx(x3, y3)
        val k3y = dy(x3, y3, z3)
        val k3z = dz(x3, y3, z3)

        val x4 = x + DT * k3x
        val y4 = y + DT * k3y
        val z4 = z + DT * k3z
        val k4x = dx(x4, y4)
        val k4y = dy(x4, y4, z4)
        val k4z = dz(x4, y4, z4)

        return doubleArrayOf(
            x + (DT / 6.0) * (k1x + 2.0 * k2x + 2.0 * k3x + k4x),
            y + (DT / 6.0) * (k1y + 2.0 * k2y + 2.0 * k3y + k4y),
            z + (DT / 6.0) * (k1z + 2.0 * k2z + 2.0 * k3z + k4z)
        )
    }
}
