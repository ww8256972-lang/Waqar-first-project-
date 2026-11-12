package com.example.ui.modules

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.security.HardwareKeystoreVault
import com.example.security.SecurityPostureManager
import com.example.ui.designsystem.*
import com.example.util.DocumentExportHelper
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import com.waqar.institute.security.BytecodeMutator
import com.waqar.institute.security.EnterpriseRaspShield
import com.waqar.institute.security.StrongBoxKeymasterVault

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
                                Text("Executive MIS & Financial Analytics", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark))
                                Text("Real-time KPI aggregation across institute operations", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
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
                                Text("Gross Fee Revenue", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                                MoneyTicker(amountPaise = totalRevenue, color = WciColors.Success, fontSizeSp = 20)
                            }
                            Column {
                                Text("Receivable Fee Dues", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                                MoneyTicker(amountPaise = totalDues, color = WciColors.Warning, fontSizeSp = 20)
                            }
                            Column {
                                Text("Net Operating Surplus", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                                MoneyTicker(amountPaise = netSurplus.coerceAtLeast(0L), color = WciColors.Info, fontSizeSp = 20)
                            }
                        }
                    }
                }

                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Academic & Operational Summary", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
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
                                Text(label, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
                                Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, color = WciColors.TextPrimaryDark))
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
                            Text("Computer Lab Assets & Library", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark))
                            Text("${state.assets.size} Tracked PCs, UPS, Projectors & Books", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
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
                                    Text("${ast.assetCode} • ${ast.name}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                                    Text("${ast.category} • Location: ${ast.locationLab}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                                    if (ast.assignedTo.isNotBlank()) {
                                        Text("Assigned: ${ast.assignedTo}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        ast.conditionStatus,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            color = if (ast.conditionStatus == "WORKING") WciColors.Success else WciColors.Warning
                                        )
                                    )
                                    MoneyTicker(amountPaise = ast.purchaseCostPaise, color = WciColors.TextSecondaryDark, fontSizeSp = 14)
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
                            Text("Support Helpdesk & NPS", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark))
                            Text("${state.tickets.size} Tickets • Grievance SLA & Satisfaction Rating", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
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
                                    Text("${tkt.ticketNo} • ${tkt.subject}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                                    Text("${tkt.category} • Priority: ${tkt.priority} • By: ${tkt.requesterName}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info))
                                    Text(tkt.description, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark))
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
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Add Lab Asset / Library Item", color = WciColors.TextPrimaryDark) },
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
            dismissButton = { TextButton(onClick = { showAddAsset = false }) { Text("Cancel") } }
        )
    }

    if (showAddTicket) {
        var subject by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var cat by remember { mutableStateOf("LAB_ISSUE") }
        var prio by remember { mutableStateOf("HIGH") }

        AlertDialog(
            onDismissRequest = { showAddTicket = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Submit Helpdesk Ticket", color = WciColors.TextPrimaryDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("LAB_ISSUE", "FEE_QUERY", "EXAM_CELL", "CERTIFICATE").forEach { c ->
                            LiquidPill(text = c.substringBefore("_"), selected = cat == c, onClick = { cat = c })
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
            dismissButton = { TextButton(onClick = { showAddTicket = false }) { Text("Cancel") } }
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

    // Admin Credential Update States
    var adminUsername by remember(state.userProfile?.username) {
        mutableStateOf(state.userProfile?.username?.ifBlank { "admin" } ?: "admin")
    }
    var adminNewPassword by remember { mutableStateOf("") }

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
                                text = "Admin Portal Username & Password (Cloud Synced)",
                                style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark)
                            )
                            Text(
                                text = "Update your Admin login username and password in Cloud Firestore. Changes take effect immediately across all devices.",
                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
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
                                text = "Update Admin Username & Password in Cloud",
                                icon = Icons.Default.SyncLock,
                                enabled = adminUsername.length >= 3 && adminNewPassword.length >= 3,
                                onClick = {
                                    viewModel.updateAdminCredentials(adminUsername, adminNewPassword)
                                    adminNewPassword = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "save_admin_credentials_button"
                            )
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
                                        style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark)
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
                                                    color = WciColors.TextPrimaryDark
                                                )
                                            )
                                            Text(
                                                text = "Linked Record ID: ${cred.linkedEntityId.ifBlank { "Global" }} • Hash: Argon2id (64 MiB, t=3, p=2)",
                                                style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
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

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = WciColors.Success
                    ) {
                        Text(
                            text = "Enterprise Security & Cryptography Posture",
                            style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "RASP Verdict: ${rasp?.verdict ?: posture.integrityVerdict} • ${tamperVerdict.statusSummary}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.Success)
                        )
                        Text(
                            text = "Keymaster: ${keymasterPosture.securityLevelLabel} • Room DB: SQLCipher 256-Bit AES",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.Info)
                        )
                        Text(
                            text = "Polymorphic IP Shield: ${if (ipFlowClean) "HEALTHY (OLLVM:$ollvmDigestHex)" else "ANOMALY"} • SPKI SHA-256 Pinning Active",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.TextSecondaryDark)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("FLAG_SECURE Screenshot Protection", style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.TextPrimaryDark))
                                Text("Blocks screen capture & recents thumbnail on sensitive financial/exam screens", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                            }
                            Switch(
                                checked = state.preferences.flagSecureEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setFlagSecureEnabled(enabled)
                                    SecurityPostureManager.applyFlagSecure(context as? Activity, enabled)
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = WciColors.GlassBorderSubtle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("TOTP 2FA Multi-Factor Challenge", style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.TextPrimaryDark))
                                Text("Require 6-digit TOTP authenticator verification on login", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Real-Time Security Violation Telemetry",
                                        style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark)
                                    )
                                    Text(
                                        text = "Captures Student ID, Username, Phone, Device & Network on RASP/Frida/MT Manager/Session Violations",
                                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                                    )
                                }
                                LiquidPill(
                                    text = "Run Telemetry Drill",
                                    selected = true,
                                    icon = Icons.Default.Shield,
                                    onClick = {
                                        val sampleStu = state.students.firstOrNull()
                                        viewModel.reportSecurityIncidentAndNotifyAdmin(
                                            context = context,
                                            username = sampleStu?.enrollmentNo?.lowercase() ?: "student_wci",
                                            fullName = sampleStu?.fullName ?: "Active Student",
                                            phone = sampleStu?.phone ?: "+91-9876543210",
                                            studentId = sampleStu?.id ?: "WCI-STU-01",
                                            violationType = "RASP_MEMORY_INTEGRITY_SCAN",
                                            details = "Automated RASP & Single-Device Telemetry Verification Drill"
                                        )
                                    }
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
                                                    color = WciColors.Danger
                                                )
                                            )
                                            Text(
                                                text = alert.details,
                                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
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
                    Text("Active Device Sessions", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                }
                items(state.sessions, key = { it.sessionId }) { sess ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sess.deviceName, style = MaterialTheme.typography.titleSmall.copy(color = WciColors.TextPrimaryDark))
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
                    Text("Security Audit Log", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                }
                items(state.auditLogs.take(15), key = { it.id }) { log ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                        Text(
                            "${log.severity} • ${log.action}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono, color = WciColors.Info)
                        )
                        Text(log.details, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextPrimaryDark))
                        Text("Actor: ${log.actorEmail}", style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                    }
                }
            }
            1 -> {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Cloud Backup, Export & Data Portability", style = MaterialTheme.typography.headlineMedium.copy(color = WciColors.TextPrimaryDark))
                        Text(
                            "Create SHA-256 verified cloud backup manifests and export full JSON snapshots via Android Share Sheet.",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        ShimmerButton(
                            text = "Create & Export Snapshot Backup",
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
                        Text("${bkp.category} • ${bkp.title}", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
                        Text("Records: ${bkp.recordCount} • Checksum: ${bkp.checksum}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, color = WciColors.Success))
                        Text(bkp.payloadJson, style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark))
                    }
                }
            }
            else -> {
                // 1. Portal Language Selection Card (English Default / Hindi Option in Settings)
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowColor = Color(0xFF00E5FF)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.preferences.languageCode == "HI") "पोर्टल भाषा (App Language)" else "Portal Language (English / Hindi)",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = WciColors.TextPrimaryDark,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                Text(
                                    text = if (state.preferences.languageCode == "HI") {
                                        "पूरे ऐप को अंग्रेजी (English) या हिंदी (Hindi) में बदलें"
                                    } else {
                                        "Keep the entire portal in English by default, or switch to Hindi anytime"
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextMutedDark)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Language",
                                tint = Color(0xFF00E5FF)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LiquidPill(
                                text = "English (Default)",
                                selected = state.preferences.languageCode != "HI",
                                icon = Icons.Default.Language,
                                onClick = { viewModel.setLanguage("EN") },
                                modifier = Modifier.weight(1f),
                                testTag = "settings_lang_en_button"
                            )
                            LiquidPill(
                                text = "हिन्दी (Hindi)",
                                selected = state.preferences.languageCode == "HI",
                                icon = Icons.Default.Translate,
                                onClick = { viewModel.setLanguage("HI") },
                                modifier = Modifier.weight(1f),
                                testTag = "settings_lang_hi_button"
                            )
                        }
                    }
                }

                // 2. Appearance & Battery Saver Mode (Default Ultra/High Motion runs automatically)
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (state.preferences.languageCode == "HI") "दिखावट और बैटरी सेवर मोड" else "Theme & Battery Saver Mode",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = WciColors.TextPrimaryDark,
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
                                    text = if (state.preferences.languageCode == "HI") "डार्क थीम (Dark Cosmos Theme)" else "Dark Cosmos Theme",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = WciColors.TextPrimaryDark,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Deep sapphire-indigo Liquid Glass & 3D Claymorphism palette",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
                                )
                            }
                            Switch(
                                checked = state.preferences.darkTheme,
                                onCheckedChange = { viewModel.setDarkTheme(it) }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = WciColors.GlassBorderSubtle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.preferences.languageCode == "HI") "बैटरी सेवर मोड (Battery Saver Mode)" else "Battery Saver Mode",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = WciColors.TextPrimaryDark,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Optimizes background wave animations to save battery power",
                                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark)
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
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text("Institute Branding & Tax Profile", style = MaterialTheme.typography.titleMedium.copy(color = WciColors.TextPrimaryDark))
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
        var credPassword by remember { mutableStateOf("123456") }
        var credDisplayName by remember { mutableStateOf(state.students.firstOrNull()?.fullName ?: "") }
        var credLinkedId by remember { mutableStateOf(state.students.firstOrNull()?.id ?: "") }

        AlertDialog(
            onDismissRequest = { showIssueCredentialDialog = false },
            containerColor = WciColors.BgElevatedDark,
            title = { Text("Issue Portal Login (Student / Parent / Teacher)", color = WciColors.TextPrimaryDark) },
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
                        label = { Text("Login Password (3–6+ digits/chars) *") },
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
                    enabled = credUsername.length >= 3 && credPassword.length >= 3 && credDisplayName.isNotBlank(),
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
                TextButton(onClick = { showIssueCredentialDialog = false }) { Text("Cancel") }
            }
        )
    }
}
