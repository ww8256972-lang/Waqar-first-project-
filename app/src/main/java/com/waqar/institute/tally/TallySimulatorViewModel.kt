package com.waqar.institute.tally

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TallyScreenMode(val title: String) {
    GATEWAY("Gateway of Tally"),
    COMPANY_MANAGER("Select / Create Company (Alt+F3)"),
    CREATE_LEDGER("Ledger Creation"),
    CREATE_INVENTORY("Stock & Units Creation"),
    VOUCHER_ENTRY("Accounting Voucher Creation"),
    DAY_BOOK("Day Book"),
    TRIAL_BALANCE("Trial Balance"),
    PROFIT_AND_LOSS("Profit & Loss A/c"),
    BALANCE_SHEET("Balance Sheet"),
    STOCK_SUMMARY("Stock Summary"),
    GST_SUMMARY("GST Computation Summary")
}

@Immutable
data class TallySimulatorUiState(
    val companies: List<TallyCompanyEntity> = emptyList(),
    val activeCompany: TallyCompanyEntity? = null,
    val groups: List<TallyGroupEntity> = emptyList(),
    val ledgers: List<TallyLedgerEntity> = emptyList(),
    val units: List<TallyUnitEntity> = emptyList(),
    val stockItems: List<TallyStockItemEntity> = emptyList(),
    val vouchers: List<TallyVoucherEntity> = emptyList(),
    val dayBook: List<TallyAccountingEngine.DayBookRow> = emptyList(),
    val trialBalance: TallyAccountingEngine.TrialBalanceReport = TallyAccountingEngine.TrialBalanceReport(
        rows = emptyList(),
        totalDebitPaise = 0L,
        totalCreditPaise = 0L,
        differencePaise = 0L,
        isBalanced = true
    ),
    val profitAndLoss: TallyAccountingEngine.ProfitAndLossReport = TallyAccountingEngine.ProfitAndLossReport(
        directIncomeRows = emptyList(),
        directExpenseRows = emptyList(),
        indirectIncomeRows = emptyList(),
        indirectExpenseRows = emptyList(),
        grossProfitPaise = 0L,
        netProfitPaise = 0L
    ),
    val balanceSheet: TallyAccountingEngine.BalanceSheetReport = TallyAccountingEngine.BalanceSheetReport(
        liabilityRows = emptyList(),
        assetRows = emptyList(),
        netProfitPaise = 0L,
        totalLiabilitiesSidePaise = 0L,
        totalAssetsSidePaise = 0L,
        differencePaise = 0L,
        isBalanced = true
    ),
    val stockSummary: List<TallyAccountingEngine.StockSummaryRow> = emptyList(),
    val gstSummary: TallyAccountingEngine.GstSummaryReport = TallyAccountingEngine.GstSummaryReport(
        0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
    ),
    val screenMode: TallyScreenMode = TallyScreenMode.GATEWAY,
    val activeVoucherType: String = "PAYMENT", // CONTRA | PAYMENT | RECEIPT | JOURNAL | SALES | PURCHASE
    val statusBanner: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class TallySimulatorViewModel(
    private val dao: TallySimulatorDao
) : ViewModel() {

    private val _selectedCompanyId = MutableStateFlow<Long?>(null)
    private val _uiState = MutableStateFlow(TallySimulatorUiState())
    val uiState: StateFlow<TallySimulatorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dao.observeCompanies().collect { companies ->
                val currentId = _selectedCompanyId.value
                val resolvedActive = companies.firstOrNull { it.id == currentId } ?: companies.firstOrNull()
                if (resolvedActive?.id != currentId) {
                    _selectedCompanyId.value = resolvedActive?.id
                }
                _uiState.update {
                    it.copy(
                        companies = companies,
                        activeCompany = resolvedActive
                    )
                }
            }
        }

        viewModelScope.launch {
            _selectedCompanyId.flatMapLatest { companyId ->
                if (companyId == null) {
                    flowOf(null)
                } else {
                    combine(
                        dao.observeGroups(companyId),
                        dao.observeLedgers(companyId),
                        dao.observeUnits(companyId),
                        dao.observeStockItems(companyId),
                        dao.observeVouchers(companyId),
                        dao.observeVoucherLines(companyId),
                        dao.observeInventoryLines(companyId)
                    ) { values ->
                        @Suppress("UNCHECKED_CAST")
                        CompanyDataset(
                            groups = values[0] as List<TallyGroupEntity>,
                            ledgers = values[1] as List<TallyLedgerEntity>,
                            units = values[2] as List<TallyUnitEntity>,
                            stockItems = values[3] as List<TallyStockItemEntity>,
                            vouchers = values[4] as List<TallyVoucherEntity>,
                            voucherLines = values[5] as List<TallyVoucherLineEntity>,
                            inventoryLines = values[6] as List<TallyInventoryLineEntity>
                        )
                    }
                }
            }.collect { dataset ->
                if (dataset == null) {
                    _uiState.update {
                        it.copy(
                            groups = emptyList(),
                            ledgers = emptyList(),
                            units = emptyList(),
                            stockItems = emptyList(),
                            vouchers = emptyList(),
                            dayBook = emptyList()
                        )
                    }
                } else {
                    val computed = withContext(Dispatchers.Default) {
                        val balances = TallyAccountingEngine.computeLedgerBalances(dataset.ledgers, dataset.voucherLines)
                        val dayBook = TallyAccountingEngine.buildDayBook(dataset.vouchers, dataset.voucherLines)
                        val trialBalance = TallyAccountingEngine.computeTrialBalance(balances)
                        val pnl = TallyAccountingEngine.computeProfitAndLoss(balances, dataset.groups)
                        val bs = TallyAccountingEngine.computeBalanceSheet(balances, pnl)
                        val stock = TallyAccountingEngine.computeStockSummary(dataset.stockItems, dataset.inventoryLines)
                        val gst = TallyAccountingEngine.computeGstSummary(dataset.vouchers)
                        ComputedReports(dayBook, trialBalance, pnl, bs, stock, gst)
                    }
                    _uiState.update {
                        it.copy(
                            groups = dataset.groups,
                            ledgers = dataset.ledgers,
                            units = dataset.units,
                            stockItems = dataset.stockItems,
                            vouchers = dataset.vouchers,
                            dayBook = computed.dayBook,
                            trialBalance = computed.trialBalance,
                            profitAndLoss = computed.pnl,
                            balanceSheet = computed.bs,
                            stockSummary = computed.stock,
                            gstSummary = computed.gst
                        )
                    }
                }
            }
        }
    }

    private data class CompanyDataset(
        val groups: List<TallyGroupEntity>,
        val ledgers: List<TallyLedgerEntity>,
        val units: List<TallyUnitEntity>,
        val stockItems: List<TallyStockItemEntity>,
        val vouchers: List<TallyVoucherEntity>,
        val voucherLines: List<TallyVoucherLineEntity>,
        val inventoryLines: List<TallyInventoryLineEntity>
    )

    private data class ComputedReports(
        val dayBook: List<TallyAccountingEngine.DayBookRow>,
        val trialBalance: TallyAccountingEngine.TrialBalanceReport,
        val pnl: TallyAccountingEngine.ProfitAndLossReport,
        val bs: TallyAccountingEngine.BalanceSheetReport,
        val stock: List<TallyAccountingEngine.StockSummaryRow>,
        val gst: TallyAccountingEngine.GstSummaryReport
    )

    fun navigateTo(mode: TallyScreenMode) {
        _uiState.update { it.copy(screenMode = mode, statusBanner = null) }
    }

    fun openVoucherMode(voucherType: String) {
        if (_uiState.value.activeCompany == null) {
            _uiState.update {
                it.copy(
                    screenMode = TallyScreenMode.COMPANY_MANAGER,
                    statusBanner = "Create a practice company first (Alt+F3)."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                screenMode = TallyScreenMode.VOUCHER_ENTRY,
                activeVoucherType = voucherType.uppercase(Locale.US),
                statusBanner = null
            )
        }
    }

    fun clearBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    fun selectCompany(companyId: Long) {
        _selectedCompanyId.value = companyId
        _uiState.update {
            it.copy(
                activeCompany = it.companies.firstOrNull { c -> c.id == companyId },
                screenMode = TallyScreenMode.GATEWAY,
                statusBanner = "Switched active company."
            )
        }
    }

    fun createCompany(
        name: String,
        stateName: String,
        gstin: String,
        financialYearFrom: String
    ) {
        val cleanName = name.trim()
        if (cleanName.length < 2) {
            _uiState.update { it.copy(statusBanner = "Company name must be at least 2 characters.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val companyId = dao.createCompanyWithStandardMasters(
                TallyCompanyEntity(
                    name = cleanName,
                    stateName = stateName.trim().ifBlank { "Uttar Pradesh" },
                    gstin = gstin.trim().uppercase(Locale.US),
                    financialYearFrom = financialYearFrom.trim().ifBlank { "2026-04-01" },
                    booksBeginFrom = financialYearFrom.trim().ifBlank { "2026-04-01" }
                )
            )
            _selectedCompanyId.value = companyId
            _uiState.update {
                it.copy(
                    screenMode = TallyScreenMode.GATEWAY,
                    statusBanner = "Created '$cleanName' with 28 standard Tally Account Groups, Cash & Profit & Loss A/c."
                )
            }
        }
    }

    fun deleteCompany(companyId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteCompany(companyId)
            _uiState.update { it.copy(statusBanner = "Deleted practice company.") }
        }
    }

    fun createLedger(
        name: String,
        group: TallyGroupEntity,
        openingBalanceRupees: String,
        openingType: String,
        gstRatePercent: Int,
        stateName: String
    ) {
        val company = _uiState.value.activeCompany ?: return
        val cleanName = name.trim()
        if (cleanName.length < 2) {
            _uiState.update { it.copy(statusBanner = "Ledger name must be at least 2 characters.") }
            return
        }
        val openingPaise = TallyAccountingEngine.parseRupeesToPaise(openingBalanceRupees)
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertLedger(
                TallyLedgerEntity(
                    companyId = company.id,
                    name = cleanName,
                    groupId = group.id,
                    groupName = group.name,
                    nature = group.nature,
                    openingBalancePaise = openingPaise,
                    openingType = if (openingType.equals("CR", ignoreCase = true)) "CR" else "DR",
                    gstRatePercent = gstRatePercent.coerceIn(0, 28),
                    stateName = stateName.trim().ifBlank { company.stateName }
                )
            )
            _uiState.update { it.copy(statusBanner = "Ledger '$cleanName' created under ${group.name}.") }
        }
    }

    fun createUnit(symbol: String, formalName: String) {
        val company = _uiState.value.activeCompany ?: return
        val cleanSym = symbol.trim()
        if (cleanSym.isEmpty()) {
            _uiState.update { it.copy(statusBanner = "Enter unit symbol (e.g., Nos, Pcs, Kg).") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertUnit(
                TallyUnitEntity(
                    companyId = company.id,
                    symbol = cleanSym,
                    formalName = formalName.trim().ifBlank { cleanSym }
                )
            )
            _uiState.update { it.copy(statusBanner = "Unit '$cleanSym' created.") }
        }
    }

    fun createStockItem(
        name: String,
        unitSymbol: String,
        hsnCode: String,
        gstRatePercent: Int,
        openingQtyRaw: String,
        openingRateRupees: String
    ) {
        val company = _uiState.value.activeCompany ?: return
        val cleanName = name.trim()
        if (cleanName.length < 2) {
            _uiState.update { it.copy(statusBanner = "Stock Item name must be at least 2 characters.") }
            return
        }
        val qty = openingQtyRaw.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val ratePaise = TallyAccountingEngine.parseRupeesToPaise(openingRateRupees)
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertStockItem(
                TallyStockItemEntity(
                    companyId = company.id,
                    name = cleanName,
                    unitSymbol = unitSymbol.trim().ifBlank { "Nos" },
                    hsnCode = hsnCode.trim(),
                    gstRatePercent = gstRatePercent.coerceIn(0, 28),
                    openingQty = qty,
                    openingRatePaise = ratePaise
                )
            )
            _uiState.update { it.copy(statusBanner = "Stock Item '$cleanName' saved.") }
        }
    }

    /**
     * Records a double-entry Accounting Voucher (F4..F9).
     * For Sales (F8) and Purchase (F9) with GST > 0%, automatically computes intra-state
     * CGST + SGST vs inter-state IGST and auto-provisions Duties & Taxes ledgers if needed.
     */
    fun saveDoubleEntryVoucher(
        voucherType: String,
        dateIso: String,
        narration: String,
        draftLines: List<TallyAccountingEngine.VoucherDraftLine>,
        gstRatePercent: Int = 0,
        partyStateName: String = "",
        stockItem: TallyStockItemEntity? = null,
        stockQty: Double = 0.0
    ) {
        val company = _uiState.value.activeCompany ?: return
        val cleanType = voucherType.trim().uppercase(Locale.US)
        val cleanDate = dateIso.trim().ifBlank {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }

        viewModelScope.launch(Dispatchers.IO) {
            val workingLines = draftLines.toMutableList()
            var taxablePaise = 0L
            var cgstPaise = 0L
            var sgstPaise = 0L
            var igstPaise = 0L

            if ((cleanType == "SALES" || cleanType == "PURCHASE") && gstRatePercent > 0 && workingLines.size == 2) {
                val baseTaxable = workingLines.first().amountPaise
                val gst = TallyAccountingEngine.computeGstSplit(
                    taxableAmountPaise = baseTaxable,
                    gstRatePercent = gstRatePercent,
                    companyState = company.stateName,
                    partyState = partyStateName
                )
                taxablePaise = gst.taxableAmountPaise
                cgstPaise = gst.cgstPaise
                sgstPaise = gst.sgstPaise
                igstPaise = gst.igstPaise

                val existingLedgers = dao.getLedgers(company.id)
                val dutiesGroup = dao.getGroups(company.id)
                    .firstOrNull { it.name.equals("Duties & Taxes", ignoreCase = true) }

                suspend fun ensureTaxLedger(label: String): TallyLedgerEntity {
                    val found = existingLedgers.firstOrNull { it.name.equals(label, ignoreCase = true) }
                    if (found != null) return found
                    val id = dao.insertLedger(
                        TallyLedgerEntity(
                            companyId = company.id,
                            name = label,
                            groupId = dutiesGroup?.id ?: 0L,
                            groupName = dutiesGroup?.name ?: "Duties & Taxes",
                            nature = "LIABILITIES",
                            gstRatePercent = gstRatePercent
                        )
                    )
                    return TallyLedgerEntity(
                        id = id,
                        companyId = company.id,
                        name = label,
                        groupId = dutiesGroup?.id ?: 0L,
                        groupName = dutiesGroup?.name ?: "Duties & Taxes",
                        nature = "LIABILITIES",
                        gstRatePercent = gstRatePercent
                    )
                }

                // Adjust party total to grandTotalPaise and append GST lines on tax side
                if (cleanType == "SALES") {
                    val drIdx = workingLines.indexOfFirst { it.entryType == "DR" }
                    if (drIdx >= 0) {
                        workingLines[drIdx] = workingLines[drIdx].copy(amountPaise = gst.grandTotalPaise)
                    }
                    if (gst.isIntraState) {
                        if (cgstPaise > 0L) {
                            val cgstLed = ensureTaxLedger("Output CGST")
                            workingLines += TallyAccountingEngine.VoucherDraftLine(cgstLed.id, cgstLed.name, "CR", cgstPaise)
                        }
                        if (sgstPaise > 0L) {
                            val sgstLed = ensureTaxLedger("Output SGST")
                            workingLines += TallyAccountingEngine.VoucherDraftLine(sgstLed.id, sgstLed.name, "CR", sgstPaise)
                        }
                    } else if (igstPaise > 0L) {
                        val igstLed = ensureTaxLedger("Output IGST")
                        workingLines += TallyAccountingEngine.VoucherDraftLine(igstLed.id, igstLed.name, "CR", igstPaise)
                    }
                } else {
                    val crIdx = workingLines.indexOfFirst { it.entryType == "CR" }
                    if (crIdx >= 0) {
                        workingLines[crIdx] = workingLines[crIdx].copy(amountPaise = gst.grandTotalPaise)
                    }
                    if (gst.isIntraState) {
                        if (cgstPaise > 0L) {
                            val cgstLed = ensureTaxLedger("Input CGST")
                            workingLines += TallyAccountingEngine.VoucherDraftLine(cgstLed.id, cgstLed.name, "DR", cgstPaise)
                        }
                        if (sgstPaise > 0L) {
                            val sgstLed = ensureTaxLedger("Input SGST")
                            workingLines += TallyAccountingEngine.VoucherDraftLine(sgstLed.id, sgstLed.name, "DR", sgstPaise)
                        }
                    } else if (igstPaise > 0L) {
                        val igstLed = ensureTaxLedger("Input IGST")
                        workingLines += TallyAccountingEngine.VoucherDraftLine(igstLed.id, igstLed.name, "DR", igstPaise)
                    }
                }
            }

            val validation = TallyAccountingEngine.validateDoubleEntry(workingLines)
            if (!validation.isValid) {
                _uiState.update { it.copy(statusBanner = validation.errorMessage ?: "Double-entry validation failed.") }
                return@launch
            }

            val nextNo = "${cleanType.take(3)}-${_uiState.value.vouchers.size + 1}"
            val voucherEntity = TallyVoucherEntity(
                companyId = company.id,
                voucherNumber = nextNo,
                voucherType = cleanType,
                dateIso = cleanDate,
                narration = narration.trim(),
                partyLedgerId = workingLines.first().ledgerId,
                partyStateName = partyStateName.trim(),
                totalAmountPaise = validation.totalDebitPaise,
                taxableAmountPaise = if (taxablePaise > 0L) taxablePaise else validation.totalDebitPaise,
                cgstPaise = cgstPaise,
                sgstPaise = sgstPaise,
                igstPaise = igstPaise
            )
            val lineEntities = workingLines.map { dl ->
                TallyVoucherLineEntity(
                    voucherId = 0L,
                    companyId = company.id,
                    ledgerId = dl.ledgerId,
                    ledgerName = dl.ledgerName,
                    entryType = dl.entryType.uppercase(Locale.US),
                    amountPaise = dl.amountPaise
                )
            }
            val invLines = if (stockItem != null && stockQty > 0.0 && (cleanType == "SALES" || cleanType == "PURCHASE")) {
                val movement = if (cleanType == "PURCHASE") "IN" else "OUT"
                val baseVal = if (taxablePaise > 0L) taxablePaise else validation.totalDebitPaise
                val ratePaise = if (stockQty > 0.0) (baseVal.toDouble() / stockQty).toLong() else 0L
                listOf(
                    TallyInventoryLineEntity(
                        voucherId = 0L,
                        companyId = company.id,
                        stockItemId = stockItem.id,
                        stockItemName = stockItem.name,
                        movementType = movement,
                        quantity = stockQty,
                        ratePaise = ratePaise,
                        amountPaise = baseVal,
                        gstRatePercent = gstRatePercent
                    )
                )
            } else emptyList()

            dao.insertVoucherWithLines(voucherEntity, lineEntities, invLines)
            _uiState.update {
                it.copy(
                    statusBanner = "Saved $cleanType Voucher #$nextNo (${TallyAccountingEngine.formatPaiseInr(validation.totalDebitPaise)}) • Dr == Cr Verified ✓"
                )
            }
        }
    }

    fun deleteVoucher(voucherId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteVoucher(voucherId)
            _uiState.update { it.copy(statusBanner = "Voucher deleted from Day Book.") }
        }
    }

    fun exportCurrentReportPdf(context: Context) {
        val state = _uiState.value
        val company = state.activeCompany ?: run {
            _uiState.update { it.copy(statusBanner = "Create/select a company before exporting PDF.") }
            return
        }
        val (title, lines) = when (state.screenMode) {
            TallyScreenMode.DAY_BOOK -> "Day Book" to state.dayBook.map { row ->
                "${row.voucher.dateIso} | ${row.voucher.voucherNumber.padEnd(8)} | ${row.voucher.voucherType.padEnd(8)} | ${row.particularsSummary.take(28).padEnd(28)} | ${TallyAccountingEngine.formatPaiseInr(row.voucher.totalAmountPaise)}"
            }
            TallyScreenMode.TRIAL_BALANCE -> "Trial Balance" to (
                state.trialBalance.rows.map { r ->
                    "${r.ledgerName.take(28).padEnd(28)} | Dr: ${TallyAccountingEngine.formatPaiseInr(r.closingDebitPaise).padStart(13)} | Cr: ${TallyAccountingEngine.formatPaiseInr(r.closingCreditPaise).padStart(13)}"
                } + listOf(
                    "----------------------------------------------------------------------------",
                    "TOTAL                        | Dr: ${TallyAccountingEngine.formatPaiseInr(state.trialBalance.totalDebitPaise).padStart(13)} | Cr: ${TallyAccountingEngine.formatPaiseInr(state.trialBalance.totalCreditPaise).padStart(13)}"
                )
            )
            TallyScreenMode.PROFIT_AND_LOSS -> "Profit & Loss A/c" to buildList {
                add("=== DIRECT INCOME ===")
                state.profitAndLoss.directIncomeRows.forEach { add("${it.ledgerName.padEnd(32)} ${TallyAccountingEngine.formatPaiseInr(it.closingCreditPaise)}") }
                add("=== DIRECT EXPENSES ===")
                state.profitAndLoss.directExpenseRows.forEach { add("${it.ledgerName.padEnd(32)} ${TallyAccountingEngine.formatPaiseInr(it.closingDebitPaise)}") }
                add("GROSS PROFIT / (LOSS): ${TallyAccountingEngine.formatPaiseInr(state.profitAndLoss.grossProfitPaise)}")
                add("=== INDIRECT INCOME ===")
                state.profitAndLoss.indirectIncomeRows.forEach { add("${it.ledgerName.padEnd(32)} ${TallyAccountingEngine.formatPaiseInr(it.closingCreditPaise)}") }
                add("=== INDIRECT EXPENSES ===")
                state.profitAndLoss.indirectExpenseRows.forEach { add("${it.ledgerName.padEnd(32)} ${TallyAccountingEngine.formatPaiseInr(it.closingDebitPaise)}") }
                add("NET PROFIT / (LOSS): ${TallyAccountingEngine.formatPaiseInr(state.profitAndLoss.netProfitPaise)}")
            }
            TallyScreenMode.BALANCE_SHEET -> "Balance Sheet" to buildList {
                add("=== CAPITAL & LIABILITIES ===")
                state.balanceSheet.liabilityRows.forEach { add("${it.ledgerName.padEnd(32)} ${TallyAccountingEngine.formatPaiseInr(it.closingCreditPaise - it.closingDebitPaise)}") }
                if (state.balanceSheet.netProfitPaise > 0L) add("${"Net Profit (P&L A/c)".padEnd(32)} ${TallyAccountingEngine.formatPaiseInr(state.balanceSheet.netProfitPaise)}")
                add("TOTAL LIABILITIES: ${TallyAccountingEngine.formatPaiseInr(state.balanceSheet.totalLiabilitiesSidePaise)}")
                add("=== ASSETS ===")
                state.balanceSheet.assetRows.forEach { add("${it.ledgerName.padEnd(32)} ${TallyAccountingEngine.formatPaiseInr(it.closingDebitPaise - it.closingCreditPaise)}") }
                if (state.balanceSheet.netProfitPaise < 0L) add("${"Net Loss (P&L A/c)".padEnd(32)} ${TallyAccountingEngine.formatPaiseInr(-state.balanceSheet.netProfitPaise)}")
                add("TOTAL ASSETS: ${TallyAccountingEngine.formatPaiseInr(state.balanceSheet.totalAssetsSidePaise)}")
            }
            TallyScreenMode.STOCK_SUMMARY -> "Stock Summary" to state.stockSummary.map { s ->
                "${s.itemName.take(24).padEnd(24)} | Cls: ${"%.2f".format(Locale.US, s.closingQty)} ${s.unitSymbol} | Val: ${TallyAccountingEngine.formatPaiseInr(s.closingValuePaise)}"
            }
            else -> "GST Computation Summary" to listOf(
                "Sales Taxable Value   : ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.salesTaxablePaise)}",
                "Output CGST           : ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.outputCgstPaise)}",
                "Output SGST           : ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.outputSgstPaise)}",
                "Output IGST           : ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.outputIgstPaise)}",
                "Purchase Taxable Value: ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.purchaseTaxablePaise)}",
                "Input CGST Credit     : ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.inputCgstPaise)}",
                "Input SGST Credit     : ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.inputSgstPaise)}",
                "Input IGST Credit     : ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.inputIgstPaise)}",
                "Net GST Payable/(ITC) : ${TallyAccountingEngine.formatPaiseInr(state.gstSummary.netPayablePaise)}"
            )
        }

        val res = TallyAccountingEngine.exportReportToPdfAndShare(context, company.name, title, lines)
        res.onSuccess {
            _uiState.update { it.copy(statusBanner = "Exported offline PDF: $title ✓") }
        }.onFailure { err ->
            _uiState.update { it.copy(statusBanner = "PDF export failed: ${err.localizedMessage}") }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = TallySimulatorDatabase.getInstance(context.applicationContext)
            return TallySimulatorViewModel(db.tallyDao()) as T
        }
    }
}
