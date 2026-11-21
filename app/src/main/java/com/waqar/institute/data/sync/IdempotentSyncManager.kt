package com.waqar.institute.data.sync

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
 * drop is assigned a deterministic SHA-256 idempotency token over canonical sorted payload keys,
 * deduplicated with last-write-wins timestamp conflict resolution, and committed to Cloud Firestore
 * in chunked batches (<= 450 operations per batch) with `SetOptions.merge()`.
 */
object IdempotentSyncManager {

    private const val MAX_FIRESTORE_BATCH_OPS = 450
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

    /**
     * Computes a deterministic SHA-256 hex digest over sorted key=value pairs of the payload,
     * avoiding JVM `Map.hashCode()` collisions across processes/devices.
     */
    fun canonicalPayloadDigest(payload: Map<String, Any?>): String {
        val canonical = payload.entries
            .filter { it.key != "idempotencyToken" && it.key != "updatedAt" }
            .sortedBy { it.key }
            .joinToString("|") { (k, v) -> "$k=${v?.toString().orEmpty()}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun enqueueIdempotentMutation(
        collectionPath: String,
        documentId: String,
        payload: Map<String, Any?>,
        mutationTimestampMs: Long = System.currentTimeMillis()
    ): String {
        val cleanPayload = payload.filterKeys { it != "idempotencyToken" && it != "updatedAt" }
        val payloadHash = canonicalPayloadDigest(cleanPayload)
        val token = generateIdempotencyToken(collectionPath, documentId, payloadHash)

        // Conflict resolution: only supersede an existing queued mutation for the same document
        // if the incoming mutation timestamp is >= the existing queued mutation's timestamp.
        val existing = queue.firstOrNull {
            it.collectionPath == collectionPath && it.documentId == documentId
        }
        if (existing != null && mutationTimestampMs < existing.createdAtEpochMs) {
            return existing.idempotencyKey
        }
        queue.removeAll { it.collectionPath == collectionPath && it.documentId == documentId }
        queue.offer(
            IdempotentMutationOperation(
                idempotencyKey = token,
                collectionPath = collectionPath,
                documentId = documentId,
                payload = cleanPayload,
                createdAtEpochMs = mutationTimestampMs
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
            val chunks = snapshot.chunked(MAX_FIRESTORE_BATCH_OPS)
            for (chunkIndex in chunks.indices) {
                val chunk = chunks[chunkIndex]
                val ok = runCatching {
                    val batch = firestore.batch()
                    chunk.forEach { op ->
                        val ref = firestore.collection(op.collectionPath).document(op.documentId)
                        batch.set(ref, op.payload, SetOptions.merge())
                    }
                    batch.commit().await()
                    committed += chunk.size
                    true
                }.getOrDefault(false)
                if (!ok) {
                    // Re-queue remaining failed chunks in order
                    for (remainingIdx in chunkIndex until chunks.size) {
                        chunks[remainingIdx].forEach { queue.offer(it) }
                    }
                    break
                }
            }
            if (committed > 0) {
                ReactiveUdfStateFlowManager.dispatch(
                    EnterprisePortalIntent.MarkBackgroundSyncCompleted(System.currentTimeMillis())
                )
            }
            ReactiveUdfStateFlowManager.dispatch(
                EnterprisePortalIntent.UpdatePendingMutations(queue.size)
            )
            committed
        }
    }
}
