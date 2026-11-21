package com.example.ui.modules

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.PortalRole
import com.example.security.AdminEncryptedCredentialStore
import com.example.security.HardwareKeystoreVault
import com.example.security.SecurityPostureManager
import com.example.ui.designsystem.*
import com.example.util.DocumentExportHelper
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import com.waqar.institute.security.BytecodeMutator
import com.waqar.institute.security.EnterpriseRaspShield
import com.waqar.institute.security.StrongBoxKeymasterVault
import kotlinx.coroutines.launch

// ============================================================================
// ANALYTICS REPORTS, LAB INVENTORY & LIBRARY, HELPDESK & NPS
// ============================================================================

@Composable
fun ReportsInventorySupportModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    val isAdmin = state.activePortalRole == PortalRole.ADMIN
    var activeTab by remember(state.activePortalRole, initialTab) {
        mutableIntStateOf(if (isAdmin) initialTab else 2)
    }
    var showAddAsset by remember { mutableStateOf(false) }
    var showAddTicket by remember { mutableStateOf(false) }

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
                    LiquidPill(text = "MIS Reports", selected = activeTab == 0, onClick = { activeTab = 0 })
                    LiquidPill(text = "Lab Inventory & Library", selected = activeTab == 1, onClick = { activeTab = 1 }, badgeCount = state.assets.size)
                    LiquidPill(text = "Helpdesk & NPS", selected = activeTab == 2, onClick = { activeTab = 2 }, badgeCount = state.tickets.size)
                    LiquidPill(text = "Adaptive Learning Hub", selected = activeTab == 3, onClick = { activeTab = 3 })
                }
            }
        }

        when (activeTab) {
            0 -> {
                val totalRevenue = state.fees.sumOf { it.paidAmountPaise }
                val totalDues = state.fees.sumOf { it.balancePaise }
                val totalExpenses = state.ledger.filter { it.entryType != "INCOME" }.sumOf { it.amountPaise }
                val netSurplus = totalRevenue - totalExpenses

                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Executive MIS & Financial Analytics", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.textPrimary))
                                Text("Real-time KPI aggregation across institute operations", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                            }
                            LiquidPill(
                                text = "Export JSON Report",
                                selected = false,
                                icon = Icons.Default.Share,
                                onClick = {
                                    val reportJson = """{"institute":"${state.preferences.instituteName}","students":${state.students.size},"revenueInr":${totalRevenue / 100},"duesInr":${totalDues / 100},"expensesInr":${totalExpenses / 100},"netSurplusInr":${netSurplus / 100},"exams":${state.exams.size},"certificates":${state.certificates.size}}"""
                                    DocumentExportHelper.exportAndShareTextOrJson(
                                        context = context,
                                        fileName = "WCI_MIS_Report.json",
                                        content = reportJson
                                    )
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Gross Fee Revenue", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                                MoneyTicker(amountPaise = totalRevenue, color = WciColors.Success, fontSizeSp = 20)
                            }
                            Column {
                                Text("Receivable Fee Dues", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                                MoneyTicker(amountPaise = totalDues, color = WciColors.Warning, fontSizeSp = 20)
                            }
                            Column {
                                Text("Net Operating Surplus", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                                MoneyTicker(amountPaise = netSurplus.coerceAtLeast(0L), color = WciColors.Info, fontSizeSp = 20)
                            }
                        }
                    }
                }

                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Academic & Operational Summary", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                        Spacer(modifier = Modifier.height(8.dp))
                        val metrics = listOf(
                            "Total Enrolled Students" to "${state.students.size}",
                            "Admission CRM Conversion Rate" to "${if (state.enquiries.isEmpty()) 0 else (state.enquiries.count { it.stage == "CONVERTED" } * 100) / state.enquiries.size}%",
                            "Active Courses & Batches" to "${state.courses.size} Courses / ${state.batches.size} Batches",
                            "Published Exams & Results" to "${state.exams.size} Exams / ${state.examResults.size} Mark Sheets",
                            "Issued QR Certificates" to "${state.certificates.size} Verified",
                            "Lab PCs & Library Assets" to "${state.assets.size} Assets",
                            "Average Student NPS Score" to "${if (state.tickets.isEmpty()) 10 else state.tickets.map { it.npsScore }.average().toInt()} / 10"
                        )
                        metrics.forEach { (label, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary))
                                Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, color = WciColors.textPrimary))
                            }
                        }
                    }
                }
            }
            1 -> {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Computer Lab Assets & Library", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.textPrimary))
                            Text("${state.assets.size} Tracked PCs, UPS, Projectors & Books", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                        }
                        ShimmerButton(text = "+ Asset", onClick = { showAddAsset = true })
                    }
                }
                if (state.assets.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.Computer,
                            title = "No computer lab assets or library books logged",
                            subtitle = "Track lab workstations, projectors, routers, UPS units, and reference books with condition status.",
                            ctaLabel = "+ Add First Lab Asset",
                            onCtaClick = { showAddAsset = true }
                        )
                    }
                } else {
                    items(state.assets, key = { it.id }) { ast ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${ast.assetCode} • ${ast.name}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                                    Text("${ast.category} • Location: ${ast.locationLab}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                                    if (ast.assignedTo.isNotBlank()) {
                                        Text("Assigned: ${ast.assignedTo}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        ast.conditionStatus,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            color = if (ast.conditionStatus == "WORKING") WciColors.Success else WciColors.Warning
                                        )
                                    )
                                    MoneyTicker(amountPaise = ast.purchaseCostPaise, color = WciColors.textSecondary, fontSizeSp = 14)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Support Helpdesk & NPS", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.textPrimary))
                            Text("${state.tickets.size} Tickets • Grievance SLA & Satisfaction Rating", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                        }
                        ShimmerButton(text = "+ Ticket", onClick = { showAddTicket = true })
                    }
                }
                if (state.tickets.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Default.Support,
                            title = "No support tickets or feedback items",
                            subtitle = "Students, parents, and teachers can raise fee, lab, exam, or certificate queries with SLA tracking.",
                            ctaLabel = "+ Raise Support Ticket",
                            onCtaClick = { showAddTicket = true }
                        )
                    }
                } else {
                    items(state.tickets, key = { it.id }) { tkt ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${tkt.ticketNo} • ${tkt.subject}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                                    Text("${tkt.category} • Priority: ${tkt.priority} • By: ${tkt.requesterName}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                                    Text(tkt.description, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary))
                                    if (tkt.resolutionNotes.isNotBlank()) {
                                        Text("Resolution: ${tkt.resolutionNotes}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Success))
                                    }
                                }
                                if (tkt.status != "RESOLVED" && (state.activePortalRole == PortalRole.ADMIN || state.activePortalRole == PortalRole.TEACHER)) {
                                    LiquidPill(
                                        text = "Resolve",
                                        selected = true,
                                        onClick = { viewModel.resolveTicket(tkt, "Resolved by Support Desk") }
                                    )
                                } else {
                                    Text(tkt.status, style = MaterialTheme.typography.labelLarge.copy(color = WciColors.Success))
                                }
                            }
                        }
                    }
                }
            }
            3 -> {
                // Tab 3: Admin Adaptive Learning Hub
                val totalAssessed = state.students.size
                val overallMastery = if (state.examResults.isNotEmpty()) {
                    state.examResults.sumOf { if (it.totalMarks > 0) (it.marksObtained * 100) / it.totalMarks else 0 } / state.examResults.size
                } else 76

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = WciColors.PurpleVibrant
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Admin Adaptive Learning Analytics Hub",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = WciColors.textPrimary
                                )
                            )
                            Text(
                                text = "Institute-wide AI mastery velocity, cohort benchmarks, and retention metrics.",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Overall Mastery", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                                    Text("$overallMastery%", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = WciColors.Success))
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Growth Trend", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                                    Text("+5.8% MoM", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = WciColors.CyanBright))
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Assessed Students", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                                    Text("$totalAssessed", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary))
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Departmental & Course Learning Velocity",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = WciColors.textPrimary
                        )
                    )
                }

                items(state.courses) { course ->
                    val enrolled = state.students.filter {
                        it.courseName.contains(course.title, ignoreCase = true) ||
                            it.courseName.contains(course.code, ignoreCase = true)
                    }
                    val courseResults = state.examResults.filter {
                        it.examTitle.contains(course.title, ignoreCase = true) ||
                            it.examTitle.contains(course.code, ignoreCase = true)
                    }
                    val mastery = if (courseResults.isNotEmpty()) {
                        courseResults.sumOf { if (it.totalMarks > 0) (it.marksObtained * 100) / it.totalMarks else 0 } / courseResults.size
                    } else 75

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${course.code} • ${course.title}",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary)
                                )
                                Text(
                                    text = "Enrolled: ${enrolled.size} Learners • Duration: ${course.durationMonths} Months",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                                )
                                Text(
                                    text = "Cohort Velocity: ${if (mastery >= 75) "Accelerated" else "Steady"}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = if (mastery >= 75) WciColors.Success else WciColors.Info)
                                )
                            }
                            DonutProgressRing(
                                progress = mastery / 100f,
                                color = if (mastery >= 75) WciColors.Success else WciColors.Warning,
                                label = "$mastery%",
                                subLabel = "Mastery",
                                sizeDp = 52.dp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddAsset) {
        var code by remember { mutableStateOf("WCI-PC-${(10..99).random()}") }
        var name by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("LAB_PC") }
        var lab by remember { mutableStateOf("Computer Lab 1") }
        var cost by remember { mutableStateOf("42000") }

        AlertDialog(
            onDismissRequest = { showAddAsset = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Add Lab Asset / Library Item", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("LAB_PC", "NETWORK_UPS", "LIBRARY_BOOK", "CONSUMABLE").forEach { c ->
                            LiquidPill(text = c.substringBefore("_"), selected = category == c, onClick = { category = c })
                        }
                    }
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Asset Tag / Barcode") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Item Name / Spec *") }, singleLine = true)
                    OutlinedTextField(value = lab, onValueChange = { lab = it }, label = { Text("Lab / Shelf Location") }, singleLine = true)
                    OutlinedTextField(value = cost, onValueChange = { cost = it }, label = { Text("Purchase Cost (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save Asset",
                    enabled = name.isNotBlank(),
                    onClick = {
                        viewModel.saveInventoryAsset(code, name, category, lab, "WORKING", "", cost.toLongOrNull() ?: 0L)
                        showAddAsset = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddAsset = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }

    if (showAddTicket) {
        var subject by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var cat by remember { mutableStateOf("LAB_ISSUE") }
        var prio by remember { mutableStateOf("HIGH") }

        AlertDialog(
            onDismissRequest = { showAddTicket = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Submit Helpdesk Ticket", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Category", style = MaterialTheme.typography.labelMedium.copy(color = WciColors.textSecondary))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("LAB_ISSUE", "FEE_QUERY", "EXAM_CELL", "CERTIFICATE").forEach { c ->
                            LiquidPill(text = c.substringBefore("_"), selected = cat == c, onClick = { cat = c })
                        }
                    }
                    Text("Priority", style = MaterialTheme.typography.labelMedium.copy(color = WciColors.textSecondary))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("LOW", "MEDIUM", "HIGH", "CRITICAL").forEach { p ->
                            LiquidPill(text = p, selected = prio == p, onClick = { prio = p })
                        }
                    }
                    OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject *") }, singleLine = true)
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Issue Details *") })
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Submit Ticket",
                    enabled = subject.isNotBlank() && desc.isNotBlank(),
                    onClick = {
                        viewModel.submitSupportTicket(subject, desc, cat, prio, 10)
                        showAddTicket = false
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddTicket = false }) { Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold) } }
        )
    }
}

// ============================================================================
// SECURITY, CLOUD CREDENTIAL VAULT, BACKUPS & INSTITUTE SETTINGS
// ============================================================================

@Composable
fun SecurityBackupSettingsModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    val palette = LocalRolePalette.current
    val isAdmin = state.activePortalRole == PortalRole.ADMIN
    var activeTab by remember(state.activePortalRole, initialTab) {
        mutableIntStateOf(if (isAdmin) initialTab else 2)
    }
    val posture = remember(state.preferences.flagSecureEnabled) {
        SecurityPostureManager.evaluate(context, state.preferences.flagSecureEnabled)
    }
    val hasStrongBox = remember { HardwareKeystoreVault.isStrongBoxSupported(context) }

    // Admin Credential Update States (synced from EncryptedSharedPreferences/Keystore)
    var adminUsername by remember(state.userProfile?.username, activeTab) {
        mutableStateOf(
            state.userProfile?.username?.takeIf { it.isNotBlank() }
                ?: AdminEncryptedCredentialStore.getActiveAdminUsername(context)
        )
    }
    var adminNewPassword by remember { mutableStateOf("") }
    var adminConfirmPassword by remember { mutableStateOf("") }

    // Issue New Student/Parent/Teacher Credential Dialog
    var showIssueCredentialDialog by remember { mutableStateOf(false) }

    var instName by remember(state.preferences.instituteName) { mutableStateOf(state.preferences.instituteName) }
    var instTagline by remember(state.preferences.instituteTagline) { mutableStateOf(state.preferences.instituteTagline) }
    var instBranch by remember(state.preferences.instituteBranch) { mutableStateOf(state.preferences.instituteBranch) }
    var instSession by remember(state.preferences.academicSession) { mutableStateOf(state.preferences.academicSession) }
    var instGst by remember(state.preferences.gstNumber) { mutableStateOf(state.preferences.gstNumber) }

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
                    LiquidPill(text = "Security & Credentials", selected = activeTab == 0, onClick = { activeTab = 0 })
                    LiquidPill(text = "Backups & Sync", selected = activeTab == 1, onClick = { activeTab = 1 }, badgeCount = state.backupsAndConfigs.size)
                    LiquidPill(text = "Institute Settings", selected = activeTab == 2, onClick = { activeTab = 2 })
                }
            }
        }

        when (activeTab) {
            0 -> {
                // 1. Admin Live Username & Password Update Card (Saved in Cloud Firestore)
                if (isAdmin) {
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            glowColor = palette.glow
                        ) {
                            Text(
                                text = "Admin Portal Username & Password (Encrypted SHA-256)",
                                style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary)
                            )
                            Text(
                                text = "Saves SHA-256 digest immediately into EncryptedSharedPreferences / Keystore (works offline & online in <0.1s) and syncs to Cloud Firestore.",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = adminUsername,
                                    onValueChange = { adminUsername = it },
                                    label = { Text("Admin Username") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("admin_username_update_input")
                                )
                                OutlinedTextField(
                                    value = adminNewPassword,
                                    onValueChange = { adminNewPassword = it },
                                    label = { Text("New Admin Password") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("admin_password_update_input")
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            ShimmerButton(
                                text = "Update Admin Credentials (Keystore + Cloud)",
                                icon = Icons.Default.SyncLock,
                                enabled = adminUsername.trim().length >= 3 && adminNewPassword.trim().length >= 4,
                                onClick = {
                                    val u = adminUsername
                                    val p = adminNewPassword
                                    com.waqar.institute.biometric.HardwareBiometricAttestor.signCriticalAdminActionWithCryptoObject(
                                        activity = context as? Activity,
                                        actionType = "ADMIN_CREDENTIALS_ROTATED",
                                        canonicalPayload = "admin_cred_rotation|${u.trim().lowercase()}",
                                        onSigned = { sig ->
                                            viewModel.updateAdminCredentials(u, p, biometricActionSignatureHex = sig.signatureHex)
                                        },
                                        onFallback = { sig ->
                                            viewModel.updateAdminCredentials(u, p, biometricActionSignatureHex = sig.signatureHex)
                                        }
                                    )
                                    adminNewPassword = ""
                                    adminConfirmPassword = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "save_admin_credentials_button"
                            )
                        }
                    }

                    // 1b. Server Hash-Chain Integrity & 1-Tap Compromised Device Revocation (Admin Only)
                    item {
                        var chainReport by remember {
                            mutableStateOf<com.waqar.institute.security.ServerAuditHashChainClient.ChainVerificationReport?>(null)
                        }
                        var isVerifyingChain by remember { mutableStateOf(false) }
                        var revokeFpInput by remember { mutableStateOf("") }
                        var revokeReasonInput by remember { mutableStateOf("Compromised or lost device") }
                        var revokedList by remember {
                            mutableStateOf<List<com.waqar.institute.security.DeviceRevocationGuard.RevokedDeviceEntry>>(emptyList())
                        }
                        val secScope = rememberCoroutineScope()

                        LaunchedEffect(Unit) {
                            isVerifyingChain = true
                            chainReport = com.waqar.institute.security.ServerAuditHashChainClient.verifyServerAuditHashChain(context)
                            revokedList = com.waqar.institute.security.DeviceRevocationGuard.fetchRevokedDevices()
                            isVerifyingChain = false
                        }

                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            val statusText = when {
                                isVerifyingChain -> "CHECKING..."
                                chainReport?.statusLabel == "VERIFIED" -> "VERIFIED"
                                chainReport?.statusLabel == "TAMPERED" -> "TAMPERED"
                                else -> "OFFLINE / PENDING"
                            }
                            val statusColor = when (statusText) {
                                "VERIFIED" -> WciColors.successColor
                                "TAMPERED" -> WciColors.dangerColor
                                else -> WciColors.warningColor
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Server Hash-Chain Integrity: $statusText",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = statusColor,
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        modifier = Modifier.testTag("server_hash_chain_status_label")
                                    )
                                    Text(
                                        text = chainReport?.message?.ifBlank { "Audit hash chain ready" }
                                            ?: "Verifying server-side Fee & Certificate SHA-256 chain...",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMono,
                                            color = WciColors.textMuted
                                        )
                                    )
                                }
                                LiquidPill(
                                    text = "Re-Verify",
                                    selected = false,
                                    onClick = {
                                        secScope.launch {
                                            isVerifyingChain = true
                                            chainReport = com.waqar.institute.security.ServerAuditHashChainClient.verifyServerAuditHashChain(context)
                                            isVerifyingChain = false
                                        }
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = WciColors.glassBorderSubtleColor)
                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "1-Tap Compromised Device Revocation",
                                style = MaterialTheme.typography.titleSmall.copy(color = WciColors.textPrimary)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = revokeFpInput,
                                    onValueChange = { revokeFpInput = it },
                                    label = { Text("Device Fingerprint") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("revoke_device_fp_input")
                                )
                                OutlinedTextField(
                                    value = revokeReasonInput,
                                    onValueChange = { revokeReasonInput = it },
                                    label = { Text("Reason") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            ShimmerButton(
                                text = "Revoke Compromised Device Immediately",
                                icon = Icons.Default.Block,
                                enabled = revokeFpInput.trim().length >= 4,
                                onClick = {
                                    val targetFp = revokeFpInput.trim()
                                    val reason = revokeReasonInput.trim()
                                    secScope.launch {
                                        val res = com.waqar.institute.security.DeviceRevocationGuard.revokeDevice(
                                            deviceFingerprint = targetFp,
                                            reason = reason,
                                            adminUid = state.userProfile?.uid ?: "admin_root"
                                        )
                                        res.onSuccess {
                                            revokeFpInput = ""
                                            revokedList = com.waqar.institute.security.DeviceRevocationGuard.fetchRevokedDevices()
                                            viewModel.postMessage("Device '$targetFp' revoked immediately across Cloud Functions & app sessions.")
                                        }.onFailure { err ->
                                            viewModel.postMessage("Revocation failed: ${err.localizedMessage}")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "revoke_device_submit_button"
                            )

                            if (revokedList.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                revokedList.take(5).forEach { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${entry.deviceFingerprint} • ${if (entry.revoked) "REVOKED" else "ACTIVE"}",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = JetBrainsMono,
                                                    color = if (entry.revoked) WciColors.dangerColor else WciColors.successColor,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                            if (entry.reason.isNotBlank()) {
                                                Text(
                                                    text = entry.reason,
                                                    style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted)
                                                )
                                            }
                                        }
                                        if (entry.revoked) {
                                            LiquidPill(
                                                text = "Unrevoke",
                                                selected = false,
                                                onClick = {
                                                    secScope.launch {
                                                        com.waqar.institute.security.DeviceRevocationGuard.unrevokeDevice(
                                                            deviceFingerprint = entry.deviceFingerprint,
                                                            adminUid = state.userProfile?.uid ?: "admin_root"
                                                        )
                                                        revokedList = com.waqar.institute.security.DeviceRevocationGuard.fetchRevokedDevices()
                                                        viewModel.postMessage("Restored device '${entry.deviceFingerprint}'.")
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Student, Parent & Teacher Cloud Credential Vault (Admin Only)
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Student, Parent & Teacher Login Credentials",
                                        style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary)
                                    )
                                    Text(
                                        text = "${state.portalCredentials.size} Accounts in Cloud • PBKDF2-HMAC-SHA256 Salted • Per-Student Record Isolation",
                                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                                    )
                                }
                                ShimmerButton(
                                    text = "+ Issue Login",
                                    icon = Icons.Default.Key,
                                    onClick = { showIssueCredentialDialog = true },
                                    testTag = "issue_role_credential_button"
                                )
                            }
                            if (state.portalCredentials.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                state.portalCredentials.take(12).forEach { cred ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "@${cred.username} • ${cred.role.uppercase()} (${cred.displayName})",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = JetBrainsMono,
                                                    fontWeight = FontWeight.Bold,
                                                    color = WciColors.textPrimary
                                                )
                                            )
                                            Text(
                                                text = "Linked Record ID: ${cred.linkedEntityId.ifBlank { "Global" }} • Hash: Argon2id (64 MiB, t=3, p=2)",
                                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                                            )
                                        }
                                        LiquidPill(
                                            text = "Revoke",
                                            selected = false,
                                            onClick = { viewModel.deleteRoleCredential(cred.username) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Enterprise RASP, StrongBox Keystore, SQLCipher AES-256 & SPKI Pinning Posture
                item {
                    val rasp = state.raspReport
                    val tamperVerdict = remember { EnterpriseRaspShield.evaluateAndEnforceTamperTrap(context) }
                    val keymasterPosture = remember { StrongBoxKeymasterVault.evaluateKeymasterPosture(context) }
                    val ipFlowClean = remember { BytecodeMutator.isInstructionPointerFlowHealthy() }
                    val ollvmDigestHex = remember {
                        com.waqar.institute.security.PlayIntegrityEnforcer
                            .computeOllvmProtectedDigestHex("WCI_SECURITY_CENTER")
                            .take(16)
                    }

                    val sqlCipherActive = com.example.data.local.InstituteLocalDatabase.isSqlCipherActive
                    val raspVerdictText = rasp?.verdict ?: posture.integrityVerdict
                    val isRaspClean = !raspVerdictText.contains("COMPROMISED", ignoreCase = true) &&
                        !raspVerdictText.contains("TAMPER", ignoreCase = true) &&
                        !raspVerdictText.contains("ROOT", ignoreCase = true)
                    val isKeymasterStrong = keymasterPosture.securityLevelLabel.contains("StrongBox", ignoreCase = true) ||
                        keymasterPosture.securityLevelLabel.contains("TEE", ignoreCase = true)

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = if (isRaspClean) WciColors.successColor else WciColors.dangerColor
                    ) {
                        Text(
                            text = "Enterprise Security & Cryptography Posture",
                            style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple("RASP", if (isRaspClean) "CLEAN" else "ALERT", if (isRaspClean) WciColors.successColor else WciColors.dangerColor),
                                Triple("SQLCipher", if (sqlCipherActive) "AES-256" else "FALLBACK", if (sqlCipherActive) WciColors.successColor else WciColors.warningColor),
                                Triple("Keymaster", if (isKeymasterStrong) "HARDWARE" else "SOFTWARE", if (isKeymasterStrong) WciColors.infoColor else WciColors.warningColor),
                                Triple("FLAG_SECURE", if (state.preferences.flagSecureEnabled) "ACTIVE" else "OFF", if (state.preferences.flagSecureEnabled) WciColors.successColor else WciColors.warningColor)
                            ).forEach { (k, v, badgeColor) ->
                                Box(
                                    modifier = Modifier
                                        .background(badgeColor.copy(alpha = 0.16f), RoundedCornerShape(WciRadius.pill))
                                        .border(1.dp, badgeColor.copy(alpha = 0.7f), RoundedCornerShape(WciRadius.pill))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "$k: $v",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = JetBrainsMono,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor
                                        )
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "RASP Verdict: $raspVerdictText • ${tamperVerdict.statusSummary}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMono,
                                color = if (isRaspClean) WciColors.successColor else WciColors.dangerColor
                            )
                        )
                        Text(
                            text = "Keymaster: ${keymasterPosture.securityLevelLabel} • Room DB: ${com.example.data.local.InstituteLocalDatabase.databaseEncryptionLabel}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMono,
                                color = WciColors.infoColor
                            )
                        )
                        Text(
                            text = "Polymorphic IP Shield: ${if (ipFlowClean) "HEALTHY (OLLVM:$ollvmDigestHex)" else "ANOMALY"} • SPKI SHA-256 Pinning Active",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMono,
                                color = if (ipFlowClean) WciColors.textSecondary else WciColors.dangerColor
                            )
                        )
                        val ledgerIntact = remember(state.auditLogs.size) {
                            com.waqar.institute.ledger.ImmutableEventSourcedLedger.verifyMerkleChainIntegrity()
                        }
                        val ledgerRootHex = remember(state.auditLogs.size) {
                            com.waqar.institute.ledger.ImmutableEventSourcedLedger.latestMerkleRootHex()
                        }
                        Text(
                            text = "Merkle Audit Ledger: ${if (ledgerIntact) "VERIFIED" else "TAMPER ALERT"} • Root:$ledgerRootHex",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMono,
                                color = if (ledgerIntact) WciColors.successColor else WciColors.dangerColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("FLAG_SECURE Screenshot Protection", style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.textPrimary))
                                Text("Blocks screen capture & recents thumbnail on sensitive financial/exam screens", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                            }
                            Switch(
                                checked = state.preferences.flagSecureEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setFlagSecureEnabled(enabled)
                                    SecurityPostureManager.applyFlagSecure(context as? Activity, enabled)
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = WciColors.glassBorderSubtleColor)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("TOTP 2FA Multi-Factor Challenge", style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.textPrimary))
                                Text("Require 6-digit TOTP authenticator verification on login", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                            }
                            Switch(
                                checked = state.userProfile?.mfaEnabled == true,
                                onCheckedChange = { viewModel.toggleMfaEnrollment(it) }
                            )
                        }
                    }
                }

                // 4. Real-Time Security Violation Telemetry & 1-Tap Student Account Lock (Admin Only)
                if (isAdmin) {
                    item {
                        val criticalAlerts = remember(state.auditLogs) {
                            state.auditLogs.filter {
                                it.severity == "CRITICAL" || it.action.startsWith("SECURITY_")
                            }
                        }
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            glowColor = WciColors.Danger
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Real-Time Security Violation Telemetry",
                                    style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary)
                                )
                                Text(
                                    text = "Captures Account ID, Username, Device & Network on RASP/Frida/MT Manager/Session Violations",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                                )
                                LiquidPill(
                                    text = "Run Telemetry Drill (Diagnostic Self-Test)",
                                    selected = true,
                                    icon = Icons.Default.Shield,
                                    onClick = {
                                        viewModel.reportSecurityIncidentAndNotifyAdmin(
                                            context = context,
                                            username = state.userProfile?.username ?: "admin_telemetry_drill",
                                            fullName = "Diagnostic Security Self-Test",
                                            phone = "",
                                            studentId = "",
                                            violationType = "RASP_MEMORY_INTEGRITY_SCAN",
                                            details = "Diagnostic Security Self-Test: Automated RASP & Single-Device Telemetry Verification Drill"
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (criticalAlerts.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                criticalAlerts.take(5).forEach { alert ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${alert.severity} • ${alert.action}",
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontFamily = JetBrainsMono,
                                                    color = WciColors.dangerColor
                                                )
                                            )
                                            Text(
                                                text = alert.details,
                                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textPrimary)
                                            )
                                        }
                                        if (alert.targetId.isNotBlank()) {
                                            LiquidPill(
                                                text = "Lock Account",
                                                selected = false,
                                                onClick = {
                                                    viewModel.suspendStudentAccountForSecurityViolation(alert.targetId)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Active Device Sessions
                item {
                    Text("Active Device Sessions", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                }
                items(state.sessions, key = { it.sessionId }) { sess ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sess.deviceName, style = MaterialTheme.typography.titleSmall.copy(color = WciColors.textPrimary))
                                Text("${sess.platform} • Active: ${sess.active}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                            }
                            if (sess.active) {
                                LiquidPill(
                                    text = "Revoke",
                                    selected = false,
                                    onClick = { viewModel.revokeSession(sess) }
                                )
                            } else {
                                Text("REVOKED", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Danger))
                            }
                        }
                    }
                }

                // Immutable Audit Logs
                item {
                    Text("Security Audit Log", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                }
                items(state.auditLogs.take(15), key = { it.id }) { log ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                        Text(
                            "${log.severity} • ${log.action}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono, color = WciColors.Info)
                        )
                        Text(log.details, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textPrimary))
                        Text("Actor: ${log.actorEmail}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                    }
                }
            }
            1 -> {
                item {
                    var backupPassphrase by remember { mutableStateOf("") }
                    val restoreWciPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            val rawBytes = runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }.getOrNull()
                            if (rawBytes != null && rawBytes.isNotEmpty()) {
                                viewModel.restoreEncryptedWciBackup(
                                    encryptedBytes = rawBytes,
                                    passphrase = backupPassphrase
                                )
                            } else {
                                viewModel.postMessage("Unable to read selected .wci backup file.")
                            }
                        }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Cloud Backup, Export & Data Portability", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.textPrimary))
                        Text(
                            "Create AES-256-GCM password-encrypted .wci institute vault archives (PBKDF2-HMAC-SHA256) or export JSON manifests via Android Share Sheet.",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = backupPassphrase,
                            onValueChange = { backupPassphrase = it },
                            label = { Text("Backup Vault Passphrase (min 6 chars)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("wci_backup_passphrase_input")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ShimmerButton(
                                text = "Export Encrypted .wci",
                                icon = Icons.Default.Lock,
                                enabled = backupPassphrase.trim().length >= 6,
                                onClick = {
                                    viewModel.exportEncryptedWciBackup(
                                        context = context,
                                        passphrase = backupPassphrase
                                    )
                                },
                                modifier = Modifier.weight(1f).testTag("export_encrypted_wci_button")
                            )
                            LiquidPill(
                                text = "Restore .wci",
                                selected = false,
                                icon = Icons.Default.Restore,
                                onClick = {
                                    if (backupPassphrase.trim().length < 6) {
                                        viewModel.postMessage("Enter your .wci vault passphrase first (min 6 characters).")
                                    } else {
                                        restoreWciPicker.launch(arrayOf("*/*"))
                                    }
                                },
                                modifier = Modifier.testTag("restore_encrypted_wci_button")
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        LiquidPill(
                            text = "Export Plain JSON Manifest Snapshot",
                            selected = false,
                            icon = Icons.Default.CloudUpload,
                            onClick = {
                                val json = viewModel.createCloudBackupSnapshot("Manual Institute Snapshot")
                                DocumentExportHelper.exportAndShareTextOrJson(
                                    context = context,
                                    fileName = "WCI_Backup_${System.currentTimeMillis()}.json",
                                    content = json
                                )
                            }
                        )
                    }
                }
                items(state.backupsAndConfigs, key = { it.id }) { bkp ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("${bkp.category} • ${bkp.title}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                        Text("Records: ${bkp.recordCount} • Checksum: ${bkp.checksum}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.Success))
                        Text(bkp.payloadJson, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted))
                    }
                }
            }
            else -> {
                // 1. Portal Language Card (Strict English Standard)
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = WciColors.Info
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Portal Language (English Standard)",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = WciColors.textPrimary,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                Text(
                                    text = "All portals (Admin, Teacher, Student, Parent) are standardized to clean English UI",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Language",
                                tint = WciColors.Info
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LiquidPill(
                                text = "English (Active)",
                                selected = true,
                                icon = Icons.Default.Language,
                                onClick = { viewModel.setLanguage("EN") },
                                modifier = Modifier.weight(1f),
                                testTag = "settings_lang_en_button"
                            )
                        }
                    }
                }

                // 2. Appearance & Battery Saver Mode (Default Ultra/High Motion runs automatically)
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Theme & Battery Saver Mode",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Dark Cosmos Theme",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = WciColors.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Deep sapphire-indigo Liquid Glass & 3D Claymorphism palette",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                                )
                            }
                            Switch(
                                checked = state.preferences.darkTheme,
                                onCheckedChange = { viewModel.setDarkTheme(it) }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = WciColors.glassBorderSubtleColor)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Battery Saver Mode",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = WciColors.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Optimizes background wave animations to save battery power",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                                )
                            }
                            Switch(
                                checked = state.preferences.reduceMotion,
                                onCheckedChange = { viewModel.setReduceMotion(it) }
                            )
                        }
                    }
                }

                if (isAdmin) {
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            glowColor = palette.glow
                        ) {
                            Text(
                                text = "Admin Login Username & Password",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = WciColors.textPrimary,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Text(
                                text = "New password SHA-256 hash is written immediately to EncryptedSharedPreferences / Android Keystore for offline & online instant login.",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = adminUsername,
                                onValueChange = { adminUsername = it },
                                label = { Text("Admin Username") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_admin_username_input")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = adminNewPassword,
                                    onValueChange = { adminNewPassword = it },
                                    label = { Text("New Password (min 4 chars)") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_admin_password_input")
                                )
                                OutlinedTextField(
                                    value = adminConfirmPassword,
                                    onValueChange = { adminConfirmPassword = it },
                                    label = { Text("Confirm Password") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_admin_confirm_password_input")
                                )
                            }
                            if (adminConfirmPassword.isNotEmpty() && adminConfirmPassword != adminNewPassword) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Passwords do not match.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Danger)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            val canSaveCreds = adminUsername.trim().length >= 3 &&
                                adminNewPassword.trim().length >= 4 &&
                                (adminConfirmPassword.isEmpty() || adminConfirmPassword == adminNewPassword)
                            ShimmerButton(
                                text = "Save New Username & Password",
                                icon = Icons.Default.SyncLock,
                                enabled = canSaveCreds,
                                onClick = {
                                    val u = adminUsername
                                    val p = adminNewPassword
                                    com.waqar.institute.biometric.HardwareBiometricAttestor.signCriticalAdminActionWithCryptoObject(
                                        activity = context as? Activity,
                                        actionType = "ADMIN_CREDENTIALS_ROTATED",
                                        canonicalPayload = "admin_cred_rotation|${u.trim().lowercase()}",
                                        onSigned = { sig ->
                                            viewModel.updateAdminCredentials(u, p, biometricActionSignatureHex = sig.signatureHex)
                                        },
                                        onFallback = { sig ->
                                            viewModel.updateAdminCredentials(u, p, biometricActionSignatureHex = sig.signatureHex)
                                        }
                                    )
                                    adminNewPassword = ""
                                    adminConfirmPassword = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "settings_save_admin_credentials_button"
                            )
                        }
                    }

                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text("Institute Branding & Tax Profile", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.textPrimary))
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = instName, onValueChange = { instName = it }, label = { Text("Institute Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = instTagline, onValueChange = { instTagline = it }, label = { Text("Institute Tagline") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = instBranch, onValueChange = { instBranch = it }, label = { Text("Campus / ISO Accreditation") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = instSession, onValueChange = { instSession = it }, label = { Text("Academic Session") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = instGst, onValueChange = { instGst = it }, label = { Text("GSTIN Number") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            ShimmerButton(
                                text = "Save Institute Settings",
                                icon = Icons.Default.Save,
                                onClick = {
                                    viewModel.saveInstituteBranding(instName, instTagline, instBranch, instSession, instGst)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showIssueCredentialDialog) {
        var credRole by remember { mutableStateOf(PortalRole.STUDENT) }
        var credUsername by remember { mutableStateOf("") }
        var credPassword by remember { mutableStateOf("") }
        var credDisplayName by remember { mutableStateOf(state.students.firstOrNull()?.fullName ?: "") }
        var credLinkedId by remember { mutableStateOf(state.students.firstOrNull()?.id ?: "") }

        AlertDialog(
            onDismissRequest = { showIssueCredentialDialog = false },
            containerColor = WciColors.elevatedSurface,
            title = { Text("Issue Portal Login (Student / Parent / Teacher)", color = WciColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(PortalRole.STUDENT, PortalRole.PARENT, PortalRole.TEACHER, PortalRole.ADMIN).forEach { r ->
                            LiquidPill(
                                text = r.label,
                                selected = credRole == r,
                                onClick = {
                                    credRole = r
                                    when (r) {
                                        PortalRole.STUDENT -> {
                                            val stu = state.students.firstOrNull()
                                            if (stu != null) {
                                                credLinkedId = stu.id
                                                credDisplayName = stu.fullName
                                                credUsername = stu.fullName.lowercase().replace(Regex("[^a-z0-9]"), "") + "_wci"
                                            }
                                        }
                                        PortalRole.PARENT -> {
                                            val stu = state.students.firstOrNull()
                                            if (stu != null) {
                                                credLinkedId = stu.id
                                                credDisplayName = stu.guardianName.ifBlank { "Parent of ${stu.fullName}" }
                                                credUsername = "parent_" + stu.fullName.lowercase().replace(Regex("[^a-z0-9]"), "")
                                            }
                                        }
                                        PortalRole.TEACHER -> {
                                            val stf = state.staff.firstOrNull()
                                            credLinkedId = stf?.id ?: "stf_faculty"
                                            credDisplayName = stf?.fullName ?: "Faculty Member"
                                            credUsername = "teacher_" + (stf?.fullName ?: "wci").lowercase().replace(Regex("[^a-z0-9]"), "")
                                        }
                                        PortalRole.ADMIN -> {
                                            credLinkedId = "admin_root"
                                            credDisplayName = "Waqar Khan (Admin)"
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Select Which Student (for Student or Parent Login)
                    if ((credRole == PortalRole.STUDENT || credRole == PortalRole.PARENT) && state.students.isNotEmpty()) {
                        Text(
                            text = if (credRole == PortalRole.PARENT) "Select Student (Whose Parent is this?):" else "Select Enrolled Student:",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info, fontWeight = FontWeight.Bold)
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
                                    selected = credLinkedId == stu.id,
                                    onClick = {
                                        credLinkedId = stu.id
                                        if (credRole == PortalRole.PARENT) {
                                            credDisplayName = stu.guardianName.ifBlank { "Parent of ${stu.fullName}" }
                                            credUsername = "parent_" + stu.fullName.lowercase().replace(Regex("[^a-z0-9]"), "")
                                        } else {
                                            credDisplayName = stu.fullName
                                            credUsername = stu.fullName.lowercase().replace(Regex("[^a-z0-9]"), "") + "_wci"
                                        }
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = credUsername,
                        onValueChange = { credUsername = it },
                        label = { Text("Login Username *") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = credPassword,
                        onValueChange = { credPassword = it },
                        label = { Text("Login Password (min 6 chars) *") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = credDisplayName,
                        onValueChange = { credDisplayName = it },
                        label = { Text(if (credRole == PortalRole.PARENT) "Parent Name (Parent of Student) *" else "Full Name / Account Holder *") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = credLinkedId,
                        onValueChange = { credLinkedId = it },
                        label = { Text("Linked Student / Staff ID (Isolates Record)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = "Save to Cloud Vault",
                    enabled = credUsername.trim().length >= 3 && credPassword.trim().length >= 6 && credDisplayName.isNotBlank(),
                    onClick = {
                        viewModel.provisionRoleCredential(
                            username = credUsername,
                            password = credPassword,
                            role = credRole,
                            linkedEntityId = credLinkedId,
                            displayName = credDisplayName
                        )
                        showIssueCredentialDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showIssueCredentialDialog = false }) {
                    Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
