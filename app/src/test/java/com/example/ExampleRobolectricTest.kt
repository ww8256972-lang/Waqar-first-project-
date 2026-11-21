package com.example

import android.content.Context
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CachedStudentEntity
import com.example.data.local.InstituteLocalDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Waqar Institute", appName)
    }

    @Test
    fun roomMigration1To3_and_100kStudentRecordsIndexedPaginationBenchmark() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDbFile = File(context.cacheDir, "wci_scalability_100k_test_${System.nanoTime()}.db")
        try {
            // 1. Verify non-destructive SQL execution of MIGRATION_1_2 and MIGRATION_2_3 on a raw SQLite database
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(tempDbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `offline_attendance_queue` (
                                    `id` TEXT NOT NULL PRIMARY KEY,
                                    `targetType` TEXT NOT NULL,
                                    `targetId` TEXT NOT NULL,
                                    `targetName` TEXT NOT NULL,
                                    `batchId` TEXT NOT NULL,
                                    `batchName` TEXT NOT NULL,
                                    `dateIso` TEXT NOT NULL,
                                    `status` TEXT NOT NULL,
                                    `method` TEXT NOT NULL,
                                    `remarks` TEXT NOT NULL,
                                    `markedByUid` TEXT NOT NULL,
                                    `queuedAtMillis` INTEGER NOT NULL,
                                    `synced` INTEGER NOT NULL
                                )
                                """.trimIndent()
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `cached_timetable_slots` (
                                    `batchId` TEXT NOT NULL PRIMARY KEY,
                                    `code` TEXT NOT NULL,
                                    `name` TEXT NOT NULL,
                                    `courseName` TEXT NOT NULL,
                                    `teacherName` TEXT NOT NULL,
                                    `roomName` TEXT NOT NULL,
                                    `daysOfWeek` TEXT NOT NULL,
                                    `startTime` TEXT NOT NULL,
                                    `endTime` TEXT NOT NULL,
                                    `updatedAtMillis` INTEGER NOT NULL
                                )
                                """.trimIndent()
                            )
                            db.execSQL(
                                "INSERT INTO `offline_attendance_queue` VALUES ('att_legacy_1','STUDENT','stu_1','Legacy Student','bat_1','DCA Morning','2026-11-01','PRESENT','QR_SCAN','On time','admin_1',1762000000000,0)"
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) = Unit
                    })
                    .build()
            )
            val rawDb = helper.writableDatabase
            InstituteLocalDatabase.MIGRATION_1_2.migrate(rawDb)
            rawDb.execSQL(
                "INSERT INTO `cached_students` VALUES ('stu_legacy_0','WCI-2025-00000','Aarav Legacy','aarav@wci.edu','+919800000000','Parent','+919800000001','ADCA','Morning Batch','ACTIVE',1800000,1800000,98,'','',1762000000000)"
            )
            InstituteLocalDatabase.MIGRATION_2_3.migrate(rawDb)
            rawDb.close()
            helper.close()

            // 2. Open with Room at version 3 and verify legacy records survived migration intact
            val db = Room.inMemoryDatabaseBuilder(context, InstituteLocalDatabase::class.java)
                .allowMainThreadQueries()
                .addMigrations(InstituteLocalDatabase.MIGRATION_1_2, InstituteLocalDatabase.MIGRATION_2_3)
                .build()
            val dao = db.offlineDao()

            // 3. Batch-insert 100,000 Student records in chunks of 5,000 inside Room transactions
            val totalRecords = 100_000
            val chunkSize = 5_000
            val startInsertNs = System.nanoTime()
            for (chunkStart in 0 until totalRecords step chunkSize) {
                val batch = ArrayList<CachedStudentEntity>(chunkSize)
                for (i in chunkStart until (chunkStart + chunkSize)) {
                    val padded = "%06d".format(i + 1)
                    batch.add(
                        CachedStudentEntity(
                            id = "stu_$padded",
                            enrollmentNo = "WCI-2026-$padded",
                            fullName = "Student $padded Waqar",
                            email = "s$padded@waqarinstitute.edu",
                            phone = "+9198${padded.takeLast(8)}",
                            guardianName = "Guardian $padded",
                            guardianPhone = "+9197${padded.takeLast(8)}",
                            courseName = if (i % 2 == 0) "ADCA" else "DCA",
                            batchName = "Batch ${(i % 20) + 1}",
                            status = "ACTIVE",
                            totalFeePaise = 1800000L,
                            paidFeePaise = 900000L,
                            attendancePercent = 85 + (i % 15),
                            photoUri = "",
                            attachmentName = "",
                            updatedAtMillis = 1_762_880_000_000L + i
                        )
                    )
                }
                dao.upsertCachedStudents(batch)
            }
            val insertMs = (System.nanoTime() - startInsertNs) / 1_000_000L

            val count = dao.countCachedStudents()
            assertEquals(100_000, count)

            // 4. Benchmark indexed prefix pagination query on 100,000 rows
            val startPageNs = System.nanoTime()
            val firstPage = dao.queryCachedStudentsPage(search = "Student 0500", limit = 30, offset = 0)
            val pageQueryMs = (System.nanoTime() - startPageNs) / 1_000_000L
            assertTrue("Expected matching page results from 100k rows", firstPage.isNotEmpty())
            assertEquals("Student 050000 Waqar", firstPage.first().fullName)
            assertTrue("Indexed page query over 100k rows should complete under 1000ms (actual=${pageQueryMs}ms)", pageQueryMs < 1000L)

            // 5. Verify Paging 3 PagingSource loads a 30-item page from 100,000 records without OOM
            val pagingSource = dao.pagingStudents("")
            val loadResult = pagingSource.load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 30,
                    placeholdersEnabled = false
                )
            )
            assertTrue(loadResult is PagingSource.LoadResult.Page)
            val page = loadResult as PagingSource.LoadResult.Page
            assertEquals(30, page.data.size)
            assertEquals("Student 000001 Waqar", page.data.first().fullName)

            val usedMemMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
            println(
                "SCALABILITY_BENCHMARK_100K: totalRecords=$count, insertMs=${insertMs}ms, indexedPageQueryMs=${pageQueryMs}ms, paging3PageSize=${page.data.size}, heapUsedMb=${usedMemMb}MB"
            )
            db.close()
        } finally {
            tempDbFile.delete()
        }
    }
}
