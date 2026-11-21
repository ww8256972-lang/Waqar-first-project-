package com.waqar.institute.tally

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure Exact-Paise (`Long`) Double-Entry Accounting & GST Engine (`TallyAccountingEngine.kt`).
 *
 * All monetary figures are represented in integer paise (`100 paise = ₹1.00`) to guarantee
 * zero floating-point drift across Trial Balance, Profit & Loss, Balance Sheet, Stock Summary,
 * and GST Computation reports.
 */
object TallyAccountingEngine {

    @Immutable
    data class VoucherDraftLine(
        val ledgerId: Long,
        val ledgerName: String,
        val entryType: String, // "DR" | "CR"
        val amountPaise: Long
    )

    @Immutable
    data class DoubleEntryValidationResult(
        val isValid: Boolean,
        val totalDebitPaise: Long,
        val totalCreditPaise: Long,
        val differencePaise: Long,
        val errorMessage: String? = null
    )

    @Immutable
    data class GstBreakup(
        val taxableAmountPaise: Long,
        val gstRatePercent: Int,
        val isIntraState: Boolean,
        val cgstPaise: Long,
        val sgstPaise: Long,
        val igstPaise: Long,
        val totalTaxPaise: Long,
        val grandTotalPaise: Long
    )

    @Immutable
    data class LedgerBalanceSummary(
        val ledgerId: Long,
        val ledgerName: String,
        val groupName: String,
        val nature: String,
        val openingDebitPaise: Long,
        val openingCreditPaise: Long,
        val periodDebitPaise: Long,
        val periodCreditPaise: Long,
        val closingDebitPaise: Long,
        val closingCreditPaise: Long
    ) {
        val netSignedPaise: Long get() = closingDebitPaise - closingCreditPaise
    }

    @Immutable
    data class DayBookRow(
        val voucher: TallyVoucherEntity,
        val lines: List<TallyVoucherLineEntity>,
        val particularsSummary: String
    )

    @Immutable
    data class TrialBalanceReport(
        val rows: List<LedgerBalanceSummary>,
        val totalDebitPaise: Long,
        val totalCreditPaise: Long,
        val differencePaise: Long,
        val isBalanced: Boolean
    )

    @Immutable
    data class ProfitAndLossReport(
        val directIncomeRows: List<LedgerBalanceSummary>,
        val directExpenseRows: List<LedgerBalanceSummary>,
        val indirectIncomeRows: List<LedgerBalanceSummary>,
        val indirectExpenseRows: List<LedgerBalanceSummary>,
        val grossProfitPaise: Long, // positive = Gross Profit, negative = Gross Loss
        val netProfitPaise: Long    // positive = Net Profit, negative = Net Loss
    )

    @Immutable
    data class BalanceSheetReport(
        val liabilityRows: List<LedgerBalanceSummary>,
        val assetRows: List<LedgerBalanceSummary>,
        val netProfitPaise: Long,
        val totalLiabilitiesSidePaise: Long,
        val totalAssetsSidePaise: Long,
        val differencePaise: Long,
        val isBalanced: Boolean
    )

    @Immutable
    data class StockSummaryRow(
        val itemId: Long,
        val itemName: String,
        val unitSymbol: String,
        val hsnCode: String,
        val openingQty: Double,
        val inwardQty: Double,
        val outwardQty: Double,
        val closingQty: Double,
        val avgRatePaise: Long,
        val closingValuePaise: Long
    )

    @Immutable
    data class GstSummaryReport(
        val salesTaxablePaise: Long,
        val outputCgstPaise: Long,
        val outputSgstPaise: Long,
        val outputIgstPaise: Long,
        val purchaseTaxablePaise: Long,
        val inputCgstPaise: Long,
        val inputSgstPaise: Long,
        val inputIgstPaise: Long,
        val netPayablePaise: Long
    )

    /**
     * Parses a user-entered rupee string (e.g. `"1250.50"`) into exact integer paise (`125050L`).
     */
    fun parseRupeesToPaise(raw: String): Long {
        val clean = raw.replace(",", "").replace("₹", "").trim()
        if (clean.isEmpty()) return 0L
        val parsed = clean.toDoubleOrNull() ?: return 0L
        if (parsed <= 0.0) return 0L
        return (parsed * 100.0).roundToLong()
    }

    /**
     * Formats exact integer paise (`Long`) into a crisp Indian currency display string.
     */
    fun formatPaiseInr(paise: Long): String {
        val sign = if (paise < 0L) "-" else ""
        val absPaise = abs(paise)
        val rupees = absPaise / 100L
        val rem = absPaise % 100L
        val rupeeStr = formatIndianGrouping(rupees)
        return String.format(Locale.US, "%s₹%s.%02d", sign, rupeeStr, rem)
    }

    private fun formatIndianGrouping(value: Long): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        val prefix = s.dropLast(3)
        val sb = StringBuilder()
        var i = prefix.length
        while (i > 0) {
            val start = (i - 2).coerceAtLeast(0)
            if (sb.isNotEmpty()) sb.insert(0, ',')
            sb.insert(0, prefix.substring(start, i))
            i = start
        }
        return "$sb,$last3"
    }

    /**
     * Enforces strict double-entry integrity: `sum(DR) == sum(CR)`, `totalAmountPaise > 0`,
     * and at least two valid positive lines.
     */
    fun validateDoubleEntry(lines: List<VoucherDraftLine>): DoubleEntryValidationResult {
        if (lines.size < 2) {
            return DoubleEntryValidationResult(
                isValid = false,
                totalDebitPaise = 0L,
                totalCreditPaise = 0L,
                differencePaise = 0L,
                errorMessage = "Double-entry voucher requires at least 1 Debit (Dr) and 1 Credit (Cr) line."
            )
        }
        var totalDr = 0L
        var totalCr = 0L
        for (line in lines) {
            if (line.ledgerId <= 0L || line.ledgerName.isBlank()) {
                return DoubleEntryValidationResult(
                    isValid = false,
                    totalDebitPaise = totalDr,
                    totalCreditPaise = totalCr,
                    differencePaise = abs(totalDr - totalCr),
                    errorMessage = "Every voucher line must select a valid Ledger."
                )
            }
            if (line.amountPaise <= 0L) {
                return DoubleEntryValidationResult(
                    isValid = false,
                    totalDebitPaise = totalDr,
                    totalCreditPaise = totalCr,
                    differencePaise = abs(totalDr - totalCr),
                    errorMessage = "Line amount for '${line.ledgerName}' must be greater than ₹0.00."
                )
            }
            when (line.entryType.trim().uppercase(Locale.US)) {
                "DR" -> totalDr += line.amountPaise
                "CR" -> totalCr += line.amountPaise
                else -> return DoubleEntryValidationResult(
                    isValid = false,
                    totalDebitPaise = totalDr,
                    totalCreditPaise = totalCr,
                    differencePaise = abs(totalDr - totalCr),
                    errorMessage = "Invalid entry side '${line.entryType}' (expected DR or CR)."
                )
            }
        }
        val diff = abs(totalDr - totalCr)
        if (totalDr <= 0L || totalCr <= 0L) {
            return DoubleEntryValidationResult(
                isValid = false,
                totalDebitPaise = totalDr,
                totalCreditPaise = totalCr,
                differencePaise = diff,
                errorMessage = "Voucher total must be greater than ₹0.00."
            )
        }
        if (diff != 0L) {
            return DoubleEntryValidationResult(
                isValid = false,
                totalDebitPaise = totalDr,
                totalCreditPaise = totalCr,
                differencePaise = diff,
                errorMessage = "Unbalanced entry: Dr (${formatPaiseInr(totalDr)}) != Cr (${formatPaiseInr(totalCr)}), Diff ${formatPaiseInr(diff)}."
            )
        }
        return DoubleEntryValidationResult(
            isValid = true,
            totalDebitPaise = totalDr,
            totalCreditPaise = totalCr,
            differencePaise = 0L,
            errorMessage = null
        )
    }

    /**
     * Exact-paise GST auto-calculator:
     * - Intra-state (same state or blank party state): splits GST 50/50 into CGST + SGST.
     * - Inter-state (different state): applies 100% IGST.
     */
    fun computeGstSplit(
        taxableAmountPaise: Long,
        gstRatePercent: Int,
        companyState: String,
        partyState: String
    ): GstBreakup {
        val safeTaxable = taxableAmountPaise.coerceAtLeast(0L)
        val safeRate = gstRatePercent.coerceIn(0, 28)
        val totalTax = ((safeTaxable * safeRate.toLong()) + 50L) / 100L
        val cleanCompState = companyState.trim().lowercase(Locale.US)
        val cleanPartyState = partyState.trim().lowercase(Locale.US)
        val isIntra = cleanPartyState.isEmpty() || cleanCompState.isEmpty() || cleanCompState == cleanPartyState

        val cgst: Long
        val sgst: Long
        val igst: Long
        if (isIntra) {
            cgst = totalTax / 2L
            sgst = totalTax - cgst
            igst = 0L
        } else {
            cgst = 0L
            sgst = 0L
            igst = totalTax
        }
        return GstBreakup(
            taxableAmountPaise = safeTaxable,
            gstRatePercent = safeRate,
            isIntraState = isIntra,
            cgstPaise = cgst,
            sgstPaise = sgst,
            igstPaise = igst,
            totalTaxPaise = totalTax,
            grandTotalPaise = safeTaxable + totalTax
        )
    }

    /**
     * Computes exact opening, period, and closing balances for every ledger in the company.
     */
    fun computeLedgerBalances(
        ledgers: List<TallyLedgerEntity>,
        voucherLines: List<TallyVoucherLineEntity>
    ): List<LedgerBalanceSummary> {
        val linesByLedger = voucherLines.groupBy { it.ledgerId }
        return ledgers.map { ledger ->
            val openDr = if (ledger.openingType.equals("DR", ignoreCase = true)) ledger.openingBalancePaise else 0L
            val openCr = if (ledger.openingType.equals("CR", ignoreCase = true)) ledger.openingBalancePaise else 0L
            val lines = linesByLedger[ledger.id].orEmpty()
            var periodDr = 0L
            var periodCr = 0L
            for (l in lines) {
                if (l.entryType.equals("DR", ignoreCase = true)) periodDr += l.amountPaise
                else periodCr += l.amountPaise
            }
            val net = (openDr + periodDr) - (openCr + periodCr)
            LedgerBalanceSummary(
                ledgerId = ledger.id,
                ledgerName = ledger.name,
                groupName = ledger.groupName,
                nature = ledger.nature,
                openingDebitPaise = openDr,
                openingCreditPaise = openCr,
                periodDebitPaise = periodDr,
                periodCreditPaise = periodCr,
                closingDebitPaise = if (net > 0L) net else 0L,
                closingCreditPaise = if (net < 0L) -net else 0L
            )
        }
    }

    fun buildDayBook(
        vouchers: List<TallyVoucherEntity>,
        lines: List<TallyVoucherLineEntity>
    ): List<DayBookRow> {
        val byVoucher = lines.groupBy { it.voucherId }
        return vouchers.map { v ->
            val vLines = byVoucher[v.id].orEmpty()
            val primaryDr = vLines.firstOrNull { it.entryType == "DR" }?.ledgerName.orEmpty()
            val primaryCr = vLines.firstOrNull { it.entryType == "CR" }?.ledgerName.orEmpty()
            val summary = when {
                primaryDr.isNotBlank() && primaryCr.isNotBlank() -> "$primaryDr  To  $primaryCr"
                v.narration.isNotBlank() -> v.narration
                else -> v.voucherType
            }
            DayBookRow(voucher = v, lines = vLines, particularsSummary = summary)
        }
    }

    fun computeTrialBalance(balances: List<LedgerBalanceSummary>): TrialBalanceReport {
        val activeRows = balances.filter { it.closingDebitPaise > 0L || it.closingCreditPaise > 0L }
        val totalDr = activeRows.sumOf { it.closingDebitPaise }
        val totalCr = activeRows.sumOf { it.closingCreditPaise }
        val diff = abs(totalDr - totalCr)
        return TrialBalanceReport(
            rows = activeRows,
            totalDebitPaise = totalDr,
            totalCreditPaise = totalCr,
            differencePaise = diff,
            isBalanced = diff == 0L
        )
    }

    fun computeProfitAndLoss(
        balances: List<LedgerBalanceSummary>,
        groups: List<TallyGroupEntity>
    ): ProfitAndLossReport {
        val grossGroups = groups.filter { it.isGrossProfitAffecting }.map { it.name.lowercase(Locale.US) }.toSet()
        val directIncomes = mutableListOf<LedgerBalanceSummary>()
        val directExpenses = mutableListOf<LedgerBalanceSummary>()
        val indirectIncomes = mutableListOf<LedgerBalanceSummary>()
        val indirectExpenses = mutableListOf<LedgerBalanceSummary>()

        for (b in balances) {
            if (b.closingDebitPaise == 0L && b.closingCreditPaise == 0L) continue
            val isGross = b.groupName.lowercase(Locale.US) in grossGroups ||
                b.groupName.equals("Sales Accounts", ignoreCase = true) ||
                b.groupName.equals("Purchase Accounts", ignoreCase = true) ||
                b.groupName.equals("Direct Incomes", ignoreCase = true) ||
                b.groupName.equals("Direct Expenses", ignoreCase = true)

            when (b.nature.uppercase(Locale.US)) {
                "INCOME" -> if (isGross) directIncomes += b else indirectIncomes += b
                "EXPENSES" -> if (isGross) directExpenses += b else indirectExpenses += b
            }
        }

        val totalDirectIncome = directIncomes.sumOf { it.closingCreditPaise - it.closingDebitPaise }
        val totalDirectExpense = directExpenses.sumOf { it.closingDebitPaise - it.closingCreditPaise }
        val grossProfit = totalDirectIncome - totalDirectExpense

        val totalIndirectIncome = indirectIncomes.sumOf { it.closingCreditPaise - it.closingDebitPaise }
        val totalIndirectExpense = indirectExpenses.sumOf { it.closingDebitPaise - it.closingCreditPaise }
        val netProfit = grossProfit + totalIndirectIncome - totalIndirectExpense

        return ProfitAndLossReport(
            directIncomeRows = directIncomes,
            directExpenseRows = directExpenses,
            indirectIncomeRows = indirectIncomes,
            indirectExpenseRows = indirectExpenses,
            grossProfitPaise = grossProfit,
            netProfitPaise = netProfit
        )
    }

    fun computeBalanceSheet(
        balances: List<LedgerBalanceSummary>,
        pnl: ProfitAndLossReport
    ): BalanceSheetReport {
        val liabilities = balances.filter {
            it.nature.equals("LIABILITIES", ignoreCase = true) &&
                (it.closingDebitPaise > 0L || it.closingCreditPaise > 0L)
        }
        val assets = balances.filter {
            it.nature.equals("ASSETS", ignoreCase = true) &&
                (it.closingDebitPaise > 0L || it.closingCreditPaise > 0L)
        }

        val baseLiab = liabilities.sumOf { it.closingCreditPaise - it.closingDebitPaise }
        val baseAssets = assets.sumOf { it.closingDebitPaise - it.closingCreditPaise }

        val liabSide = baseLiab + if (pnl.netProfitPaise > 0L) pnl.netProfitPaise else 0L
        val assetSide = baseAssets + if (pnl.netProfitPaise < 0L) -pnl.netProfitPaise else 0L
        val diff = abs(liabSide - assetSide)

        return BalanceSheetReport(
            liabilityRows = liabilities,
            assetRows = assets,
            netProfitPaise = pnl.netProfitPaise,
            totalLiabilitiesSidePaise = liabSide,
            totalAssetsSidePaise = assetSide,
            differencePaise = diff,
            isBalanced = diff == 0L
        )
    }

    fun computeStockSummary(
        items: List<TallyStockItemEntity>,
        inventoryLines: List<TallyInventoryLineEntity>
    ): List<StockSummaryRow> {
        val byItem = inventoryLines.groupBy { it.stockItemId }
        return items.map { item ->
            val moves = byItem[item.id].orEmpty()
            var inwardQty = 0.0
            var inwardValPaise = 0L
            var outwardQty = 0.0
            for (m in moves) {
                if (m.movementType.equals("IN", ignoreCase = true)) {
                    inwardQty += m.quantity
                    inwardValPaise += m.amountPaise
                } else {
                    outwardQty += m.quantity
                }
            }
            val openingValPaise = (item.openingQty * item.openingRatePaise.toDouble()).roundToLong()
            val totalAvailQty = item.openingQty + inwardQty
            val avgRatePaise = if (totalAvailQty > 0.0) {
                ((openingValPaise + inwardValPaise).toDouble() / totalAvailQty).roundToLong()
            } else {
                item.openingRatePaise
            }
            val closingQty = (item.openingQty + inwardQty - outwardQty)
            val closingValPaise = (closingQty * avgRatePaise.toDouble()).roundToLong()

            StockSummaryRow(
                itemId = item.id,
                itemName = item.name,
                unitSymbol = item.unitSymbol,
                hsnCode = item.hsnCode,
                openingQty = item.openingQty,
                inwardQty = inwardQty,
                outwardQty = outwardQty,
                closingQty = closingQty,
                avgRatePaise = avgRatePaise,
                closingValuePaise = closingValPaise
            )
        }
    }

    fun computeGstSummary(vouchers: List<TallyVoucherEntity>): GstSummaryReport {
        var salesTaxable = 0L
        var outCgst = 0L
        var outSgst = 0L
        var outIgst = 0L
        var purchaseTaxable = 0L
        var inCgst = 0L
        var inSgst = 0L
        var inIgst = 0L

        for (v in vouchers) {
            when (v.voucherType.uppercase(Locale.US)) {
                "SALES" -> {
                    salesTaxable += v.taxableAmountPaise
                    outCgst += v.cgstPaise
                    outSgst += v.sgstPaise
                    outIgst += v.igstPaise
                }
                "PURCHASE" -> {
                    purchaseTaxable += v.taxableAmountPaise
                    inCgst += v.cgstPaise
                    inSgst += v.sgstPaise
                    inIgst += v.igstPaise
                }
            }
        }
        val totalOutput = outCgst + outSgst + outIgst
        val totalInput = inCgst + inSgst + inIgst
        return GstSummaryReport(
            salesTaxablePaise = salesTaxable,
            outputCgstPaise = outCgst,
            outputSgstPaise = outSgst,
            outputIgstPaise = outIgst,
            purchaseTaxablePaise = purchaseTaxable,
            inputCgstPaise = inCgst,
            inputSgstPaise = inSgst,
            inputIgstPaise = inIgst,
            netPayablePaise = totalOutput - totalInput
        )
    }

    /**
     * Generates a 100% offline A4 PDF for any Tally Practice report into `context.cacheDir/exports/`
     * and launches the Android share chooser via `FileProvider`.
     */
    fun exportReportToPdfAndShare(
        context: Context,
        companyName: String,
        reportTitle: String,
        lines: List<String>
    ): Result<File> = runCatching {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = reportTitle.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").take(32)
        val outFile = File(exportsDir, "tally_${safeTitle}_${System.currentTimeMillis()}.pdf")

        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val headerPaint = Paint().apply {
            color = Color.parseColor("#004D40")
            style = Paint.Style.FILL
        }
        val headerTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor("#111827")
            textSize = 10f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        val linesPerPage = 44
        val chunks = if (lines.isEmpty()) listOf(listOf("No entries recorded yet.")) else lines.chunked(linesPerPage)
        chunks.forEachIndexed { pageIndex, chunk ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), 64f, headerPaint)
            canvas.drawText("TALLY PRACTICE SIMULATOR — $companyName", 24f, 28f, headerTextPaint)
            canvas.drawText("$reportTitle (Page ${pageIndex + 1}/${chunks.size})", 24f, 48f, headerTextPaint)

            var y = 88f
            for (line in chunk) {
                canvas.drawText(line.take(86), 24f, y, bodyPaint)
                y += 16f
            }
            pdf.finishPage(page)
        }

        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Tally Practice: $reportTitle ($companyName)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Tally Report PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        outFile
    }
}
