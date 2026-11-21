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
import com.waqar.institute.security.BindPortalWindowSecurity
import com.waqar.institute.security.WindowSecureManager
import com.waqar.institute.session.ConcurrentSessionManager
import kotlinx.coroutines.launch

data class ModuleDestination(
    val routeId: String,
    val title: String,
    val shortLabel: String,
    val category: String,
    val icon: ImageVector,
    val accentStart: Color = WciColors.Info,
    val accentEnd: Color = WciColors.PurpleVibrant
)

object PortalModuleDestination {
    const val M13_STAFF = "M13_STAFF"
}

val AllDrawerDestinations = listOf(
    ModuleDestination("M01_DASHBOARD", "Director Dashboard", "Home", "Core", Icons.Default.Dashboard, WciColors.PurpleVibrant, WciColors.ScannerPink),
    ModuleDestination("M03_STUDENTS", "Student Directory & 360°", "Students", "People", Icons.Default.School, WciColors.Info, WciColors.BlueAccent),
    ModuleDestination("M04_ADMISSIONS", "Admissions & Enquiry CRM", "CRM", "People", Icons.Default.SupportAgent, WciColors.Warning, WciColors.IndigoDeep),
    ModuleDestination("M05_COURSES", "Courses & Curriculum", "Courses", "Academics", Icons.AutoMirrored.Filled.MenuBook, WciColors.SuccessNeon, WciColors.ScannerTeal),
    ModuleDestination("M06_BATCHES", "Batches & Lab Timetable", "Batches", "Academics", Icons.Default.Schedule, WciColors.Success, WciColors.BlueAccent),
    ModuleDestination("M07_ATTENDANCE", "Attendance & QR Roll-Call", "Attendance", "Academics", Icons.Default.QrCodeScanner, WciColors.SuccessNeon, WciColors.CyanBright),
    ModuleDestination("M08_FEES", "Fees, Invoices & GST Receipts", "Fees", "Finance", Icons.AutoMirrored.Filled.ReceiptLong, WciColors.NeonMagenta, WciColors.VioletAccent),
    ModuleDestination("M09_LEDGER", "Accounts & Expense Ledger", "Ledger", "Finance", Icons.Default.AccountBalanceWallet, WciColors.NeonPinkCoral, WciColors.AmberHighlight),
    ModuleDestination("M10_EXAMS", "Online Exams & CBT Engine", "Exams", "Assessment", Icons.Default.Quiz, WciColors.IndigoAccent, WciColors.NeonOrchid),
    ModuleDestination("M11_RESULTS", "Mark Sheets & Rank Cards", "Results", "Assessment", Icons.Default.EmojiEvents, WciColors.PurpleVibrant, WciColors.Info),
    ModuleDestination("M12_MATERIALS", "Study Material & LMS", "Learn", "Academics", Icons.AutoMirrored.Filled.LibraryBooks, WciColors.Info, WciColors.SuccessNeon),
    ModuleDestination("M13_CERTIFICATES", "QR Certificates & IDs", "Certificates", "Assessment", Icons.Default.WorkspacePremium, WciColors.NeonOrchid, WciColors.IndigoDeep),
    ModuleDestination(PortalModuleDestination.M13_STAFF, "Faculty & Staff Directory", "Faculty HR", "People", Icons.Default.Badge, WciColors.Info, WciColors.SuccessNeon),
    ModuleDestination("M14_PAYROLL", "Staff HR & Monthly Payroll", "Payroll", "People", Icons.Default.Payments, WciColors.SuccessNeon, WciColors.PurpleVibrant),
    ModuleDestination("M15_PARENTS", "Parent & PTM Records", "PTM Notes", "People", Icons.Default.FamilyRestroom, WciColors.NeonPinkCoral, WciColors.PurpleVibrant),
    ModuleDestination("M16_ANNOUNCEMENTS", "Notices & Broadcasts", "Inbox", "Comms", Icons.Default.Campaign, WciColors.Warning, WciColors.PurpleVibrant),
    ModuleDestination("M17_LIVE_CLASSES", "Live Classes & Lab Polls", "Live", "Comms", Icons.Default.Videocam, WciColors.Danger, WciColors.VioletAccent),
    ModuleDestination("M18_AI_HUB", "Waqar Computer Institute AI", "AI Tutor", "AI", Icons.Default.AutoAwesome, WciColors.PurpleVibrant, WciColors.Info),
    ModuleDestination("M19_REPORTS", "Analytics & MIS Reports", "Reports", "Operations", Icons.Default.Analytics, WciColors.Info, WciColors.IndigoDeep),
    ModuleDestination("M20_INVENTORY", "Computer Lab & Library Assets", "Inventory", "Operations", Icons.Default.Computer, WciColors.Success, WciColors.Info),
    ModuleDestination("M21_HELPDESK", "Support Helpdesk & NPS", "Helpdesk", "Operations", Icons.Default.Support, WciColors.NeonPinkCoral, WciColors.Warning),
    ModuleDestination("M22_SECURITY", "Security, Vault & Audit Logs", "Security", "Security", Icons.Default.Security, WciColors.PurpleVibrant, WciColors.SuccessNeon),
    ModuleDestination("M23_BACKUP", "Cloud Backup & Export", "Backup", "Security", Icons.Default.CloudSync, WciColors.Info, WciColors.PurpleVibrant),
    ModuleDestination("M24_SETTINGS", "Institute & Portal Settings", "Settings", "Settings", Icons.Default.Settings, WciColors.IndigoAccent, WciColors.Info),
    ModuleDestination(
        com.waqar.institute.biometric.AdminFaceConversionAndGateManager.ROUTE_ADMIN_CONVERSION,
        "Admin Conversion & Face Vault",
        "Admin Conv",
        "Security",
        Icons.Default.AdminPanelSettings,
        WciColors.SuccessNeon,
        WciColors.Info
    ),
    com.waqar.institute.navigation.AppDrawer.codingSandboxDestination,
    com.waqar.institute.navigation.AppDrawer.academicProSuiteDestination,
    com.waqar.institute.navigation.AppDrawer.upiAutoEmiVaultDestination,
    com.waqar.institute.navigation.AppDrawer.tallyPracticeSimulatorDestination,
    com.waqar.institute.navigation.AppDrawer.adaptiveLearningDestination,
    // Strictly LAST item in Side Navigation Drawer across all 4 portals
    com.waqar.institute.navigation.AppDrawer.termsAndConditionsDestination
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
            "M24_SETTINGS",
            com.waqar.institute.navigation.AppDrawer.ROUTE_CODING_SANDBOX,
            com.waqar.institute.navigation.AppDrawer.ROUTE_ACADEMIC_PRO_SUITE,
            com.waqar.institute.navigation.AppDrawer.ROUTE_TALLY_SIMULATOR,
            com.waqar.institute.navigation.AppDrawer.ROUTE_ADAPTIVE_LEARNING,
            com.waqar.institute.navigation.AppDrawer.ROUTE_TERMS_AND_CONDITIONS
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
            "M24_SETTINGS",
            com.waqar.institute.navigation.AppDrawer.ROUTE_CODING_SANDBOX,
            com.waqar.institute.navigation.AppDrawer.ROUTE_ACADEMIC_PRO_SUITE,
            com.waqar.institute.navigation.AppDrawer.ROUTE_UPI_EMI_COMMAND,
            com.waqar.institute.navigation.AppDrawer.ROUTE_TALLY_SIMULATOR,
            com.waqar.institute.navigation.AppDrawer.ROUTE_ADAPTIVE_LEARNING,
            com.waqar.institute.navigation.AppDrawer.ROUTE_TERMS_AND_CONDITIONS
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
            "M24_SETTINGS",
            com.waqar.institute.navigation.AppDrawer.ROUTE_CODING_SANDBOX,
            com.waqar.institute.navigation.AppDrawer.ROUTE_ACADEMIC_PRO_SUITE,
            com.waqar.institute.navigation.AppDrawer.ROUTE_UPI_EMI_COMMAND,
            com.waqar.institute.navigation.AppDrawer.ROUTE_TALLY_SIMULATOR,
            com.waqar.institute.navigation.AppDrawer.ROUTE_ADAPTIVE_LEARNING,
            com.waqar.institute.navigation.AppDrawer.ROUTE_TERMS_AND_CONDITIONS
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
        ModuleDestination("M01_DASHBOARD", "Director Dashboard", "Home", "Core", Icons.Default.Home, WciColors.PurpleVibrant, WciColors.ScannerPink),
        ModuleDestination("M03_STUDENTS", "Students", "Students", "People", Icons.Default.School, WciColors.Info, WciColors.BlueAccent),
        ModuleDestination("M07_ATTENDANCE", "Attendance", "Attendance", "Academics", Icons.AutoMirrored.Filled.FactCheck, WciColors.SuccessNeon, WciColors.ScannerTeal),
        ModuleDestination("M08_FEES", "Fees", "Fees", "Finance", Icons.Default.CurrencyRupee, WciColors.NeonMagenta, WciColors.VioletAccent),
        ModuleDestination("M16_ANNOUNCEMENTS", "Notices & Broadcasts", "Inbox", "Comms", Icons.Default.Campaign, WciColors.Warning, WciColors.PurpleVibrant)
    )
    PortalRole.STUDENT -> listOf(
        ModuleDestination("M01_DASHBOARD", "Learning Home", "Home", "Core", Icons.Default.Home, WciColors.IndigoAccent, WciColors.IndigoDeep),
        ModuleDestination("M06_BATCHES", "Class Timetable & Batches", "Learn", "Academics", Icons.Default.Schedule, WciColors.Info, WciColors.SuccessNeon),
        ModuleDestination("M10_EXAMS", "Online Exams", "Exams", "Assessment", Icons.Default.EditNote, WciColors.IndigoAccent, WciColors.NeonOrchid),
        ModuleDestination("M11_RESULTS", "Mark Sheets", "Results", "Assessment", Icons.Default.Star, WciColors.PurpleVibrant, WciColors.Info),
        ModuleDestination("M08_FEES", "My Fees", "Fees", "Finance", Icons.Default.CurrencyRupee, WciColors.NeonMagenta, WciColors.VioletAccent)
    )
    PortalRole.TEACHER -> listOf(
        ModuleDestination("M01_DASHBOARD", "Today's Classes", "Today", "Core", Icons.Default.Today, WciColors.Success, WciColors.CyanBright),
        ModuleDestination("M06_BATCHES", "Batches", "Batches", "Academics", Icons.Default.Schedule, WciColors.Info, WciColors.BlueAccent),
        ModuleDestination("M07_ATTENDANCE", "Roll-Call", "Roll-Call", "Academics", Icons.AutoMirrored.Filled.FactCheck, WciColors.SuccessNeon, WciColors.ScannerTeal),
        ModuleDestination("M11_RESULTS", "Grades", "Grades", "Assessment", Icons.Default.Star, WciColors.PurpleVibrant, WciColors.NeonOrchid),
        ModuleDestination("M12_MATERIALS", "Material", "Material", "Academics", Icons.AutoMirrored.Filled.LibraryBooks, WciColors.Warning, WciColors.PurpleVibrant)
    )
    PortalRole.PARENT -> listOf(
        ModuleDestination("M01_DASHBOARD", "Child Progress", "Child", "Core", Icons.Default.Face, WciColors.NeonPinkCoral, WciColors.PurpleVibrant),
        ModuleDestination("M07_ATTENDANCE", "Attendance", "Attendance", "Academics", Icons.AutoMirrored.Filled.FactCheck, WciColors.SuccessNeon, WciColors.ScannerTeal),
        ModuleDestination("M08_FEES", "Fees", "Fees", "Finance", Icons.Default.CurrencyRupee, WciColors.NeonMagenta, WciColors.VioletAccent),
        ModuleDestination("M11_RESULTS", "Results", "Results", "Assessment", Icons.Default.Star, WciColors.IndigoAccent, WciColors.NeonOrchid),
        ModuleDestination("M16_ANNOUNCEMENTS", "Alerts", "Alerts", "Comms", Icons.Default.NotificationsActive, WciColors.Warning, WciColors.PurpleVibrant)
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

    if (activeRole == PortalRole.ADMIN) {
        val adminUsername = state.userProfile?.username?.trim()?.ifBlank { "waqar" } ?: "waqar"
        val adminDisplayName = state.userProfile?.displayName?.ifBlank { "Waqar Khan (Admin)" } ?: "Waqar Khan (Admin)"

        com.waqar.institute.biometric.AdminBiometricGateOverlay(
            adminUsername = adminUsername,
            adminDisplayName = adminDisplayName,
            onSignOutRequested = { viewModel.signOut() }
        ) {
            PortalShellBody(state, viewModel, externalDrawerState)
        }
        return
    }

    if (activeRole == PortalRole.TEACHER) {
        val teacherUid = state.userProfile?.linkedEntityId?.trim()?.ifBlank {
            state.userProfile?.uid?.trim().orEmpty()
        } ?: ""
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortalShellBody(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    externalDrawerState: DrawerState? = null
) {
    val activeRole = state.activePortalRole
    val isDark = LocalIsDarkTheme.current
    val isStudentClay = activeRole == PortalRole.STUDENT
    val drawerDestinations = remember(activeRole) { allowedDrawerDestinationsForRole(activeRole) }
    val bottomTabs = remember(activeRole) { bottomTabsForRole(activeRole) }

    var currentRoute by remember(activeRole) { mutableStateOf("M01_DASHBOARD") }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    val sessionExpiredMsg by ConcurrentSessionManager.sessionExpiredMessage.collectAsState()

    LaunchedEffect(activeRole, currentRoute, state.userProfile?.uid) {
        runCatching {
            com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator.verifyPortalRouteAndAction(
                userId = state.userProfile?.uid ?: state.userProfile?.username.orEmpty(),
                activeRole = activeRole.key,
                routeId = currentRoute
            )
        }
    }

    val activeRoutePalette = remember(currentRoute, activeRole) {
        WciColors.paletteForRoute(currentRoute, activeRole)
    }

    LaunchedEffect(Unit) {
        OtaDynamicManager.initializeAndFetchOtaConfig()
    }

    BindPortalWindowSecurity(
        portalRole = activeRole.key,
        isAuthenticated = true,
        allowAdminSecureOverride = state.preferences.flagSecureEnabled
    )
    val capturePolicy = if (
        WindowSecureManager.isPortalProtected(
            portalRole = activeRole.key,
            isAuthenticated = true,
            allowAdminSecureOverride = state.preferences.flagSecureEnabled
        )
    ) {
        ScreenCapturePolicy.LOCKED_SENSITIVE
    } else {
        ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY
    }
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
                PortalRole.ADMIN -> "Director Dashboard"
                PortalRole.STUDENT -> {
                    val firstName = (state.userProfile?.displayName ?: "Student").substringBefore(" ")
                    "Hi, $firstName!"
                }
                PortalRole.PARENT -> {
                    val childName = state.students.firstOrNull()?.fullName?.substringBefore(" ") ?: "Student"
                    "$childName's Progress"
                }
                PortalRole.TEACHER -> "Today's Classes"
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
            scrimColor = WciColors.DrawerScrimDark,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .width(310.dp)
                        .fillMaxHeight(),
                    drawerContainerColor = if (isDark) WciColors.DrawerContainerDark else WciColors.DrawerContainerLight,
                    drawerContentColor = WciColors.textPrimary,
                    drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
                ) {
                    // Drawer Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = if (isDark) {
                                        listOf(
                                            activeRoutePalette.start.copy(alpha = 0.42f),
                                            activeRoutePalette.end.copy(alpha = 0.34f),
                                            WciColors.DrawerHeaderDeepDark
                                        )
                                    } else {
                                        listOf(
                                            activeRoutePalette.start.copy(alpha = 0.18f),
                                            activeRoutePalette.end.copy(alpha = 0.14f),
                                            WciColors.BgPrimaryLight
                                        )
                                    }
                                )
                            )
                            .statusBarsPadding()
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
                                            color = WciColors.OnAccentDark
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
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Menu",
                                        tint = WciColors.textPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.preferences.instituteName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = WciColors.textPrimary,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Text(
                                text = "${state.userProfile?.displayName ?: activeRole.label} (@${state.userProfile?.username?.ifBlank { activeRole.key } ?: activeRole.key})",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.textSecondary
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = WciColors.glassBorderSubtleColor)

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
                                val itemBrush = when {
                                    selected -> Brush.linearGradient(
                                        colors = listOf(
                                            dest.accentStart.copy(alpha = if (isDark) 0.38f else 0.22f),
                                            dest.accentEnd.copy(alpha = if (isDark) 0.30f else 0.16f)
                                        )
                                    )
                                    isDark -> Brush.linearGradient(
                                        colors = listOf(
                                            WciColors.DrawerItemDarkStart.copy(alpha = 0.60f),
                                            WciColors.DrawerHeaderDeepDark.copy(alpha = 0.60f)
                                        )
                                    )
                                    else -> Brush.linearGradient(
                                        colors = listOf(
                                            WciColors.SurfaceOffWhite,
                                            WciColors.BgPrimaryLight
                                        )
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(WciRadius.md))
                                        .background(itemBrush, RoundedCornerShape(WciRadius.md))
                                        .border(
                                            width = if (selected) 1.5.dp else 0.8.dp,
                                            color = if (selected) dest.accentStart else WciColors.glassBorderSubtleColor,
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
                                            tint = WciColors.OnAccentDark,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dest.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = WciColors.textPrimary,
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

                    HorizontalDivider(color = WciColors.glassBorderSubtleColor)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiquidPill(
                            text = "Language: English",
                            selected = true,
                            icon = Icons.Default.Translate,
                            onClick = {
                                viewModel.setLanguage("EN")
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign Out",
                                tint = WciColors.dangerColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sign Out", color = WciColors.dangerColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        ) {
            NeonWaveBackground(
                palette = activeRoutePalette,
                modifier = Modifier.fillMaxSize()
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isCompactWidth = maxWidth < 600.dp
                    val useNavigationRail = !isCompactWidth

                    Row(modifier = Modifier.fillMaxSize()) {
                        if (useNavigationRail) {
                            WciNavigationRail(
                                tabs = bottomTabs,
                                currentRoute = currentRoute,
                                palette = activeRoutePalette,
                                onOpenDrawer = toggleDrawerSmoothly,
                                onSelectRoute = { selectedRoute -> currentRoute = selectedRoute }
                            )
                        }

                        Scaffold(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
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
                                    onOpenProfileMenu = { showProfileSheet = true }
                                )
                            },
                            bottomBar = {
                                if (!useNavigationRail) {
                                    WciBottomBar(
                                        tabs = bottomTabs,
                                        currentRoute = currentRoute,
                                        isClayPortal = isStudentClay,
                                        onSelectRoute = { selectedRoute -> currentRoute = selectedRoute }
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .widthIn(max = 960.dp)
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
                                            color = WciColors.textPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = WciColors.textPrimary,
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
                                com.waqar.institute.biometric.AdminFaceConversionAndGateManager.ROUTE_ADMIN_CONVERSION -> {
                                    com.waqar.institute.biometric.AdminConversionModuleScreen(
                                        state = state,
                                        viewModel = viewModel
                                    )
                                }
                                com.waqar.institute.navigation.AppDrawer.ROUTE_CODING_SANDBOX -> {
                                    com.waqar.institute.ui.termux.CodingSandboxScreen(
                                        activeUserId = state.userProfile?.linkedEntityId?.ifBlank { state.userProfile.uid }
                                            ?: state.userProfile?.uid.orEmpty(),
                                        activeUsername = state.userProfile?.username.orEmpty(),
                                        activePortalRole = state.activePortalRole.key,
                                        palette = activeRoutePalette
                                    )
                                }
                                com.waqar.institute.navigation.AppDrawer.ROUTE_ACADEMIC_PRO_SUITE -> {
                                    com.waqar.institute.academic.AcademicProSuiteScreen(
                                        state = state,
                                        viewModel = viewModel
                                    )
                                }
                                com.waqar.institute.navigation.AppDrawer.ROUTE_UPI_EMI_COMMAND -> {
                                    com.waqar.institute.upi.UpiAndAutoEmiVaultScreen(
                                        uiState = state,
                                        viewModel = viewModel
                                    )
                                }
                                com.waqar.institute.navigation.AppDrawer.ROUTE_TALLY_SIMULATOR -> {
                                    com.waqar.institute.tally.TallyPracticeSimulatorScreen()
                                }
                                com.waqar.institute.navigation.AppDrawer.ROUTE_ADAPTIVE_LEARNING -> {
                                    com.waqar.institute.adaptive.AdaptiveLearningDashboardScreen(
                                        state = state,
                                        viewModel = viewModel,
                                        onNavigateToModule = { target ->
                                            if (drawerDestinations.any { it.routeId == target } || AllDrawerDestinations.any { it.routeId == target }) {
                                                currentRoute = target
                                            }
                                        }
                                    )
                                }
                                com.waqar.institute.navigation.AppDrawer.ROUTE_TERMS_AND_CONDITIONS -> {
                                    com.waqar.institute.ui.legal.TermsAndConditionsScreen()
                                }
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
            }
        }
        }

        if (showProfileSheet) {
            ModalBottomSheet(
                onDismissRequest = { showProfileSheet = false },
                containerColor = WciColors.elevatedSurface,
                contentColor = WciColors.textPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(activeRoutePalette.brush, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (state.userProfile?.displayName ?: activeRole.label).take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = WciColors.OnAccentDark,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.userProfile?.displayName ?: activeRole.label,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = WciColors.textPrimary,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Text(
                                text = "@${state.userProfile?.username?.ifBlank { activeRole.key } ?: activeRole.key} • ${activeRole.label} Portal",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.infoColor
                                )
                            )
                            val linked = state.userProfile?.linkedEntityId.orEmpty()
                            if (linked.isNotBlank()) {
                                Text(
                                    text = "Linked ID: $linked",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = WciColors.glassBorderSubtleColor)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LiquidPill(
                            text = if (isDark) "Dark Cosmos" else "Daylight Mode",
                            selected = !isDark,
                            icon = if (isDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                            onClick = { viewModel.setDarkTheme(!isDark) },
                            modifier = Modifier.weight(1f),
                            testTag = "profile_theme_toggle"
                        )
                        LiquidPill(
                            text = "English (EN)",
                            selected = true,
                            icon = Icons.Default.Translate,
                            onClick = {
                                viewModel.setLanguage("EN")
                            },
                            modifier = Modifier.weight(1f),
                            testTag = "profile_language_toggle"
                        )
                    }

                    ShimmerButton(
                        text = "Sign Out of ${activeRole.label} Portal",
                        icon = Icons.AutoMirrored.Filled.Logout,
                        onClick = {
                            showProfileSheet = false
                            showSignOutConfirmation = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "profile_sheet_sign_out_button"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        if (showSignOutConfirmation) {
            AlertDialog(
                onDismissRequest = { showSignOutConfirmation = false },
                containerColor = WciColors.elevatedSurface,
                title = {
                    Text(
                        text = "Confirm Sign Out",
                        color = WciColors.textPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to sign out of your ${activeRole.label} Portal session?",
                        color = WciColors.textSecondary
                    )
                },
                confirmButton = {
                    ShimmerButton(
                        text = "Sign Out",
                        onClick = {
                            showSignOutConfirmation = false
                            viewModel.signOut()
                        }
                    )
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutConfirmation = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        sessionExpiredMsg?.let { expiredText ->
            AlertDialog(
                onDismissRequest = { ConcurrentSessionManager.clearSessionExpiredAlert() },
                containerColor = WciColors.elevatedSurface,
                title = {
                    Text(
                        text = "Single-Device Security Alert",
                        color = WciColors.dangerColor,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        text = expiredText,
                        color = WciColors.textPrimary
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
    onOpenProfileMenu: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val isStudentClay = activeRole == PortalRole.STUDENT
    val topBarBrush = if (!isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.94f),
                palette.start.copy(alpha = 0.14f),
                WciColors.SurfaceOffWhite.copy(alpha = 0.96f)
            )
        )
    } else {
        when (activeRole) {
            PortalRole.STUDENT -> Brush.linearGradient(
                colors = listOf(
                    WciColors.TopBarStudentStart.copy(alpha = 0.96f),
                    palette.start.copy(alpha = 0.34f),
                    WciColors.TopBarStudentEnd.copy(alpha = 0.96f)
                )
            )
            PortalRole.TEACHER -> Brush.linearGradient(
                colors = listOf(
                    WciColors.TopBarTeacherStart.copy(alpha = 0.88f),
                    WciColors.SuccessNeon.copy(alpha = 0.24f),
                    WciColors.TopBarTeacherEnd.copy(alpha = 0.90f)
                )
            )
            PortalRole.PARENT -> Brush.linearGradient(
                colors = listOf(
                    WciColors.TopBarParentStart.copy(alpha = 0.88f),
                    WciColors.NeonPinkCoral.copy(alpha = 0.28f),
                    WciColors.TopBarParentEnd.copy(alpha = 0.90f)
                )
            )
            PortalRole.ADMIN -> Brush.linearGradient(
                colors = listOf(
                    WciColors.TopBarAdminStart.copy(alpha = 0.88f),
                    palette.start.copy(alpha = 0.25f),
                    palette.end.copy(alpha = 0.20f),
                    WciColors.TopBarAdminEnd.copy(alpha = 0.90f)
                )
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
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
                    listOf(
                        if (isDark) Color.White.copy(alpha = 0.55f) else WciColors.LightBorderSubtle,
                        palette.start.copy(alpha = 0.65f)
                    )
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
            // Smooth 48dp 3-Lines Navigation Menu Button
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
                    tint = WciColors.OnAccentDark,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = WciColors.textPrimary,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = WciColors.textSecondary,
                        fontSize = 12.sp
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
            // 48dp Bell Icon -> Opens Inbox / Alerts
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.12f) else WciColors.SlateTrack,
                        CircleShape
                    )
                    .border(1.dp, WciColors.glassBorderSubtleColor, CircleShape)
                    .clickable(onClick = onOpenInbox),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsNone,
                    contentDescription = "Notifications & Inbox",
                    tint = WciColors.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
                if (unreadAlertsCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(8.dp)
                            .background(WciColors.Danger, CircleShape)
                    )
                }
            }

            // 48dp User Avatar -> Opens Profile, Quick Theme/Language & Sign Out Menu
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(palette.brush, CircleShape)
                    .border(1.4.dp, Color.White.copy(alpha = 0.75f), CircleShape)
                    .clickable(onClick = onOpenProfileMenu)
                    .testTag("topbar_sign_out_button"),
                contentAlignment = Alignment.Center
            ) {
                if (studentPhotoUri.isNotBlank()) {
                    StudentAvatarThumbnail(
                        photoUri = studentPhotoUri,
                        fullName = userInitial,
                        palette = palette,
                        size = 48.dp
                    )
                } else {
                    Text(
                        text = userInitial,
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = WciColors.OnAccentDark,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun WciNavigationRail(
    tabs: List<ModuleDestination>,
    currentRoute: String,
    palette: RolePalette,
    onOpenDrawer: () -> Unit,
    onSelectRoute: (String) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = if (isDark) WciColors.NavRailContainerDark else WciColors.NavRailContainerLight,
        contentColor = WciColors.textPrimary,
        header = {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.brush, RoundedCornerShape(14.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = "All Modules",
                    tint = WciColors.OnAccentDark
                )
            }
        }
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.routeId
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectRoute(tab.routeId) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.shortLabel
                    )
                },
                label = {
                    Text(
                        text = tab.shortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = WciColors.OnAccentDark,
                    selectedTextColor = palette.start,
                    indicatorColor = palette.start,
                    unselectedIconColor = WciColors.textSecondary,
                    unselectedTextColor = WciColors.textMuted
                )
            )
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
    val isDark = LocalIsDarkTheme.current
    val haptic = LocalHapticFeedback.current
    val barShape = RoundedCornerShape(WciRadius.xl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(
                elevation = if (isClayPortal) 18.dp else 12.dp,
                shape = barShape,
                ambientColor = WciColors.PurpleVibrant.copy(alpha = 0.45f)
            )
            .clip(barShape)
            .background(
                brush = Brush.linearGradient(
                    colors = when {
                        !isDark -> listOf(
                            Color.White.copy(alpha = 0.96f),
                            WciColors.BgPrimaryLight.copy(alpha = 0.96f)
                        )
                        isClayPortal -> listOf(
                            WciColors.BottomBarClayStart.copy(alpha = 0.96f),
                            WciColors.TopBarStudentEnd.copy(alpha = 0.96f)
                        )
                        else -> listOf(
                            WciColors.BottomBarDarkStart.copy(alpha = 0.92f),
                            WciColors.BottomBarDarkMid.copy(alpha = 0.88f),
                            WciColors.BottomBarDarkEnd.copy(alpha = 0.92f)
                        )
                    }
                ),
                shape = barShape
            )
            .border(
                width = if (isClayPortal) 2.dp else 1.3.dp,
                brush = Brush.linearGradient(
                    listOf(
                        if (isDark) Color.White.copy(alpha = 0.52f) else WciColors.LightBorderSubtle,
                        WciColors.Info.copy(alpha = 0.55f),
                        WciColors.PurpleVibrant.copy(alpha = 0.55f)
                    )
                ),
                shape = barShape
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
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
                targetValue = if (selected) WciColors.textPrimary else WciColors.textSecondary,
                animationSpec = WciMotion.fastTween(),
                label = "bottomTabLabelColor_${tab.routeId}"
            )

            val tabBrush = if (selected) {
                Brush.linearGradient(
                    colors = listOf(
                        tab.accentStart.copy(alpha = if (isDark) 0.45f else 0.24f),
                        tab.accentEnd.copy(alpha = if (isDark) 0.38f else 0.18f)
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
                    .heightIn(min = 48.dp)
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
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("bottom_tab_${tab.routeId}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
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
                        tint = if (selected) WciColors.OnAccentDark else tab.accentStart,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tab.shortLabel,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = labelColor
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
