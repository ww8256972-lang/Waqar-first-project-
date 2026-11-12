package com.example.security

import org.lsposed.lsparanoid.Obfuscate
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Standards-compliant RFC 6238 Time-Based One-Time Password (TOTP) Validator
 * using HMAC-SHA1, 30-second time steps, and a ±1 step drift window.
 */
@Obfuscate
object Rfc6238TotpValidator {
    private const val TIME_STEP_SECONDS = 30L
    private const val CODE_DIGITS = 6
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generateBase32Secret(byteLength: Int = 20): String {
        val randomBytes = ByteArray(byteLength).also { SecureRandom().nextBytes(it) }
        val output = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in randomBytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                output.append(BASE32_ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            output.append(BASE32_ALPHABET[index])
        }
        return output.toString()
    }

    fun verifyCode(
        base32Secret: String,
        userCode: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        window: LongRange = -1L..1L
    ): Boolean {
        val cleanCode = userCode.trim()
        if (cleanCode.length != CODE_DIGITS || !cleanCode.all { it.isDigit() }) return false
        val secretBytes = decodeBase32(base32Secret)
        if (secretBytes.isEmpty()) return false

        val currentCounter = (nowEpochMillis / 1000L) / TIME_STEP_SECONDS
        val userCodeBytes = cleanCode.toByteArray(Charsets.UTF_8)

        for (offset in window) {
            val candidate = generateHotp(secretBytes, currentCounter + offset, CODE_DIGITS)
            if (MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), userCodeBytes)) {
                return true
            }
        }
        return false
    }

    fun generateCurrentCode(
        base32Secret: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String {
        val secretBytes = decodeBase32(base32Secret)
        if (secretBytes.isEmpty()) return ""
        val currentCounter = (nowEpochMillis / 1000L) / TIME_STEP_SECONDS
        return generateHotp(secretBytes, currentCounter, CODE_DIGITS)
    }

    private fun generateHotp(secret: ByteArray, counter: Long, digits: Int): String {
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 10.0.pow(digits).toInt()
        return otp.toString().padStart(digits, '0')
    }

    private fun decodeBase32(input: String): ByteArray {
        val clean = input.uppercase().filter { it in BASE32_ALPHABET }
        if (clean.isEmpty()) return ByteArray(0)
        val output = ArrayList<Byte>(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val value = BASE32_ALPHABET.indexOf(ch)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                output.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return output.toByteArray()
    }
}
