package com.example.ui.modules

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
import com.example.exam.QuestionBank
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

private data class DraftCustomMcq(
    val question: String,
    val optA: String,
    val optB: String,
    val optC: String,
    val optD: String,
    val correctIndex: Int
)

private fun parseCustomExamQuestions(questionsJson: String, courseName: String): List<ExamQuestionItem> {
    if (questionsJson.isBlank() || questionsJson == "[]") return emptyList()
    return runCatching {
        val arr = JSONArray(questionsJson)
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
        list
    }.getOrDefault(emptyList())
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
    var submittedResultSummary by remember { mutableStateOf<String?>(null) }
    var resultCountdownSeconds by remember { mutableIntStateOf(300) }

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
                    style = MaterialTheme.typography.headlineMedium.copy(color = Color.White, fontWeight = FontWeight.ExtraBold),
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
                val latestScheduledExam = state.exams.firstOrNull()
                item {
                    // Always-visible 3D Claymorphism "Upcoming Exam" Hero Card with Date & 24-Hour Time
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 28.dp,
                        glowColor = Color(0xFF818CF8),
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
                                        Color(0xFF818CF8).copy(alpha = 0.28f),
                                        RoundedCornerShape(WciRadius.pill)
                                    )
                                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(WciRadius.pill))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "UPCOMING EXAM • ONLINE CBT",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                )
                            }
                            Text(
                                text = if (latestScheduledExam != null) {
                                    "Schedule: ${latestScheduledExam.scheduledDateIso.ifBlank { "Live Now" }} • ${latestScheduledExam.durationMinutes} Mins"
                                } else {
                                    "24-Hour Schedule Ready • 45 Mins"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.Success
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = latestScheduledExam?.title ?: "Upcoming Exam — Awaiting Admin Schedule",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (latestScheduledExam != null) {
                                "${latestScheduledExam.courseName} • Batch: ${latestScheduledExam.batchName} • Scheduled Date & Time (24h): ${latestScheduledExam.scheduledDateIso.ifBlank { "Today" }}"
                            } else if (canManageAssessments) {
                                "Publish an online MCQ test with custom questions, 4 options, 1 real correct answer, any date, and 24-hour clock time."
                            } else {
                                "Your next batch examination is being prepared by the Examination Cell. As soon as Admin publishes the exam time, the Start Exam button unlocks automatically."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    (if (latestScheduledExam != null) "LIVE" else "03") to "Date",
                                    (if (latestScheduledExam != null) latestScheduledExam.scheduledDateIso.takeLast(5).ifBlank { "14:00" } else "14:00") to "24h Time",
                                    "${latestScheduledExam?.durationMinutes ?: 45}" to "Mins"
                                ).forEach { (v, u) ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color(0xFF1A255C).copy(alpha = 0.90f))
                                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = v,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontFamily = JetBrainsMono,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color(0xFF00E5FF)
                                                )
                                            )
                                            Text(
                                                text = u,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 10.sp,
                                                    color = WciColors.TextSecondaryDark
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            if (canManageAssessments) {
                                ShimmerButton(
                                    text = "+ Schedule Exam",
                                    onClick = { showCreateExam = true }
                                )
                            } else if (state.activePortalRole == PortalRole.STUDENT) {
                                LiquidPill(
                                    text = if (latestScheduledExam != null) "Start Exam Now" else "Practice Demo Test",
                                    selected = true,
                                    icon = Icons.Default.PlayArrow,
                                    onClick = {
                                        activeExamAttempt = latestScheduledExam ?: ExamRecord(
                                            id = "practice_cbt_01",
                                            title = "ADCA & Tally Prime Practice CBT",
                                            courseName = state.students.firstOrNull()?.courseName ?: "ADCA",
                                            batchName = state.students.firstOrNull()?.batchName ?: "Morning Batch",
                                            examType = "PRACTICE_MCQ",
                                            durationMinutes = 30,
                                            totalMarks = 100,
                                            passingMarks = 40,
                                            scheduledDateIso = "2026-11-20 14:00",
                                            questionsJson = "[]"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                if (state.exams.isNotEmpty()) {
                    items(state.exams, key = { it.id }) { exam ->
                        val customQCount = remember(exam.questionsJson) {
                            parseCustomExamQuestions(exam.questionsJson, exam.courseName).size
                        }
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            glowColor = Color(0xFF818CF8)
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
                                                    Color(0xFF818CF8).copy(alpha = 0.25f),
                                                    RoundedCornerShape(WciRadius.pill)
                                                )
                                                .border(1.dp, Color(0xFF818CF8), RoundedCornerShape(WciRadius.pill))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = exam.examType,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = JetBrainsMono,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00E5FF),
                                                    fontSize = 10.sp
                                                )
                                            )
                                        }
                                        Text(
                                            text = "Date & 24h Time: ${exam.scheduledDateIso.ifBlank { "Immediate" }} • ${exam.durationMinutes} Mins",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = JetBrainsMono,
                                                color = WciColors.Success
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = exam.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    )
                                    Text(
                                        text = "${exam.courseName} • Batch: ${exam.batchName}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                                    )
                                    Text(
                                        text = "Max Marks: ${exam.totalMarks} • Passing Marks: ${exam.passingMarks} • ${if (customQCount > 0) "$customQCount Custom Admin MCQs" else "50 Pool MCQs"}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMono,
                                            color = WciColors.TextSecondaryDark
                                        )
                                    )
                                }
                                if (state.activePortalRole != PortalRole.PARENT) {
                                    LiquidPill(
                                        text = if (state.activePortalRole == PortalRole.STUDENT) "Start Exam" else "Preview CBT",
                                        selected = true,
                                        icon = Icons.Default.PlayArrow,
                                        onClick = { activeExamAttempt = exam }
                                    )
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
                    items(state.examResults, key = { it.id }) { res ->
                        val percentage = if (res.totalMarks > 0) (res.marksObtained * 100) / res.totalMarks else 0
                        val isPassed = res.status.uppercase() != "FAIL" && percentage >= 40
                        val statusColor = if (isPassed) WciColors.Success else WciColors.Danger

                        // Rich Multi-Section Institute Marksheet Card
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            glowColor = if (isPassed) Color(0xFFA855F7) else WciColors.Danger,
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
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "OFFICIAL EXAMINATION MARKSHEET",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMono,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF00E5FF),
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                brush = Brush.linearGradient(
                                                    listOf(Color(0xFFA855F7), Color(0xFFEC4899))
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
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                Color(0xFF14F195).copy(alpha = 0.22f),
                                                RoundedCornerShape(WciRadius.pill)
                                            )
                                            .border(1.dp, Color(0xFF14F195), RoundedCornerShape(WciRadius.pill))
                                            .padding(horizontal = 10.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "RANK #${res.rank}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF14F195),
                                                fontSize = 11.sp
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
                                            color = Color.White,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = res.examTitle,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = Color(0xFF00E5FF),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${state.preferences.instituteName} • Session ${state.preferences.academicSession}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = WciColors.TextSecondaryDark
                                        )
                                    )
                                }
                                DonutProgressRing(
                                    progress = percentage / 100f,
                                    color = statusColor,
                                    label = "$percentage%",
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
                                        .background(Color(0xFF121A45).copy(alpha = 0.85f))
                                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(WciRadius.md))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "MARKS OBTAINED",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 10.sp,
                                                color = WciColors.TextMutedDark
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${res.marksObtained} / ${res.totalMarks}",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White
                                            )
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(WciRadius.md))
                                        .background(Color(0xFF121A45).copy(alpha = 0.85f))
                                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(WciRadius.md))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "PERCENTAGE",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 10.sp,
                                                color = WciColors.TextMutedDark
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "$percentage.0%",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF00E5FF)
                                            )
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(WciRadius.md))
                                        .background(Color(0xFF121A45).copy(alpha = 0.85f))
                                        .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(WciRadius.md))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "VERDICT",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 10.sp,
                                                color = WciColors.TextMutedDark
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

                            // 4. Footer Row: Examiner Remarks + Official PDF Export
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = res.remarks.ifBlank { "Verified by Controller of Examinations" },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = WciColors.TextSecondaryDark
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                LiquidPill(
                                    text = "Download PDF Marksheet",
                                    selected = true,
                                    icon = Icons.Default.PictureAsPdf,
                                    onClick = {
                                        DocumentExportHelper.exportAndShareMarkSheetPdf(
                                            context = context,
                                            instituteName = state.preferences.instituteName,
                                            result = res
                                        )
                                    }
                                )
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
                            Text("${mat.materialType} • ${mat.title}", style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold))
                            Text("${mat.courseName} • Batch: ${mat.batchName} • Due: ${mat.dueDateIso}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(mat.contentUrlOrBody, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
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
                            viewModel.issueCertificate(
                                certType = "COMPLETION",
                                studentId = model.studentId,
                                studentName = model.studentName,
                                courseName = model.courseTitle,
                                grade = model.grade
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
        var title by remember { mutableStateOf("") }
        var course by remember { mutableStateOf(state.courses.firstOrNull()?.title ?: "ADCA") }
        var batch by remember { mutableStateOf(state.batches.firstOrNull()?.name ?: "Morning Batch") }
        var examDateIso by remember { mutableStateOf("2026-11-20") }
        var examTime24h by remember { mutableStateOf("14:30") }
        var duration by remember { mutableStateOf("45") }
        var totalMarks by remember { mutableStateOf("100") }
        var passMarks by remember { mutableStateOf("40") }

        var activeQuestionTab by remember { mutableIntStateOf(0) }
        var customQuestions by remember {
            mutableStateOf(
                listOf(
                    DraftCustomMcq(
                        question = "Which shortcut key is used in Tally Prime to post a Payment Voucher?",
                        optA = "F4",
                        optB = "F5",
                        optC = "F6",
                        optD = "F8",
                        correctIndex = 1
                    ),
                    DraftCustomMcq(
                        question = "In MS Excel, every formula or function must begin with which symbol?",
                        optA = "= (Equal sign)",
                        optB = "# (Hash)",
                        optC = "@ (At symbol)",
                        optD = "$ (Dollar)",
                        correctIndex = 0
                    ),
                    DraftCustomMcq(
                        question = "Which data type in Python stores key-value pairs?",
                        optA = "list",
                        optB = "tuple",
                        optC = "dict",
                        optD = "set",
                        correctIndex = 2
                    ),
                    DraftCustomMcq(
                        question = "What does HTML stand for in Web Development?",
                        optA = "HyperText Markup Language",
                        optB = "HighText Machine Language",
                        optC = "HyperLoop Tool Language",
                        optD = "Home Tool Markup Language",
                        correctIndex = 0
                    )
                )
            )
        }

        val currentDraft = customQuestions[activeQuestionTab]

        AlertDialog(
            onDismissRequest = { showCreateExam = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Publish Online CBT Exam (24h Clock + 4 Questions)", color = WciColors.TextPrimaryDark) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Exam Title *") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Course *") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = batch, onValueChange = { batch = it }, label = { Text("Batch") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = examDateIso,
                            onValueChange = { examDateIso = it },
                            label = { Text("Exam Date (YYYY-MM-DD)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = examTime24h,
                            onValueChange = { examTime24h = it },
                            label = { Text("24-Hour Time (HH:mm)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = duration, onValueChange = { duration = it }, label = { Text("Mins") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = totalMarks, onValueChange = { totalMarks = it }, label = { Text("Total Marks") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = passMarks, onValueChange = { passMarks = it }, label = { Text("Pass Marks") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }

                    HorizontalDivider(color = WciColors.GlassBorderSubtle)
                    Text(
                        text = "Write 4 Exam Questions (4 Options Each + Select 1 Real Answer):",
                        style = MaterialTheme.typography.titleSmall.copy(color = WciColors.Info, fontWeight = FontWeight.ExtraBold)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (0 until 4).forEach { qIdx ->
                            LiquidPill(
                                text = "Q${qIdx + 1}",
                                selected = activeQuestionTab == qIdx,
                                onClick = { activeQuestionTab = qIdx },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = currentDraft.question,
                        onValueChange = { newText ->
                            customQuestions = customQuestions.toMutableList().also {
                                it[activeQuestionTab] = currentDraft.copy(question = newText)
                            }
                        },
                        label = { Text("Question ${activeQuestionTab + 1} Text *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = currentDraft.optA,
                            onValueChange = { v ->
                                customQuestions = customQuestions.toMutableList().also {
                                    it[activeQuestionTab] = currentDraft.copy(optA = v)
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
                                    it[activeQuestionTab] = currentDraft.copy(optB = v)
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
                                    it[activeQuestionTab] = currentDraft.copy(optC = v)
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
                                    it[activeQuestionTab] = currentDraft.copy(optD = v)
                                }
                            },
                            label = { Text("Option D") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Text(
                        text = "Select 1 Real Correct Option for Question ${activeQuestionTab + 1}:",
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
                                        it[activeQuestionTab] = currentDraft.copy(correctIndex = idx)
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
                    text = "Publish Exam to Portal",
                    enabled = title.isNotBlank(),
                    onClick = {
                        val jsonArr = JSONArray()
                        customQuestions.forEachIndexed { idx, q ->
                            if (q.question.isNotBlank()) {
                                val obj = JSONObject()
                                obj.put("id", "CUSTOM_Q_${idx + 1}")
                                obj.put("category", course)
                                obj.put("questionText", q.question.trim())
                                val opts = JSONArray()
                                opts.put(q.optA.ifBlank { "Option A" })
                                opts.put(q.optB.ifBlank { "Option B" })
                                opts.put(q.optC.ifBlank { "Option C" })
                                opts.put(q.optD.ifBlank { "Option D" })
                                obj.put("options", opts)
                                obj.put("correctOptionIndex", q.correctIndex)
                                jsonArr.put(obj)
                            }
                        }
                        viewModel.createExam(
                            title = title,
                            courseName = course,
                            batchName = batch,
                            examType = "MCQ_ONLINE",
                            durationMinutes = duration.toIntOrNull() ?: 45,
                            totalMarks = totalMarks.toIntOrNull() ?: 100,
                            passingMarks = passMarks.toIntOrNull() ?: 40,
                            scheduledDateIso = "${examDateIso.trim()} ${examTime24h.trim()}",
                            questionsJson = jsonArr.toString()
                        )
                        showCreateExam = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showCreateExam = false }) { Text("Cancel") } }
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Record Exam Marks", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showAddResult = false }) { Text("Cancel") } }
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Upload Study Material / Attach File", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showAddMaterial = false }) { Text("Cancel") } }
        )
    }

    if (showIssueCertificate) {
        var certType by remember { mutableStateOf("DIPLOMA") }
        var studentName by remember { mutableStateOf(state.students.firstOrNull()?.fullName ?: "") }
        var courseName by remember { mutableStateOf(state.students.firstOrNull()?.courseName ?: "ADCA") }
        var grade by remember { mutableStateOf("A+") }

        AlertDialog(
            onDismissRequest = { showIssueCertificate = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Issue Verifiable QR Certificate", color = WciColors.TextPrimaryDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("DIPLOMA", "COMPLETION", "MERIT", "BONAFIDE").forEach { ct ->
                            LiquidPill(text = ct, selected = certType == ct, onClick = { certType = ct })
                        }
                    }
                    OutlinedTextField(value = studentName, onValueChange = { studentName = it }, label = { Text("Student Name *") }, singleLine = true)
                    OutlinedTextField(value = courseName, onValueChange = { courseName = it }, label = { Text("Course Completed *") }, singleLine = true)
                    OutlinedTextField(value = grade, onValueChange = { grade = it }, label = { Text("Grade Awarded (e.g. A+, Distinction)") }, singleLine = true)
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Issue Certificate",
                    enabled = studentName.isNotBlank() && courseName.isNotBlank(),
                    onClick = {
                        viewModel.issueCertificate(certType, "stu_cert", studentName, courseName, grade)
                        showIssueCertificate = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showIssueCertificate = false }) { Text("Cancel") } }
        )
    }

    activeExamAttempt?.let { exam ->
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val isAdminPreview = state.activePortalRole == PortalRole.ADMIN
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

        val shuffledQuestions = remember(exam.id, exam.questionsJson, studentId, proctoredState.questions) {
            val customParsed = parseCustomExamQuestions(exam.questionsJson, exam.courseName)
            when {
                customParsed.isNotEmpty() -> customParsed
                proctoredState.questions.isNotEmpty() -> proctoredState.questions
                else -> QuestionBank.selectAndShuffleQuestionsForStudent(
                    studentId = studentId,
                    examId = exam.id,
                    courseFilter = exam.courseName,
                    targetCount = 50
                )
            }
        }
        val sanitizationReport = remember(exam.id) {
            PreExamSecurityGate.sanitizeAndAuditExamEnvironment(context)
        }
        var currentIdx by remember { mutableIntStateOf(0) }
        var selectedAnswers by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
        var faceVerified by remember { mutableStateOf(false) }
        var faceStatusText by remember {
            mutableStateOf("Facial Biometric Gate: Verify Registered Student Identity")
        }
        var remainingSeconds by remember(exam.id) {
            mutableIntStateOf((exam.durationMinutes.coerceAtLeast(5)) * 60)
        }

        LaunchedEffect(exam.id) {
            val activity = com.example.media.SecureWindowController.tailrecFindActivity(context)
            proctoredExamViewModel.beginProctoredExamSession(
                activity = activity,
                exam = exam,
                studentId = studentId,
                studentName = studentName,
                questionCount = 50
            )
            if (proctoredState.selectedAnswers.isNotEmpty()) {
                selectedAnswers = proctoredState.selectedAnswers
            }
        }

        LaunchedEffect(faceGateStatus) {
            when (val st = faceGateStatus) {
                is com.example.biometric.FaceVerificationGateStatus.Idle -> {
                    faceStatusText = "Facial Biometric Gate: Verify Registered Student Identity"
                }
                is com.example.biometric.FaceVerificationGateStatus.ScanningFace -> {
                    faceStatusText = "Scanning Front-Camera Face Biometrics & Anti-Spoofing Liveness..."
                }
                is com.example.biometric.FaceVerificationGateStatus.VerifiedMatch -> {
                    faceVerified = true
                    faceStatusText = "✓ Biometric Face Verified (${st.similarityPercent}% Cosine Match) • FLAG_SECURE Active"
                }
                is com.example.biometric.FaceVerificationGateStatus.BlockedMismatch -> {
                    faceVerified = false
                    faceStatusText = st.errorMessage
                }
            }
        }

        // Enforce ExamViewModel 2-Strike Background Auto-Submit & Multi-Window Block
        DisposableEffect(lifecycleOwner, exam.id, faceVerified) {
            val activity = com.example.media.SecureWindowController.tailrecFindActivity(context)
            AntiOverlayShield.applyWindowOverlayShield(activity, true)
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (faceVerified && event == androidx.lifecycle.Lifecycle.Event.ON_STOP && !isAdminPreview) {
                    proctoredExamViewModel.onAppBackgroundedOrSwitched(ownerUid)
                } else if (faceVerified && event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !isAdminPreview) {
                    val inMultiWindow = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        activity?.isInMultiWindowMode == true
                    } else false
                    proctoredExamViewModel.onWindowFocusOrMultiWindowChanged(
                        hasFocus = true,
                        isMultiWindow = inMultiWindow,
                        ownerUid = ownerUid
                    )
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                proctoredExamViewModel.releaseExamSecurityShield(activity)
                AntiOverlayShield.applyWindowOverlayShield(activity, false)
            }
        }

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
                }
            )
        }

        LaunchedEffect(proctoredState.isSubmitted, proctoredState.submittedResult) {
            if (proctoredState.isSubmitted && !isAdminPreview) {
                val res = proctoredState.submittedResult
                if (res != null) {
                    resultCountdownSeconds = 300
                    submittedResultSummary = "${exam.title}: Auto-Submitted (${res.marksObtained}/${res.totalMarks} • ${res.grade})"
                }
                activeExamAttempt = null
            }
        }

        // Hardware-enforce FLAG_SECURE & anti-screenshot during active CBT exams
        BindScreenCapturePolicy(ScreenCapturePolicy.LOCKED_SENSITIVE)

        // Auto-submit countdown timer
        LaunchedEffect(exam.id, faceVerified) {
            if (!faceVerified) return@LaunchedEffect
            while (remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
            }
            // Auto-submit when timer hits 00:00
            var rawCorrect = 0
            shuffledQuestions.forEach { q ->
                if (selectedAnswers[q.id] == q.correctOptionIndex) rawCorrect++
            }
            val score = ((rawCorrect.toDouble() / shuffledQuestions.size.coerceAtLeast(1)) * exam.totalMarks).toInt()
            val computedRank = state.examResults.filter { it.examId == exam.id && it.studentId != studentId }.count { it.marksObtained > score } + 1
            if (isAdminPreview) {
                viewModel.postMessage("Admin Preview Mode: Score $score/${exam.totalMarks} — Not saved to Student Results.")
            } else {
                viewModel.recordExamResult(
                    examId = exam.id,
                    examTitle = exam.title,
                    studentId = studentId,
                    studentName = studentName,
                    marksObtained = score,
                    totalMarks = exam.totalMarks,
                    rank = computedRank,
                    remarks = "Auto-Submitted on Timer Expiry ($rawCorrect/${shuffledQuestions.size} correct)",
                    isOnlineCbtAttempt = true
                )
            }
            activeExamAttempt = null
        }

        val currentQuestion = shuffledQuestions.getOrNull(currentIdx)
        val minsLeft = remainingSeconds / 60
        val secsLeft = remainingSeconds % 60

        AlertDialog(
            onDismissRequest = { activeExamAttempt = null },
            containerColor = WciColors.BgElevatedDark,
            title = {
                Column {
                    Text(
                        text = if (isAdminPreview) "Admin Preview CBT • ${exam.title}" else "Proctored CBT • ${exam.title}",
                        color = WciColors.TextPrimaryDark,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                    )
                    Text(
                        text = "Time Left: %02d:%02d • Strikes: ${proctoredState.backgroundStrikes}/${proctoredState.maxAllowedStrikes} • Pool: 50/${QuestionBank.fullQuestionPool.size} Qs".format(minsLeft, secsLeft),
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
                            .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (sanitizationReport.isClearedToStartExam && !proctoredState.isMultiWindowBlocked) {
                                    "✓ Pre-Exam Sanitizer: Clipboard Scrubbed • No Split-Screen/Root/VPN"
                                } else {
                                    "⚠ Security Alert: ${proctoredState.securityAlertBanner ?: sanitizationReport.blockingReasons.firstOrNull().orEmpty()}"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (sanitizationReport.isClearedToStartExam && !proctoredState.isMultiWindowBlocked) WciColors.Success else WciColors.Warning
                                )
                            )
                            Text(
                                text = faceStatusText,
                                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF38BDF8))
                            )
                        }
                    }

                    if (!faceVerified) {
                        ShimmerButton(
                            text = "Verify Student Face Biometrics (Front Camera)",
                            icon = Icons.Default.FaceRetouchingNatural,
                            onClick = {
                                runCatching {
                                    selfieCameraLauncher.launch(null)
                                }.onFailure {
                                    val photoUri = studentRecord?.photoUri.orEmpty()
                                    faceAuthViewModel.verifyStudentFaceForExam(
                                        context = context,
                                        studentId = studentId,
                                        admissionPhotoDataUri = photoUri,
                                        liveSelfieBitmap = null,
                                        onVerifiedSuccess = {
                                            faceVerified = true
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (proctoredState.isMultiWindowBlocked) {
                        Text(
                            text = proctoredState.securityAlertBanner
                                ?: "Split-Screen / Multi-Window mode is blocked during proctored CBT exams.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.Danger)
                        )
                    } else if (currentQuestion != null) {
                        Text(
                            text = "Question ${currentIdx + 1} of ${shuffledQuestions.size} (${currentQuestion.category}) • Answered: ${selectedAnswers.size}/${shuffledQuestions.size}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMono,
                                color = WciColors.Info
                            )
                        )
                        Text(
                            text = currentQuestion.questionText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = WciColors.TextPrimaryDark
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
                                    if (currentIdx < shuffledQuestions.lastIndex) {
                                        currentIdx++
                                        proctoredExamViewModel.navigateToQuestion(currentIdx)
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = if (isAdminPreview) {
                        "Finish Preview (${selectedAnswers.size}/${shuffledQuestions.size})"
                    } else {
                        "Submit CBT (${selectedAnswers.size}/${shuffledQuestions.size})"
                    },
                    enabled = faceVerified && !proctoredState.isMultiWindowBlocked,
                    onClick = {
                        var rawCorrect = 0
                        shuffledQuestions.forEach { q ->
                            if (selectedAnswers[q.id] == q.correctOptionIndex) rawCorrect++
                        }
                        val score = if (selectedAnswers.isEmpty()) {
                            0
                        } else {
                            ((rawCorrect.toDouble() / shuffledQuestions.size.coerceAtLeast(1)) * exam.totalMarks).toInt()
                        }
                        if (isAdminPreview) {
                            viewModel.postMessage(
                                "Admin Preview Mode: Scored $score / ${exam.totalMarks} ($rawCorrect/${shuffledQuestions.size} Correct) — Not saved to Student Results."
                            )
                        } else {
                            val computedRank = state.examResults.filter { it.examId == exam.id && it.studentId != studentId }.count { it.marksObtained > score } + 1
                            viewModel.recordExamResult(
                                examId = exam.id,
                                examTitle = exam.title,
                                studentId = studentId,
                                studentName = studentName,
                                marksObtained = score,
                                totalMarks = exam.totalMarks,
                                rank = computedRank,
                                remarks = "Proctored CBT: $rawCorrect/${shuffledQuestions.size} Correct (Schedule: ${exam.scheduledDateIso})",
                                isOnlineCbtAttempt = true
                            )
                            resultCountdownSeconds = 300
                            submittedResultSummary = "${exam.title}: Scored $score / ${exam.totalMarks} ($rawCorrect/${shuffledQuestions.size} Correct)"
                        }
                        activeExamAttempt = null
                    }
                )
            },
            dismissButton = { TextButton(onClick = { activeExamAttempt = null }) { Text("Exit") } }
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
            containerColor = WciColors.BgElevatedDark,
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
                            color = WciColors.TextPrimaryDark,
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
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
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
                    Text("Close")
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
                        "IMPORTANT" -> Color(0xFF00E5FF)
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
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                                Text(
                                    text = "AUDIENCE: ${ann.targetAudience}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.TextMutedDark,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                            Text(
                                text = ann.channel,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = Color(0xFFA855F7)
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = ann.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = ann.body,
                            style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.TextSecondaryDark)
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
                                    color = Color(0xFF00E5FF)
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
                            "Launch interactive virtual classes or computer lab practical sessions with live polls and attendance tracking."
                        } else {
                            "Upcoming live classes, computer lab practical sessions, and interactive polls scheduled for your batch appear here."
                        },
                        ctaLabel = if (canBroadcastOrHost) "+ Start Live Class" else null,
                        onCtaClick = if (canBroadcastOrHost) ({ showScheduleLive = true }) else null
                    )
                }
            } else {
                item {
                    ExoPlayerHdLecturePlayer(
                        streamUrl = ExoPlayerLectureManager.DEFAULT_SAMPLE_LECTURE_STREAM,
                        title = state.liveClasses.firstOrNull()?.title ?: "Waqar Institute HD Masterclass"
                    )
                }
                items(state.liveClasses, key = { it.id }) { lc ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = WciColors.Success
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${lc.title} (${lc.meetingCode})", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                                Text("Batch: ${lc.batchName} • Host: ${lc.teacherName}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                                Text("Schedule: ${lc.scheduledTimeIso} (${lc.durationMinutes}m)", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                                if (lc.activePollQuestion.isNotBlank()) {
                                    Text("Live Poll: ${lc.activePollQuestion}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Warning))
                                }
                            }
                            Text(lc.status, style = MaterialTheme.typography.labelLarge.copy(color = WciColors.Success))
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Broadcast Notice", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showBroadcastDialog = false }) { Text("Cancel") } }
        )
    }

    if (showScheduleLive) {
        var title by remember { mutableStateOf("") }
        var batch by remember { mutableStateOf(state.batches.firstOrNull()?.name ?: "ADCA Morning Batch") }
        var teacher by remember { mutableStateOf(state.userProfile?.displayName ?: "Waqar Sir") }
        var poll by remember { mutableStateOf("Have you completed today's Tally GST voucher exercise?") }

        AlertDialog(
            onDismissRequest = { showScheduleLive = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Start Live Class & Lab Poll", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showScheduleLive = false }) { Text("Cancel") } }
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
                            style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark, fontWeight = FontWeight.ExtraBold)
                        )
                        Text(
                            "Instant Sub-ms Knowledge Base • ChatGPT & AI Academic Tutor • Vision Doubt Solver",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                        )
                    }
                    if (state.activePortalRole == PortalRole.ADMIN) {
                        Column(horizontalAlignment = Alignment.End) {
                            CountUpInt(targetValue = totalTokensUsed, suffix = " tok", color = WciColors.Info, fontSizeSp = 18)
                            Text("${state.aiLogs.size} AI sessions", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
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
                        style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.TextPrimaryDark)
                    )
                }
            }
        }

        // Persisted AI History
        item {
            Text(
                text = "Recent Waqar AI Answers",
                style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark)
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
                    Text("Q: ${log.promptText}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = WciColors.TextPrimaryDark))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(log.responseText, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                }
            }
        }
    }
}
