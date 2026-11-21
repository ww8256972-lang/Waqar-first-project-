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

    @Test
    fun schnorrZkp_verifiesHonestProof_andRejectsForgedFiatShamirChallenge() {
        val context = "ATTENDANCE_BATCH_A_2026"
        val proof = com.waqar.institute.consensus.ZkpAndCrdtConsensusEngine
            .generateSchnorrZeroKnowledgeProof("student_secret_witness", context)

        assertTrue(
            com.waqar.institute.consensus.ZkpAndCrdtConsensusEngine
                .verifySchnorrZeroKnowledgeProof(proof, context)
        )
        assertFalse(
            com.waqar.institute.consensus.ZkpAndCrdtConsensusEngine
                .verifySchnorrZeroKnowledgeProof(proof, "DIFFERENT_CONTEXT")
        )

        // Forge t = g^s * y^(-c) mod p with arbitrary c = 1 so lhs == rhs algebraically,
        // verifying that Fiat-Shamir hash challenge equality catches the forgery.
        val engine = com.waqar.institute.consensus.ZkpAndCrdtConsensusEngine
        val p = engine.P_MODULUS
        val g = engine.G_GENERATOR
        val y = java.math.BigInteger(proof.publicCommitmentYHex, 16)
        val forgedC = java.math.BigInteger.ONE
        val forgedS = java.math.BigInteger.valueOf(42L)
        val forgedT = g.modPow(forgedS, p)
            .multiply(y.modInverse(p).modPow(forgedC, p))
            .mod(p)
        val forgedProof = proof.copy(
            ephemeralCommitmentTHex = forgedT.toString(16).uppercase(),
            challengeCHex = forgedC.toString(16).uppercase(),
            responseSHex = forgedS.toString(16).uppercase()
        )
        assertFalse(engine.verifySchnorrZeroKnowledgeProof(forgedProof, context))
    }

    @Test
    fun immutableLedger_verifiesValidChain_andDetectsTampering() {
        val genesis = "0000000000000000000000000000000000000000000000000000000000000000"
        val vClock = mapOf("dev1" to 1L)
        val ts = 1_762_880_000_000L
        val raw1 = "1|$genesis|FEE_PAID|admin|INR 5000|$vClock|$ts"
        val h1 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw1.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        val entry1 = com.waqar.institute.ledger.ImmutableEventSourcedLedger.LedgerEventEntry(
            sequenceIndex = 1L,
            eventType = "FEE_PAID",
            actorId = "admin",
            payloadSummary = "INR 5000",
            prevEventHash = genesis,
            currentEventHash = h1,
            vectorClock = vClock,
            timestampMs = ts
        )
        val raw2 = "2|$h1|CERT_ISSUED|admin|WCI-101|$vClock|${ts + 1000L}"
        val h2 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw2.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        val entry2 = com.waqar.institute.ledger.ImmutableEventSourcedLedger.LedgerEventEntry(
            sequenceIndex = 2L,
            eventType = "CERT_ISSUED",
            actorId = "admin",
            payloadSummary = "WCI-101",
            prevEventHash = h1,
            currentEventHash = h2,
            vectorClock = vClock,
            timestampMs = ts + 1000L
        )

        assertTrue(
            com.waqar.institute.ledger.ImmutableEventSourcedLedger
                .verifyExternalMerkleChain(listOf(entry1, entry2))
        )
        assertFalse(
            com.waqar.institute.ledger.ImmutableEventSourcedLedger
                .verifyExternalMerkleChain(listOf(entry1.copy(payloadSummary = "INR 1"), entry2))
        )
        assertFalse(
            com.waqar.institute.ledger.ImmutableEventSourcedLedger
                .verifyExternalMerkleChain(listOf(entry1, entry2.copy(prevEventHash = genesis)))
        )
    }

    @Test
    fun certificateCanonicalVerificationHash_isDeterministicSha256() {
        val h1 = com.waqar.institute.certificate.CertificateQrGenerator.computeCanonicalVerificationHash(
            certSerialNo = "WCI-CERT-2026-10042",
            studentIdOrRoll = "WCI-101",
            studentName = "Aarav Sharma",
            courseName = "ADCA Diploma",
            grade = "A+",
            issueDateIso = "2026-11-13"
        )
        val h2 = com.waqar.institute.certificate.CertificateQrGenerator.computeCanonicalVerificationHash(
            certSerialNo = "WCI-CERT-2026-10042",
            studentIdOrRoll = "WCI-101",
            studentName = "Aarav Sharma",
            courseName = "ADCA Diploma",
            grade = "A+",
            issueDateIso = "2026-11-13"
        )
        val hTampered = com.waqar.institute.certificate.CertificateQrGenerator.computeCanonicalVerificationHash(
            certSerialNo = "WCI-CERT-2026-10042",
            studentIdOrRoll = "WCI-101",
            studentName = "Aarav Sharma",
            courseName = "ADCA Diploma",
            grade = "C",
            issueDateIso = "2026-11-13"
        )
        assertEquals(h1, h2)
        assertTrue(h1.startsWith("SHA256:"))
        assertEquals(23, h1.length)
        assertFalse(h1 == hTampered)
    }

    @Test
    fun faceCosineSimilarity_acceptsIdenticalAndRejectsDistinctVectors() {
        val vA = FloatArray(128) { i -> kotlin.math.sin((i + 1) * 0.23f) }
        val vB = FloatArray(128) { i -> kotlin.math.cos((i + 1) * 0.41f) }

        val sameSim = com.waqar.institute.biometric.FaceBiometricVerificationEngine
            .computeCosineSimilaritySafe(vA, vA)
        val diffSim = com.waqar.institute.biometric.FaceBiometricVerificationEngine
            .computeCosineSimilaritySafe(vA, vB)

        assertTrue("Identical vectors must exceed match threshold", sameSim >= 0.99f)
        assertTrue(
            "Distinct face vectors must fall below match threshold (${com.waqar.institute.biometric.FaceBiometricVerificationEngine.COSINE_MATCH_THRESHOLD})",
            diffSim < com.waqar.institute.biometric.FaceBiometricVerificationEngine.COSINE_MATCH_THRESHOLD
        )
    }

    @Test
    fun studentCosineSimilarity_acceptsIdenticalAndRejectsDistinct12DVectors() {
        val stuA = floatArrayOf(-0.04f, 0.02f, 0.05f, 0.01f, -0.02f, 0.04f, -0.03f, 0.03f, 0.06f, -0.04f, 0.04f, -0.08f)
        val stuB = floatArrayOf(0.05f, -0.04f, -0.03f, -0.05f, 0.03f, -0.06f, 0.04f, -0.02f, -0.07f, 0.05f, -0.03f, 0.14f)

        val selfSim = com.example.biometric.StudentFaceEnrollmentManager.computeCosineSimilarity(stuA, stuA)
        val crossSim = com.example.biometric.StudentFaceEnrollmentManager.computeCosineSimilarity(stuA, stuB)

        assertTrue(selfSim >= 0.99f)
        assertTrue("Distinct student vectors must fail 0.85 threshold (got $crossSim)", crossSim < 0.85f)
    }

    @Test
    fun stringShield_and_bytecodeMutator_and_vmBridge_roundTripCleanly() {
        val secret = "WCI_CAMPUS_GEOFENCE_KEY_2026"
        val shielded = com.example.security.StringShield.shield(secret, 0x5A)
        val unshielded = com.example.security.StringShield.unshield(shielded, 0x5A)
        assertEquals(secret, unshielded)

        assertTrue(com.waqar.institute.security.BytecodeMutator.isInstructionPointerFlowHealthy())
        assertTrue(
            com.waqar.institute.security.BinaryVirtualizationBridge.verifySessionBinding(
                userId = "cred_rohit_wci",
                portalRole = "STUDENT",
                tokenNonce = "sess_nonce_2026"
            )
        )
        assertFalse(
            com.waqar.institute.security.BinaryVirtualizationBridge.verifyCriticalPayloadInVm(ByteArray(0))
        )

        val selfHeal = com.waqar.institute.security.AppSelfHealingManager.verifyRuntimeIntegrity()
        assertEquals(
            com.waqar.institute.security.AppSelfHealingManager.isNativeLibraryLoaded(),
            selfHeal.isSegmentIntact
        )
        assertTrue(selfHeal.crc32SignatureHex.startsWith("0x"))
    }

    @Test
    fun idempotentSyncManager_canonicalDigestAndConflictResolution_areDeterministic() {
        val payloadA = linkedMapOf<String, Any?>(
            "id" to "fee_901",
            "paidAmountPaise" to 600000L,
            "status" to "PARTIAL",
            "updatedAt" to 1000L
        )
        val payloadB = linkedMapOf<String, Any?>(
            "status" to "PARTIAL",
            "id" to "fee_901",
            "paidAmountPaise" to 600000L,
            "idempotencyToken" to "ignored_meta"
        )
        // Regardless of insertion order or transient metadata keys, canonical SHA-256 digest must match
        val digestA = com.waqar.institute.data.sync.IdempotentSyncManager.canonicalPayloadDigest(payloadA)
        val digestB = com.waqar.institute.data.sync.IdempotentSyncManager.canonicalPayloadDigest(payloadB)
        assertEquals(digestA, digestB)

        val tokenNewer = com.waqar.institute.data.sync.IdempotentSyncManager.enqueueIdempotentMutation(
            collectionPath = "fees",
            documentId = "fee_901",
            payload = mapOf("id" to "fee_901", "paidAmountPaise" to 1200000L, "status" to "PAID"),
            mutationTimestampMs = 2_000L
        )
        // Stale out-of-order mutation (timestamp 1_500L < 2_000L) must NOT overwrite the newer queued mutation
        val tokenStale = com.waqar.institute.data.sync.IdempotentSyncManager.enqueueIdempotentMutation(
            collectionPath = "fees",
            documentId = "fee_901",
            payload = mapOf("id" to "fee_901", "paidAmountPaise" to 600000L, "status" to "PARTIAL"),
            mutationTimestampMs = 1_500L
        )
        assertEquals(tokenNewer, tokenStale)
    }
}
