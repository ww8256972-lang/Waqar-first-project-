package com.waqar.institute.data.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.waqar.institute.ui.state.EnterprisePortalIntent
import com.waqar.institute.ui.state.ReactiveUdfStateFlowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

data class IdempotentMutationOperation(
    val idempotencyKey: String,
    val collectionPath: String,
    val documentId: String,
    val payload: Map<String, Any?>,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Idempotent Offline Mutation Queue & Conflict-Resolution Engine (`IdempotentSyncManager.kt`).
 *
 * Guarantees that any fee payment, attendance mark, or exam submission queued during a network
 * drop is assigned a deterministic SHA-256 idempotency token and committed to Cloud Firestore
 * with `SetOptions.merge()` so retries never duplicate ledger entries.
 */
object IdempotentSyncManager {

    private val queue = ConcurrentLinkedQueue<IdempotentMutationOperation>()
    private val flushMutex = Mutex()

    fun generateIdempotencyToken(
        collection: String,
        entityId: String,
        logicalTimestampOrSlot: String
    ): String {
        val raw = "$collection|$entityId|$logicalTimestampOrSlot"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(14).joinToString("") { "%02x".format(it) }
    }

    fun enqueueIdempotentMutation(
        collectionPath: String,
        documentId: String,
        payload: Map<String, Any?>
    ): String {
        val token = generateIdempotencyToken(collectionPath, documentId, payload.hashCode().toString())
        val cleanPayload = payload.filterKeys { it != "idempotencyToken" && it != "updatedAt" }
        queue.removeAll { it.collectionPath == collectionPath && it.documentId == documentId }
        queue.offer(
            IdempotentMutationOperation(
                idempotencyKey = token,
                collectionPath = collectionPath,
                documentId = documentId,
                payload = cleanPayload
            )
        )
        ReactiveUdfStateFlowManager.dispatch(
            EnterprisePortalIntent.UpdatePendingMutations(queue.size)
        )
        return token
    }

    suspend fun flushPendingMutations(
        firestore: FirebaseFirestore = com.example.data.firestore.InstituteRepository.getConfiguredFirestore()
    ): Int = withContext(Dispatchers.IO) {
        flushMutex.withLock {
            if (queue.isEmpty()) return@withLock 0
            var committed = 0
            val snapshot = mutableListOf<IdempotentMutationOperation>()
            while (true) {
                val next = queue.poll() ?: break
                snapshot.add(next)
            }
            runCatching {
                val batch = firestore.batch()
                snapshot.forEach { op ->
                    val ref = firestore.collection(op.collectionPath).document(op.documentId)
                    batch.set(ref, op.payload, SetOptions.merge())
                }
                batch.commit().await()
                committed = snapshot.size
                ReactiveUdfStateFlowManager.dispatch(
                    EnterprisePortalIntent.MarkBackgroundSyncCompleted(System.currentTimeMillis())
                )
            }.onFailure {
                // Re-queue on transient failure
                snapshot.forEach { queue.offer(it) }
            }
            ReactiveUdfStateFlowManager.dispatch(
                EnterprisePortalIntent.UpdatePendingMutations(queue.size)
            )
            committed
        }
    }
}
