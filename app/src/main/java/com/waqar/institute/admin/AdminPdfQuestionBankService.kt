package com.waqar.institute.admin

import android.content.Context
import android.net.Uri
import com.example.data.firestore.InstituteRepository
import com.example.data.model.ExamRecord
import com.example.exam.ExamQuestionItem
import com.example.exam.PdfMcqBankParser
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Admin Module Service for Parsing Uploaded PDF Question Banks & Transforming into the
 * Normalized Firestore Quiz/Exam Engine Schema (`AdminPdfQuestionBankService.kt`).
 *
 * Capabilities:
 * 1. Parses uploaded PDF (FlateDecode stream text or Vision OCR fallback) and text question banks.
 * 2. Extracts serial-numbered questions, 4 option-wise choices (A/B/C/D), and the Real Answer key.
 * 3. Normalizes extracted questions into the strict Firestore `exams/{examId}` document schema
 *    validated by `isValidExamPaper` in `firestore.rules` (enforcing length bounds, mark totals,
 *    and per-student randomized subset count).
 * 4. Persists the normalized question bank directly to Cloud Firestore via `InstituteRepository`.
 */
object AdminPdfQuestionBankService {

    data class NormalizedQuestionSchema(
        val id: String,
        val serialNumber: Int,
        val category: String,
        val questionText: String,
        val options: List<String>,
        val correctOptionIndex: Int,
        val marks: Int = 2
    )

    data class NormalizedFirestoreExamSchema(
        val id: String,
        val title: String,
        val courseId: String,
        val courseName: String,
        val batchName: String,
        val examType: String,
        val durationMinutes: Int,
        val totalMarks: Int,
        val passingMarks: Int,
        val scheduledDateIso: String,
        val questionsJson: String,
        val status: String,
        val published: Boolean,
        val ownerUid: String,
        val createdAt: Timestamp,
        val totalPoolSize: Int,
        val perStudentQuestionCount: Int,
        val extractionMethod: String,
        val normalizedQuestions: List<NormalizedQuestionSchema>
    ) {
        /**
         * Returns the exact Firestore document map compliant with `isValidExamPaper` in `firestore.rules`.
         */
        fun toFirestoreDocumentMap(): Map<String, Any> = linkedMapOf(
            "id" to id,
            "title" to title,
            "courseId" to courseId,
            "courseName" to courseName,
            "batchName" to batchName,
            "examType" to examType,
            "durationMinutes" to durationMinutes,
            "totalMarks" to totalMarks,
            "passingMarks" to passingMarks,
            "scheduledDateIso" to scheduledDateIso,
            "questionsJson" to questionsJson,
            "status" to status,
            "published" to published,
            "ownerUid" to ownerUid,
            "createdAt" to createdAt
        )

        /**
         * Converts this normalized schema into an `ExamRecord` for `InstituteRepository.saveExam(...)`.
         */
        fun toExamRecord(): ExamRecord = ExamRecord(
            id = id,
            title = title,
            courseId = courseId,
            courseName = courseName,
            batchName = batchName,
            examType = examType,
            durationMinutes = durationMinutes,
            totalMarks = totalMarks,
            passingMarks = passingMarks,
            scheduledDateIso = scheduledDateIso,
            questionsJson = questionsJson,
            status = status,
            ownerUid = ownerUid,
            createdAt = createdAt
        )
    }

    /**
     * Parses an uploaded PDF Uri from the Admin/Teacher portal and transforms it into the
     * normalized Firestore schema required by the proctored quiz engine.
     */
    suspend fun parseUploadedPdfToFirestoreSchema(
        context: Context,
        pdfUri: Uri,
        examTitle: String,
        courseId: String = "",
        courseName: String,
        batchName: String,
        durationMinutes: Int = 60,
        marksPerQuestion: Int = 2,
        passingPercentage: Int = 40,
        requestedPerStudentCount: Int? = null,
        scheduledDateIso: String = currentDateIso(),
        ownerUid: String = "admin_root"
    ): Result<NormalizedFirestoreExamSchema> = withContext(Dispatchers.IO) {
        runCatching {
            val parsedResult = PdfMcqBankParser.extractMcqsFromDocumentUri(
                context = context,
                uri = pdfUri,
                defaultCategory = courseName.ifBlank { "General" }
            )
            if (parsedResult.questions.isEmpty()) {
                val warn = parsedResult.warnings.firstOrNull()
                    ?: "No valid serial-wise MCQ questions found in uploaded PDF."
                throw IllegalArgumentException(warn)
            }

            transformQuestionsToFirestoreSchema(
                rawQuestions = parsedResult.questions,
                examTitle = examTitle,
                courseId = courseId,
                courseName = courseName,
                batchName = batchName,
                durationMinutes = durationMinutes,
                marksPerQuestion = marksPerQuestion,
                passingPercentage = passingPercentage,
                requestedPerStudentCount = requestedPerStudentCount ?: parsedResult.questionsPerStudent,
                scheduledDateIso = scheduledDateIso,
                ownerUid = ownerUid,
                extractionMethod = parsedResult.extractionMethod
            )
        }
    }

    /**
     * Parses raw PDF bytes directly (useful for background services and unit testing) and
     * normalizes into the Firestore quiz engine schema.
     */
    fun parsePdfBytesToFirestoreSchema(
        pdfBytes: ByteArray,
        examTitle: String,
        courseId: String = "",
        courseName: String,
        batchName: String,
        durationMinutes: Int = 60,
        marksPerQuestion: Int = 2,
        passingPercentage: Int = 40,
        requestedPerStudentCount: Int? = null,
        scheduledDateIso: String = currentDateIso(),
        ownerUid: String = "admin_root"
    ): Result<NormalizedFirestoreExamSchema> = runCatching {
        val isPdfHeader = pdfBytes.size >= 4 &&
            pdfBytes[0] == '%'.code.toByte() &&
            pdfBytes[1] == 'P'.code.toByte() &&
            pdfBytes[2] == 'D'.code.toByte() &&
            pdfBytes[3] == 'F'.code.toByte()

        val extractedText = if (isPdfHeader) {
            PdfMcqBankParser.extractTextFromPdfBytes(pdfBytes)
        } else {
            String(pdfBytes, Charsets.UTF_8)
        }

        val questions = PdfMcqBankParser.parseSerialMcqText(
            rawText = extractedText,
            defaultCategory = courseName.ifBlank { "General" }
        )
        require(questions.isNotEmpty()) {
            "No valid MCQ questions could be extracted from the provided PDF bytes."
        }

        transformQuestionsToFirestoreSchema(
            rawQuestions = questions,
            examTitle = examTitle,
            courseId = courseId,
            courseName = courseName,
            batchName = batchName,
            durationMinutes = durationMinutes,
            marksPerQuestion = marksPerQuestion,
            passingPercentage = passingPercentage,
            requestedPerStudentCount = requestedPerStudentCount
                ?: PdfMcqBankParser.suggestPerStudentCount(questions.size),
            scheduledDateIso = scheduledDateIso,
            ownerUid = ownerUid,
            extractionMethod = if (isPdfHeader) "PDF_TEXT_STREAM" else "RAW_TEXT"
        )
    }

    /**
     * Normalizes extracted `ExamQuestionItem` objects into the strict Firestore `exams/{examId}` schema.
     */
    fun transformQuestionsToFirestoreSchema(
        rawQuestions: List<ExamQuestionItem>,
        examTitle: String,
        courseId: String = "",
        courseName: String,
        batchName: String,
        durationMinutes: Int = 60,
        marksPerQuestion: Int = 2,
        passingPercentage: Int = 40,
        requestedPerStudentCount: Int? = null,
        scheduledDateIso: String = currentDateIso(),
        ownerUid: String = "admin_root",
        extractionMethod: String = "PDF_TEXT_STREAM",
        examId: String = "exm_pdf_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): NormalizedFirestoreExamSchema {
        require(rawQuestions.isNotEmpty()) { "Question bank cannot be empty." }

        val safeCourseName = courseName.trim().ifBlank { "ADCA Diploma" }.take(120)
        val safeTitle = examTitle.trim().ifBlank { "$safeCourseName CBT Assessment" }.take(140)
        val safeBatchName = batchName.trim().ifBlank { "All Batches" }.take(100)
        val safeMarksPerQuestion = marksPerQuestion.coerceIn(1, 20)

        val normalizedQuestions = rawQuestions.mapIndexed { idx, item ->
            val serial = idx + 1
            val cleanOptions = item.options
                .map { it.trim().ifBlank { "Option" } }
                .take(4)
                .toMutableList()
            while (cleanOptions.size < 4) {
                cleanOptions.add("Option ${listOf("A", "B", "C", "D")[cleanOptions.size]}")
            }
            NormalizedQuestionSchema(
                id = item.id.ifBlank { "PDF_Q%03d".format(serial) },
                serialNumber = serial,
                category = item.category.ifBlank { safeCourseName },
                questionText = item.questionText.trim(),
                options = cleanOptions,
                correctOptionIndex = item.correctOptionIndex.coerceIn(0, 3),
                marks = safeMarksPerQuestion
            )
        }

        val examItems = normalizedQuestions.map { nq ->
            ExamQuestionItem(
                id = nq.id,
                category = nq.category,
                questionText = nq.questionText,
                options = nq.options,
                correctOptionIndex = nq.correctOptionIndex,
                marks = nq.marks
            )
        }

        val perStudentCount = (requestedPerStudentCount ?: PdfMcqBankParser.suggestPerStudentCount(examItems.size))
            .coerceIn(1, examItems.size)

        val questionsJson = PdfMcqBankParser.serializeCustomExamBankJson(
            questions = examItems,
            questionsPerStudent = perStudentCount
        )

        // Ensure totalMarks and passingMarks satisfy firestore.rules isValidExamPaper constraints:
        // durationMinutes in 5..360, totalMarks in 1..1000, passingMarks in 0..totalMarks
        val safeDuration = durationMinutes.coerceIn(5, 360)
        val computedTotalMarks = (perStudentCount * safeMarksPerQuestion).coerceIn(1, 1000)
        val computedPassingMarks = ((computedTotalMarks * passingPercentage.coerceIn(1, 100)) / 100)
            .coerceIn(0, computedTotalMarks)

        return NormalizedFirestoreExamSchema(
            id = examId.take(80),
            title = safeTitle,
            courseId = courseId.trim().take(80),
            courseName = safeCourseName,
            batchName = safeBatchName,
            examType = "MCQ_CBT_ANTI_CHEAT",
            durationMinutes = safeDuration,
            totalMarks = computedTotalMarks,
            passingMarks = computedPassingMarks,
            scheduledDateIso = scheduledDateIso.trim().take(30),
            questionsJson = questionsJson,
            status = "SCHEDULED",
            published = true,
            ownerUid = ownerUid.trim().ifBlank { "admin_root" }.take(128),
            createdAt = Timestamp.now(),
            totalPoolSize = normalizedQuestions.size,
            perStudentQuestionCount = perStudentCount,
            extractionMethod = extractionMethod,
            normalizedQuestions = normalizedQuestions
        )
    }

    /**
     * End-to-end Admin workflow: parses the uploaded PDF and saves the normalized exam record
     * directly to Cloud Firestore (`exams/{examId}`).
     */
    suspend fun parseAndPublishPdfQuestionBankToFirestore(
        context: Context,
        repository: InstituteRepository,
        pdfUri: Uri,
        examTitle: String,
        courseId: String = "",
        courseName: String,
        batchName: String,
        durationMinutes: Int = 60,
        marksPerQuestion: Int = 2,
        passingPercentage: Int = 40,
        requestedPerStudentCount: Int? = null,
        scheduledDateIso: String = currentDateIso(),
        ownerUid: String = "admin_root"
    ): Result<NormalizedFirestoreExamSchema> = withContext(Dispatchers.IO) {
        val schemaResult = parseUploadedPdfToFirestoreSchema(
            context = context,
            pdfUri = pdfUri,
            examTitle = examTitle,
            courseId = courseId,
            courseName = courseName,
            batchName = batchName,
            durationMinutes = durationMinutes,
            marksPerQuestion = marksPerQuestion,
            passingPercentage = passingPercentage,
            requestedPerStudentCount = requestedPerStudentCount,
            scheduledDateIso = scheduledDateIso,
            ownerUid = ownerUid
        )
        schemaResult.onSuccess { schema ->
            repository.saveExam(schema.toExamRecord())
        }
    }

    private fun currentDateIso(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
