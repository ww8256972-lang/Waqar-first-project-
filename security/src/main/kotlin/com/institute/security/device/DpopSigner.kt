package com.institute.security.device

import org.json.JSONObject
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.UUID

object DpopSigner {

    private const val B64U_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    internal fun b64u(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1

            out.append(B64U_ALPHABET[b0 ushr 2])
            if (b1 >= 0) {
                out.append(B64U_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 >= 0) {
                    out.append(B64U_ALPHABET[((b1 and 0x0f) shl 2) or (b2 ushr 6)])
                    out.append(B64U_ALPHABET[b2 and 0x3f])
                } else {
                    out.append(B64U_ALPHABET[(b1 and 0x0f) shl 2])
                }
            } else {
                out.append(B64U_ALPHABET[(b0 and 0x03) shl 4])
            }
            i += 3
        }
        return out.toString()
    }

    internal fun fix32(bytes: ByteArray): ByteArray {
        val trimmed = bytes.dropWhile { it == 0.toByte() }.toByteArray()
        return when {
            trimmed.size == 32 -> trimmed
            trimmed.size > 32 -> trimmed.copyOfRange(trimmed.size - 32, trimmed.size)
            else -> ByteArray(32 - trimmed.size) + trimmed
        }
    }

    fun jwk(publicKey: ECPublicKey = DeviceKeyManager.publicKey()): JSONObject {
        val w = publicKey.w
        return JSONObject()
            .put("kty", "EC")
            .put("crv", "P-256")
            .put("x", b64u(fix32(w.affineX.toByteArray())))
            .put("y", b64u(fix32(w.affineY.toByteArray())))
    }

    /** Computes RFC 7638 SHA-256 JWK thumbprint (base64url). */
    fun jwkThumbprint(publicKey: ECPublicKey = DeviceKeyManager.publicKey()): String {
        val w = publicKey.w
        // Canonical lexicographical order per RFC 7638: {"crv":...,"kty":...,"x":...,"y":...}
        val canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${b64u(fix32(w.affineX.toByteArray()))}\",\"y\":\"${b64u(fix32(w.affineY.toByteArray()))}\"}"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return b64u(digest)
    }

    /** Converts Keystore ASN.1 DER ECDSA signature into raw JWS R || S (64 bytes). */
    internal fun derToRaw(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid ECDSA DER signature" }
        var o = 2
        if (der[1].toInt() and 0x80 != 0) {
            o += der[1].toInt() and 0x7f
        }
        require(der[o] == 0x02.toByte()) { "Expected INTEGER for R" }
        val rLen = der[o + 1].toInt() and 0xff
        val r = der.copyOfRange(o + 2, o + 2 + rLen)
        o += 2 + rLen
        require(der[o] == 0x02.toByte()) { "Expected INTEGER for S" }
        val sLen = der[o + 1].toInt() and 0xff
        val s = der.copyOfRange(o + 2, o + 2 + sLen)
        return fix32(r) + fix32(s)
    }

    fun proof(
        method: String,
        url: String,
        accessToken: String?,
        publicKey: ECPublicKey = DeviceKeyManager.publicKey(),
        signer: (ByteArray) -> ByteArray = DeviceKeyManager::sign,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        jti: String = UUID.randomUUID().toString(),
    ): String {
        val header = JSONObject()
            .put("typ", "dpop+jwt")
            .put("alg", "ES256")
            .put("jwk", jwk(publicKey))

        val payload = JSONObject()
            .put("htm", method)
            .put("htu", url)
            .put("iat", nowEpochSec)
            .put("jti", jti)

        accessToken?.takeIf { it.isNotBlank() }?.let { token ->
            val ath = b64u(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
            payload.put("ath", ath)
        }

        val signingInput = "${b64u(header.toString().toByteArray(Charsets.UTF_8))}.${b64u(payload.toString().toByteArray(Charsets.UTF_8))}"
        val rawSig = derToRaw(signer(signingInput.toByteArray(Charsets.UTF_8)))
        return "$signingInput.${b64u(rawSig)}"
    }
}
