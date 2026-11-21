package com.example.exam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.ai.GeminiInstituteService
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * Serial Question, Option-Wise & Real Answer PDF Parser (`PdfMcqBankParser.kt`).
 *
 * Extracts numbered MCQ questions (1, 2, 3, ...), 4 options (A, B, C, D), and the Real Answer
 * (`correctOptionIndex` 0..3) directly from uploaded PDF or TXT files:
 * 1. Fast On-Device PDF Stream Text Extractor (`Inflater` FlateDecode + PDF `Tj`/`TJ` text operators).
 * 2. Smart Serial MCQ & Answer-Key Parser supporting inline answers (`Ans: B`, `Answer: (C)`,
 *    `Real Answer: D`, `उत्तर: B`, `[Ans: A]`) AND bottom-of-PDF Answer Key tables (`1-B, 2-C...`).
 * 3. Automatic Multimodal OCR Fallback via Android `PdfRenderer` + Firebase AI Logic (`gemini-2.5-flash`)
 *    when the uploaded PDF is a scanned image PDF with no embedded text stream.
 */
object PdfMcqBankParser {

    data class ParsedExamBankResult(
        val questions: List<ExamQuestionItem>,
        val questionsPerStudent: Int,
        val extractionMethod: String,
        val warnings: List<String> = emptyList()
    )

    data class CustomExamBankPayload(
        val perStudentCount: Int,
        val questions: List<ExamQuestionItem>
    )

    private const val FIRESTORE_QUESTIONS_JSON_SAFE_LIMIT = 19_500

    suspend fun extractMcqsFromDocumentUri(
        context: Context,
        uri: Uri,
        defaultCategory: String
    ): ParsedExamBankResult = withContext(Dispatchers.IO) {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext ParsedExamBankResult(
                questions = emptyList(),
                questionsPerStudent = 0,
                extractionMethod = "READ_ERROR",
                warnings = listOf("Unable to open selected file.")
            )

        val isPdfHeader = rawBytes.size >= 4 &&
            rawBytes[0] == '%'.code.toByte() &&
            rawBytes[1] == 'P'.code.toByte() &&
            rawBytes[2] == 'D'.code.toByte() &&
            rawBytes[3] == 'F'.code.toByte()

        val extractedText = if (isPdfHeader) {
            extractTextFromPdfBytes(rawBytes)
        } else {
            String(rawBytes, StandardCharsets.UTF_8)
        }

        // [B2] ChatGPT Bulk MCQ+Answer Parser (.json, .csv, .md, .txt, extracted .pdf)
        val chatGptParsed = com.waqar.institute.exam.ChatGptBulkMcqFileParser
            .parseBulkQuestionsAndAnswers(extractedText)
            .mapIndexed { idx, master ->
                ExamQuestionItem(
                    id = master.masterQuestionId,
                    category = defaultCategory.ifBlank { "General" },
                    questionText = master.questionText,
                    options = master.options,
                    correctOptionIndex = master.correctOptionIndex,
                    marks = 1
                )
            }

        val textParsed = parseSerialMcqText(extractedText, defaultCategory)
        val bestParsed = if (chatGptParsed.size >= textParsed.size && chatGptParsed.isNotEmpty()) {
            chatGptParsed
        } else {
            textParsed
        }
        if (bestParsed.isNotEmpty()) {
            return@withContext ParsedExamBankResult(
                questions = bestParsed,
                questionsPerStudent = suggestPerStudentCount(bestParsed.size),
                extractionMethod = if (isPdfHeader) "PDF_TEXT_STREAM" else "CHATGPT_BULK_TEXT_DOCUMENT"
            )
        }

        // Fallback for Scanned Image PDFs using Android built-in PdfRenderer + Firebase AI Logic
        if (isPdfHeader) {
            val aiParsed = runCatching {
                extractMcqsFromScannedPdfViaVision(context, uri, defaultCategory)
            }.getOrDefault(emptyList())
            if (aiParsed.isNotEmpty()) {
                return@withContext ParsedExamBankResult(
                    questions = aiParsed,
                    questionsPerStudent = suggestPerStudentCount(aiParsed.size),
                    extractionMethod = "PDF_VISION_OCR"
                )
            }
        }

        ParsedExamBankResult(
            questions = emptyList(),
            questionsPerStudent = 0,
            extractionMethod = "NO_MCQS_DETECTED",
            warnings = listOf(
                "No serial-wise MCQ format detected. Format example:\n" +
                    "1. Question text?\nA) Option 1\nB) Option 2\nC) Option 3\nD) Option 4\nAns: B"
            )
        )
    }

    fun suggestPerStudentCount(totalPoolSize: Int): Int {
        return when {
            totalPoolSize <= 0 -> 0
            totalPoolSize <= 10 -> totalPoolSize
            totalPoolSize <= 25 -> (totalPoolSize * 0.7).toInt().coerceAtLeast(10)
            else -> minOf(50, (totalPoolSize * 0.6).toInt().coerceAtLeast(15))
        }
    }

    /**
     * Extracts readable text lines from PDF content streams (both compressed FlateDecode and raw streams).
     */
    fun extractTextFromPdfBytes(pdfBytes: ByteArray): String {
        val rawStr = String(pdfBytes, StandardCharsets.ISO_8859_1)
        val collected = StringBuilder()

        // 1. Scan all stream ... endstream blocks
        var searchFrom = 0
        while (true) {
            val streamIdx = rawStr.indexOf("stream", searchFrom)
            if (streamIdx < 0) break
            val endStreamIdx = rawStr.indexOf("endstream", streamIdx + 6)
            if (endStreamIdx < 0) break

            var dataStart = streamIdx + 6
            if (dataStart < rawStr.length && rawStr[dataStart] == '\r') dataStart++
            if (dataStart < rawStr.length && rawStr[dataStart] == '\n') dataStart++

            var dataEnd = endStreamIdx
            while (dataEnd > dataStart && (pdfBytes[dataEnd - 1] == '\n'.code.toByte() || pdfBytes[dataEnd - 1] == '\r'.code.toByte())) {
                dataEnd--
            }

            if (dataEnd > dataStart) {
                val slice = pdfBytes.copyOfRange(dataStart, dataEnd)
                val headerWindow = rawStr.substring((streamIdx - 220).coerceAtLeast(0), streamIdx)
                val decodedStreamBytes = if (headerWindow.contains("/FlateDecode")) {
                    inflateZlibSafe(slice) ?: slice
                } else {
                    slice
                }
                val streamText = String(decodedStreamBytes, StandardCharsets.ISO_8859_1)
                if (streamText.contains("BT") && (streamText.contains("Tj") || streamText.contains("TJ"))) {
                    collected.append(extractPdfTextOperators(streamText)).append('\n')
                }
            }
            searchFrom = endStreamIdx + 9
        }

        // 2. If no PDF text operators matched (e.g., plain PDF text), attempt fallback string extraction
        if (collected.isBlank()) {
            collected.append(extractPdfTextOperators(rawStr))
        }
        return collected.toString()
    }

    private fun inflateZlibSafe(input: ByteArray): ByteArray? {
        return runCatching {
            val inflater = Inflater()
            inflater.setInput(input)
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, count)
                }
            }
            inflater.end()
            out.toByteArray()
        }.getOrNull()
    }

    private fun extractPdfTextOperators(stream: String): String {
        val out = StringBuilder()
        var i = 0
        val n = stream.length

        fun readLiteralString(startIdx: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var idx = startIdx + 1
            var depth = 1
            while (idx < n && depth > 0) {
                val ch = stream[idx]
                if (ch == '\\' && idx + 1 < n) {
                    val next = stream[idx + 1]
                    when (next) {
                        'n' -> { sb.append('\n'); idx += 2 }
                        'r' -> { sb.append('\n'); idx += 2 }
                        't' -> { sb.append(' '); idx += 2 }
                        '(', ')', '\\' -> { sb.append(next); idx += 2 }
                        in '0'..'7' -> {
                            var oct = ""
                            var k = idx + 1
                            while (k < n && k < idx + 4 && stream[k] in '0'..'7') {
                                oct += stream[k]
                                k++
                            }
                            sb.append(oct.toInt(8).toChar())
                            idx = k
                        }
                        else -> { sb.append(next); idx += 2 }
                    }
                } else if (ch == '(') {
                    depth++
                    sb.append(ch)
                    idx++
                } else if (ch == ')') {
                    depth--
                    if (depth > 0) sb.append(ch)
                    idx++
                } else {
                    sb.append(ch)
                    idx++
                }
            }
            return sb.toString() to idx
        }

        while (i < n) {
            val ch = stream[i]
            if (ch == '(') {
                val (lit, nextIdx) = readLiteralString(i)
                out.append(lit)
                i = nextIdx
                val tail = stream.substring(i, (i + 10).coerceAtMost(n))
                if (tail.startsWith(" Tj") || tail.startsWith("Tj") || tail.startsWith(" '") || tail.startsWith("'")) {
                    out.append('\n')
                }
            } else if (ch == 'T' && i + 1 < n && (stream[i + 1] == 'd' || stream[i + 1] == 'D' || stream[i + 1] == '*')) {
                out.append('\n')
                i += 2
            } else if (ch == 'E' && i + 1 < n && stream[i + 1] == 'T') {
                out.append('\n')
                i += 2
            } else {
                i++
            }
        }
        return out.toString()
    }

    /**
     * Parses serial-wise numbered MCQs, option-wise choices (A/B/C/D), and Real Answer markers
     * directly from extracted PDF/document text.
     */
    fun parseSerialMcqText(rawText: String, defaultCategory: String = "General"): List<ExamQuestionItem> {
        if (rawText.isBlank()) return emptyList()

        val normalized = rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // Split inline options like "A) foo B) bar C) baz D) qux" onto new lines
            .replace(Regex("""(?<!\S)([A-Da-d])\s*[\)\.]\s+""")) { "\n${it.groupValues[1].uppercase()}) " }
            .replace(Regex("""(?<!\S)\(([A-Da-d])\)\s*""")) { "\n${it.groupValues[1].uppercase()}) " }
            // Split inline answer markers onto new lines
            .replace(Regex("""(?i)(?<!\S)(?:Real\s+Answer|Correct\s+Answer|Correct\s+Option|Answer|Ans|Key|उत्तर)\s*[:\-=]""")) {
                "\nAns:"
            }

        val lines = normalized.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Extract standalone answer key table if present (e.g. "1-B", "1. B", "Q1: C")
        val bottomAnswerKey = mutableMapOf<Int, Int>()
        val keyEntryRegex = Regex("""(?:Q\.?\s*)?(\d{1,3})\s*[\.\-\)\:]\s*\(?([A-Da-d])\)?(?:\s|,|;|$)""")
        val ansSectionIdx = lines.indexOfFirst {
            it.matches(Regex("""(?i).*(answer\s*key|answers\s*sheet|real\s*answers|उत्तर\s*माला).*"""))
        }
        if (ansSectionIdx >= 0) {
            for (k in (ansSectionIdx + 1) until lines.size) {
                keyEntryRegex.findAll(lines[k]).forEach { m ->
                    val qNum = m.groupValues[1].toIntOrNull()
                    val letterIdx = optionLetterToIndex(m.groupValues[2])
                    if (qNum != null && letterIdx != null) {
                        bottomAnswerKey[qNum] = letterIdx
                    }
                }
            }
        }

        val questionHeaderRegex = Regex("""^(?:Q(?:uestion)?\s*\.?\s*(\d{1,3})\s*[\.\)\:\-]?|(\d{1,3})\s*[\.\)]\s+|\((\d{1,3})\)\s+)(.+)$""", RegexOption.IGNORE_CASE)
        val optionRegex = Regex("""^(?:\(?([A-Da-d])\)?[\)\.\:\-]\s+)(.+)$""")
        val inlineAnsRegex = Regex("""(?i)^Ans:\s*(?:Option\s*)?\(?([A-Da-d])\)?(?:\b.*)?$""")

        data class MutableDraft(
            val serialNo: Int,
            val questionBuf: StringBuilder = StringBuilder(),
            val optionsMap: LinkedHashMap<Int, String> = linkedMapOf(),
            var lastOptionIdx: Int? = null,
            var answerIdx: Int? = null
        )

        val drafts = mutableListOf<MutableDraft>()
        var current: MutableDraft? = null

        val contentLinesLimit = if (ansSectionIdx >= 0) ansSectionIdx else lines.size
        for (idx in 0 until contentLinesLimit) {
            val line = lines[idx]

            val ansMatch = inlineAnsRegex.matchEntire(line)
            if (ansMatch != null && current != null) {
                current.answerIdx = optionLetterToIndex(ansMatch.groupValues[1])
                continue
            }

            val optMatch = optionRegex.matchEntire(line)
            if (optMatch != null && current != null) {
                val optIdx = optionLetterToIndex(optMatch.groupValues[1])
                if (optIdx != null) {
                    var optText = optMatch.groupValues[2].trim()
                    if (optText.endsWith("*") || optText.contains("[Correct]", ignoreCase = true) || optText.contains("(Correct)", ignoreCase = true)) {
                        current.answerIdx = optIdx
                        optText = optText
                            .replace("[Correct]", "", ignoreCase = true)
                            .replace("(Correct)", "", ignoreCase = true)
                            .removeSuffix("*")
                            .trim()
                    }
                    current.optionsMap[optIdx] = optText
                    current.lastOptionIdx = optIdx
                    continue
                }
            }

            val qMatch = questionHeaderRegex.matchEntire(line)
            if (qMatch != null) {
                val qNum = (qMatch.groupValues[1].ifBlank {
                    qMatch.groupValues[2].ifBlank { qMatch.groupValues[3] }
                }).toIntOrNull() ?: (drafts.size + 1)
                val qText = qMatch.groupValues[4].trim()
                val draft = MutableDraft(serialNo = qNum)
                draft.questionBuf.append(qText)
                drafts.add(draft)
                current = draft
                continue
            }

            // Continuation line (belongs to current option or question)
            if (current != null) {
                val activeOpt = current.lastOptionIdx
                if (activeOpt != null && current.optionsMap.containsKey(activeOpt)) {
                    current.optionsMap[activeOpt] = "${current.optionsMap[activeOpt]} $line".trim()
                } else if (current.optionsMap.isEmpty()) {
                    current.questionBuf.append(' ').append(line)
                }
            }
        }

        // Sort serial-wise & convert complete MCQs
        return drafts
            .sortedBy { it.serialNo }
            .mapIndexedNotNull { index, draft ->
                val qText = draft.questionBuf.toString().trim()
                if (qText.isBlank() || draft.optionsMap.size < 2) return@mapIndexedNotNull null

                val optionsList = mutableListOf<String>()
                for (optIdx in 0..3) {
                    draft.optionsMap[optIdx]?.takeIf { it.isNotBlank() }?.let { optionsList.add(it) }
                }
                if (optionsList.size < 2) return@mapIndexedNotNull null
                while (optionsList.size < 4) {
                    val fillerLabel = listOf("A", "B", "C", "D")[optionsList.size]
                    optionsList.add("Option $fillerLabel")
                }

                val resolvedAnswer = (draft.answerIdx ?: bottomAnswerKey[draft.serialNo] ?: 0)
                    .coerceIn(0, optionsList.lastIndex)

                ExamQuestionItem(
                    id = "PDF_Q%03d".format(draft.serialNo.coerceAtLeast(index + 1)),
                    category = defaultCategory.ifBlank { "Institute Exam" },
                    questionText = qText,
                    options = optionsList,
                    correctOptionIndex = resolvedAnswer,
                    marks = 2
                )
            }
    }

    private suspend fun extractMcqsFromScannedPdfViaVision(
        context: Context,
        uri: Uri,
        defaultCategory: String
    ): List<ExamQuestionItem> {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return emptyList()

        val pageBitmaps = mutableListOf<Bitmap>()
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val maxPages = minOf(renderer.pageCount, 8)
                for (p in 0 until maxPages) {
                    renderer.openPage(p).use { page ->
                        val targetWidth = 1080
                        val targetHeight = ((page.height.toFloat() / page.width.coerceAtLeast(1)) * targetWidth)
                            .toInt().coerceIn(400, 1800)
                        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pageBitmaps.add(bmp)
                    }
                }
            }
        }
        if (pageBitmaps.isEmpty()) return emptyList()

        val model = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(GeminiInstituteService.MODEL_NAME)

        val prompt = content {
            pageBitmaps.forEach { image(it) }
            text(
                "Extract ALL multiple-choice questions IN EXACT SERIAL ORDER from these PDF pages. " +
                    "Return ONLY a valid JSON array where each element has: " +
                    "{\"serial\":1,\"question\":\"...\",\"options\":[\"optA\",\"optB\",\"optC\",\"optD\"],\"correctOptionIndex\":0}. " +
                    "correctOptionIndex MUST be 0 for A, 1 for B, 2 for C, 3 for D based on the real answer shown in the PDF."
            )
        }

        val responseText = withTimeout(45_000L) {
            model.generateContent(prompt).text.orEmpty()
        }
        val jsonStart = responseText.indexOf('[')
        val jsonEnd = responseText.lastIndexOf(']')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()

        val arr = JSONArray(responseText.substring(jsonStart, jsonEnd + 1))
        val parsed = mutableListOf<ExamQuestionItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val qText = obj.optString("question").trim()
            val optsArr = obj.optJSONArray("options") ?: continue
            val opts = mutableListOf<String>()
            for (j in 0 until optsArr.length()) {
                val txt = optsArr.optString(j).trim()
                if (txt.isNotBlank()) opts.add(txt)
            }
            if (qText.isNotBlank() && opts.size >= 2) {
                while (opts.size < 4) opts.add("Option ${listOf("A", "B", "C", "D")[opts.size]}")
                parsed.add(
                    ExamQuestionItem(
                        id = "PDF_Q%03d".format(obj.optInt("serial", i + 1)),
                        category = defaultCategory.ifBlank { "Institute Exam" },
                        questionText = qText,
                        options = opts.take(4),
                        correctOptionIndex = obj.optInt("correctOptionIndex", 0).coerceIn(0, 3),
                        marks = 2
                    )
                )
            }
        }
        return parsed
    }

    /**
     * Serializes the uploaded question bank + per-student random question count within
     * Firestore `firestore.rules` `isValidExamPaper` 20,000-character limit.
     */
    fun serializeCustomExamBankJson(
        questions: List<ExamQuestionItem>,
        questionsPerStudent: Int
    ): String {
        if (questions.isEmpty()) return "[]"
        val cleanPerStudent = questionsPerStudent.coerceIn(1, questions.size)

        fun buildJsonForSlice(slice: List<ExamQuestionItem>): String {
            val arr = JSONArray()
            slice.forEachIndexed { idx, q ->
                val obj = JSONObject()
                obj.put("id", q.id.ifBlank { "PDF_Q%03d".format(idx + 1) })
                obj.put("category", q.category)
                obj.put("questionText", q.questionText.trim())
                val opts = JSONArray()
                q.options.take(4).forEach { opts.put(it.trim()) }
                obj.put("options", opts)
                obj.put("correctOptionIndex", q.correctOptionIndex.coerceIn(0, q.options.lastIndex.coerceAtLeast(0)))
                arr.put(obj)
            }
            val wrapper = JSONObject()
            wrapper.put("perStudentCount", minOf(cleanPerStudent, slice.size))
            wrapper.put("questions", arr)
            return wrapper.toString()
        }

        var count = questions.size
        while (count > 1) {
            val json = buildJsonForSlice(questions.take(count))
            if (json.length <= FIRESTORE_QUESTIONS_JSON_SAFE_LIMIT) {
                return json
            }
            count = (count * 0.85).toInt().coerceAtLeast(1)
        }
        return buildJsonForSlice(questions.take(1))
    }

    /**
     * Parses both legacy JSONArray `[...]` and new `{"perStudentCount":N,"questions":[...]}` formats.
     */
    fun parseCustomExamBank(questionsJson: String, courseName: String): CustomExamBankPayload {
        if (questionsJson.isBlank() || questionsJson == "[]") {
            return CustomExamBankPayload(perStudentCount = 0, questions = emptyList())
        }
        return runCatching {
            val trimmed = questionsJson.trim()
            val (perStudentCount, arr) = if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val qArr = root.optJSONArray("questions") ?: JSONArray()
                val count = root.optInt("perStudentCount", qArr.length()).coerceAtLeast(1)
                count to qArr
            } else {
                val qArr = JSONArray(trimmed)
                qArr.length().coerceAtLeast(1) to qArr
            }

            val list = mutableListOf<ExamQuestionItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val qText = obj.optString("questionText").trim()
                val optsArr = obj.optJSONArray("options")
                val opts = mutableListOf<String>()
                if (optsArr != null) {
                    for (j in 0 until optsArr.length()) {
                        opts.add(optsArr.optString(j))
                    }
                }
                if (qText.isNotBlank() && opts.size >= 2) {
                    list.add(
                        ExamQuestionItem(
                            id = obj.optString("id", "CUSTOM_Q_${i + 1}"),
                            category = obj.optString("category", courseName.ifBlank { "Custom Exam" }),
                            questionText = qText,
                            options = opts,
                            correctOptionIndex = obj.optInt("correctOptionIndex", 0).coerceIn(0, opts.lastIndex)
                        )
                    )
                }
            }
            CustomExamBankPayload(
                perStudentCount = perStudentCount.coerceIn(1, list.size.coerceAtLeast(1)),
                questions = list
            )
        }.getOrDefault(CustomExamBankPayload(perStudentCount = 0, questions = emptyList()))
    }

    private fun optionLetterToIndex(raw: String): Int? {
        return when (raw.trim().uppercase()) {
            "A", "1" -> 0
            "B", "2" -> 1
            "C", "3" -> 2
            "D", "4" -> 3
            else -> null
        }
    }
}
