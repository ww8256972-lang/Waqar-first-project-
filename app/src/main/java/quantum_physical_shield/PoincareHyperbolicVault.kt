package quantum_physical_shield

import android.content.Context
import com.example.security.TinkMaterialVault
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.tanh

/**
 * 4. `quantum_physical_shield/PoincareHyperbolicVault.kt`
 * Google Tink AES-256-GCM AEAD Vault (backed by Android Keystore) with Poincaré Unit Disk
 * Möbius Coordinate Projection.
 *
 * Replaces toy floating-point reversibility as the cryptographic primitive with real Google Tink
 * AES-256-GCM authenticated encryption while preserving exact public method signatures and
 * hyperbolic coordinate projection APIs.
 */
object PoincareHyperbolicVault {

    @Volatile
    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext ?: context
    }

    data class ComplexPoint(val re: Double, val im: Double) {
        fun magnitude(): Double = hypot(re, im)

        operator fun plus(other: ComplexPoint) = ComplexPoint(re + other.re, im + other.im)
        operator fun minus(other: ComplexPoint) = ComplexPoint(re - other.re, im - other.im)
        operator fun times(other: ComplexPoint) = ComplexPoint(
            re = this.re * other.re - this.im * other.im,
            im = this.re * other.im + this.im * other.re
        )
        operator fun div(other: ComplexPoint): ComplexPoint {
            val denom = (other.re * other.re + other.im * other.im).coerceAtLeast(1e-15)
            return ComplexPoint(
                re = (this.re * other.re + this.im * other.im) / denom,
                im = (this.im * other.re - this.re * other.im) / denom
            )
        }
        fun conjugate() = ComplexPoint(re, -im)
    }

    data class MobiusDiskKey(val w0: ComplexPoint) {
        init {
            require(w0.magnitude() < 0.99) { "Poincaré disk anchor |w0| must be strictly < 1." }
        }
    }

    data class HyperbolicVaultEntry(
        val keyAlias: String,
        val transformedPoints: List<ComplexPoint>,
        val originalByteLength: Int,
        val tinkAeadCiphertext: ByteArray = ByteArray(0)
    )

    private val vaultStorage = ConcurrentHashMap<String, HyperbolicVaultEntry>()

    fun applyMobiusTransform(z: ComplexPoint, key: MobiusDiskKey): ComplexPoint {
        val one = ComplexPoint(1.0, 0.0)
        val numerator = z - key.w0
        val denominator = one - (key.w0.conjugate() * z)
        return numerator / denominator
    }

    fun invertMobiusTransform(u: ComplexPoint, key: MobiusDiskKey): ComplexPoint {
        val one = ComplexPoint(1.0, 0.0)
        val numerator = u + key.w0
        val denominator = one + (key.w0.conjugate() * u)
        return numerator / denominator
    }

    private fun buildAad(keyAlias: String, mobiusKey: MobiusDiskKey): ByteArray =
        "poincare_tink_v2|$keyAlias|${"%.6f".format(mobiusKey.w0.re)}|${"%.6f".format(mobiusKey.w0.im)}"
            .toByteArray(Charsets.UTF_8)

    fun sealSecretInPoincareDisk(
        keyAlias: String,
        secretText: String,
        mobiusKey: MobiusDiskKey = MobiusDiskKey(ComplexPoint(0.35, -0.28))
    ): HyperbolicVaultEntry {
        val plainBytes = secretText.toByteArray(Charsets.UTF_8)
        val aad = buildAad(keyAlias, mobiusKey)
        // Real Google Tink AES-256-GCM AEAD encryption backed by Android Keystore
        val ciphertext = TinkMaterialVault.encryptBytes(appContext, plainBytes, aad)

        // Project AEAD ciphertext bytes (never plaintext!) onto the Poincaré unit disk
        val points = ciphertext.mapIndexed { idx, b ->
            val unsigned = b.toInt() and 0xFF
            val r = (unsigned.toDouble() - 128.0) / 256.0
            val theta = ((idx % 16) - 8) / 32.0
            val z = ComplexPoint(tanh(r) * 0.75, tanh(theta) * 0.35)
            applyMobiusTransform(z, mobiusKey)
        }
        val entry = HyperbolicVaultEntry(
            keyAlias = keyAlias,
            transformedPoints = points,
            originalByteLength = plainBytes.size,
            tinkAeadCiphertext = ciphertext
        )
        vaultStorage[keyAlias] = entry
        return entry
    }

    fun unsealSecretFromPoincareDisk(
        entry: HyperbolicVaultEntry,
        mobiusKey: MobiusDiskKey = MobiusDiskKey(ComplexPoint(0.35, -0.28))
    ): String {
        val aad = buildAad(entry.keyAlias, mobiusKey)
        val ciphertext = if (entry.tinkAeadCiphertext.isNotEmpty()) {
            entry.tinkAeadCiphertext
        } else {
            ByteArray(entry.transformedPoints.size) { i ->
                val z = invertMobiusTransform(entry.transformedPoints[i], mobiusKey)
                val clamped = (z.re / 0.75).coerceIn(-0.999, 0.999)
                val atanhVal = 0.5 * kotlin.math.ln((1.0 + clamped) / (1.0 - clamped))
                kotlin.math.round(atanhVal * 256.0 + 128.0).toInt().coerceIn(0, 255).toByte()
            }
        }
        val decrypted = TinkMaterialVault.decryptBytes(appContext, ciphertext, aad)
        return String(decrypted, Charsets.UTF_8)
    }
}
