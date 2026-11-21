package com.example.ai

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.firestore.InstituteRepository
import com.example.security.HardwareKeystoreVault
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.lsposed.lsparanoid.Obfuscate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

@Entity(
    tableName = "ai_response_cache",
    indices = [
        Index(value = ["featureType", "languageCode"], name = "idx_ai_cache_feature_lang"),
        Index(value = ["lastAccessedMillis"], name = "idx_ai_cache_last_accessed")
    ]
)
data class CachedAiResponseEntity(
    @PrimaryKey val promptHash: String,
    val featureType: String,
    val languageCode: String,
    val promptText: String,
    val responseText: String,
    val modelName: String,
    val hitCount: Int = 1,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastAccessedMillis: Long = System.currentTimeMillis()
)

@Dao
interface AiResponseCacheDao {
    @Query("SELECT * FROM ai_response_cache WHERE promptHash = :promptHash LIMIT 1")
    suspend fun getCachedResponseByHash(promptHash: String): CachedAiResponseEntity?

    @Query("SELECT * FROM ai_response_cache WHERE featureType = :featureType ORDER BY lastAccessedMillis DESC LIMIT :limit")
    suspend fun getRecentEntriesByFeature(featureType: String, limit: Int = 40): List<CachedAiResponseEntity>

    @Query("SELECT * FROM ai_response_cache ORDER BY lastAccessedMillis DESC LIMIT :limit")
    fun observeRecentCachedResponses(limit: Int = 50): Flow<List<CachedAiResponseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedResponse(entry: CachedAiResponseEntity)

    @Query("UPDATE ai_response_cache SET hitCount = hitCount + 1, lastAccessedMillis = :nowMillis WHERE promptHash = :promptHash")
    suspend fun incrementHitCount(promptHash: String, nowMillis: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM ai_response_cache")
    suspend fun countCachedResponses(): Int
}

@Database(
    entities = [CachedAiResponseEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AiResponseCacheDatabase : RoomDatabase() {
    abstract fun aiCacheDao(): AiResponseCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AiResponseCacheDatabase? = null

        fun getDatabase(context: Context): AiResponseCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return@synchronized it }
                val appCtx = context.applicationContext ?: context
                val instance = runCatching {
                    System.loadLibrary("sqlcipher")
                    val passphraseBytes = HardwareKeystoreVault.getOrCreateSqlCipherPassphrase(appCtx)
                    val passphraseCopy = passphraseBytes.copyOf()
                    val supportFactory = SupportOpenHelperFactory(passphraseCopy)
                    val encryptedDb = Room.databaseBuilder(
                        appCtx,
                        AiResponseCacheDatabase::class.java,
                        "waqar_ai_tutor_cache_encrypted.db"
                    )
                        .openHelperFactory(supportFactory)
                        .build()
                    encryptedDb.openHelper.writableDatabase
                    Arrays.fill(passphraseBytes, 0.toByte())
                    encryptedDb
                }.getOrElse {
                    Room.databaseBuilder(
                        appCtx,
                        AiResponseCacheDatabase::class.java,
                        "waqar_ai_tutor_cache_local.db"
                    ).build()
                }
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * 3-Tier Sub-Millisecond & Shared AI Response Cache Repository:
 * - L1: In-memory `ConcurrentHashMap` (< 1ms zero-lag lookup + token similarity index)
 * - L2: Local Room + SQLCipher `AiResponseCacheDatabase` (persistent encrypted lookup)
 * - L3: Global Shared Firestore Cache (`ai_shared_cache/{canonicalSha256}`) with
 *   mathematical prompt canonicalization + token-set Jaccard/Cosine similarity (`>= 0.78`)
 *   so rephrased questions hit cached answers at ₹0 API cost across all 100,000 students.
 */
@Obfuscate
object AiLocalCacheRepository {
    const val SHARED_CACHE_COLLECTION = "ai_shared_cache"
    const val SIMILARITY_THRESHOLD = 0.78

    private val l1HotMemoryCache = ConcurrentHashMap<String, String>()
    private val l1CanonicalTokenIndex = ConcurrentHashMap<String, Pair<Set<String>, String>>()

    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "what", "which", "who", "whom",
        "please", "pls", "plz", "tell", "me", "explain", "describe", "define", "about",
        "in", "of", "for", "to", "on", "with", "and", "or", "how", "do", "does", "can",
        "you", "give", "example", "kya", "hai", "hain", "batao", "samjhao", "ke", "ka", "ki"
    )

    @Volatile
    private var attachedContext: Context? = null

    fun attachContext(context: Context) {
        if (attachedContext == null) {
            attachedContext = context.applicationContext ?: context
        }
    }

    fun tokenizeCanonicalPrompt(rawPrompt: String): Set<String> {
        val rawTokens = rawPrompt
            .lowercase()
            .replace(Regex("[^a-z0-9+#.\\s]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in STOPWORDS }
        if (rawTokens.isNotEmpty()) return rawTokens.toSet()
        return rawPrompt
            .lowercase()
            .replace(Regex("[^a-z0-9+#.\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun canonicalizePrompt(
        featureType: String,
        languageCode: String,
        rawPrompt: String
    ): String {
        val sortedTokens = tokenizeCanonicalPrompt(rawPrompt).sorted()
        val body = if (sortedTokens.isNotEmpty()) {
            sortedTokens.joinToString(" ")
        } else {
            rawPrompt.trim().lowercase().replace(Regex("\\s+"), " ")
        }
        return "${featureType.trim().uppercase()}|${languageCode.trim().uppercase()}|$body"
    }

    fun computeCanonicalPromptHash(
        featureType: String,
        languageCode: String,
        rawPrompt: String
    ): String {
        val normalized = "${featureType.trim().uppercase()}|${languageCode.trim().uppercase()}|${
            rawPrompt.trim().lowercase().replace(Regex("\\s+"), " ")
        }"
        return sha256Hex(normalized)
    }

    fun computeSharedCacheSha256(
        featureType: String,
        languageCode: String,
        rawPrompt: String
    ): String = sha256Hex(canonicalizePrompt(featureType, languageCode, rawPrompt))

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Mathematical Jaccard similarity: |A ∩ B| / |A ∪ B|.
     */
    fun computeTokenJaccardSimilarity(tokensA: Set<String>, tokensB: Set<String>): Double {
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /**
     * Mathematical Cosine similarity over binary term-presence vectors:
     * |A ∩ B| / sqrt(|A| * |B|).
     */
    fun computeTokenCosineSimilarity(tokensA: Set<String>, tokensB: Set<String>): Double {
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val denom = sqrt(tokensA.size.toDouble() * tokensB.size.toDouble())
        return if (denom == 0.0) 0.0 else intersection / denom
    }

    /**
     * Combined Jaccard + Cosine semantic token similarity score used for L1/L2/L3 deduplication.
     */
    fun computePromptSimilarity(promptA: String, promptB: String): Double {
        val tokensA = tokenizeCanonicalPrompt(promptA)
        val tokensB = tokenizeCanonicalPrompt(promptB)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val jaccard = computeTokenJaccardSimilarity(tokensA, tokensB)
        val cosine = computeTokenCosineSimilarity(tokensA, tokensB)
        return maxOf(jaccard, cosine)
    }

    fun getInstantL1CacheHit(
        featureType: String,
        languageCode: String,
        prompt: String
    ): String? {
        val exactHash = computeCanonicalPromptHash(featureType, languageCode, prompt)
        l1HotMemoryCache[exactHash]?.let { return it }

        val canonicalHash = computeSharedCacheSha256(featureType, languageCode, prompt)
        l1HotMemoryCache[canonicalHash]?.let { return it }

        val queryTokens = tokenizeCanonicalPrompt(prompt)
        if (queryTokens.size >= 2) {
            val featurePrefix = "${featureType.trim().uppercase()}|"
            var bestScore = 0.0
            var bestAnswer: String? = null
            for ((indexedKey, pair) in l1CanonicalTokenIndex) {
                if (!indexedKey.startsWith(featurePrefix)) continue
                val score = maxOf(
                    computeTokenJaccardSimilarity(queryTokens, pair.first),
                    computeTokenCosineSimilarity(queryTokens, pair.first)
                )
                if (score >= SIMILARITY_THRESHOLD && score > bestScore) {
                    bestScore = score
                    bestAnswer = pair.second
                }
            }
            if (bestAnswer != null) return bestAnswer
        }
        return null
    }

    private fun resolveContext(context: Context?): Context? {
        context?.let {
            attachContext(it)
            return attachedContext
        }
        attachedContext?.let { return it }
        return runCatching {
            com.google.firebase.FirebaseApp.getInstance().applicationContext.also { attachedContext = it }
        }.getOrNull()
    }

    suspend fun getCachedResponse(
        context: Context?,
        featureType: String,
        languageCode: String,
        prompt: String
    ): String? {
        getInstantL1CacheHit(featureType, languageCode, prompt)?.let { return it }

        val exactHash = computeCanonicalPromptHash(featureType, languageCode, prompt)
        val canonicalHash = computeSharedCacheSha256(featureType, languageCode, prompt)
        val ctx = resolveContext(context) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val dao = AiResponseCacheDatabase.getDatabase(ctx).aiCacheDao()
                val directEntity = dao.getCachedResponseByHash(exactHash)
                    ?: dao.getCachedResponseByHash(canonicalHash)
                if (directEntity != null && directEntity.responseText.isNotBlank()) {
                    indexInL1(featureType, languageCode, prompt, directEntity.responseText)
                    dao.incrementHitCount(directEntity.promptHash, System.currentTimeMillis())
                    return@runCatching directEntity.responseText
                }

                // L2 Similarity Scan over recent encrypted entries for this featureType
                val queryTokens = tokenizeCanonicalPrompt(prompt)
                if (queryTokens.size >= 2) {
                    val candidates = dao.getRecentEntriesByFeature(featureType.trim().uppercase(), 40)
                    var bestScore = 0.0
                    var bestMatch: CachedAiResponseEntity? = null
                    for (entry in candidates) {
                        val candidateTokens = tokenizeCanonicalPrompt(entry.promptText)
                        val score = maxOf(
                            computeTokenJaccardSimilarity(queryTokens, candidateTokens),
                            computeTokenCosineSimilarity(queryTokens, candidateTokens)
                        )
                        if (score >= SIMILARITY_THRESHOLD && score > bestScore) {
                            bestScore = score
                            bestMatch = entry
                        }
                    }
                    if (bestMatch != null && bestMatch.responseText.isNotBlank()) {
                        indexInL1(featureType, languageCode, prompt, bestMatch.responseText)
                        dao.incrementHitCount(bestMatch.promptHash, System.currentTimeMillis())
                        return@runCatching bestMatch.responseText
                    }
                }
                null
            }.getOrNull()
        }
    }

    /**
     * Stage 3 (L3): Global Shared Firestore Cache (`ai_shared_cache/{canonicalSha256}`)
     * with exact canonical SHA-256 lookup + token-set Jaccard/Cosine similarity (`>= 0.78`).
     */
    suspend fun getSharedFirestoreCacheHit(
        context: Context?,
        featureType: String,
        languageCode: String,
        prompt: String
    ): String? {
        if (android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return null
        }
        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (currentUser == null) return null

        val canonicalHash = computeSharedCacheSha256(featureType, languageCode, prompt)
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(3_500L) {
                runCatching {
                    val db = InstituteRepository.getConfiguredFirestore(resolveContext(context))
                    val exactDoc = db.collection(SHARED_CACHE_COLLECTION).document(canonicalHash).get().await()
                    val exactText = exactDoc.getString("responseText")?.takeIf { it.isNotBlank() }
                    if (exactText != null) {
                        putCachedResponse(
                            context = context,
                            featureType = featureType,
                            languageCode = languageCode,
                            prompt = prompt,
                            responseText = exactText,
                            modelName = exactDoc.getString("modelName") ?: "cache/l3-shared",
                            writeToSharedFirestore = false
                        )
                        return@runCatching exactText
                    }

                    // Similarity scan on recent shared L3 entries for rephrased questions
                    val queryTokens = tokenizeCanonicalPrompt(prompt)
                    if (queryTokens.size >= 2) {
                        val snap = db.collection(SHARED_CACHE_COLLECTION)
                            .whereEqualTo("featureType", featureType.trim().uppercase())
                            .limit(35)
                            .get()
                            .await()
                        var bestScore = 0.0
                        var bestText: String? = null
                        var bestModel = "cache/l3-shared-similar"
                        for (doc in snap.documents) {
                            val candidatePrompt = doc.getString("promptText")
                                ?: doc.getString("canonicalPrompt")
                                ?: continue
                            val responseText = doc.getString("responseText")?.takeIf { it.isNotBlank() }
                                ?: continue
                            val candidateTokens = tokenizeCanonicalPrompt(candidatePrompt)
                            val score = maxOf(
                                computeTokenJaccardSimilarity(queryTokens, candidateTokens),
                                computeTokenCosineSimilarity(queryTokens, candidateTokens)
                            )
                            if (score >= SIMILARITY_THRESHOLD && score > bestScore) {
                                bestScore = score
                                bestText = responseText
                                bestModel = doc.getString("modelName") ?: bestModel
                            }
                        }
                        if (bestText != null) {
                            putCachedResponse(
                                context = context,
                                featureType = featureType,
                                languageCode = languageCode,
                                prompt = prompt,
                                responseText = bestText,
                                modelName = bestModel,
                                writeToSharedFirestore = false
                            )
                            return@runCatching bestText
                        }
                    }
                    null
                }.getOrNull()
            }
        }
    }

    private fun indexInL1(
        featureType: String,
        languageCode: String,
        prompt: String,
        responseText: String
    ) {
        val exactHash = computeCanonicalPromptHash(featureType, languageCode, prompt)
        val canonicalHash = computeSharedCacheSha256(featureType, languageCode, prompt)
        l1HotMemoryCache[exactHash] = responseText
        l1HotMemoryCache[canonicalHash] = responseText
        val tokens = tokenizeCanonicalPrompt(prompt)
        if (tokens.isNotEmpty()) {
            val featureKey = "${featureType.trim().uppercase()}|$canonicalHash"
            l1CanonicalTokenIndex[featureKey] = tokens to responseText
        }
    }

    suspend fun putCachedResponse(
        context: Context?,
        featureType: String,
        languageCode: String,
        prompt: String,
        responseText: String,
        modelName: String,
        writeToSharedFirestore: Boolean = true
    ) {
        if (prompt.isBlank() || responseText.isBlank()) return
        val exactHash = computeCanonicalPromptHash(featureType, languageCode, prompt)
        val canonicalHash = computeSharedCacheSha256(featureType, languageCode, prompt)
        indexInL1(featureType, languageCode, prompt, responseText)

        val ctx = resolveContext(context)
        if (ctx != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val dao = AiResponseCacheDatabase.getDatabase(ctx).aiCacheDao()
                    val existing = dao.getCachedResponseByHash(exactHash)
                    dao.upsertCachedResponse(
                        CachedAiResponseEntity(
                            promptHash = exactHash,
                            featureType = featureType.trim().uppercase(),
                            languageCode = languageCode.trim().uppercase(),
                            promptText = prompt.trim().take(4000),
                            responseText = responseText.take(16000),
                            modelName = modelName,
                            hitCount = (existing?.hitCount ?: 0) + 1,
                            createdAtMillis = existing?.createdAtMillis ?: now,
                            lastAccessedMillis = now
                        )
                    )
                }
            }
        }

        // Best-effort L3 write (succeeds for Admin sessions; server Cloud Function writes via Admin SDK for students)
        if (writeToSharedFirestore && !android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (FirebaseAuth.getInstance().currentUser == null) return@runCatching
                    val db = InstituteRepository.getConfiguredFirestore(ctx)
                    val payload = hashMapOf<String, Any>(
                        "promptHash" to canonicalHash,
                        "featureType" to featureType.trim().uppercase(),
                        "languageCode" to languageCode.trim().uppercase(),
                        "canonicalPrompt" to canonicalizePrompt(featureType, languageCode, prompt).take(1000),
                        "promptText" to prompt.trim().take(1000),
                        "responseText" to responseText.take(8000),
                        "modelName" to modelName,
                        "hitCount" to FieldValue.increment(1L),
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    db.collection(SHARED_CACHE_COLLECTION)
                        .document(canonicalHash)
                        .set(payload, SetOptions.merge())
                        .await()
                }
            }
        }
    }

    internal fun clearL1ForTests() {
        l1HotMemoryCache.clear()
        l1CanonicalTokenIndex.clear()
    }
}
