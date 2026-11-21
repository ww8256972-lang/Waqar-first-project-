package com.waqar.institute.security

import androidx.test.core.app.ApplicationProvider
import com.example.exam.TamperProofExamClock
import com.waqar.institute.biometric.HardwareBiometricAttestor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EliteSecurityLayersTest {

    @Test
    fun layer1_hardwareDpopProof_verifiesSignature_andDetectsBodyOrUrlTampering() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val url = "https://asia-east1-gen-lang-client-0309263247.cloudfunctions.net/streamInstituteAiTutor"
        val body = """{"userPrompt":"Explain pointers in C","role":"student"}"""

        val proof = HardwareDpopRequestSigner.signRequest(
            context = ctx,
            httpMethod = "POST",
            url = url,
            rawBody = body,
            deviceFpOverride = "fp_test_device_01"
        )
        assertNotNull(proof)
        val nonNullProof = proof!!
        assertTrue(
            HardwareDpopRequestSigner.verifyProofLocally(
                proofToken = nonNullProof.proofToken,
                publicKeySpkiBase64 = nonNullProof.publicKeySpkiBase64,
                expectedMethod = "POST",
                expectedUrl = url,
                expectedRawBody = body
            )
        )
        // Tampered request body must fail ath verification
        assertFalse(
            HardwareDpopRequestSigner.verifyProofLocally(
                proofToken = nonNullProof.proofToken,
                publicKeySpkiBase64 = nonNullProof.publicKeySpkiBase64,
                expectedMethod = "POST",
                expectedUrl = url,
                expectedRawBody = """{"userPrompt":"Tampered prompt","role":"admin"}"""
            )
        )
        // Wrong endpoint URL must fail htu verification
        assertFalse(
            HardwareDpopRequestSigner.verifyProofLocally(
                proofToken = nonNullProof.proofToken,
                publicKeySpkiBase64 = nonNullProof.publicKeySpkiBase64,
                expectedMethod = "POST",
                expectedUrl = "https://evil.example.com/steal",
                expectedRawBody = body
            )
        )
    }

    @Test
    fun layer2_serverExamDeadlineAnchoring_enforcesAuthoritativeWindowAndExpiry() {
        val serverNow = 1_760_000_000_000L
        val hardDeadline = serverNow + 1_800_000L // 30 minutes
        val monotonicStart = 100_000L

        val deadlineElapsed = TamperProofExamClock.anchorAuthoritativeServerDeadline(
            serverNowMs = serverNow,
            hardDeadlineAtMs = hardDeadline,
            currentElapsedRealtimeMs = monotonicStart
        )
        assertEquals(monotonicStart + 1_800_000L, deadlineElapsed)
        assertEquals(1800, TamperProofExamClock.computeRemainingSeconds(deadlineElapsed, monotonicStart))
        assertFalse(TamperProofExamClock.isServerDeadlineExpired(hardDeadline, 30_000L, hardDeadline + 10_000L))
        assertTrue(TamperProofExamClock.isServerDeadlineExpired(hardDeadline, 30_000L, hardDeadline + 31_000L))
    }

    @Test
    fun layer3_biometricActionSigningFallback_producesNonEmptySignatureInHeadlessEnvironment() {
        var captured: HardwareBiometricAttestor.BiometricActionSignature? = null
        HardwareBiometricAttestor.signCriticalAdminActionWithCryptoObject(
            activity = null,
            actionType = "UPI_FEE_APPROVED",
            canonicalPayload = "txn_123|stu_1|50000|UTR123456789012",
            onSigned = { captured = it },
            onFallback = { captured = it }
        )
        assertNotNull(captured)
        assertEquals("UPI_FEE_APPROVED", captured!!.actionType)
        assertTrue(captured!!.signatureHex.length >= 32)
    }

    @Test
    fun layer4_remoteKillSwitch_andDeviceRevocationEvaluator() {
        assertTrue(DeviceRevocationGuard.isForceUpdateRequired(currentVersionCode = 1, minSupportedVersionCode = 5L))
        assertFalse(DeviceRevocationGuard.isForceUpdateRequired(currentVersionCode = 5, minSupportedVersionCode = 5L))
        assertFalse(DeviceRevocationGuard.isForceUpdateRequired(currentVersionCode = 7, minSupportedVersionCode = 5L))

        val revoked = DeviceRevocationGuard.evaluateRevocationDocument("fp_stolen_99", true, "Lost device")
        assertTrue(revoked is DeviceRevocationGuard.DeviceLockdownState.DeviceRevoked)
        val allowed = DeviceRevocationGuard.evaluateRevocationDocument("fp_clean_01", false, null)
        assertEquals(DeviceRevocationGuard.DeviceLockdownState.Allowed, allowed)
    }

    @Test
    fun layer5_serverAuditHashChain_verifiesContinuity_andDetectsSingleBitTampering() {
        val payload1 = ServerAuditHashChainClient.canonicalizeJsonValue(
            mapOf("utrNumber" to "123456789012", "amountPaise" to 50000L)
        )
        val ts1 = 1_760_000_001_000L
        val hash1 = ServerAuditHashChainClient.computeChainLinkHash(
            previousHash = ServerAuditHashChainClient.GENESIS_HASH,
            seq = 1L,
            eventType = "UPI_FEE_APPROVED",
            recordId = "upi_001",
            canonicalPayloadJson = payload1,
            serverTimestampMs = ts1
        )
        val block1 = ServerAuditHashChainClient.SealedAuditBlock(
            seq = 1L,
            previousHash = ServerAuditHashChainClient.GENESIS_HASH,
            currentHash = hash1,
            eventType = "UPI_FEE_APPROVED",
            recordId = "upi_001",
            canonicalPayload = payload1,
            serverTimestampMs = ts1
        )

        val payload2 = ServerAuditHashChainClient.canonicalizeJsonValue(
            mapOf("serialNo" to "WCI-CERT-2026-10001", "grade" to "A+")
        )
        val ts2 = 1_760_000_002_000L
        val hash2 = ServerAuditHashChainClient.computeChainLinkHash(
            previousHash = hash1,
            seq = 2L,
            eventType = "CERTIFICATE_ISSUED",
            recordId = "WCI-CERT-2026-10001",
            canonicalPayloadJson = payload2,
            serverTimestampMs = ts2
        )
        val block2 = ServerAuditHashChainClient.SealedAuditBlock(
            seq = 2L,
            previousHash = hash1,
            currentHash = hash2,
            eventType = "CERTIFICATE_ISSUED",
            recordId = "WCI-CERT-2026-10001",
            canonicalPayload = payload2,
            serverTimestampMs = ts2
        )

        val validReport = ServerAuditHashChainClient.verifyOrderedBlocks(listOf(block1, block2))
        assertTrue(validReport.valid)
        assertEquals("VERIFIED", validReport.statusLabel)
        assertEquals(2, validReport.checkedCount)

        // Mutate block1 payload without recomputing chain -> must detect TAMPERED at seq 1
        val tamperedBlock1 = block1.copy(canonicalPayload = """{"amountPaise":99999,"utrNumber":"123456789012"}""")
        val tamperedReport = ServerAuditHashChainClient.verifyOrderedBlocks(listOf(tamperedBlock1, block2))
        assertFalse(tamperedReport.valid)
        assertEquals("TAMPERED", tamperedReport.statusLabel)
        assertEquals(1L, tamperedReport.brokenAtSeq)
    }
}
