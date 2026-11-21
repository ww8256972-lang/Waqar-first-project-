package com.example.exam

import android.content.Context
import androidx.work.*
import com.example.BuildConfig
import com.example.data.firestore.InstituteRepository
import com.example.data.model.ExamRecord
import com.example.data.model.ExamResult
import com.google.firebase.Timestamp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Answer-Key-Stripped Exam Question (`SanitizedExamQuestion`).
 *
 * Security Guarantee: Contains ONLY question prompt, category, options, and mark weight —
 * NEVER holds `correctOptionIndex` or `explanation` in Compose/ViewModel UI state.
 */
data class SanitizedExamQuestion(
    val id: String,
    val category: String,
    val questionText: String,
    val options: List<String>,
    val marks: Int = 2
)

fun ExamQuestionItem.toSanitized(): SanitizedExamQuestion = SanitizedExamQuestion(
    id = id,
    category = category,
    questionText = questionText,
    options = options,
    marks = marks
)

data class ServerExamSessionInfo(
    val sessionId: String,
    val examId: String,
    val sessionNonce: String,
    val serverStartedAtMs: Long,
    val hardDeadlineAtMs: Long,
    val serverNowMs: Long,
    val isAuthoritativeServerSession: Boolean
)

data class ExamGradingSubmissionRequest(
    val exam: ExamRecord,
    val studentId: String,
    val studentName: String,
    val questionIds: List<String>,
    val selectedAnswers: Map<String, Int>,
    val violationReason: String?,
    val ownerUid: String,
    val sessionNonce: String = ""
)

data class ExamGradingOutcome(
    val rawCorrectCount: Int,
    val totalQuestions: Int,
    val scaledMarks: Int,
    val grade: String,
    val passed: Boolean,
    val remarks: String,
    val gradedByRemoteServer: Boolean,
    val savedResult: ExamResult? = null
)

/**
 * Clean Backend Grading Interface (`ExamGradingGateway`).
 * Routes submission grading through Firebase Cloud Functions protected by App Check + ID Token
 * when configured, falling back gracefully to an ephemeral sealed answer vault when offline.
 */
interface ExamGradingGateway {
    suspend fun gradeSubmission(request: ExamGradingSubmissionRequest): ExamGradingOutcome
}

private data class SealedSessionKeyEntry(
    val maskByte: Byte,
    val maskedCorrectIndices: ByteArray,
    val hmacTagsByIndex: Array<ByteArray>,
    val sessionNonce: ByteArray,
    val questionIndexById: Map<String, Int>,
    val marksByIndex: IntArray
)

/**
 * Ephemeral in-memory vault holding ONLY XOR-masked byte indices and HMAC-SHA256 verification digests
 * keyed with `EXAM_ANSWER_HMAC_PEPPER` + per-session CSPRNG nonce. All sensitive byte arrays are
 * zero-filled (`Arrays.fill(..., 0)`) and evicted immediately upon grading or purge.
 */
private object SealedAnswerKeyVault {
    private val sessions = ConcurrentHashMap<String, SealedSessionKeyEntry>()
    private val rng = SecureRandom()
    private const val FALLBACK_PEPPER = "WCI_EXAM_BLIND_PEPPER_2026_V1"

    private fun sessionKey(examId: String, studentId: String): String =
        "${examId.ifBlank { "exm_default" }}|${studentId.ifBlank { "stu_default" }}"

    private fun resolvePepperString(): String =
        BuildConfig.EXAM_ANSWER_HMAC_PEPPER.trim().ifBlank { FALLBACK_PEPPER }

    private fun computeAnswerHmacTag(
        examId: String,
        studentId: String,
        questionId: String,
        optionIdx: Int,
        sessionNonce: ByteArray
    ): ByteArray {
        val pepperBytes = resolvePepperString().toByteArray(Charsets.UTF_8)
        val combinedKey = ByteArray(pepperBytes.size + sessionNonce.size)
        System.arraycopy(pepperBytes, 0, combinedKey, 0, pepperBytes.size)
        System.arraycopy(sessionNonce, 0, combinedKey, pepperBytes.size, sessionNonce.size)
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(combinedKey, "HmacSHA256"))
            val payload = "${examId.ifBlank { "exm_default" }}|${studentId.ifBlank { "stu_default" }}|$questionId|$optionIdx"
                .toByteArray(Charsets.UTF_8)
            mac.doFinal(payload)
        } finally {
            Arrays.fill(pepperBytes, 0.toByte())
            Arrays.fill(combinedKey, 0.toByte())
        }
    }

    fun computeStudentPaperWatermark(examId: String, studentId: String): String {
        val input = "WM|${examId.ifBlank { "exm_default" }}|${studentId.ifBlank { "stu_default" }}|${resolvePepperString()}"
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return try {
            val hex = digest.take(5).joinToString("") { "%02X".format(it) }
            "WM-$hex"
        } finally {
            Arrays.fill(input, 0.toByte())
            Arrays.fill(digest, 0.toByte())
        }
    }

    fun storeSealedKeys(examId: String, studentId: String, questions: List<ExamQuestionItem>) {
        val key = sessionKey(examId, studentId)
        purge(examId, studentId)
        val mask = ByteArray(1).also { rng.nextBytes(it) }[0]
        val nonce = ByteArray(16).also { rng.nextBytes(it) }
        val masked = ByteArray(questions.size)
        val hmacTags = Array(questions.size) { ByteArray(0) }
        val marks = IntArray(questions.size)
        val idxMap = HashMap<String, Int>(questions.size)
        questions.forEachIndexed { idx, q ->
            idxMap[q.id] = idx
            marks[idx] = q.marks
            masked[idx] = ((q.correctOptionIndex and 0xFF) xor (mask.toInt() and 0xFF)).toByte()
            hmacTags[idx] = computeAnswerHmacTag(examId, studentId, q.id, q.correctOptionIndex, nonce)
        }
        sessions[key] = SealedSessionKeyEntry(mask, masked, hmacTags, nonce, idxMap, marks)
    }

    fun evaluateAndScrub(
        exam: ExamRecord,
        studentId: String,
        questionIds: List<String>,
        selectedAnswers: Map<String, Int>,
        violationReason: String?
    ): ExamGradingOutcome {
        val key = sessionKey(exam.id, studentId)
        val entry = sessions.remove(key)
        var rawCorrect = 0
        var rawScore = 0
        var maxPossible = 0

        if (entry != null) {
            try {
                for (qId in questionIds) {
                    val idx = entry.questionIndexById[qId] ?: continue
                    val qMarks = entry.marksByIndex[idx]
                    maxPossible += qMarks
                    val unmaskedCorrect =
                        (entry.maskedCorrectIndices[idx].toInt() and 0xFF) xor (entry.maskByte.toInt() and 0xFF)
                    val chosen = selectedAnswers[qId]
                    if (chosen != null && chosen == unmaskedCorrect) {
                        val candidateTag = computeAnswerHmacTag(
                            examId = exam.id,
                            studentId = studentId,
                            questionId = qId,
                            optionIdx = chosen,
                            sessionNonce = entry.sessionNonce
                        )
                        val hmacValid = try {
                            MessageDigest.isEqual(entry.hmacTagsByIndex[idx], candidateTag)
                        } finally {
                            Arrays.fill(candidateTag, 0.toByte())
                        }
                        if (hmacValid) {
                            rawCorrect++
                            rawScore += qMarks
                        }
                    }
                }
            } finally {
                Arrays.fill(entry.maskedCorrectIndices, 0.toByte())
                Arrays.fill(entry.marksByIndex, 0)
                Arrays.fill(entry.sessionNonce, 0.toByte())
                entry.hmacTagsByIndex.forEach { tag -> Arrays.fill(tag, 0.toByte()) }
            }
        }

        val totalQs = questionIds.size.coerceAtLeast(1)
        val scaledMarks = if (maxPossible > 0) {
            ((rawScore.toDouble() / maxPossible.toDouble()) * exam.totalMarks.toDouble()).toInt()
        } else 0
        val pct = if (exam.totalMarks > 0) (scaledMarks * 100) / exam.totalMarks else 0
        val grade = when {
            pct >= 90 -> "A+"
            pct >= 80 -> "A"
            pct >= 70 -> "B+"
            pct >= 60 -> "B"
            pct >= 40 -> "C"
            else -> "F"
        }
        val passed = scaledMarks >= exam.passingMarks
        val watermark = computeStudentPaperWatermark(exam.id, studentId)
        val remarksText = buildString {
            append("Answered ${selectedAnswers.size}/${questionIds.size} questions ($pct%).")
            if (!violationReason.isNullOrBlank()) {
                append(" [Auto-Submitted: $violationReason | $watermark]")
            } else {
                append(" [Verified Proctored CBT • $rawCorrect/$totalQs Correct | HMAC-$watermark]")
            }
        }
        return ExamGradingOutcome(
            rawCorrectCount = rawCorrect,
            totalQuestions = questionIds.size,
            scaledMarks = scaledMarks.coerceIn(0, exam.totalMarks),
            grade = grade,
            passed = passed,
            remarks = remarksText.take(480),
            gradedByRemoteServer = false
        )
    }

    fun purge(examId: String, studentId: String) {
        val removed = sessions.remove(sessionKey(examId, studentId)) ?: return
        Arrays.fill(removed.maskedCorrectIndices, 0.toByte())
        Arrays.fill(removed.marksByIndex, 0)
        Arrays.fill(removed.sessionNonce, 0.toByte())
        removed.hmacTagsByIndex.forEach { tag -> Arrays.fill(tag, 0.toByte()) }
    }
}

class FirebaseCloudExamGradingGateway(
    private val gradingEndpointUrl: String = BuildConfig.EXAM_GRADING_ENDPOINT_URL.trim(),
    private val context: Context? = null
) : ExamGradingGateway {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun gradeSubmission(request: ExamGradingSubmissionRequest): ExamGradingOutcome {
        val cleanUrl = gradingEndpointUrl.trim()
        val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (!isRobolectric && (cleanUrl.startsWith("https://", ignoreCase = true) || cleanUrl.startsWith("http://", ignoreCase = true))) {
            val remoteOutcome = tryGradeRemotely(request)
            if (remoteOutcome != null) {
                SealedAnswerKeyVault.purge(request.exam.id, request.studentId)
                return remoteOutcome
            }
        }
        return SealedAnswerKeyVault.evaluateAndScrub(
            exam = request.exam,
            studentId = request.studentId,
            questionIds = request.questionIds,
            selectedAnswers = request.selectedAnswers,
            violationReason = request.violationReason
        )
    }

    private suspend fun tryGradeRemotely(request: ExamGradingSubmissionRequest): ExamGradingOutcome? =
        withContext(Dispatchers.IO) {
            runCatching {
                val appCheckToken = withTimeoutOrNull(2_500L) {
                    FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
                }?.takeIf { it.isNotBlank() }

                val idToken = withTimeoutOrNull(2_500L) {
                    FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
                }?.takeIf { it.isNotBlank() } ?: return@runCatching null

                val answersJson = JSONObject()
                request.selectedAnswers.forEach { (k, v) -> answersJson.put(k, v) }

                val watermark = computeWatermarkForRemote(request.exam.id, request.studentId)
                val telemetry = security_brahmastra.SecurityBridge.buildExamTelemetryMetadata()
                val payload = JSONObject().apply {
                    put("examId", request.exam.id)
                    put("examTitle", request.exam.title)
                    put("studentId", request.studentId)
                    put("studentName", request.studentName)
                    put("totalMarks", request.exam.totalMarks)
                    put("passingMarks", request.exam.passingMarks)
                    put("paperWatermark", watermark)
                    put("answersByMasterQId", answersJson)
                    put("answers", answersJson)
                    put("hashChainHead", telemetry["hashChainHead"]?.toString().orEmpty())
                    put("behavioralAnomalyCount", (telemetry["behavioralAnomalyCount"] as? Number)?.toInt() ?: 0)
                    if (request.sessionNonce.isNotBlank()) {
                        put("sessionNonce", request.sessionNonce)
                    }
                    if (!request.violationReason.isNullOrBlank()) {
                        put("violationReason", request.violationReason)
                    }
                }.toString()

                val reqBuilder = Request.Builder()
                    .url(gradingEndpointUrl)
                    .header("Authorization", "Bearer $idToken")
                    .header("X-WCI-Paper-Watermark", watermark)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))

                if (appCheckToken != null) {
                    reqBuilder.header("X-Firebase-AppCheck", appCheckToken)
                }

                com.waqar.institute.security.HardwareDpopRequestSigner.attachDpopHeaders(
                    builder = reqBuilder,
                    context = context,
                    httpMethod = "POST",
                    url = gradingEndpointUrl,
                    rawBody = payload
                )

                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val bodyStr = resp.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val baseRemarks = json.optString("remarks", "Verified Server-Graded Proctored CBT")
                    val stampedRemarks = if (baseRemarks.contains(watermark)) baseRemarks else "$baseRemarks [$watermark]"
                    ExamGradingOutcome(
                        rawCorrectCount = json.optInt("rawCorrectCount", json.optInt("correctCount", 0)),
                        totalQuestions = json.optInt("totalQuestions", request.questionIds.size),
                        scaledMarks = json.optInt("marksObtained", 0).coerceIn(0, request.exam.totalMarks),
                        grade = json.optString("grade", "B"),
                        passed = json.optBoolean("passed", false),
                        remarks = stampedRemarks.take(480),
                        gradedByRemoteServer = true
                    )
                }
            }.getOrNull()
        }

    private fun computeWatermarkForRemote(examId: String, studentId: String): String =
        SealedAnswerKeyVault.computeStudentPaperWatermark(examId, studentId)
}

/**
 * Enterprise Examination Repository (`ExamRepository.kt`).
 *
 * Connects the 156-question `QuestionBank` & Fisher-Yates Seed Shuffler with:
 * - Admin Exam Scheduling (`scheduleNewOnlineExam`)
 * - Student Exam Attempt Submission (`submitOnlineExamAttempt`)
 * - Offline SQLCipher/SharedPreferences Encrypted Answer Caching (`cacheAnswerLocally`, `getCachedAnswers`)
 * - WorkManager Background Cloud Sync (`ExamSubmissionSyncWorker`)
 */
class ExamRepository(
    private val context: Context,
    private val instituteRepository: InstituteRepository,
    private val gradingGateway: ExamGradingGateway = FirebaseCloudExamGradingGateway(context = context)
) {
    private val startSessionEndpointUrl =
        "https://asia-east1-gen-lang-client-0309263247.cloudfunctions.net/startExamSession"

    private val sessionHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val prefs by lazy {
        com.example.security.HardwareKeystoreVault.openEncryptedPreferences(context)
    }

    /**
     * Layer 2 — Requests an authoritative server-enforced exam start timestamp, hard deadline,
     * and single-use `sessionNonce` from `startExamSession` (`asia-east1`), protected by
     * Firebase ID token + App Check + Hardware DPoP. Falls back gracefully offline/in Robolectric.
     */
    suspend fun startAuthoritativeExamSession(
        examId: String,
        durationSeconds: Int
    ): ServerExamSessionInfo = withContext(Dispatchers.IO) {
        val safeDurationSec = durationSeconds.coerceIn(10, 360 * 60)
        val localNow = TamperProofExamClock.currentTrustedEpochMs()
        val localFallback = ServerExamSessionInfo(
            sessionId = "${examId}_local",
            examId = examId,
            sessionNonce = "local_${UUID.randomUUID().toString().replace("-", "")}",
            serverStartedAtMs = localNow,
            hardDeadlineAtMs = localNow + (safeDurationSec * 1_000L),
            serverNowMs = localNow,
            isAuthoritativeServerSession = false
        )
        val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (isRobolectric) return@withContext localFallback

        runCatching {
            val idToken = withTimeoutOrNull(3_000L) {
                FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            }?.takeIf { it.isNotBlank() } ?: return@runCatching localFallback

            val appCheckToken = withTimeoutOrNull(3_000L) {
                FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
            }?.takeIf { it.isNotBlank() }

            val reqBody = JSONObject().apply {
                put("examId", examId)
                put("durationSeconds", safeDurationSec)
            }.toString()

            val reqBuilder = Request.Builder()
                .url(startSessionEndpointUrl)
                .header("Authorization", "Bearer $idToken")
                .post(reqBody.toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (appCheckToken != null) {
                reqBuilder.header("X-Firebase-AppCheck", appCheckToken)
            }

            com.waqar.institute.security.HardwareDpopRequestSigner.attachDpopHeaders(
                builder = reqBuilder,
                context = context,
                httpMethod = "POST",
                url = startSessionEndpointUrl,
                rawBody = reqBody
            )

            sessionHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching localFallback
                val json = JSONObject(resp.body?.string().orEmpty())
                val nonce = json.optString("sessionNonce", "").trim()
                val startedAtMs = json.optLong("serverStartedAtMs", localNow)
                val hardDeadlineAtMs = json.optLong("hardDeadlineAtMs", startedAtMs + safeDurationSec * 1_000L)
                val serverNowMs = json.optLong("serverNowMs", startedAtMs)
                if (nonce.isBlank()) return@runCatching localFallback
                TamperProofExamClock.anchorAuthoritativeServerDeadline(serverNowMs, hardDeadlineAtMs)
                ServerExamSessionInfo(
                    sessionId = json.optString("sessionId", "${examId}_server"),
                    examId = examId,
                    sessionNonce = nonce,
                    serverStartedAtMs = startedAtMs,
                    hardDeadlineAtMs = hardDeadlineAtMs,
                    serverNowMs = serverNowMs,
                    isAuthoritativeServerSession = true
                )
            }
        }.getOrDefault(localFallback)
    }

    fun generateShuffledExamPaper(
        studentId: String,
        examId: String,
        courseName: String,
        questionCount: Int = 50
    ): List<ExamQuestionItem> {
        return QuestionBank.selectAndShuffleQuestionsForStudent(
            studentId = studentId.ifBlank { "stu_default" },
            examId = examId.ifBlank { "exm_default" },
            courseFilter = courseName,
            targetCount = questionCount
        )
    }

    fun generateShuffledPaperForExam(
        studentId: String,
        exam: ExamRecord,
        fallbackQuestionCount: Int = 50
    ): List<ExamQuestionItem> {
        val customBank = PdfMcqBankParser.parseCustomExamBank(exam.questionsJson, exam.courseName)
        if (customBank.questions.isNotEmpty()) {
            return QuestionBank.selectUniqueQuestionsForStudent(
                pool = customBank.questions,
                studentId = studentId.ifBlank { "stu_default" },
                examId = exam.id.ifBlank { "exm_default" },
                targetCount = customBank.perStudentCount.coerceIn(1, customBank.questions.size)
            )
        }
        return emptyList()
    }

    /**
     * Prepares the per-student shuffled CBT paper, seals answer keys into `SealedAnswerKeyVault`
     * (never returned to UI or ViewModel state), and returns only `SanitizedExamQuestion` items.
     */
    fun prepareSanitizedPaperForExam(
        studentId: String,
        exam: ExamRecord
    ): List<SanitizedExamQuestion> {
        val rawShuffled = generateShuffledPaperForExam(
            studentId = studentId,
            exam = exam
        )
        if (rawShuffled.isEmpty()) return emptyList()
        ActiveExamAntiCheatGuard.registerActiveExamSession(
            studentId = studentId,
            questions = rawShuffled
        )
        SealedAnswerKeyVault.storeSealedKeys(
            examId = exam.id,
            studentId = studentId,
            questions = rawShuffled
        )
        return rawShuffled.map { it.toSanitized() }
    }

    fun computeStudentPaperWatermark(examId: String, studentId: String): String =
        SealedAnswerKeyVault.computeStudentPaperWatermark(examId, studentId)

    fun purgeAnswerKeys(examId: String, studentId: String) {
        SealedAnswerKeyVault.purge(examId, studentId)
    }

    /**
     * Caches the student's selected option index locally after every single tap
     * so zero progress is lost if the battery dies or internet drops mid-exam.
     */
    fun cacheAnswerLocally(examId: String, studentId: String, questionId: String, selectedOption: Int) {
        val key = "ans_${examId}_${studentId}_$questionId"
        prefs.edit().putInt(key, selectedOption).apply()
    }

    fun getCachedAnswers(examId: String, studentId: String, questionIds: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (qId in questionIds) {
            val key = "ans_${examId}_${studentId}_$qId"
            if (prefs.contains(key)) {
                map[qId] = prefs.getInt(key, -1)
            }
        }
        return map
    }

    fun clearCachedAnswers(examId: String, studentId: String, questionIds: List<String>) {
        val editor = prefs.edit()
        for (qId in questionIds) {
            editor.remove("ans_${examId}_${studentId}_$qId")
        }
        editor.apply()
    }

    suspend fun scheduleOnlineExam(
        code: String,
        title: String,
        courseName: String,
        batchName: String,
        examDateIso: String,
        durationMinutes: Int,
        totalMarks: Int,
        passingMarks: Int,
        ownerUid: String,
        batchId: String = "",
        startTime24h: String = "10:00",
        scheduledStartEpochMs: Long = 0L,
        durationSeconds: Int = 0,
        createdByRole: String = "admin"
    ): ExamRecord {
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val effectiveSecs = if (durationSeconds > 0) durationSeconds.coerceAtLeast(10) else durationMinutes.coerceIn(1, 360) * 60
        val effectiveMins = if (durationSeconds > 0) ((effectiveSecs + 59) / 60).coerceAtLeast(1) else durationMinutes.coerceIn(1, 360)
        val exam = ExamRecord(
            id = "exm_${UUID.randomUUID().toString().take(8)}",
            title = title.trim(),
            courseId = "crs_${courseName.lowercase().replace(Regex("[^a-z0-9]"), "").take(8)}",
            courseName = courseName.trim(),
            batchId = batchId.trim(),
            batchName = batchName.trim().ifBlank { "All Batches" },
            examType = "MCQ_ONLINE",
            durationMinutes = effectiveMins,
            durationSeconds = effectiveSecs,
            startTime24h = startTime24h.trim().ifBlank { "10:00" },
            scheduledStartEpochMs = scheduledStartEpochMs,
            autoAssignLabPcSeats = true,
            createdByRole = createdByRole.trim().ifBlank { "admin" },
            totalMarks = totalMarks.coerceAtLeast(1),
            passingMarks = passingMarks.coerceIn(1, totalMarks.coerceAtLeast(1)),
            scheduledDateIso = examDateIso.trim(),
            questionsJson = "[]",
            status = "SCHEDULED",
            ownerUid = ownerUid,
            createdAt = safeNow
        )
        instituteRepository.saveExam(exam)
        return exam
    }

    /**
     * [A5 + B2] Persists canonical Master Answer Keys ONLY into Admin/Server-restricted
     * `/examAnswerKeys/{examId}` so students never receive plaintext answer keys.
     */
    suspend fun saveMasterMcqBankAndAnswerKeyVault(
        examId: String,
        examTitle: String,
        batchId: String,
        masterQuestions: List<com.waqar.institute.exam.MasterMcqQuestion>,
        ownerUid: String
    ) {
        if (masterQuestions.isEmpty()) return
        val answerKeyMap = com.waqar.institute.exam.ChatGptBulkMcqFileParser.extractAnswerKeyMap(masterQuestions)
        runCatching {
            instituteRepository.saveExamAnswerKey(
                examId = examId,
                examTitle = examTitle,
                batchId = batchId,
                answersByMasterQId = answerKeyMap,
                ownerUid = ownerUid
            )
        }
    }

    suspend fun submitStudentExamResult(
        exam: ExamRecord,
        studentId: String,
        studentName: String,
        questions: List<ExamQuestionItem>,
        selectedAnswers: Map<String, Int>,
        violationReason: String? = null,
        ownerUid: String
    ): ExamResult {
        var rawScore = 0
        var maxPossible = 0
        for (q in questions) {
            maxPossible += q.marks
            val chosen = selectedAnswers[q.id]
            if (chosen != null && chosen == q.correctOptionIndex) {
                rawScore += q.marks
            }
        }
        val scaledMarks = if (maxPossible > 0) {
            ((rawScore.toDouble() / maxPossible.toDouble()) * exam.totalMarks.toDouble()).toInt()
        } else 0

        val pct = if (exam.totalMarks > 0) (scaledMarks * 100) / exam.totalMarks else 0
        val grade = when {
            pct >= 90 -> "A+"
            pct >= 80 -> "A"
            pct >= 70 -> "B+"
            pct >= 60 -> "B"
            pct >= 40 -> "C"
            else -> "F"
        }
        val passed = scaledMarks >= exam.passingMarks
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val remarksText = buildString {
            append("Answered ${selectedAnswers.size}/${questions.size} questions ($pct%).")
            if (!violationReason.isNullOrBlank()) {
                append(" [Auto-Submitted: $violationReason]")
            } else {
                append(" [Verified Proctored CBT]")
            }
        }

        val exactPct = if (exam.totalMarks > 0) (scaledMarks.toDouble() * 100.0) / exam.totalMarks.toDouble() else 0.0
        val roundedPctLabel = "${(kotlin.math.round(exactPct * 100.0) / 100.0)}%"
        val correctCount = questions.count { q -> selectedAnswers[q.id] == q.correctOptionIndex }
        val effectiveOwnerUid = runCatching {
            FirebaseAuth.getInstance().currentUser?.uid
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ownerUid.ifBlank { studentId.ifBlank { "stu_online" } }
        val result = ExamResult(
            id = "${exam.id}_${effectiveOwnerUid}",
            examId = exam.id,
            examTitle = exam.title,
            studentId = studentId.ifBlank { effectiveOwnerUid },
            studentName = studentName.ifBlank { "WCI Student" },
            marksObtained = scaledMarks.coerceIn(0, exam.totalMarks),
            totalMarks = exam.totalMarks,
            exactPercentage = exactPct,
            formattedPercentage = roundedPctLabel,
            correctCount = correctCount,
            totalQuestions = questions.size,
            grade = grade,
            rank = 1,
            status = if (passed) "PASSED" else "FAILED",
            remarks = remarksText.take(480),
            ownerUid = effectiveOwnerUid,
            createdAt = safeNow
        )

        runCatching {
            instituteRepository.saveExamResult(result)
            clearCachedAnswers(exam.id, studentId, questions.map { it.id })
        }.onFailure {
            enqueueOfflineResultSync(result)
        }
        return result
    }

    /**
     * Grades answers via `ExamGradingGateway` (server Cloud Function or ephemeral sealed vault)
     * without ever holding plaintext `correctOptionIndex` in UI state, then persists & scrubs.
     */
    suspend fun submitSanitizedExamResult(
        exam: ExamRecord,
        studentId: String,
        studentName: String,
        sanitizedQuestions: List<SanitizedExamQuestion>,
        selectedAnswers: Map<String, Int>,
        isPreviewOnly: Boolean = false,
        violationReason: String? = null,
        ownerUid: String,
        sessionNonce: String = ""
    ): ExamGradingOutcome {
        val questionIds = sanitizedQuestions.map { it.id }
        val outcome = gradingGateway.gradeSubmission(
            ExamGradingSubmissionRequest(
                exam = exam,
                studentId = studentId,
                studentName = studentName,
                questionIds = questionIds,
                selectedAnswers = selectedAnswers,
                violationReason = violationReason,
                ownerUid = ownerUid,
                sessionNonce = sessionNonce
            )
        )
        if (isPreviewOnly) {
            clearCachedAnswers(exam.id, studentId, questionIds)
            return outcome
        }

        val safeNow = Timestamp(Date(TamperProofExamClock.currentTrustedEpochMs() - 1000L))
        val exactPct = if (exam.totalMarks > 0) {
            (outcome.scaledMarks.toDouble() * 100.0) / exam.totalMarks.toDouble()
        } else 0.0
        val roundedPctLabel = "${(kotlin.math.round(exactPct * 100.0) / 100.0)}%"
        val hashHead = security_brahmastra.SecurityBridge.currentHashChainHead()
        val effectiveOwnerUid = runCatching {
            FirebaseAuth.getInstance().currentUser?.uid
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ownerUid.ifBlank { studentId.ifBlank { "stu_online" } }
        val result = ExamResult(
            id = if (outcome.gradedByRemoteServer) "${exam.id}_${studentId.ifBlank { effectiveOwnerUid }}" else "${exam.id}_${effectiveOwnerUid}",
            examId = exam.id,
            examTitle = exam.title,
            studentId = studentId.ifBlank { effectiveOwnerUid },
            studentName = studentName.ifBlank { "WCI Student" },
            marksObtained = outcome.scaledMarks.coerceIn(0, exam.totalMarks),
            totalMarks = exam.totalMarks,
            exactPercentage = exactPct,
            formattedPercentage = roundedPctLabel,
            correctCount = outcome.rawCorrectCount,
            totalQuestions = sanitizedQuestions.size,
            grade = outcome.grade,
            rank = 1,
            status = if (outcome.passed) "PASSED" else "FAILED",
            remarks = outcome.remarks,
            ownerUid = effectiveOwnerUid,
            createdAt = safeNow,
            hashChainHead = hashHead
        )

        if (outcome.gradedByRemoteServer) {
            // Authoritative result already persisted server-side by gradeExamSubmission Admin SDK
            clearCachedAnswers(exam.id, studentId, questionIds)
        } else {
            runCatching {
                instituteRepository.saveExamResult(result)
                clearCachedAnswers(exam.id, studentId, questionIds)
            }.onFailure {
                enqueueOfflineResultSync(result)
            }
        }
        return outcome.copy(savedResult = result)
    }

    /**
     * [C3] Batch Ranker Leaderboard & Question-Wise Wrong-Answer Analytics for Teachers & Admins.
     */
    data class BatchLeaderboardEntry(
        val rank: Int,
        val podiumBadge: String, // "GOLD #1", "SILVER #2", "BRONZE #3", or "#N"
        val result: ExamResult,
        val exactPercentage: Double,
        val formattedPercentage: String
    )

    data class MissedQuestionStat(
        val questionId: String,
        val questionText: String,
        val wrongAttempts: Int,
        val totalAttempts: Int,
        val missRatePercent: Int
    )

    data class BatchExamAnalyticsReport(
        val examId: String,
        val leaderboard: List<BatchLeaderboardEntry>,
        val topMissedQuestions: List<MissedQuestionStat>,
        val passRatePercent: Int,
        val averagePercentage: Double
    )

    companion object {
        /**
         * [C3] Ranks all students for `examId` by `marksObtained DESC` (assigning Top-3 podium badges)
         * and computes the Top-5 most-missed questions from `submissions` (`studentId -> (qId -> isCorrect)`).
         */
        fun computeBatchLeaderboardAndWrongQuestionStats(
            examId: String,
            results: List<ExamResult>,
            submissions: List<Map<String, Boolean>> = emptyList(),
            questionTextById: Map<String, String> = emptyMap()
        ): BatchExamAnalyticsReport {
            val filtered = if (examId.isBlank()) results else results.filter { it.examId == examId }
            val sorted = filtered.sortedWith(
                compareByDescending<ExamResult> { it.marksObtained }
                    .thenByDescending { it.exactPercentage }
                    .thenBy { it.studentName.lowercase() }
            )
            val leaderboard = sorted.mapIndexed { idx, res ->
                val rank = idx + 1
                val pct = if (res.exactPercentage > 0.0 || res.marksObtained == 0) {
                    res.exactPercentage
                } else {
                    (res.marksObtained.toDouble() * 100.0) / res.totalMarks.coerceAtLeast(1).toDouble()
                }
                val pctStr = res.formattedPercentage.ifBlank {
                    "${(kotlin.math.round(pct * 100.0) / 100.0)}%"
                }
                val badge = when (rank) {
                    1 -> "GOLD #1"
                    2 -> "SILVER #2"
                    3 -> "BRONZE #3"
                    else -> "#$rank"
                }
                BatchLeaderboardEntry(
                    rank = rank,
                    podiumBadge = badge,
                    result = res.copy(rank = rank, exactPercentage = pct, formattedPercentage = pctStr),
                    exactPercentage = pct,
                    formattedPercentage = pctStr
                )
            }

            val totalMap = LinkedHashMap<String, Int>()
            val wrongMap = LinkedHashMap<String, Int>()
            for (submission in submissions) {
                for ((qId, isCorrect) in submission) {
                    totalMap[qId] = (totalMap[qId] ?: 0) + 1
                    if (!isCorrect) {
                        wrongMap[qId] = (wrongMap[qId] ?: 0) + 1
                    }
                }
            }

            val topMissed = totalMap.entries
                .map { (qId, totalAttempts) ->
                    val wrong = wrongMap[qId] ?: 0
                    val missPct = if (totalAttempts > 0) ((wrong * 100) / totalAttempts) else 0
                    MissedQuestionStat(
                        questionId = qId,
                        questionText = questionTextById[qId] ?: "Question $qId",
                        wrongAttempts = wrong,
                        totalAttempts = totalAttempts,
                        missRatePercent = missPct
                    )
                }
                .filter { it.wrongAttempts > 0 }
                .sortedWith(
                    compareByDescending<MissedQuestionStat> { it.wrongAttempts }
                        .thenByDescending { it.missRatePercent }
                )
                .take(5)

            val passedCount = leaderboard.count { it.result.status.equals("PASSED", ignoreCase = true) }
            val passRate = if (leaderboard.isNotEmpty()) (passedCount * 100) / leaderboard.size else 0
            val avgPct = if (leaderboard.isNotEmpty()) {
                kotlin.math.round((leaderboard.sumOf { it.exactPercentage } / leaderboard.size) * 100.0) / 100.0
            } else 0.0

            return BatchExamAnalyticsReport(
                examId = examId,
                leaderboard = leaderboard,
                topMissedQuestions = topMissed,
                passRatePercent = passRate,
                averagePercentage = avgPct
            )
        }
    }

    private fun enqueueOfflineResultSync(result: ExamResult) {
        val data = workDataOf(
            "id" to result.id,
            "examId" to result.examId,
            "examTitle" to result.examTitle,
            "studentId" to result.studentId,
            "studentName" to result.studentName,
            "marksObtained" to result.marksObtained,
            "totalMarks" to result.totalMarks,
            "grade" to result.grade,
            "status" to result.status,
            "remarks" to result.remarks,
            "ownerUid" to result.ownerUid
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<ExamSubmissionSyncWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync_exam_${result.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}

class ExamSubmissionSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repo = InstituteRepository.create(applicationContext)
        val id = inputData.getString("id") ?: return Result.failure()
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val examResult = ExamResult(
            id = id,
            examId = inputData.getString("examId").orEmpty(),
            examTitle = inputData.getString("examTitle").orEmpty(),
            studentId = inputData.getString("studentId").orEmpty(),
            studentName = inputData.getString("studentName").orEmpty(),
            marksObtained = inputData.getInt("marksObtained", 0),
            totalMarks = inputData.getInt("totalMarks", 100),
            grade = inputData.getString("grade").orEmpty().ifBlank { "B" },
            rank = 1,
            status = inputData.getString("status").orEmpty().ifBlank { "PASSED" },
            remarks = inputData.getString("remarks").orEmpty(),
            ownerUid = inputData.getString("ownerUid").orEmpty().ifBlank { "cred_admin" },
            createdAt = safeNow
        )
        return runCatching {
            repo.saveExamResult(examResult)
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
