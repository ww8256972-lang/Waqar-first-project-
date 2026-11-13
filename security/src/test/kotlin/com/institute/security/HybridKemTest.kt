package com.institute.security

import com.institute.crypto.HybridKem
import com.institute.security.pqc.SecureEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class HybridKemTest {

    @Test
    fun roundTrip() {
        val (pub, priv) = HybridKem.generateRecipient("k-2026-11")
        val aad = "attendance|batch:cpp2026|1760000000|nonce-1".toByteArray(Charsets.UTF_8)
        val s = HybridKem.seal("hello-pqc".toByteArray(Charsets.UTF_8), aad, pub)
        assertEquals("hello-pqc", String(HybridKem.open(s, aad, priv), Charsets.UTF_8))
    }

    @Test
    fun wrongAadFails() {
        val (pub, priv) = HybridKem.generateRecipient("k-2026-11")
        val s = HybridKem.seal("x".toByteArray(Charsets.UTF_8), "a".toByteArray(Charsets.UTF_8), pub)
        assertThrows(Exception::class.java) {
            HybridKem.open(s, "b".toByteArray(Charsets.UTF_8), priv)
        }
    }

    @Test
    fun wrongRecipientKeyFails() {
        val (pub1, _) = HybridKem.generateRecipient("k-2026-11")
        val (_, priv2) = HybridKem.generateRecipient("k-2026-11")
        val aad = "fee_edit|fee_record:rahul_nov|1760000000|n2".toByteArray(Charsets.UTF_8)
        val s = HybridKem.seal("amount=5000".toByteArray(Charsets.UTF_8), aad, pub1)
        assertThrows(Exception::class.java) {
            HybridKem.open(s, aad, priv2)
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val (pub, priv) = HybridKem.generateRecipient("k-2026-11")
        val aad = "fee_edit|fee_record:rahul_nov|1760000000|n3".toByteArray(Charsets.UTF_8)
        val s = HybridKem.seal("secret-payload".toByteArray(Charsets.UTF_8), aad, pub)
        val tamperedCt = s.ct.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val tampered = HybridKem.Sealed(s.kid, s.kemCt, s.ephX25519, s.nonce, tamperedCt)
        assertThrows(Exception::class.java) {
            HybridKem.open(tampered, aad, priv)
        }
    }

    @Test
    fun secureEnvelope_roundTripsWithHybridKem() {
        val (pub, priv) = HybridKem.generateRecipient("k-2026-11")
        val payload = """{"student":"rahul","amountInr":4500}""".toByteArray(Charsets.UTF_8)
        val envelopeJson = SecureEnvelope.sealFor(
            action = "fee_edit",
            objectId = "fee_record:rahul_nov",
            plain = payload,
            recipient = pub,
            nowEpochSec = 1_760_000_000L,
            nonce = "n-env-1",
        )

        val aad = envelopeJson.getString("aad")
        assertEquals("fee_edit|fee_record:rahul_nov|1760000000|n-env-1", aad)

        val s = envelopeJson.getJSONObject("sealed")
        val dec = Base64.getDecoder()
        val opened = HybridKem.open(
            HybridKem.Sealed(
                kid = s.getString("kid"),
                kemCt = dec.decode(s.getString("kemCt")),
                ephX25519 = dec.decode(s.getString("eph")),
                nonce = dec.decode(s.getString("nonce")),
                ct = dec.decode(s.getString("ct")),
            ),
            aad.toByteArray(Charsets.UTF_8),
            priv,
        )
        assertArrayEquals(payload, opened)
    }
}
