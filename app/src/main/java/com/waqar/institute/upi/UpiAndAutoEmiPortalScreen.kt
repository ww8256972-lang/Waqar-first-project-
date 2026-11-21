package com.waqar.institute.upi

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.PortalRole
import com.example.ui.designsystem.WciColors
import com.example.ui.theme.*
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val EmeraldGreen = WciColors.SuccessNeon
private val ElectricCyan = WciColors.Info
private val GoldAmber = WciColors.Warning
private val CoralRose = WciColors.Danger
private val NeonPurple = WciColors.PurpleVibrant

/**
 * Full-screen composable for the Admin Side Menu Bar (`M27_UPI_EMI_COMMAND`) and Student/Parent portals.
 */
@Composable
fun UpiAndAutoEmiVaultScreen(
    uiState: InstituteUiState,
    viewModel: InstituteViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        UpiAndAutoEmiEmbeddedSection(
            uiState = uiState,
            viewModel = viewModel
        )
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Reusable section embedded both in the Admin Side Menu Bar (`M27_UPI_EMI_COMMAND`)
 * and at the top of `M08_FEES` (`FinanceAndPayrollModuleScreen`).
 */
@Composable
fun UpiAndAutoEmiEmbeddedSection(
    uiState: InstituteUiState,
    viewModel: InstituteViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAdmin = uiState.activePortalRole == PortalRole.ADMIN
    val fsmState by ZeroFeeUpiPaymentEngine.fsmState.collectAsStateWithLifecycle()

    val installedApps = remember(context) {
        ZeroFeeUpiPaymentEngine.detectInstalledWhitelistedUpiApps(context)
    }
    var selectedUpiPackage by remember {
        mutableStateOf(
            installedApps.firstOrNull { it.isInstalled }?.packageName
                ?: ZeroFeeUpiPaymentEngine.OFFICIAL_UPI_PACKAGE_CATALOG.first().packageName
        )
    }

    // ActivityResultLauncher that automatically receives the UPI app response and extracts UTR without manual typing
    val upiIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        scope.launch {
            val actorUid = uiState.userProfile?.uid ?: "student_portal"
            val verification = ZeroFeeUpiPaymentEngine.verifyIntentResultAndSubmitPending(
                context = context,
                intentData = result.data,
                actorUid = actorUid
            )
            verification.onSuccess { record ->
                viewModel.recordVerifiedPendingUpiTransaction(record)
            }.onFailure { err ->
                viewModel.postMessage("UPI Verification Notice: ${err.localizedMessage ?: "Cancelled or invalid response"}")
            }
        }
    }

    // Merchant Identity & 5-Layer Zero-Fraud Security Header Card
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF0A2E36),
                        Color(0xFF0F172A),
                        Color(0xFF1E1B4B)
                    )
                )
            )
            .border(1.dp, EmeraldGreen.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(EmeraldGreen.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = "Zero-Fee Direct UPI Shield",
                            tint = EmeraldGreen
                        )
                    }
                    Column {
                        Text(
                            text = "ZERO-FEE DIRECT UPI INTENT & AUTO-EMI VAULT",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldGreen,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = ZeroFeeUpiPaymentEngine.MERCHANT_NAME,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Surface(
                    color = ElectricCyan.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "0% Gateway Fee",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecurityLayerPill("Merchant UPI: ${ZeroFeeUpiPaymentEngine.MERCHANT_VPA}", GoldAmber)
                SecurityLayerPill("L1: Official UPI Whitelist", ElectricCyan)
                SecurityLayerPill("L2: HMAC-SHA256 + 120s Nonce", EmeraldGreen)
                SecurityLayerPill("L3: Single-Use UTR Lock", NeonPurple)
                SecurityLayerPill("L4: 1-Tap Admin Confirm", CoralRose)
            }
        }
    }

    if (isAdmin) {
        AdminUpiAndAutoEmiCommandSection(
            uiState = uiState,
            viewModel = viewModel
        )
    }

    // Student & Parent Direct UPI Payment + Active Auto-EMI Deduction Section
    // (Also visible to Admin at the bottom as a Live Student Payment Preview / Desk Terminal)
    StudentAndParentUpiPaymentSection(
        uiState = uiState,
        viewModel = viewModel,
        installedApps = installedApps,
        selectedUpiPackage = selectedUpiPackage,
        onSelectUpiPackage = { selectedUpiPackage = it },
        fsmState = fsmState,
        onLaunchSignedUpiPayment = { ledger, amountPaise, paymentType ->
            scope.launch {
                val actorUid = uiState.userProfile?.uid ?: ledger.student_id
                val challengeRes = ZeroFeeUpiPaymentEngine.issueSignedPaymentChallenge(
                    context = context,
                    studentId = ledger.student_id,
                    studentName = ledger.student_name,
                    courseName = ledger.course_name,
                    amountPaise = amountPaise,
                    paymentType = paymentType,
                    preferredPackage = selectedUpiPackage,
                    actorUid = actorUid
                )
                challengeRes.onSuccess { challenge ->
                    val intentRes = ZeroFeeUpiPaymentEngine.buildWhitelistedLaunchIntent(
                        challenge = challenge,
                        selectedPackage = selectedUpiPackage
                    )
                    intentRes.onSuccess { launchIntent ->
                        val canResolveSelected = runCatching {
                            launchIntent.resolveActivity(context.packageManager) != null
                        }.getOrDefault(false)
                        if (canResolveSelected) {
                            upiIntentLauncher.launch(launchIntent)
                        } else {
                            val fallbackInstalledApp = ZeroFeeUpiPaymentEngine
                                .detectInstalledWhitelistedUpiApps(context)
                                .firstOrNull { it.isInstalled }
                            if (fallbackInstalledApp != null) {
                                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                                    data = android.net.Uri.parse(challenge.upiUriString)
                                    setPackage(fallbackInstalledApp.packageName)
                                }
                                selectedUpiPackage = fallbackInstalledApp.packageName
                                upiIntentLauncher.launch(fallbackIntent)
                            } else {
                                ZeroFeeUpiPaymentEngine.markLaunchBlockedNoUpiApp(selectedUpiPackage)
                                viewModel.postMessage(
                                    "Please install Google Pay, PhonePe, Paytm, or BHIM to complete this UPI payment."
                                )
                            }
                        }
                    }.onFailure { err ->
                        viewModel.postMessage("UPI Launch Blocked: ${err.localizedMessage}")
                    }
                }.onFailure { err ->
                    viewModel.postMessage("Security Challenge Blocked: ${err.localizedMessage}")
                }
            }
        }
    )
}

@Composable
private fun SecurityLayerPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AdminUpiAndAutoEmiCommandSection(
    uiState: InstituteUiState,
    viewModel: InstituteViewModel
) {
    val context = LocalContext.current
    var adminTab by remember { mutableIntStateOf(0) }
    var merchantAlertPasteText by remember { mutableStateOf("") }

    val pendingTxns = remember(uiState.upiTransactions) {
        uiState.upiTransactions.filter { it.status == "PENDING_APPROVAL" }
    }
    val verifiedTxns = remember(uiState.upiTransactions) {
        uiState.upiTransactions.filter { it.status == "VERIFIED_PAID" }
    }
    val verifiedTotalInr = remember(verifiedTxns) {
        verifiedTxns.sumOf { it.amount_paise } / 100L
    }
    val fourMonthOverdueCount = remember(uiState.studentFeesLedger) {
        uiState.studentFeesLedger.count { it.overdue_months >= 4 }
    }
    val activeAutoEmiCount = remember(uiState.studentFeesLedger) {
        uiState.studentFeesLedger.count { it.auto_emi_active }
    }

    // Admin KPI Summary Row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AdminKpiMiniCard(
            title = "Pending 1-Tap Confirm",
            value = "${pendingTxns.size}",
            subtitle = "Auto-Captured UTRs",
            accent = GoldAmber
        )
        AdminKpiMiniCard(
            title = "Verified UPI Collected",
            value = "₹$verifiedTotalInr",
            subtitle = "${verifiedTxns.size} Verified Txns",
            accent = EmeraldGreen
        )
        AdminKpiMiniCard(
            title = "4+ Months Overdue",
            value = "$fourMonthOverdueCount",
            subtitle = "Auto-EMI Eligible",
            accent = CoralRose
        )
        AdminKpiMiniCard(
            title = "Active Auto-EMI",
            value = "$activeAutoEmiCount",
            subtitle = "Monthly Scheduled",
            accent = ElectricCyan
        )
    }

    // Admin Sub-Navigation Tabs
    ScrollableTabRow(
        selectedTabIndex = adminTab,
        edgePadding = 0.dp,
        containerColor = Color.Transparent
    ) {
        listOf(
            "1-Tap UPI Approvals (${pendingTxns.size})",
            "4-Month Overdue & Auto-EMI (${uiState.studentFeesLedger.size})",
            "5-Layer Security Logs (${uiState.instituteAuditLogs.size})"
        ).forEachIndexed { idx, title ->
            Tab(
                selected = adminTab == idx,
                onClick = { adminTab = idx },
                modifier = Modifier.testTag("admin_upi_tab_$idx"),
                text = {
                    Text(
                        text = title,
                        fontWeight = if (adminTab == idx) FontWeight.ExtraBold else FontWeight.Medium
                    )
                }
            )
        }
    }

    when (adminTab) {
        0 -> {
            // Layer 4: Merchant Notification Auto-Reconciler Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Layer 4: Merchant Alert / UTR Quick-Reconciler",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Paste a Paytm Business / PhonePe Business / Bank alert or 12-digit UTR to match against Pending Approvals.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = merchantAlertPasteText,
                            onValueChange = { merchantAlertPasteText = it },
                            label = { Text("Paste Paytm/PhonePe Business Alert or 12-Digit UTR") },
                            placeholder = { Text("e.g. Received Rs. 1500.00 from student, UTR: 432198765432") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("merchant_alert_paste_input")
                        )
                        Button(
                            onClick = {
                                viewModel.adminReconcileMerchantAlertText(merchantAlertPasteText)
                                merchantAlertPasteText = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                            modifier = Modifier.testTag("auto_match_utr_btn")
                        ) {
                            Icon(Icons.Default.FactCheck, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Match")
                        }
                    }
                }
            }

            // Pending Approval List
            if (pendingTxns.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = "Zero Pending UPI Approvals",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "When a Student or Parent completes a UPI Intent payment to ${ZeroFeeUpiPaymentEngine.MERCHANT_VPA}, the app automatically extracts the 12-digit UTR and places it here for your 1-Tap Confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val approvalHostActivity = androidx.activity.compose.LocalActivity.current
                pendingTxns.forEach { txn ->
                    PendingUpiApprovalCard(
                        txn = txn,
                        onApprove = {
                            com.waqar.institute.biometric.HardwareBiometricAttestor.signCriticalAdminActionWithCryptoObject(
                                activity = approvalHostActivity,
                                actionType = "UPI_FEE_APPROVED",
                                canonicalPayload = "${txn.id}|${txn.student_id}|${txn.amount_paise}|${txn.utr_number}",
                                onSigned = { sig ->
                                    viewModel.adminApproveUpiTransaction(txn, biometricActionSignatureHex = sig.signatureHex)
                                },
                                onFallback = { sig ->
                                    viewModel.adminApproveUpiTransaction(txn, biometricActionSignatureHex = sig.signatureHex)
                                }
                            )
                        },
                        onReject = { viewModel.adminRejectUpiTransaction(txn) }
                    )
                }
            }

            // Recent Verified / Rejected UPI Transactions
            val processedTxns = remember(uiState.upiTransactions) {
                uiState.upiTransactions.filter { it.status != "PENDING_APPROVAL" }.take(15)
            }
            if (processedTxns.isNotEmpty()) {
                Text(
                    text = "Recently Processed UPI Intent Transactions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )
                processedTxns.forEach { txn ->
                    ProcessedUpiTransactionRow(txn = txn)
                }
            }
        }

        1 -> {
            // Admin 4-Month Overdue & Auto-EMI Master Control
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = NeonPurple.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "4-Month Overdue Auto-EMI & Monthly Fee Master Control",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonPurple
                    )
                    Text(
                        text = "• Every student has a Monthly Fee option in Student & Parent Portals.\n" +
                            "• When a student reaches 4+ Months Overdue (or when Admin turns ON Auto-EMI and sets a Monthly Billing Date 1–28), the Auto-EMI System activates in Student & Parent Portals and sends automatic monthly deduction alerts on that billing date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.studentFeesLedger.isEmpty()) {
                Text(
                    text = "No students enrolled yet. Add students in Student Directory to manage Monthly Fee & Auto-EMI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                uiState.studentFeesLedger.forEach { ledger ->
                    AdminStudentAutoEmiControlCard(
                        ledger = ledger,
                        onSaveConfig = { monthlyInr, emiInr, overdueMonths, autoEmiActive, billingDay ->
                            viewModel.configureStudentAutoEmiAndMonthlyFee(
                                studentId = ledger.student_id,
                                monthlyFeeInr = monthlyInr,
                                emiInstallmentInr = emiInr,
                                overdueMonths = overdueMonths,
                                autoEmiActive = autoEmiActive,
                                billingDayOfMonth = billingDay
                            )
                        },
                        onTriggerMonthlyAlert = {
                            viewModel.triggerMonthlyEmiDeductionNotice(ledger)
                        }
                    )
                }
            }
        }

        2 -> {
            // 5-Layer Cryptographic Security & Duplicate UTR Audit Logs
            if (uiState.instituteAuditLogs.isEmpty()) {
                Text(
                    text = "No UPI security audit logs recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                uiState.instituteAuditLogs.take(40).forEach { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = log.event_type,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = when (log.severity) {
                                        "CRITICAL" -> CoralRose
                                        "WARNING" -> GoldAmber
                                        else -> EmeraldGreen
                                    }
                                )
                                Text(
                                    text = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date(log.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = log.details,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (log.utr_number.isNotBlank() || log.intent_hash.isNotBlank()) {
                                Text(
                                    text = "UTR: ${log.utr_number.ifBlank { "N/A" }} • Device: ${log.device_fingerprint} • HMAC: ${log.intent_hash.take(16)}...",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = ElectricCyan
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingUpiApprovalCard(
    txn: UpiTransactionRecord,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, GoldAmber.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = GoldAmber.copy(alpha = 0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${txn.student_name} • ${txn.course_name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Type: ${txn.payment_type} • App: ${txn.upi_package.substringAfterLast('.')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "₹${txn.amount_paise / 100}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = EmeraldGreen
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Auto-Captured UTR (No Manual Entry): ${txn.utr_number}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = GoldAmber
                    )
                    Text(
                        text = "Signed Ref (tr): ${txn.tr_ref} • HMAC-SHA256: ${txn.intent_hash.take(18)}...",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = ElectricCyan
                    )
                    Text(
                        text = "Hardware Device: ${txn.device_fingerprint} • Single-Use UTR Lock: ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("admin_1tap_approve_upi_${txn.id}"),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                ) {
                    Icon(Icons.Default.Verified, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("1-Tap Confirm & Issue Receipt", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.testTag("admin_reject_upi_${txn.id}"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRose)
                ) {
                    Icon(Icons.Default.Block, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reject Fraud")
                }
            }
        }
    }
}

@Composable
private fun ProcessedUpiTransactionRow(txn: UpiTransactionRecord) {
    val isPaid = txn.status == "VERIFIED_PAID"
    val badgeColor = if (isPaid) EmeraldGreen else CoralRose
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${txn.student_name} • ₹${txn.amount_paise / 100} (${txn.payment_type})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "UTR: ${txn.utr_number} • Ref: ${txn.tr_ref}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                color = badgeColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = txn.status,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AdminStudentAutoEmiControlCard(
    ledger: StudentFeesLedgerRecord,
    onSaveConfig: (monthlyInr: Long, emiInr: Long, overdueMonths: Int, autoEmiActive: Boolean, billingDay: Int) -> Unit,
    onTriggerMonthlyAlert: () -> Unit
) {
    var expanded by remember { mutableStateOf(ledger.overdue_months >= 4 || ledger.auto_emi_active) }
    var monthlyFeeInput by remember(ledger.monthly_fee_paise) {
        mutableStateOf((ledger.monthly_fee_paise / 100L).toString())
    }
    var emiInstallmentInput by remember(ledger.emi_installment_paise) {
        mutableStateOf((ledger.emi_installment_paise / 100L).toString())
    }
    var overdueMonths by remember(ledger.overdue_months) {
        mutableIntStateOf(ledger.overdue_months)
    }
    var billingDayInput by remember(ledger.emi_billing_date) {
        mutableStateOf(ledger.emi_billing_date.toString())
    }
    var autoEmiEnabled by remember(ledger.auto_emi_active) {
        mutableStateOf(ledger.auto_emi_active)
    }

    val isEligibleFourMonths = overdueMonths >= 4
    val borderCol = when {
        autoEmiEnabled -> ElectricCyan
        isEligibleFourMonths -> CoralRose
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderCol.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${ledger.student_name} (${ledger.enrollment_no.ifBlank { ledger.student_id }})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${ledger.course_name} • Balance: ₹${ledger.balancePaise / 100} • Billing Day: ${ledger.emi_billing_date}th",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = (if (isEligibleFourMonths) CoralRose else GoldAmber).copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "$overdueMonths Mo Overdue",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isEligibleFourMonths) CoralRose else GoldAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Switch(
                        checked = autoEmiEnabled,
                        onCheckedChange = { checked ->
                            autoEmiEnabled = checked
                            onSaveConfig(
                                monthlyFeeInput.toLongOrNull() ?: 1500L,
                                emiInstallmentInput.toLongOrNull() ?: 1500L,
                                overdueMonths,
                                checked,
                                billingDayInput.toIntOrNull() ?: 5
                            )
                        },
                        modifier = Modifier.testTag("toggle_auto_emi_${ledger.student_id}")
                    )
                }
            }

            if (isEligibleFourMonths && !autoEmiEnabled) {
                Surface(
                    color = CoralRose.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "4+ Months Overdue ($overdueMonths Months)! Ready for Admin Auto-EMI Activation.",
                            style = MaterialTheme.typography.labelSmall,
                            color = CoralRose,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                autoEmiEnabled = true
                                onSaveConfig(
                                    monthlyFeeInput.toLongOrNull() ?: 1500L,
                                    emiInstallmentInput.toLongOrNull() ?: 1500L,
                                    overdueMonths,
                                    true,
                                    billingDayInput.toIntOrNull() ?: 5
                                )
                            }
                        ) {
                            Text("Enable Auto-EMI Now", fontWeight = FontWeight.ExtraBold, color = ElectricCyan)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Overdue Months Quick Stepper
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Overdue Months Counter: $overdueMonths Months",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { overdueMonths = (overdueMonths - 1).coerceAtLeast(0) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("-1 Mo") }
                            OutlinedButton(
                                onClick = { overdueMonths = (overdueMonths + 1).coerceAtMost(60) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("+1 Mo") }
                            Button(
                                onClick = {
                                    overdueMonths = 4
                                    autoEmiEnabled = true
                                    onSaveConfig(
                                        monthlyFeeInput.toLongOrNull() ?: 1500L,
                                        emiInstallmentInput.toLongOrNull() ?: 1500L,
                                        4,
                                        true,
                                        billingDayInput.toIntOrNull() ?: 5
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CoralRose),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("set_4mo_overdue_auto_emi_${ledger.student_id}")
                            ) {
                                Text("Set 4-Mo + Auto-EMI", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = monthlyFeeInput,
                            onValueChange = { monthlyFeeInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Monthly Fee (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = emiInstallmentInput,
                            onValueChange = { emiInstallmentInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Auto-EMI Amt (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = billingDayInput,
                            onValueChange = { billingDayInput = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text("Billing Day (1-28)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onSaveConfig(
                                    monthlyFeeInput.toLongOrNull() ?: 1500L,
                                    emiInstallmentInput.toLongOrNull() ?: 1500L,
                                    overdueMonths,
                                    autoEmiEnabled,
                                    (billingDayInput.toIntOrNull() ?: 5).coerceIn(1, 28)
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save Schedule")
                        }
                        OutlinedButton(
                            onClick = onTriggerMonthlyAlert,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.NotificationsActive, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Send Billing Alert")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudentAndParentUpiPaymentSection(
    uiState: InstituteUiState,
    viewModel: InstituteViewModel,
    installedApps: List<WhitelistedUpiApp>,
    selectedUpiPackage: String,
    onSelectUpiPackage: (String) -> Unit,
    fsmState: UpiPaymentFsmState,
    onLaunchSignedUpiPayment: (ledger: StudentFeesLedgerRecord, amountPaise: Long, paymentType: String) -> Unit
) {
    val ledgers = uiState.studentFeesLedger
    var selectedStudentId by remember(ledgers) {
        mutableStateOf(ledgers.firstOrNull()?.student_id.orEmpty())
    }
    val activeLedger = ledgers.firstOrNull { it.student_id == selectedStudentId }
        ?: ledgers.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (uiState.activePortalRole == PortalRole.ADMIN) {
                    "Student & Parent Portal Live UPI Payment Terminal"
                } else {
                    "Pay Monthly Fee & Auto-EMI Installment (Zero-Fee Direct UPI)"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )

            // Student Selector if multiple students are visible (Admin or Parent with multiple children)
            if (ledgers.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ledgers.take(15).forEach { item ->
                        FilterChip(
                            selected = item.student_id == activeLedger?.student_id,
                            onClick = { selectedStudentId = item.student_id },
                            label = {
                                Text("${item.student_name} (${item.overdue_months}m)")
                            }
                        )
                    }
                }
            }

            // Layer 1: Strict Whitelisted UPI App Selector
            Text(
                text = "Select Official Whitelisted UPI App (Spoofed/Unlisted Apps Blocked):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                installedApps.forEach { app ->
                    FilterChip(
                        selected = selectedUpiPackage == app.packageName,
                        onClick = { onSelectUpiPackage(app.packageName) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (selectedUpiPackage == app.packageName) Icons.Default.CheckCircle else Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = app.shortBadge + if (app.isInstalled) " ✓" else "",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
            }

            if (activeLedger == null) {
                Text(
                    text = "No student fee ledger linked to this account yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Option 1: Separate Monthly Fee Card (Always visible for every student in Student & Parent portals)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, EmeraldGreen.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = EmeraldGreen.copy(alpha = 0.08f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MONTHLY COURSE FEE • ${activeLedger.student_name}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "${activeLedger.course_name} • Due Date: ${activeLedger.next_due_date_iso} (Day ${activeLedger.emi_billing_date})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "₹${activeLedger.monthly_fee_paise / 100}/mo",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = EmeraldGreen
                            )
                        }

                        Text(
                            text = "Total Fee: ₹${activeLedger.total_fee_paise / 100} • Paid: ₹${activeLedger.paid_fee_paise / 100} • Remaining: ₹${activeLedger.balancePaise / 100}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = {
                                onLaunchSignedUpiPayment(
                                    activeLedger,
                                    activeLedger.monthly_fee_paise.coerceAtLeast(10000L),
                                    "MONTHLY_FEE"
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pay_monthly_fee_upi_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                        ) {
                            Icon(Icons.Default.SendToMobile, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Pay Monthly Fee (₹${activeLedger.monthly_fee_paise / 100}) via Direct UPI Intent",
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }

                // Option 2: 4-Month Overdue & Admin-Controlled Auto-EMI Deduction Card
                if (activeLedger.auto_emi_active || activeLedger.overdue_months >= 4) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, CoralRose.copy(alpha = 0.8f), RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = CoralRose.copy(alpha = 0.1f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (activeLedger.auto_emi_active) {
                                            "AUTO-EMI SYSTEM ACTIVE • BILLING DAY ${activeLedger.emi_billing_date} OF MONTH"
                                        } else {
                                            "4+ MONTHS OVERDUE (${activeLedger.overdue_months} MO) • AUTO-EMINOTICE"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CoralRose,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = activeLedger.last_emi_notice_iso,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "₹${activeLedger.emi_installment_paise / 100}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CoralRose
                                )
                            }

                            Button(
                                onClick = {
                                    onLaunchSignedUpiPayment(
                                        activeLedger,
                                        activeLedger.emi_installment_paise.coerceAtLeast(10000L),
                                        "AUTO_EMI"
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pay_auto_emi_upi_btn"),
                                colors = ButtonDefaults.buttonColors(containerColor = CoralRose)
                            ) {
                                Icon(Icons.Default.Autorenew, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Pay Auto-EMI Installment (₹${activeLedger.emi_installment_paise / 100}) Now",
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            val linkedStudentForWa = uiState.students.firstOrNull {
                                it.id == activeLedger.student_id || it.enrollmentNo == activeLedger.enrollment_no
                            }
                            val guardianPhoneForWa = linkedStudentForWa?.guardianPhone?.ifBlank { linkedStudentForWa.phone }.orEmpty()
                            if (guardianPhoneForWa.isNotBlank()) {
                                val waContext = androidx.compose.ui.platform.LocalContext.current
                                OutlinedButton(
                                    onClick = {
                                        com.waqar.institute.communication.WhatsAppInstituteAlertHelper.sendFeeOrAutoEmiAlert(
                                            context = waContext,
                                            guardianPhone = guardianPhoneForWa,
                                            studentName = activeLedger.student_name,
                                            amountInr = (activeLedger.emi_installment_paise / 100L).coerceAtLeast(1L),
                                            dueDateIso = activeLedger.next_due_date_iso
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("whatsapp_auto_emi_alert_btn")
                                ) {
                                    Icon(Icons.Default.SendToMobile, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("1-Tap WhatsApp Auto-EMI Reminder to Parent", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Live Cryptographic FSM Status Banner
            when (val st = fsmState) {
                is UpiPaymentFsmState.Idle -> {}
                is UpiPaymentFsmState.Challenge -> {
                    FsmStatusBanner(
                        title = "FSM State: CHALLENGE ISSUED (120s Window)",
                        subtitle = "HMAC-SHA256 Ref: ${st.challenge.trRef}",
                        color = ElectricCyan
                    )
                }
                is UpiPaymentFsmState.Dispatched -> {
                    FsmStatusBanner(
                        title = "FSM State: DISPATCHED TO ${st.packageName}",
                        subtitle = "Waiting for automatic UTR return (120-second entropy window active)...",
                        color = GoldAmber
                    )
                }
                is UpiPaymentFsmState.Verifying -> {
                    FsmStatusBanner(
                        title = "FSM State: VERIFYING HMAC-SHA256 & SINGLE-USE UTR LOCK",
                        subtitle = "Checking ${st.challenge.trRef} on Dispatchers.IO...",
                        color = ElectricCyan
                    )
                }
                is UpiPaymentFsmState.PendingApproval -> {
                    FsmStatusBanner(
                        title = "UTR ${st.transaction.utr_number} AUTO-CAPTURED -> SENT TO ADMIN PENDING APPROVAL",
                        subtitle = st.message,
                        color = EmeraldGreen
                    )
                }
                is UpiPaymentFsmState.VerifiedPaid -> {
                    FsmStatusBanner(
                        title = "PAYMENT VERIFIED PAID • RECEIPT ${st.receiptNo}",
                        subtitle = "UTR: ${st.transaction.utr_number} confirmed by Admin.",
                        color = EmeraldGreen
                    )
                }
                is UpiPaymentFsmState.Expired -> {
                    FsmStatusBanner(
                        title = "CHALLENGE EXPIRED (>120 SECONDS)",
                        subtitle = st.reason,
                        color = CoralRose
                    )
                }
                is UpiPaymentFsmState.Failed -> {
                    FsmStatusBanner(
                        title = "SECURITY / PAYMENT BLOCKED (${st.errorCode})",
                        subtitle = st.reason,
                        color = CoralRose
                    )
                }
            }

            // Student's Live UPI Transaction Status History
            val studentTxns = remember(uiState.upiTransactions, activeLedger) {
                if (activeLedger == null) uiState.upiTransactions.take(5)
                else uiState.upiTransactions.filter { it.student_id == activeLedger.student_id }.take(8)
            }
            if (studentTxns.isNotEmpty()) {
                Text(
                    text = "Live UPI Intent Verification Status (Real-Time Cloud Sync):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                studentTxns.forEach { txn ->
                    ProcessedUpiTransactionRow(txn = txn)
                }
            }
        }
    }
}

@Composable
private fun FsmStatusBanner(
    title: String,
    subtitle: String,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = color
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AdminKpiMiniCard(
    title: String,
    value: String,
    subtitle: String,
    accent: Color
) {
    Card(
        modifier = Modifier.width(172.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                fontSize = 20.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
