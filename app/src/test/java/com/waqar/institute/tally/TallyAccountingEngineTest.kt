package com.waqar.institute.tally

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PortalRole
import com.example.ui.portal.allowedDrawerDestinationsForRole
import com.waqar.institute.navigation.AppDrawer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TallyAccountingEngineTest {

    @Test
    fun doubleEntryValidation_acceptsBalanced_andRejectsUnbalancedOrZero() {
        val balanced = TallyAccountingEngine.validateDoubleEntry(
            listOf(
                TallyAccountingEngine.VoucherDraftLine(1L, "Rent Expense", "DR", 500_000L),
                TallyAccountingEngine.VoucherDraftLine(2L, "Cash", "CR", 500_000L)
            )
        )
        assertTrue(balanced.isValid)
        assertEquals(500_000L, balanced.totalDebitPaise)
        assertEquals(0L, balanced.differencePaise)

        val unbalanced = TallyAccountingEngine.validateDoubleEntry(
            listOf(
                TallyAccountingEngine.VoucherDraftLine(1L, "Rent Expense", "DR", 500_000L),
                TallyAccountingEngine.VoucherDraftLine(2L, "Cash", "CR", 450_000L)
            )
        )
        assertFalse(unbalanced.isValid)
        assertEquals(50_000L, unbalanced.differencePaise)

        val zeroAmount = TallyAccountingEngine.validateDoubleEntry(
            listOf(
                TallyAccountingEngine.VoucherDraftLine(1L, "Rent Expense", "DR", 0L),
                TallyAccountingEngine.VoucherDraftLine(2L, "Cash", "CR", 0L)
            )
        )
        assertFalse(zeroAmount.isValid)
    }

    @Test
    fun gstAutoCalculation_splitsIntraStateCgstSgst_andInterStateIgstExactly() {
        val intra = TallyAccountingEngine.computeGstSplit(
            taxableAmountPaise = 1_000_000L, // ₹10,000.00
            gstRatePercent = 18,
            companyState = "Uttar Pradesh",
            partyState = "Uttar Pradesh"
        )
        assertTrue(intra.isIntraState)
        assertEquals(90_000L, intra.cgstPaise)
        assertEquals(90_000L, intra.sgstPaise)
        assertEquals(0L, intra.igstPaise)
        assertEquals(1_180_000L, intra.grandTotalPaise)

        val inter = TallyAccountingEngine.computeGstSplit(
            taxableAmountPaise = 1_000_000L,
            gstRatePercent = 18,
            companyState = "Uttar Pradesh",
            partyState = "Maharashtra"
        )
        assertFalse(inter.isIntraState)
        assertEquals(0L, inter.cgstPaise)
        assertEquals(0L, inter.sgstPaise)
        assertEquals(180_000L, inter.igstPaise)
        assertEquals(1_180_000L, inter.grandTotalPaise)
    }

    @Test
    fun financialReports_trialBalance_profitAndLoss_balanceSheet_andStockSummaryTally() {
        val groups = TallyStandardMasters.STANDARD_28_GROUPS.mapIndexed { idx, def ->
            TallyGroupEntity(
                id = idx + 1L,
                companyId = 1L,
                name = def.name,
                parentGroupName = def.parentGroupName,
                nature = def.nature,
                isGrossProfitAffecting = def.isGrossProfitAffecting
            )
        }
        val cashLedger = TallyLedgerEntity(1L, 1L, "Cash", 27L, "Cash-in-Hand", "ASSETS", 0L, "DR")
        val capitalLedger = TallyLedgerEntity(2L, 1L, "Owner Capital", 1L, "Capital Account", "LIABILITIES", 0L, "CR")
        val salesLedger = TallyLedgerEntity(3L, 1L, "Sales A/c", 10L, "Sales Accounts", "INCOME", 0L, "CR")
        val rentLedger = TallyLedgerEntity(4L, 1L, "Office Rent", 15L, "Indirect Expenses", "EXPENSES", 0L, "DR")

        val lines = listOf(
            // Capital introduced ₹50,000
            TallyVoucherLineEntity(1L, 1L, 1L, cashLedger.id, cashLedger.name, "DR", 5_000_000L),
            TallyVoucherLineEntity(2L, 1L, 1L, capitalLedger.id, capitalLedger.name, "CR", 5_000_000L),
            // Cash sales ₹20,000
            TallyVoucherLineEntity(3L, 2L, 1L, cashLedger.id, cashLedger.name, "DR", 2_000_000L),
            TallyVoucherLineEntity(4L, 2L, 1L, salesLedger.id, salesLedger.name, "CR", 2_000_000L),
            // Rent paid ₹5,000
            TallyVoucherLineEntity(5L, 3L, 1L, rentLedger.id, rentLedger.name, "DR", 500_000L),
            TallyVoucherLineEntity(6L, 3L, 1L, cashLedger.id, cashLedger.name, "CR", 500_000L)
        )

        val balances = TallyAccountingEngine.computeLedgerBalances(
            listOf(cashLedger, capitalLedger, salesLedger, rentLedger),
            lines
        )
        val tb = TallyAccountingEngine.computeTrialBalance(balances)
        assertTrue(tb.isBalanced)
        assertEquals(7_000_000L, tb.totalDebitPaise)
        assertEquals(7_000_000L, tb.totalCreditPaise)

        val pnl = TallyAccountingEngine.computeProfitAndLoss(balances, groups)
        assertEquals(2_000_000L, pnl.grossProfitPaise)
        assertEquals(1_500_000L, pnl.netProfitPaise)

        val bs = TallyAccountingEngine.computeBalanceSheet(balances, pnl)
        assertTrue(bs.isBalanced)
        assertEquals(6_500_000L, bs.totalAssetsSidePaise)
        assertEquals(6_500_000L, bs.totalLiabilitiesSidePaise)

        val stockItem = TallyStockItemEntity(10L, 1L, "USB Keyboard", "Nos", "8471", 18, 10.0, 50_000L)
        val invLines = listOf(
            TallyInventoryLineEntity(1L, 1L, 1L, 10L, "USB Keyboard", "IN", 10.0, 60_000L, 600_000L, 18),
            TallyInventoryLineEntity(2L, 2L, 1L, 10L, "USB Keyboard", "OUT", 5.0, 55_000L, 275_000L, 18)
        )
        val stockRows = TallyAccountingEngine.computeStockSummary(listOf(stockItem), invLines)
        assertEquals(1, stockRows.size)
        assertEquals(15.0, stockRows.first().closingQty, 0.001)
        assertEquals(55_000L, stockRows.first().avgRatePaise)
        assertEquals(825_000L, stockRows.first().closingValuePaise)
    }

    @Test
    fun roomDatabase_startsEmpty_andSeeds28GroupsPlusCashAndPnlOnlyOnUserCompanyCreation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TallySimulatorDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = db.tallyDao()
            assertTrue("Fresh simulator DB must start with zero dummy companies", dao.getAllCompanies().isEmpty())

            val companyId = dao.createCompanyWithStandardMasters(
                TallyCompanyEntity(name = "Student Practice Co", stateName = "Uttar Pradesh")
            )
            assertEquals(1, dao.getAllCompanies().size)
            assertEquals(28, dao.getGroups(companyId).size)
            val defaultLedgers = dao.getLedgers(companyId).map { it.name }.toSet()
            assertEquals(setOf("Cash", "Profit & Loss A/c"), defaultLedgers)
        } finally {
            db.close()
        }
    }

    @Test
    fun drawerNavigation_includesTallySimulator_andTermsAndConditionsStrictlyLastForAll4Portals() {
        for (role in PortalRole.entries) {
            val items = allowedDrawerDestinationsForRole(role)
            assertTrue(
                "Role $role must include Tally Practice Simulator",
                items.any { it.routeId == AppDrawer.ROUTE_TALLY_SIMULATOR && it.title == "Tally Practice Simulator" }
            )
            val last = items.last()
            assertEquals(
                "Role $role must have Terms & Conditions strictly as the LAST drawer item",
                AppDrawer.ROUTE_TERMS_AND_CONDITIONS,
                last.routeId
            )
            assertEquals("Terms & Conditions", last.title)
        }
    }
}
