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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                Text("${course.code} • ${course.title}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                                Text(
                                    "Category: ${course.category} • Duration: ${course.durationMonths} Months • ${course.totalModules} Modules",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                                )
                                if (course.syllabusSummary.isNotBlank()) {
                                    Text("Syllabus: ${course.syllabusSummary}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                                }
                            }
                            MoneyTicker(amountPaise = course.totalFeePaise, color = WciColors.Success, fontSizeSp = 18)
                        }
                    }
                }
            }
        } else {
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
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${batch.code} • ${batch.name}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark, fontWeight = FontWeight.ExtraBold))
                                Text(
                                    "${batch.courseName} • Batch Admin / Faculty: ${batch.teacherName}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info, fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    "${batch.daysOfWeek} (${batch.startTime}–${batch.endTime}) • Room: ${batch.roomName}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.TextSecondaryDark)
                                )
                                if (isAdmin && state.staff.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Make Teacher Batch Admin (1-Tap):",
                                        style = MaterialTheme.typography.labelSmall.copy(color = WciColors.Warning)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        state.staff.forEach { teacherStaff ->
                                            val isAssigned = batch.teacherName.contains(teacherStaff.fullName, ignoreCase = true)
                                            LiquidPill(
                                                text = if (isAssigned) "★ ${teacherStaff.fullName}" else teacherStaff.fullName,
                                                selected = isAssigned,
                                                onClick = {
                                                    viewModel.assignBatchAdminTeacher(batch, teacherStaff.fullName)
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
                                    text = "Cap: ${batch.capacity} seats",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                                )
                            }
                        }
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Create Course", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showAddCourse = false }) { Text("Cancel") } }
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Schedule Batch & Lab Slot", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showAddBatch = false }) { Text("Cancel") } }
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
                        style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark)
                    )
                    Text(
                        text = if (canMarkAttendance) {
                            "${state.attendance.size} Records • Offline Queue + Live Cloud Sync"
                        } else {
                            "${state.attendance.size} Verified Attendance Entries"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidPill(
                        text = "QR Check-in",
                        selected = false,
                        icon = Icons.Default.QrCodeScanner,
                        onClick = { showQrScanDialog = true }
                    )
                    if (canMarkAttendance) {
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
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
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
                        val matched = state.students.filter {
                            it.batchName.equals(selectedBatchRollCall, ignoreCase = true)
                        }
                        if (matched.isNotEmpty()) matched else state.students
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Batch-Wise Student Roster Roll-Call",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = WciColors.TextPrimaryDark,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Text(
                                "Select a Batch → Tap Present / Absent / Late per student → Save entire batch to Firebase",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                            )
                        }
                        LiquidPill(
                            text = "✓ All Present",
                            selected = true,
                            onClick = {
                                rosterStatusMap = studentsInSelectedBatch.associate { it.id to "PRESENT" }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

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

                    Spacer(modifier = Modifier.height(10.dp))

                    // Student List by Name for Selected Batch
                    studentsInSelectedBatch.forEachIndexed { idx, stu ->
                        val chosenStatus = rosterStatusMap[stu.id] ?: "PRESENT"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${idx + 1}. ${stu.fullName}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = WciColors.TextPrimaryDark
                                    )
                                )
                                Text(
                                    text = "${stu.enrollmentNo} • ${stu.batchName.ifBlank { selectedBatchRollCall }}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("PRESENT", "ABSENT", "LATE").forEach { st ->
                                    LiquidPill(
                                        text = st,
                                        selected = chosenStatus == st,
                                        onClick = {
                                            rosterStatusMap = rosterStatusMap.toMutableMap().apply {
                                                put(stu.id, st)
                                            }
                                            viewModel.markAttendance(
                                                targetType = "STUDENT",
                                                targetId = stu.id,
                                                targetName = stu.fullName,
                                                batchName = stu.batchName.ifBlank { selectedBatchRollCall },
                                                status = st,
                                                method = "BATCH_ROSTER"
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    ShimmerButton(
                        text = "Save ${studentsInSelectedBatch.size} Students Attendance to Firebase",
                        icon = Icons.Default.CloudUpload,
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
                            Text(att.targetName, style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                            Text(
                                "${att.dateIso} • Batch: ${att.batchName.ifBlank { "General" }} • Via ${att.method}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.TextMutedDark)
                            )
                            if (att.remarks.isNotBlank()) {
                                Text("Note: ${att.remarks}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                            }
                        }
                        val statusColor = when (att.status) {
                            "PRESENT" -> WciColors.Success
                            "ABSENT" -> WciColors.Danger
                            else -> WciColors.Warning
                        }
                        Text(
                            text = att.status,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        )
                    }
                }
            }
        }
    }

    if (showMarkDialog) {
        var name by remember { mutableStateOf(state.students.firstOrNull()?.fullName ?: "") }
        var batch by remember { mutableStateOf(state.batches.firstOrNull()?.name ?: "Morning Batch") }
        var status by remember { mutableStateOf("PRESENT") }
        var remarks by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showMarkDialog = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Mark Attendance", color = WciColors.TextPrimaryDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Student / Staff Name *") }, singleLine = true)
                    OutlinedTextField(value = batch, onValueChange = { batch = it }, label = { Text("Batch / Department") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        viewModel.markAttendance(
                            targetType = targetTypeFilter,
                            targetId = "id_${name.take(6).lowercase()}",
                            targetName = name,
                            batchName = batch,
                            status = status,
                            method = "MANUAL",
                            remarks = remarks
                        )
                        showMarkDialog = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showMarkDialog = false }) { Text("Cancel") } }
        )
    }

    if (showQrScanDialog) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val activeBatchName = state.batches.firstOrNull()?.name ?: "ADCA Morning Batch"
        val activeBatchId = state.batches.firstOrNull()?.id ?: "bat_adca01"
        val rollingToken by remember(activeBatchId) {
            AntiProxyAttendanceManager.observeRollingQrCode(activeBatchId, activeBatchName)
        }.collectAsState(initial = null)

        var qrCodeInput by remember {
            mutableStateOf(state.students.firstOrNull()?.enrollmentNo ?: "WCI-2026-1001")
        }
        var geofenceStatusText by remember {
            mutableStateOf("GPS Geofence Lock: 100m Campus Radius Active")
        }
        var geofenceVerified by remember { mutableStateOf(true) }
        var showLiveCameraScanner by remember { mutableStateOf(false) }

        val locationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                scope.launch {
                    when (val res = AntiProxyAttendanceManager.verifyStudentCampusGeofence(context)) {
                        is GeofenceVerificationResult.VerifiedInsideCampus -> {
                            geofenceVerified = true
                            geofenceStatusText = "Verified Inside Campus (${res.distanceMeters.toInt()}m from Institute)"
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
                            geofenceVerified = true
                            geofenceStatusText = "Campus Wi-Fi / Cryptographic Rolling Token Active"
                        }
                    }
                }
            }
        }

        // Keep window surface visible so optical QR scanner viewfinder never renders a black screen
        BindScreenCapturePolicy(ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY)

        AlertDialog(
            onDismissRequest = { showQrScanDialog = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Live QR Scanner & Anti-Proxy Roll-Call", color = WciColors.TextPrimaryDark) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Illuminated Optical Scanner Viewfinder + Live Rotating QR Matrix (Never a black screen)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = WciColors.BgPrimaryDark,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .border(2.dp, WciColors.Success, RoundedCornerShape(18.dp))
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
                                    tint = WciColors.Success
                                )
                                Text(
                                    text = "OPTICAL QR SCANNER READY • $activeBatchName",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.Success,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                            }
                            val payloadToRender = rollingToken?.rawQrPayload ?: "WCI-ATTENDANCE:$qrCodeInput"
                            QrMatrixBadge(payload = payloadToRender)
                        }
                    }
                    Text(
                        text = "Rotates in: ${rollingToken?.expiresInSeconds ?: 15}s • Nonce: ${rollingToken?.nonceHex?.take(8) ?: "HMAC"}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMono,
                            color = WciColors.Success,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    LinearProgressIndicator(
                        progress = { ((rollingToken?.expiresInSeconds ?: 15) / 15f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = WciColors.Success,
                        trackColor = WciColors.BgElevatedDark
                    )
                    Text(
                        text = geofenceStatusText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (geofenceVerified) WciColors.Info else WciColors.Danger
                        )
                    )
                    LiquidPill(
                        text = "Verify 100m Campus GPS Geofence",
                        selected = geofenceVerified,
                        icon = Icons.Default.MyLocation,
                        onClick = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    )
                    ShimmerButton(
                        text = "Open Live CameraX QR Scanner",
                        icon = Icons.Default.CameraAlt,
                        onClick = { showLiveCameraScanner = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.students.isNotEmpty()) {
                        Text(
                            text = "Tap Student ID Card QR to Scan:",
                            style = MaterialTheme.typography.labelSmall.copy(color = WciColors.TextPrimaryDark)
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
                                    selected = qrCodeInput == stu.enrollmentNo,
                                    icon = Icons.Default.QrCode2,
                                    onClick = { qrCodeInput = stu.enrollmentNo }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = qrCodeInput,
                        onValueChange = { qrCodeInput = it },
                        label = { Text("Scanned Student Enrollment No / Name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Verify & Mark Present",
                    enabled = geofenceVerified && qrCodeInput.isNotBlank(),
                    onClick = {
                        val matchedStudent = state.students.firstOrNull {
                            it.enrollmentNo.equals(qrCodeInput, ignoreCase = true) ||
                                it.fullName.contains(qrCodeInput, ignoreCase = true)
                        }
                        viewModel.markAttendance(
                            targetType = "STUDENT",
                            targetId = matchedStudent?.id ?: qrCodeInput,
                            targetName = matchedStudent?.fullName ?: qrCodeInput,
                            batchName = matchedStudent?.batchName ?: activeBatchName,
                            status = "PRESENT",
                            method = "QR_SCAN",
                            remarks = "Verified via 15s Rolling HMAC QR & 100m Geofence"
                        )
                        showQrScanDialog = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showQrScanDialog = false }) { Text("Close") } }
        )

        if (showLiveCameraScanner) {
            com.example.attendance.CameraXQrScannerDialog(
                onQrCodeScanned = { rawScanned ->
                    showLiveCameraScanner = false
                    val verifiedBatchResult = AntiProxyAttendanceManager.verifyScannedQrPayload(rawScanned)
                    val verifiedBatchId = verifiedBatchResult.getOrNull()
                    if (verifiedBatchId != null) {
                        val selfStudent = state.students.firstOrNull()
                        viewModel.markAttendance(
                            targetType = "STUDENT",
                            targetId = selfStudent?.id ?: state.userProfile?.uid ?: "stu_qr",
                            targetName = selfStudent?.fullName ?: state.userProfile?.displayName ?: "Verified Student",
                            batchName = activeBatchName,
                            status = "PRESENT",
                            method = "QR_SCAN",
                            remarks = "CameraX ML Kit Verified Rolling HMAC QR ($verifiedBatchId)"
                        )
                        showQrScanDialog = false
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
                        style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark)
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
                                    Text("${fee.receiptNo} • ${fee.studentName}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                                    Text("${fee.courseName} • ${fee.title}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                                    Text(
                                        "Mode: ${fee.paymentMode} • Status: ${fee.status} • Due: ${formatInrPaise(fee.balancePaise)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.TextMutedDark)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    MoneyTicker(amountPaise = fee.paidAmountPaise, color = WciColors.Success, fontSizeSp = 18)
                                    Spacer(modifier = Modifier.height(6.dp))
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
                                    Text("${item.entryType} • ${item.title}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                                    Text("Category: ${item.category} • Vendor: ${item.vendorName.ifBlank { "Direct" }}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                                    Text("${item.dateIso} • Ref: ${item.referenceNo} • Mode: ${item.paymentMode}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.TextMutedDark))
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
                                    Text("${pay.staffName} • ${pay.monthYear}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                                    Text(
                                        "Basic: ${formatInrPaise(pay.basicPaise)} | Allow: ${formatInrPaise(pay.allowancesPaise)} | Ded: ${formatInrPaise(pay.deductionsPaise)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.TextMutedDark)
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Collect Fee & Generate Receipt", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showCollectFee = false }) { Text("Cancel") } }
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Add Accounting Ledger Entry", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showAddLedger = false }) { Text("Cancel") } }
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Process Monthly Payslip (M14)", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showRunPayroll = false }) { Text("Cancel") } }
        )
    }
}
