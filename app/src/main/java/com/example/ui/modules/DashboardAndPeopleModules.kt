package com.example.ui.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.ui.designsystem.*
import com.example.util.DocumentExportHelper
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import java.io.ByteArrayOutputStream

// Helper to encode a picked student photo into a compact, cross-device Base64 JPEG data URI
private fun encodePhotoUriToCompactDataUri(context: Context, uri: Uri): String {
    return runCatching {
        val original = if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        val maxDim = 220
        val ratio = minOf(maxDim.toFloat() / original.width.coerceAtLeast(1), maxDim.toFloat() / original.height.coerceAtLeast(1), 1f)
        val targetW = (original.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (original.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(original, targetW, targetH, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    }.getOrDefault(uri.toString())
}

// Helper to extract display name and file size from an attached document URI
private fun resolveAttachedDocumentMeta(context: Context, uri: Uri): String {
    return runCatching {
        var name = uri.lastPathSegment ?: "Student_Document.pdf"
        var sizeBytes = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
            }
        }
        val sizeKb = (sizeBytes / 1024L).coerceAtLeast(1L)
        if (sizeBytes > 0L) "$name (${sizeKb} KB)" else name
    }.getOrDefault("Attached_Document.pdf")
}

@Composable
fun StudentAvatarThumbnail(
    photoUri: String,
    fullName: String,
    palette: RolePalette,
    size: Dp = 48.dp
) {
    val decodedBitmap = remember(photoUri) {
        if (photoUri.startsWith("data:image")) {
            runCatching {
                val base64Part = photoUri.substringAfter("base64,", "")
                if (base64Part.isNotEmpty()) {
                    val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
            }.getOrNull()
        } else null
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.tagBackground, CircleShape)
            .border(1.5.dp, palette.start, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when {
            decodedBitmap != null -> {
                Image(
                    bitmap = decodedBitmap.asImageBitmap(),
                    contentDescription = fullName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            photoUri.isNotBlank() -> {
                AsyncImage(
                    model = photoUri,
                    contentDescription = fullName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Text(
                    text = fullName.take(2).uppercase().ifBlank { "ST" },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = palette.start
                    )
                )
            }
        }
    }
}

// ============================================================================
// EXECUTIVE DASHBOARD & KPI COMMAND CENTER
// ============================================================================

@Composable
fun AdminDashboardModule(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    onNavigateToModule: (String) -> Unit
) {
    val palette = LocalRolePalette.current
    val activeRole = state.activePortalRole
    val isAdmin = activeRole == PortalRole.ADMIN
    val isTeacher = activeRole == PortalRole.TEACHER
    val isStudent = activeRole == PortalRole.STUDENT
    val isParent = activeRole == PortalRole.PARENT
    val context = LocalContext.current
    var showExamInstructionsModal by remember { mutableStateOf(false) }
    var selectedChildIdx by remember { mutableIntStateOf(0) }
    val selectedParentChild = remember(state.students, selectedChildIdx) {
        state.students.getOrNull(selectedChildIdx) ?: state.students.firstOrNull()
    }

    val scopedDashboardFees = remember(state.fees, isParent, selectedParentChild) {
        if (isParent && selectedParentChild != null) {
            state.fees.filter {
                it.studentId.equals(selectedParentChild.id, ignoreCase = true) ||
                    it.studentName.equals(selectedParentChild.fullName, ignoreCase = true)
            }
        } else {
            state.fees
        }
    }
    val totalCollectedPaise = remember(scopedDashboardFees) { scopedDashboardFees.sumOf { it.paidAmountPaise } }
    val totalPendingPaise = remember(scopedDashboardFees) { scopedDashboardFees.sumOf { it.balancePaise } }
    val avgAttendance = remember(state.students, isParent, selectedParentChild) {
        if (isParent && selectedParentChild != null) {
            selectedParentChild.attendancePercent
        } else if (state.students.isEmpty()) {
            0
        } else {
            state.students.map { it.attendancePercent }.average().toInt()
        }
    }
    val presentTodayCount = remember(state.attendance) {
        state.attendance.count { it.status == "PRESENT" }
    }
    val bestExamPercent = remember(state.examResults, isParent, selectedParentChild) {
        val relevantResults = if (isParent && selectedParentChild != null) {
            state.examResults.filter {
                it.studentId.equals(selectedParentChild.id, ignoreCase = true) ||
                    it.studentName.equals(selectedParentChild.fullName, ignoreCase = true)
            }
        } else {
            state.examResults
        }
        val top = relevantResults.maxByOrNull {
            if (it.totalMarks > 0) (it.marksObtained * 100) / it.totalMarks else 0
        }
        if (top != null && top.totalMarks > 0) (top.marksObtained * 100) / top.totalMarks else 0
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Parent Child Switcher Chips (Liquid Glass)
        if (isParent && state.students.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.students.forEachIndexed { index, child ->
                        LiquidPill(
                            text = child.fullName.substringBefore(" "),
                            selected = selectedChildIdx == index,
                            icon = Icons.Default.Face,
                            onClick = { selectedChildIdx = index }
                        )
                    }
                }
            }
        }

        // Student Portal: Schedule-Gated "Upcoming Exam - Scheduled by Admin" Hero Card
        if (isStudent) {
            item {
                val nowMs = System.currentTimeMillis()
                val scheduledExamInfo = remember(state.exams, nowMs) {
                    val sdfDateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    val rangeRegex = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})-(\d{1,2}:\d{2})$""")
                    val singleRegex = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})$""")
                    state.exams.mapNotNull { exam ->
                        val raw = exam.scheduledDateIso.trim()
                        val (startMs, endMs) = when {
                            rangeRegex.matches(raw) -> {
                                val m = rangeRegex.matchEntire(raw)!!
                                val d = m.groupValues[1]
                                val s = runCatching { sdfDateTime.parse("$d ${m.groupValues[2]}")?.time }.getOrNull()
                                val e = runCatching { sdfDateTime.parse("$d ${m.groupValues[3]}")?.time }.getOrNull()
                                s to (e ?: s?.plus(exam.durationMinutes.coerceAtLeast(15) * 60_000L))
                            }
                            singleRegex.matches(raw) -> {
                                val s = runCatching { sdfDateTime.parse(raw)?.time }.getOrNull()
                                s to s?.plus(exam.durationMinutes.coerceAtLeast(15) * 60_000L)
                            }
                            else -> null to null
                        }
                        if (startMs != null && endMs != null && nowMs <= endMs) {
                            Triple(exam, startMs, endMs)
                        } else null
                    }.minByOrNull { it.second }
                }
                val nextExam = scheduledExamInfo?.first
                val nextStartMs = scheduledExamInfo?.second
                val nextEndMs = scheduledExamInfo?.third
                val isExamLiveNow = nextExam != null && nextStartMs != null && nextEndMs != null &&
                    nowMs in nextStartMs..nextEndMs

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 28.dp,
                    glowColor = Color(0xFF818CF8),
                    strongSurface = true,
                    onClick = { onNavigateToModule("M10_EXAMS") }
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
                                text = when {
                                    isExamLiveNow -> "EXAM WINDOW LIVE NOW"
                                    nextExam != null -> "SCHEDULED BY ADMIN • UPCOMING"
                                    else -> "UPCOMING EXAM - SCHEDULED BY ADMIN"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isExamLiveNow) WciColors.Success else Color.White
                                )
                            )
                        }
                        Text(
                            text = if (nextExam != null) "${nextExam.durationMinutes} min • ${nextExam.totalMarks} Marks" else "Awaiting Schedule",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMono,
                                color = WciColors.Info
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = nextExam?.title ?: if (state.preferences.languageCode == "HI") {
                            "Upcoming Exam - एडमिन द्वारा निर्धारित"
                        } else {
                            "Upcoming Exam - Scheduled by Admin"
                        },
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (nextExam != null) {
                            "${nextExam.courseName} • Batch: ${nextExam.batchName} • Window: ${nextExam.scheduledDateIso}"
                        } else {
                            "No live exam window is active right now. Scheduled batch exams appear and unlock automatically at the Admin-assigned date and time."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = WciColors.TextSecondaryDark
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val countdownTriple by produceState(
                            initialValue = Triple("--", "--", "--"),
                            key1 = nextStartMs
                        ) {
                            if (nextStartMs == null) {
                                value = Triple("--", "--", "--")
                            } else {
                                while (true) {
                                    val diffSec = ((nextStartMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                                    val d = (diffSec / 86400L).toString().padStart(2, '0')
                                    val h = ((diffSec % 86400L) / 3600L).toString().padStart(2, '0')
                                    val m = ((diffSec % 3600L) / 60L).toString().padStart(2, '0')
                                    value = Triple(d, h, m)
                                    kotlinx.coroutines.delay(30_000L)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                countdownTriple.first to "days",
                                countdownTriple.second to "hrs",
                                countdownTriple.third to "min"
                            ).forEach { (num, unit) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xFF1A255C).copy(alpha = 0.90f))
                                        .border(1.2.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = num,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF00E5FF)
                                            )
                                        )
                                        Text(
                                            text = unit,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 10.sp,
                                                color = WciColors.TextSecondaryDark
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiquidPill(
                                text = "Instructions",
                                selected = false,
                                icon = Icons.Default.Info,
                                onClick = { showExamInstructionsModal = true }
                            )
                            LiquidPill(
                                text = if (isExamLiveNow) "Start CBT" else "Exam Hall",
                                selected = isExamLiveNow,
                                icon = Icons.Default.Quiz,
                                onClick = { onNavigateToModule("M10_EXAMS") }
                            )
                        }
                    }
                }
            }
        }

        // Hero Welcome & Attendance Summary Banner
        item {
            val childDisplayName = (selectedParentChild ?: state.students.firstOrNull())?.fullName?.substringBefore(" ") ?: "Student"
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .neonBorder(palette = palette, cornerRadius = WciRadius.xl),
                cornerRadius = WciRadius.xl,
                glowColor = palette.start,
                strongSurface = true
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .background(palette.brush, RoundedCornerShape(WciRadius.pill))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = when (activeRole) {
                                    PortalRole.ADMIN -> "INSTITUTE OVERVIEW"
                                    PortalRole.TEACHER -> "TODAY AT A GLANCE"
                                    PortalRole.STUDENT -> "THIS MONTH AT A GLANCE"
                                    PortalRole.PARENT -> "${childDisplayName.uppercase()} AT A GLANCE"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF05060F),
                                    fontSize = 10.sp
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isParent) {
                                "$childDisplayName's Academic Summary"
                            } else {
                                "Welcome, ${state.userProfile?.displayName ?: activeRole.label}"
                            },
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = "${state.preferences.instituteName} • ${state.preferences.academicSession}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = WciColors.TextSecondaryDark
                            )
                        )
                    }
                    DonutProgressRing(
                        progress = avgAttendance / 100f,
                        color = WciColors.Success,
                        label = "$avgAttendance%",
                        subLabel = "Attendance"
                    )
                }
            }
        }

        // Parent 14-Day Attendance Strip + Instant UPI Fee Payment (Liquid Glass)
        if (isParent) {
            item {
                val childAttendanceLogs = remember(state.attendance, selectedParentChild) {
                    val filtered = if (selectedParentChild != null) {
                        state.attendance.filter {
                            it.targetId.equals(selectedParentChild.id, ignoreCase = true) ||
                                it.targetName.equals(selectedParentChild.fullName, ignoreCase = true)
                        }
                    } else {
                        state.attendance
                    }
                    filtered.take(14)
                }
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowColor = Color(0xFFFF6B8B),
                    onClick = { onNavigateToModule("M07_ATTENDANCE") }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Attendance • Last 14 Days",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = "Present • Absent • Late",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (idx in 0 until 14) {
                            val status = childAttendanceLogs.getOrNull(idx)?.status ?: "UNMARKED"
                            val dotColor = when (status) {
                                "PRESENT" -> WciColors.Success
                                "LATE" -> WciColors.Warning
                                "ABSENT" -> WciColors.Danger
                                else -> Color(0xFF334155)
                            }
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(dotColor, CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            )
                        }
                    }
                    if (totalPendingPaise > 0L) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ShimmerButton(
                            text = "Pay Fee Due (${formatInrPaise(totalPendingPaise)}) via UPI",
                            icon = Icons.Default.Payment,
                            onClick = {
                                runCatching {
                                    val upiUri = Uri.parse(
                                        "upi://pay?pa=waqarinstitute@upi&pn=Waqar%20Computer%20Institute&am=${totalPendingPaise / 100L}&cu=INR"
                                    )
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, upiUri))
                                }.onFailure {
                                    onNavigateToModule("M08_FEES")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 4 Role-Scoped Multi-Color KPI Cards
        item {
            val childShortName = (selectedParentChild ?: state.students.firstOrNull())?.fullName?.substringBefore(" ")?.uppercase() ?: "CHILD"
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isAdmin) {
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFF00E5FF),
                            onClick = { onNavigateToModule("M03_STUDENTS") }
                        ) {
                            Text("ENROLLED STUDENTS", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            Spacer(modifier = Modifier.height(6.dp))
                            CountUpInt(targetValue = state.students.size, color = Color(0xFF00E5FF), fontSizeSp = 26)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${state.batches.size} Batches • ${state.courses.size} Courses",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }

                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFF14F195),
                            onClick = { onNavigateToModule("M08_FEES") }
                        ) {
                            Text("FEE COLLECTED (MTD)", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            Spacer(modifier = Modifier.height(6.dp))
                            MoneyTicker(amountPaise = totalCollectedPaise, color = WciColors.Success, fontSizeSp = 22)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Dues: ${formatInrPaise(totalPendingPaise)}",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFFF6B8B))
                            )
                        }
                    } else if (isTeacher) {
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFF00E5FF),
                            onClick = { onNavigateToModule("M06_BATCHES") }
                        ) {
                            Text("ASSIGNED BATCHES", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            Spacer(modifier = Modifier.height(6.dp))
                            CountUpInt(targetValue = state.batches.size, color = Color(0xFF00E5FF), fontSizeSp = 26)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${state.courses.size} Active Courses",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }

                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFF14F195),
                            onClick = { onNavigateToModule("M07_ATTENDANCE") }
                        ) {
                            Text("PRESENT TODAY", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            Spacer(modifier = Modifier.height(6.dp))
                            CountUpInt(targetValue = presentTodayCount, color = WciColors.Success, fontSizeSp = 26)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "QR & Roster Roll-Call",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }
                    } else {
                        // Student & Parent KPI Row 1
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFFA855F7),
                            onClick = { onNavigateToModule("M11_RESULTS") }
                        ) {
                            Text(
                                text = if (isParent) "$childShortName EXAM SCORE" else "LATEST EXAM SCORE",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            CountUpInt(targetValue = bestExamPercent, suffix = "%", color = Color(0xFFE879F9), fontSizeSp = 26)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${state.examResults.size} Published Marksheets",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }

                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFFFF5894),
                            onClick = { onNavigateToModule("M08_FEES") }
                        ) {
                            Text(
                                text = if (isParent) "$childShortName FEE DUE" else "FEE DUE",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            MoneyTicker(amountPaise = totalPendingPaise, color = Color(0xFFFF6B8B), fontSizeSp = 22)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Paid: ${formatInrPaise(totalCollectedPaise)}",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Success)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isAdmin) {
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFFA855F7),
                            onClick = { onNavigateToModule("M07_ATTENDANCE") }
                        ) {
                            Text("TODAY'S ATTENDANCE", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            Spacer(modifier = Modifier.height(6.dp))
                            CountUpInt(targetValue = avgAttendance, suffix = "%", color = Color(0xFFE879F9), fontSizeSp = 26)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$presentTodayCount Present Today",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }

                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFF38BDF8),
                            onClick = { onNavigateToModule("M04_ADMISSIONS") }
                        ) {
                            Text("NEW ENQUIRIES", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            Spacer(modifier = Modifier.height(6.dp))
                            CountUpInt(targetValue = state.enquiries.size, color = Color(0xFF38BDF8), fontSizeSp = 26)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${state.enquiries.count { it.stage == "CONVERTED" }} Converted",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Success)
                            )
                        }
                    } else {
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFF10F596),
                            onClick = { onNavigateToModule("M07_ATTENDANCE") }
                        ) {
                            Text("PRESENT DAYS", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            Spacer(modifier = Modifier.height(6.dp))
                            CountUpInt(targetValue = presentTodayCount, color = WciColors.Success, fontSizeSp = 26)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isTeacher) "Verified Attendance & QR Logs" else "Verified Attendance Logs",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }

                        GlassCard(
                            modifier = Modifier.weight(1f),
                            glowColor = Color(0xFF38BDF8),
                            onClick = { onNavigateToModule("M16_ANNOUNCEMENTS") }
                        ) {
                            Text("INSTITUTE ALERTS", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            Spacer(modifier = Modifier.height(6.dp))
                            CountUpInt(targetValue = state.announcements.size, color = Color(0xFF38BDF8), fontSizeSp = 26)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${state.liveClasses.size} Live Classes",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }
                    }
                }
            }
        }

        // Role-Scoped Tactile Quick Actions Grid
        item {
            Text(
                text = when (activeRole) {
                    PortalRole.ADMIN -> "Director Quick Operations"
                    PortalRole.TEACHER -> "Faculty Classroom Actions"
                    PortalRole.STUDENT -> "My Student Workspace"
                    PortalRole.PARENT -> "Guardian Quick Access"
                },
                style = MaterialTheme.typography.titleMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (isAdmin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ClayActionTile(
                        title = "+ Add Student",
                        subtitle = "Admission & Photo",
                        icon = Icons.Default.PersonAdd,
                        accentColor = Color(0xFF00E5FF),
                        onClick = { onNavigateToModule("M03_STUDENTS") },
                        modifier = Modifier.weight(1f),
                        testTag = "quick_add_student_tile"
                    )
                    ClayActionTile(
                        title = "Collect Fee",
                        subtitle = "GST Receipt",
                        icon = Icons.Default.CurrencyRupee,
                        accentColor = Color(0xFF14F195),
                        onClick = { onNavigateToModule("M08_FEES") },
                        modifier = Modifier.weight(1f),
                        testTag = "quick_collect_fee_tile"
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ClayActionTile(
                        title = "Roll Call & QR",
                        subtitle = "Batch Attendance",
                        icon = Icons.Default.QrCodeScanner,
                        accentColor = Color(0xFFA855F7),
                        onClick = { onNavigateToModule("M07_ATTENDANCE") },
                        modifier = Modifier.weight(1f),
                        testTag = "quick_attendance_tile"
                    )
                    ClayActionTile(
                        title = "Exams & CBT",
                        subtitle = "24h Schedule & Qs",
                        icon = Icons.Default.Quiz,
                        accentColor = Color(0xFFFF5894),
                        onClick = { onNavigateToModule("M10_EXAMS") },
                        modifier = Modifier.weight(1f),
                        testTag = "quick_exams_tile"
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ClayActionTile(
                        title = "Add Teacher",
                        subtitle = "Photo, Face ID & Login",
                        icon = Icons.Default.Badge,
                        accentColor = Color(0xFF38BDF8),
                        onClick = { onNavigateToModule("M13_STAFF") },
                        modifier = Modifier.weight(1f),
                        testTag = "quick_add_teacher_tile"
                    )
                    ClayActionTile(
                        title = "Manage Parents",
                        subtitle = "Add Parent User & PTM",
                        icon = Icons.Default.FamilyRestroom,
                        accentColor = Color(0xFFF59E0B),
                        onClick = { onNavigateToModule("M15_PARENTS") },
                        modifier = Modifier.weight(1f),
                        testTag = "quick_add_parent_tile"
                    )
                }
            } else if (isTeacher) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ClayActionTile(
                        title = "Mark Attendance",
                        subtitle = "Roster & QR Scan",
                        icon = Icons.AutoMirrored.Filled.FactCheck,
                        accentColor = Color(0xFF10F596),
                        onClick = { onNavigateToModule("M07_ATTENDANCE") },
                        modifier = Modifier.weight(1f)
                    )
                    ClayActionTile(
                        title = "Exams & Marks",
                        subtitle = "CBT & Marksheets",
                        icon = Icons.Default.Quiz,
                        accentColor = Color(0xFFA855F7),
                        onClick = { onNavigateToModule("M11_RESULTS") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ClayActionTile(
                        title = "Study Material",
                        subtitle = "Notes & Labs",
                        icon = Icons.AutoMirrored.Filled.LibraryBooks,
                        accentColor = Color(0xFF00E5FF),
                        onClick = { onNavigateToModule("M12_MATERIALS") },
                        modifier = Modifier.weight(1f)
                    )
                    ClayActionTile(
                        title = "Gemini AI Hub",
                        subtitle = "Quiz & Lesson Plan",
                        icon = Icons.Default.AutoAwesome,
                        accentColor = Color(0xFFFF5894),
                        onClick = { onNavigateToModule("M18_AI_HUB") },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // Student & Parent 3D Claymorphism Quick Tiles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ClayActionTile(
                        title = "Mark Sheets",
                        subtitle = "Grades & Rank Card",
                        icon = Icons.Default.EmojiEvents,
                        accentColor = Color(0xFFA855F7),
                        onClick = { onNavigateToModule("M11_RESULTS") },
                        modifier = Modifier.weight(1f)
                    )
                    ClayActionTile(
                        title = "Fee Receipts",
                        subtitle = "Download GST PDF",
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        accentColor = Color(0xFFFF5894),
                        onClick = { onNavigateToModule("M08_FEES") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ClayActionTile(
                        title = if (isParent) "QR Certificates" else "Online CBT Exam",
                        subtitle = if (isParent) "Verified Diploma" else "Practice & Tests",
                        icon = if (isParent) Icons.Default.WorkspacePremium else Icons.Default.Quiz,
                        accentColor = Color(0xFF00E5FF),
                        onClick = { onNavigateToModule(if (isParent) "M13_CERTIFICATES" else "M10_EXAMS") },
                        modifier = Modifier.weight(1f)
                    )
                    ClayActionTile(
                        title = if (isParent) "Notices & PTM" else "AI Doubt Tutor",
                        subtitle = if (isParent) "Institute Alerts" else "24x7 Gemini Help",
                        icon = if (isParent) Icons.Default.Campaign else Icons.Default.AutoAwesome,
                        accentColor = Color(0xFF10F596),
                        onClick = { onNavigateToModule(if (isParent) "M16_ANNOUNCEMENTS" else "M18_AI_HUB") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Live Revenue / Progress Trend Sparkline Card
        if (isAdmin) {
            item {
                val sparkPoints = remember(state.fees) {
                    if (state.fees.size >= 2) {
                        state.fees.take(8).reversed().map { (it.paidAmountPaise / 100f).coerceAtLeast(1f) }
                    } else {
                        listOf(0f, 0f)
                    }
                }
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Fee Collection Trajectory",
                                style = MaterialTheme.typography.titleSmall.copy(color = Color.White)
                            )
                            Text(
                                text = "${state.fees.size} verified receipts issued",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }
                        LiquidPill(
                            text = "Reports →",
                            selected = false,
                            onClick = { onNavigateToModule("M19_REPORTS") }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SparklineCanvas(
                        points = sparkPoints,
                        color = WciColors.Success,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                    )
                }
            }
        }

        // Recent Activity / Notices Feed
        item {
            Text(
                text = if (isAdmin) "Recent Institute Activity" else "Recent Notices & Updates",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
            )
        }

        if (isAdmin) {
            if (state.auditLogs.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.VerifiedUser,
                        title = "No institute activity recorded yet",
                        subtitle = "Add your first course, batch, or student to populate live telemetry across Waqar Computer Institute.",
                        ctaLabel = "Enroll First Student",
                        onCtaClick = { onNavigateToModule("M03_STUDENTS") }
                    )
                }
            } else {
                items(state.auditLogs.take(6), key = { it.id }) { log ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = log.action,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = JetBrainsMono,
                                        color = palette.start
                                    )
                                )
                                Text(
                                    text = log.details,
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
                                )
                            }
                            Text(
                                text = log.actorEmail.substringBefore("@"),
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                        }
                    }
                }
            }
        } else {
            if (state.announcements.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.Campaign,
                        title = "No recent institute notices",
                        subtitle = "Official batch schedules, exam alerts, and holiday updates from Waqar Computer Institute appear here."
                    )
                }
            } else {
                items(state.announcements.take(5), key = { it.id }) { ann ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                        Text(
                            text = ann.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = ann.body,
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                        )
                    }
                }
            }
        }
    }

    if (showExamInstructionsModal) {
        val nextExam = state.exams.firstOrNull()
        AlertDialog(
            onDismissRequest = { showExamInstructionsModal = false },
            containerColor = WciColors.BgElevatedDark,
            title = {
                Text(
                    text = "Official Online CBT Exam Instructions",
                    color = WciColors.TextPrimaryDark,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = Color(0xFF00E5FF)
                    ) {
                        Text(
                            text = nextExam?.title ?: "Upcoming Batch CBT Assessment",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = WciColors.TextPrimaryDark,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Scheduled Date & 24h Time: ${nextExam?.scheduledDateIso?.ifBlank { "As scheduled by Admin" } ?: "Awaiting Admin Schedule"}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMono,
                                color = WciColors.Success,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "Duration: ${nextExam?.durationMinutes ?: 45} Minutes • Max Marks: ${nextExam?.totalMarks ?: 100} • Passing: ${nextExam?.passingMarks ?: 40}",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                        )
                    }

                    Text(
                        text = "Mandatory Examination Rules:",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = WciColors.Warning,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Text(
                        text = "1. Facial Biometric Verification is required before the question paper unlocks.\n" +
                            "2. Each question has 4 Options (A, B, C, D) with 1 verified correct option.\n" +
                            "3. Screenshot, screen recording, split-screen, and overlay apps are strictly blocked by FLAG_SECURE & RASP.\n" +
                            "4. The exam auto-submits when the 24-hour scheduled countdown timer reaches 00:00.\n" +
                            "5. Official graded marksheets with Batch Rank & PDF download publish within 5 minutes of submission.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.TextPrimaryDark)
                    )
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Open Exam Hall",
                    icon = Icons.Default.Quiz,
                    onClick = {
                        showExamInstructionsModal = false
                        onNavigateToModule("M10_EXAMS")
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showExamInstructionsModal = false }) {
                    Text("Close")
                }
            }
        )
    }
}

// ============================================================================
// STUDENT MANAGEMENT, PHOTO/FILE ATTACHMENT & 360° PROFILE
// ============================================================================

@Composable
fun StudentsModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel
) {
    val context = LocalContext.current
    val palette = LocalRolePalette.current
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedStudent by remember { mutableStateOf<StudentRecord?>(null) }

    val canManageStudents = state.activePortalRole == PortalRole.ADMIN

    val filteredStudents = remember(state.students, searchQuery, statusFilter) {
        state.students.filter { s ->
            val matchesStatus = statusFilter == "ALL" || s.status == statusFilter
            val matchesQuery = searchQuery.isBlank() ||
                s.fullName.contains(searchQuery, ignoreCase = true) ||
                s.enrollmentNo.contains(searchQuery, ignoreCase = true) ||
                s.phone.contains(searchQuery, ignoreCase = true) ||
                s.courseName.contains(searchQuery, ignoreCase = true)
            matchesStatus && matchesQuery
        }
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (canManageStudents) "Student Directory" else "My Student Profile",
                        style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark)
                    )
                    Text(
                        text = if (canManageStudents) {
                            "${state.students.size} Enrolled • Photo, Document Attachment & Login Credentials"
                        } else {
                            "Personal Enrollment Record & QR Digital ID"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                    )
                }
                if (canManageStudents) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidPill(
                            text = "Export CSV",
                            selected = false,
                            icon = Icons.Default.Download,
                            onClick = {
                                DocumentExportHelper.exportAndShareStudentsCsv(context, state.students)
                            }
                        )
                        ShimmerButton(
                            text = "+ Admission",
                            icon = Icons.Default.PersonAdd,
                            onClick = { showAddDialog = true },
                            testTag = "enroll_student_button"
                        )
                    }
                }
            }
        }

        if (canManageStudents) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        viewModel.updateStudentPagingSearch(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("student_search_input"),
                    placeholder = { Text("Search by name, enrollment no, phone, or course...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.start) },
                    singleLine = true,
                    shape = RoundedCornerShape(WciRadius.md)
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("ALL", "ACTIVE", "ON_LEAVE", "ALUMNI", "DROPPED").forEach { st ->
                        LiquidPill(
                            text = st,
                            selected = statusFilter == st,
                            onClick = { statusFilter = st }
                        )
                    }
                }
            }
        }

        if (filteredStudents.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.School,
                    title = if (canManageStudents) "No students enrolled yet" else "No linked student record found",
                    subtitle = if (canManageStudents) {
                        "Admit students with their photo, attached document/marksheet, and Student & Parent portal login credentials."
                    } else {
                        "Ask your Institute Admin to link your username to your Student Enrollment ID."
                    },
                    ctaLabel = if (canManageStudents) "+ New Student Admission" else null,
                    onCtaClick = if (canManageStudents) ({ showAddDialog = true }) else null
                )
            }
        } else {
            items(filteredStudents, key = { it.id }) { student ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedStudent = student }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            StudentAvatarThumbnail(
                                photoUri = student.photoUri,
                                fullName = student.fullName,
                                palette = palette,
                                size = 50.dp
                            )
                            Column {
                                Text(
                                    text = student.fullName,
                                    style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${student.enrollmentNo} • ${student.courseName}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.TextSecondaryDark
                                    )
                                )
                                Text(
                                    text = "Batch: ${student.batchName.ifBlank { "Unassigned" }} • Phone: ${student.phone}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                                )
                                if (student.attachmentName.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AttachFile,
                                            contentDescription = "Attached File",
                                            tint = WciColors.Info,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = student.attachmentName,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = WciColors.Info,
                                                fontSize = 11.sp
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (student.status == "ACTIVE") WciColors.Success.copy(alpha = 0.18f)
                                        else WciColors.Warning.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(WciRadius.pill)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = student.status,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Bold,
                                        color = if (student.status == "ACTIVE") WciColors.Success else WciColors.Warning
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Due: ${formatInrPaise(student.dueFeePaise)}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = if (student.dueFeePaise > 0L) WciColors.Warning else WciColors.Success
                                )
                            )
                            Text(
                                text = "Attend: ${student.attendancePercent}%",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        EnrollStudentDialog(
            courses = state.courses,
            batches = state.batches,
            onDismiss = { showAddDialog = false },
            onSave = { enrollNo, name, email, phone, gName, gPhone, course, batch, feeInr, paidInr, photoDataUri, attachName, stuUser, stuPass, parUser, parPass ->
                viewModel.addOrUpdateStudent(
                    enrollmentNo = enrollNo,
                    fullName = name,
                    email = email,
                    phone = phone,
                    guardianName = gName,
                    guardianPhone = gPhone,
                    courseName = course,
                    batchName = batch,
                    totalFeeInr = feeInr,
                    paidFeeInr = paidInr,
                    photoUri = photoDataUri,
                    attachmentName = attachName,
                    studentUsername = stuUser,
                    studentPassword = stuPass,
                    parentUsername = parUser,
                    parentPassword = parPass
                )
                showAddDialog = false
            }
        )
    }

    selectedStudent?.let { stu ->
        Student360DetailDialog(
            student = stu,
            canEditOrDelete = canManageStudents,
            onDismiss = { selectedStudent = null },
            onIssueIdCard = {
                viewModel.issueCertificate(
                    certType = "ID_CARD",
                    studentId = stu.id,
                    studentName = stu.fullName,
                    courseName = stu.courseName,
                    grade = "ACTIVE"
                )
                selectedStudent = null
            },
            onCreateParentCredential = { parentUser, parentPass ->
                viewModel.provisionRoleCredential(
                    username = parentUser,
                    password = parentPass,
                    role = PortalRole.PARENT,
                    linkedEntityId = stu.id,
                    displayName = "${stu.guardianName.ifBlank { "Parent" }} (Parent of ${stu.fullName})"
                )
            },
            onStatusChange = { newStatus ->
                viewModel.addOrUpdateStudent(
                    existingId = stu.id,
                    enrollmentNo = stu.enrollmentNo,
                    fullName = stu.fullName,
                    email = stu.email,
                    phone = stu.phone,
                    guardianName = stu.guardianName,
                    guardianPhone = stu.guardianPhone,
                    courseName = stu.courseName,
                    batchName = stu.batchName,
                    status = newStatus,
                    totalFeeInr = stu.totalFeePaise / 100L,
                    paidFeeInr = stu.paidFeePaise / 100L,
                    attendancePercent = stu.attendancePercent,
                    photoUri = stu.photoUri,
                    attachmentName = stu.attachmentName
                )
                selectedStudent = null
            },
            onDeleteConfirmed = {
                viewModel.deleteStudent(stu)
                selectedStudent = null
            }
        )
    }
}

@Composable
private fun EnrollStudentDialog(
    courses: List<CourseRecord>,
    batches: List<BatchRecord>,
    onDismiss: () -> Unit,
    onSave: (
        enrollmentNo: String,
        fullName: String,
        email: String,
        phone: String,
        guardianName: String,
        guardianPhone: String,
        courseName: String,
        batchName: String,
        totalFeeInr: Long,
        paidFeeInr: Long,
        photoUri: String,
        attachmentName: String,
        studentUsername: String,
        studentPassword: String,
        parentUsername: String,
        parentPassword: String
    ) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalRolePalette.current

    var enrollmentNo by remember { mutableStateOf("WCI-2026-${(1000..9999).random()}") }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var guardianName by remember { mutableStateOf("") }
    var guardianPhone by remember { mutableStateOf("") }
    var courseName by remember { mutableStateOf(courses.firstOrNull()?.title ?: "ADCA (Advanced Diploma in Computer Applications)") }
    var batchName by remember { mutableStateOf(batches.firstOrNull()?.name ?: "Morning 9:00 AM Batch") }
    var totalFeeInr by remember { mutableStateOf("12000") }
    var paidFeeInr by remember { mutableStateOf("4000") }

    // Student Photo & File Attachment States
    var photoDataUri by remember { mutableStateOf("") }
    var attachedFileName by remember { mutableStateOf("") }

    // Admin-assigned Student & Parent Login Credentials (prefilled with 6-digit password, editable to 3+ digits)
    var studentUsername by remember { mutableStateOf("") }
    var studentPassword by remember { mutableStateOf("123456") }
    var parentUsername by remember { mutableStateOf("") }
    var parentPassword by remember { mutableStateOf("123456") }

    // 1. Android Photo Picker for Student Admission Photo
    val studentPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoDataUri = encodePhotoUriToCompactDataUri(context, uri)
        }
    }

    // 2. Android Document Picker for Student Admission File (Aadhar / Marksheet / ID PDF)
    val studentDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            attachedFileName = resolveAttachedDocumentMeta(context, uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WciColors.BgElevatedDark,
        title = { Text("New Student Admission", color = WciColors.TextPrimaryDark) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Student Photo & Document Attachment Row
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StudentAvatarThumbnail(
                            photoUri = photoDataUri,
                            fullName = fullName.ifBlank { "ST" },
                            palette = palette,
                            size = 56.dp
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LiquidPill(
                                text = if (photoDataUri.isBlank()) "Upload Student Photo" else "Change Student Photo ✓",
                                selected = photoDataUri.isNotBlank(),
                                icon = Icons.Default.AddAPhoto,
                                onClick = {
                                    studentPhotoLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("upload_student_photo_button")
                            )
                            LiquidPill(
                                text = if (attachedFileName.isBlank()) "Attach Document / File" else attachedFileName,
                                selected = attachedFileName.isNotBlank(),
                                icon = Icons.Default.AttachFile,
                                onClick = {
                                    studentDocumentLauncher.launch(arrayOf("application/pdf", "image/*", "*/*"))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("attach_student_file_button")
                            )
                        }
                    }
                }

                OutlinedTextField(value = enrollmentNo, onValueChange = { enrollmentNo = it }, label = { Text("Enrollment No") }, singleLine = true)
                OutlinedTextField(
                    value = fullName,
                    onValueChange = {
                        fullName = it
                        val cleanSlug = it.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                        if (cleanSlug.length >= 2) {
                            studentUsername = "${cleanSlug}_wci"
                            if (guardianName.isBlank()) {
                                parentUsername = "parent_$cleanSlug"
                            }
                        }
                    },
                    label = { Text("Student Full Name *") },
                    singleLine = true
                )
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Student Phone *") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Student Email") }, singleLine = true)
                OutlinedTextField(
                    value = guardianName,
                    onValueChange = {
                        guardianName = it
                        val cleanSlug = it.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                        if (cleanSlug.length >= 2) {
                            parentUsername = "parent_$cleanSlug"
                        }
                    },
                    label = { Text("Father / Guardian Name") },
                    singleLine = true
                )
                OutlinedTextField(value = guardianPhone, onValueChange = { guardianPhone = it }, label = { Text("Guardian Phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(value = courseName, onValueChange = { courseName = it }, label = { Text("Course Title *") }, singleLine = true)
                OutlinedTextField(value = batchName, onValueChange = { batchName = it }, label = { Text("Batch Name *") }, singleLine = true)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = totalFeeInr,
                        onValueChange = { totalFeeInr = it },
                        label = { Text("Total Fee (₹)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = paidFeeInr,
                        onValueChange = { paidFeeInr = it },
                        label = { Text("Paid Now (₹)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                HorizontalDivider(color = WciColors.GlassBorderSubtle)
                Text(
                    text = "Assign Student & Parent Login Credentials (Saved in Cloud)",
                    style = MaterialTheme.typography.titleSmall.copy(color = palette.start)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = studentUsername,
                        onValueChange = { studentUsername = it },
                        label = { Text("Student Username") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = studentPassword,
                        onValueChange = { studentPassword = it },
                        label = { Text("Student Password") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = parentUsername,
                        onValueChange = { parentUsername = it },
                        label = { Text("Parent Username") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = parentPassword,
                        onValueChange = { parentPassword = it },
                        label = { Text("Parent Password") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            ShimmerButton(
                text = "Save Admission",
                enabled = fullName.isNotBlank() && phone.isNotBlank(),
                onClick = {
                    onSave(
                        enrollmentNo,
                        fullName,
                        email,
                        phone,
                        guardianName,
                        guardianPhone,
                        courseName,
                        batchName,
                        totalFeeInr.toLongOrNull() ?: 0L,
                        paidFeeInr.toLongOrNull() ?: 0L,
                        photoDataUri,
                        attachedFileName,
                        studentUsername,
                        studentPassword,
                        parentUsername,
                        parentPassword
                    )
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = WciColors.TextMutedDark) }
        }
    )
}

@Composable
private fun Student360DetailDialog(
    student: StudentRecord,
    canEditOrDelete: Boolean,
    onDismiss: () -> Unit,
    onIssueIdCard: () -> Unit,
    onCreateParentCredential: (String, String) -> Unit,
    onStatusChange: (String) -> Unit,
    onDeleteConfirmed: () -> Unit
) {
    val palette = LocalRolePalette.current
    var parentUsername by remember(student.id) {
        mutableStateOf("parent_" + student.fullName.lowercase().replace(Regex("[^a-z0-9]"), ""))
    }
    var parentPassword by remember(student.id) { mutableStateOf("123456") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WciColors.BgElevatedDark,
        title = {
            Text("Student 360° Profile • ${student.enrollmentNo}", color = WciColors.TextPrimaryDark)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StudentAvatarThumbnail(
                        photoUri = student.photoUri,
                        fullName = student.fullName,
                        palette = palette,
                        size = 76.dp
                    )
                    QrMatrixBadge(
                        payload = "WCI-STUDENT:${student.enrollmentNo}:${student.id}",
                        modifier = Modifier.size(84.dp)
                    )
                }
                Text(student.fullName, style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark))
                Text("${student.courseName} • ${student.batchName}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                Text(
                    "Phone: ${student.phone} | Guardian: ${student.guardianName.ifBlank { "N/A" }} (${student.guardianPhone})",
                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                )
                if (student.attachmentName.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, tint = WciColors.Info, modifier = Modifier.size(16.dp))
                        Text(
                            "Attached Document: ${student.attachmentName}",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                        )
                    }
                }
                Text(
                    "Total Fee: ${formatInrPaise(student.totalFeePaise)} | Paid: ${formatInrPaise(student.paidFeePaise)} | Due: ${formatInrPaise(student.dueFeePaise)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.Success)
                )

                if (canEditOrDelete) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("ACTIVE", "ON_LEAVE", "ALUMNI").forEach { st ->
                            LiquidPill(
                                text = st,
                                selected = student.status == st,
                                onClick = { onStatusChange(st) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(color = WciColors.GlassBorderSubtle)
                    Text(
                        text = "Issue Parent Portal Login for ${student.fullName}:",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = WciColors.ParentPalette.start,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = parentUsername,
                            onValueChange = { parentUsername = it },
                            label = { Text("Parent Username") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = parentPassword,
                            onValueChange = { parentPassword = it },
                            label = { Text("Parent Password") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    ShimmerButton(
                        text = "Save Parent Login Linked to ${student.fullName.substringBefore(" ")}",
                        icon = Icons.Default.FamilyRestroom,
                        enabled = parentUsername.length >= 3 && parentPassword.length >= 3,
                        onClick = { onCreateParentCredential(parentUsername, parentPassword) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ShimmerButton(
                    text = "Generate QR Student ID Card",
                    icon = Icons.Default.Badge,
                    onClick = onIssueIdCard,
                    modifier = Modifier.fillMaxWidth()
                )

                if (canEditOrDelete) {
                    HoldToConfirmButton(
                        label = "Delete Student Record",
                        onConfirmed = onDeleteConfirmed,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = WciColors.TextPrimaryDark) }
        }
    )
}

// ============================================================================
// ADMISSION CRM & ENQUIRY PIPELINE
// ============================================================================

@Composable
fun AdmissionsCrmModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel
) {
    var showAddLead by remember { mutableStateOf(false) }
    var stageFilter by remember { mutableStateOf("ALL") }

    val stages = listOf("ALL", "NEW", "FOLLOW_UP", "DEMO_SCHEDULED", "COUNSELLED", "CONVERTED", "LOST")
    val filtered = remember(state.enquiries, stageFilter) {
        if (stageFilter == "ALL") state.enquiries else state.enquiries.filter { it.stage == stageFilter }
    }

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
                Column {
                    Text("Admissions & Enquiry CRM", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark))
                    Text("${state.enquiries.size} Leads • Demo Class & 1-Tap Conversion", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                }
                ShimmerButton(text = "+ New Lead", icon = Icons.Default.AddIcCall, onClick = { showAddLead = true })
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                stages.forEach { st ->
                    LiquidPill(text = st, selected = stageFilter == st, onClick = { stageFilter = st })
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.SupportAgent,
                    title = "No admission enquiries in this stage",
                    subtitle = "Log walk-in, phone, or social media enquiries, schedule demo classes, and convert leads into enrolled students in one tap.",
                    ctaLabel = "+ Log First Enquiry",
                    onCtaClick = { showAddLead = true }
                )
            }
        } else {
            items(filtered, key = { it.id }) { lead ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(lead.applicantName, style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                            Text(
                                "${lead.interestedCourse} • Source: ${lead.source}",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                            )
                            Text(
                                "Phone: ${lead.phone} • Follow-up: ${lead.followUpDate} • By: ${lead.counsellorName}",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                            )
                            if (lead.notes.isNotBlank()) {
                                Text(
                                    "Notes: ${lead.notes}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                                )
                            }
                        }
                        if (lead.stage != "CONVERTED") {
                            LiquidPill(
                                text = "Convert →",
                                selected = true,
                                onClick = {
                                    viewModel.convertEnquiryToStudent(
                                        enquiry = lead,
                                        totalFeeInr = 12000L,
                                        batchName = state.batches.firstOrNull()?.name ?: "Regular Batch"
                                    )
                                }
                            )
                        } else {
                            Text(
                                "CONVERTED",
                                style = MaterialTheme.typography.labelLarge.copy(color = WciColors.Success)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddLead) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var course by remember { mutableStateOf("DCA / ADCA / Python") }
        var source by remember { mutableStateOf("Walk-in") }
        var notes by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddLead = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Log Admission Enquiry", color = WciColors.TextPrimaryDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Applicant Name *") }, singleLine = true)
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Mobile Number *") }, singleLine = true)
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Interested Course *") }, singleLine = true)
                    OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text("Lead Source (Walk-in / Referral / Web)") }, singleLine = true)
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Counsellor Notes") })
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save Lead",
                    enabled = name.isNotBlank() && phone.isNotBlank(),
                    onClick = {
                        viewModel.saveEnquiry(
                            applicantName = name,
                            phone = phone,
                            email = email,
                            interestedCourse = course,
                            source = source,
                            stage = "NEW",
                            counsellorName = state.userProfile?.displayName ?: "Counsellor",
                            followUpDate = "",
                            notes = notes
                        )
                        showAddLead = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddLead = false }) { Text("Cancel") }
            }
        )
    }
}

// ============================================================================
// STAFF DIRECTORY & GUARDIANS
// ============================================================================

@Composable
fun StaffAndGuardiansModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    var activeTab by remember { mutableIntStateOf(initialTab) }
    var showAddStaff by remember { mutableStateOf(false) }
    var editingStaff by remember { mutableStateOf<StaffMember?>(null) }
    var showAddGuardian by remember { mutableStateOf(false) }
    val isAdmin = state.activePortalRole == PortalRole.ADMIN

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
                        text = "Teachers & Staff",
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        badgeCount = state.staff.size
                    )
                    LiquidPill(
                        text = "Guardians & PTM",
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        badgeCount = state.guardians.size
                    )
                }
                if (isAdmin) {
                    ShimmerButton(
                        text = if (activeTab == 0) "+ Add Teacher" else "+ Add Parent",
                        icon = if (activeTab == 0) Icons.Default.PersonAdd else Icons.Default.FamilyRestroom,
                        onClick = {
                            if (activeTab == 0) {
                                editingStaff = null
                                showAddStaff = true
                            } else {
                                showAddGuardian = true
                            }
                        },
                        testTag = if (activeTab == 0) "add_teacher_button" else "add_guardian_button"
                    )
                }
            }
        }

        if (activeTab == 0) {
            if (state.staff.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.Badge,
                        title = "No teachers or faculty registered yet",
                        subtitle = "Add teachers with their details, Gallery profile picture (for Biometric Face Authentication), and Teacher Portal username & password.",
                        ctaLabel = if (isAdmin) "+ Add First Teacher" else null,
                        onCtaClick = if (isAdmin) ({
                            editingStaff = null
                            showAddStaff = true
                        }) else null
                    )
                }
            } else {
                items(state.staff, key = { it.id }) { member ->
                    val resolvedUsername = member.teacherUsername.ifBlank {
                        "faculty_" + member.fullName.lowercase().replace(Regex("[^a-z0-9]"), "")
                    }
                    val resolvedPhotoUri = member.photoUri.ifBlank {
                        com.waqar.institute.biometric.FaceBiometricVerificationEngine.getEnrolledTeacherPhotoUri(
                            context = context,
                            teacherUid = member.id,
                            teacherName = member.fullName
                        )
                    }
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = if (isAdmin) ({
                            editingStaff = member
                            showAddStaff = true
                        }) else null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                StudentAvatarThumbnail(
                                    photoUri = resolvedPhotoUri,
                                    fullName = member.fullName,
                                    palette = WciColors.TeacherPalette,
                                    size = 54.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = member.fullName,
                                        style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark)
                                    )
                                    Text(
                                        text = "${member.employeeCode} • ${member.designation} (${member.department})",
                                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                                    )
                                    Text(
                                        text = "Login: @$resolvedUsername • Phone: ${member.phone}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMono,
                                            color = WciColors.TextSecondaryDark
                                        )
                                    )
                                    Text(
                                        text = if (resolvedPhotoUri.isNotBlank()) {
                                            "Biometric Face Photo Enrolled ✓"
                                        } else {
                                            "Tap to upload Gallery Photo for Face Auth"
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = if (resolvedPhotoUri.isNotBlank()) WciColors.Success else WciColors.Warning,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                            if (isAdmin) {
                                Column(horizontalAlignment = Alignment.End) {
                                    MoneyTicker(amountPaise = member.monthlySalaryPaise, color = WciColors.Success, fontSizeSp = 16)
                                    Text("/ month", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (state.guardians.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.FamilyRestroom,
                        title = "No guardian household links yet",
                        subtitle = "Link parents/guardians to enrolled students to record PTM feedback, consent forms, and fee alerts.",
                        ctaLabel = if (isAdmin) "+ Link Guardian" else null,
                        onCtaClick = if (isAdmin) ({ showAddGuardian = true }) else null
                    )
                }
            } else {
                items(state.guardians, key = { it.id }) { g ->
                    val linkedEnrollment = state.students.firstOrNull {
                        it.id.equals(g.studentId, ignoreCase = true) ||
                            it.fullName.equals(g.studentName, ignoreCase = true)
                    }?.enrollmentNo.orEmpty()
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("${g.guardianName} (${g.relation} of ${g.studentName})", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                        Text(
                            text = "Linked Student ID: ${g.studentId}" + if (linkedEnrollment.isNotBlank()) " • Roll: $linkedEnrollment" else "",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.Info)
                        )
                        Text("Phone: ${g.phone} • Consent Signed: ${if (g.consentSigned) "Yes" else "Pending"}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Success))
                        if (g.ptmNotes.isNotBlank()) {
                            Text("PTM Notes: ${g.ptmNotes}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                        }
                    }
                }
            }
        }
    }

    if (showAddStaff) {
        val currentEditing = editingStaff
        var code by remember(currentEditing) {
            mutableStateOf(currentEditing?.employeeCode ?: "WCI-EMP-${(101..999).random()}")
        }
        var name by remember(currentEditing) {
            mutableStateOf(currentEditing?.fullName ?: "")
        }
        var phone by remember(currentEditing) {
            mutableStateOf(currentEditing?.phone ?: "")
        }
        var email by remember(currentEditing) {
            mutableStateOf(currentEditing?.email ?: "")
        }
        var roleTitle by remember(currentEditing) {
            mutableStateOf(currentEditing?.designation ?: "Senior Computer Faculty")
        }
        var dept by remember(currentEditing) {
            mutableStateOf(currentEditing?.department ?: "Academics & Lab")
        }
        var salary by remember(currentEditing) {
            mutableStateOf(
                if (currentEditing != null && currentEditing.monthlySalaryPaise > 0L) {
                    (currentEditing.monthlySalaryPaise / 100L).toString()
                } else {
                    "25000"
                }
            )
        }
        var teacherUsername by remember(currentEditing) {
            mutableStateOf(
                currentEditing?.teacherUsername?.ifBlank {
                    if (currentEditing.fullName.isNotBlank()) {
                        "faculty_" + currentEditing.fullName.lowercase().replace(Regex("[^a-z0-9]"), "")
                    } else ""
                } ?: ""
            )
        }
        var teacherPassword by remember(currentEditing) { mutableStateOf("123456") }
        var teacherPhotoDataUri by remember(currentEditing) {
            mutableStateOf(
                currentEditing?.photoUri?.ifBlank {
                    com.waqar.institute.biometric.FaceBiometricVerificationEngine.getEnrolledTeacherPhotoUri(
                        context = context,
                        teacherUid = currentEditing.id,
                        teacherName = currentEditing.fullName
                    )
                } ?: ""
            )
        }

        // Zero-Permission Android Photo Picker for Teacher Gallery Profile Picture (Biometric Face Auth)
        val teacherPhotoLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                teacherPhotoDataUri = encodePhotoUriToCompactDataUri(context, uri)
            }
        }

        AlertDialog(
            onDismissRequest = {
                showAddStaff = false
                editingStaff = null
            },
            containerColor = WciColors.BgElevatedDark,
            title = {
                Text(
                    text = if (currentEditing == null) {
                        "Add Teacher • Profile Photo & Login"
                    } else {
                        "Update Teacher • Biometric Photo & Login"
                    },
                    color = WciColors.TextPrimaryDark
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Teacher Gallery Profile Photo Card (Wired to Biometric Face Authentication)
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StudentAvatarThumbnail(
                                photoUri = teacherPhotoDataUri,
                                fullName = name.ifBlank { "TR" },
                                palette = WciColors.TeacherPalette,
                                size = 60.dp
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                LiquidPill(
                                    text = if (teacherPhotoDataUri.isBlank()) {
                                        "Select Teacher Photo (Gallery)"
                                    } else {
                                        "Change Teacher Photo ✓"
                                    },
                                    selected = teacherPhotoDataUri.isNotBlank(),
                                    icon = Icons.Default.AddAPhoto,
                                    onClick = {
                                        teacherPhotoLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("upload_teacher_photo_button")
                                )
                                Text(
                                    text = if (teacherPhotoDataUri.isNotBlank()) {
                                        "Biometric Face Authentication linked to this Gallery photo ✓"
                                    } else {
                                        "Used for Teacher 24h Biometric Face Authentication & ID"
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = if (teacherPhotoDataUri.isNotBlank()) WciColors.Success else WciColors.TextMutedDark,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }

                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Employee Code") }, singleLine = true)
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            val cleanSlug = it.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                            if (cleanSlug.length >= 2 && currentEditing == null) {
                                teacherUsername = "faculty_$cleanSlug"
                            }
                        },
                        label = { Text("Teacher Full Name *") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Teacher Mobile Phone *") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Teacher Email") }, singleLine = true)
                    OutlinedTextField(value = roleTitle, onValueChange = { roleTitle = it }, label = { Text("Designation (e.g. Senior Faculty)") }, singleLine = true)
                    OutlinedTextField(value = dept, onValueChange = { dept = it }, label = { Text("Department (e.g. Computer Science)") }, singleLine = true)
                    OutlinedTextField(
                        value = salary,
                        onValueChange = { salary = it },
                        label = { Text("Monthly Salary (₹)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    HorizontalDivider(color = WciColors.GlassBorderSubtle)
                    Text(
                        text = "Create Teacher Username & Password (Saved in Cloud)",
                        style = MaterialTheme.typography.titleSmall.copy(color = WciColors.TeacherPalette.start)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = teacherUsername,
                            onValueChange = { teacherUsername = it },
                            label = { Text("Teacher Username") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = teacherPassword,
                            onValueChange = { teacherPassword = it },
                            label = { Text("Teacher Password") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save Teacher & Login",
                    enabled = name.isNotBlank() && phone.isNotBlank(),
                    onClick = {
                        viewModel.saveStaffMember(
                            existingId = currentEditing?.id,
                            employeeCode = code,
                            fullName = name,
                            email = email,
                            phone = phone,
                            designation = roleTitle,
                            department = dept,
                            monthlySalaryInr = salary.toLongOrNull() ?: 0L,
                            photoUri = teacherPhotoDataUri,
                            teacherUsername = teacherUsername,
                            teacherPassword = teacherPassword
                        )
                        showAddStaff = false
                        editingStaff = null
                    }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddStaff = false
                        editingStaff = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddGuardian) {
        var gName by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var relation by remember { mutableStateOf("Father") }
        var sName by remember { mutableStateOf(state.students.firstOrNull()?.fullName ?: "") }
        var ptm by remember { mutableStateOf("Attended orientation & signed digital consent.") }
        var parentUsername by remember { mutableStateOf("") }
        var parentPassword by remember { mutableStateOf("123456") }

        AlertDialog(
            onDismissRequest = { showAddGuardian = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Create Parent Account & Link Student", color = WciColors.TextPrimaryDark) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.students.isNotEmpty()) {
                        Text(
                            text = "Select Student (Whose Parent is this?):",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = WciColors.Info,
                                fontWeight = FontWeight.ExtraBold
                            )
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
                                    selected = sName.equals(stu.fullName, ignoreCase = true),
                                    onClick = {
                                        sName = stu.fullName
                                        if (gName.isBlank() && stu.guardianName.isNotBlank()) {
                                            gName = stu.guardianName
                                        }
                                        if (phone.isBlank() && stu.guardianPhone.isNotBlank()) {
                                            phone = stu.guardianPhone
                                        }
                                        if (parentUsername.isBlank()) {
                                            parentUsername = "parent_" + stu.fullName.lowercase().replace(Regex("[^a-z0-9]"), "")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = gName,
                        onValueChange = {
                            gName = it
                            if (parentUsername.isBlank() && it.length >= 3) {
                                parentUsername = "parent_" + it.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                            }
                        },
                        label = { Text("Parent / Guardian Name *") },
                        singleLine = true
                    )
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Guardian Mobile *") }, singleLine = true)
                    OutlinedTextField(value = relation, onValueChange = { relation = it }, label = { Text("Relation (Father/Mother/Guardian)") }, singleLine = true)
                    OutlinedTextField(value = sName, onValueChange = { sName = it }, label = { Text("Linked Student Name, Roll No or ID *") }, singleLine = true)
                    OutlinedTextField(value = ptm, onValueChange = { ptm = it }, label = { Text("PTM / Consent Notes") })
                    HorizontalDivider(color = WciColors.GlassBorderSubtle)
                    Text(
                        text = "Assign Parent Portal Login Credentials (Saved in Cloud):",
                        style = MaterialTheme.typography.titleSmall.copy(color = WciColors.ParentPalette.start)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = parentUsername,
                            onValueChange = { parentUsername = it },
                            label = { Text("Parent Username") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = parentPassword,
                            onValueChange = { parentPassword = it },
                            label = { Text("Parent Password") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save Parent & Login",
                    enabled = gName.isNotBlank() && phone.isNotBlank() && sName.isNotBlank(),
                    onClick = {
                        viewModel.saveGuardianLink(
                            guardianName = gName,
                            phone = phone,
                            email = "",
                            relation = relation,
                            studentName = sName,
                            ptmNotes = ptm,
                            parentUsername = parentUsername,
                            parentPassword = parentPassword
                        )
                        showAddGuardian = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddGuardian = false }) { Text("Cancel") } }
        )
    }
}
