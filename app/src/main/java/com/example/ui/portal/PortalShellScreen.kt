package com.example.ui.portal

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PortalRole
import com.example.media.BindScreenCapturePolicy
import com.example.media.ScreenCapturePolicy
import com.example.ota.OtaDynamicManager
import com.example.ui.designsystem.*
import com.example.ui.modules.*
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import com.waqar.institute.biometric.TeacherAttendanceSyncManager
import com.waqar.institute.biometric.TeacherBiometricGateOverlay
import com.waqar.institute.session.ConcurrentSessionManager
import kotlinx.coroutines.launch

data class ModuleDestination(
    val routeId: String,
    val title: String,
    val shortLabel: String,
    val category: String,
    val icon: ImageVector,
    val accentStart: Color = Color(0xFF00E5FF),
    val accentEnd: Color = Color(0xFFA855F7)
)

object PortalModuleDestination {
    const val M13_STAFF = "M13_STAFF"
}

val AllDrawerDestinations = listOf(
    ModuleDestination("M01_DASHBOARD", "Director Dashboard", "Home", "Core", Icons.Default.Dashboard, Color(0xFFA855F7), Color(0xFFEC4899)),
    ModuleDestination("M03_STUDENTS", "Student Directory & 360°", "Students", "People", Icons.Default.School, Color(0xFF00E5FF), Color(0xFF3B82F6)),
    ModuleDestination("M04_ADMISSIONS", "Admissions & Enquiry CRM", "CRM", "People", Icons.Default.SupportAgent, Color(0xFF38BDF8), Color(0xFF6366F1)),
    ModuleDestination("M05_COURSES", "Courses & Curriculum", "Courses", "Academics", Icons.AutoMirrored.Filled.MenuBook, Color(0xFF10F596), Color(0xFF06B6D4)),
    ModuleDestination("M06_BATCHES", "Batches & Lab Timetable", "Batches", "Academics", Icons.Default.Schedule, Color(0xFF14F195), Color(0xFF3B82F6)),
    ModuleDestination("M07_ATTENDANCE", "Attendance & QR Roll-Call", "Attendance", "Academics", Icons.Default.QrCodeScanner, Color(0xFF10F596), Color(0xFF00C6FF)),
    ModuleDestination("M08_FEES", "Fees, Invoices & GST Receipts", "Fees", "Finance", Icons.AutoMirrored.Filled.ReceiptLong, Color(0xFFFF5894), Color(0xFF8B5CF6)),
    ModuleDestination("M09_LEDGER", "Accounts & Expense Ledger", "Ledger", "Finance", Icons.Default.AccountBalanceWallet, Color(0xFFFF6B8B), Color(0xFFFF9F43)),
    ModuleDestination("M10_EXAMS", "Online Exams & CBT Engine", "Exams", "Assessment", Icons.Default.Quiz, Color(0xFF818CF8), Color(0xFFE879F9)),
    ModuleDestination("M11_RESULTS", "Mark Sheets & Rank Cards", "Results", "Assessment", Icons.Default.EmojiEvents, Color(0xFFA855F7), Color(0xFF00E5FF)),
    ModuleDestination("M12_MATERIALS", "Study Material & LMS", "Learn", "Academics", Icons.AutoMirrored.Filled.LibraryBooks, Color(0xFF00E5FF), Color(0xFF10F596)),
    ModuleDestination("M13_CERTIFICATES", "QR Certificates & IDs", "Certificates", "Assessment", Icons.Default.WorkspacePremium, Color(0xFFE879F9), Color(0xFF6366F1)),
    ModuleDestination(PortalModuleDestination.M13_STAFF, "Faculty & Staff Directory", "Faculty HR", "People", Icons.Default.Badge, Color(0xFF00E5FF), Color(0xFF10F596)),
    ModuleDestination("M14_PAYROLL", "Staff HR & Monthly Payroll", "Payroll", "People", Icons.Default.Payments, Color(0xFF10F596), Color(0xFFA855F7)),
    ModuleDestination("M15_PARENTS", "Parent & PTM Records", "PTM Notes", "People", Icons.Default.FamilyRestroom, Color(0xFFFF6B8B), Color(0xFFA855F7)),
    ModuleDestination("M16_ANNOUNCEMENTS", "Notices & Broadcasts", "Inbox", "Comms", Icons.Default.Campaign, Color(0xFF38BDF8), Color(0xFFA855F7)),
    ModuleDestination("M17_LIVE_CLASSES", "Live Classes & Lab Polls", "Live", "Comms", Icons.Default.Videocam, Color(0xFFFF4D79), Color(0xFF8B5CF6)),
    ModuleDestination("M18_AI_HUB", "Waqar Computer Institute AI", "AI Tutor", "AI", Icons.Default.AutoAwesome, Color(0xFFA855F7), Color(0xFF00E5FF)),
    ModuleDestination("M19_REPORTS", "Analytics & MIS Reports", "Reports", "Operations", Icons.Default.Analytics, Color(0xFF00E5FF), Color(0xFF6366F1)),
    ModuleDestination("M20_INVENTORY", "Computer Lab & Library Assets", "Inventory", "Operations", Icons.Default.Computer, Color(0xFF14F195), Color(0xFF00E5FF)),
    ModuleDestination("M21_HELPDESK", "Support Helpdesk & NPS", "Helpdesk", "Operations", Icons.Default.Support, Color(0xFFFF6B8B), Color(0xFF38BDF8)),
    ModuleDestination("M22_SECURITY", "Security, Vault & Audit Logs", "Security", "Security", Icons.Default.Security, Color(0xFFA855F7), Color(0xFF10F596)),
    ModuleDestination("M23_BACKUP", "Cloud Backup & Export", "Backup", "Security", Icons.Default.CloudSync, Color(0xFF00E5FF), Color(0xFFA855F7)),
    ModuleDestination("M24_SETTINGS", "Settings & Language (EN/HI)", "Settings", "Settings", Icons.Default.Settings, Color(0xFF818CF8), Color(0xFF00E5FF))
)

fun allowedDrawerDestinationsForRole(role: PortalRole): List<ModuleDestination> {
    val allowedIds = when (role) {
        PortalRole.ADMIN -> AllDrawerDestinations.map { it.routeId }.toSet()
        PortalRole.TEACHER -> setOf(
            "M01_DASHBOARD",
            "M05_COURSES",
            "M06_BATCHES",
            "M07_ATTENDANCE",
            "M10_EXAMS",
            "M11_RESULTS",
            "M12_MATERIALS",
            "M14_PAYROLL",
            "M16_ANNOUNCEMENTS",
            "M17_LIVE_CLASSES",
            "M18_AI_HUB",
            "M21_HELPDESK",
            "M24_SETTINGS"
        )
        PortalRole.STUDENT -> setOf(
            "M01_DASHBOARD",
            "M03_STUDENTS",
            "M06_BATCHES",
            "M07_ATTENDANCE",
            "M08_FEES",
            "M10_EXAMS",
            "M11_RESULTS",
            "M12_MATERIALS",
            "M13_CERTIFICATES",
            "M16_ANNOUNCEMENTS",
            "M17_LIVE_CLASSES",
            "M18_AI_HUB",
            "M21_HELPDESK",
            "M24_SETTINGS"
        )
        PortalRole.PARENT -> setOf(
            "M01_DASHBOARD",
            "M03_STUDENTS",
            "M07_ATTENDANCE",
            "M08_FEES",
            "M11_RESULTS",
            "M13_CERTIFICATES",
            "M15_PARENTS",
            "M16_ANNOUNCEMENTS",
            "M21_HELPDESK",
            "M24_SETTINGS"
        )
    }
    return AllDrawerDestinations.filter { it.routeId in allowedIds }.map { dest ->
        when {
            role == PortalRole.ADMIN && dest.routeId == "M15_PARENTS" ->
                dest.copy(title = "Manage Parents / Add Parent User", shortLabel = "Parents")
            role == PortalRole.STUDENT && dest.routeId == "M01_DASHBOARD" ->
                dest.copy(title = "Learning Home", shortLabel = "Home")
            role == PortalRole.TEACHER && dest.routeId == "M01_DASHBOARD" ->
                dest.copy(title = "Today's Classes", shortLabel = "Today")
            role == PortalRole.PARENT && dest.routeId == "M01_DASHBOARD" ->
                dest.copy(title = "Child Progress Home", shortLabel = "Child")
            role == PortalRole.STUDENT && dest.routeId == "M03_STUDENTS" ->
                dest.copy(title = "My ID Card & Profile", shortLabel = "My ID")
            role == PortalRole.PARENT && dest.routeId == "M03_STUDENTS" ->
                dest.copy(title = "Child Profile & ID Card", shortLabel = "Child ID")
            role == PortalRole.STUDENT && dest.routeId == "M08_FEES" ->
                dest.copy(title = "My Fees & GST Receipts", shortLabel = "Fees")
            role == PortalRole.PARENT && dest.routeId == "M08_FEES" ->
                dest.copy(title = "Child's Fee & Receipts", shortLabel = "Fees")
            role == PortalRole.STUDENT && dest.routeId == "M11_RESULTS" ->
                dest.copy(title = "Results & Mark Sheets", shortLabel = "Results")
            role == PortalRole.PARENT && dest.routeId == "M11_RESULTS" ->
                dest.copy(title = "Child's Results & Rank", shortLabel = "Results")
            else -> dest
        }
    }
}

private fun bottomTabsForRole(role: PortalRole): List<ModuleDestination> = when (role) {
    PortalRole.ADMIN -> listOf(
        ModuleDestination("M01_DASHBOARD", "Director Dashboard", "Home", "Core", Icons.Default.Home, Color(0xFFA855F7), Color(0xFFEC4899)),
        ModuleDestination("M03_STUDENTS", "Students", "Students", "People", Icons.Default.School, Color(0xFF00E5FF), Color(0xFF3B82F6)),
        ModuleDestination("M07_ATTENDANCE", "Attendance", "Attendance", "Academics", Icons.AutoMirrored.Filled.FactCheck, Color(0xFF10F596), Color(0xFF06B6D4)),
        ModuleDestination("M08_FEES", "Fees", "Fees", "Finance", Icons.Default.CurrencyRupee, Color(0xFFFF5894), Color(0xFF8B5CF6)),
        ModuleDestination("M16_ANNOUNCEMENTS", "Notices & Broadcasts", "Inbox", "Comms", Icons.Default.Campaign, Color(0xFF38BDF8), Color(0xFFA855F7))
    )
    PortalRole.STUDENT -> listOf(
        ModuleDestination("M01_DASHBOARD", "Learning Home", "Home", "Core", Icons.Default.Home, Color(0xFF818CF8), Color(0xFF6366F1)),
        ModuleDestination("M06_BATCHES", "Class Timetable & Batches", "Learn", "Academics", Icons.Default.Schedule, Color(0xFF00E5FF), Color(0xFF10F596)),
        ModuleDestination("M10_EXAMS", "Online Exams", "Exams", "Assessment", Icons.Default.EditNote, Color(0xFF818CF8), Color(0xFFE879F9)),
        ModuleDestination("M11_RESULTS", "Mark Sheets", "Results", "Assessment", Icons.Default.Star, Color(0xFFA855F7), Color(0xFF00E5FF)),
        ModuleDestination("M08_FEES", "My Fees", "Fees", "Finance", Icons.Default.CurrencyRupee, Color(0xFFFF5894), Color(0xFF8B5CF6))
    )
    PortalRole.TEACHER -> listOf(
        ModuleDestination("M01_DASHBOARD", "Today's Classes", "Today", "Core", Icons.Default.Today, Color(0xFF14F195), Color(0xFF00C6FF)),
        ModuleDestination("M06_BATCHES", "Batches", "Batches", "Academics", Icons.Default.Schedule, Color(0xFF00E5FF), Color(0xFF3B82F6)),
        ModuleDestination("M07_ATTENDANCE", "Roll-Call", "Roll-Call", "Academics", Icons.AutoMirrored.Filled.FactCheck, Color(0xFF10F596), Color(0xFF06B6D4)),
        ModuleDestination("M11_RESULTS", "Grades", "Grades", "Assessment", Icons.Default.Star, Color(0xFFA855F7), Color(0xFFE879F9)),
        ModuleDestination("M12_MATERIALS", "Material", "Material", "Academics", Icons.AutoMirrored.Filled.LibraryBooks, Color(0xFF38BDF8), Color(0xFFA855F7))
    )
    PortalRole.PARENT -> listOf(
        ModuleDestination("M01_DASHBOARD", "Child Progress", "Child", "Core", Icons.Default.Face, Color(0xFFFF6B8B), Color(0xFFA855F7)),
        ModuleDestination("M07_ATTENDANCE", "Attendance", "Attendance", "Academics", Icons.AutoMirrored.Filled.FactCheck, Color(0xFF10F596), Color(0xFF06B6D4)),
        ModuleDestination("M08_FEES", "Fees", "Fees", "Finance", Icons.Default.CurrencyRupee, Color(0xFFFF5894), Color(0xFF8B5CF6)),
        ModuleDestination("M11_RESULTS", "Results", "Results", "Assessment", Icons.Default.Star, Color(0xFF818CF8), Color(0xFFE879F9)),
        ModuleDestination("M16_ANNOUNCEMENTS", "Alerts", "Alerts", "Comms", Icons.Default.NotificationsActive, Color(0xFF38BDF8), Color(0xFFA855F7))
    )
}

@Composable
fun PortalShellScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    externalDrawerState: DrawerState? = null
) {
    val activeRole = state.activePortalRole

    LaunchedEffect(activeRole, state.staff.size) {
        if (activeRole == PortalRole.ADMIN && state.staff.isNotEmpty()) {
            // Automatically mark any unverified teachers as ABSENT after the daily 10:30 AM cutoff
            TeacherAttendanceSyncManager.evaluateDailyCutoffAndAutoMarkAbsent(
                teachers = state.staff,
                cutoffHour24 = 10,
                cutoffMinute = 30
            )
        }
    }

    if (activeRole == PortalRole.TEACHER) {
        val teacherUid = state.userProfile?.linkedEntityId?.ifBlank {
            state.userProfile?.uid ?: "teacher"
        } ?: "teacher"
        val teacherName = state.userProfile?.displayName?.ifBlank { "Faculty Member" } ?: "Faculty Member"

        TeacherBiometricGateOverlay(
            teacherUid = teacherUid,
            teacherName = teacherName,
            onSignOutRequested = { viewModel.signOut() }
        ) {
            PortalShellBody(state, viewModel, externalDrawerState)
        }
        return
    }

    PortalShellBody(state, viewModel, externalDrawerState)
}

@Composable
private fun PortalShellBody(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    externalDrawerState: DrawerState? = null
) {
    val activeRole = state.activePortalRole
    val isStudentClay = activeRole == PortalRole.STUDENT
    val drawerDestinations = remember(activeRole) { allowedDrawerDestinationsForRole(activeRole) }
    val bottomTabs = remember(activeRole) { bottomTabsForRole(activeRole) }

    var currentRoute by remember(activeRole) { mutableStateOf("M01_DASHBOARD") }
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    val sessionExpiredMsg by ConcurrentSessionManager.sessionExpiredMessage.collectAsState()

    val activeRoutePalette = remember(currentRoute, activeRole) {
        WciColors.paletteForRoute(currentRoute, activeRole)
    }

    LaunchedEffect(Unit) {
        OtaDynamicManager.initializeAndFetchOtaConfig()
    }

    val capturePolicy = ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY
    BindScreenCapturePolicy(capturePolicy)

    val internalDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerState = externalDrawerState ?: internalDrawerState
    val scope = rememberCoroutineScope()

    val toggleDrawerSmoothly: () -> Unit = {
        scope.launch {
            if (!drawerState.isAnimationRunning) {
                if (drawerState.isClosed) {
                    drawerState.open()
                } else {
                    drawerState.close()
                }
            }
        }
    }

    // Ensure non-default routes or open drawer return smoothly on Back press
    BackHandler(enabled = drawerState.isOpen || currentRoute != "M01_DASHBOARD") {
        if (drawerState.isOpen) {
            scope.launch {
                if (!drawerState.isAnimationRunning) {
                    drawerState.close()
                }
            }
        } else {
            currentRoute = "M01_DASHBOARD"
        }
    }

    val currentDest = remember(currentRoute, drawerDestinations) {
        drawerDestinations.find { it.routeId == currentRoute }
            ?: AllDrawerDestinations.find { it.routeId == currentRoute }
            ?: drawerDestinations.first()
    }

    val groupedDestinations = remember(drawerDestinations) {
        drawerDestinations.groupBy { it.category }
    }

    // Top-bar personalized title & subtitle per PRD mockups
    val topBarHeaderTitle = remember(currentRoute, activeRole, state.userProfile, state.students) {
        if (currentRoute != "M01_DASHBOARD") {
            currentDest.title
        } else {
            when (activeRole) {
                PortalRole.ADMIN -> if (state.preferences.languageCode == "HI") "निदेशक डैशबोर्ड" else "Director Dashboard"
                PortalRole.STUDENT -> {
                    val firstName = (state.userProfile?.displayName ?: "Student").substringBefore(" ")
                    if (state.preferences.languageCode == "HI") "नमस्ते, $firstName!" else "Hi, $firstName!"
                }
                PortalRole.PARENT -> {
                    val childName = state.students.firstOrNull()?.fullName?.substringBefore(" ") ?: "Student"
                    "$childName's Progress"
                }
                PortalRole.TEACHER -> if (state.preferences.languageCode == "HI") "आज की कक्षाएं" else "Today's Classes"
            }
        }
    }

    val topBarSubtitle = remember(activeRole, state.preferences, state.students) {
        when (activeRole) {
            PortalRole.ADMIN -> "${state.preferences.instituteName} • ${state.preferences.academicSession}"
            PortalRole.STUDENT -> {
                val stu = state.students.firstOrNull()
                if (stu != null) "${stu.courseName} • ${stu.batchName} • Roll ${stu.enrollmentNo}"
                else "${state.preferences.instituteName} • ${state.preferences.academicSession}"
            }
            PortalRole.PARENT -> {
                val names = state.students.take(2).joinToString(" & ") { it.fullName.substringBefore(" ") }
                if (names.isNotBlank()) "Parent of $names" else state.preferences.instituteName
            }
            PortalRole.TEACHER -> "${state.preferences.instituteName} • Faculty Console"
        }
    }

    CompositionLocalProvider(
        LocalRolePalette provides activeRoutePalette,
        LocalPortalRole provides activeRole
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // CRITICAL FIX: Disable edge drag interception while closed so tapping the top-left 3-line Menu button never flickers
            gesturesEnabled = drawerState.isOpen,
            scrimColor = Color(0x9904071A),
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .width(310.dp)
                        .fillMaxHeight(),
                    drawerContainerColor = Color(0xF5121B46),
                    drawerContentColor = WciColors.TextPrimaryDark,
                    drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
                ) {
                    // Drawer Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        activeRoutePalette.start.copy(alpha = 0.42f),
                                        activeRoutePalette.end.copy(alpha = 0.34f),
                                        Color(0xFF141D4B)
                                    )
                                )
                            )
                            .padding(horizontal = 20.dp, vertical = 20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(activeRoutePalette.brush, RoundedCornerShape(WciRadius.pill))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${activeRole.label.uppercase()} PORTAL",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMono,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF05060F)
                                        )
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            if (!drawerState.isAnimationRunning) {
                                                drawerState.close()
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Menu",
                                        tint = Color.White
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.preferences.instituteName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Text(
                                text = "${state.userProfile?.displayName ?: activeRole.label} (@${state.userProfile?.username?.ifBlank { activeRole.key } ?: activeRole.key})",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.TextSecondaryDark
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = WciColors.GlassBorderLight)

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        groupedDestinations.forEach { (category, destinations) ->
                            item(key = "cat_$category") {
                                Text(
                                    text = category.uppercase(),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Bold,
                                        color = activeRoutePalette.start,
                                        letterSpacing = 1.2.sp
                                    ),
                                    modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp)
                                )
                            }
                            items(destinations, key = { it.routeId }) { dest ->
                                val selected = currentRoute == dest.routeId
                                val itemBrush = if (selected) {
                                    Brush.linearGradient(
                                        colors = listOf(
                                            dest.accentStart.copy(alpha = 0.38f),
                                            dest.accentEnd.copy(alpha = 0.30f)
                                        )
                                    )
                                } else {
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF1A255C).copy(alpha = 0.60f),
                                            Color(0xFF141D4B).copy(alpha = 0.60f)
                                        )
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(WciRadius.md))
                                        .background(itemBrush, RoundedCornerShape(WciRadius.md))
                                        .border(
                                            width = if (selected) 1.5.dp else 0.8.dp,
                                            color = if (selected) dest.accentStart else Color.White.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(WciRadius.md)
                                        )
                                        .clickable {
                                            currentRoute = dest.routeId
                                            scope.launch {
                                                if (!drawerState.isAnimationRunning) {
                                                    drawerState.close()
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("drawer_item_${dest.routeId}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                brush = Brush.linearGradient(listOf(dest.accentStart, dest.accentEnd)),
                                                shape = RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = dest.icon,
                                            contentDescription = dest.title,
                                            tint = Color(0xFF05060F),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dest.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = Color.White,
                                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(dest.accentStart, CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = WciColors.GlassBorderSubtle)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiquidPill(
                            text = if (state.preferences.languageCode == "HI") "Language: हिंदी" else "Language: English",
                            selected = false,
                            icon = Icons.Default.Translate,
                            onClick = {
                                val next = if (state.preferences.languageCode == "HI") "EN" else "HI"
                                viewModel.setLanguage(next)
                            }
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    if (!drawerState.isAnimationRunning) {
                                        drawerState.close()
                                    }
                                }
                                showSignOutConfirmation = true
                            },
                            modifier = Modifier.testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign Out",
                                tint = WciColors.Danger,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sign Out", color = WciColors.Danger, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        ) {
            NeonWaveBackground(
                palette = activeRoutePalette,
                modifier = Modifier.fillMaxSize()
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        val linkedStudentPhoto = remember(state.students, state.userProfile, activeRole) {
                            if (activeRole == PortalRole.STUDENT || activeRole == PortalRole.PARENT) {
                                state.students.firstOrNull()?.photoUri.orEmpty()
                            } else ""
                        }
                        WciTopBar(
                            title = topBarHeaderTitle,
                            subtitle = topBarSubtitle,
                            activeRole = activeRole,
                            palette = activeRoutePalette,
                            unreadAlertsCount = state.announcements.size,
                            userInitial = (state.userProfile?.displayName ?: activeRole.label).take(1).uppercase(),
                            studentPhotoUri = linkedStudentPhoto,
                            onOpenDrawer = toggleDrawerSmoothly,
                            onOpenInbox = { currentRoute = "M16_ANNOUNCEMENTS" },
                            onRequestSignOut = { showSignOutConfirmation = true }
                        )
                    },
                    bottomBar = {
                        WciBottomBar(
                            tabs = bottomTabs,
                            currentRoute = currentRoute,
                            isClayPortal = isStudentClay,
                            onSelectRoute = { selectedRoute -> currentRoute = selectedRoute }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Status Banner if present
                        state.statusMessage?.let { msg ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(WciRadius.md))
                                    .background(
                                        brush = Brush.linearGradient(
                                            listOf(
                                                activeRoutePalette.start.copy(alpha = 0.35f),
                                                activeRoutePalette.end.copy(alpha = 0.28f)
                                            )
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        activeRoutePalette.start.copy(alpha = 0.7f),
                                        RoundedCornerShape(WciRadius.md)
                                    )
                                    .clickable { viewModel.postMessage(null) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Active Module Content
                        Crossfade(
                            targetState = currentRoute,
                            modifier = Modifier.weight(1f),
                            label = "moduleCrossfade"
                        ) { route ->
                            when (route) {
                                "M01_DASHBOARD" -> AdminDashboardModule(
                                    state = state,
                                    viewModel = viewModel,
                                    onNavigateToModule = { target ->
                                        if (drawerDestinations.any { it.routeId == target } || AllDrawerDestinations.any { it.routeId == target }) {
                                            currentRoute = target
                                        }
                                    }
                                )
                                "M03_STUDENTS" -> StudentsModuleScreen(state = state, viewModel = viewModel)
                                "M04_ADMISSIONS" -> AdmissionsCrmModuleScreen(state = state, viewModel = viewModel)
                                "M05_COURSES" -> CoursesAndBatchesModuleScreen(state = state, viewModel = viewModel, initialTab = 0)
                                "M06_BATCHES" -> CoursesAndBatchesModuleScreen(state = state, viewModel = viewModel, initialTab = 1)
                                "M07_ATTENDANCE" -> AttendanceModuleScreen(state = state, viewModel = viewModel)
                                "M08_FEES" -> FinanceAndPayrollModuleScreen(state = state, viewModel = viewModel, initialTab = 0)
                                "M09_LEDGER" -> FinanceAndPayrollModuleScreen(state = state, viewModel = viewModel, initialTab = 1)
                                "M14_PAYROLL" -> FinanceAndPayrollModuleScreen(state = state, viewModel = viewModel, initialTab = 2)
                                "M10_EXAMS" -> ExamsMaterialsCertificatesModuleScreen(state = state, viewModel = viewModel, initialTab = 0)
                                "M11_RESULTS" -> ExamsMaterialsCertificatesModuleScreen(state = state, viewModel = viewModel, initialTab = 1)
                                "M12_MATERIALS" -> ExamsMaterialsCertificatesModuleScreen(state = state, viewModel = viewModel, initialTab = 2)
                                "M13_CERTIFICATES" -> ExamsMaterialsCertificatesModuleScreen(state = state, viewModel = viewModel, initialTab = 3)
                                PortalModuleDestination.M13_STAFF -> StaffAndGuardiansModuleScreen(state = state, viewModel = viewModel, initialTab = 0)
                                "M15_PARENTS", "M15_GUARDIANS" -> StaffAndGuardiansModuleScreen(state = state, viewModel = viewModel, initialTab = 1)
                                "M16_ANNOUNCEMENTS" -> InboxAndLiveClassesModuleScreen(state = state, viewModel = viewModel, initialTab = 0)
                                "M17_LIVE_CLASSES" -> InboxAndLiveClassesModuleScreen(state = state, viewModel = viewModel, initialTab = 1)
                                "M18_AI_HUB" -> AiHubModuleScreen(state = state, viewModel = viewModel)
                                "M19_REPORTS" -> ReportsInventorySupportModuleScreen(state = state, viewModel = viewModel, initialTab = 0)
                                "M20_INVENTORY" -> ReportsInventorySupportModuleScreen(state = state, viewModel = viewModel, initialTab = 1)
                                "M21_HELPDESK" -> ReportsInventorySupportModuleScreen(state = state, viewModel = viewModel, initialTab = 2)
                                "M22_SECURITY" -> SecurityBackupSettingsModuleScreen(state = state, viewModel = viewModel, initialTab = 0)
                                "M23_BACKUP" -> SecurityBackupSettingsModuleScreen(state = state, viewModel = viewModel, initialTab = 1)
                                "M24_SETTINGS" -> SecurityBackupSettingsModuleScreen(state = state, viewModel = viewModel, initialTab = 2)
                                else -> AdminDashboardModule(
                                    state = state,
                                    viewModel = viewModel,
                                    onNavigateToModule = { target ->
                                        if (drawerDestinations.any { it.routeId == target }) {
                                            currentRoute = target
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSignOutConfirmation) {
            AlertDialog(
                onDismissRequest = { showSignOutConfirmation = false },
                containerColor = WciColors.BgElevatedDark,
                title = {
                    Text(
                        text = if (state.preferences.languageCode == "HI") "लॉग आउट की पुष्टि करें" else "Confirm Sign Out",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        text = if (state.preferences.languageCode == "HI") {
                            "क्या आप वाकई अपने पोर्टल सत्र से लॉग आउट करना चाहते हैं?"
                        } else {
                            "Are you sure you want to sign out of your ${activeRole.label} Portal session?"
                        },
                        color = WciColors.TextSecondaryDark
                    )
                },
                confirmButton = {
                    ShimmerButton(
                        text = if (state.preferences.languageCode == "HI") "लॉग आउट करें" else "Sign Out",
                        onClick = {
                            showSignOutConfirmation = false
                            viewModel.signOut()
                        }
                    )
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutConfirmation = false }) {
                        Text(if (state.preferences.languageCode == "HI") "रद्द करें" else "Cancel")
                    }
                }
            )
        }

        sessionExpiredMsg?.let { expiredText ->
            AlertDialog(
                onDismissRequest = { ConcurrentSessionManager.clearSessionExpiredAlert() },
                containerColor = WciColors.BgElevatedDark,
                title = {
                    Text(
                        text = "Single-Device Security Alert",
                        color = WciColors.Danger,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        text = expiredText,
                        color = Color.White
                    )
                },
                confirmButton = {
                    ShimmerButton(
                        text = "OK",
                        onClick = { ConcurrentSessionManager.clearSessionExpiredAlert() }
                    )
                }
            )
        }
    }
}

@Composable
private fun WciTopBar(
    title: String,
    subtitle: String,
    activeRole: PortalRole,
    palette: RolePalette,
    unreadAlertsCount: Int,
    userInitial: String,
    studentPhotoUri: String = "",
    onOpenDrawer: () -> Unit,
    onOpenInbox: () -> Unit,
    onRequestSignOut: () -> Unit
) {
    val isStudentClay = activeRole == PortalRole.STUDENT
    val topBarBrush = when (activeRole) {
        PortalRole.STUDENT -> Brush.linearGradient(
            colors = listOf(
                Color(0xFF28377E).copy(alpha = 0.96f),
                palette.start.copy(alpha = 0.34f),
                Color(0xFF1F2B68).copy(alpha = 0.96f)
            )
        )
        PortalRole.TEACHER -> Brush.linearGradient(
            colors = listOf(
                Color(0xFF123244).copy(alpha = 0.88f),
                Color(0xFF10F596).copy(alpha = 0.24f),
                Color(0xFF0E2536).copy(alpha = 0.90f)
            )
        )
        PortalRole.PARENT -> Brush.linearGradient(
            colors = listOf(
                Color(0xFF2B183D).copy(alpha = 0.88f),
                Color(0xFFFF6B8B).copy(alpha = 0.28f),
                Color(0xFF1E122E).copy(alpha = 0.90f)
            )
        )
        PortalRole.ADMIN -> Brush.linearGradient(
            colors = listOf(
                Color(0xFF141D4E).copy(alpha = 0.88f),
                palette.start.copy(alpha = 0.25f),
                palette.end.copy(alpha = 0.20f),
                Color(0xFF101842).copy(alpha = 0.90f)
            )
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(
                elevation = if (isStudentClay) 16.dp else 10.dp,
                shape = RoundedCornerShape(WciRadius.xl),
                ambientColor = palette.start.copy(alpha = 0.45f)
            )
            .clip(RoundedCornerShape(WciRadius.xl))
            .background(topBarBrush, RoundedCornerShape(WciRadius.xl))
            .border(
                width = if (isStudentClay) 2.dp else 1.2.dp,
                brush = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.55f), palette.start.copy(alpha = 0.65f))
                ),
                shape = RoundedCornerShape(WciRadius.xl)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Smooth 48dp 3-Lines Navigation Menu Button (Zero Click-Flicker)
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.brush, RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .testTag("open_drawer_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open Navigation Drawer",
                    tint = Color(0xFF05060F),
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = WciColors.TextSecondaryDark,
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Bell Icon -> Opens Inbox / Alerts
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                    .clickable(onClick = onOpenInbox),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsNone,
                    contentDescription = "Notifications & Inbox",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                if (unreadAlertsCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(8.dp)
                            .background( Color(0xFFFF4D79), CircleShape)
                    )
                }
            }

            // User Avatar / Sign Out Pill (with Student Profile Photo sync & Confirmation)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(palette.brush, CircleShape)
                    .border(1.4.dp, Color.White.copy(alpha = 0.75f), CircleShape)
                    .clickable(onClick = onRequestSignOut)
                    .testTag("topbar_sign_out_button"),
                contentAlignment = Alignment.Center
            ) {
                if (studentPhotoUri.isNotBlank()) {
                    StudentAvatarThumbnail(
                        photoUri = studentPhotoUri,
                        fullName = userInitial,
                        palette = palette,
                        size = 42.dp
                    )
                } else {
                    Text(
                        text = userInitial,
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = Color(0xFF05060F),
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun WciBottomBar(
    tabs: List<ModuleDestination>,
    currentRoute: String,
    isClayPortal: Boolean,
    onSelectRoute: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val barShape = RoundedCornerShape(WciRadius.xl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(
                elevation = if (isClayPortal) 18.dp else 12.dp,
                shape = barShape,
                ambientColor = Color(0xFFA855F7).copy(alpha = 0.45f)
            )
            .clip(barShape)
            .background(
                brush = Brush.linearGradient(
                    colors = if (isClayPortal) {
                        listOf(
                            Color(0xFF25347A).copy(alpha = 0.96f),
                            Color(0xFF1F2B68).copy(alpha = 0.96f)
                        )
                    } else {
                        listOf(
                            Color(0xFF131C4E).copy(alpha = 0.92f),
                            Color(0xFF1D2869).copy(alpha = 0.88f),
                            Color(0xFF111844).copy(alpha = 0.92f)
                        )
                    }
                ),
                shape = barShape
            )
            .border(
                width = if (isClayPortal) 2.dp else 1.3.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.52f),
                        Color(0xFF00E5FF).copy(alpha = 0.55f),
                        Color(0xFFA855F7).copy(alpha = 0.55f)
                    )
                ),
                shape = barShape
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.routeId
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()

            val animatedScale by animateFloatAsState(
                targetValue = when {
                    isPressed -> 0.88f
                    selected -> 1.08f
                    else -> 1f
                },
                animationSpec = WciMotion.bouncySpring(),
                label = "bottomTabScale_${tab.routeId}"
            )

            val labelColor by animateColorAsState(
                targetValue = if (selected) Color.White else WciColors.TextSecondaryDark,
                animationSpec = WciMotion.fastTween(),
                label = "bottomTabLabelColor_${tab.routeId}"
            )

            val tabBrush = if (selected) {
                Brush.linearGradient(
                    colors = listOf(
                        tab.accentStart.copy(alpha = 0.45f),
                        tab.accentEnd.copy(alpha = 0.38f)
                    )
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.Transparent)
                )
            }

            Column(
                modifier = Modifier
                    .scale(animatedScale)
                    .clip(RoundedCornerShape(WciRadius.lg))
                    .background(tabBrush, RoundedCornerShape(WciRadius.lg))
                    .then(
                        if (selected) {
                            Modifier.border(
                                width = 1.4.dp,
                                brush = Brush.linearGradient(listOf(tab.accentStart, tab.accentEnd)),
                                shape = RoundedCornerShape(WciRadius.lg)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectRoute(tab.routeId)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("bottom_tab_${tab.routeId}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(if (selected) 30.dp else 26.dp)
                        .background(
                            brush = if (selected) {
                                Brush.linearGradient(listOf(tab.accentStart, tab.accentEnd))
                            } else {
                                Brush.linearGradient(
                                    listOf(
                                        tab.accentStart.copy(alpha = 0.20f),
                                        tab.accentEnd.copy(alpha = 0.14f)
                                    )
                                )
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.shortLabel,
                        tint = if (selected) Color(0xFF05060F) else tab.accentStart,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Text(
                    text = tab.shortLabel,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = labelColor
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
