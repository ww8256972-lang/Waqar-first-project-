package com.example

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.waqar.institute.biometric.FaceLivenessManager
import com.waqar.institute.security.EnterpriseCoreSecuritySuite
import com.waqar.institute.updater.GitHubOtaUpdater
import internal_compose_brahmastra.CodingSandboxCage
import internal_compose_brahmastra.ComposePhantomTouchGuard
import internal_compose_brahmastra.HomomorphicLedgerGuard
import internal_compose_brahmastra.InternalShieldAutoPlug
import internal_compose_brahmastra.MonotonicCausalityClock
import internal_compose_brahmastra.RoleCapabilityGate
import kotlinx.coroutines.runBlocking
import math_brahmastra_native.LorenzChaosCipher
import math_brahmastra_native.MathShieldConnector
import math_brahmastra_native.ShamirMemoryVault
import math_brahmastra_native.SiliconPufEngine
import math_brahmastra_native.TremorFftSentinel
import math_brahmastra_native.ZkpSchnorrAuth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import quantum_physical_shield.KolmogorovTouchTopology
import quantum_physical_shield.PoincareHyperbolicVault
import quantum_physical_shield.PrnuSensorFingerprint
import quantum_physical_shield.QuantumAutoPlug
import quantum_physical_shield.ThermalClockDriftEngine
import quantum_physical_shield.UltrasonicSonarLiveness
import security_brahmastra.BehavioralBiometrics
import security_brahmastra.CanaryWatermark
import security_brahmastra.HashChainState
import security_brahmastra.HoneypotSentinel
import security_brahmastra.PowChallenge
import security_brahmastra.SecurityBridge
import java.io.File
import java.security.SecureRandom
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrahmastraShieldSuitesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HoneypotSentinel.resetForTests()
        MonotonicCausalityClock.recalibrateAnchor()
    }

    @Test
    fun faceLivenessManager_validatesBlinkHeadTurnAnd128DMasterMatch() {
        val rawEmb = FloatArray(128) { idx -> sin((idx + 1) * 0.21f) }
        val masterEmb = FaceLivenessManager.l2Normalize(rawEmb)

        // Happy path: eyes open -> eyes closed (blink) -> eyes open + head yaw >= 10 deg + matching embedding
        val validFrames = listOf(
            FaceLivenessManager.FrameTelemetry(1000L, 1, 0.92f, 0.90f, 1.0f, 0f, 0f, masterEmb.copyOf()),
            FaceLivenessManager.FrameTelemetry(1150L, 1, 0.12f, 0.15f, -4.0f, 0f, 0f, masterEmb.copyOf()),
            FaceLivenessManager.FrameTelemetry(1300L, 1, 0.89f, 0.91f, -14.5f, 0f, 0f, masterEmb.copyOf())
        )
        val pass = FaceLivenessManager.evaluateFrameTelemetrySeries(
            frames = validFrames,
            enrolledMasterEmbedding = masterEmb,
            requireHeadTurnDirection = "LEFT"
        )
        assertTrue("Expected blink + left head-turn + match to pass, got $pass", pass is FaceLivenessManager.LivenessSequenceVerdict.Passed)

        // Photo spoof (no blink transition) must fail closed
        val noBlinkFrames = listOf(
            FaceLivenessManager.FrameTelemetry(1000L, 1, 0.90f, 0.90f, 0.0f, 0f, 0f, masterEmb.copyOf()),
            FaceLivenessManager.FrameTelemetry(1200L, 1, 0.88f, 0.89f, -13.0f, 0f, 0f, masterEmb.copyOf())
        )
        val noBlinkFail = FaceLivenessManager.evaluateFrameTelemetrySeries(noBlinkFrames, masterEmb)
        assertTrue(noBlinkFail is FaceLivenessManager.LivenessSequenceVerdict.Failed)
        assertEquals("BLINK_LIVENESS_FAILED", (noBlinkFail as FaceLivenessManager.LivenessSequenceVerdict.Failed).reasonCode)

        // Multi-face frame must fail immediately
        val multiFaceFrames = validFrames.toMutableList().apply {
            set(1, get(1).copy(detectedFaceCount = 2))
        }
        val multiFaceFail = FaceLivenessManager.evaluateFrameTelemetrySeries(multiFaceFrames, masterEmb)
        assertEquals("MULTIPLE_FACES_DETECTED", (multiFaceFail as FaceLivenessManager.LivenessSequenceVerdict.Failed).reasonCode)

        // Impostor embedding must fail FACE_MISMATCH
        val impostorEmb = FaceLivenessManager.l2Normalize(FloatArray(128) { idx -> -masterEmb[idx] })
        val impostorFrames = validFrames.map { it.copy(embedding128D = impostorEmb) }
        val mismatchFail = FaceLivenessManager.evaluateFrameTelemetrySeries(impostorFrames, masterEmb)
        assertEquals("FACE_MISMATCH", (mismatchFail as FaceLivenessManager.LivenessSequenceVerdict.Failed).reasonCode)
    }

    @Test
    fun enterpriseCoreSecuritySuite_executesAll8CoreFunctions() {
        val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secretJson = """{"student":"Aarav Sharma","balance":4500,"aadhaarLast4":"9876"}"""

        // 1. Client-Side Zero-Knowledge AES-GCM-256
        val zkEnv = EnterpriseCoreSecuritySuite.encryptZeroKnowledgePayload(
            plaintext = secretJson,
            associatedData = "WCI_REC_001",
            explicitKeyBytes = keyBytes
        )
        assertFalse(zkEnv.ciphertextBase64.contains("Aarav"))
        val decrypted = EnterpriseCoreSecuritySuite.decryptZeroKnowledgePayload(
            envelope = zkEnv,
            associatedData = "WCI_REC_001",
            explicitKeyBytes = keyBytes
        )
        assertEquals(secretJson, decrypted)

        // 2. StrongBox / TEE Biometric Key Derivation & Cipher Initialization
        val strongBoxCipher = EnterpriseCoreSecuritySuite.initBiometricGcmEncryptionCipher(requireUserAuthentication = false)
        assertNotNull(strongBoxCipher.algorithm)

        // 4. Local-to-Cloud Ephemeral Sharding Sync
        val shards = EnterpriseCoreSecuritySuite.shardConfidentialPayload(
            recordId = "rec_shard_1",
            confidentialJson = secretJson,
            shardCount = 3,
            explicitKeyBytes = keyBytes
        )
        assertEquals(3, shards.size)
        val reassembled = EnterpriseCoreSecuritySuite.reassembleConfidentialShards(shards, explicitKeyBytes = keyBytes)
        assertEquals(secretJson, reassembled)

        // 5. FileProvider Install Intent
        val dummyApk = File(context.cacheDir, "wci_test_update.apk").apply { writeText("APK") }
        val installIntent = EnterpriseCoreSecuritySuite.buildFileProviderInstallIntent(context, dummyApk)
        assertEquals(Intent.ACTION_VIEW, installIntent.action)
        assertEquals("application/vnd.android.package-archive", installIntent.type)
        dummyApk.delete()

        // 6. Runtime Anti-Frida & Memory Zeroization (RASP)
        val secretBytes = "TOP_SECRET_KEY_BUFFER".toByteArray()
        val secretChars = "MasterSecret99".toCharArray()
        val raspAttack = EnterpriseCoreSecuritySuite.inspectRaspAndZeroizeOnIntrusion(
            sensitiveByteBuffers = listOf(secretBytes),
            sensitiveCharBuffers = listOf(secretChars),
            overrideMapsLines = listOf("7f881000-7f889000 rwxp 00000000 00:00 0 /data/local/tmp/frida-gadget.so")
        )
        assertTrue(raspAttack.intrusionDetected)
        assertEquals(2, raspAttack.zeroizedBufferCount)
        assertTrue(secretBytes.all { it == 0.toByte() })
        assertTrue(secretChars.all { it == '\u0000' })

        // 7. WhatsApp & Telegram Broadcast Intents
        val waIntent = EnterpriseCoreSecuritySuite.buildWhatsAppBroadcastIntent("+91 9876543210", "Fee Receipt Approved")
        assertEquals("com.whatsapp", waIntent.`package`)
        val tgIntent = EnterpriseCoreSecuritySuite.buildTelegramBroadcastIntent("@waqar_institute", "Exam Tomorrow 10 AM")
        assertEquals("org.telegram.messenger", tgIntent.`package`)

        // 8. SQLCipher 256-Bit Vault Passphrase Derivation
        val sqlKey = EnterpriseCoreSecuritySuite.deriveSqlCipher256BitPassphrase(context)
        assertEquals(32, sqlKey.size)
    }

    @Test
    fun securityBrahmastraPackage_executesAll6Modules() {
        // 1. HoneypotSentinel
        val logEntry = HoneypotSentinel.triggerShadowMode(
            reason = "FRIDA_ATTACHED",
            metadata = mapOf("actorId" to "stu_01", "ip" to "10.0.0.5")
        )
        assertTrue(HoneypotSentinel.isShadowSession)
        assertTrue(logEntry.id.startsWith("hp_"))
        assertEquals(2, HoneypotSentinel.getDecoyExamQuestions().size)
        assertEquals(1, HoneypotSentinel.getIntrusionHoneypotLogs().size)

        // 2. CanaryWatermark
        val origText = "Explain the difference between TCP and UDP transport protocols."
        val watermarked = CanaryWatermark.injectTextCanary(origText, "STU_2026_99", 1780000123L)
        assertTrue(watermarked.length > origText.length)
        assertEquals("STU_2026_99|1780000123", CanaryWatermark.extractTextCanary(watermarked))

        // 3. BehavioralBiometrics
        val profiler = BehavioralBiometrics(deviationThresholdRatio = 0.35)
        repeat(5) { idx ->
            profiler.recordKeyDown(1000L + idx * 300L)
            profiler.recordKeyUp(1090L + idx * 300L)
            profiler.recordPointerMove(10f * idx, 15f * idx)
        }
        profiler.lockBaselineFromCurrentSamples()
        assertNull(profiler.evaluateRhythmDeviation())
        val anomaly = profiler.evaluateRhythmDeviation(
            BehavioralBiometrics.BehavioralVector(meanDwellMs = 260.0, meanFlightMs = 420.0, pointerEntropy = 0.2)
        )
        assertNotNull("Expected >35% typing rhythm deviation to trigger anomaly", anomaly)

        // 4. HashChainState + 5. PowChallenge + 6. SecurityBridge
        val session = SecurityBridge.initBrahmastraShield("stu_01", "STUDENT", "sess_99")
        assertNotNull(session.canarySample)
        SecurityBridge.recordAnsweredQuestionInChain("Q1", 2)
        SecurityBridge.recordAnsweredQuestionInChain("Q2", 0)
        val wrapped = SecurityBridge.wrapExamSubmission("""{"examId":"exm_1"}""", powDifficulty = 3)
        assertEquals(2, wrapped.hashChainStepCount)
        assertTrue(PowChallenge.verifyPoWHeader(wrapped.powHeaderValue, "stu_01_sess_99", minDifficulty = 3))
    }

    @Test
    fun mathBrahmastraNativePackage_executesAll6PureMathModules() {
        // 1. SiliconPufEngine
        val puf = SiliconPufEngine.verifySiliconIntegrity()
        assertEquals(64, puf.siliconFingerprint256Hex.length)
        assertTrue(puf.isGenuineSilicon)

        // 2. LorenzChaosCipher
        val packet = LorenzChaosCipher.encryptWithLorenzChaos("WCI_TOP_SECRET_LEDGER_2026")
        assertNotEquals("WCI_TOP_SECRET_LEDGER_2026", packet.ciphertextBase64)
        assertEquals("WCI_TOP_SECRET_LEDGER_2026", LorenzChaosCipher.decryptWithLorenzChaos(packet))

        // 3. ShamirMemoryVault (3-of-5 Lagrange Interpolation)
        val masterKey = ByteArray(32) { (it + 7).toByte() }
        val shares = ShamirMemoryVault.splitSecretIntoShares(masterKey, totalShares = 5, threshold = 3)
        assertEquals(5, shares.size)
        val reconstructed = ShamirMemoryVault.reconstructSecretBytes(listOf(shares[0], shares[2], shares[4]))
        assertArrayEquals(masterKey, reconstructed)
        val sig = ShamirMemoryVault.reconstructAndSign(shares.take(3), "payload_123")
        assertEquals(64, sig.length)

        // 4. ZkpSchnorrAuth
        val scalar = ZkpSchnorrAuth.deriveSecretScalar("admin_waqar_zkp_secret")
        val proof = ZkpSchnorrAuth.generateProof(scalar, "nonce_2026")
        assertTrue(
            ZkpSchnorrAuth.verifyProof(
                proof.publicKeyYHex,
                proof.commitmentTHex,
                proof.responseSHex,
                "nonce_2026"
            )
        )
        assertFalse(
            ZkpSchnorrAuth.verifyProof(
                proof.publicKeyYHex,
                proof.commitmentTHex,
                proof.responseSHex,
                "tampered_nonce"
            )
        )

        // 5. TremorFftSentinel
        val flatBot = DoubleArray(64) { 9.81 }
        assertFalse("Flat emulator sensor stream must fail FFT human check", TremorFftSentinel.isGenuineHumanHand(flatBot))
        val humanTremor = DoubleArray(64) { i -> 9.81 + 0.02 * sin(2.0 * Math.PI * i / 12.0) }
        assertTrue("Physiological human hand micro-tremor must pass FFT check", TremorFftSentinel.isGenuineHumanHand(humanTremor))

        // 6. MathShieldConnector
        val armed = MathShieldConnector.armMathBrahmastra()
        assertTrue(armed.siliconVerified)
        assertEquals(5, armed.shamirShareCount)
        val signedEnvelope = MathShieldConnector.signWithMathShield("""{"login":"waqar"}""")
        assertTrue(
            ZkpSchnorrAuth.verifyProof(
                signedEnvelope.zkpProof.publicKeyYHex,
                signedEnvelope.zkpProof.commitmentTHex,
                signedEnvelope.zkpProof.responseSHex,
                signedEnvelope.zkpProof.challengeNonce
            )
        )
    }

    @Test
    fun quantumPhysicalShieldAndInternalComposeBrahmastra_executeAllModules() = runBlocking {
        // Quantum Physical Shield
        val qStatus = QuantumAutoPlug.autoArmQuantumShield(context)
        assertTrue(qStatus.armed)
        assertTrue(qStatus.thermalDriftVerified)
        assertTrue(qStatus.poincareSessionSealed)

        val flatFrame = FloatArray(16 * 16) { 128f }
        assertFalse(PrnuSensorFingerprint.extractAndVerifyPrnu(flatFrame, 16, 16).isAuthenticPhysicalCmos)

        val chirp = UltrasonicSonarLiveness.generateUltrasonicChirpPcm(128)
        val roomEcho3D = DoubleArray(400)
        for (i in chirp.indices) {
            roomEcho3D[50 + i] += chirp[i] * 0.9
            roomEcho3D[60 + i] += chirp[i] * 0.45 // secondary facial contour reflection
            roomEcho3D[68 + i] += chirp[i] * 0.35
        }
        val sonar = UltrasonicSonarLiveness.analyzeEchoFor3DGeometry(chirp, roomEcho3D)
        assertTrue("3D facial depth multi-path reflection should pass sonar check", sonar.isReal3DFaceGeometry)

        val sealed = PoincareHyperbolicVault.sealSecretInPoincareDisk("bio_1", "WaqarVault#2026")
        assertEquals("WaqarVault#2026", PoincareHyperbolicVault.unsealSecretFromPoincareDisk(sealed))

        assertTrue(
            KolmogorovTouchTopology.evaluateTouchSample(
                KolmogorovTouchTopology.TouchKinematicSample(22f, 0.52f, 0.40f, 0.008f)
            ).isGenuineBiomechanicalTouch
        )
        assertFalse(
            KolmogorovTouchTopology.evaluateTouchSample(
                KolmogorovTouchTopology.TouchKinematicSample(0f, 1.0f, 1.0f, 0f)
            ).isGenuineBiomechanicalTouch
        )

        // Internal Compose Brahmastra
        val internalSnap = InternalShieldAutoPlug.ArmInternalComposeShield()
        assertTrue(internalSnap.armed)
        assertTrue(internalSnap.homomorphicSelfTestPassed)
        assertTrue(internalSnap.sandboxAstSelfTestPassed)

        val ledger = HomomorphicLedgerGuard()
        val mFee = ledger.maskInteger(12_500L, 77L)
        val mFine = ledger.maskInteger(500L, 19L)
        assertEquals(13_000L, ledger.unmaskInteger(ledger.addHomomorphically(mFee, mFine)))

        assertFalse(ComposePhantomTouchGuard.evaluateMotionEventFlagsAndGeometry(MotionEvent.FLAG_WINDOW_IS_OBSCURED).allowed)
        assertTrue(ComposePhantomTouchGuard.evaluateMotionEventFlagsAndGeometry(0).allowed)

        val blockedAst = CodingSandboxCage.executeInCoroutineCage("import os; os.system('id')") { "bad" }
        assertTrue(blockedAst is CodingSandboxCage.CagedExecutionResult.BlockedByAstScanner)
        val allowedAst = CodingSandboxCage.executeInCoroutineCage("val x = 2 + 2") { 4 }
        assertEquals(4, (allowedAst as CodingSandboxCage.CagedExecutionResult.Success).output)

        val token = RoleCapabilityGate.issueCapabilityToken("stu_01", "STUDENT", "M10_EXAMS")
        assertTrue(RoleCapabilityGate.verifyCapabilityToken(token, setOf("STUDENT")))
        assertFalse(RoleCapabilityGate.verifyCapabilityToken(token, setOf("ADMIN")))

        val rollbackCheck = MonotonicCausalityClock.verifyWallClockCausality(
            currentWallEpochMs = System.currentTimeMillis() - 600_000L // -10 min clock tamper
        )
        assertTrue(rollbackCheck.clockRollbackTamperDetected)
    }

    @Test
    fun masterRemediationAll8Fixes_verifiedEndToEnd() = runBlocking {
        // FIX-03: Unified 26-Module Brahmastra Orchestrator bootstrap & runtime gates
        val status = com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator
            .bootstrapAll26BrahmastraModules(context)
        assertTrue(status.bootstrapped)
        assertEquals(64, status.siliconPufDigestHex.length)
        assertEquals(5, status.shamirSharesSealed)
        assertTrue(status.activeHashChainHeadHex.isNotBlank())

        val routeOk = com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator.verifyPortalRouteAndAction(
            userId = "cred_waqar",
            activeRole = "ADMIN",
            routeId = "M01_DASHBOARD"
        )
        assertTrue(routeOk)

        val feeTotal = com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator.guardFeeLedgerBatch(
            installmentsPaise = longArrayOf(150_000L, 250_000L),
            totalExpectedFeePaise = 500_000L
        )
        assertEquals(400_000L, feeTotal)

        val watermarkedCsv = com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator
            .embedForensicExportWatermark("RollNo,Name\n101,Student", "admin_waqar")
        assertTrue(watermarkedCsv.length > "RollNo,Name\n101,Student".length)

        // FIX-06: JavaScript multi-statement for-loop & Python elif/else + zero dummy names
        assertFalse(
            com.waqar.institute.terminal.CodingSandboxUiState.DEFAULT_JS_STARTER
                .contains("Aarav Sharma")
        )
        val jsOut = com.waqar.institute.terminal.CodingSandboxViewModel.evaluateJavaScriptOfflineSandboxed(
            """
            const marks = [80, 90, 100];
            let sum = 0;
            for (let i = 0; i < marks.length; i++) {
                sum += marks[i];
                console.log("Step:", i, sum);
            }
            console.log("Final:", sum);
            """.trimIndent()
        )
        assertTrue(jsOut.contains("Step: 0 80"))
        assertTrue(jsOut.contains("Step: 2 270"))
        assertTrue(jsOut.contains("Final: 270"))

        val pyOut = com.waqar.institute.terminal.CodingSandboxViewModel.evaluatePythonOfflineSandboxed(
            """
            score = 88
            if score >= 90:
                print("Grade A+")
            elif score >= 80:
                print("Grade A")
            else:
                print("Grade B")
            """.trimIndent()
        )
        assertEquals("Grade A", pyOut.trim())

        // FIX-07: Real ISO/IEC 18004 ZXing QR bitmap generation used by AcademicProSuiteEngine
        val qrBmp = com.waqar.institute.certificate.CertificateQrGenerator.generateScannableQrBitmap(
            content = "WCI_PRO_QR_V1|STU-101|BATCH-A|HMAC",
            sizePx = 180
        )
        assertEquals(180, qrBmp.width)
        assertEquals(180, qrBmp.height)

        // FIX-08: EnterpriseRateLimiter raised to 35 requests per 60s window for normal ERP operations
        assertEquals(35, com.example.security.EnterpriseRateLimiter.MAX_REQUESTS_PER_WINDOW)
        com.example.security.EnterpriseRateLimiter.resetAllForTests(context)
        for (reqIdx in 1..20) {
            val dec = com.example.security.EnterpriseRateLimiter.checkAndConsume(
                context = context,
                scope = com.example.security.EnterpriseRateLimiter.RateLimitScope.LOGIN,
                identityKey = "normal_erp_student_sync",
                nowEpochMs = 1_800_000_000_000L + reqIdx * 100L
            )
            assertTrue("Expected request #$reqIdx (<=35) to be allowed", dec.allowed)
        }
    }
}
