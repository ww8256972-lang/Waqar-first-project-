package com.waqar.institute.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.waqar.institute.data.repository.OfflineFirstRepository
import com.waqar.institute.data.sync.IdempotentSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Reliable Background Execution & Periodic Offline Sync Worker (`InstituteBackgroundSyncWorker.kt`).
 *
 * Uses Android Jetpack `WorkManager` (`CoroutineWorker`) with exponential backoff and network
 * constraints to guarantee offline attendance queues, fee ledger mutations, and study assignment
 * uploads sync reliably even after app exit or device reboot.
 */
class InstituteBackgroundSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val offlineRepo = OfflineFirstRepository(applicationContext)
            val flushedAttendance = offlineRepo.flushUnsyncedOfflineQueue()
            val flushedMutations = IdempotentSyncManager.flushPendingMutations()

            Result.success(
                workDataOf(
                    KEY_SYNCED_ATTENDANCE_COUNT to flushedAttendance,
                    KEY_SYNCED_MUTATIONS_COUNT to flushedMutations,
                    KEY_SYNC_TIMESTAMP_MS to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            if (runAttemptCount < 4) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf("error" to (e.localizedMessage ?: "Background sync failed"))
                )
            }
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK_NAME = "wci_periodic_enterprise_sync"
        private const val UNIQUE_IMMEDIATE_WORK_NAME = "wci_immediate_enterprise_sync"

        const val KEY_SYNCED_ATTENDANCE_COUNT = "synced_attendance_count"
        const val KEY_SYNCED_MUTATIONS_COUNT = "synced_mutations_count"
        const val KEY_SYNC_TIMESTAMP_MS = "sync_timestamp_ms"

        fun schedulePeriodicEnterpriseSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<InstituteBackgroundSyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }

        fun triggerImmediateSyncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeRequest = OneTimeWorkRequestBuilder<InstituteBackgroundSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                oneTimeRequest
            )
        }
    }
}
