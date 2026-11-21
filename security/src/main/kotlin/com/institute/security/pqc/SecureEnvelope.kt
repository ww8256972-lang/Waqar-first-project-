package com.institute.security.pqc

import com.institute.crypto.HybridKem
import com.institute.security.SecurityKit
import org.json.JSONObject
import java.util.UUID

object SecureEnvelope {

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    internal fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val has1 = i + 1 < bytes.size
            val has2 = i + 2 < bytes.size
            val b1 = if (has1) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (has2) bytes[i + 2].toInt() and 0xff else 0

            out.append(B64_ALPHABET[b0 ushr 2])
            out.append(B64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(if (has1) B64_ALPHABET[((b1 and 0x0f) shl 2) or (b2 ushr 6)] else '=')
            out.append(if (has2) B64_ALPHABET[b2 and 0x3f] else '=')
            i += 3
        }
        return out.toString()
    }

    /**
     * Seals sensitive payloads (e.g. fee records, biometric templates) client-side using
     * hybrid ML-KEM-768 + X25519 + AES-256-GCM and replay-protected AAD.
     */
    fun sealFor(
        action: String,
        objectId: String,
        plain: ByteArray,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        nonce: String = UUID.randomUUID().toString(),
    ): JSONObject {
        val cfg = SecurityKit.config
        val recipient = HybridKem.RecipientPublic(
            kid = cfg.pqcKid,
            mlkem = cfg.pqcMlKemPub,
            x25519 = cfg.pqcX25519Pub,
        )
        return sealFor(action, objectId, plain, recipient, nowEpochSec, nonce)
    }

    fun sealFor(
        action: String,
        objectId: String,
        plain: ByteArray,
        recipient: HybridKem.RecipientPublic,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        nonce: String = UUID.randomUUID().toString(),
    ): JSONObject {
        val aad = "$action|$objectId|$nowEpochSec|$nonce"
        val sealed = HybridKem.seal(plain, aad.toByteArray(Charsets.UTF_8), recipient)
        return JSONObject()
            .put("aad", aad)
            .put(
                "sealed",
                JSONObject()
                    .put("kid", sealed.kid)
                    .put("kemCt", encodeBase64(sealed.kemCt))
                    .put("eph", encodeBase64(sealed.ephX25519))
                    .put("nonce", encodeBase64(sealed.nonce))
                    .put("ct", encodeBase64(sealed.ct)),
            )
    }
}
