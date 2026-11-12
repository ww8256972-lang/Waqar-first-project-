package com.example.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.lsposed.lsparanoid.Obfuscate
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Memory-hard Argon2id credential hasher (RFC 9106) with 64 MiB memory cost,
 * 3 iterations, 2 lanes, 16-byte CSPRNG salt, and 32-byte output tag.
 * Also supports transparent verification of legacy PBKDF2-HMAC-SHA256 records
 * so existing credentials can be seamlessly upgraded to Argon2id on login.
 */
@Obfuscate
object Argon2CredentialHasher {

    private const val MEMORY_KB = 65_536       // 64 MiB memory-hard cost
    private const val ITERATIONS = 3           // 3 time-cost passes
    private const val PARALLELISM = 2          // 2 parallel lanes
    private const val HASH_LENGTH_BYTES = 32   // 256-bit output tag
    private const val SALT_LENGTH_BYTES = 16   // 128-bit CSPRNG salt
    private const val LEGACY_PBKDF2_ITERATIONS = 65_536
    private const val LEGACY_PBKDF2_BITS = 256

    private val secureRandom = SecureRandom()

    data class Argon2Digest(
        val encodedHash: String,
        val saltBase64: String
    )

    fun generateSaltBytes(): ByteArray =
        ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

    fun hashPasswordArgon2id(
        passwordChars: CharArray,
        saltBytes: ByteArray = generateSaltBytes()
    ): Argon2Digest {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(MEMORY_KB)
            .withIterations(ITERATIONS)
            .withParallelism(PARALLELISM)
            .withSalt(saltBytes)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)

        val output = ByteArray(HASH_LENGTH_BYTES)
        generator.generateBytes(passwordChars, output)
        params.clear()

        val saltB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(saltBytes)
        val hashB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(output)
        val formatted = "\$argon2id\$v=19\$m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM\$$saltB64\$$hashB64"
        return Argon2Digest(encodedHash = formatted, saltBase64 = saltB64)
    }

    fun verifyPasswordConstantTime(
        passwordChars: CharArray,
        storedEncodedHash: String,
        legacySaltHex: String = ""
    ): Boolean {
        if (storedEncodedHash.startsWith("\$argon2id\$")) {
            val parts = storedEncodedHash.split('$').filter { it.isNotBlank() }
            if (parts.size < 5) return false

            val paramPart = parts[2] // m=65536,t=3,p=2
            val paramMap = paramPart.split(',').associate {
                val kv = it.split('=')
                kv[0] to kv.getOrElse(1) { "0" }.toIntOrNull()
            }
            val memoryKb = paramMap["m"] ?: MEMORY_KB
            val iterations = paramMap["t"] ?: ITERATIONS
            val parallelism = paramMap["p"] ?: PARALLELISM

            val saltBytes = runCatching {
                Base64.getUrlDecoder().decode(parts[3])
            }.getOrNull() ?: return false
            val expectedHashBytes = runCatching {
                Base64.getUrlDecoder().decode(parts[4])
            }.getOrNull() ?: return false

            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKb)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(saltBytes)
                .build()

            val generator = Argon2BytesGenerator()
            generator.init(params)
            val candidateHashBytes = ByteArray(expectedHashBytes.size)
            generator.generateBytes(passwordChars, candidateHashBytes)
            params.clear()

            return MessageDigest.isEqual(expectedHashBytes, candidateHashBytes)
        }

        // Legacy PBKDF2-HMAC-SHA256 verification path (for pre-existing records prior to Argon2id upgrade)
        if (legacySaltHex.isNotBlank() && storedEncodedHash.length == 64) {
            return runCatching {
                val saltBytes = legacySaltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val spec = PBEKeySpec(passwordChars, saltBytes, LEGACY_PBKDF2_ITERATIONS, LEGACY_PBKDF2_BITS)
                val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val hashBytes = skf.generateSecret(spec).encoded
                spec.clearPassword()
                val computedHex = hashBytes.joinToString("") { "%02x".format(it) }
                MessageDigest.isEqual(
                    storedEncodedHash.toByteArray(Charsets.UTF_8),
                    computedHex.toByteArray(Charsets.UTF_8)
                )
            }.getOrDefault(false)
        }

        return false
    }

    fun isArgon2idFormat(storedHash: String): Boolean =
        storedHash.startsWith("\$argon2id\$")
}
