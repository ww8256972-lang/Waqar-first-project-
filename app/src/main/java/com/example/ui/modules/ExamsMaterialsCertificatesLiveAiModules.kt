package com.example.ui.modules

import android.app.Activity
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.verticalScroll
import com.example.biometric.StudentFaceEnrollmentManager
import com.example.data.model.ExamRecord
import com.example.data.model.PortalRole
import com.example.exam.ExamQuestionItem
import com.example.media.AgoraLiveVideoClassroomRoom
import com.example.media.BindScreenCapturePolicy
import com.example.media.ExoPlayerHdLecturePlayer
import com.example.media.ExoPlayerLectureManager
import com.example.media.ScreenCapturePolicy
import com.example.security.AntiOverlayShield
import com.example.security.PreExamSecurityGate
import com.example.ui.designsystem.*
import com.example.util.DocumentExportHelper
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import com.waqar.institute.certificate.CertificateGeneratorViewModel
import com.waqar.institute.certificate.WaqarOfficialDynamicCertificateCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

private object NoOpExamTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        // Blocked by exam lockdown: copy/cut/paste/select-all toolbar disabled.
    }
    override fun hide() = Unit
}

private data class DraftCustomMcq(
    val question: String,
    val optA: String,
    val optB: String,
    val optC: String,
    val optD: String,
    val correctIndex: Int
)

private fun parseCustomExamQuestions(questionsJson: String, courseName: String): List<ExamQuestionItem> {
    return com.example.exam.PdfMcqBankParser.parseCustomExamBank(questionsJson, courseName).questions
}

private enum class ExamWindowStatus { UPCOMING, LIVE, ENDED, UNSCHEDULED }

private data class ExamScheduleEvaluation(
    val status: ExamWindowStatus,
    val startMillis: Long?,
    val endMillis: Long?,
    val displayWindow: String
)

private fun evaluateExamScheduleWindow(
    scheduledDateIso: String,
    durationMinutes: Int,
    nowMillis: Long = System.currentTimeMillis()
): ExamScheduleEvaluation {
    val raw = scheduledDateIso.trim()
    if (raw.isBlank()) {
        return ExamScheduleEvaluation(ExamWindowStatus.UNSCHEDULED, null, null, "Not scheduled by Admin")
    }
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
    val rangeRegex = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})-(\d{1,2}:\d{2})$""")
    val singleRegex = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})$""")
    val (startMs, endMs) = when {
        rangeRegex.matches(raw) -> {
            val m = rangeRegex.matchEntire(raw)!!
            val date = m.groupValues[1]
            val s = runCatching { sdf.parse("$date ${m.groupValues[2]}")?.time }.getOrNull()
            val e = runCatching { sdf.parse("$date ${m.groupValues[3]}")?.time }.getOrNull()
            val fixedEnd = if (s != null && e != null && e > s) e else s?.plus(durationMinutes.coerceAtLeast(10) * 60_000L)
            s to fixedEnd
        }
        singleRegex.matches(raw) -> {
            val s = runCatching { sdf.parse(raw)?.time }.getOrNull()
            s to s?.plus(durationMinutes.coerceAtLeast(10) * 60_000L)
        }
        else -> null to null
    }
    if (startMs == null || endMs == null) {
        return ExamScheduleEvaluation(ExamWindowStatus.UNSCHEDULED, null, null, raw)
    }
    val status = when {
        nowMillis < startMs -> ExamWindowStatus.UPCOMING
        nowMillis <= endMs -> ExamWindowStatus.LIVE
        else -> ExamWindowStatus.ENDED
    }
    return ExamScheduleEvaluation(status, startMs, endMs, raw)
}

private fun hasStudentAlreadyAttemptedExam(
    exam: ExamRecord,
    studentId: String,
    enrollmentNo: String,
    studentName: String,
    results: List<com.example.data.model.ExamResult>
): Boolean {
    return results.any { res ->
        val matchesExam = res.examId.equals(exam.id, ignoreCase = true) ||
            (exam.title.isNotBlank() && res.examTitle.equals(exam.title, ignoreCase = true))
        val matchesStudent = (studentId.isNotBlank() && res.studentId.equals(studentId, ignoreCase = true)) ||
            (enrollmentNo.isNotBlank() && res.studentId.equals(enrollmentNo, ignoreCase = true)) ||
            (studentName.isNotBlank() && res.studentName.equals(studentName, ignoreCase = true))
        matchesExam && matchesStudent
    }
}

// ============================================================================
// M10, M11, M12: EXAMS & MARK SHEETS, STUDY MATERIAL, QR CERTIFICATES
// ============================================================================

@Composable
fun ExamsMaterialsCertificatesModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    val isAdmin = state.activePortalRole == PortalRole.ADMIN
    val canManageAssessments = isAdmin || state.activePortalRole == PortalRole.TEACHER
    var activeTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var showCreateExam by remember { mutableStateOf(false) }
    var showAddResult by remember { mutableStateOf(false) }
    var showAddMaterial by remember { mutableStateOf(false) }
    var showIssueCertificate by remember { mutableStateOf(false) }
    var activeExamAttempt by remember { mutableStateOf<ExamRecord?>(null) }
    var inspectMasterKeyExam by remember { mutableStateOf<ExamRecord?>(null) }
    var submittedResultSummary by remember { mutableStateOf<String?>(null) }
    var resultCountdownSeconds by remember { mutableIntStateOf(300) }

    val activeStudentRecord = state.students.firstOrNull {
        it.id == state.userProfile?.linkedEntityId ||
            it.fullName.equals(state.userProfile?.displayName.orEmpty(), ignoreCase = true)
    }
    val currentStuId = activeStudentRecord?.id ?: state.userProfile?.linkedEntityId.orEmpty()
    val currentStuRoll = activeStudentRecord?.enrollmentNo.orEmpty()
    val currentStuName = activeStudentRecord?.fullName ?: state.userProfile?.displayName.orEmpty()
    val nowSnapshot by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(10_000L)
            value = System.currentTimeMillis()
        }
    }
    val evaluatedExams = remember(state.exams, nowSnapshot) {
        state.exams.map { exam ->
            exam to evaluateExamScheduleWindow(exam.scheduledDateIso, exam.durationMinutes, nowSnapshot)
        }
    }
    val heroExamPair = remember(evaluatedExams) {
        evaluatedExams.firstOrNull { it.second.status == ExamWindowStatus.LIVE }
            ?: evaluatedExams.firstOrNull { it.second.status == ExamWindowStatus.UPCOMING }
            ?: evaluatedExams.firstOrNull()
    }
    val latestScheduledExam = heroExamPair?.first
    val heroEvaluation = heroExamPair?.second
    val heroAlreadyAttempted = if (latestScheduledExam != null && state.activePortalRole == PortalRole.STUDENT) {
        hasStudentAlreadyAttemptedExam(
            exam = latestScheduledExam,
            studentId = currentStuId,
            enrollmentNo = currentStuRoll,
            studentName = currentStuName,
            results = state.examResults
        )
    } else false

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiquidPill(text = "Exams", selected = activeTab == 0, onClick = { activeTab = 0 }, badgeCount = state.exams.size)
                LiquidPill(text = "Mark Sheets", selected = activeTab == 1, onClick = { activeTab = 1 }, badgeCount = state.examResults.size)
                LiquidPill(text = "Study Material", selected = activeTab == 2, onClick = { activeTab = 2 }, badgeCount = state.materials.size)
                LiquidPill(text = "QR Certificates", selected = activeTab == 3, onClick = { activeTab = 3 }, badgeCount = state.certificates.size)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (activeTab) {
                        0 -> "Online MCQ & Practical Exams"
                        1 -> if (canManageAssessments) "Exam Results & PDF Mark Sheets" else "Official Institute Mark Sheet"
                        2 -> "LMS Study Material & Assignments"
                        else -> "Verifiable QR Certificates & IDs"
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.textPrimary, fontWeight = FontWeight.ExtraBold),
                    modifier = Modifier.weight(1f)
                )
                val showActionBtn = when (activeTab) {
                    0, 1, 2 -> canManageAssessments
                    else -> isAdmin
                }
                if (showActionBtn) {
                    ShimmerButton(
                        text = when (activeTab) {
                            0 -> "+ Exam"
                            1 -> "+ Marks"
                            2 -> "+ Material"
                            else -> "+ Certificate"
                        },
                        onClick = {
                            when (activeTab) {
                                0 -> showCreateExam = true
                                1 -> showAddResult = true
                                2 -> showAddMaterial = true
                                else -> showIssueCertificate = true
                            }
                        }
                    )
                }
            }
        }

        when (activeTab) {
            0 -> {
                item {
                    // Schedule-Gated "Upcoming Exam - Scheduled by Admin" Hero Card
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 28.dp,
                        glowColor = WciColors.IndigoAccent,
                        strongSurface = true
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        WciColors.IndigoAccent.copy(alpha = 0.24f),
                                        RoundedCornerShape(WciRadius.pill)
                                    )
                                    .border(1.dp, WciColors.glassBorderSubtleColor, RoundedCornerShape(WciRadius.pill))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = when {
                                        heroEvaluation?.status == ExamWindowStatus.LIVE -> "LIVE EXAM WINDOW • ONLINE CBT"
                                        latestScheduledExam != null -> "UPCOMING EXAM • SCHEDULED BY ADMIN"
                                        else -> "UPCOMING EXAM - SCHEDULED BY ADMIN"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (heroEvaluation?.status == ExamWindowStatus.LIVE) WciColors.successColor else WciColors.textPrimary
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (latestScheduledExam != null) {
                                    "${latestScheduledExam.scheduledDateIso.ifBlank { "Unscheduled" }} • ${latestScheduledExam.durationMinutes} Mins"
                                } else {
                                    "Admin Schedule Required"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.successColor
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = latestScheduledExam?.title ?: "Upcoming Exam - Scheduled by Admin",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                latestScheduledExam != null && heroEvaluation?.status == ExamWindowStatus.LIVE ->
                                    "${latestScheduledExam.courseName} • Batch: ${latestScheduledExam.batchName} • Window Open Now (${latestScheduledExam.scheduledDateIso})"
                                latestScheduledExam != null ->
                                    "${latestScheduledExam.courseName} • Batch: ${latestScheduledExam.batchName} • Opens at: ${latestScheduledExam.scheduledDateIso}"
                                canManageAssessments ->
                                    "Publish an online MCQ test with custom questions, Date, 24h Start Time & End Time, and Duration."
                                else ->
                                    "No active exam window is currently open. When Admin/Faculty schedules an exam date and time window, it unlocks here automatically."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val datePart = latestScheduledExam?.scheduledDateIso?.substringBefore(" ")?.takeIf { it.isNotBlank() } ?: "--"
                                val timePart = latestScheduledExam?.scheduledDateIso?.substringAfter(" ", "")?.ifBlank { "--:--" } ?: "--:--"
                                listOf(
                                    datePart to "Date",
                                    timePart to "24h Window",
                                    "${latestScheduledExam?.durationMinutes ?: 0}" to "Mins"
                                ).forEach { (v, u) ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(WciColors.containerSurface)
                                            .border(1.dp, WciColors.glassBorderSubtleColor, RoundedCornerShape(14.dp))
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = v,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontFamily = JetBrainsMono,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = WciColors.infoColor
                                                )
                                            )
                                            Text(
                                                text = u,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 12.sp,
                                                    color = WciColors.textSecondary
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            if (canManageAssessments) {
                                ShimmerButton(
                                    text = "+ Schedule Exam",
                                    onClick = { showCreateExam = true },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (state.activePortalRole == PortalRole.STUDENT && latestScheduledExam != null) {
                                when {
                                    heroAlreadyAttempted -> {
                                        LiquidPill(
                                            text = "Submitted ✓ • View Marks",
                                            selected = false,
                                            icon = Icons.Default.Verified,
                                            onClick = { activeTab = 1 },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    heroEvaluation?.status == ExamWindowStatus.LIVE -> {
                                        LiquidPill(
                                            text = "Start Exam Now",
                                            selected = true,
                                            icon = Icons.Default.PlayArrow,
                                            onClick = { activeExamAttempt = latestScheduledExam },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    heroEvaluation?.status == ExamWindowStatus.UPCOMING -> {
                                        LiquidPill(
                                            text = "Locked Until Start",
                                            selected = false,
                                            icon = Icons.Default.Lock,
                                            onClick = {
                                                viewModel.postMessage("Upcoming Exam opens at ${latestScheduledExam.scheduledDateIso}")
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    else -> {
                                        LiquidPill(
                                            text = "Window Closed",
                                            selected = false,
                                            icon = Icons.Default.LockClock,
                                            onClick = {
                                                viewModel.postMessage("This exam window has closed.")
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (evaluatedExams.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.Quiz,
                            title = "No exams available",
                            subtitle = if (canManageAssessments) {
                                "No examination schedule has been published yet. Click '+ Schedule Exam' to create a real online exam."
                            } else {
                                "No exams available for your batch right now. Scheduled exams will appear here automatically."
                            },
                            ctaLabel = if (canManageAssessments) "+ Schedule Exam" else null,
                            onCtaClick = if (canManageAssessments) ({ showCreateExam = true }) else null
                        )
                    }
                } else {
                    items(evaluatedExams, key = { it.first.id }) { (exam, eval) ->
                        val parsedBank = remember(exam.questionsJson, exam.courseName) {
                            com.example.exam.PdfMcqBankParser.parseCustomExamBank(exam.questionsJson, exam.courseName)
                        }
                        val customQCount = parsedBank.questions.size
                        val alreadyAttempted = if (state.activePortalRole == PortalRole.STUDENT) {
                            hasStudentAlreadyAttemptedExam(
                                exam = exam,
                                studentId = currentStuId,
                                enrollmentNo = currentStuRoll,
                                studentName = currentStuName,
                                results = state.examResults
                            )
                        } else false

                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            glowColor = WciColors.IndigoAccent
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    WciColors.IndigoAccent.copy(alpha = 0.25f),
                                                    RoundedCornerShape(WciRadius.pill)
                                                )
                                                .border(1.dp, WciColors.IndigoAccent, RoundedCornerShape(WciRadius.pill))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = exam.examType,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = JetBrainsMono,
                                                    fontWeight = FontWeight.Bold,
                                                    color = WciColors.infoColor,
                                                    fontSize = 12.sp
                                                )
                                            )
                                        }
                                        Text(
                                            text = "${exam.scheduledDateIso.ifBlank { "Unscheduled" }} • ${exam.durationMinutes} Mins",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = JetBrainsMono,
                                                color = if (eval.status == ExamWindowStatus.LIVE) WciColors.successColor else WciColors.warningColor
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = exam.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = WciColors.textPrimary,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    )
                                    Text(
                                        text = "${exam.courseName} • Batch: ${exam.batchName}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.infoColor)
                                    )
                                    Text(
                                        text = if (customQCount > 0) {
                                            "Max Marks: ${exam.totalMarks} • Pool: $customQCount Qs (${parsedBank.perStudentCount}/Student • Anti-Cheat 1 Q/Screen)"
                                        } else {
                                            "Max Marks: ${exam.totalMarks} • Passing: ${exam.passingMarks} • Pending Question Paper Upload (1 Q/Screen)"
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMono,
                                            color = WciColors.textSecondary
                                        )
                                    )
                                }
                                when {
                                    canManageAssessments -> {
                                        Column(
                                            horizontalAlignment = Alignment.End,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            LiquidPill(
                                                text = "Preview CBT",
                                                selected = true,
                                                icon = Icons.Default.PlayArrow,
                                                onClick = { activeExamAttempt = exam }
                                            )
                                            if (customQCount > 0) {
                                                LiquidPill(
                                                    text = "Serial Key ($customQCount)",
                                                    selected = false,
                                                    icon = Icons.Default.Verified,
                                                    onClick = { inspectMasterKeyExam = exam }
                                                )
                                            }
                                        }
                                    }
                                    state.activePortalRole == PortalRole.STUDENT -> {
                                        when {
                                            alreadyAttempted -> {
                                                LiquidPill(
                                                    text = "Submitted ✓",
                                                    selected = false,
                                                    icon = Icons.Default.Verified,
                                                    onClick = { activeTab = 1 }
                                                )
                                            }
                                            eval.status == ExamWindowStatus.LIVE -> {
                                                LiquidPill(
                                                    text = "Start Exam",
                                                    selected = true,
                                                    icon = Icons.Default.PlayArrow,
                                                    onClick = { activeExamAttempt = exam }
                                                )
                                            }
                                            eval.status == ExamWindowStatus.UPCOMING -> {
                                                LiquidPill(
                                                    text = "Scheduled",
                                                    selected = false,
                                                    icon = Icons.Default.Lock,
                                                    onClick = {
                                                        viewModel.postMessage("Scheduled for ${exam.scheduledDateIso}. Entry opens at start time.")
                                                    }
                                                )
                                            }
                                            eval.status == ExamWindowStatus.ENDED -> {
                                                LiquidPill(
                                                    text = "Closed",
                                                    selected = false,
                                                    icon = Icons.Default.LockClock,
                                                    onClick = {
                                                        viewModel.postMessage("Exam window (${exam.scheduledDateIso}) has ended.")
                                                    }
                                                )
                                            }
                                            else -> {
                                                LiquidPill(
                                                    text = "Unscheduled",
                                                    selected = false,
                                                    icon = Icons.Default.Lock,
                                                    onClick = {
                                                        viewModel.postMessage("Awaiting Admin start/end time schedule.")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (state.examResults.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.EmojiEvents,
                            title = "No exam mark sheets published yet",
                            subtitle = if (canManageAssessments) {
                                "Enter student marks or complete an online CBT exam to generate graded mark sheets with batch ranks."
                            } else {
                                "Complete an online CBT test or wait for your faculty to publish your official graded mark sheet."
                            },
                            ctaLabel = if (canManageAssessments) "+ Enter Student Marks" else null,
                            onCtaClick = if (canManageAssessments) ({ showAddResult = true }) else null
                        )
                    }
                } else {
                    // [C3] Batch Ranker Leaderboard (Top 3 Podium) + Top 5 Most-Missed Questions Card
                    item {
                        val latestExam = remember(state.examResults, state.exams) {
                            state.exams.firstOrNull { ex ->
                                state.examResults.any { it.examId == ex.id }
                            }
                        }
                        val analyticsReport = remember(state.examResults, latestExam) {
                            com.example.exam.ExamRepository.computeBatchLeaderboardAndWrongQuestionStats(
                                examId = latestExam?.id.orEmpty(),
                                results = state.examResults
                            )
                        }
                        if (analyticsReport.leaderboard.isNotEmpty()) {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                glowColor = WciColors.Warning,
                                strongSurface = true
                            ) {
                                Text(
                                    text = "🏆 BATCH RANKER LEADERBOARD (${latestExam?.batchName ?: "All Batches"})",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.Warning,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Candidates: ${analyticsReport.leaderboard.size} • Pass Rate: ${analyticsReport.passRatePercent}% • Avg: ${"%.2f".format(analyticsReport.averagePercentage)}%",
                                    style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textSecondary)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                analyticsReport.leaderboard.take(3).forEach { entry ->
                                    val medal = when (entry.rank) {
                                        1 -> "🥇"
                                        2 -> "🥈"
                                        else -> "🥉"
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$medal ${entry.podiumBadge} ${entry.result.studentName}",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = WciColors.textPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Text(
                                            text = "${entry.result.marksObtained}/${entry.result.totalMarks} (${entry.formattedPercentage} • ${entry.result.grade})",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = JetBrainsMono,
                                                color = WciColors.Success,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        )
                                    }
                                }
                                if (analyticsReport.topMissedQuestions.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = WciColors.glassBorderSubtleColor)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Top Missed Questions (Revision Focus):",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = WciColors.Danger,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    analyticsReport.topMissedQuestions.take(5).forEach { mq ->
                                        Text(
                                            text = "• ${mq.questionId}: ${mq.wrongAttempts}/${mq.totalAttempts} wrong (${mq.missRatePercent}%)",
                                            style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textSecondary)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    items(state.examResults, key = { it.id }) { res ->
                        val exactPct = if (res.exactPercentage > 0.0) {
                            res.exactPercentage
                        } else if (res.totalMarks > 0) {
                            kotlin.math.round(((res.marksObtained.toDouble() * 100.0) / res.totalMarks.toDouble()) * 100.0) / 100.0
                        } else 0.0
                        val formattedPctLabel = res.formattedPercentage.ifBlank { "$exactPct%" }
                        val isPassed = res.status.uppercase() != "FAIL" && res.status.uppercase() != "FAILED" && exactPct >= 40.0
                        val statusColor = if (isPassed) WciColors.Success else WciColors.Danger

                        // Rich Multi-Section Institute Marksheet Card
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            glowColor = if (isPassed) WciColors.PurpleVibrant else WciColors.Danger,
                            strongSurface = true
                        ) {
                            // 1. Header Banner: Official Crest + Grade & Rank Badges
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Verified,
                                        contentDescription = "Verified Marksheet",
                                        tint = WciColors.Info,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "OFFICIAL EXAMINATION MARKSHEET",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMono,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = WciColors.infoColor,
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                brush = Brush.linearGradient(
                                                    listOf(WciColors.PurpleVibrant, WciColors.ScannerPink)
                                                ),
                                                shape = RoundedCornerShape(WciRadius.pill)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "GRADE ${res.grade}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                WciColors.successColor.copy(alpha = 0.18f),
                                                RoundedCornerShape(WciRadius.pill)
                                            )
                                            .border(1.dp, WciColors.successColor, RoundedCornerShape(WciRadius.pill))
                                            .padding(horizontal = 10.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "RANK #${res.rank}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = WciColors.successColor,
                                                fontSize = 12.sp
                                            )
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 2. Candidate Info & Circular Percentage Score Ring
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = res.studentName,
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            color = WciColors.textPrimary,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = res.examTitle,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = WciColors.infoColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${state.preferences.instituteName} • Session ${state.preferences.academicSession}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = WciColors.textSecondary
                                        )
                                    )
                                }
                                DonutProgressRing(
                                    progress = (exactPct / 100.0).toFloat().coerceIn(0f, 1f),
                                    color = statusColor,
                                    label = formattedPctLabel,
                                    subLabel = res.grade,
                                    sizeDp = 78.dp
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 3. 3-Column Score Breakdown Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(WciRadius.md))
                                        .background(WciColors.containerSurface)
                                        .border(1.dp, WciColors.glassBorderSubtleColor, RoundedCornerShape(WciRadius.md))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "MARKS OBTAINED",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                color = WciColors.textMuted
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${res.marksObtained} / ${res.totalMarks}",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = WciColors.textPrimary
                                            )
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(WciRadius.md))
                                        .background(WciColors.containerSurface)
                                        .border(1.dp, WciColors.glassBorderSubtleColor, RoundedCornerShape(WciRadius.md))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "EXACT %",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                color = WciColors.textMuted
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = formattedPctLabel,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = WciColors.infoColor
                                            )
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(WciRadius.md))
                                        .background(WciColors.containerSurface)
                                        .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(WciRadius.md))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "VERDICT",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                color = WciColors.textMuted
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = res.status.ifBlank { if (isPassed) "PASS" else "FAIL" },
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = statusColor
                                            )
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 4. Footer Row: Examiner Remarks + Official PDF Export + 1-Tap WhatsApp Alert
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = res.remarks.ifBlank { "Verified by Controller of Examinations" },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = WciColors.textSecondary
                                    )
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    LiquidPill(
                                        text = "PDF Marksheet",
                                        selected = true,
                                        icon = Icons.Default.PictureAsPdf,
                                        onClick = {
                                            DocumentExportHelper.exportAndShareMarkSheetPdf(
                                                context = context,
                                                instituteName = state.preferences.instituteName,
                                                result = res
                                            )
                                        },
                                        modifier = Modifier.weight(1f).testTag("export_marksheet_pdf_${res.id}")
                                    )
                                    if (canManageAssessments) {
                                        val parentPhone = state.students.firstOrNull {
                                            it.id == res.studentId || it.fullName.equals(res.studentName, ignoreCase = true)
                                        }?.let { stu -> stu.guardianPhone.ifBlank { stu.phone } }.orEmpty()
                                        LiquidPill(
                                            text = "WhatsApp Parent",
                                            selected = false,
                                            icon = Icons.AutoMirrored.Filled.Send,
                                            onClick = {
                                                if (parentPhone.isBlank()) {
                                                    viewModel.postMessage("No guardian/student phone number found for ${res.studentName}.")
                                                } else {
                                                    val launched = com.waqar.institute.communication.WhatsAppInstituteAlertHelper.sendExamResultAlert(
                                                        context = context,
                                                        guardianPhone = parentPhone,
                                                        studentName = res.studentName,
                                                        examTitle = res.examTitle,
                                                        marks = res.marksObtained,
                                                        total = res.totalMarks,
                                                        pct = formattedPctLabel,
                                                        grade = res.grade
                                                    )
                                                    if (!launched) {
                                                        viewModel.postMessage("Unable to launch WhatsApp on this device.")
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f).testTag("whatsapp_exam_result_${res.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (state.materials.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.AutoMirrored.Filled.LibraryBooks,
                            title = "No study material or homework uploaded",
                            subtitle = if (canManageAssessments) {
                                "Publish PDF lecture notes, video links, code lab exercises, and graded assignments for batches."
                            } else {
                                "Lecture notes, practical lab assignments, and study guides uploaded by your faculty will appear here."
                            },
                            ctaLabel = if (canManageAssessments) "+ Upload First Material" else null,
                            onCtaClick = if (canManageAssessments) ({ showAddMaterial = true }) else null
                        )
                    }
                } else {
                    items(state.materials, key = { it.id }) { mat ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text("${mat.materialType} • ${mat.title}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary, fontWeight = FontWeight.Bold))
                            Text("${mat.courseName} • Batch: ${mat.batchName} • Due: ${mat.dueDateIso}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.infoColor))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(mat.contentUrlOrBody, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Screenshots Allowed • Offline Ready",
                                    style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Success)
                                )
                                LiquidPill(
                                    text = "Save Offline PDF",
                                    selected = true,
                                    icon = Icons.Default.Download,
                                    onClick = {
                                        val saved = ExoPlayerLectureManager.saveOfflineStudyAssignmentPdf(
                                            context = context,
                                            title = mat.title,
                                            courseName = mat.courseName,
                                            category = mat.materialType,
                                            fileUrl = mat.contentUrlOrBody
                                        )
                                        saved.onSuccess { file ->
                                            viewModel.postMessage("Saved offline study PDF: ${file.name}")
                                        }.onFailure { err ->
                                            viewModel.postMessage("Download error: ${err.localizedMessage}")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                val activeStudentRecord = state.students.firstOrNull {
                    it.id == state.userProfile?.linkedEntityId ||
                        it.fullName.equals(state.userProfile?.displayName.orEmpty(), ignoreCase = true)
                } ?: state.students.firstOrNull()

                val autoFilledModels = run {
                    val models = mutableListOf<com.waqar.institute.certificate.AutoFilledCertificateModel>()
                    // 1. All issued certificates
                    state.certificates.forEach { cert ->
                        val matchedStudent = state.students.firstOrNull {
                            it.id == cert.studentId || it.fullName.equals(cert.studentName, ignoreCase = true)
                        }
                        models.add(
                            CertificateGeneratorViewModel.buildAutoFilledCertificate(
                                student = matchedStudent,
                                issuedCert = cert,
                                courses = state.courses,
                                examResults = state.examResults
                            )
                        )
                    }
                    // 2. Ensure active student or enrolled students also see their Locked/Unlocked dynamic certificate card
                    val candidateStudents = if (state.activePortalRole == PortalRole.ADMIN || state.activePortalRole == PortalRole.TEACHER) {
                        state.students
                    } else {
                        listOfNotNull(activeStudentRecord)
                    }
                    candidateStudents.forEach { stu ->
                        val alreadyPresent = models.any {
                            it.studentId == stu.id || it.studentName.equals(stu.fullName, ignoreCase = true)
                        }
                        if (!alreadyPresent) {
                            models.add(
                                CertificateGeneratorViewModel.buildAutoFilledCertificate(
                                    student = stu,
                                    issuedCert = null,
                                    courses = state.courses,
                                    examResults = state.examResults
                                )
                            )
                        }
                    }
                    if (models.isEmpty()) {
                        models.add(
                            CertificateGeneratorViewModel.buildAutoFilledCertificate(
                                student = activeStudentRecord,
                                issuedCert = null,
                                courses = state.courses,
                                examResults = state.examResults
                            )
                        )
                    }
                    models
                }

                items(autoFilledModels, key = { "${it.serialNo}_${it.studentId}" }) { model ->
                    WaqarOfficialDynamicCertificateCard(
                        model = model,
                        isAdmin = isAdmin,
                        onAdminUnlockAndIssue = {
                            com.waqar.institute.biometric.HardwareBiometricAttestor.signCriticalAdminActionWithCryptoObject(
                                activity = context as? Activity,
                                actionType = "CERTIFICATE_ISSUED",
                                canonicalPayload = "COMPLETION|${model.studentId}|${model.courseTitle}|${model.grade}",
                                onSigned = { sig ->
                                    viewModel.issueCertificate(
                                        certType = "COMPLETION",
                                        studentId = model.studentId,
                                        studentName = model.studentName,
                                        courseName = model.courseTitle,
                                        grade = model.grade,
                                        biometricActionSignatureHex = sig.signatureHex
                                    )
                                },
                                onFallback = { sig ->
                                    viewModel.issueCertificate(
                                        certType = "COMPLETION",
                                        studentId = model.studentId,
                                        studentName = model.studentName,
                                        courseName = model.courseTitle,
                                        grade = model.grade,
                                        biometricActionSignatureHex = sig.signatureHex
                                    )
                                }
                            )
                        },
                        onExportPdf = {
                            DocumentExportHelper.exportAndShareOfficialAutoFilledCertificatePdf(
                                context = context,
                                model = model
                            )
                        }
                    )
                }
            }
        }
    }

    if (showCreateExam) {
        val coroutineScope = rememberCoroutineScope()
        var title by remember { mutableStateOf("") }
        var course by remember { mutableStateOf(state.courses.firstOrNull()?.title ?: "ADCA") }
        var selectedBatchId by remember { mutableStateOf(state.batches.firstOrNull()?.id.orEmpty()) }
        var batch by remember { mutableStateOf(state.batches.firstOrNull()?.name ?: "Morning Batch") }
        var examDateIso by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())) }
        var examTime24h by remember { mutableStateOf("14:00") }
        var examEndTime24h by remember { mutableStateOf("15:00") }
        var duration by remember { mutableStateOf("45") }
        var durationUnitIsSeconds by remember { mutableStateOf(false) }
        var totalMarks by remember { mutableStateOf("100") }
        var passMarks by remember { mutableStateOf("40") }
        var questionsPerStudentStr by remember { mutableStateOf("1") }
        var uploadedPdfName by remember { mutableStateOf("") }
        var pdfExtractStatus by remember { mutableStateOf("") }
        var isParsingPdf by remember { mutableStateOf(false) }

        var activeQuestionTab by remember { mutableIntStateOf(0) }
        var customQuestions by remember {
            mutableStateOf(
                listOf(
                    DraftCustomMcq(
                        question = "",
                        optA = "",
                        optB = "",
                        optC = "",
                        optD = "",
                        correctIndex = 0
                    )
                )
            )
        }

        val pdfMcqPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                var displayName = uri.lastPathSegment ?: "Question_Bank.pdf"
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameIdx >= 0) {
                            displayName = cursor.getString(nameIdx) ?: displayName
                        }
                    }
                }
                uploadedPdfName = displayName
                if (title.isBlank()) {
                    title = displayName.substringBeforeLast(".").take(80)
                }
                isParsingPdf = true
                pdfExtractStatus = "Extracting serial-wise Questions, Options & Real Answers from $displayName..."
                coroutineScope.launch {
                    val result = com.example.exam.PdfMcqBankParser.extractMcqsFromDocumentUri(
                        context = context,
                        uri = uri,
                        defaultCategory = course
                    )
                    isParsingPdf = false
                    if (result.questions.isNotEmpty()) {
                        customQuestions = result.questions.map { q ->
                            DraftCustomMcq(
                                question = q.questionText,
                                optA = q.options.getOrElse(0) { "Option A" },
                                optB = q.options.getOrElse(1) { "Option B" },
                                optC = q.options.getOrElse(2) { "Option C" },
                                optD = q.options.getOrElse(3) { "Option D" },
                                correctIndex = q.correctOptionIndex.coerceIn(0, 3)
                            )
                        }
                        activeQuestionTab = 0
                        questionsPerStudentStr = result.questionsPerStudent.toString()
                        pdfExtractStatus = "✓ Extracted ${result.questions.size} serial questions (${result.extractionMethod}) with Real Answers! Each student gets ${result.questionsPerStudent} unique questions."
                        viewModel.postMessage("PDF Loaded: ${result.questions.size} serial questions extracted!")
                    } else {
                        pdfExtractStatus = result.warnings.firstOrNull()
                            ?: "Could not detect MCQs in $displayName."
                    }
                }
            }
        }

        val safeTabIdx = activeQuestionTab.coerceIn(0, (customQuestions.size - 1).coerceAtLeast(0))
        val currentDraft = customQuestions.getOrNull(safeTabIdx) ?: DraftCustomMcq("", "", "", "", "", 0)

        AlertDialog(
            onDismissRequest = { showCreateExam = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Upload Q&A PDF / Schedule Proctored Exam", color = WciColors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiquidPill(
                        text = when {
                            isParsingPdf -> "Parsing ChatGPT / PDF Questions & Real Answers..."
                            uploadedPdfName.isNotBlank() -> "Loaded: $uploadedPdfName (${customQuestions.size} Qs) ✓"
                            else -> "Upload ChatGPT Q&A File (.txt/.md/.csv/.json/.pdf)"
                        },
                        selected = uploadedPdfName.isNotBlank(),
                        icon = Icons.Default.PictureAsPdf,
                        onClick = {
                            if (!isParsingPdf) {
                                pdfMcqPickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "text/plain",
                                        "text/markdown",
                                        "text/csv",
                                        "application/json",
                                        "*/*"
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("upload_qa_pdf_button")
                    )
                    if (isParsingPdf) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WciColors.Info)
                    }
                    if (pdfExtractStatus.isNotBlank()) {
                        Text(
                            text = pdfExtractStatus,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (pdfExtractStatus.startsWith("✓")) WciColors.Success else WciColors.Warning
                            )
                        )
                    }

                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Exam Title *") }, singleLine = true)
                    if (state.batches.isNotEmpty()) {
                        Text(
                            text = "Select Target Batch (Live Enrolled Student Count):",
                            style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Info, fontWeight = FontWeight.Bold)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.batches.forEach { b ->
                                val bCount = viewModel.getStudentCountForBatch(b, state.students)
                                val isSel = (selectedBatchId.isNotBlank() && selectedBatchId == b.id) ||
                                    (selectedBatchId.isBlank() && batch.equals(b.name, ignoreCase = true))
                                LiquidPill(
                                    text = "${b.name} — $bCount Students",
                                    selected = isSel,
                                    onClick = {
                                        selectedBatchId = b.id
                                        batch = b.name
                                        if (b.courseName.isNotBlank()) {
                                            course = b.courseName
                                        }
                                    },
                                    modifier = Modifier.testTag("exam_batch_chip_${b.id}")
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Course *") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = batch, onValueChange = { batch = it }, label = { Text("Batch") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    OutlinedTextField(
                        value = examDateIso,
                        onValueChange = { examDateIso = it },
                        label = { Text("Exam Date (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = examTime24h,
                            onValueChange = { examTime24h = it },
                            label = { Text("Start (24h HH:mm)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = examEndTime24h,
                            onValueChange = { examEndTime24h = it },
                            label = { Text("End (24h HH:mm)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Duration Unit:", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textSecondary))
                        LiquidPill(
                            text = "Minutes (e.g. 30m)",
                            selected = !durationUnitIsSeconds,
                            onClick = { durationUnitIsSeconds = false }
                        )
                        LiquidPill(
                            text = "Seconds (e.g. 30s)",
                            selected = durationUnitIsSeconds,
                            onClick = {
                                durationUnitIsSeconds = true
                                if ((duration.toIntOrNull() ?: 45) > 300) duration = "30"
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = duration,
                            onValueChange = { duration = it.filter { ch -> ch.isDigit() } },
                            label = { Text(if (durationUnitIsSeconds) "Seconds" else "Mins") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(value = totalMarks, onValueChange = { totalMarks = it }, label = { Text("Total Marks") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = passMarks, onValueChange = { passMarks = it }, label = { Text("Pass Marks") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    OutlinedTextField(
                        value = questionsPerStudentStr,
                        onValueChange = { questionsPerStudentStr = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Unique Qs Per Student (Pool: ${customQuestions.size} • 1 Q/Screen)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    HorizontalDivider(color = WciColors.glassBorderSubtleColor)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Serial Questions (${customQuestions.size}) • Real Answers Verified:",
                            style = MaterialTheme.typography.titleSmall.copy(color = WciColors.Info, fontWeight = FontWeight.ExtraBold),
                            modifier = Modifier.weight(1f)
                        )
                        LiquidPill(
                            text = "+ Add Q",
                            selected = false,
                            onClick = {
                                customQuestions = customQuestions + DraftCustomMcq(
                                    question = "",
                                    optA = "",
                                    optB = "",
                                    optC = "",
                                    optD = "",
                                    correctIndex = 0
                                )
                                activeQuestionTab = customQuestions.lastIndex
                                questionsPerStudentStr = customQuestions.size.toString()
                            }
                        )
                    }

                    // Immediate Serial-Wise Question + Real Answer Preview List
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 170.dp)
                            .background(WciColors.SurfaceDeepNavy, RoundedCornerShape(10.dp))
                            .border(1.dp, WciColors.glassBorderSubtleColor, RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        customQuestions.forEachIndexed { idx, q ->
                            val ansLetter = listOf("A", "B", "C", "D").getOrElse(q.correctIndex) { "A" }
                            val ansText = listOf(q.optA, q.optB, q.optC, q.optD).getOrElse(q.correctIndex) { "" }
                            val isSelected = idx == safeTabIdx
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) WciColors.SurfaceSlateDark else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) WciColors.Info else WciColors.glassBorderSubtleColor.copy(alpha = 0.4f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { activeQuestionTab = idx }
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "Q.${idx + 1}: ${q.question.ifBlank { "(Tap to edit question)" }}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = WciColors.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 2
                                )
                                Text(
                                    text = "A) ${q.optA}  •  B) ${q.optB}  •  C) ${q.optC}  •  D) ${q.optD}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textSecondary),
                                    maxLines = 1
                                )
                                Text(
                                    text = "✓ Real Answer: $ansLetter ($ansText)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = WciColors.Success,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        customQuestions.indices.forEach { qIdx ->
                            LiquidPill(
                                text = "Q${qIdx + 1}",
                                selected = safeTabIdx == qIdx,
                                onClick = { activeQuestionTab = qIdx }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = currentDraft.question,
                        onValueChange = { newText ->
                            customQuestions = customQuestions.toMutableList().also {
                                it[safeTabIdx] = currentDraft.copy(question = newText)
                            }
                        },
                        label = { Text("Question ${safeTabIdx + 1} Text *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = currentDraft.optA,
                            onValueChange = { v ->
                                customQuestions = customQuestions.toMutableList().also {
                                    it[safeTabIdx] = currentDraft.copy(optA = v)
                                }
                            },
                            label = { Text("Option A") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = currentDraft.optB,
                            onValueChange = { v ->
                                customQuestions = customQuestions.toMutableList().also {
                                    it[safeTabIdx] = currentDraft.copy(optB = v)
                                }
                            },
                            label = { Text("Option B") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = currentDraft.optC,
                            onValueChange = { v ->
                                customQuestions = customQuestions.toMutableList().also {
                                    it[safeTabIdx] = currentDraft.copy(optC = v)
                                }
                            },
                            label = { Text("Option C") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = currentDraft.optD,
                            onValueChange = { v ->
                                customQuestions = customQuestions.toMutableList().also {
                                    it[safeTabIdx] = currentDraft.copy(optD = v)
                                }
                            },
                            label = { Text("Option D") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Text(
                        text = "Real Correct Answer for Question ${safeTabIdx + 1} (Hidden from Students):",
                        style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Success)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Option A" to 0, "Option B" to 1, "Option C" to 2, "Option D" to 3).forEach { (label, idx) ->
                            LiquidPill(
                                text = if (currentDraft.correctIndex == idx) "✓ $label" else label,
                                selected = currentDraft.correctIndex == idx,
                                onClick = {
                                    customQuestions = customQuestions.toMutableList().also {
                                        it[safeTabIdx] = currentDraft.copy(correctIndex = idx)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Publish Exam (${customQuestions.count { it.question.isNotBlank() }} Qs)",
                    enabled = title.isNotBlank() && !isParsingPdf,
                    onClick = {
                        val validItems = customQuestions.mapIndexedNotNull { idx, q ->
                            if (q.question.isBlank()) return@mapIndexedNotNull null
                            ExamQuestionItem(
                                id = "PDF_Q%03d".format(idx + 1),
                                category = course.ifBlank { "Institute Exam" },
                                questionText = q.question.trim(),
                                options = listOf(
                                    q.optA.ifBlank { "Option A" }.trim(),
                                    q.optB.ifBlank { "Option B" }.trim(),
                                    q.optC.ifBlank { "Option C" }.trim(),
                                    q.optD.ifBlank { "Option D" }.trim()
                                ),
                                correctOptionIndex = q.correctIndex.coerceIn(0, 3)
                            )
                        }
                        val perStudent = (questionsPerStudentStr.toIntOrNull() ?: validItems.size)
                            .coerceIn(1, validItems.size.coerceAtLeast(1))
                        val rawDurValue = duration.toIntOrNull() ?: if (durationUnitIsSeconds) 30 else 45
                        val computedDurationSeconds = if (durationUnitIsSeconds) {
                            rawDurValue.coerceAtLeast(10)
                        } else {
                            rawDurValue.coerceIn(1, 360) * 60
                        }
                        val computedDurationMinutes = ((computedDurationSeconds + 59) / 60).coerceAtLeast(1)
                        val serializedJson = if (validItems.isNotEmpty()) {
                            com.waqar.institute.admin.AdminPdfQuestionBankService.transformQuestionsToFirestoreSchema(
                                rawQuestions = validItems,
                                examTitle = title,
                                courseName = course,
                                batchName = batch,
                                durationMinutes = computedDurationMinutes,
                                requestedPerStudentCount = perStudent,
                                scheduledDateIso = examDateIso.trim(),
                                ownerUid = state.userProfile?.uid ?: "admin_root",
                                extractionMethod = if (uploadedPdfName.isNotBlank()) "CHATGPT_OR_PDF_BULK" else "MANUAL_ADMIN_BANK"
                            ).questionsJson
                        } else {
                            com.example.exam.PdfMcqBankParser.serializeCustomExamBankJson(
                                questions = emptyList(),
                                questionsPerStudent = perStudent
                            )
                        }
                        viewModel.createExam(
                            title = title,
                            courseName = course,
                            batchName = batch,
                            examType = "MCQ_ONLINE",
                            durationMinutes = computedDurationMinutes,
                            totalMarks = totalMarks.toIntOrNull() ?: 100,
                            passingMarks = passMarks.toIntOrNull() ?: 40,
                            scheduledDateIso = examDateIso.trim(),
                            questionsJson = serializedJson,
                            batchId = selectedBatchId,
                            startTime24h = examTime24h.trim().ifBlank { "14:00" },
                            durationSeconds = computedDurationSeconds,
                            autoAssignLabPcSeats = true
                        )
                        showCreateExam = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showCreateExam = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    inspectMasterKeyExam?.let { masterExam ->
        val masterBank = remember(masterExam.questionsJson, masterExam.courseName) {
            com.example.exam.PdfMcqBankParser.parseCustomExamBank(masterExam.questionsJson, masterExam.courseName)
        }
        AlertDialog(
            onDismissRequest = { inspectMasterKeyExam = null },
            containerColor = WciColors.elevatedSurface,
            title = {
                Column {
                    Text(
                        text = "Serial Question Bank & Real Answer Key",
                        color = WciColors.textPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                    )
                    Text(
                        text = "${masterExam.title} • ${masterBank.questions.size} Total Qs (${masterBank.perStudentCount} unique/student)",
                        style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Info)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    masterBank.questions.forEachIndexed { idx, q ->
                        val ansLetter = listOf("A", "B", "C", "D").getOrElse(q.correctOptionIndex) { "A" }
                        val ansVal = q.options.getOrElse(q.correctOptionIndex) { "" }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(WciColors.SurfaceDeepNavy, RoundedCornerShape(10.dp))
                                .border(1.dp, WciColors.glassBorderSubtleColor, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Q.${idx + 1} (${q.id}): ${q.questionText}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = WciColors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            q.options.forEachIndexed { optIdx, opt ->
                                val letter = listOf("A", "B", "C", "D").getOrElse(optIdx) { "${optIdx + 1}" }
                                Text(
                                    text = if (optIdx == q.correctOptionIndex) "✓ $letter) $opt" else "   $letter) $opt",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (optIdx == q.correctOptionIndex) WciColors.Success else WciColors.textSecondary,
                                        fontWeight = if (optIdx == q.correctOptionIndex) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                            Text(
                                text = "Real Answer: Option $ansLetter ($ansVal)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.Success
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Close",
                    onClick = { inspectMasterKeyExam = null }
                )
            }
        )
    }

    if (showAddResult) {
        var examTitle by remember { mutableStateOf(state.exams.firstOrNull()?.title ?: "ADCA Mid-Term Exam") }
        var studentName by remember { mutableStateOf(state.students.firstOrNull()?.fullName ?: "") }
        var marks by remember { mutableStateOf("88") }
        var total by remember { mutableStateOf("100") }
        var rank by remember { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { showAddResult = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Record Exam Marks", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = examTitle, onValueChange = { examTitle = it }, label = { Text("Exam Title *") }, singleLine = true)
                    OutlinedTextField(value = studentName, onValueChange = { studentName = it }, label = { Text("Student Name *") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = marks, onValueChange = { marks = it }, label = { Text("Obtained") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = total, onValueChange = { total = it }, label = { Text("Total") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = rank, onValueChange = { rank = it }, label = { Text("Rank") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save Marks",
                    enabled = studentName.isNotBlank(),
                    onClick = {
                        viewModel.recordExamResult(
                            examId = state.exams.firstOrNull()?.id ?: "exm_01",
                            examTitle = examTitle,
                            studentId = state.students.firstOrNull { it.fullName == studentName }?.id ?: "stu_01",
                            studentName = studentName,
                            marksObtained = marks.toIntOrNull() ?: 0,
                            totalMarks = total.toIntOrNull() ?: 100,
                            rank = rank.toIntOrNull() ?: 1,
                            remarks = "Verified by Examination Cell"
                        )
                        showAddResult = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddResult = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    if (showAddMaterial) {
        var title by remember { mutableStateOf("") }
        var course by remember { mutableStateOf(state.courses.firstOrNull()?.title ?: "ADCA") }
        var batch by remember { mutableStateOf(state.batches.firstOrNull()?.name ?: "All Batches") }
        var type by remember { mutableStateOf("PDF_NOTES") }
        var body by remember { mutableStateOf("") }
        var attachedFileDisplay by remember { mutableStateOf("") }

        val materialFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                var fileName = uri.lastPathSegment ?: "Study_Notes.pdf"
                var fileSizeKb = 120L
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                            if (sizeIdx >= 0) fileSizeKb = (cursor.getLong(sizeIdx) / 1024L).coerceAtLeast(1L)
                        }
                    }
                }
                attachedFileDisplay = "$fileName ($fileSizeKb KB)"
                if (title.isBlank()) {
                    title = fileName.substringBeforeLast(".")
                }
                body = if (body.isBlank()) {
                    "Attached Study File: $attachedFileDisplay • Ready for Student Portal Download"
                } else {
                    "$body\n[Attached File: $attachedFileDisplay]"
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showAddMaterial = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Upload Study Material / Attach File", color = WciColors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("PDF_NOTES", "ASSIGNMENT", "CODE_LAB", "VIDEO_LECTURE").forEach { t ->
                            LiquidPill(text = t.substringBefore("_"), selected = type == t, onClick = { type = t })
                        }
                    }
                    LiquidPill(
                        text = if (attachedFileDisplay.isBlank()) "Attach PDF / Image / Doc / ZIP File" else "Attached: $attachedFileDisplay ✓",
                        selected = attachedFileDisplay.isNotBlank(),
                        icon = Icons.Default.AttachFile,
                        onClick = {
                            materialFileLauncher.launch(arrayOf("application/pdf", "image/*", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Topic Title *") }, singleLine = true)
                    OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Course *") }, singleLine = true)
                    OutlinedTextField(value = batch, onValueChange = { batch = it }, label = { Text("Target Batch") }, singleLine = true)
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Notes Content / Assignment Instructions / Attached File *") })
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Publish to Student Portal",
                    enabled = title.isNotBlank() && body.isNotBlank(),
                    onClick = {
                        viewModel.saveStudyMaterial(title, course, batch, type, body, "")
                        showAddMaterial = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddMaterial = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    if (showIssueCertificate) {
        var certType by remember { mutableStateOf("DIPLOMA") }
        var selectedStudentId by remember { mutableStateOf(state.students.firstOrNull()?.id ?: "") }
        var studentName by remember { mutableStateOf(state.students.firstOrNull()?.fullName ?: "") }
        var rollNumber by remember { mutableStateOf(state.students.firstOrNull()?.enrollmentNo ?: "") }
        var courseName by remember { mutableStateOf(state.students.firstOrNull()?.courseName ?: "ADCA") }
        var grade by remember { mutableStateOf("A+") }

        AlertDialog(
            onDismissRequest = { showIssueCertificate = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Issue Verifiable QR Certificate", color = WciColors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.students.isNotEmpty()) {
                        Text(
                            text = "Select Enrolled Student (Auto-fills Name, Roll No & Course):",
                            style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Info, fontWeight = FontWeight.ExtraBold)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.students.forEach { stu ->
                                LiquidPill(
                                    text = "${stu.fullName} (${stu.enrollmentNo})",
                                    selected = selectedStudentId == stu.id,
                                    onClick = {
                                        selectedStudentId = stu.id
                                        studentName = stu.fullName
                                        rollNumber = stu.enrollmentNo
                                        courseName = stu.courseName
                                        val bestGrade = state.examResults
                                            .filter { it.studentId == stu.id || it.studentName.equals(stu.fullName, ignoreCase = true) }
                                            .maxByOrNull { it.marksObtained }
                                            ?.grade
                                        if (!bestGrade.isNullOrBlank()) {
                                            grade = bestGrade
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("DIPLOMA", "COMPLETION", "MERIT", "BONAFIDE").forEach { ct ->
                            LiquidPill(text = ct, selected = certType == ct, onClick = { certType = ct })
                        }
                    }
                    OutlinedTextField(value = studentName, onValueChange = { studentName = it }, label = { Text("Student Name *") }, singleLine = true)
                    OutlinedTextField(value = rollNumber, onValueChange = { rollNumber = it }, label = { Text("Student Roll Number *") }, singleLine = true)
                    OutlinedTextField(value = courseName, onValueChange = { courseName = it }, label = { Text("Course Completed *") }, singleLine = true)
                    OutlinedTextField(value = grade, onValueChange = { grade = it }, label = { Text("Grade Awarded (e.g. A+, Distinction)") }, singleLine = true)
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Issue Certificate",
                    enabled = studentName.isNotBlank() && rollNumber.isNotBlank() && courseName.isNotBlank(),
                    onClick = {
                        val matchedStu = state.students.firstOrNull {
                            it.id == selectedStudentId || it.enrollmentNo.equals(rollNumber.trim(), ignoreCase = true) ||
                                it.fullName.equals(studentName.trim(), ignoreCase = true)
                        }
                        val matchedId = matchedStu?.id ?: selectedStudentId.ifBlank { rollNumber.trim() }
                        val cType = certType
                        val sName = studentName.trim()
                        val cName = courseName.trim()
                        val cGrade = grade.trim()
                        val rNo = rollNumber.trim()
                        com.waqar.institute.biometric.HardwareBiometricAttestor.signCriticalAdminActionWithCryptoObject(
                            activity = context as? Activity,
                            actionType = "CERTIFICATE_ISSUED",
                            canonicalPayload = "$cType|$matchedId|$rNo|$cName|$cGrade",
                            onSigned = { sig ->
                                viewModel.issueCertificate(
                                    certType = cType,
                                    studentId = matchedId,
                                    studentName = sName,
                                    courseName = cName,
                                    grade = cGrade,
                                    enrollmentNo = rNo,
                                    biometricActionSignatureHex = sig.signatureHex
                                )
                            },
                            onFallback = { sig ->
                                viewModel.issueCertificate(
                                    certType = cType,
                                    studentId = matchedId,
                                    studentName = sName,
                                    courseName = cName,
                                    grade = cGrade,
                                    enrollmentNo = rNo,
                                    biometricActionSignatureHex = sig.signatureHex
                                )
                            }
                        )
                        showIssueCertificate = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showIssueCertificate = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    activeExamAttempt?.let { exam ->
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val isAdminPreview = state.activePortalRole == PortalRole.ADMIN || state.activePortalRole == PortalRole.TEACHER
        val ownerUid = state.userProfile?.uid ?: "stu_cbt"
        val studentId = state.userProfile?.linkedEntityId?.ifBlank { state.userProfile.uid } ?: "stu_cbt"
        val studentName = state.userProfile?.displayName ?: "CBT Candidate"
        val studentRecord = state.students.firstOrNull {
            it.id == studentId || it.fullName.equals(studentName, ignoreCase = true)
        }

        val examRepo = remember(context) {
            com.example.exam.ExamRepository(
                context = context.applicationContext,
                instituteRepository = com.example.data.firestore.InstituteRepository.create(context.applicationContext)
            )
        }
        val proctoredExamViewModel = remember(exam.id) { com.example.exam.ExamViewModel(examRepo) }
        val faceAuthViewModel = remember(exam.id) { com.example.biometric.ExamFaceAuthViewModel() }

        val proctoredState by proctoredExamViewModel.examState.collectAsState()
        val faceGateStatus by faceAuthViewModel.status.collectAsState()

        var sanitizationReport by remember(exam.id) {
            mutableStateOf(PreExamSecurityGate.sanitizeAndAuditExamEnvironment(context))
        }
        val isDeviceSecurityBlocked = !isAdminPreview && !sanitizationReport.isClearedToStartExam

        val livenessChallenge = remember(exam.id, studentId) {
            com.waqar.institute.biometric.HardwareBiometricAttestor.nextRandomChallenge()
        }
        val frontCameraCheck = remember(exam.id) {
            com.waqar.institute.biometric.HardwareBiometricAttestor.verifyPhysicalFrontCameraPresent(context)
        }
        val paperWatermark = remember(exam.id, studentId) {
            examRepo.computeStudentPaperWatermark(exam.id, studentId)
        }

        val sanitizedQuestions = remember(exam.id, studentId, proctoredState.sanitizedQuestions) {
            proctoredState.sanitizedQuestions
        }
        var currentIdx by remember { mutableIntStateOf(0) }
        var selectedAnswers by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
        var faceVerified by remember { mutableStateOf(false) }
        var faceStatusText by remember {
            mutableStateOf("Facial Biometric Gate: Verify Registered Student Identity (${livenessChallenge.instruction})")
        }
        var remainingSeconds by remember(exam.id) {
            mutableIntStateOf(exam.effectiveDurationSeconds)
        }
        var secondaryDisplayAlert by remember(exam.id) { mutableStateOf<String?>(null) }

        LaunchedEffect(exam.id, isDeviceSecurityBlocked) {
            if (!isDeviceSecurityBlocked) {
                runCatching {
                    com.waqar.institute.sandbox.MicroVmExamSandboxManager.activateIsolatedExamMicroVm()
                    com.waqar.institute.ipc.ZeroCopyIpcRingManager.publishPortalEventZeroCopy(
                        portalTag = "M10_CBT_EXAM",
                        eventSummary = "START:${exam.id}:$studentId:$paperWatermark"
                    )
                }
                val activity = com.example.media.SecureWindowController.tailrecFindActivity(context)
                proctoredExamViewModel.beginProctoredExamSession(
                    activity = activity,
                    exam = exam,
                    studentId = studentId,
                    studentName = studentName,
                    questionCount = 50
                )
            }
        }

        // Maha Brahmastra #4: Continuous 2-Second Native C++ Anti-Frida / ptrace / /proc/self/maps Watchdog
        LaunchedEffect(exam.id, faceVerified, isDeviceSecurityBlocked, isAdminPreview) {
            if (isAdminPreview || isDeviceSecurityBlocked) return@LaunchedEffect
            com.waqar.institute.security.NativeSecurityBridge.armHighSecurityExamWatchdog(true)
            try {
                while (true) {
                    val watchdogViolation = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.waqar.institute.security.NativeSecurityBridge.pollContinuousWatchdogViolation(context)
                    }
                    if (!watchdogViolation.isNullOrBlank()) {
                        secondaryDisplayAlert = "Native Watchdog Violation: $watchdogViolation"
                        com.waqar.institute.security.SecurityIncidentLogger.dispatchSecurityViolation(
                            context = context,
                            repository = com.example.data.firestore.InstituteRepository.create(context.applicationContext),
                            actorUid = ownerUid,
                            username = state.userProfile?.username ?: studentId,
                            fullName = studentName,
                            phoneNumber = studentRecord?.phone ?: "",
                            studentIdOrEnrollment = studentRecord?.enrollmentNo ?: studentId,
                            violationType = "NATIVE_WATCHDOG_TAMPER",
                            violationDetails = "Exam=${exam.id} | $watchdogViolation"
                        )
                        if (faceVerified) {
                            proctoredExamViewModel.submitExamNow(
                                ownerUid = ownerUid,
                                violationReason = "Anti-Cheat Rule 4 (C++ Watchdog): $watchdogViolation"
                            )
                        } else {
                            sanitizationReport = PreExamSecurityGate.sanitizeAndAuditExamEnvironment(context)
                        }
                        break
                    }
                    delay(2_000L)
                }
            } finally {
                com.waqar.institute.security.NativeSecurityBridge.armHighSecurityExamWatchdog(false)
            }
        }

        LaunchedEffect(proctoredState.selectedAnswers) {
            if (proctoredState.selectedAnswers.isNotEmpty()) {
                selectedAnswers = proctoredState.selectedAnswers
            }
        }

        LaunchedEffect(faceGateStatus) {
            when (val st = faceGateStatus) {
                is com.example.biometric.FaceVerificationGateStatus.Idle -> {
                    faceStatusText = "Facial Biometric Gate: ${livenessChallenge.instruction}"
                }
                is com.example.biometric.FaceVerificationGateStatus.ScanningFace -> {
                    faceStatusText = "Scanning Front-Camera Face Biometrics & Anti-Spoofing (${livenessChallenge.label})..."
                }
                is com.example.biometric.FaceVerificationGateStatus.VerifiedMatch -> {
                    faceVerified = true
                    val receipt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            com.waqar.institute.biometric.HardwareBiometricAttestor.issueKeystoreAttestedPassReceipt(
                                context = context,
                                subjectId = studentId,
                                portalRole = "STUDENT_EXAM",
                                challenge = livenessChallenge,
                                similarity = st.similarityPercent / 100f
                            )
                        }.getOrNull()
                    }
                    val attestTag = if (receipt?.hardwareBackedKey == true) " • TEE Attested" else ""
                    faceStatusText = "✓ Biometric Face Verified (${st.similarityPercent}% Cosine Match$attestTag) • FLAG_SECURE Active"
                }
                is com.example.biometric.FaceVerificationGateStatus.BlockedMismatch -> {
                    faceVerified = false
                    faceStatusText = st.errorMessage
                }
            }
        }

        // Enforce 2-Strike ON_PAUSE/ON_STOP Auto-Submit, Multi-Window Block & Live DisplayManager Watcher
        DisposableEffect(lifecycleOwner, exam.id, faceVerified, isDeviceSecurityBlocked) {
            val activity = com.example.media.SecureWindowController.tailrecFindActivity(context)
            AntiOverlayShield.applyWindowOverlayShield(activity, true)
            var strikeCountedInPauseCycle = false

            val unregisterDisplayWatcher = if (!isAdminPreview && !isDeviceSecurityBlocked) {
                AntiOverlayShield.observeSecondaryDisplays(context) { externalNames ->
                    secondaryDisplayAlert = "External/Virtual Display detected mid-exam (${externalNames.joinToString()})"
                    if (faceVerified) {
                        proctoredExamViewModel.onWindowFocusOrMultiWindowChanged(
                            hasFocus = false,
                            isMultiWindow = true,
                            ownerUid = ownerUid
                        )
                    } else {
                        sanitizationReport = PreExamSecurityGate.sanitizeAndAuditExamEnvironment(context)
                    }
                }
            } else {
                {}
            }

            // [A1] Hardware Kiosk LockTask + Presentation/HDMI/Screen-Share Display Watchdog
            if (activity != null && !isAdminPreview && !isDeviceSecurityBlocked) {
                com.waqar.institute.security.ExamDisplayAndKioskShield.engageExamKioskAndDisplayWatchdog(
                    activity = activity,
                    onExternalDisplayDetected = { reason ->
                        secondaryDisplayAlert = reason
                        if (faceVerified) {
                            proctoredExamViewModel.submitExamNow(
                                ownerUid = ownerUid,
                                violationReason = "A1 External Display / Screen-Share Blocked: $reason"
                            )
                        }
                    }
                )
            }

            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (isAdminPreview) return@LifecycleEventObserver
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        if (faceVerified && !strikeCountedInPauseCycle) {
                            strikeCountedInPauseCycle = true
                            proctoredExamViewModel.onAppBackgroundedOrSwitched(ownerUid)
                        }
                    }
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                        strikeCountedInPauseCycle = false
                        if (faceVerified) {
                            val inMultiWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                activity?.isInMultiWindowMode == true
                            } else false
                            proctoredExamViewModel.onWindowFocusOrMultiWindowChanged(
                                hasFocus = true,
                                isMultiWindow = inMultiWindow,
                                ownerUid = ownerUid
                            )
                        }
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                unregisterDisplayWatcher()
                if (activity != null) {
                    com.waqar.institute.security.ExamDisplayAndKioskShield.releaseExamKioskAndDisplayWatchdog(activity)
                }
                lifecycleOwner.lifecycle.removeObserver(observer)
                proctoredExamViewModel.releaseExamSecurityShield(activity)
                AntiOverlayShield.applyWindowOverlayShield(activity, false)
            }
        }

        // Preserve Face Biometric capture workflow cleanly
        val selfieCameraLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicturePreview()
        ) { capturedBitmap ->
            val photoUri = studentRecord?.photoUri.orEmpty()
            faceAuthViewModel.verifyStudentFaceForExam(
                context = context,
                studentId = studentId,
                admissionPhotoDataUri = photoUri,
                liveSelfieBitmap = capturedBitmap,
                onVerifiedSuccess = {
                    faceVerified = true
                    proctoredExamViewModel.onBiometricReVerificationSuccess()
                }
            )
        }

        LaunchedEffect(proctoredState.isSubmitted, proctoredState.submittedResult, proctoredState.lastGradingOutcome) {
            if (proctoredState.isSubmitted) {
                val outcome = proctoredState.lastGradingOutcome
                val res = proctoredState.submittedResult
                if (isAdminPreview) {
                    val marks = outcome?.scaledMarks ?: 0
                    val correct = outcome?.rawCorrectCount ?: 0
                    val totalQs = outcome?.totalQuestions ?: sanitizedQuestions.size
                    viewModel.postMessage(
                        "Admin Preview Mode: Scored $marks / ${exam.totalMarks} ($correct/$totalQs Correct) — Not saved to Student Results."
                    )
                } else {
                    val marks = res?.marksObtained ?: outcome?.scaledMarks ?: 0
                    val grade = res?.grade ?: outcome?.grade ?: "B"
                    val correct = outcome?.rawCorrectCount ?: 0
                    val totalQs = outcome?.totalQuestions ?: sanitizedQuestions.size
                    resultCountdownSeconds = 300
                    submittedResultSummary = "${exam.title}: Submitted ($marks/${exam.totalMarks} • $grade • $correct/$totalQs Correct)"
                }
                activeExamAttempt = null
            }
        }

        // Hardware-enforce FLAG_SECURE & anti-screenshot during active CBT exams
        BindScreenCapturePolicy(ScreenCapturePolicy.LOCKED_SENSITIVE)

        // Tamper-proof monotonic elapsedRealtime countdown timer supporting exact Seconds & Minutes
        val examDeadlineElapsedMs = remember(exam.id, faceVerified) {
            if (faceVerified) {
                com.example.exam.TamperProofExamClock.elapsedDeadlineAfterDurationSeconds(
                    exam.effectiveDurationSeconds
                )
            } else 0L
        }
        LaunchedEffect(exam.id, faceVerified, isDeviceSecurityBlocked) {
            if (!faceVerified || isDeviceSecurityBlocked || examDeadlineElapsedMs <= 0L) return@LaunchedEffect
            launch { com.example.exam.TamperProofExamClock.syncServerTimeOffsetAsync() }
            var elapsedCycles = 0
            while (true) {
                val left = com.example.exam.TamperProofExamClock.computeRemainingSeconds(examDeadlineElapsedMs)
                remainingSeconds = left
                if (left <= 0) break
                delay(500L)
                elapsedCycles++
                // Periodic re-verification prompt every 3 minutes (360 half-second cycles)
                if (!isAdminPreview && elapsedCycles > 0 && elapsedCycles % 360 == 0 && !proctoredState.isReVerificationRequired) {
                    proctoredExamViewModel.requestPeriodicBiometricReVerification(
                        "Periodic Proctoring Check: Re-verify student identity to confirm continuous candidate presence."
                    )
                }
                // Runtime check for OTG / external device connection mid-exam
                if (!isAdminPreview && elapsedCycles % 10 == 0) {
                    if (com.waqar.institute.security.ExamDisplayAndKioskShield.hasOtgOrExternalDeviceConnected(context)) {
                        val otgList = com.example.security.PreExamSecurityGate.detectConnectedUsbOtgOrExternalDevices(context)
                        secondaryDisplayAlert = "OTG / External Device connection detected (${otgList.joinToString()})."
                        proctoredExamViewModel.submitExamNow(
                            ownerUid = ownerUid,
                            violationReason = "Anti-Cheating Block: OTG or external device connected during exam (${otgList.joinToString()})"
                        )
                        break
                    }
                }
            }
            proctoredExamViewModel.submitExamNow(
                ownerUid = ownerUid,
                violationReason = if (isAdminPreview) null else "Timer Expired",
                isPreviewOnly = isAdminPreview
            )
        }

        val currentQuestion = sanitizedQuestions.getOrNull(currentIdx)
        val minsLeft = remainingSeconds / 60
        val secsLeft = remainingSeconds % 60

        AlertDialog(
            onDismissRequest = { activeExamAttempt = null },
            containerColor = WciColors.elevatedSurface,
            title = {
                Column {
                    Text(
                        text = if (isAdminPreview) "Admin Preview CBT • ${exam.title}" else "Proctored CBT • ${exam.title}",
                        color = WciColors.textPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                    )
                    Text(
                        text = "Time Left: %02d:%02d • Strikes: ${proctoredState.backgroundStrikes}/${proctoredState.maxAllowedStrikes} • Paper: ${sanitizedQuestions.size} Qs • $paperWatermark".format(minsLeft, secsLeft),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMono,
                            color = WciColors.Success
                        )
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Pre-Exam Security & Biometric Status Strip
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(WciColors.SurfaceDeepNavy, RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                (if (isDeviceSecurityBlocked) WciColors.Danger else WciColors.Warning).copy(alpha = 0.6f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (sanitizationReport.isClearedToStartExam && !proctoredState.isMultiWindowBlocked && secondaryDisplayAlert == null) {
                                    "✓ Pre-Exam Sanitizer: Clipboard Scrubbed • Watchdog 2s & FLAG_SECURE Ready"
                                } else {
                                    "⚠ Security Alert: ${secondaryDisplayAlert ?: proctoredState.securityAlertBanner ?: sanitizationReport.blockingReasons.firstOrNull().orEmpty()}"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (sanitizationReport.isClearedToStartExam && !proctoredState.isMultiWindowBlocked && secondaryDisplayAlert == null) {
                                        WciColors.Success
                                    } else {
                                        WciColors.Danger
                                    }
                                )
                            )
                            Text(
                                text = faceStatusText,
                                style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Warning)
                            )
                        }
                    }

                    if (isDeviceSecurityBlocked) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Exam Access Blocked Until Security Issues Are Resolved:",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = WciColors.Danger,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            sanitizationReport.blockingReasons.forEach { reason ->
                                Text(
                                    text = "• $reason",
                                    style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textPrimary)
                                )
                            }
                            ShimmerButton(
                                text = "Re-Check Device Security",
                                onClick = {
                                    sanitizationReport = PreExamSecurityGate.sanitizeAndAuditExamEnvironment(context)
                                    secondaryDisplayAlert = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recheck_exam_security_button")
                            )
                        }
                    } else if (!faceVerified) {
                        if (!frontCameraCheck.hasPhysicalFrontCamera && !isAdminPreview) {
                            Text(
                                text = "Hardware Camera Warning: ${frontCameraCheck.reason}",
                                style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Danger)
                            )
                        }
                        ShimmerButton(
                            text = "Verify Student Face (${livenessChallenge.label})",
                            icon = Icons.Default.FaceRetouchingNatural,
                            onClick = {
                                if (!frontCameraCheck.hasPhysicalFrontCamera && !isAdminPreview) {
                                    faceStatusText = "Physical front camera missing: ${frontCameraCheck.reason}"
                                    viewModel.postMessage(faceStatusText)
                                } else {
                                    runCatching {
                                        selfieCameraLauncher.launch(null)
                                    }.onFailure {
                                        faceStatusText = "Front camera unavailable on this device. Proctored CBT requires a working camera."
                                        viewModel.postMessage(faceStatusText)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("verify_exam_face_biometric_button")
                        )
                    } else if (proctoredState.isMultiWindowBlocked) {
                        Text(
                            text = proctoredState.securityAlertBanner
                                ?: "Split-Screen / Floating Window mode is blocked during proctored CBT exams.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.Danger)
                        )
                    } else if (proctoredState.isReVerificationRequired) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(WciColors.SurfaceDeepNavy, RoundedCornerShape(14.dp))
                                .border(1.dp, WciColors.Warning, RoundedCornerShape(14.dp))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Face,
                                contentDescription = "Periodic Re-Verification",
                                tint = WciColors.Warning,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "Periodic Biometric Re-Verification Required",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = WciColors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = proctoredState.reVerificationPrompt
                                    ?: "Candidate presence check: Please perform a live face scan to continue your assessment.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = WciColors.textSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            )
                            ShimmerButton(
                                text = "Verify Face Liveness",
                                onClick = {
                                    runCatching {
                                        selfieCameraLauncher.launch(null)
                                    }.onFailure {
                                        viewModel.postMessage("Camera launch failed: ${it.localizedMessage}")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (currentQuestion != null) {
                        // Strict 1-Question-Per-Screen Anti-Cheat Focus View (Copy/Cut/Paste/Toolbar Disabled)
                        CompositionLocalProvider(LocalTextToolbar provides NoOpExamTextToolbar) {
                            DisableSelection {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "1 Question/Screen • Unique Student Set • Anti-Cheat Copy/Toolbar Locked",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = WciColors.Warning,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "Question ${currentIdx + 1} of ${sanitizedQuestions.size} (${currentQuestion.category}) • Answered: ${selectedAnswers.size}/${sanitizedQuestions.size}",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = JetBrainsMono,
                                            color = WciColors.Info
                                        )
                                    )
                                    Text(
                                        text = currentQuestion.questionText,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = WciColors.textPrimary
                                        )
                                    )
                                    val chosenIdx = selectedAnswers[currentQuestion.id] ?: -1
                                    currentQuestion.options.forEachIndexed { idx, opt ->
                                        val prefix = listOf("A", "B", "C", "D").getOrElse(idx) { "${idx + 1}" }
                                        LiquidPill(
                                            text = "$prefix) $opt",
                                            selected = chosenIdx == idx,
                                            onClick = {
                                                proctoredExamViewModel.selectOption(currentQuestion.id, idx)
                                                selectedAnswers = selectedAnswers.toMutableMap().apply {
                                                    put(currentQuestion.id, idx)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            LiquidPill(
                                text = "◀ Previous",
                                selected = false,
                                onClick = {
                                    if (currentIdx > 0) {
                                        currentIdx--
                                        proctoredExamViewModel.navigateToQuestion(currentIdx)
                                    }
                                }
                            )
                            LiquidPill(
                                text = "Next Question ▶",
                                selected = true,
                                onClick = {
                                    if (currentIdx < sanitizedQuestions.lastIndex) {
                                        currentIdx++
                                        proctoredExamViewModel.navigateToQuestion(currentIdx)
                                    }
                                }
                            )
                        }
                    } else {
                        Text(
                            text = "No questions uploaded for this exam yet. Contact your Admin or Teacher to attach an MCQ question paper.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.Warning)
                        )
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = if (isAdminPreview) {
                        "Finish Preview (${selectedAnswers.size}/${sanitizedQuestions.size})"
                    } else {
                        "Submit CBT (${selectedAnswers.size}/${sanitizedQuestions.size})"
                    },
                    enabled = !isDeviceSecurityBlocked && faceVerified && !proctoredState.isMultiWindowBlocked && sanitizedQuestions.isNotEmpty(),
                    onClick = {
                        proctoredExamViewModel.submitExamNow(
                            ownerUid = ownerUid,
                            violationReason = null,
                            isPreviewOnly = isAdminPreview
                        )
                    },
                    modifier = Modifier.testTag("submit_cbt_exam_button")
                )
            },
            dismissButton = { TextButton(onClick = { activeExamAttempt = null }) { Text("Exit", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    submittedResultSummary?.let { summary ->
        LaunchedEffect(summary) {
            while (resultCountdownSeconds > 0) {
                delay(1000L)
                resultCountdownSeconds--
            }
        }
        val cdMin = resultCountdownSeconds / 60
        val cdSec = resultCountdownSeconds % 60
        AlertDialog(
            onDismissRequest = { submittedResultSummary = null },
            containerColor = WciColors.elevatedSurface,
            title = {
                Text(
                    "Exam Submitted • Result Publication Timer",
                    color = WciColors.Success,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = WciColors.textPrimary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Text(
                        text = "Official Marksheet Verification Countdown: %02d:%02d".format(cdMin, cdSec),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMono,
                            color = WciColors.Info,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Text(
                        text = "Your answers have been graded and synced to Cloud Firestore. Your official graded marksheet with Rank & PDF download is ready.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                    )
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Open Official Marksheet Now",
                    icon = Icons.Default.EmojiEvents,
                    onClick = {
                        submittedResultSummary = null
                        activeTab = 1
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { submittedResultSummary = null }) {
                    Text("Close", color = WciColors.textSecondary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// ============================================================================
// M16 & M17: INBOX, BROADCAST ANNOUNCEMENTS & LIVE CLASSES (F339–F374)
// ============================================================================

@Composable
fun InboxAndLiveClassesModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    initialTab: Int = 0
) {
    val canBroadcastOrHost = state.activePortalRole == PortalRole.ADMIN || state.activePortalRole == PortalRole.TEACHER
    var activeTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var showBroadcastDialog by remember { mutableStateOf(false) }
    var showScheduleLive by remember { mutableStateOf(false) }
    var selectedLiveClassId by remember(state.liveClasses) {
        mutableStateOf(state.liveClasses.firstOrNull()?.id ?: "")
    }
    var videoStageMode by remember { mutableStateOf("AGORA") }
    val activeSession = state.liveClasses.firstOrNull { it.id == selectedLiveClassId }
        ?: state.liveClasses.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidPill(
                        text = "Notices & Inbox",
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        badgeCount = state.announcements.size
                    )
                    LiquidPill(
                        text = "Live Classes",
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        badgeCount = state.liveClasses.size
                    )
                }
                if (canBroadcastOrHost) {
                    ShimmerButton(
                        text = if (activeTab == 0) "+ Broadcast" else "+ Live Class",
                        onClick = { if (activeTab == 0) showBroadcastDialog = true else showScheduleLive = true }
                    )
                }
            }
        }

        if (activeTab == 0) {
            if (state.announcements.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.Campaign,
                        title = "No announcements in your Inbox yet",
                        subtitle = if (canBroadcastOrHost) {
                            "Broadcast holiday alerts, exam dates, fee reminders, and batch notices to All, Students, Teachers, or Parents."
                        } else {
                            "Official holiday notices, exam schedules, and batch announcements from Waqar Computer Institute appear here."
                        },
                        ctaLabel = if (canBroadcastOrHost) "+ Broadcast First Notice" else null,
                        onCtaClick = if (canBroadcastOrHost) ({ showBroadcastDialog = true }) else null
                    )
                }
            } else {
                items(state.announcements, key = { it.id }) { ann ->
                    val prioColor = when (ann.priority) {
                        "URGENT" -> WciColors.Danger
                        "IMPORTANT" -> WciColors.Info
                        else -> WciColors.Success
                    }
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = prioColor
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(prioColor.copy(alpha = 0.22f), RoundedCornerShape(WciRadius.pill))
                                        .border(1.dp, prioColor, RoundedCornerShape(WciRadius.pill))
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = ann.priority,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMono,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = prioColor,
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                                Text(
                                    text = "AUDIENCE: ${ann.targetAudience}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.textMuted,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                            Text(
                                text = ann.channel,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.infoColor,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = ann.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = ann.body,
                            style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "From: ${ann.senderName}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.Info
                                )
                            )
                            Icon(
                                imageVector = Icons.Default.MarkEmailRead,
                                contentDescription = "Verified Notice",
                                tint = WciColors.Success,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        } else {
            if (state.liveClasses.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.Videocam,
                        title = "No live classes or lab bookings active",
                        subtitle = if (canBroadcastOrHost) {
                            "Launch interactive virtual classes or computer lab practical sessions with Agora live video, audio, and polls."
                        } else {
                            "Upcoming live classes, computer lab practical sessions, and interactive polls scheduled for your batch appear here."
                        },
                        ctaLabel = if (canBroadcastOrHost) "+ Start Live Class" else null,
                        onCtaClick = if (canBroadcastOrHost) ({ showScheduleLive = true }) else null
                    )
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidPill(
                            text = "Live Video Room (Agora RTC)",
                            selected = videoStageMode == "AGORA",
                            icon = Icons.Default.Videocam,
                            onClick = { videoStageMode = "AGORA" }
                        )
                        LiquidPill(
                            text = "HD Recorded Lecture",
                            selected = videoStageMode == "RECORDED",
                            icon = Icons.Default.PlayCircle,
                            onClick = { videoStageMode = "RECORDED" }
                        )
                    }
                }
                item {
                    val sessionTitle = activeSession?.title ?: "Waqar Institute Live Classroom"
                    val channelCode = (activeSession?.meetingCode?.ifBlank { activeSession.id } ?: "wci_live_room")
                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    val hostDisplay = activeSession?.teacherName ?: "Waqar Sir"

                    if (videoStageMode == "AGORA") {
                        AgoraLiveVideoClassroomRoom(
                            channelId = channelCode,
                            sessionTitle = sessionTitle,
                            hostName = hostDisplay,
                            isHostRole = canBroadcastOrHost
                        )
                    } else {
                        val uploadedLectureUrl = remember(state.materials, activeSession) {
                            state.materials.firstOrNull { mat ->
                                val body = mat.contentUrlOrBody.trim()
                                val looksLikeVideoUrl = (body.startsWith("http://", ignoreCase = true) ||
                                    body.startsWith("https://", ignoreCase = true)) &&
                                    (mat.materialType.equals("VIDEO", ignoreCase = true) ||
                                        body.contains(".mp4", ignoreCase = true) ||
                                        body.contains(".m3u8", ignoreCase = true))
                                val batchMatches = activeSession == null ||
                                    mat.batchName.equals(activeSession.batchName, ignoreCase = true) ||
                                    mat.batchName.isBlank()
                                looksLikeVideoUrl && batchMatches
                            }?.contentUrlOrBody?.trim()
                        }
                        ExoPlayerHdLecturePlayer(
                            streamUrl = uploadedLectureUrl ?: ExoPlayerLectureManager.DEFAULT_SAMPLE_LECTURE_STREAM,
                            title = sessionTitle
                        )
                    }
                }
                items(state.liveClasses, key = { it.id }) { lc ->
                    val isSelected = activeSession?.id == lc.id
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = if (isSelected) WciColors.Danger else WciColors.Success,
                        onClick = { selectedLiveClassId = lc.id }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${lc.title} (${lc.meetingCode})", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                                Text("Batch: ${lc.batchName} • Host: ${lc.teacherName}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                                Text("Schedule: ${lc.scheduledTimeIso} (${lc.durationMinutes}m)", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                                if (lc.activePollQuestion.isNotBlank()) {
                                    Text("Live Poll: ${lc.activePollQuestion}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Warning))
                                }
                            }
                            Text(
                                text = if (isSelected) "ACTIVE" else lc.status,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = if (isSelected) WciColors.Danger else WciColors.Success
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBroadcastDialog) {
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        var audience by remember { mutableStateOf("ALL") }
        var channel by remember { mutableStateOf("IN_APP") }
        var priority by remember { mutableStateOf("IMPORTANT") }

        AlertDialog(
            onDismissRequest = { showBroadcastDialog = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Broadcast Notice", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Announcement Title *") }, singleLine = true)
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Message Body *") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ALL", "STUDENTS", "TEACHERS", "PARENTS").forEach { aud ->
                            LiquidPill(text = aud, selected = audience == aud, onClick = { audience = aud })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("NORMAL", "IMPORTANT", "URGENT").forEach { pr ->
                            LiquidPill(text = pr, selected = priority == pr, onClick = { priority = pr })
                        }
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Send Broadcast",
                    enabled = title.isNotBlank() && body.isNotBlank(),
                    onClick = {
                        viewModel.publishAnnouncement(title, body, audience, channel, priority)
                        showBroadcastDialog = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showBroadcastDialog = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    if (showScheduleLive) {
        var title by remember { mutableStateOf("") }
        var batch by remember { mutableStateOf(state.batches.firstOrNull()?.name ?: "ADCA Morning Batch") }
        var teacher by remember { mutableStateOf(state.userProfile?.displayName ?: "Waqar Sir") }
        var poll by remember { mutableStateOf("Have you completed today's Tally GST voucher exercise?") }

        AlertDialog(
            onDismissRequest = { showScheduleLive = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Start Live Class & Lab Poll", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Session Topic *") }, singleLine = true)
                    OutlinedTextField(value = batch, onValueChange = { batch = it }, label = { Text("Batch Name *") }, singleLine = true)
                    OutlinedTextField(value = teacher, onValueChange = { teacher = it }, label = { Text("Faculty Host") }, singleLine = true)
                    OutlinedTextField(value = poll, onValueChange = { poll = it }, label = { Text("Interactive Poll Question") })
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Launch Live Room",
                    enabled = title.isNotBlank(),
                    onClick = {
                        viewModel.scheduleLiveClass(title, batch, teacher, "", 60, poll)
                        showScheduleLive = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showScheduleLive = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }
}

// ============================================================================
// M18: GEMINI AI TUTOR & ADMIN AI ASSISTANT HUB (F375–F390)
// ============================================================================

@Composable
fun AiHubModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel
) {
    val context = LocalContext.current
    val palette = LocalRolePalette.current
    var selectedFeature by remember { mutableStateOf("TUTOR_CHAT") }
    var promptInput by remember { mutableStateOf("") }

    // Zero-permission Android Photo Picker for Multimodal Doubt Solving (Play Policy compliant)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                viewModel.analyzeImageWithAi(
                    bitmap = bitmap,
                    question = promptInput.ifBlank { "Explain this computer science screenshot, code snippet, or question." }
                )
            }
        }
    }

    val totalTokensUsed = remember(state.aiLogs) { state.aiLogs.sumOf { it.tokensUsed } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .neonBorder(palette = palette, cornerRadius = WciRadius.xl),
                cornerRadius = WciRadius.xl,
                glowColor = palette.glow,
                strongSurface = true
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Waqar Computer Institute AI",
                            style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.textPrimary, fontWeight = FontWeight.ExtraBold)
                        )
                        Text(
                            "Instant Sub-ms Knowledge Base • ChatGPT & AI Academic Tutor • Vision Doubt Solver",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                        )
                    }
                    if (state.activePortalRole == PortalRole.ADMIN) {
                        Column(horizontalAlignment = Alignment.End) {
                            CountUpInt(targetValue = totalTokensUsed, suffix = " tok", color = WciColors.Info, fontSizeSp = 18)
                            Text("${state.aiLogs.size} AI sessions", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                        }
                    }
                }
            }
        }

        // Feature Mode Selector Pills
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "TUTOR_CHAT" to "AI Doubt Tutor",
                    "QUIZ_GEN" to "Generate 5-MCQ Quiz",
                    "LESSON_PLAN" to "Lab Lesson Plan",
                    "NOTICE_DRAFT" to "Draft Notice",
                    "RISK_ANALYSIS" to "Cohort Analysis"
                ).forEach { (key, label) ->
                    LiquidPill(
                        text = label,
                        selected = selectedFeature == key,
                        icon = Icons.Default.AutoAwesome,
                        onClick = { selectedFeature = key }
                    )
                }
            }
        }

        // Prompt Composer Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_prompt_input"),
                    label = {
                        Text(
                            when (selectedFeature) {
                                "QUIZ_GEN" -> "Enter topic for 5-MCQ Quiz (e.g., Tally Prime GST, Python Loops, HTML Forms)"
                                "LESSON_PLAN" -> "Enter topic for 60-min Lab Lesson Plan"
                                "NOTICE_DRAFT" -> "Enter announcement topic (e.g., Exam Schedule, Batch Timing)"
                                "RISK_ANALYSIS" -> "Ask Waqar Computer Institute AI to analyze fee collection & attendance"
                                else -> "Ask any DCA/ADCA, Tally Prime, Python, Excel, or computer doubt..."
                            }
                        )
                    },
                    minLines = 3
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiquidPill(
                        text = "Attach Image / Code Photo",
                        selected = false,
                        icon = Icons.Default.AddPhotoAlternate,
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    ShimmerButton(
                        text = if (state.isAiStreaming) "Generating..." else "Ask Waqar AI",
                        icon = Icons.Default.AutoAwesome,
                        enabled = !state.isAiStreaming && promptInput.isNotBlank(),
                        onClick = {
                            viewModel.askAiAssistant(selectedFeature, promptInput)
                        },
                        testTag = "ai_generate_button"
                    )
                }
            }
        }

        // Live Streaming Output Card
        if (state.isAiStreaming || state.currentAiStreamText.isNotBlank()) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowColor = WciColors.Info
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = WciColors.Info)
                        Text(
                            text = if (state.isAiStreaming) "Waqar Computer Institute AI is responding..." else "Waqar Computer Institute AI Response",
                            style = MaterialTheme.typography.titleSmall.copy(color = WciColors.Info)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.currentAiStreamText,
                        style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.textPrimary)
                    )
                }
            }
        }

        // Persisted AI History
        item {
            Text(
                text = "Recent Waqar AI Answers",
                style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary)
            )
        }

        if (state.aiLogs.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.Psychology,
                    title = "Ask Waqar Computer Institute AI",
                    subtitle = "Get instant answers on Tally Prime shortcuts, MS Excel formulas, Python programs, or ADCA exam questions.",
                    ctaLabel = "Ask Sample Python Question",
                    onCtaClick = {
                        promptInput = "Explain Python lists vs dictionaries with a practical student marks example."
                        viewModel.askAiAssistant("TUTOR_CHAT", promptInput)
                    }
                )
            }
        } else {
            items(state.aiLogs.take(10), key = { it.id }) { log ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Waqar Computer Institute AI • ${log.featureType}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono, color = palette.start)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Q: ${log.promptText}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(log.responseText, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary))
                }
            }
        }
    }
}
