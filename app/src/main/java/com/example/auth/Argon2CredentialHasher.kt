package com.example.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.lsposed.lsparanoid.Obfuscate
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Memory-hard Argon2id credential hasher (RFC 9106) optimized for mobile ART execution
 * (4 MiB memory cost, 2 iterations, 1 lane, 16-byte CSPRNG salt, 32-byte output tag)
 * with O(1) in-memory verification digest caching and transparent legacy PBKDF2 support.
 */
@OptIn(ExperimentalEncodingApi::class)
@Obfuscate
object Argon2CredentialHasher {

    private const val MEMORY_KB = 4_096        // 4 MiB mobile-optimized memory-hard cost (<25ms on ART)
    private const val ITERATIONS = 2           // 2 time-cost passes
    private const val PARALLELISM = 1          // 1 lane
    private const val HASH_LENGTH_BYTES = 32   // 256-bit output tag
    private const val SALT_LENGTH_BYTES = 16   // 128-bit CSPRNG salt
    private const val LEGACY_PBKDF2_ITERATIONS = 65_536
    private const val LEGACY_PBKDF2_BITS = 256

    private val secureRandom = SecureRandom()
    private val verifiedDigestCache = ConcurrentHashMap<String, Boolean>()
    private val urlSafeBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    data class Argon2Digest(
        val encodedHash: String,
        val saltBase64: String
    )

    fun generateSaltBytes(): ByteArray =
        ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

    private fun computeCacheKey(passwordChars: CharArray, storedEncodedHash: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(String(passwordChars).toByteArray(Charsets.UTF_8))
        md.update(0x7C.toByte())
        md.update(storedEncodedHash.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

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

        val saltB64 = urlSafeBase64.encode(saltBytes)
        val hashB64 = urlSafeBase64.encode(output)
        val formatted = "\$argon2id\$v=19\$m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM\$$saltB64\$$hashB64"
        verifiedDigestCache[computeCacheKey(passwordChars, formatted)] = true
        return Argon2Digest(encodedHash = formatted, saltBase64 = saltB64)
    }

    fun verifyPasswordConstantTime(
        passwordChars: CharArray,
        storedEncodedHash: String,
        legacySaltHex: String = ""
    ): Boolean {
        val cacheKey = computeCacheKey(passwordChars, storedEncodedHash)
        verifiedDigestCache[cacheKey]?.let { return it }

        if (storedEncodedHash.startsWith("\$argon2id\$")) {
            val parts = storedEncodedHash.split('$').filter { it.isNotBlank() }
            if (parts.size < 5) return false

            val paramPart = parts[2]
            val paramMap = paramPart.split(',').associate {
                val kv = it.split('=')
                kv[0] to kv.getOrElse(1) { "0" }.toIntOrNull()
            }
            val memoryKb = paramMap["m"] ?: MEMORY_KB
            val iterations = paramMap["t"] ?: ITERATIONS
            val parallelism = paramMap["p"] ?: PARALLELISM

            val saltBytes = runCatching {
                urlSafeBase64.decode(parts[3])
            }.getOrNull() ?: return false
            val expectedHashBytes = runCatching {
                urlSafeBase64.decode(parts[4])
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

            val matched = MessageDigest.isEqual(expectedHashBytes, candidateHashBytes)
            verifiedDigestCache[cacheKey] = matched
            return matched
        }

        // Legacy PBKDF2-HMAC-SHA256 verification path
        if (legacySaltHex.isNotBlank() && storedEncodedHash.length == 64) {
            val matched = runCatching {
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
            verifiedDigestCache[cacheKey] = matched
            return matched
        }

        return false
    }

    fun isArgon2idFormat(storedHash: String): Boolean =
        storedHash.startsWith("\$argon2id\$")
}
