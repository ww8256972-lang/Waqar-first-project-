package security_brahmastra

/**
 * 6. `security_brahmastra/SecurityBridge.kt`
 * Single Safe Integration Point connecting `HoneypotSentinel`, `CanaryWatermark`,
 * `BehavioralBiometrics`, `HashChainState`, and `PowChallenge`, and persisting
 * `hashChainHead` + behavioral telemetry into `/examResults` and `gradeExamSubmission`.
 */
object SecurityBridge {

    data class ShieldSessionContext(
        val userId: String,
        val role: String,
        val sessionId: String,
        val initializedAtMs: Long,
        val canarySample: String
    )

    data class WrappedExamSubmission(
        val originalPayloadJson: String,
        val hashChainHeadHex: String,
        val hashChainStepCount: Int,
        val powHeaderValue: String,
        val isShadowModeActive: Boolean,
        val behavioralAnomalyCount: Int
    )

    @Volatile
    private var activeContext: ShieldSessionContext? = null
    private var activeHashChain = HashChainState()
    private val behavioralProfiler = BehavioralBiometrics()

    fun initBrahmastraShield(
        userId: String,
        role: String = "STUDENT",
        sessionId: String = "sess_${System.currentTimeMillis()}"
    ): ShieldSessionContext {
        activeHashChain = HashChainState("WCI_CHAIN|$userId|$sessionId")
        val canary = CanaryWatermark.injectTextCanary("EXAM_SESSION_TOKEN", userId)
        val ctx = ShieldSessionContext(
            userId = userId,
            role = role,
            sessionId = sessionId,
            initializedAtMs = System.currentTimeMillis(),
            canarySample = canary
        )
        activeContext = ctx
        return ctx
    }

    fun recordAnsweredQuestionInChain(questionId: String, selectedOption: Int): HashChainState.ChainStep =
        activeHashChain.recordQuestionStep(questionId, selectedOption)

    fun currentHashChainHead(): String = activeHashChain.getCurrentHeadHash()

    /**
     * Builds telemetry metadata persisted to `/examResults` and transmitted to `gradeExamSubmission`.
     */
    fun buildExamTelemetryMetadata(): Map<String, Any> {
        val ctx = activeContext
        return mapOf(
            "hashChainHead" to activeHashChain.getCurrentHeadHash(),
            "hashChainStepCount" to activeHashChain.getRecordedSteps().size,
            "behavioralAnomalyCount" to behavioralProfiler.getAnomalyEvents().size,
            "shadowModeActive" to HoneypotSentinel.isShadowSession,
            "sessionId" to (ctx?.sessionId ?: "uninitialized")
        )
    }

    fun wrapExamSubmission(
        payloadJson: String,
        powDifficulty: Int = 3
    ): WrappedExamSubmission {
        val ctx = activeContext ?: initBrahmastraShield("student_default")
        val pow = PowChallenge.solvePoWChallenge(
            nonce = "${ctx.userId}_${ctx.sessionId}",
            difficulty = powDifficulty
        )
        return WrappedExamSubmission(
            originalPayloadJson = payloadJson,
            hashChainHeadHex = activeHashChain.getCurrentHeadHash(),
            hashChainStepCount = activeHashChain.getRecordedSteps().size,
            powHeaderValue = pow.toHeaderValue(),
            isShadowModeActive = HoneypotSentinel.isShadowSession,
            behavioralAnomalyCount = behavioralProfiler.getAnomalyEvents().size
        )
    }

    fun getBehavioralProfiler(): BehavioralBiometrics = behavioralProfiler
}
