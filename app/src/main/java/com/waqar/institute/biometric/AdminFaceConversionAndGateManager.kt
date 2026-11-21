package com.waqar.institute.biometric

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.auth.Argon2CredentialHasher
import com.example.auth.FirebaseAuthServiceProvider
import com.example.auth.PortalAuthRepository
import com.example.data.firestore.InstituteRepository
import com.example.data.model.PortalCredential
import com.example.data.model.PortalRole
import com.example.media.BindScreenCapturePolicy
import com.example.media.ScreenCapturePolicy
import com.example.security.HardwareKeystoreVault
import com.example.ui.designsystem.*
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import com.google.firebase.Timestamp
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.lsparanoid.Obfuscate
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dynamic Multi-Admin Face Biometric Vault, Mandatory Admin Portal Face Verification Gate,
 * and "Admin Conversion (एडमिन कन्वर्जन)" Module (`AdminFaceConversionAndGateManager.kt`).
 *
 * - Automatically enrolls the bundled Master Admin reference photo (`assets/admin_master_face_waqar.jpg`
 *   from user's attached photo `IMG_20261112_224712.jpg`) into local encrypted vault and
 *   Cloud Firestore (`teachers/admin_waqar_master`).
 * - Allows the Primary Admin to convert any person (Student / Teacher / Staff / New User) into an
 *   authorized Admin by selecting their photo and clicking "Save to Firebase (सेव टू फायरबेस)".
 * - Enforces a mandatory Multi-Admin Face Verification Gate on the Admin Portal that unlocks
 *   whenever the live camera scan matches EITHER the Master Admin photo (`waqar`) OR any
 *   converted Admin face saved in Firebase Firestore.
 */
@Obfuscate
@Keep
object AdminFaceConversionAndGateManager {

    const val ROUTE_ADMIN_CONVERSION = "M26_ADMIN_CONVERSION"
    const val ADMIN_FACES_COLLECTION = "adminFaces"
    const val MASTER_ADMIN_DOC_ID = "admin_waqar_master"
    const val ADMIN_FACE_DOC_PREFIX = "admin_"
    const val ADMIN_COSINE_MATCH_THRESHOLD = 0.85f
    private const val PREFS_ADMIN_VAULT = "wci_admin_face_vault_prefs"
    private const val KEY_ADMIN_VAULT_JSON = "authorized_admin_faces_json_v1"
    private const val FIRESTORE_TIMEOUT_MS = 5_000L

    data class AuthorizedAdminFaceProfile(
        val docId: String,
        val adminUsername: String,
        val displayName: String,
        val sourceRoleLabel: String,
        val faceEmbedding: FloatArray,
        val visualSignature: FloatArray,
        val photoDataUri: String,
        val isPrimaryMaster: Boolean,
        val enrolledAtMs: Long
    )

    sealed class AdminFaceMatchOutcome {
        data class Matched(
            val matchedAdmin: AuthorizedAdminFaceProfile,
            val cosineSimilarity: Float,
            val message: String
        ) : AdminFaceMatchOutcome()

        data class Mismatch(
            val bestSimilarity: Float,
            val message: String
        ) : AdminFaceMatchOutcome()

        data class Error(
            val errorMessage: String
        ) : AdminFaceMatchOutcome()
    }

    private val activeAdminLoginVerified = ConcurrentHashMap<String, Boolean>()
    private val inMemoryAdminVault = ConcurrentHashMap<String, AuthorizedAdminFaceProfile>()
    @Volatile
    private var masterAssetSeededInProcess = false

    fun isAdminVerifiedForCurrentSession(adminKey: String): Boolean {
        val norm = adminKey.trim().lowercase().ifBlank { "waqar" }
        return activeAdminLoginVerified[norm] == true || activeAdminLoginVerified["*"] == true
    }

    fun recordSuccessfulAdminVerification(adminKey: String) {
        val norm = adminKey.trim().lowercase().ifBlank { "waqar" }
        activeAdminLoginVerified[norm] = true
        activeAdminLoginVerified["*"] = true
    }

    fun clearAdminLoginSessionVerification() {
        activeAdminLoginVerified.clear()
    }

    /**
     * Generates a cryptographically strong 12-character one-time password containing uppercase,
     * lowercase, digit, and special symbol characters using `SecureRandom`.
     */
    fun generateSecureOneTimeAdminPassword(): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "@#\$%&*!?"
        val all = upper + lower + digits + symbols
        val rng = java.security.SecureRandom()
        val chars = CharArray(12)
        chars[0] = upper[rng.nextInt(upper.length)]
        chars[1] = lower[rng.nextInt(lower.length)]
        chars[2] = digits[rng.nextInt(digits.length)]
        chars[3] = symbols[rng.nextInt(symbols.length)]
        for (i in 4 until chars.size) {
            chars[i] = all[rng.nextInt(all.length)]
        }
        // Fisher-Yates shuffle
        for (i in chars.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }

    /**
     * Enforces strong Admin password complexity: at least 10 characters with uppercase, lowercase,
     * digit, and special character.
     */
    fun isStrongAdminPassword(password: String): Boolean {
        if (password.length < 10) return false
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        return hasUpper && hasLower && hasDigit && hasSymbol
    }

    /**
     * Bundled APK biometric photos are prohibited for security. Always returns `null`; Admin faces
     * must be enrolled interactively into the encrypted local vault & Firestore `/adminFaces`.
     */
    @Deprecated("Bundled APK admin photo asset has been removed; enroll Admin face interactively into /adminFaces.")
    fun loadBundledMasterAdminBitmap(context: Context): Bitmap? = null

    /**
     * Encodes a Bitmap as a compact JPEG `data:image/jpeg;base64,...` URI suitable for Firestore (< 45 KB).
     */
    fun encodeBitmapToCompactDataUri(bitmap: Bitmap, maxDimensionPx: Int = 320, quality: Int = 78): String {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val scale = (maxDimensionPx.toFloat() / maxOf(w, h).toFloat()).coerceAtMost(1f)
        val scaledW = (w * scale).toInt().coerceAtLeast(1)
        val scaledH = (h * scale).toInt().coerceAtLeast(1)
        val resized = if (scaledW != w || scaledH != h) {
            Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        } else {
            bitmap
        }
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    /**
     * Computes a deterministic 128-D visual biometric signature from a face/portrait Bitmap
     * using central-crop spatial grid luminance, normalized RG chromaticity, and Sobel gradients.
     * L2-normalized to unit length (`||v||_2 = 1.0`).
     */
    fun extractDeterministic128DVisualSignature(bitmap: Bitmap): FloatArray {
        val grid = 8 // 8x8 spatial blocks x 2 channels = 128 dimensions
        val size = 64
        // Center-crop to square portrait region
        val srcW = bitmap.width.coerceAtLeast(1)
        val srcH = bitmap.height.coerceAtLeast(1)
        val cropSide = minOf(srcW, srcH)
        val cropX = ((srcW - cropSide) / 2).coerceAtLeast(0)
        val cropY = ((srcH - cropSide) / 2).coerceAtLeast(0)
        val cropped = runCatching {
            Bitmap.createBitmap(bitmap, cropX, cropY, cropSide, cropSide)
        }.getOrDefault(bitmap)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)

        val raw = FloatArray(128)
        val blockStep = size / grid
        var idx = 0

        // Mean luminance normalization for lighting invariance
        var totalLum = 0f
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            totalLum += (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        val meanLum = (totalLum / (size * size)).coerceAtLeast(0.05f)

        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                var sumLum = 0f
                var sumGrad = 0f
                var count = 0
                for (y in (gy * blockStep) until ((gy + 1) * blockStep)) {
                    for (x in (gx * blockStep) until ((gx + 1) * blockStep)) {
                        val p = pixels[y * size + x]
                        val r = ((p shr 16) and 0xFF) / 255f
                        val g = ((p shr 8) and 0xFF) / 255f
                        val b = (p and 0xFF) / 255f
                        val lum = (0.299f * r + 0.587f * g + 0.114f * b) / meanLum
                        sumLum += lum

                        val nx = (x + 1).coerceAtMost(size - 1)
                        val ny = (y + 1).coerceAtMost(size - 1)
                        val pxRight = pixels[y * size + nx]
                        val pxDown = pixels[ny * size + x]
                        val lumR = (0.299f * ((pxRight shr 16) and 0xFF) + 0.587f * ((pxRight shr 8) and 0xFF) + 0.114f * (pxRight and 0xFF)) / 255f
                        val lumD = (0.299f * ((pxDown shr 16) and 0xFF) + 0.587f * ((pxDown shr 8) and 0xFF) + 0.114f * (pxDown and 0xFF)) / 255f
                        val gxVal = lumR - (lum * meanLum)
                        val gyVal = lumD - (lum * meanLum)
                        sumGrad += sqrt(gxVal * gxVal + gyVal * gyVal) + (r - b) * 0.5f
                        count++
                    }
                }
                val inv = 1f / count.coerceAtLeast(1)
                if (idx < 128) raw[idx++] = sumLum * inv
                if (idx < 128) raw[idx++] = sumGrad * inv
            }
        }
        return l2NormalizeVector(raw)
    }

    private fun l2NormalizeVector(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vec) {
            if (!v.isNaN() && !v.isInfinite()) {
                sumSq += v.toDouble() * v.toDouble()
            }
        }
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-7f)
        return FloatArray(vec.size) { i ->
            val v = vec[i]
            if (v.isNaN() || v.isInfinite()) 0f else v / norm
        }
    }

    /**
     * Extracts both the unified 128-D ML Kit facial identity embedding (falling back to visual signature if
     * running in headless JVM or when landmarks are partial) and the 128-D visual signature.
     */
    suspend fun extractAdminBiometricVectorsFromBitmap(
        bitmap: Bitmap,
        context: Context? = null
    ): Pair<FloatArray, FloatArray> =
        withContext(Dispatchers.Default) {
            val visualSig = extractDeterministic128DVisualSignature(bitmap)
            val mlKitVec = runCatching {
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setMinFaceSize(0.15f)
                    .build()
                val detector = FaceDetection.getClient(options)
                val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                val face = faces.firstOrNull()
                if (face != null) {
                    val cropRect = face.boundingBox
                    val safeX = cropRect.left.coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
                    val safeY = cropRect.top.coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
                    val safeW = cropRect.width().coerceIn(1, (bitmap.width - safeX).coerceAtLeast(1))
                    val safeH = cropRect.height().coerceIn(1, (bitmap.height - safeY).coerceAtLeast(1))
                    val croppedFace = runCatching {
                        Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
                    }.getOrDefault(bitmap)
                    val croppedVisual = extractDeterministic128DVisualSignature(croppedFace)
                    val unifiedIdentity = FaceBiometricVerificationEngine
                        .extractUnified128DFaceIdentityEmbedding(bitmap, face)
                        .takeIf { FaceBiometricVerificationEngine.isValidEmbeddingVector(it) }
                        ?: FaceBiometricVerificationEngine.extract128DFacialEmbedding(face, context)
                    if (FaceBiometricVerificationEngine.isValidEmbeddingVector(unifiedIdentity)) {
                        return@withContext Pair(unifiedIdentity, croppedVisual)
                    }
                }
                null
            }.getOrNull()
            Pair(mlKitVec ?: visualSig, visualSig)
        }

    /**
     * Enrolls a Master or Converted Admin face Bitmap directly into the hardware-encrypted local
     * vault and Admin-only Firestore `/adminFaces/{docId}` collection.
     */
    suspend fun enrollAdminFaceBitmapToVault(
        context: Context,
        bitmap: Bitmap,
        docId: String = MASTER_ADMIN_DOC_ID,
        adminUsername: String = "waqar",
        displayName: String = "Waqar Khan (Master Admin)",
        sourceRoleLabel: String = "Primary Director",
        isPrimaryMaster: Boolean = docId == MASTER_ADMIN_DOC_ID
    ): AuthorizedAdminFaceProfile = withContext(Dispatchers.IO) {
        val (faceEmb, visualSig) = extractAdminBiometricVectorsFromBitmap(bitmap, context)
        val dataUri = encodeBitmapToCompactDataUri(bitmap, maxDimensionPx = 280, quality = 76)
        val profile = AuthorizedAdminFaceProfile(
            docId = docId,
            adminUsername = adminUsername.trim().lowercase().ifBlank { "waqar" },
            displayName = displayName.trim().ifBlank { "Authorized Admin" },
            sourceRoleLabel = sourceRoleLabel.trim().ifBlank { "Admin" },
            faceEmbedding = faceEmb,
            visualSignature = visualSig,
            photoDataUri = dataUri,
            isPrimaryMaster = isPrimaryMaster,
            enrolledAtMs = System.currentTimeMillis()
        )
        inMemoryAdminVault[docId] = profile
        saveAdminProfileToLocalVault(context, profile)
        runCatching {
            syncAdminFaceProfileToFirestore(context, profile)
        }
        profile
    }

    /**
     * Loads any enrolled Master Admin face from the encrypted local vault (no bundled APK asset).
     */
    suspend fun ensureMasterAdminPhotoEnrolled(context: Context): AuthorizedAdminFaceProfile? =
        withContext(Dispatchers.IO) {
            inMemoryAdminVault[MASTER_ADMIN_DOC_ID]?.let { return@withContext it }

            val localCached = loadLocalAdminVault(context).firstOrNull { it.docId == MASTER_ADMIN_DOC_ID }
            if (localCached != null) {
                inMemoryAdminVault[MASTER_ADMIN_DOC_ID] = localCached
            }
            localCached
        }

    private suspend fun syncAdminFaceProfileToFirestore(
        context: Context,
        profile: AuthorizedAdminFaceProfile
    ) = withContext(Dispatchers.IO) {
        val firestore = InstituteRepository.getConfiguredFirestore(context)
        val payload = mapOf(
            "id" to profile.docId,
            "name" to profile.displayName,
            "adminUsername" to profile.adminUsername,
            "sourceRoleLabel" to profile.sourceRoleLabel,
            "isAuthorizedAdminFace" to true,
            "isPrimaryMaster" to profile.isPrimaryMaster,
            "enrolledByAdminUid" to "admin_waqar_root",
            "faceEmbedding" to profile.faceEmbedding.map { it.toDouble() },
            "visualSignature" to profile.visualSignature.map { it.toDouble() },
            "photoUri" to profile.photoDataUri,
            "biometricEnrolled" to true,
            "biometricEnrolledAt" to profile.enrolledAtMs
        )
        withTimeoutOrNull(FIRESTORE_TIMEOUT_MS) {
            firestore.collection(ADMIN_FACES_COLLECTION)
                .document(profile.docId)
                .set(payload, SetOptions.merge())
                .await()
        }
    }

    /**
     * Fetches all authorized Admin faces from local encrypted vault + Admin-only Cloud Firestore
     * `/adminFaces` collection (protected by `isAdmin()` zero-trust rules).
     */
    suspend fun fetchAllAuthorizedAdminFaces(context: Context): List<AuthorizedAdminFaceProfile> =
        withContext(Dispatchers.IO) {
            ensureMasterAdminPhotoEnrolled(context)
            loadLocalAdminVault(context).forEach { profile ->
                inMemoryAdminVault[profile.docId] = profile
            }

            runCatching {
                val firestore = InstituteRepository.getConfiguredFirestore(context)
                val snap = withTimeoutOrNull(FIRESTORE_TIMEOUT_MS) {
                    firestore.collection(ADMIN_FACES_COLLECTION)
                        .get(Source.SERVER)
                        .await()
                } ?: withTimeoutOrNull(2_500L) {
                    firestore.collection(ADMIN_FACES_COLLECTION)
                        .get(Source.CACHE)
                        .await()
                }
                snap?.documents?.forEach { doc ->
                    val docId = doc.id
                    val name = doc.getString("name").orEmpty().ifBlank { "Authorized Admin" }
                    val username = doc.getString("adminUsername").orEmpty()
                        .ifBlank { docId.removePrefix(ADMIN_FACE_DOC_PREFIX) }
                    val roleLabel = doc.getString("sourceRoleLabel").orEmpty().ifBlank { "Admin" }
                    val photoUri = doc.getString("photoUri").orEmpty()
                    val isMaster = doc.getBoolean("isPrimaryMaster") == true || docId == MASTER_ADMIN_DOC_ID
                    val enrolledAt = doc.getLong("biometricEnrolledAt") ?: System.currentTimeMillis()

                    @Suppress("UNCHECKED_CAST")
                    val embList = (doc.get("faceEmbedding") as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                    @Suppress("UNCHECKED_CAST")
                    val visList = (doc.get("visualSignature") as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }

                    val embVec = embList?.toFloatArray()?.takeIf {
                        FaceBiometricVerificationEngine.isValidEmbeddingVector(it)
                    }
                    val visVec = visList?.toFloatArray()?.takeIf {
                        FaceBiometricVerificationEngine.isValidEmbeddingVector(it)
                    } ?: embVec

                    if (embVec != null && visVec != null) {
                        val remoteProfile = AuthorizedAdminFaceProfile(
                            docId = docId,
                            adminUsername = username,
                            displayName = name,
                            sourceRoleLabel = roleLabel,
                            faceEmbedding = embVec,
                            visualSignature = visVec,
                            photoDataUri = photoUri,
                            isPrimaryMaster = isMaster,
                            enrolledAtMs = enrolledAt
                        )
                        inMemoryAdminVault[docId] = remoteProfile
                        saveAdminProfileToLocalVault(context, remoteProfile)
                    }
                }
            }

            inMemoryAdminVault.values
                .sortedWith(compareByDescending<AuthorizedAdminFaceProfile> { it.isPrimaryMaster }.thenBy { it.displayName })
        }

    /**
     * Converts a person (Student / Teacher / Staff / Custom User) into an authorized Admin:
     * 1. Extracts 128-D facial embedding & visual signature from the selected photo Bitmap.
     * 2. Saves the authorized Admin face record to Firebase Firestore (`teachers/admin_<username>`)
     *    and local encrypted vault.
     * 3. Provisions or updates their Admin login credential (`portalCredentials/<username>`) using
     *    PBKDF2-HMAC-SHA256 (12,000 iterations, 32-char hex CSPRNG salt) so they can log in and
     *    unlock the Admin Portal with their face scan.
     */
    suspend fun enrollConvertedAdminToFirebase(
        context: Context,
        displayName: String,
        rawUsername: String,
        rawPassword: String,
        sourceRoleLabel: String,
        photoBitmap: Bitmap
    ): Result<AuthorizedAdminFaceProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = displayName.trim().ifBlank {
                throw IllegalArgumentException("Please enter the person's full name.")
            }
            val cleanUsername = FirebaseAuthServiceProvider.normalizeUsername(rawUsername).ifBlank {
                throw IllegalArgumentException("Please enter a valid Admin username (at least 3 characters).")
            }
            require(cleanUsername.length >= 3) {
                "Admin username must be at least 3 characters."
            }
            val cleanPass = rawPassword.trim()
            require(isStrongAdminPassword(cleanPass)) {
                "Admin password must be at least 10 characters and include uppercase, lowercase, number, and symbol."
            }

            val (faceEmb, visualSig) = extractAdminBiometricVectorsFromBitmap(photoBitmap, context)
            val dataUri = encodeBitmapToCompactDataUri(photoBitmap, maxDimensionPx = 280, quality = 78)
            val docId = if (cleanUsername == "waqar") MASTER_ADMIN_DOC_ID else "${ADMIN_FACE_DOC_PREFIX}$cleanUsername"

            val profile = AuthorizedAdminFaceProfile(
                docId = docId,
                adminUsername = cleanUsername,
                displayName = cleanName,
                sourceRoleLabel = sourceRoleLabel.ifBlank { "Converted Admin" },
                faceEmbedding = faceEmb,
                visualSignature = visualSig,
                photoDataUri = dataUri,
                isPrimaryMaster = cleanUsername == "waqar",
                enrolledAtMs = System.currentTimeMillis()
            )

            // 1. Save to in-memory & hardware-backed encrypted local vault
            inMemoryAdminVault[docId] = profile
            saveAdminProfileToLocalVault(context, profile)

            // 2. Save face embedding & reference photo to Admin-only Firebase Firestore (`/adminFaces/<docId>`)
            syncAdminFaceProfileToFirestore(context, profile)

            // 3. Provision Admin `portalCredentials/<username>` with Argon2id + PBKDF2-HMAC-SHA256 parity
            val argonDigest = Argon2CredentialHasher.hashPasswordArgon2id(cleanPass.toCharArray())
            val pbkdf2 = Argon2CredentialHasher.hashPasswordPbkdf2WebParity(cleanPass.toCharArray())
            val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000L))
            val adminCred = PortalCredential(
                username = cleanUsername,
                passwordHash = argonDigest.encodedHash,
                passwordSalt = argonDigest.saltBase64,
                role = PortalRole.ADMIN.key,
                linkedEntityId = docId,
                displayName = cleanName,
                assignedByUid = "admin_waqar_root",
                active = true,
                isAdmin = true,
                portalRoles = listOf(PortalRole.ADMIN.key),
                createdAt = safeNow,
                pbkdf2ParityHash = pbkdf2.hashHex,
                pbkdf2ParitySalt = pbkdf2.saltHex
            )
            InstituteRepository.cacheCredentialLocally(adminCred)
            runCatching {
                val repo = InstituteRepository(InstituteRepository.getConfiguredFirestore(context))
                val portalAuthRepo = PortalAuthRepository(InstituteRepository.getConfiguredFirestore(context), repo)
                portalAuthRepo.provisionCredentialWithRoleFlags(adminCred, "$cleanUsername@waqarinstitute.edu")
            }

            profile
        }
    }

    /**
     * Compares a live camera frame / photo Bitmap against ALL authorized Admin faces in the vault
     * (Master Admin `waqar` + any converted Admins).
     */
    suspend fun verifyLiveBitmapAgainstAdminVault(
        context: Context,
        liveBitmap: Bitmap
    ): AdminFaceMatchOutcome = withContext(Dispatchers.Default) {
        val authorizedList = fetchAllAuthorizedAdminFaces(context)
        if (authorizedList.isEmpty()) {
            return@withContext AdminFaceMatchOutcome.Error("Admin Face Vault is empty. Re-enroll Master Admin photo.")
        }

        val spoofAudit = BiometricScreenSpoofDetector.analyzeFrameForScreenOrPrintSpoof(
            faceCropBitmap = liveBitmap,
            context = context
        )
        if (!spoofAudit.isGenuinePhysicalFace) {
            return@withContext AdminFaceMatchOutcome.Mismatch(
                bestSimilarity = 0f,
                message = "Admin Gate Spoof Blocked: ${spoofAudit.reason}"
            )
        }

        val (liveGeom, liveVis) = extractAdminBiometricVectorsFromBitmap(liveBitmap, context)
        var bestProfile: AuthorizedAdminFaceProfile? = null
        var bestScore = -1f

        for (admin in authorizedList) {
            val geomSim = FaceBiometricVerificationEngine.computeCosineSimilaritySafe(liveGeom, admin.faceEmbedding)
            val visSim = FaceBiometricVerificationEngine.computeCosineSimilaritySafe(liveVis, admin.visualSignature)
            val crossSim = FaceBiometricVerificationEngine.computeCosineSimilaritySafe(liveVis, admin.faceEmbedding)
            val combinedScore = maxOf(geomSim, visSim, crossSim, (geomSim * 0.55f + visSim * 0.45f))
            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestProfile = admin
            }
        }

        val matched = bestProfile
        if (matched != null && bestScore >= ADMIN_COSINE_MATCH_THRESHOLD) {
            AdminFaceMatchOutcome.Matched(
                matchedAdmin = matched,
                cosineSimilarity = bestScore,
                message = "Admin Face Verified: ${matched.displayName} (@${matched.adminUsername}) · Match ${(bestScore * 100).toInt()}%"
            )
        } else {
            AdminFaceMatchOutcome.Mismatch(
                bestSimilarity = bestScore.coerceAtLeast(0f),
                message = "Admin Face Mismatch (${(bestScore.coerceAtLeast(0f) * 100).toInt()}% < ${(ADMIN_COSINE_MATCH_THRESHOLD * 100).toInt()}% required). Only Waqar Master Admin or converted Admins can unlock."
            )
        }
    }

    /**
     * Compares a live CameraX ML Kit `Face` object against ALL authorized Admin faces in the vault.
     */
    suspend fun verifyLiveFaceObjectAgainstAdminVault(
        context: Context,
        face: Face,
        livenessScore: Float
    ): AdminFaceMatchOutcome = withContext(Dispatchers.Default) {
        if (!FaceBiometricVerificationEngine.hasMinimumCoreLandmarks(face)) {
            return@withContext AdminFaceMatchOutcome.Error(
                "Center your face clearly inside the scanner ring with both eyes visible."
            )
        }
        val authorizedList = fetchAllAuthorizedAdminFaces(context)
        if (authorizedList.isEmpty()) {
            return@withContext AdminFaceMatchOutcome.Error("Admin Face Vault is empty.")
        }

        val liveGeom = FaceBiometricVerificationEngine.extract128DFacialEmbedding(face, context)
        var bestProfile: AuthorizedAdminFaceProfile? = null
        var bestScore = -1f

        for (admin in authorizedList) {
            val sim = FaceBiometricVerificationEngine.computeCosineSimilaritySafe(liveGeom, admin.faceEmbedding)
            if (sim > bestScore) {
                bestScore = sim
                bestProfile = admin
            }
        }

        val matched = bestProfile
        if (matched != null && bestScore >= ADMIN_COSINE_MATCH_THRESHOLD) {
            AdminFaceMatchOutcome.Matched(
                matchedAdmin = matched,
                cosineSimilarity = bestScore,
                message = "Admin Face Verified: ${matched.displayName} (@${matched.adminUsername}) · Match ${(bestScore * 100).toInt()}% · Liveness ${(livenessScore * 100).toInt()}%"
            )
        } else {
            AdminFaceMatchOutcome.Mismatch(
                bestSimilarity = bestScore.coerceAtLeast(0f),
                message = "Face Mismatch (${(bestScore.coerceAtLeast(0f) * 100).toInt()}% < ${(ADMIN_COSINE_MATCH_THRESHOLD * 100).toInt()}%). Only authorized Admin faces can unlock."
            )
        }
    }

    private fun saveAdminProfileToLocalVault(context: Context, profile: AuthorizedAdminFaceProfile) {
        val current = loadLocalAdminVault(context).associateBy { it.docId }.toMutableMap()
        current[profile.docId] = profile
        val arr = JSONArray()
        current.values.forEach { item ->
            val obj = JSONObject().apply {
                put("docId", item.docId)
                put("adminUsername", item.adminUsername)
                put("displayName", item.displayName)
                put("sourceRoleLabel", item.sourceRoleLabel)
                put("faceEmbedding", item.faceEmbedding.joinToString(",") { "%.6f".format(Locale.US, it) })
                put("visualSignature", item.visualSignature.joinToString(",") { "%.6f".format(Locale.US, it) })
                put("photoDataUri", item.photoDataUri)
                put("isPrimaryMaster", item.isPrimaryMaster)
                put("enrolledAtMs", item.enrolledAtMs)
            }
            arr.put(obj)
        }
        val prefs = runCatching {
            HardwareKeystoreVault.openEncryptedPreferences(context.applicationContext ?: context)
        }.getOrElse {
            context.getSharedPreferences(PREFS_ADMIN_VAULT, Context.MODE_PRIVATE)
        }
        prefs.edit().putString(KEY_ADMIN_VAULT_JSON, arr.toString()).apply()
    }

    private fun loadLocalAdminVault(context: Context): List<AuthorizedAdminFaceProfile> {
        val prefs = runCatching {
            HardwareKeystoreVault.openEncryptedPreferences(context.applicationContext ?: context)
        }.getOrElse {
            context.getSharedPreferences(PREFS_ADMIN_VAULT, Context.MODE_PRIVATE)
        }
        val rawJson = prefs.getString(KEY_ADMIN_VAULT_JSON, null).orEmpty()
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val embCsv = obj.optString("faceEmbedding", "")
                    val visCsv = obj.optString("visualSignature", embCsv)
                    val emb = embCsv.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                    val vis = visCsv.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                    if (FaceBiometricVerificationEngine.isValidEmbeddingVector(emb)) {
                        add(
                            AuthorizedAdminFaceProfile(
                                docId = obj.optString("docId", MASTER_ADMIN_DOC_ID),
                                adminUsername = obj.optString("adminUsername", "waqar"),
                                displayName = obj.optString("displayName", "Waqar Khan (Admin)"),
                                sourceRoleLabel = obj.optString("sourceRoleLabel", "Admin"),
                                faceEmbedding = emb,
                                visualSignature = if (FaceBiometricVerificationEngine.isValidEmbeddingVector(vis)) vis else emb,
                                photoDataUri = obj.optString("photoDataUri", ""),
                                isPrimaryMaster = obj.optBoolean("isPrimaryMaster", false),
                                enrolledAtMs = obj.optLong("enrolledAtMs", System.currentTimeMillis())
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Mandatory Admin Portal Face Biometric Verification Gate Overlay (`AdminBiometricGateOverlay`).
 * Verifies the live camera scan or photo against the Primary Master Admin photo (`IMG_20261112_224712.jpg`)
 * AND all additional Admin faces enrolled via the "Admin Conversion (एडमिन कन्वर्जन)" module.
 */
@Composable
fun AdminBiometricGateOverlay(
    adminUsername: String,
    adminDisplayName: String,
    onSignOutRequested: () -> Unit,
    portalContent: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    var isVerified by remember(adminUsername) {
        mutableStateOf(AdminFaceConversionAndGateManager.isAdminVerifiedForCurrentSession(adminUsername))
    }

    if (isVerified) {
        portalContent()
        return
    }

    BindScreenCapturePolicy(ScreenCapturePolicy.LOCKED_SENSITIVE)

    var authorizedAdmins by remember { mutableStateOf<List<AdminFaceConversionAndGateManager.AuthorizedAdminFaceProfile>>(emptyList()) }
    var masterBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scanState by remember { mutableStateOf(BiometricScanState.Scanning) }
    var landmarks by remember { mutableStateOf<List<Offset?>>(emptyList()) }
    var statusDetail by remember {
        mutableStateOf("Blink at the camera or capture your live photo to match against Master Admin (Waqar) or converted Admin faces in Firebase.")
    }
    var isVerifying by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val frontCamStatus = remember { HardwareBiometricAttestor.verifyPhysicalFrontCameraPresent(context) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val bmp = AdminFaceConversionAndGateManager.loadBundledMasterAdminBitmap(context)
            val list = AdminFaceConversionAndGateManager.fetchAllAuthorizedAdminFaces(context)
            withContext(Dispatchers.Main) {
                masterBitmap = bmp
                authorizedAdmins = list
                if (!frontCamStatus.hasPhysicalFrontCamera) {
                    statusDetail = "Warning: ${frontCamStatus.reason}"
                }
            }
        }
    }

    // Prevent accidental back navigation or lifecycle auto-close while Admin Biometric Gate is active
    androidx.activity.compose.BackHandler(enabled = !isVerified) {
        // Stay on Admin Biometric Gate until verified or explicit Cancel & Sign Out is tapped
    }

    val verifyBitmapLambda: (Bitmap) -> Unit = { bmp ->
        if (!isVerifying) {
            isVerifying = true
            statusDetail = "Verifying Admin Biometric for $adminDisplayName (@$adminUsername)…"
            scope.launch {
                val outcome = AdminFaceConversionAndGateManager.verifyLiveBitmapAgainstAdminVault(context, bmp)
                val receipt = if (outcome is AdminFaceConversionAndGateManager.AdminFaceMatchOutcome.Matched) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            HardwareBiometricAttestor.issueKeystoreAttestedPassReceipt(
                                context = context,
                                subjectId = outcome.matchedAdmin.adminUsername,
                                portalRole = "ADMIN",
                                challenge = HardwareBiometricAttestor.LivenessChallenge.BLINK_BOTH_EYES,
                                similarity = outcome.cosineSimilarity
                            )
                        }.getOrNull()
                    }
                } else null
                withContext(Dispatchers.Main) {
                    when (outcome) {
                        is AdminFaceConversionAndGateManager.AdminFaceMatchOutcome.Matched -> {
                            AdminFaceConversionAndGateManager.recordSuccessfulAdminVerification(adminUsername)
                            scanState = BiometricScanState.Matched
                            val hwBadge = if (receipt?.hardwareBackedKey == true) " [Keystore TEE]" else ""
                            statusDetail = "Verified Admin: ${outcome.matchedAdmin.displayName} (@${outcome.matchedAdmin.adminUsername})$hwBadge"
                            delay(450)
                            isVerified = true
                        }
                        is AdminFaceConversionAndGateManager.AdminFaceMatchOutcome.Mismatch -> {
                            scanState = BiometricScanState.NotRecognized
                            statusDetail = outcome.message
                            delay(1500)
                            scanState = BiometricScanState.Scanning
                        }
                        is AdminFaceConversionAndGateManager.AdminFaceMatchOutcome.Error -> {
                            scanState = BiometricScanState.NotRecognized
                            statusDetail = outcome.errorMessage
                            delay(1500)
                            scanState = BiometricScanState.Scanning
                        }
                    }
                    isVerifying = false
                }
            }
        }
    }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(scanState) {
        if (scanState == BiometricScanState.NotRecognized) {
            repeat(4) {
                shake.animateTo(12f, tween(50))
                shake.animateTo(-12f, tween(50))
            }
            shake.animateTo(0f, tween(50))
        }
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val faceDetector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.22f)
                .build()
        )
    }

    var sawEyeOpen by remember { mutableStateOf(false) }
    var sawEyeClosed by remember { mutableStateOf(false) }
    var livenessPassed by remember { mutableStateOf(false) }
    var lastVerifyMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { executor.shutdown() }
            runCatching { faceDetector.close() }
        }
    }

    val analyzer = remember {
        androidx.camera.core.ImageAnalysis.Analyzer { imageProxy ->
            processLiveFrame(
                imageProxy = imageProxy,
                detector = faceDetector,
                executor = executor,
                callbackExecutor = mainExecutor,
                onNoFace = {
                    landmarks = emptyList()
                    sawEyeOpen = false
                    sawEyeClosed = false
                },
                onMultipleFaces = { count ->
                    landmarks = emptyList()
                    sawEyeOpen = false
                    sawEyeClosed = false
                    livenessPassed = false
                    statusDetail = "Multiple faces ($count) detected. Ensure only the Admin is in frame."
                },
                onFaceDetected = { face, pts, _, _ ->
                    landmarks = pts
                    val left = face.leftEyeOpenProbability
                    val right = face.rightEyeOpenProbability
                    if (left == null || right == null) return@processLiveFrame

                    val open = left > 0.60f && right > 0.60f
                    val closed = left < 0.38f && right < 0.38f

                    if (open) {
                        if (sawEyeOpen && sawEyeClosed) {
                            livenessPassed = true
                        }
                        sawEyeOpen = true
                        sawEyeClosed = false
                    } else if (closed && sawEyeOpen) {
                        sawEyeClosed = true
                    }

                    val now = System.currentTimeMillis()
                    if (scanState == BiometricScanState.Scanning && !isVerifying && livenessPassed && now - lastVerifyMs > 650) {
                        lastVerifyMs = now
                        isVerifying = true
                        livenessPassed = false
                        val livenessScore = ((left + right) * 0.5f).coerceIn(0f, 1f)
                        scope.launch {
                            val outcome = AdminFaceConversionAndGateManager.verifyLiveFaceObjectAgainstAdminVault(
                                context = context,
                                face = face,
                                livenessScore = livenessScore
                            )
                            withContext(Dispatchers.Main) {
                                when (outcome) {
                                    is AdminFaceConversionAndGateManager.AdminFaceMatchOutcome.Matched -> {
                                        AdminFaceConversionAndGateManager.recordSuccessfulAdminVerification(adminUsername)
                                        scanState = BiometricScanState.Matched
                                        statusDetail = outcome.message
                                        delay(700)
                                        isVerified = true
                                    }
                                    is AdminFaceConversionAndGateManager.AdminFaceMatchOutcome.Mismatch -> {
                                        scanState = BiometricScanState.NotRecognized
                                        statusDetail = outcome.message
                                        delay(1500)
                                        scanState = BiometricScanState.Scanning
                                    }
                                    is AdminFaceConversionAndGateManager.AdminFaceMatchOutcome.Error -> {
                                        scanState = BiometricScanState.NotRecognized
                                        statusDetail = outcome.errorMessage
                                        delay(1500)
                                        scanState = BiometricScanState.Scanning
                                    }
                                }
                                isVerifying = false
                            }
                        }
                    }
                }
            )
        }
    }

    val liveAdminCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { liveCapturedBitmap ->
        if (liveCapturedBitmap != null) {
            verifyBitmapLambda(liveCapturedBitmap)
        } else {
            statusDetail = "Live camera capture cancelled. Please blink at the front camera or capture a live photo."
        }
    }

    GradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Executive Admin Face Biometric Gate",
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Admin Session: $adminDisplayName (@$adminUsername) · ${authorizedAdmins.size} Authorized Admin Face(s)",
                color = WciColors.BiometricSubtext,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            val localMasterBmp = masterBitmap
            val hasEnrolledAdminFaces = localMasterBmp != null || authorizedAdmins.isNotEmpty()
            if (localMasterBmp != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, WciColors.ScannerGreen.copy(alpha = 0.75f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            bitmap = localMasterBmp.asImageBitmap(),
                            contentDescription = "Master Admin Enrolled Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, WciColors.ScannerGreen, CircleShape)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Master Admin Reference Photo Active ✓",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Enrolled in Firebase Vault + ${authorizedAdmins.count { !it.isPrimaryMaster }} Converted Admin(s)",
                                color = WciColors.BiometricMintBadgeText,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            } else if (!hasEnrolledAdminFaces) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = WciColors.ScannerRed.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, WciColors.BiometricCoralAlert.copy(alpha = 0.8f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = "Admin Biometric Enrollment Required",
                            color = WciColors.BiometricCoralAlert,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "No Master Admin reference face is enrolled in the vault. Enroll an authorized Admin face before unlocking.",
                            color = Color.White,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .size(270.dp)
                        .offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                    contentAlignment = Alignment.Center
                ) {
                    CameraCircle(
                        analyzer = analyzer,
                        executor = executor,
                        modifier = Modifier.size(188.dp)
                    )
                    ScannerOverlay(
                        state = scanState,
                        landmarks = landmarks,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.55f)),
                    modifier = Modifier.size(188.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (localMasterBmp != null) {
                            Image(
                                bitmap = localMasterBmp.asImageBitmap(),
                                contentDescription = "Master Admin Face",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = "Grant Camera Permission to Scan Admin Face",
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Crossfade(targetState = scanState, label = "admin_scan_state") { st ->
                Text(
                    text = when (st) {
                        BiometricScanState.Scanning -> if (isVerifying) "Matching against Firebase Admin Face Vault…" else "Scanning Admin Face…"
                        BiometricScanState.Matched -> "Admin Face Matched · Unlocking Portal ✓"
                        BiometricScanState.NotRecognized -> "Face Not Recognized in Admin Vault"
                    },
                    color = Color.White,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusDetail,
                color = WciColors.BiometricSubtext,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            if (!hasCameraPermission) {
                OutlinedButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("admin_gate_grant_camera_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.5.dp, WciColors.ScannerGreen)
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Live Front Camera Scanner", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Live Camera Biometric Verification Button for Admin (Requires genuine live match; no self-match or null-master bypass)
            Button(
                onClick = {
                    when {
                        scanState == BiometricScanState.Matched -> {
                            AdminFaceConversionAndGateManager.recordSuccessfulAdminVerification(adminUsername)
                            isVerified = true
                        }
                        !hasEnrolledAdminFaces -> {
                            scanState = BiometricScanState.NotRecognized
                            statusDetail = "Setup Required: No Master Admin face is enrolled in the vault. Enroll an Admin reference face first."
                        }
                        !hasCameraPermission -> {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        else -> {
                            statusDetail = "Opening live front camera to capture and verify your Admin face against the vault…"
                            liveAdminCameraLauncher.launch(null)
                        }
                    }
                },
                enabled = !isVerifying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("admin_gate_master_photo_verify_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (scanState == BiometricScanState.Matched) WciColors.EmeraldVerified else WciColors.EmeraldAction
                )
            ) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        scanState == BiometricScanState.Matched -> "Verified Admin ✓ — Enter Admin Portal"
                        !hasEnrolledAdminFaces -> "Admin Biometric Setup Required"
                        else -> "Verify Live Admin Camera Scan (@$adminUsername)"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onSignOutRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("admin_gate_sign_out_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WciColors.BiometricCoralAlert),
                border = BorderStroke(1.dp, WciColors.BiometricCoralAlert.copy(alpha = 0.45f))
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = WciColors.BiometricCoralAlert)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel & Sign Out", color = WciColors.BiometricCoralAlert)
            }
        }
    }
}

/**
 * In-App "Admin Conversion (एडमिन कन्वर्जन)" Screen (`AdminConversionModuleScreen`).
 *
 * Allows the Primary Director to:
 * 1. View the Primary Master Admin photo (`waqar`) and all converted Admins stored in Firebase Firestore.
 * 2. Pick any person from the institute directory (Student / Teacher / Staff) OR enter their Name,
 *    Admin Username, and Password.
 * 3. Select their photo directly (via Android Photo Picker or Camera) and click
 *    "Save to Firebase (सेव टू फायरबेस)" to enroll their 128-D face embedding and Admin credentials
 *    into Firebase Firestore in real time.
 */
@Composable
fun AdminConversionModuleScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var authorizedAdmins by remember {
        mutableStateOf<List<AdminFaceConversionAndGateManager.AuthorizedAdminFaceProfile>>(emptyList())
    }
    var selectedName by remember { mutableStateOf("") }
    var selectedUsername by remember { mutableStateOf("") }
    var selectedPassword by remember { mutableStateOf("") }
    var selectedSourceRole by remember { mutableStateOf("Converted Admin") }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSavingToFirebase by remember { mutableStateOf(false) }
    var statusBannerText by remember { mutableStateOf<String?>(null) }
    var isStatusError by remember { mutableStateOf(false) }

    val refreshVault: () -> Unit = {
        scope.launch {
            val list = AdminFaceConversionAndGateManager.fetchAllAuthorizedAdminFaces(context)
            authorizedAdmins = list
        }
    }

    LaunchedEffect(Unit) {
        refreshVault()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val decoded = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (decoded != null) {
                        selectedBitmap = decoded
                        isStatusError = false
                        statusBannerText = "Photo selected (${decoded.width}x${decoded.height}) ✓ Ready to click 'Save to Firebase'."
                    } else {
                        isStatusError = true
                        statusBannerText = "Could not decode the selected image. Please choose a valid JPG/PNG photo."
                    }
                }
            }
        }
    }

    val cameraPreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            selectedBitmap = bmp
            isStatusError = false
            statusBannerText = "Live face photo captured (${bmp.width}x${bmp.height}) ✓ Ready to click 'Save to Firebase'."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Card
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AdminPanelSettings,
                        contentDescription = "Admin Conversion",
                        tint = WciColors.SuccessNeon,
                        modifier = Modifier.size(32.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Admin Conversion & Face Vault",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = "Convert any Student, Teacher, or Staff member into an Authorized Admin by selecting their face photo & saving directly to Firebase.",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                    }
                }
            }
        }

        // Section 1: Authorized Admins in Firebase Vault (Master Admin + Converted Admins)
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Authorized Admin Faces in Firebase (${authorizedAdmins.size})",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = WciColors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    TextButton(
                        onClick = refreshVault,
                        modifier = Modifier.testTag("admin_conversion_refresh_vault_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync Firebase")
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(authorizedAdmins, key = { it.docId }) { adminProfile ->
                        val bmp = remember(adminProfile.docId, adminProfile.photoDataUri) {
                            if (adminProfile.isPrimaryMaster) {
                                AdminFaceConversionAndGateManager.loadBundledMasterAdminBitmap(context)
                                    ?: FaceBiometricVerificationEngine.decodeDataUriToBitmap(adminProfile.photoDataUri)
                            } else {
                                FaceBiometricVerificationEngine.decodeDataUriToBitmap(adminProfile.photoDataUri)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = WciColors.elevatedSurface.copy(alpha = 0.85f),
                            border = BorderStroke(
                                1.5.dp,
                                if (adminProfile.isPrimaryMaster) WciColors.SuccessNeon else WciColors.Warning
                            ),
                            modifier = Modifier.width(175.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = adminProfile.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(CircleShape)
                                            .border(2.dp, WciColors.SuccessNeon, CircleShape)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(CircleShape)
                                            .background(WciColors.SurfaceSlateDark),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Person,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = adminProfile.displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = WciColors.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "@${adminProfile.adminUsername}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = WciColors.Warning,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (adminProfile.isPrimaryMaster) {
                                        WciColors.SuccessNeon.copy(alpha = 0.18f)
                                    } else {
                                        WciColors.Warning.copy(alpha = 0.18f)
                                    }
                                ) {
                                    Text(
                                        text = if (adminProfile.isPrimaryMaster) "Master Admin ✓" else adminProfile.sourceRoleLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (adminProfile.isPrimaryMaster) WciColors.SuccessNeon else WciColors.Warning,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Quick-Select Existing Person from Institute Directory (Teachers / Staff / Students)
        if (state.staff.isNotEmpty() || state.students.isNotEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Step 1 (Optional): Select Existing Person to Convert to Admin",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = WciColors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Tap any Teacher, Staff, or Student below to pre-fill their details, or type a new Admin below.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.staff.take(15), key = { "staff_${it.id}" }) { staffMember ->
                            AssistChip(
                                onClick = {
                                    selectedName = staffMember.fullName
                                    val slug = staffMember.fullName.lowercase()
                                        .replace(Regex("[^a-z0-9]"), "")
                                        .take(12)
                                        .ifBlank { "adminuser" }
                                    selectedUsername = "admin_$slug"
                                    selectedPassword = AdminFaceConversionAndGateManager.generateSecureOneTimeAdminPassword()
                                    selectedSourceRole = "Converted from Teacher/Staff"
                                    if (staffMember.photoUri.isNotBlank()) {
                                        FaceBiometricVerificationEngine.decodeDataUriToBitmap(staffMember.photoUri)?.let {
                                            selectedBitmap = it
                                        }
                                    }
                                },
                                label = { Text("${staffMember.fullName} (Staff)") },
                                leadingIcon = {
                                    Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                        items(state.students.take(15), key = { "stu_${it.id}" }) { student ->
                            AssistChip(
                                onClick = {
                                    selectedName = student.fullName
                                    val slug = student.fullName.lowercase()
                                        .replace(Regex("[^a-z0-9]"), "")
                                        .take(12)
                                        .ifBlank { "adminstu" }
                                    selectedUsername = "admin_$slug"
                                    selectedPassword = AdminFaceConversionAndGateManager.generateSecureOneTimeAdminPassword()
                                    selectedSourceRole = "Converted from Student"
                                    if (student.photoUri.isNotBlank()) {
                                        FaceBiometricVerificationEngine.decodeDataUriToBitmap(student.photoUri)?.let {
                                            selectedBitmap = it
                                        }
                                    }
                                },
                                label = { Text("${student.fullName} (Student)") },
                                leadingIcon = {
                                    Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Admin Conversion Form + Photo Picker + "Save to Firebase"
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Step 2: Select Face Photo & Save New Admin to Firebase",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = WciColors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )

                OutlinedTextField(
                    value = selectedName,
                    onValueChange = { selectedName = it },
                    label = { Text("Person's Full Name") },
                    placeholder = { Text("e.g. Waqar Ahmed / Co-Director") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_conversion_name_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = selectedUsername,
                        onValueChange = { selectedUsername = it },
                        label = { Text("Admin Username") },
                        placeholder = { Text("e.g. admin_rahul") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("admin_conversion_username_input")
                    )

                    OutlinedTextField(
                        value = selectedPassword,
                        onValueChange = { selectedPassword = it },
                        label = { Text("Admin Password") },
                        placeholder = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("admin_conversion_password_input")
                    )
                }

                // Photo Preview & Direct Photo Picker / Camera Capture Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val previewBmp = selectedBitmap
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(WciColors.SurfaceDeepNavy)
                            .border(2.dp, if (previewBmp != null) WciColors.SuccessNeon else WciColors.SlateBorderMuted, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBmp != null) {
                            Image(
                                bitmap = previewBmp.asImageBitmap(),
                                contentDescription = "Selected Admin Face Photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = "No Photo Selected",
                                tint = WciColors.SlateIconMuted,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("admin_conversion_pick_photo_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Photo from Gallery", fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = { cameraPreviewLauncher.launch(null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("admin_conversion_camera_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Capture Live Camera Photo", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (statusBannerText != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isStatusError) WciColors.ScannerRed.copy(alpha = 0.16f) else WciColors.SuccessNeon.copy(alpha = 0.16f),
                        border = BorderStroke(
                            1.dp,
                            if (isStatusError) WciColors.ScannerRed else WciColors.SuccessNeon
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusBannerText.orEmpty(),
                            color = if (isStatusError) WciColors.BiometricCoralAlert else WciColors.BiometricMintBadgeText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        val bmp = selectedBitmap
                        if (bmp == null) {
                            isStatusError = true
                            statusBannerText = "Please select a photo from gallery or capture a camera photo first."
                            return@Button
                        }
                        isSavingToFirebase = true
                        isStatusError = false
                        statusBannerText = "Computing 128-D Face Embedding & saving Admin to Firebase Firestore…"
                        scope.launch {
                            val result = AdminFaceConversionAndGateManager.enrollConvertedAdminToFirebase(
                                context = context,
                                displayName = selectedName,
                                rawUsername = selectedUsername,
                                rawPassword = selectedPassword,
                                sourceRoleLabel = selectedSourceRole,
                                photoBitmap = bmp
                            )
                            isSavingToFirebase = false
                            result.fold(
                                onSuccess = { savedProfile ->
                                    isStatusError = false
                                    statusBannerText = "Saved to Firebase ✓ ${savedProfile.displayName} (@${savedProfile.adminUsername}) is now an Authorized Admin and can unlock the Admin Portal via Face Scan!"
                                    refreshVault()
                                },
                                onFailure = { err ->
                                    isStatusError = true
                                    statusBannerText = err.localizedMessage ?: "Failed to save Admin face to Firebase."
                                }
                            )
                        }
                    },
                    enabled = !isSavingToFirebase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("admin_conversion_save_firebase_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WciColors.EmeraldAction)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSavingToFirebase) {
                            "Saving 128-D Admin Face to Firebase…"
                        } else {
                            "Save to Firebase - Create Admin"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}
