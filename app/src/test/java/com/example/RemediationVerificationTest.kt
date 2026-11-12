package com.example

import com.example.auth.Argon2CredentialHasher
import com.example.security.Rfc6238TotpValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediationVerificationTest {

    @Test
    fun argon2id_hashesAndVerifiesPassword_andRejectsWrongPassword() {
        val salt = Argon2CredentialHasher.generateSaltBytes()
        assertEquals(16, salt.size)

        val digest = Argon2CredentialHasher.hashPasswordArgon2id("Waqar@2026#Admin".toCharArray(), salt)
        assertTrue(Argon2CredentialHasher.isArgon2idFormat(digest.encodedHash))

        assertTrue(
            Argon2CredentialHasher.verifyPasswordConstantTime(
                passwordChars = "Waqar@2026#Admin".toCharArray(),
                storedEncodedHash = digest.encodedHash
            )
        )
        assertFalse(
            Argon2CredentialHasher.verifyPasswordConstantTime(
                passwordChars = "WrongPassword123".toCharArray(),
                storedEncodedHash = digest.encodedHash
            )
        )
    }

    @Test
    fun rfc6238Totp_generatesAndVerifiesSixDigitCode() {
        val secret = Rfc6238TotpValidator.generateBase32Secret()
        assertTrue(secret.length >= 16)

        val nowMillis = 1_762_880_000_000L
        val code = Rfc6238TotpValidator.generateCurrentCode(secret, nowMillis)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })

        assertTrue(Rfc6238TotpValidator.verifyCode(secret, code, nowMillis))
        assertTrue(Rfc6238TotpValidator.verifyCode(secret, code, nowMillis + 25_000L))
        assertFalse(Rfc6238TotpValidator.verifyCode(secret, "000000", nowMillis + 600_000L))
    }
}
