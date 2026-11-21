package com.waqar.institute.tally

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Isolated 100% Offline Tally Practice Simulator Room Database (`wci_tally_practice_simulator.db`).
 *
 * Strictly isolated from the Institute ERP database & Cloud Firestore.
 * Starts 100% empty (ZERO dummy companies, customers, ledgers, or vouchers).
 * Standard 28 Tally Account Groups + `Cash` + `Profit & Loss A/c` are initialized ONLY
 * inside a newly user-created company via [TallySimulatorDao.createCompanyWithStandardMasters].
 */

@Entity(tableName = "tally_companies")
data class TallyCompanyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val stateName: String = "Uttar Pradesh",
    val gstin: String = "",
    val financialYearFrom: String = "2026-04-01",
    val booksBeginFrom: String = "2026-04-01",
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tally_groups",
    foreignKeys = [
        ForeignKey(
            entity = TallyCompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId")]
)
data class TallyGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val companyId: Long,
    val name: String,
    val parentGroupName: String,
    val nature: String, // ASSETS | LIABILITIES | INCOME | EXPENSES
    val isGrossProfitAffecting: Boolean = false
)

@Entity(
    tableName = "tally_ledgers",
    foreignKeys = [
        ForeignKey(
            entity = TallyCompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId"), Index("groupId")]
)
data class TallyLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val companyId: Long,
    val name: String,
    val groupId: Long,
    val groupName: String,
    val nature: String, // ASSETS | LIABILITIES | INCOME | EXPENSES
    val openingBalancePaise: Long = 0L,
    val openingType: String = "DR", // DR | CR
    val gstRatePercent: Int = 0,
    val stateName: String = "",
    val isDefault: Boolean = false
)

@Entity(
    tableName = "tally_units",
    foreignKeys = [
        ForeignKey(
            entity = TallyCompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId")]
)
data class TallyUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val companyId: Long,
    val symbol: String,
    val formalName: String,
    val decimalPlaces: Int = 0
)

@Entity(
    tableName = "tally_stock_items",
    foreignKeys = [
        ForeignKey(
            entity = TallyCompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId")]
)
data class TallyStockItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val companyId: Long,
    val name: String,
    val unitSymbol: String,
    val hsnCode: String = "",
    val gstRatePercent: Int = 18,
    val openingQty: Double = 0.0,
    val openingRatePaise: Long = 0L
)

@Entity(
    tableName = "tally_vouchers",
    foreignKeys = [
        ForeignKey(
            entity = TallyCompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId")]
)
data class TallyVoucherEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val companyId: Long,
    val voucherNumber: String,
    val voucherType: String, // CONTRA | PAYMENT | RECEIPT | JOURNAL | SALES | PURCHASE
    val dateIso: String,
    val narration: String = "",
    val partyLedgerId: Long = 0L,
    val partyStateName: String = "",
    val totalAmountPaise: Long = 0L,
    val taxableAmountPaise: Long = 0L,
    val cgstPaise: Long = 0L,
    val sgstPaise: Long = 0L,
    val igstPaise: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tally_voucher_lines",
    foreignKeys = [
        ForeignKey(
            entity = TallyVoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("voucherId"), Index("companyId"), Index("ledgerId")]
)
data class TallyVoucherLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val voucherId: Long,
    val companyId: Long,
    val ledgerId: Long,
    val ledgerName: String,
    val entryType: String, // DR | CR
    val amountPaise: Long
)

@Entity(
    tableName = "tally_inventory_lines",
    foreignKeys = [
        ForeignKey(
            entity = TallyVoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("voucherId"), Index("companyId"), Index("stockItemId")]
)
data class TallyInventoryLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val voucherId: Long,
    val companyId: Long,
    val stockItemId: Long,
    val stockItemName: String,
    val movementType: String, // IN | OUT
    val quantity: Double,
    val ratePaise: Long,
    val amountPaise: Long,
    val gstRatePercent: Int = 0
)

/**
 * Standard 28 TallyPrime Account Groups (15 Primary Groups + 13 Sub-Groups).
 * Seeded strictly when a user creates their own practice company.
 */
object TallyStandardMasters {
    data class StandardGroupDef(
        val name: String,
        val parentGroupName: String,
        val nature: String,
        val isGrossProfitAffecting: Boolean = false
    )

    val STANDARD_28_GROUPS: List<StandardGroupDef> = listOf(
        // 15 Primary Groups
        StandardGroupDef("Capital Account", "Primary", "LIABILITIES", false),
        StandardGroupDef("Loans (Liability)", "Primary", "LIABILITIES", false),
        StandardGroupDef("Current Liabilities", "Primary", "LIABILITIES", false),
        StandardGroupDef("Fixed Assets", "Primary", "ASSETS", false),
        StandardGroupDef("Investments", "Primary", "ASSETS", false),
        StandardGroupDef("Current Assets", "Primary", "ASSETS", false),
        StandardGroupDef("Branch / Divisions", "Primary", "LIABILITIES", false),
        StandardGroupDef("Misc. Expenses (ASSET)", "Primary", "ASSETS", false),
        StandardGroupDef("Suspense A/c", "Primary", "LIABILITIES", false),
        StandardGroupDef("Sales Accounts", "Primary", "INCOME", true),
        StandardGroupDef("Purchase Accounts", "Primary", "EXPENSES", true),
        StandardGroupDef("Direct Incomes", "Primary", "INCOME", true),
        StandardGroupDef("Direct Expenses", "Primary", "EXPENSES", true),
        StandardGroupDef("Indirect Incomes", "Primary", "INCOME", false),
        StandardGroupDef("Indirect Expenses", "Primary", "EXPENSES", false),
        // 13 Standard Sub-Groups
        StandardGroupDef("Reserves & Surplus", "Capital Account", "LIABILITIES", false),
        StandardGroupDef("Bank OD A/c", "Loans (Liability)", "LIABILITIES", false),
        StandardGroupDef("Secured Loans", "Loans (Liability)", "LIABILITIES", false),
        StandardGroupDef("Unsecured Loans", "Loans (Liability)", "LIABILITIES", false),
        StandardGroupDef("Duties & Taxes", "Current Liabilities", "LIABILITIES", false),
        StandardGroupDef("Provisions", "Current Liabilities", "LIABILITIES", false),
        StandardGroupDef("Sundry Creditors", "Current Liabilities", "LIABILITIES", false),
        StandardGroupDef("Stock-in-Hand", "Current Assets", "ASSETS", false),
        StandardGroupDef("Deposits (Asset)", "Current Assets", "ASSETS", false),
        StandardGroupDef("Loans & Advances (Asset)", "Current Assets", "ASSETS", false),
        StandardGroupDef("Sundry Debtors", "Current Assets", "ASSETS", false),
        StandardGroupDef("Cash-in-Hand", "Current Assets", "ASSETS", false),
        StandardGroupDef("Bank Accounts", "Current Assets", "ASSETS", false)
    )
}

@Dao
interface TallySimulatorDao {
    @Query("SELECT * FROM tally_companies ORDER BY createdAtMs DESC")
    fun observeCompanies(): Flow<List<TallyCompanyEntity>>

    @Query("SELECT * FROM tally_companies ORDER BY createdAtMs DESC")
    suspend fun getAllCompanies(): List<TallyCompanyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompany(company: TallyCompanyEntity): Long

    @Query("DELETE FROM tally_companies WHERE id = :companyId")
    suspend fun deleteCompany(companyId: Long)

    @Query("SELECT * FROM tally_groups WHERE companyId = :companyId ORDER BY name ASC")
    fun observeGroups(companyId: Long): Flow<List<TallyGroupEntity>>

    @Query("SELECT * FROM tally_groups WHERE companyId = :companyId ORDER BY name ASC")
    suspend fun getGroups(companyId: Long): List<TallyGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<TallyGroupEntity>): List<Long>

    @Query("SELECT * FROM tally_ledgers WHERE companyId = :companyId ORDER BY name ASC")
    fun observeLedgers(companyId: Long): Flow<List<TallyLedgerEntity>>

    @Query("SELECT * FROM tally_ledgers WHERE companyId = :companyId ORDER BY name ASC")
    suspend fun getLedgers(companyId: Long): List<TallyLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedger(ledger: TallyLedgerEntity): Long

    @Query("SELECT * FROM tally_units WHERE companyId = :companyId ORDER BY symbol ASC")
    fun observeUnits(companyId: Long): Flow<List<TallyUnitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnit(unit: TallyUnitEntity): Long

    @Query("SELECT * FROM tally_stock_items WHERE companyId = :companyId ORDER BY name ASC")
    fun observeStockItems(companyId: Long): Flow<List<TallyStockItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockItem(item: TallyStockItemEntity): Long

    @Query("SELECT * FROM tally_vouchers WHERE companyId = :companyId ORDER BY dateIso DESC, id DESC")
    fun observeVouchers(companyId: Long): Flow<List<TallyVoucherEntity>>

    @Query("SELECT * FROM tally_voucher_lines WHERE companyId = :companyId ORDER BY id ASC")
    fun observeVoucherLines(companyId: Long): Flow<List<TallyVoucherLineEntity>>

    @Query("SELECT * FROM tally_inventory_lines WHERE companyId = :companyId ORDER BY id ASC")
    fun observeInventoryLines(companyId: Long): Flow<List<TallyInventoryLineEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucher(voucher: TallyVoucherEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucherLines(lines: List<TallyVoucherLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventoryLines(lines: List<TallyInventoryLineEntity>)

    @Query("DELETE FROM tally_vouchers WHERE id = :voucherId")
    suspend fun deleteVoucher(voucherId: Long)

    @Transaction
    suspend fun createCompanyWithStandardMasters(company: TallyCompanyEntity): Long {
        val companyId = insertCompany(company)
        val groupEntities = TallyStandardMasters.STANDARD_28_GROUPS.map { def ->
            TallyGroupEntity(
                companyId = companyId,
                name = def.name,
                parentGroupName = def.parentGroupName,
                nature = def.nature,
                isGrossProfitAffecting = def.isGrossProfitAffecting
            )
        }
        val groupIds = insertGroups(groupEntities)
        val groupIdByName = groupEntities.mapIndexed { idx, g -> g.name to groupIds[idx] }.toMap()

        // TallyPrime standard default ledgers ONLY (`Cash` & `Profit & Loss A/c`) — zero dummy parties
        val cashGroupId = groupIdByName["Cash-in-Hand"] ?: groupIds.first()
        val pnlGroupId = groupIdByName["Reserves & Surplus"] ?: groupIds.first()
        insertLedger(
            TallyLedgerEntity(
                companyId = companyId,
                name = "Cash",
                groupId = cashGroupId,
                groupName = "Cash-in-Hand",
                nature = "ASSETS",
                openingBalancePaise = 0L,
                openingType = "DR",
                isDefault = true
            )
        )
        insertLedger(
            TallyLedgerEntity(
                companyId = companyId,
                name = "Profit & Loss A/c",
                groupId = pnlGroupId,
                groupName = "Reserves & Surplus",
                nature = "LIABILITIES",
                openingBalancePaise = 0L,
                openingType = "CR",
                isDefault = true
            )
        )
        return companyId
    }

    @Transaction
    suspend fun insertVoucherWithLines(
        voucher: TallyVoucherEntity,
        lines: List<TallyVoucherLineEntity>,
        inventoryLines: List<TallyInventoryLineEntity>
    ): Long {
        val voucherId = insertVoucher(voucher)
        if (lines.isNotEmpty()) {
            insertVoucherLines(lines.map { it.copy(voucherId = voucherId) })
        }
        if (inventoryLines.isNotEmpty()) {
            insertInventoryLines(inventoryLines.map { it.copy(voucherId = voucherId) })
        }
        return voucherId
    }
}

@Database(
    entities = [
        TallyCompanyEntity::class,
        TallyGroupEntity::class,
        TallyLedgerEntity::class,
        TallyUnitEntity::class,
        TallyStockItemEntity::class,
        TallyVoucherEntity::class,
        TallyVoucherLineEntity::class,
        TallyInventoryLineEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TallySimulatorDatabase : RoomDatabase() {
    abstract fun tallyDao(): TallySimulatorDao

    companion object {
        const val DB_NAME = "wci_tally_practice_simulator.db"

        @Volatile
        private var INSTANCE: TallySimulatorDatabase? = null

        fun getInstance(context: Context): TallySimulatorDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TallySimulatorDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
        }
    }
}
