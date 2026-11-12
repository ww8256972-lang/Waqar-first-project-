package com.waqar.institute.database

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enterprise Multi-Threaded Room & Cloud Firestore Cache Optimization (`InstituteDatabaseModule`).
 *
 * Configures:
 * 1. Dedicated Multi-Threaded Query Pool: High-throughput pool with 4-8 background threads
 *    named `wci-db-worker-#` for parallel local and cloud database reads/writes.
 * 2. Aggressive Firestore Persistent Caching: `CACHE_SIZE_UNLIMITED` using modern `PersistentCacheSettings`,
 *    allowing instant offline reads and immediate UI rendering even under zero network connectivity.
 */
object InstituteDatabaseModule {

    private val threadIndex = AtomicInteger(1)

    private val databaseExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
        ThreadFactory { runnable ->
            Thread(runnable, "wci-db-worker-${threadIndex.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    )

    /**
     * Dedicated CoroutineDispatcher for database queries and heavy offline sync.
     */
    val databaseDispatcher: CoroutineDispatcher = databaseExecutor.asCoroutineDispatcher()

    /**
     * Applies aggressive Firestore caching with unlimited persistent cache.
     */
    fun configureOptimizedFirestoreCache(firestore: FirebaseFirestore) {
        runCatching {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder()
                        .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                        .build()
                )
                .build()
            firestore.firestoreSettings = settings
        }
    }
}
