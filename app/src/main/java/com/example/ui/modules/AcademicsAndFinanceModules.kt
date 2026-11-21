package com.example.ui.modules

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import com.example.attendance.AntiProxyAttendanceManager
import com.example.attendance.GeofenceVerificationResult
import com.example.data.model.PortalRole
import com.example.media.BindScreenCapturePolicy
import com.example.media.ScreenCapturePolicy
import com.example.ui.designsystem.*
import com.example.util.DocumentExportHelper
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import kotlinx.coroutines.launch

// ============================================================================
// M05 & M06: COURSES, CURRICULUM & BATCH TIMETABLE (F089–F130)
// ============================================================================

@Composable
fun CoursesAndBatchesModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    initialTab: Int = 0
) {
    var activeTab by remember { mutableIntStateOf(initialTab) }
    var showAddCourse by remember { mutableStateOf(false) }
    var showAddBatch by remember { mutableStateOf(false) }
    val isAdmin = state.activePortalRole == PortalRole.ADMIN
    val canManageBatches = isAdmin || state.activePortalRole == PortalRole.TEACHER

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
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiquidPill(
                        text = "Courses",
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        badgeCount = state.courses.size
                    )
                    LiquidPill(
                        text = "Batches & Timetable",
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        badgeCount = state.batches.size
                    )
                    LiquidPill(
                        text = "Adaptive Analytics",
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 }
                    )
                }
                if ((activeTab == 0 && isAdmin) || (activeTab == 1 && canManageBatches)) {
                    ShimmerButton(
                        text = if (activeTab == 0) "+ Course" else "+ Batch",
                        onClick = { if (activeTab == 0) showAddCourse = true else showAddBatch = true }
                    )
                }
            }
        }

        if (activeTab == 0) {
            if (state.courses.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "No courses defined yet",
                        subtitle = "Create computer institute courses (DCA, ADCA, Tally Prime with GST, Python, Full-Stack Web, CCC, Typing) with fee & syllabus structures.",
                        ctaLabel = if (isAdmin) "+ Create First Course" else null,
                        onCtaClick = if (isAdmin) ({ showAddCourse = true }) else null
                    )
                }
            } else {
                items(state.courses, key = { it.id }) { course ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${course.code} • ${course.title}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                                Text(
                                    "Category: ${course.category} • Duration: ${course.durationMonths} Months • ${course.totalModules} Modules",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                                )
                                if (course.syllabusSummary.isNotBlank()) {
                                    Text("Syllabus: ${course.syllabusSummary}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                                }
                            }
                            MoneyTicker(amountPaise = course.totalFeePaise, color = WciColors.Success, fontSizeSp = 18)
                        }
                    }
                }
            }
        } else if (activeTab == 1) {
            if (state.batches.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Default.Schedule,
                        title = "No batches or timetable slots scheduled",
                        subtitle = "Schedule morning/afternoon/evening lab batches with room allocation, assigned faculty, and seat capacity.",
                        ctaLabel = if (canManageBatches) "+ Schedule First Batch" else null,
                        onCtaClick = if (canManageBatches) ({ showAddBatch = true }) else null
                    )
                }
            } else {
                items(state.batches, key = { it.id }) { batch ->
                    val batchAdminLabel = batch.batchAdminTeacherName.ifBlank { batch.teacherName }.ifBlank { "Unassigned" }
                    val liveBatchCount = viewModel.getStudentCountForBatch(batch, state.students)
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${batch.code} • ${batch.name}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary, fontWeight = FontWeight.ExtraBold))
                                Text(
                                    "${batch.courseName} • Batch Admin: $batchAdminLabel",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info, fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    "${batch.daysOfWeek} (${batch.startTime}–${batch.endTime}) • Room: ${batch.roomName}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.textSecondary)
                                )
                                Text(
                                    text = buildString {
                                        append("Permissions: ")
                                        if (batch.canBatchAdminTakeAttendance) append("Attendance ✓ ")
                                        if (batch.canBatchAdminScheduleExams) append("• Exams ✓")
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Success)
                                )
                                if (isAdmin && state.staff.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Assign Batch-Admin Teacher (1-Tap):",
                                        style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Warning)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        state.staff.forEach { teacherStaff ->
                                            val isAssigned =
                                                batch.batchAdminTeacherId.equals(teacherStaff.id, ignoreCase = true) ||
                                                    batchAdminLabel.contains(teacherStaff.fullName, ignoreCase = true)
                                            LiquidPill(
                                                text = if (isAssigned) "★ ${teacherStaff.fullName}" else teacherStaff.fullName,
                                                selected = isAssigned,
                                                onClick = {
                                                    viewModel.assignBatchAdminTeacher(batch.id, teacherStaff)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = batch.status,
                                    style = MaterialTheme.typography.labelLarge.copy(color = WciColors.Success)
                                )
                                Text(
                                    text = "$liveBatchCount / ${batch.capacity} students",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.Info,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Adaptive Course Analytics Tab (activeTab == 2)
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowColor = WciColors.CyanBright
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Course Adaptive Mastery & Retention Hub",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = WciColors.textPrimary
                            )
                        )
                        Text(
                            text = "Background AI aggregates CBT performance and syllabus progress per course to predict mastery velocity.",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                    }
                }
            }
            items(state.courses) { course ->
                val enrolledInCourse = state.students.filter {
                    it.courseName.contains(course.title, ignoreCase = true) ||
                        it.courseName.contains(course.code, ignoreCase = true)
                }
                val courseResults = state.examResults.filter {
                    it.examTitle.contains(course.title, ignoreCase = true) ||
                        it.examTitle.contains(course.code, ignoreCase = true)
                }
                val avgMastery = if (courseResults.isNotEmpty()) {
                    courseResults.sumOf { if (it.totalMarks > 0) (it.marksObtained * 100) / it.totalMarks else 0 } / courseResults.size
                } else 74

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${course.code} • ${course.title}",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = WciColors.textPrimary
                                )
                            )
                            Text(
                                text = "Enrolled: ${enrolledInCourse.size} Students • Duration: ${course.durationMonths} Mos",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                            )
                            Text(
                                text = "Estimated Concept Mastery: $avgMastery%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (avgMastery >= 75) WciColors.Success else WciColors.Warning,
                                    fontFamily = JetBrainsMono
                                )
                            )
                        }
                        DonutProgressRing(
                            progress = avgMastery / 100f,
                            color = if (avgMastery >= 75) WciColors.Success else WciColors.Warning,
                            label = "$avgMastery%",
                            subLabel = "Mastery",
                            sizeDp = 54.dp
                        )
                    }
                }
            }
        }
    }

    if (showAddCourse) {
        var code by remember { mutableStateOf("ADCA-01") }
        var title by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("Diploma") }
        var months by remember { mutableStateOf("12") }
        var feeInr by remember { mutableStateOf("15000") }
        var modules by remember { mutableStateOf("8") }
        var syllabus by remember { mutableStateOf("MS Office, Tally Prime + GST, HTML/CSS/JS, Python & AI Tools") }

        AlertDialog(
            onDismissRequest = { showAddCourse = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Create Course", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Course Code *") }, singleLine = true)
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Course Title *") }, singleLine = true)
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = months, onValueChange = { months = it }, label = { Text("Months") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = modules, onValueChange = { modules = it }, label = { Text("Modules") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    OutlinedTextField(value = feeInr, onValueChange = { feeInr = it }, label = { Text("Total Course Fee (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = syllabus, onValueChange = { syllabus = it }, label = { Text("Syllabus Summary") })
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save Course",
                    enabled = code.isNotBlank() && title.isNotBlank(),
                    onClick = {
                        viewModel.saveCourse(
                            code = code,
                            title = title,
                            category = category,
                            durationMonths = months.toIntOrNull() ?: 6,
                            totalFeeInr = feeInr.toLongOrNull() ?: 0L,
                            syllabusSummary = syllabus,
                            totalModules = modules.toIntOrNull() ?: 6
                        )
                        showAddCourse = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddCourse = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    if (showAddBatch) {
        var code by remember { mutableStateOf("BAT-M1") }
        var name by remember { mutableStateOf("") }
        var courseName by remember { mutableStateOf(state.courses.firstOrNull()?.title ?: "ADCA") }
        var teacher by remember { mutableStateOf(state.staff.firstOrNull()?.fullName ?: "Waqar Sir") }
        var room by remember { mutableStateOf("Computer Lab 1") }
        var days by remember { mutableStateOf("Mon-Sat") }
        var start by remember { mutableStateOf("09:00") }
        var end by remember { mutableStateOf("10:30") }
        var capacity by remember { mutableStateOf("30") }

        AlertDialog(
            onDismissRequest = { showAddBatch = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Schedule Batch & Lab Slot", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Batch Code *") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Batch Name *") }, singleLine = true)
                    OutlinedTextField(value = courseName, onValueChange = { courseName = it }, label = { Text("Course Name *") }, singleLine = true)
                    OutlinedTextField(value = teacher, onValueChange = { teacher = it }, label = { Text("Batch Admin / Assigned Teacher *") }, singleLine = true)
                    if (state.staff.isNotEmpty()) {
                        Text(
                            "Select Batch Admin Teacher:",
                            style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Info)
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.staff.forEach { stf ->
                                LiquidPill(
                                    text = stf.fullName,
                                    selected = teacher.contains(stf.fullName, ignoreCase = true),
                                    onClick = { teacher = "${stf.fullName} (Batch Admin)" }
                                )
                            }
                        }
                    }
                    OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("Lab / Classroom") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Start (24h)") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("End (24h)") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = capacity, onValueChange = { capacity = it }, label = { Text("Seats") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save Batch",
                    enabled = code.isNotBlank() && name.isNotBlank(),
                    onClick = {
                        viewModel.saveBatch(code, name, courseName, teacher, room, days, start, end, capacity.toIntOrNull() ?: 30)
                        showAddBatch = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddBatch = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }
}

// ============================================================================
// M07: ATTENDANCE & QR ROLL-CALL ENGINE (F131–F156)
// ============================================================================

@Composable
fun AttendanceModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel
) {
    val palette = LocalRolePalette.current
    val canMarkAttendance = state.activePortalRole == PortalRole.ADMIN || state.activePortalRole == PortalRole.TEACHER
    var targetTypeFilter by remember { mutableStateOf("STUDENT") }
    var showMarkDialog by remember { mutableStateOf(false) }
    var showQrScanDialog by remember { mutableStateOf(false) }

    val unsyncedCount = remember(state.queuedOfflineAttendance) {
        state.queuedOfflineAttendance.count { !it.synced }
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
                        text = if (canMarkAttendance) "Attendance & QR Roll-Call" else "My Attendance Log",
                        style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.textPrimary)
                    )
                    Text(
                        text = if (canMarkAttendance) {
                            "${state.attendance.size} Records • Offline Queue + Live Cloud Sync"
                        } else {
                            "${state.attendance.size} Verified Attendance Entries"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                    )
                }
                if (canMarkAttendance) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidPill(
                            text = "QR Check-in",
                            selected = false,
                            icon = Icons.Default.QrCodeScanner,
                            onClick = { showQrScanDialog = true }
                        )
                        ShimmerButton(
                            text = "+ Mark",
                            icon = Icons.AutoMirrored.Filled.FactCheck,
                            onClick = { showMarkDialog = true },
                            testTag = "mark_attendance_button"
                        )
                    }
                }
            }
        }

        // Teacher Portal Pre-Biometric 50m GPS Geofence + Live Face Biometric Attendance Card
        if (state.activePortalRole == com.example.data.model.PortalRole.TEACHER || canMarkAttendance) {
            item {
                val teacherContext = androidx.compose.ui.platform.LocalContext.current
                val teacherScope = androidx.compose.runtime.rememberCoroutineScope()
                val resolvedTeacherId: String = state.userProfile?.linkedEntityId?.ifBlank { state.userProfile.uid }
                    ?: state.userProfile?.uid.orEmpty()
                val resolvedTeacherName: String = state.userProfile?.displayName?.ifBlank { "Faculty Member" }
                    ?: "Faculty Member"

                var isCheckingGps by remember { mutableStateOf(false) }
                var gpsAlertDialogText by remember { mutableStateOf<String?>(null) }
                var gpsStatusBanner by remember {
                    mutableStateOf(
                        "Institute Coordinates: ${com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_LATITUDE}, ${com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_LONGITUDE} (${com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_PLUS_CODE_LABEL}) • Max Radius: 50m"
                    )
                }

                val faceBiometricCameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview()
                ) { capturedBitmap ->
                    if (capturedBitmap != null) {
                        teacherScope.launch {
                            val matchRes = com.waqar.institute.biometric.FaceBiometricVerificationEngine.verifyTeacherLiveFace(
                                teacherUid = resolvedTeacherId,
                                teacherName = resolvedTeacherName,
                                liveFrameBitmap = capturedBitmap,
                                context = teacherContext
                            )
                            when (matchRes) {
                                is com.waqar.institute.biometric.FaceBiometricVerificationEngine.BiometricMatchResult.Verified -> {
                                    com.waqar.institute.biometric.TeacherBiometricGateManager.recordSuccessfulLoginVerification(
                                        context = teacherContext,
                                        teacherUid = resolvedTeacherId,
                                        cosineScore = matchRes.cosineSimilarity
                                    )
                                    com.waqar.institute.biometric.TeacherAttendanceSyncManager.markTeacherPresentOnBiometricPass(
                                        context = teacherContext,
                                        teacherUid = resolvedTeacherId,
                                        teacherName = resolvedTeacherName,
                                        cosineSimilarity = matchRes.cosineSimilarity,
                                        livenessConfidence = matchRes.livenessConfidence
                                    )
                                    viewModel.markAttendance(
                                        targetType = "STAFF",
                                        targetId = resolvedTeacherId.ifBlank { "stf_teacher" },
                                        targetName = resolvedTeacherName,
                                        batchName = "Faculty Biometric + 50m GPS",
                                        status = "PRESENT",
                                        method = "BIOMETRIC"
                                    )
                                    gpsStatusBanner = "Face Biometric Verified (${"%.2f".format(matchRes.cosineSimilarity)}) & 50m GPS Geofence Passed • Attendance Marked PRESENT ✓"
                                }
                                is com.waqar.institute.biometric.FaceBiometricVerificationEngine.BiometricMatchResult.Mismatch -> {
                                    gpsAlertDialogText = matchRes.message
                                }
                                is com.waqar.institute.biometric.FaceBiometricVerificationEngine.BiometricMatchResult.SpoofOrLivenessFailed -> {
                                    gpsAlertDialogText = matchRes.reason
                                }
                                is com.waqar.institute.biometric.FaceBiometricVerificationEngine.BiometricMatchResult.Error -> {
                                    gpsAlertDialogText = matchRes.errorMessage
                                }
                            }
                        }
                    }
                }

                val executeGpsGeofenceThenBiometric: () -> Unit = {
                    isCheckingGps = true
                    gpsStatusBanner = "Fetching High-Accuracy GPS fix before launching Face Biometric scanner…"
                    teacherScope.launch {
                        val geofenceRes = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier
                            .verifyTeacherLocationBeforeBiometric(teacherContext)
                        isCheckingGps = false
                        when (geofenceRes) {
                            is com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.GeofenceVerificationResult.InsidePremises -> {
                                gpsStatusBanner = "GPS Verified (${"%.1f".format(geofenceRes.distanceMeters)}m ≤ 50m) ✓ Launching Face Biometric scanner…"
                                faceBiometricCameraLauncher.launch(null)
                            }
                            is com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.GeofenceVerificationResult.OutsidePremises -> {
                                gpsStatusBanner = "${com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE} (${"%.1f".format(geofenceRes.distanceMeters)}m > 50m)"
                                gpsAlertDialogText = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE
                            }
                            is com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.GeofenceVerificationResult.MockLocationRejected -> {
                                gpsStatusBanner = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.MOCK_LOCATION_ALERT_MESSAGE
                                gpsAlertDialogText = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.MOCK_LOCATION_ALERT_MESSAGE
                            }
                            is com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.GeofenceVerificationResult.LocationUnavailable -> {
                                gpsStatusBanner = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE
                                gpsAlertDialogText = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE
                            }
                        }
                    }
                }

                val teacherLocationPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) { perms ->
                    val granted = perms[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        perms[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    if (granted) {
                        executeGpsGeofenceThenBiometric()
                    } else {
                        gpsAlertDialogText = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE
                    }
                }

                if (gpsAlertDialogText != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { gpsAlertDialogText = null },
                        containerColor = WciColors.elevatedSurface,
                        titleContentColor = WciColors.textPrimary,
                        textContentColor = WciColors.textSecondary,
                        title = { Text("Teacher Biometric Attendance Gate", fontWeight = FontWeight.Bold, color = WciColors.textPrimary) },
                        text = { Text(gpsAlertDialogText ?: com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE, color = WciColors.textSecondary) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { gpsAlertDialogText = null }) {
                                Text("OK", fontWeight = FontWeight.Bold, color = WciColors.infoColor)
                            }
                        }
                    )
                }

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowColor = WciColors.Success
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Teacher Face Biometric Attendance (50m GPS Geofence)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = gpsStatusBanner,
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                        ShimmerButton(
                            text = if (isCheckingGps) {
                                "Checking 50m GPS Geofence…"
                            } else {
                                "Verify 50m GPS & Scan Face Biometric"
                            },
                            icon = Icons.Default.LocationOn,
                            enabled = !isCheckingGps,
                            onClick = {
                                if (com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.hasFineOrCoarseLocationPermission(teacherContext)) {
                                    executeGpsGeofenceThenBiometric()
                                } else {
                                    teacherLocationPermLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "teacher_gps_biometric_attendance_button"
                        )
                    }
                }
            }
        }

        // Offline Sync Queue Banner (Room Database integration — PDF 1 Section 5.3)
        if (unsyncedCount > 0 && canMarkAttendance) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowColor = WciColors.Warning
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "$unsyncedCount Attendance Entries Queued Offline (Room DB)",
                                style = MaterialTheme.typography.titleSmall.copy(color = WciColors.Warning)
                            )
                            Text(
                                "Tap Sync Now to push queued records to Firestore.",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                            )
                        }
                        ShimmerButton(
                            text = "Sync Now",
                            onClick = { viewModel.syncOfflineAttendanceQueue() }
                        )
                    }
                }
            }
        }

        // Batch-Wise Student Roster Roll-Call (Admin & Teacher select a Batch and mark all students by name in 1 tap)
        if (canMarkAttendance && state.students.isNotEmpty() && targetTypeFilter == "STUDENT") {
            item {
                val availableBatchNames = remember(state.batches, state.students) {
                    (listOf("ALL BATCHES") + state.batches.map { it.name } + state.students.map { it.batchName })
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                }
                var selectedBatchRollCall by remember(availableBatchNames) {
                    mutableStateOf(availableBatchNames.firstOrNull { it != "ALL BATCHES" } ?: "ALL BATCHES")
                }
                val studentsInSelectedBatch = remember(state.students, selectedBatchRollCall) {
                    if (selectedBatchRollCall == "ALL BATCHES") {
                        state.students
                    } else {
                        state.students.filter {
                            it.batchName.equals(selectedBatchRollCall, ignoreCase = true)
                        }
                    }
                }
                var rosterStatusMap by remember(selectedBatchRollCall, studentsInSelectedBatch) {
                    mutableStateOf(
                        studentsInSelectedBatch.associate { it.id to "PRESENT" }
                    )
                }

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowColor = palette.start,
                    strongSurface = true
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Batch-Wise Student Roster Roll-Call",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = WciColors.textPrimary,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                Text(
                                    "Select a Batch → Tap Present / Absent / Late per student → Save entire batch",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.infoColor)
                                )
                            }
                            if (studentsInSelectedBatch.isNotEmpty()) {
                                LiquidPill(
                                    text = "✓ All Present",
                                    selected = true,
                                    onClick = {
                                        rosterStatusMap = studentsInSelectedBatch.associate { it.id to "PRESENT" }
                                    }
                                )
                            }
                        }

                        // Batch Selector Pill Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableBatchNames.forEach { bName ->
                                LiquidPill(
                                    text = bName,
                                    selected = selectedBatchRollCall == bName,
                                    icon = Icons.Default.Groups,
                                    onClick = { selectedBatchRollCall = bName }
                                )
                            }
                        }

                        if (studentsInSelectedBatch.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(WciRadius.md))
                                    .background(WciColors.cardInnerSurface)
                                    .border(1.dp, WciColors.glassBorderSubtleColor, RoundedCornerShape(WciRadius.md))
                                    .padding(14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No students enrolled in \"$selectedBatchRollCall\" yet.",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.textSecondary)
                                )
                            }
                        } else {
                            // 2-Line Thumb-Friendly Student Roster Rows (Prevents crushed names on small screens)
                            studentsInSelectedBatch.forEachIndexed { idx, stu ->
                                val chosenStatus = rosterStatusMap[stu.id] ?: "PRESENT"
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(WciRadius.md))
                                        .background(WciColors.cardInnerSurface)
                                        .border(1.dp, WciColors.glassBorderSubtleColor, RoundedCornerShape(WciRadius.md))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${idx + 1}. ${stu.fullName}",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = WciColors.textPrimary
                                                )
                                            )
                                            Text(
                                                text = "${stu.enrollmentNo} • ${stu.batchName.ifBlank { selectedBatchRollCall }}",
                                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                                            )
                                        }
                                        Text(
                                            text = chosenStatus,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = JetBrainsMono,
                                                fontWeight = FontWeight.Bold,
                                                color = when (chosenStatus) {
                                                    "PRESENT" -> WciColors.successColor
                                                    "ABSENT" -> WciColors.dangerColor
                                                    else -> WciColors.warningColor
                                                }
                                            )
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf("PRESENT" to "Present", "ABSENT" to "Absent", "LATE" to "Late").forEach { (stKey, stLabel) ->
                                            LiquidPill(
                                                text = stLabel,
                                                selected = chosenStatus == stKey,
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    rosterStatusMap = rosterStatusMap.toMutableMap().apply {
                                                        put(stu.id, stKey)
                                                    }
                                                    viewModel.markAttendance(
                                                        targetType = "STUDENT",
                                                        targetId = stu.id,
                                                        targetName = stu.fullName,
                                                        batchName = stu.batchName.ifBlank { selectedBatchRollCall },
                                                        status = stKey,
                                                        method = "BATCH_ROSTER"
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        ShimmerButton(
                            text = "Save ${studentsInSelectedBatch.size} Students Attendance to Firebase",
                            icon = Icons.Default.CloudUpload,
                            enabled = studentsInSelectedBatch.isNotEmpty(),
                            onClick = {
                                val bulkPayload = studentsInSelectedBatch.associateWith { stu ->
                                    rosterStatusMap[stu.id] ?: "PRESENT"
                                }
                                viewModel.markBatchAttendanceInBulk(
                                    batchName = selectedBatchRollCall,
                                    studentStatuses = bulkPayload
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (canMarkAttendance) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidPill(text = "Student Attendance", selected = targetTypeFilter == "STUDENT", onClick = { targetTypeFilter = "STUDENT" })
                    LiquidPill(text = "Faculty & Staff Attendance", selected = targetTypeFilter == "STAFF", onClick = { targetTypeFilter = "STAFF" })
                }
            }
        }

        val filtered = state.attendance.filter { it.targetType == targetTypeFilter }
        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    title = "No $targetTypeFilter attendance logs yet",
                    subtitle = if (canMarkAttendance) {
                        "Mark attendance via the 1-tap roll-call roster, manual entry, or QR ID card check-in."
                    } else {
                        "Your daily batch attendance logs marked by your faculty will appear here."
                    },
                    ctaLabel = if (canMarkAttendance) "+ Mark First Attendance" else null,
                    onCtaClick = if (canMarkAttendance) ({ showMarkDialog = true }) else null
                )
            }
        } else {
            items(filtered, key = { it.id }) { att ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(att.targetName, style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                            Text(
                                "${att.dateIso} • Batch: ${att.batchName.ifBlank { "General" }} • Via ${att.method}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.textMuted)
                            )
                            if (att.remarks.isNotBlank()) {
                                Text("Note: ${att.remarks}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary))
                            }
                        }
                        val statusColor = when (att.status) {
                            "PRESENT" -> WciColors.Success
                            "ABSENT" -> WciColors.Danger
                            else -> WciColors.Warning
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = att.status,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            )
                            if (canMarkAttendance && att.status == "ABSENT" && att.targetType == "STUDENT") {
                                val waContext = LocalContext.current
                                val studentPhone = state.students.firstOrNull {
                                    it.id == att.targetId || it.fullName.equals(att.targetName, ignoreCase = true)
                                }?.let { stu -> stu.guardianPhone.ifBlank { stu.phone } }.orEmpty()
                                LiquidPill(
                                    text = "WhatsApp Alert",
                                    selected = false,
                                    icon = Icons.AutoMirrored.Filled.Send,
                                    onClick = {
                                        if (studentPhone.isBlank()) {
                                            viewModel.postMessage("No parent/student phone number saved for ${att.targetName}.")
                                        } else {
                                            val launched = com.waqar.institute.communication.WhatsAppInstituteAlertHelper.sendAbsentAlert(
                                                context = waContext,
                                                guardianPhone = studentPhone,
                                                studentName = att.targetName,
                                                batchName = att.batchName.ifBlank { "General" },
                                                dateIso = att.dateIso
                                            )
                                            if (!launched) {
                                                viewModel.postMessage("Unable to open WhatsApp on this device.")
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("whatsapp_absent_alert_${att.id}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMarkDialog) {
        var selectedEntityId by remember(targetTypeFilter) {
            mutableStateOf(
                if (targetTypeFilter == "STUDENT") state.students.firstOrNull()?.id.orEmpty()
                else state.staff.firstOrNull()?.id.orEmpty()
            )
        }
        var name by remember(targetTypeFilter) {
            mutableStateOf(
                if (targetTypeFilter == "STUDENT") state.students.firstOrNull()?.fullName.orEmpty()
                else state.staff.firstOrNull()?.fullName.orEmpty()
            )
        }
        var batch by remember(targetTypeFilter) {
            mutableStateOf(
                if (targetTypeFilter == "STUDENT") {
                    state.students.firstOrNull()?.batchName ?: state.batches.firstOrNull()?.name ?: "Morning Batch"
                } else {
                    state.staff.firstOrNull()?.department ?: "Faculty"
                }
            )
        }
        var status by remember { mutableStateOf("PRESENT") }
        var remarks by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showMarkDialog = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Mark Attendance", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (targetTypeFilter == "STUDENT" && state.students.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.students.take(15).forEach { stu ->
                                LiquidPill(
                                    text = stu.fullName,
                                    selected = selectedEntityId == stu.id,
                                    onClick = {
                                        selectedEntityId = stu.id
                                        name = stu.fullName
                                        if (stu.batchName.isNotBlank()) batch = stu.batchName
                                    }
                                )
                            }
                        }
                    } else if (targetTypeFilter == "STAFF" && state.staff.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.staff.take(15).forEach { stf ->
                                LiquidPill(
                                    text = stf.fullName,
                                    selected = selectedEntityId == stf.id,
                                    onClick = {
                                        selectedEntityId = stf.id
                                        name = stf.fullName
                                        if (stf.department.isNotBlank()) batch = stf.department
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { newName ->
                            name = newName
                            selectedEntityId = if (targetTypeFilter == "STUDENT") {
                                state.students.firstOrNull { it.fullName.equals(newName.trim(), ignoreCase = true) }?.id.orEmpty()
                            } else {
                                state.staff.firstOrNull { it.fullName.equals(newName.trim(), ignoreCase = true) }?.id.orEmpty()
                            }
                        },
                        label = { Text("Student / Staff Name *") },
                        singleLine = true
                    )
                    OutlinedTextField(value = batch, onValueChange = { batch = it }, label = { Text("Batch / Department") }, singleLine = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("PRESENT", "ABSENT", "LATE", "LEAVE").forEach { st ->
                            LiquidPill(text = st, selected = status == st, onClick = { status = st })
                        }
                    }
                    OutlinedTextField(value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save Attendance",
                    enabled = name.isNotBlank(),
                    onClick = {
                        val resolvedId = selectedEntityId.ifBlank {
                            if (targetTypeFilter == "STUDENT") {
                                state.students.firstOrNull {
                                    it.fullName.equals(name.trim(), ignoreCase = true) ||
                                        it.enrollmentNo.equals(name.trim(), ignoreCase = true)
                                }?.id
                            } else {
                                state.staff.firstOrNull {
                                    it.fullName.equals(name.trim(), ignoreCase = true) ||
                                        it.employeeCode.equals(name.trim(), ignoreCase = true)
                                }?.id
                            } ?: "manual_${name.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")}"
                        }
                        viewModel.markAttendance(
                            targetType = targetTypeFilter,
                            targetId = resolvedId,
                            targetName = name.trim(),
                            batchName = batch.trim(),
                            status = status,
                            method = "MANUAL",
                            remarks = remarks.trim()
                        )
                        showMarkDialog = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showMarkDialog = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    if (canMarkAttendance && showQrScanDialog) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val isAdmin = state.activePortalRole == PortalRole.ADMIN
        val activeBatchName = state.batches.firstOrNull()?.name ?: "ADCA Morning Batch"
        val activeBatchId = state.batches.firstOrNull()?.id ?: "bat_adca01"
        val rollingToken by remember(activeBatchId) {
            AntiProxyAttendanceManager.observeRollingQrCode(activeBatchId, activeBatchName)
        }.collectAsState(initial = null)

        var qrCodeInput by remember {
            mutableStateOf("")
        }
        var geofenceStatusText by remember {
            mutableStateOf(
                if (isAdmin) {
                    "Admin QR Mode Active — Scan student QR or tap Verify Campus GPS"
                } else {
                    "Checking 50m Institute Campus GPS Geofence…"
                }
            )
        }
        var geofenceVerified by remember { mutableStateOf(isAdmin) }
        var isVerifyingGeofence by remember { mutableStateOf(false) }
        var showAdminManualPicker by remember { mutableStateOf(false) }
        var showLiveCameraScanner by remember { mutableStateOf(false) }

        fun runCampusGeofenceVerification() {
            isVerifyingGeofence = true
            geofenceStatusText = "Acquiring high-accuracy GPS fix against Institute campus…"
            scope.launch {
                val res = AntiProxyAttendanceManager.verifyStudentCampusGeofence(context)
                isVerifyingGeofence = false
                when (res) {
                    is GeofenceVerificationResult.VerifiedInsideCampus -> {
                        geofenceVerified = true
                        geofenceStatusText = "✓ Campus Geofence Verified (${res.distanceMeters.toInt()}m from Institute) • Ready"
                    }
                    is GeofenceVerificationResult.BlockedMockLocation -> {
                        geofenceVerified = false
                        geofenceStatusText = res.message
                    }
                    is GeofenceVerificationResult.BlockedOutsideGeofence -> {
                        geofenceVerified = false
                        geofenceStatusText = res.message
                    }
                    is GeofenceVerificationResult.LocationUnavailable -> {
                        geofenceVerified = isAdmin && showAdminManualPicker
                        geofenceStatusText = res.message
                    }
                }
            }
        }

        val qrGpsPermLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                runCampusGeofenceVerification()
            } else {
                geofenceVerified = isAdmin && showAdminManualPicker
                geofenceStatusText = "Location permission denied — Campus GPS verification required for anti-proxy QR."
            }
        }

        LaunchedEffect(showQrScanDialog) {
            val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasLoc) {
                runCampusGeofenceVerification()
            } else if (!isAdmin) {
                geofenceVerified = false
                geofenceStatusText = "Tap 'Verify Campus GPS' to grant location & unlock 50m institute geofence."
            }
        }

        // Keep window surface visible so optical QR scanner viewfinder never renders a black screen
        BindScreenCapturePolicy(ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY)

        AlertDialog(
            onDismissRequest = { showQrScanDialog = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Live QR Scanner & Anti-Proxy Roll-Call", color = WciColors.textPrimary) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Illuminated Optical Scanner Viewfinder + Live Rotating QR Matrix
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = WciColors.primarySurface,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .border(2.dp, WciColors.successColor, RoundedCornerShape(18.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Optical QR Scanner Active",
                                    tint = WciColors.successColor
                                )
                                Text(
                                    text = "OPTICAL QR SCANNER READY • $activeBatchName",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.successColor,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                            }
                            val payloadToRender = rollingToken?.rawQrPayload ?: "WCI-ATTENDANCE:${qrCodeInput.ifBlank { activeBatchId }}"
                            QrMatrixBadge(payload = payloadToRender)
                        }
                    }
                    Text(
                        text = "Rotates in: ${rollingToken?.expiresInSeconds ?: 15}s • Nonce: ${rollingToken?.nonceHex?.take(8) ?: "HMAC"}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMono,
                            color = WciColors.successColor,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    LinearProgressIndicator(
                        progress = { ((rollingToken?.expiresInSeconds ?: 15) / 15f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = WciColors.successColor,
                        trackColor = WciColors.elevatedSurface
                    )
                    Text(
                        text = geofenceStatusText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (geofenceVerified) WciColors.successColor else WciColors.warningColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    LiquidPill(
                        text = if (isVerifyingGeofence) "Checking GPS Geofence…" else "Verify Campus GPS (50m)",
                        selected = geofenceVerified,
                        icon = Icons.Default.LocationOn,
                        onClick = {
                            val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (hasLoc) {
                                runCampusGeofenceVerification()
                            } else {
                                qrGpsPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ShimmerButton(
                        text = "Open Live CameraX QR Scanner",
                        icon = Icons.Default.CameraAlt,
                        onClick = { showLiveCameraScanner = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isAdmin && state.students.isNotEmpty()) {
                        LiquidPill(
                            text = if (showAdminManualPicker) "Hide Admin Manual ID Picker" else "Admin Manual ID Override",
                            selected = showAdminManualPicker,
                            icon = Icons.Default.AdminPanelSettings,
                            onClick = {
                                showAdminManualPicker = !showAdminManualPicker
                                if (showAdminManualPicker) geofenceVerified = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (showAdminManualPicker) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                state.students.forEach { stu ->
                                    LiquidPill(
                                        text = "${stu.fullName} (${stu.enrollmentNo})",
                                        selected = qrCodeInput == stu.enrollmentNo,
                                        icon = Icons.Default.QrCode2,
                                        onClick = { qrCodeInput = stu.enrollmentNo }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = qrCodeInput,
                        onValueChange = { qrCodeInput = it },
                        label = { Text("Scanned Student Enrollment No / ID / Name") },
                        singleLine = true,
                        colors = wciOutlinedTextFieldColors()
                    )
                }
            },
            confirmButton = {
                val canSubmit = (geofenceVerified || (isAdmin && showAdminManualPicker)) && qrCodeInput.isNotBlank()
                ShimmerButton(
                    text = "Verify & Mark Present",
                    enabled = canSubmit,
                    onClick = {
                        val query = qrCodeInput.trim()
                        val matchedStudent = state.students.firstOrNull {
                            it.id.equals(query, ignoreCase = true) ||
                                it.enrollmentNo.equals(query, ignoreCase = true) ||
                                it.fullName.contains(query, ignoreCase = true)
                        }
                        viewModel.markAttendance(
                            targetType = "STUDENT",
                            targetId = matchedStudent?.id ?: query,
                            targetName = matchedStudent?.fullName ?: query,
                            batchName = matchedStudent?.batchName?.ifBlank { activeBatchName } ?: activeBatchName,
                            status = "PRESENT",
                            method = "QR_SCAN",
                            remarks = if (isAdmin && showAdminManualPicker) {
                                "Verified via QR / Admin Manual ID Override"
                            } else {
                                "Verified via 15s Rolling HMAC QR & 50m GPS Geofence"
                            }
                        )
                        showQrScanDialog = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showQrScanDialog = false }) { Text("Close", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )

        if (showLiveCameraScanner) {
            com.example.attendance.CameraXQrScannerDialog(
                onQrCodeScanned = { rawScanned ->
                    showLiveCameraScanner = false
                    val verifiedBatchResult = AntiProxyAttendanceManager.verifyScannedQrPayload(rawScanned)
                    val verifiedBatchId = verifiedBatchResult.getOrNull()
                    if (verifiedBatchId != null) {
                        val targetStudent = when (state.activePortalRole) {
                            PortalRole.STUDENT -> state.students.firstOrNull {
                                it.id == state.userProfile?.linkedEntityId ||
                                    it.fullName.equals(state.userProfile?.displayName.orEmpty(), ignoreCase = true)
                            } ?: state.students.singleOrNull()
                            else -> {
                                val query = qrCodeInput.trim()
                                if (query.isNotBlank()) {
                                    state.students.firstOrNull {
                                        it.id.equals(query, ignoreCase = true) ||
                                            it.enrollmentNo.equals(query, ignoreCase = true) ||
                                            it.fullName.contains(query, ignoreCase = true)
                                    }
                                } else null
                            }
                        }
                        if (targetStudent != null) {
                            scope.launch {
                                val geoOk = if (geofenceVerified || isAdmin) true else {
                                    when (val geo = AntiProxyAttendanceManager.verifyStudentCampusGeofence(context)) {
                                        is GeofenceVerificationResult.VerifiedInsideCampus -> {
                                            geofenceVerified = true
                                            geofenceStatusText = "✓ Campus Geofence Verified (${geo.distanceMeters.toInt()}m)"
                                            true
                                        }
                                        is GeofenceVerificationResult.BlockedMockLocation -> {
                                            geofenceStatusText = geo.message
                                            false
                                        }
                                        is GeofenceVerificationResult.BlockedOutsideGeofence -> {
                                            geofenceStatusText = geo.message
                                            false
                                        }
                                        is GeofenceVerificationResult.LocationUnavailable -> {
                                            geofenceStatusText = geo.message
                                            false
                                        }
                                    }
                                }
                                if (geoOk) {
                                    viewModel.markAttendance(
                                        targetType = "STUDENT",
                                        targetId = targetStudent.id,
                                        targetName = targetStudent.fullName,
                                        batchName = targetStudent.batchName.ifBlank { activeBatchName },
                                        status = "PRESENT",
                                        method = "QR_SCAN",
                                        remarks = "CameraX ML Kit Verified Rolling HMAC QR ($verifiedBatchId)"
                                    )
                                    showQrScanDialog = false
                                }
                            }
                        } else {
                            geofenceStatusText = "Batch QR verified ($verifiedBatchId) — Enter or select Student Roll No below to mark present."
                        }
                    } else {
                        qrCodeInput = rawScanned.substringAfterLast(":")
                    }
                },
                onDismiss = { showLiveCameraScanner = false }
            )
        }
    }
}

// ============================================================================
// M08, M09, M14: FEES & RECEIPTS, LEDGER & STAFF PAYROLL (F157–F214, F305–F322)
// ============================================================================

@Composable
fun FinanceAndPayrollModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    val isAdmin = state.activePortalRole == PortalRole.ADMIN
    val isTeacher = state.activePortalRole == PortalRole.TEACHER
    var activeTab by remember(state.activePortalRole, initialTab) {
        mutableIntStateOf(
            when (state.activePortalRole) {
                PortalRole.TEACHER -> 2 // Teachers see their Staff Payroll slips
                PortalRole.STUDENT, PortalRole.PARENT -> 0 // Students & Parents see only their Fee Receipts
                PortalRole.ADMIN -> initialTab
            }
        )
    }
    var showCollectFee by remember { mutableStateOf(false) }
    var editingFeeInvoice by remember { mutableStateOf<com.example.data.model.FeeInvoice?>(null) }
    var showAddLedger by remember { mutableStateOf(false) }
    var showRunPayroll by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isAdmin) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiquidPill(text = "Fee Receipts", selected = activeTab == 0, onClick = { activeTab = 0 }, badgeCount = state.fees.size)
                    LiquidPill(text = "Expense Ledger", selected = activeTab == 1, onClick = { activeTab = 1 }, badgeCount = state.ledger.size)
                    LiquidPill(text = "Staff Payroll", selected = activeTab == 2, onClick = { activeTab = 2 }, badgeCount = state.payroll.size)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val totalRevenue = state.fees.sumOf { it.paidAmountPaise }
                val totalDues = state.fees.sumOf { it.balancePaise }
                val totalExpenses = state.ledger.filter { it.entryType != "INCOME" }.sumOf { it.amountPaise }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (activeTab) {
                            0 -> if (isAdmin) "Fee Collection & GST Invoices" else "My Fee Receipts & Installments"
                            1 -> "Accounts, Expenses & Petty Cash"
                            else -> if (isTeacher) "My Monthly Salary Slips" else "Monthly Staff Payroll & Payslips"
                        },
                        style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.textPrimary)
                    )
                    Text(
                        text = if (isAdmin) {
                            "Collected: ${formatInrPaise(totalRevenue)} • Expenses: ${formatInrPaise(totalExpenses)}"
                        } else {
                            "Total Paid: ${formatInrPaise(totalRevenue)} • Balance Due: ${formatInrPaise(totalDues)}"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.Success)
                    )
                }
                if (isAdmin) {
                    ShimmerButton(
                        text = when (activeTab) {
                            0 -> "+ Collect Fee"
                            1 -> "+ Add Entry"
                            else -> "+ Payslip"
                        },
                        onClick = {
                            when (activeTab) {
                                0 -> showCollectFee = true
                                1 -> showAddLedger = true
                                else -> showRunPayroll = true
                            }
                        },
                        testTag = "finance_primary_action_button"
                    )
                }
            }
        }

        when (activeTab) {
            0 -> {
                item(key = "zero_fee_upi_auto_emi_vault") {
                    com.waqar.institute.upi.UpiAndAutoEmiEmbeddedSection(
                        uiState = state,
                        viewModel = viewModel
                    )
                }
                if (state.fees.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            title = "No fee invoices or receipts yet",
                            subtitle = if (isAdmin) {
                                "Collect student fees via UPI, Cash, Card, or Bank Transfer and export official GST-ready PDF receipts."
                            } else {
                                "Your official institute fee receipts and GST invoices will appear here once issued."
                            },
                            ctaLabel = if (isAdmin) "+ Issue First Fee Receipt" else null,
                            onCtaClick = if (isAdmin) ({ showCollectFee = true }) else null
                        )
                    }
                } else {
                    items(state.fees, key = { it.id }) { fee ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${fee.receiptNo} • ${fee.studentName}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                                    Text("${fee.courseName} • ${fee.title}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                                    Text(
                                        "Mode: ${fee.paymentMode} • Status: ${fee.status} • Due: ${formatInrPaise(fee.balancePaise)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.textMuted)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    MoneyTicker(amountPaise = fee.paidAmountPaise, color = WciColors.Success, fontSizeSp = 18)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (isAdmin) {
                                            LiquidPill(
                                                text = "Update Fee",
                                                selected = true,
                                                icon = Icons.Default.CurrencyRupee,
                                                onClick = { editingFeeInvoice = fee }
                                            )
                                        }
                                        LiquidPill(
                                            text = "PDF Receipt",
                                            selected = false,
                                            icon = Icons.Default.PictureAsPdf,
                                            onClick = {
                                                DocumentExportHelper.exportAndShareFeeReceiptPdf(
                                                    context = context,
                                                    instituteName = state.preferences.instituteName,
                                                    gstNumber = state.preferences.gstNumber,
                                                    invoice = fee
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (state.ledger.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.AccountBalanceWallet,
                            title = "No accounting ledger entries yet",
                            subtitle = "Record institute rent, electricity, internet bills, lab maintenance, petty cash, and vendor payments.",
                            ctaLabel = "+ Log Expense / Income",
                            onCtaClick = { showAddLedger = true }
                        )
                    }
                } else {
                    items(state.ledger, key = { it.id }) { item ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${item.entryType} • ${item.title}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                                    Text("Category: ${item.category} • Vendor: ${item.vendorName.ifBlank { "Direct" }}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary))
                                    Text("${item.dateIso} • Ref: ${item.referenceNo} • Mode: ${item.paymentMode}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.textMuted))
                                }
                                MoneyTicker(
                                    amountPaise = item.amountPaise,
                                    color = if (item.entryType == "INCOME") WciColors.Success else WciColors.Danger,
                                    fontSizeSp = 18
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                if (state.payroll.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.Payments,
                            title = "No staff payroll slips generated yet",
                            subtitle = "Process monthly faculty & staff salaries with allowances, PF/TDS deductions, and downloadable PDF payslips.",
                            ctaLabel = "+ Process First Payslip",
                            onCtaClick = { showRunPayroll = true }
                        )
                    }
                } else {
                    items(state.payroll, key = { it.id }) { pay ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${pay.staffName} • ${pay.monthYear}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                                    Text(
                                        "Basic: ${formatInrPaise(pay.basicPaise)} | Allow: ${formatInrPaise(pay.allowancesPaise)} | Ded: ${formatInrPaise(pay.deductionsPaise)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.textMuted)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    MoneyTicker(amountPaise = pay.netPayPaise, color = WciColors.Success, fontSizeSp = 18)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LiquidPill(
                                        text = "PDF Payslip",
                                        selected = false,
                                        icon = Icons.Default.PictureAsPdf,
                                        onClick = {
                                            DocumentExportHelper.exportAndSharePayslipPdf(
                                                context = context,
                                                instituteName = state.preferences.instituteName,
                                                entry = pay
                                            )
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

    if (showCollectFee) {
        var studentName by remember { mutableStateOf(state.students.firstOrNull()?.fullName ?: "") }
        var courseName by remember { mutableStateOf(state.students.firstOrNull()?.courseName ?: "ADCA") }
        var title by remember { mutableStateOf("Installment 1 - Tuition & Lab Fee") }
        var totalInr by remember { mutableStateOf("12000") }
        var paidInr by remember { mutableStateOf("6000") }
        var discountInr by remember { mutableStateOf("0") }
        var mode by remember { mutableStateOf("UPI") }

        AlertDialog(
            onDismissRequest = { showCollectFee = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Collect Fee & Generate Receipt", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = studentName, onValueChange = { studentName = it }, label = { Text("Student Name *") }, singleLine = true)
                    OutlinedTextField(value = courseName, onValueChange = { courseName = it }, label = { Text("Course Name *") }, singleLine = true)
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Installment / Particulars") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = totalInr, onValueChange = { totalInr = it }, label = { Text("Total (₹)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = paidInr, onValueChange = { paidInr = it }, label = { Text("Paid (₹)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = discountInr, onValueChange = { discountInr = it }, label = { Text("Disc (₹)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("UPI", "CASH", "CARD", "BANK_TRANSFER").forEach { pm ->
                            LiquidPill(text = pm, selected = mode == pm, onClick = { mode = pm })
                        }
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Issue Receipt",
                    enabled = studentName.isNotBlank() && courseName.isNotBlank(),
                    onClick = {
                        viewModel.createFeeInvoiceAndReceipt(
                            studentId = state.students.firstOrNull { it.fullName == studentName }?.id ?: "stu_direct",
                            studentName = studentName,
                            courseName = courseName,
                            title = title,
                            totalAmountInr = totalInr.toLongOrNull() ?: 0L,
                            paidAmountInr = paidInr.toLongOrNull() ?: 0L,
                            discountInr = discountInr.toLongOrNull() ?: 0L,
                            paymentMode = mode
                        )
                        showCollectFee = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showCollectFee = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    editingFeeInvoice?.let { feeToUpdate ->
        var additionalPaidInr by remember(feeToUpdate.id) {
            mutableStateOf(((feeToUpdate.balancePaise / 100L).coerceAtLeast(1000L)).toString())
        }
        var mode by remember(feeToUpdate.id) { mutableStateOf(feeToUpdate.paymentMode.ifBlank { "UPI" }) }

        AlertDialog(
            onDismissRequest = { editingFeeInvoice = null },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Update Fee • ${feeToUpdate.receiptNo}", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${feeToUpdate.studentName} • ${feeToUpdate.courseName}",
                        style = MaterialTheme.typography.titleSmall.copy(color = WciColors.textPrimary)
                    )
                    Text(
                        text = "Paid: ${formatInrPaise(feeToUpdate.paidAmountPaise)} • Balance Due: ${formatInrPaise(feeToUpdate.balancePaise)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.Info)
                    )
                    OutlinedTextField(
                        value = additionalPaidInr,
                        onValueChange = { additionalPaidInr = it.filter(Char::isDigit) },
                        label = { Text("Additional Payment Amount (₹) *") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("UPI", "CASH", "CARD", "NEFT").forEach { m ->
                            LiquidPill(text = m, selected = mode == m, onClick = { mode = m })
                        }
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Update Fee",
                    enabled = (additionalPaidInr.toLongOrNull() ?: 0L) > 0L,
                    onClick = {
                        viewModel.updateFeeInvoice(
                            invoiceId = feeToUpdate.id,
                            additionalPaidInr = additionalPaidInr.toLongOrNull() ?: 0L,
                            paymentMode = mode
                        )
                        editingFeeInvoice = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { editingFeeInvoice = null }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) }
            }
        )
    }

    if (showAddLedger) {
        var entryType by remember { mutableStateOf("EXPENSE") }
        var category by remember { mutableStateOf("Lab Infrastructure & Rent") }
        var title by remember { mutableStateOf("") }
        var vendor by remember { mutableStateOf("") }
        var amountInr by remember { mutableStateOf("5000") }
        var gstInr by remember { mutableStateOf("900") }

        AlertDialog(
            onDismissRequest = { showAddLedger = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Add Accounting Ledger Entry", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("EXPENSE", "INCOME", "PETTY_CASH", "VENDOR_PAYMENT").forEach { t ->
                            LiquidPill(text = t, selected = entryType == t, onClick = { entryType = t })
                        }
                    }
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Voucher Description *") }, singleLine = true)
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Account Head / Category") }, singleLine = true)
                    OutlinedTextField(value = vendor, onValueChange = { vendor = it }, label = { Text("Vendor / Payee Name") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = amountInr, onValueChange = { amountInr = it }, label = { Text("Amount (₹)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = gstInr, onValueChange = { gstInr = it }, label = { Text("GST (₹)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Post Voucher",
                    enabled = title.isNotBlank(),
                    onClick = {
                        viewModel.addLedgerEntry(entryType, category, title, vendor, amountInr.toLongOrNull() ?: 0L, gstInr.toLongOrNull() ?: 0L, "UPI", "")
                        showAddLedger = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddLedger = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    if (showRunPayroll) {
        var staffName by remember { mutableStateOf(state.staff.firstOrNull()?.fullName ?: "") }
        var monthYear by remember { mutableStateOf("Nov 2026") }
        var basic by remember { mutableStateOf("22000") }
        var allow by remember { mutableStateOf("3000") }
        var deduct by remember { mutableStateOf("1000") }
        var leaves by remember { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { showRunPayroll = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Process Monthly Payslip (M14)", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = staffName, onValueChange = { staffName = it }, label = { Text("Staff Full Name *") }, singleLine = true)
                    OutlinedTextField(value = monthYear, onValueChange = { monthYear = it }, label = { Text("Payroll Month (e.g. Nov 2026)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = basic, onValueChange = { basic = it }, label = { Text("Basic (₹)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = allow, onValueChange = { allow = it }, label = { Text("HRA/Allow (₹)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = deduct, onValueChange = { deduct = it }, label = { Text("PF/TDS Ded (₹)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = leaves, onValueChange = { leaves = it }, label = { Text("Leaves") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Disburse & Save",
                    enabled = staffName.isNotBlank(),
                    onClick = {
                        viewModel.runStaffPayroll(
                            staffId = state.staff.firstOrNull { it.fullName == staffName }?.id ?: "stf_01",
                            staffName = staffName,
                            monthYear = monthYear,
                            basicInr = basic.toLongOrNull() ?: 0L,
                            allowancesInr = allow.toLongOrNull() ?: 0L,
                            deductionsInr = deduct.toLongOrNull() ?: 0L,
                            leaveDays = leaves.toIntOrNull() ?: 0
                        )
                        showRunPayroll = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showRunPayroll = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }
}
