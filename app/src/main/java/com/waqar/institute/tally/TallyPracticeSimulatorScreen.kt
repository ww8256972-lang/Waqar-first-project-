package com.waqar.institute.tally

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.designsystem.JetBrainsMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Authentic TallyPrime Desktop Palette
private val TallyTealRibbon = Color(0xFF004D40)
private val TallyTealDark = Color(0xFF00332B)
private val TallyWorkspaceBg = Color(0xFFF4FBF7)
private val TallyPanelSurface = Color(0xFFFFFFFF)
private val TallyBorderMint = Color(0xFFB2DFDB)
private val TallySelectionGold = Color(0xFFFDE047)
private val TallyTextDark = Color(0xFF0F172A)
private val TallyTextMuted = Color(0xFF475569)

/**
 * Isolated 100% Offline Tally Practice Simulator (`TallyPracticeSimulatorScreen.kt`).
 *
 * Authentic desktop TallyPrime aesthetic with Gateway of Tally split screen, persistent
 * interactive Function Key Strip (F4..F9, Alt+F3, Ctrl+A, Esc) supporting both touch taps
 * and hardware/OTG keyboard shortcuts via `onPreviewKeyEvent`.
 */
@Composable
fun TallyPracticeSimulatorScreen() {
    val context = LocalContext.current
    val vm: TallySimulatorViewModel = viewModel(factory = TallySimulatorViewModel.Factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    var pendingCtrlASaveTrigger by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TallyWorkspaceBg)
            .testTag("tally_practice_simulator_screen")
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    ev.key == Key.F4 -> { vm.openVoucherMode("CONTRA"); true }
                    ev.key == Key.F5 -> { vm.openVoucherMode("PAYMENT"); true }
                    ev.key == Key.F6 -> { vm.openVoucherMode("RECEIPT"); true }
                    ev.key == Key.F7 -> { vm.openVoucherMode("JOURNAL"); true }
                    ev.key == Key.F8 -> { vm.openVoucherMode("SALES"); true }
                    ev.key == Key.F9 -> { vm.openVoucherMode("PURCHASE"); true }
                    ev.key == Key.F3 && ev.isAltPressed -> { vm.navigateTo(TallyScreenMode.COMPANY_MANAGER); true }
                    ev.key == Key.A && ev.isCtrlPressed -> { pendingCtrlASaveTrigger++; true }
                    ev.key == Key.Escape -> { vm.navigateTo(TallyScreenMode.GATEWAY); true }
                    else -> false
                }
            }
    ) {
        // 1. Authentic TallyPrime Deep Teal Top Ribbon (#004D40)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TallyTealRibbon)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TallyPrime Practice • OFFLINE SANDBOX",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = TallySelectionGold,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = JetBrainsMono
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.activeCompany?.let { "${it.name} (${it.stateName}) • FY ${it.financialYearFrom}" }
                        ?: "No Company Loaded — Tap Alt+F3 to Create Your Practice Company",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.White.copy(alpha = 0.92f),
                        fontFamily = JetBrainsMono
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.activeCompany != null && state.screenMode in setOf(
                    TallyScreenMode.DAY_BOOK,
                    TallyScreenMode.TRIAL_BALANCE,
                    TallyScreenMode.PROFIT_AND_LOSS,
                    TallyScreenMode.BALANCE_SHEET,
                    TallyScreenMode.STOCK_SUMMARY,
                    TallyScreenMode.GST_SUMMARY
                )
            ) {
                IconButton(
                    onClick = { vm.exportCurrentReportPdf(context) },
                    modifier = Modifier.testTag("tally_export_pdf_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = "Export Report PDF",
                        tint = TallySelectionGold
                    )
                }
            }
        }

        // 2. Interactive Function Key Strip (Touch + Hardware Keyboard)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TallyTealDark)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TallyFunctionKeyChip("F4", "Contra", state.screenMode == TallyScreenMode.VOUCHER_ENTRY && state.activeVoucherType == "CONTRA") {
                vm.openVoucherMode("CONTRA")
            }
            TallyFunctionKeyChip("F5", "Payment", state.screenMode == TallyScreenMode.VOUCHER_ENTRY && state.activeVoucherType == "PAYMENT") {
                vm.openVoucherMode("PAYMENT")
            }
            TallyFunctionKeyChip("F6", "Receipt", state.screenMode == TallyScreenMode.VOUCHER_ENTRY && state.activeVoucherType == "RECEIPT") {
                vm.openVoucherMode("RECEIPT")
            }
            TallyFunctionKeyChip("F7", "Journal", state.screenMode == TallyScreenMode.VOUCHER_ENTRY && state.activeVoucherType == "JOURNAL") {
                vm.openVoucherMode("JOURNAL")
            }
            TallyFunctionKeyChip("F8", "Sales", state.screenMode == TallyScreenMode.VOUCHER_ENTRY && state.activeVoucherType == "SALES") {
                vm.openVoucherMode("SALES")
            }
            TallyFunctionKeyChip("F9", "Purchase", state.screenMode == TallyScreenMode.VOUCHER_ENTRY && state.activeVoucherType == "PURCHASE") {
                vm.openVoucherMode("PURCHASE")
            }
            TallyFunctionKeyChip("Alt+F3", "Company", state.screenMode == TallyScreenMode.COMPANY_MANAGER) {
                vm.navigateTo(TallyScreenMode.COMPANY_MANAGER)
            }
            TallyFunctionKeyChip("Ctrl+A", "Accept", false) {
                pendingCtrlASaveTrigger++
            }
            TallyFunctionKeyChip("Esc", "Gateway", state.screenMode == TallyScreenMode.GATEWAY) {
                vm.navigateTo(TallyScreenMode.GATEWAY)
            }
        }

        // Status Feedback Ribbon
        state.statusBanner?.let { banner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TallySelectionGold)
                    .clickable { vm.clearBanner() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = TallyTextDark,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text("✕", color = TallyTextDark, fontWeight = FontWeight.ExtraBold)
            }
        }

        // 3. Main Workspace Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            when (state.screenMode) {
                TallyScreenMode.GATEWAY -> GatewayOfTallyPane(state = state, vm = vm)
                TallyScreenMode.COMPANY_MANAGER -> CompanyManagerPane(state = state, vm = vm, saveTrigger = pendingCtrlASaveTrigger)
                TallyScreenMode.CREATE_LEDGER -> CreateLedgerPane(state = state, vm = vm, saveTrigger = pendingCtrlASaveTrigger)
                TallyScreenMode.CREATE_INVENTORY -> CreateInventoryPane(state = state, vm = vm, saveTrigger = pendingCtrlASaveTrigger)
                TallyScreenMode.VOUCHER_ENTRY -> VoucherEntryPane(state = state, vm = vm, saveTrigger = pendingCtrlASaveTrigger)
                TallyScreenMode.DAY_BOOK -> DayBookPane(state = state, vm = vm)
                TallyScreenMode.TRIAL_BALANCE -> TrialBalancePane(state = state)
                TallyScreenMode.PROFIT_AND_LOSS -> ProfitAndLossPane(state = state)
                TallyScreenMode.BALANCE_SHEET -> BalanceSheetPane(state = state)
                TallyScreenMode.STOCK_SUMMARY -> StockSummaryPane(state = state)
                TallyScreenMode.GST_SUMMARY -> GstSummaryPane(state = state)
            }
        }
    }
}

@Composable
private fun TallyFunctionKeyChip(
    keyCode: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(
                color = if (active) TallySelectionGold else Color(0xFF00695C),
                shape = RoundedCornerShape(4.dp)
            )
            .border(1.dp, TallyBorderMint.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = keyCode,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.ExtraBold,
                color = if (active) TallyTextDark else TallySelectionGold
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (active) TallyTextDark else Color.White,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun GatewayOfTallyPane(
    state: TallySimulatorUiState,
    vm: TallySimulatorViewModel
) {
    val activeCompany = state.activeCompany
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            // Left/Top Current Company Summary Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TallyPanelSurface, RoundedCornerShape(6.dp))
                    .border(1.dp, TallyBorderMint, RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AccountBalance, contentDescription = null, tint = TallyTealRibbon)
                        Text(
                            text = "CURRENT COMPANY",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = JetBrainsMono,
                                color = TallyTealRibbon,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                    }
                    TextButton(
                        onClick = { vm.navigateTo(TallyScreenMode.COMPANY_MANAGER) },
                        modifier = Modifier.testTag("tally_create_or_select_company_button")
                    ) {
                        Text("Alt+F3 Company", color = TallyTealRibbon, fontWeight = FontWeight.Bold)
                    }
                }
                if (activeCompany == null) {
                    Text(
                        text = "No practice company created yet. This sandbox starts 100% empty with zero dummy records. Tap 'Create Practice Company' to initialize 28 standard Tally Account Groups, Cash & Profit & Loss A/c.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TallyTextMuted)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { vm.navigateTo(TallyScreenMode.COMPANY_MANAGER) },
                        colors = ButtonDefaults.buttonColors(containerColor = TallyTealRibbon),
                        modifier = Modifier.testTag("tally_empty_create_company_cta")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create Practice Company", color = Color.White)
                    }
                } else {
                    Text(
                        text = activeCompany.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = TallyTextDark,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Text(
                        text = "State: ${activeCompany.stateName} • GSTIN: ${activeCompany.gstin.ifBlank { "Unregistered" }} • Groups: ${state.groups.size} • Ledgers: ${state.ledgers.size} • Vouchers: ${state.vouchers.size}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMono,
                            color = TallyTextMuted
                        )
                    )
                }
            }
        }

        if (activeCompany != null) {
            item {
                TallyMenuBox(title = "GATEWAY OF TALLY — MASTERS") {
                    TallyMenuRow("Create / View Ledgers (${state.ledgers.size})", "Under 28 Standard Groups", "tally_menu_ledgers") {
                        vm.navigateTo(TallyScreenMode.CREATE_LEDGER)
                    }
                    TallyMenuRow("Inventory: Units & Stock Items (${state.stockItems.size})", "HSN & GST Rate Setup", "tally_menu_inventory") {
                        vm.navigateTo(TallyScreenMode.CREATE_INVENTORY)
                    }
                }
            }
            item {
                TallyMenuBox(title = "TRANSACTIONS (DOUBLE-ENTRY VOUCHERS)") {
                    TallyMenuRow("Vouchers (F4 Contra • F5 Pay • F6 Rcpt • F7 Jrnl)", "Strict Dr == Cr Validation", "tally_menu_vouchers") {
                        vm.openVoucherMode("PAYMENT")
                    }
                    TallyMenuRow("GST Invoicing (F8 Sales • F9 Purchase)", "Auto CGST+SGST / IGST", "tally_menu_gst_vouchers") {
                        vm.openVoucherMode("SALES")
                    }
                    TallyMenuRow("Day Book (${state.dayBook.size} Vouchers)", "Chronological Register", "tally_menu_daybook") {
                        vm.navigateTo(TallyScreenMode.DAY_BOOK)
                    }
                }
            }
            item {
                TallyMenuBox(title = "FINANCIAL REPORTS & GST SUMMARY") {
                    TallyMenuRow(
                        "Trial Balance",
                        if (state.trialBalance.isBalanced) "Balanced (${TallyAccountingEngine.formatPaiseInr(state.trialBalance.totalDebitPaise)})" else "Diff!",
                        "tally_menu_trial_balance"
                    ) { vm.navigateTo(TallyScreenMode.TRIAL_BALANCE) }
                    TallyMenuRow(
                        "Profit & Loss A/c",
                        "Net: ${TallyAccountingEngine.formatPaiseInr(state.profitAndLoss.netProfitPaise)}",
                        "tally_menu_pnl"
                    ) { vm.navigateTo(TallyScreenMode.PROFIT_AND_LOSS) }
                    TallyMenuRow(
                        "Balance Sheet",
                        "Assets: ${TallyAccountingEngine.formatPaiseInr(state.balanceSheet.totalAssetsSidePaise)}",
                        "tally_menu_balance_sheet"
                    ) { vm.navigateTo(TallyScreenMode.BALANCE_SHEET) }
                    TallyMenuRow(
                        "Stock Summary",
                        "${state.stockSummary.size} Tracked Items",
                        "tally_menu_stock_summary"
                    ) { vm.navigateTo(TallyScreenMode.STOCK_SUMMARY) }
                    TallyMenuRow(
                        "GST Computation Summary",
                        "Net GST: ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.netPayablePaise)}",
                        "tally_menu_gst_summary"
                    ) { vm.navigateTo(TallyScreenMode.GST_SUMMARY) }
                }
            }
        }
    }
}

@Composable
private fun TallyMenuBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TallyPanelSurface, RoundedCornerShape(6.dp))
            .border(1.dp, TallyBorderMint, RoundedCornerShape(6.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(TallyTealRibbon)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = Color.White,
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Column(modifier = Modifier.padding(6.dp), content = content)
    }
}

@Composable
private fun TallyMenuRow(
    title: String,
    trailing: String,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = TallyTextDark,
                fontWeight = FontWeight.SemiBold
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                color = TallyTealRibbon,
                fontWeight = FontWeight.Bold
            )
        )
    }
    HorizontalDivider(color = TallyBorderMint.copy(alpha = 0.45f))
}

@Composable
private fun CompanyManagerPane(
    state: TallySimulatorUiState,
    vm: TallySimulatorViewModel,
    saveTrigger: Int
) {
    var name by remember { mutableStateOf("") }
    var stateName by remember { mutableStateOf("Uttar Pradesh") }
    var gstin by remember { mutableStateOf("") }
    var fyFrom by remember { mutableStateOf("2026-04-01") }

    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0 && name.trim().length >= 2) {
            vm.createCompany(name, stateName, gstin, fyFrom)
            name = ""
            gstin = ""
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TallyMenuBox("CREATE NEW PRACTICE COMPANY (ZERO DUMMY DATA)") {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Company Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("tally_company_name_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = stateName,
                            onValueChange = { stateName = it },
                            label = { Text("State (for GST)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = gstin,
                            onValueChange = { gstin = it },
                            label = { Text("GSTIN (Optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = fyFrom,
                        onValueChange = { fyFrom = it },
                        label = { Text("Financial Year Begins (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            vm.createCompany(name, stateName, gstin, fyFrom)
                            name = ""
                            gstin = ""
                        },
                        enabled = name.trim().length >= 2,
                        colors = ButtonDefaults.buttonColors(containerColor = TallyTealRibbon),
                        modifier = Modifier.fillMaxWidth().testTag("tally_save_company_button")
                    ) {
                        Text("Accept & Create Company (Ctrl+A)", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (state.companies.isNotEmpty()) {
            item {
                TallyMenuBox("EXISTING PRACTICE COMPANIES (${state.companies.size})") {
                    state.companies.forEach { company ->
                        val isActive = company.id == state.activeCompany?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isActive) TallySelectionGold.copy(alpha = 0.45f) else Color.Transparent)
                                .clickable { vm.selectCompany(company.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(company.name, fontWeight = FontWeight.Bold, color = TallyTextDark)
                                Text(
                                    "${company.stateName} • FY ${company.financialYearFrom}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, color = TallyTextMuted)
                                )
                            }
                            IconButton(onClick = { vm.deleteCompany(company.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Company", tint = Color(0xFFB91C1C))
                            }
                        }
                        HorizontalDivider(color = TallyBorderMint.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateLedgerPane(
    state: TallySimulatorUiState,
    vm: TallySimulatorViewModel,
    saveTrigger: Int
) {
    var ledgerName by remember { mutableStateOf("") }
    var selectedGroupIdx by remember(state.groups) { mutableIntStateOf(0) }
    var openingBalance by remember { mutableStateOf("0") }
    var openingType by remember { mutableStateOf("DR") }
    var gstRate by remember { mutableStateOf("0") }
    var partyState by remember { mutableStateOf(state.activeCompany?.stateName ?: "Uttar Pradesh") }
    var groupMenuExpanded by remember { mutableStateOf(false) }

    val selectedGroup = state.groups.getOrNull(selectedGroupIdx)

    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0 && selectedGroup != null && ledgerName.trim().length >= 2) {
            vm.createLedger(ledgerName, selectedGroup, openingBalance, openingType, gstRate.toIntOrNull() ?: 0, partyState)
            ledgerName = ""
            openingBalance = "0"
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TallyMenuBox("LEDGER CREATION") {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ledgerName,
                        onValueChange = { ledgerName = it },
                        label = { Text("Ledger Name (e.g., HDFC Bank, Sales A/c, Rent)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("tally_ledger_name_input")
                    )
                    Box {
                        OutlinedButton(
                            onClick = { groupMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Under Group: ${selectedGroup?.name ?: "Select Group"} (${selectedGroup?.nature.orEmpty()})", color = TallyTextDark)
                        }
                        DropdownMenu(expanded = groupMenuExpanded, onDismissRequest = { groupMenuExpanded = false }) {
                            state.groups.forEachIndexed { idx, g ->
                                DropdownMenuItem(
                                    text = { Text("${g.name} [${g.nature}]") },
                                    onClick = {
                                        selectedGroupIdx = idx
                                        openingType = if (g.nature == "ASSETS" || g.nature == "EXPENSES") "DR" else "CR"
                                        groupMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = openingBalance,
                            onValueChange = { openingBalance = it },
                            label = { Text("Opening Bal (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = openingType == "DR", onClick = { openingType = "DR" }, label = { Text("Dr") })
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(selected = openingType == "CR", onClick = { openingType = "CR" }, label = { Text("Cr") })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = gstRate,
                            onValueChange = { gstRate = it },
                            label = { Text("GST %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.5f)
                        )
                        OutlinedTextField(
                            value = partyState,
                            onValueChange = { partyState = it },
                            label = { Text("Party State") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            if (selectedGroup != null) {
                                vm.createLedger(ledgerName, selectedGroup, openingBalance, openingType, gstRate.toIntOrNull() ?: 0, partyState)
                                ledgerName = ""
                                openingBalance = "0"
                            }
                        },
                        enabled = selectedGroup != null && ledgerName.trim().length >= 2,
                        colors = ButtonDefaults.buttonColors(containerColor = TallyTealRibbon),
                        modifier = Modifier.fillMaxWidth().testTag("tally_save_ledger_button")
                    ) {
                        Text("Create Ledger (Ctrl+A)", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            TallyMenuBox("LEDGERS IN COMPANY (${state.ledgers.size})") {
                state.ledgers.forEach { l ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(l.name, fontWeight = FontWeight.Bold, color = TallyTextDark)
                            Text("${l.groupName} • ${l.nature}", style = MaterialTheme.typography.labelSmall.copy(color = TallyTextMuted))
                        }
                        Text(
                            "${TallyAccountingEngine.formatPaiseInr(l.openingBalancePaise)} ${l.openingType}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono, color = TallyTealRibbon)
                        )
                    }
                    HorizontalDivider(color = TallyBorderMint.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun CreateInventoryPane(
    state: TallySimulatorUiState,
    vm: TallySimulatorViewModel,
    saveTrigger: Int
) {
    var unitSym by remember { mutableStateOf("") }
    var unitFormal by remember { mutableStateOf("") }
    var itemName by remember { mutableStateOf("") }
    var itemUnit by remember { mutableStateOf("Nos") }
    var hsn by remember { mutableStateOf("") }
    var gstRate by remember { mutableStateOf("18") }
    var openQty by remember { mutableStateOf("0") }
    var openRate by remember { mutableStateOf("0") }

    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0 && itemName.trim().length >= 2) {
            vm.createStockItem(itemName, itemUnit, hsn, gstRate.toIntOrNull() ?: 18, openQty, openRate)
            itemName = ""
            hsn = ""
            openQty = "0"
            openRate = "0"
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TallyMenuBox("UNIT OF MEASURE") {
                Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = unitSym, onValueChange = { unitSym = it }, label = { Text("Symbol") }, singleLine = true, modifier = Modifier.weight(0.8f))
                    OutlinedTextField(value = unitFormal, onValueChange = { unitFormal = it }, label = { Text("Formal Name") }, singleLine = true, modifier = Modifier.weight(1.2f))
                    Button(
                        onClick = {
                            vm.createUnit(unitSym, unitFormal)
                            if (unitSym.isNotBlank()) itemUnit = unitSym.trim()
                            unitSym = ""
                            unitFormal = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TallyTealRibbon)
                    ) { Text("Add", color = Color.White) }
                }
            }
        }
        item {
            TallyMenuBox("STOCK ITEM CREATION") {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = itemName, onValueChange = { itemName = it }, label = { Text("Stock Item Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = itemUnit, onValueChange = { itemUnit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = hsn, onValueChange = { hsn = it }, label = { Text("HSN") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = gstRate, onValueChange = { gstRate = it }, label = { Text("GST %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(0.8f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = openQty, onValueChange = { openQty = it }, label = { Text("Opening Qty") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = openRate, onValueChange = { openRate = it }, label = { Text("Rate (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Button(
                        onClick = {
                            vm.createStockItem(itemName, itemUnit, hsn, gstRate.toIntOrNull() ?: 18, openQty, openRate)
                            itemName = ""
                            hsn = ""
                            openQty = "0"
                            openRate = "0"
                        },
                        enabled = itemName.trim().length >= 2,
                        colors = ButtonDefaults.buttonColors(containerColor = TallyTealRibbon),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save Stock Item (Ctrl+A)", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun VoucherEntryPane(
    state: TallySimulatorUiState,
    vm: TallySimulatorViewModel,
    saveTrigger: Int
) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var dateIso by remember { mutableStateOf(today) }
    var drLedgerId by remember(state.ledgers) { mutableLongStateOf(state.ledgers.firstOrNull()?.id ?: 0L) }
    var crLedgerId by remember(state.ledgers) { mutableLongStateOf(state.ledgers.lastOrNull()?.id ?: 0L) }
    var amountRupees by remember { mutableStateOf("") }
    var gstRateText by remember(state.activeVoucherType) {
        mutableStateOf(if (state.activeVoucherType in setOf("SALES", "PURCHASE")) "18" else "0")
    }
    var partyState by remember(state.activeCompany) { mutableStateOf(state.activeCompany?.stateName ?: "Uttar Pradesh") }
    var narration by remember { mutableStateOf("") }
    var selectedStockId by remember { mutableLongStateOf(0L) }
    var stockQtyText by remember { mutableStateOf("") }

    var showDrMenu by remember { mutableStateOf(false) }
    var showCrMenu by remember { mutableStateOf(false) }
    var showStockMenu by remember { mutableStateOf(false) }

    val drLedger = state.ledgers.firstOrNull { it.id == drLedgerId }
    val crLedger = state.ledgers.firstOrNull { it.id == crLedgerId }
    val selectedStock = state.stockItems.firstOrNull { it.id == selectedStockId }
    val amountPaise = TallyAccountingEngine.parseRupeesToPaise(amountRupees)
    val gstRate = gstRateText.toIntOrNull()?.coerceIn(0, 28) ?: 0
    val isGstVoucher = state.activeVoucherType in setOf("SALES", "PURCHASE")
    val gstPreview = if (isGstVoucher && gstRate > 0 && amountPaise > 0L) {
        TallyAccountingEngine.computeGstSplit(
            taxableAmountPaise = amountPaise,
            gstRatePercent = gstRate,
            companyState = state.activeCompany?.stateName.orEmpty(),
            partyState = partyState
        )
    } else null

    val submitVoucher: () -> Unit = {
        if (drLedger != null && crLedger != null && amountPaise > 0L) {
            vm.saveDoubleEntryVoucher(
                voucherType = state.activeVoucherType,
                dateIso = dateIso,
                narration = narration,
                draftLines = listOf(
                    TallyAccountingEngine.VoucherDraftLine(drLedger.id, drLedger.name, "DR", amountPaise),
                    TallyAccountingEngine.VoucherDraftLine(crLedger.id, crLedger.name, "CR", amountPaise)
                ),
                gstRatePercent = if (isGstVoucher) gstRate else 0,
                partyStateName = partyState,
                stockItem = selectedStock,
                stockQty = stockQtyText.toDoubleOrNull() ?: 0.0
            )
            amountRupees = ""
            narration = ""
            stockQtyText = ""
        }
    }

    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0) submitVoucher()
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TallyMenuBox("${state.activeVoucherType} VOUCHER ENTRY (DOUBLE-ENTRY Dr == Cr)") {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = dateIso, onValueChange = { dateIso = it }, label = { Text("Voucher Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    // Debit (Dr) Ledger Selector
                    Box {
                        OutlinedButton(onClick = { showDrMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Debit (Dr): ${drLedger?.name ?: "Select Debit Ledger"}", color = TallyTextDark)
                        }
                        DropdownMenu(expanded = showDrMenu, onDismissRequest = { showDrMenu = false }) {
                            state.ledgers.forEach { l ->
                                DropdownMenuItem(text = { Text("${l.name} (${l.groupName})") }, onClick = { drLedgerId = l.id; showDrMenu = false })
                            }
                        }
                    }

                    // Credit (Cr) Ledger Selector
                    Box {
                        OutlinedButton(onClick = { showCrMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Credit (Cr): ${crLedger?.name ?: "Select Credit Ledger"}", color = TallyTextDark)
                        }
                        DropdownMenu(expanded = showCrMenu, onDismissRequest = { showCrMenu = false }) {
                            state.ledgers.forEach { l ->
                                DropdownMenuItem(text = { Text("${l.name} (${l.groupName})") }, onClick = { crLedgerId = l.id; showCrMenu = false })
                            }
                        }
                    }

                    OutlinedTextField(
                        value = amountRupees,
                        onValueChange = { amountRupees = it },
                        label = { Text(if (isGstVoucher) "Taxable Amount (₹)" else "Voucher Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("tally_voucher_amount_input")
                    )

                    if (isGstVoucher) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = gstRateText,
                                onValueChange = { gstRateText = it },
                                label = { Text("GST % (0/5/12/18/28)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(0.8f)
                            )
                            OutlinedTextField(
                                value = partyState,
                                onValueChange = { partyState = it },
                                label = { Text("Party State") },
                                singleLine = true,
                                modifier = Modifier.weight(1.2f)
                            )
                        }
                        if (state.stockItems.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.weight(1.2f)) {
                                    OutlinedButton(onClick = { showStockMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text(selectedStock?.name ?: "Optional Stock Item", maxLines = 1, overflow = TextOverflow.Ellipsis, color = TallyTextDark)
                                    }
                                    DropdownMenu(expanded = showStockMenu, onDismissRequest = { showStockMenu = false }) {
                                        DropdownMenuItem(text = { Text("None") }, onClick = { selectedStockId = 0L; showStockMenu = false })
                                        state.stockItems.forEach { item ->
                                            DropdownMenuItem(text = { Text("${item.name} (${item.unitSymbol})") }, onClick = { selectedStockId = item.id; showStockMenu = false })
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = stockQtyText,
                                    onValueChange = { stockQtyText = it },
                                    label = { Text("Qty") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(0.8f)
                                )
                            }
                        }
                        if (gstPreview != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TallySelectionGold.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = if (gstPreview.isIntraState) {
                                        "Intra-State GST: CGST ${TallyAccountingEngine.formatPaiseInr(gstPreview.cgstPaise)} + SGST ${TallyAccountingEngine.formatPaiseInr(gstPreview.sgstPaise)}"
                                    } else {
                                        "Inter-State GST: IGST ${TallyAccountingEngine.formatPaiseInr(gstPreview.igstPaise)}"
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono, color = TallyTextDark, fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Grand Invoice Total: ${TallyAccountingEngine.formatPaiseInr(gstPreview.grandTotalPaise)}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono, color = TallyTealRibbon, fontWeight = FontWeight.ExtraBold)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = narration,
                        onValueChange = { narration = it },
                        label = { Text("Narration") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = submitVoucher,
                        enabled = drLedger != null && crLedger != null && amountPaise > 0L,
                        colors = ButtonDefaults.buttonColors(containerColor = TallyTealRibbon),
                        modifier = Modifier.fillMaxWidth().testTag("tally_save_voucher_button")
                    ) {
                        Text("Accept & Post Voucher (Ctrl+A)", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayBookPane(state: TallySimulatorUiState, vm: TallySimulatorViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                text = "DAY BOOK (${state.dayBook.size} Vouchers)",
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono, color = TallyTealRibbon, fontWeight = FontWeight.ExtraBold)
            )
        }
        if (state.dayBook.isEmpty()) {
            item { Text("No vouchers posted yet. Press F4–F9 to record your first double-entry voucher.", color = TallyTextMuted) }
        }
        items(state.dayBook, key = { it.voucher.id }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TallyPanelSurface, RoundedCornerShape(6.dp))
                    .border(1.dp, TallyBorderMint, RoundedCornerShape(6.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${row.voucher.dateIso} • ${row.voucher.voucherType} #${row.voucher.voucherNumber}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, color = TallyTealRibbon, fontWeight = FontWeight.Bold)
                    )
                    Text(row.particularsSummary, fontWeight = FontWeight.Bold, color = TallyTextDark)
                    if (row.voucher.narration.isNotBlank()) {
                        Text(row.voucher.narration, style = MaterialTheme.typography.bodySmall.copy(color = TallyTextMuted))
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        TallyAccountingEngine.formatPaiseInr(row.voucher.totalAmountPaise),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, color = TallyTextDark)
                    )
                    IconButton(onClick = { vm.deleteVoucher(row.voucher.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Voucher", tint = Color(0xFFB91C1C), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrialBalancePane(state: TallySimulatorUiState) {
    val tb = state.trialBalance
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            TallyReportHeader("TRIAL BALANCE", if (tb.isBalanced) "BALANCED ✓" else "DIFF: ${TallyAccountingEngine.formatPaiseInr(tb.differencePaise)}")
        }
        items(tb.rows, key = { it.ledgerId }) { r ->
            TallyAmountRow(
                label = "${r.ledgerName} (${r.groupName})",
                leftAmount = if (r.closingDebitPaise > 0L) "Dr ${TallyAccountingEngine.formatPaiseInr(r.closingDebitPaise)}" else "",
                rightAmount = if (r.closingCreditPaise > 0L) "Cr ${TallyAccountingEngine.formatPaiseInr(r.closingCreditPaise)}" else ""
            )
        }
        item {
            TallyTotalBar(
                "TOTAL",
                "Dr ${TallyAccountingEngine.formatPaiseInr(tb.totalDebitPaise)}",
                "Cr ${TallyAccountingEngine.formatPaiseInr(tb.totalCreditPaise)}"
            )
        }
    }
}

@Composable
private fun ProfitAndLossPane(state: TallySimulatorUiState) {
    val pnl = state.profitAndLoss
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            TallyReportHeader(
                "PROFIT & LOSS A/C",
                if (pnl.netProfitPaise >= 0L) "NET PROFIT: ${TallyAccountingEngine.formatPaiseInr(pnl.netProfitPaise)}"
                else "NET LOSS: ${TallyAccountingEngine.formatPaiseInr(-pnl.netProfitPaise)}"
            )
        }
        item { TallySectionSubheader("Direct Incomes / Sales") }
        items(pnl.directIncomeRows) { r -> TallyAmountRow(r.ledgerName, "", TallyAccountingEngine.formatPaiseInr(r.closingCreditPaise - r.closingDebitPaise)) }
        item { TallySectionSubheader("Direct Expenses / Purchases") }
        items(pnl.directExpenseRows) { r -> TallyAmountRow(r.ledgerName, TallyAccountingEngine.formatPaiseInr(r.closingDebitPaise - r.closingCreditPaise), "") }
        item { TallyTotalBar("Gross Profit / (Loss)", "", TallyAccountingEngine.formatPaiseInr(pnl.grossProfitPaise)) }
        item { TallySectionSubheader("Indirect Incomes") }
        items(pnl.indirectIncomeRows) { r -> TallyAmountRow(r.ledgerName, "", TallyAccountingEngine.formatPaiseInr(r.closingCreditPaise - r.closingDebitPaise)) }
        item { TallySectionSubheader("Indirect Expenses") }
        items(pnl.indirectExpenseRows) { r -> TallyAmountRow(r.ledgerName, TallyAccountingEngine.formatPaiseInr(r.closingDebitPaise - r.closingCreditPaise), "") }
        item { TallyTotalBar("Net Profit / (Loss)", "", TallyAccountingEngine.formatPaiseInr(pnl.netProfitPaise)) }
    }
}

@Composable
private fun BalanceSheetPane(state: TallySimulatorUiState) {
    val bs = state.balanceSheet
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { TallyReportHeader("BALANCE SHEET", if (bs.isBalanced) "TALLIED ✓" else "DIFF: ${TallyAccountingEngine.formatPaiseInr(bs.differencePaise)}") }
        item { TallySectionSubheader("Capital & Liabilities") }
        items(bs.liabilityRows) { r -> TallyAmountRow("${r.ledgerName} (${r.groupName})", "", TallyAccountingEngine.formatPaiseInr(r.closingCreditPaise - r.closingDebitPaise)) }
        if (bs.netProfitPaise > 0L) {
            item { TallyAmountRow("Profit & Loss A/c (Net Profit)", "", TallyAccountingEngine.formatPaiseInr(bs.netProfitPaise)) }
        }
        item { TallyTotalBar("Total Liabilities", "", TallyAccountingEngine.formatPaiseInr(bs.totalLiabilitiesSidePaise)) }
        item { TallySectionSubheader("Assets") }
        items(bs.assetRows) { r -> TallyAmountRow("${r.ledgerName} (${r.groupName})", "", TallyAccountingEngine.formatPaiseInr(r.closingDebitPaise - r.closingCreditPaise)) }
        if (bs.netProfitPaise < 0L) {
            item { TallyAmountRow("Profit & Loss A/c (Net Loss)", "", TallyAccountingEngine.formatPaiseInr(-bs.netProfitPaise)) }
        }
        item { TallyTotalBar("Total Assets", "", TallyAccountingEngine.formatPaiseInr(bs.totalAssetsSidePaise)) }
    }
}

@Composable
private fun StockSummaryPane(state: TallySimulatorUiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { TallyReportHeader("STOCK SUMMARY", "${state.stockSummary.size} Items") }
        if (state.stockSummary.isEmpty()) {
            item { Text("No stock items created yet.", color = TallyTextMuted) }
        }
        items(state.stockSummary, key = { it.itemId }) { s ->
            TallyAmountRow(
                label = "${s.itemName} (HSN: ${s.hsnCode.ifBlank { "-" }})",
                leftAmount = "Cls: ${"%.2f".format(Locale.US, s.closingQty)} ${s.unitSymbol}",
                rightAmount = TallyAccountingEngine.formatPaiseInr(s.closingValuePaise)
            )
        }
    }
}

@Composable
private fun GstSummaryPane(state: TallySimulatorUiState) {
    val g = state.gstSummary
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { TallyReportHeader("GST COMPUTATION SUMMARY", "Exact Paise") }
        item { TallyAmountRow("Outward Taxable Sales", "", TallyAccountingEngine.formatPaiseInr(g.salesTaxablePaise)) }
        item { TallyAmountRow("Output CGST", "", TallyAccountingEngine.formatPaiseInr(g.outputCgstPaise)) }
        item { TallyAmountRow("Output SGST", "", TallyAccountingEngine.formatPaiseInr(g.outputSgstPaise)) }
        item { TallyAmountRow("Output IGST", "", TallyAccountingEngine.formatPaiseInr(g.outputIgstPaise)) }
        item { TallyAmountRow("Inward Taxable Purchases", "", TallyAccountingEngine.formatPaiseInr(g.purchaseTaxablePaise)) }
        item { TallyAmountRow("Input CGST Credit", "", TallyAccountingEngine.formatPaiseInr(g.inputCgstPaise)) }
        item { TallyAmountRow("Input SGST Credit", "", TallyAccountingEngine.formatPaiseInr(g.inputSgstPaise)) }
        item { TallyAmountRow("Input IGST Credit", "", TallyAccountingEngine.formatPaiseInr(g.inputIgstPaise)) }
        item { TallyTotalBar("Net GST Payable / (ITC)", "", TallyAccountingEngine.formatPaiseInr(g.netPayablePaise)) }
    }
}

@Composable
private fun TallyReportHeader(title: String, badge: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TallyTealRibbon, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge.copy(color = Color.White, fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold))
        Text(badge, style = MaterialTheme.typography.labelMedium.copy(color = TallySelectionGold, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun TallySectionSubheader(text: String) {
    Text(
        text = text.uppercase(Locale.US),
        style = MaterialTheme.typography.labelMedium.copy(color = TallyTealRibbon, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun TallyAmountRow(label: String, leftAmount: String, rightAmount: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TallyPanelSurface)
            .border(0.5.dp, TallyBorderMint)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(color = TallyTextDark, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        if (leftAmount.isNotBlank()) {
            Text(leftAmount, style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono, color = TallyTextDark), modifier = Modifier.padding(end = 10.dp))
        }
        if (rightAmount.isNotBlank()) {
            Text(rightAmount, style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono, color = TallyTealRibbon, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun TallyTotalBar(label: String, left: String, right: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TallySelectionGold, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(color = TallyTextDark, fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f))
        if (left.isNotBlank()) Text(left, style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono, color = TallyTextDark, fontWeight = FontWeight.ExtraBold), modifier = Modifier.padding(end = 10.dp))
        Text(right, style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono, color = TallyTextDark, fontWeight = FontWeight.ExtraBold))
    }
}
