package com.example.biometric

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.security.HardwareKeystoreVault
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Facial Biometric Enrollment & Feature Vector Extractor (`StudentFaceEnrollmentManager.kt`).
 *
 * Uses Google ML Kit Face Detection (`com.google.mlkit:face-detection`) to:
 * 1. Detect a student's face in their admission photo or live CameraX selfie.
 * 2. Extract a normalized 12-dimensional geometric facial feature embedding vector
 *    (inter-ocular ratio, nose-to-eye bridge ratios, mouth-to-eye geometry, head Euler angles,
 *    and eye-open / smiling liveness probabilities).
 * 3. Compute Cosine Similarity between the enrolled admission face embedding and the live
 *    pre-exam selfie to verify identity (`similarity >= 0.85f`) and block proxy exam takers.
 */
data class EnrolledFaceTemplate(
    val studentId: String,
    val featureVector: FloatArray,
    val leftEyeOpenProb: Float,
    val rightEyeOpenProb: Float,
    val smilingProb: Float,
    val enrolledAtEpochMs: Long
)

object StudentFaceEnrollmentManager {
    private const val PREFS_NAME = "wci_biometric_face_templates"
    private const val SECURE_KEY_PREFIX = "stu_bio_face_v2_"
    private const val MAX_HEAD_EULER_DEGREES = 22.0f
    private const val MIN_LIVE_EYE_OPEN = 0.45f

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.18f)
        .build()

    private fun openSecurePrefs(context: Context): android.content.SharedPreferences? {
        return runCatching {
            HardwareKeystoreVault.openEncryptedPreferences(context.applicationContext ?: context)
        }.getOrNull()
    }

    private fun hasMandatoryLandmarks(face: Face): Boolean {
        return face.getLandmark(FaceLandmark.LEFT_EYE) != null &&
            face.getLandmark(FaceLandmark.RIGHT_EYE) != null &&
            face.getLandmark(FaceLandmark.NOSE_BASE) != null &&
            face.getLandmark(FaceLandmark.MOUTH_LEFT) != null &&
            face.getLandmark(FaceLandmark.MOUTH_RIGHT) != null
    }

    /**
     * Extracts a normalized facial feature vector from a base64 `data:image/jpeg;base64,...` photo URI
     * or `Bitmap` and saves it securely tied to `studentId`.
     */
    suspend fun enrollFaceFromPhotoDataUri(
        context: Context,
        studentId: String,
        photoDataUri: String
    ): Result<EnrolledFaceTemplate> = runCatching {
        val bitmap = decodeDataUriToBitmap(photoDataUri)
            ?: throw IllegalArgumentException("Student admission photo could not be decoded.")
        enrollFaceFromBitmap(context, studentId, bitmap).getOrThrow()
    }

    suspend fun enrollFaceFromBitmap(
        context: Context,
        studentId: String,
        bitmap: Bitmap
    ): Result<EnrolledFaceTemplate> = runCatching {
        val detector = FaceDetection.getClient(detectorOptions)
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(image).await()
        require(faces.isNotEmpty()) {
            "No human face detected in photo. Please upload a clear front-facing portrait."
        }
        require(faces.size == 1) {
            "Multiple faces (${faces.size}) detected! Only the registered student must appear in the photo."
        }
        val face = faces.first()
        require(hasMandatoryLandmarks(face)) {
            "Face landmarks unclear in admission portrait. Upload a well-lit front-facing photo."
        }
        val vector = com.waqar.institute.biometric.FaceBiometricVerificationEngine
            .extractUnified128DFaceIdentityEmbedding(bitmap, face)
            .takeIf { com.waqar.institute.biometric.FaceBiometricVerificationEngine.isValidEmbeddingVector(it) }
            ?: extractNormalizedGeometryVector(face)
        val template = EnrolledFaceTemplate(
            studentId = studentId,
            featureVector = vector,
            leftEyeOpenProb = face.leftEyeOpenProbability ?: 0.85f,
            rightEyeOpenProb = face.rightEyeOpenProbability ?: 0.85f,
            smilingProb = face.smilingProbability ?: 0.5f,
            enrolledAtEpochMs = System.currentTimeMillis()
        )
        saveTemplateToVault(context, template)
        template
    }

    /**
     * Compares a live selfie `Bitmap` against the student's enrolled admission photo (or stored template)
     * using Cosine Similarity and ML Kit liveness checks (both eyes open, single face, head pose, core landmarks).
     */
    suspend fun verifyLiveSelfieAgainstStudent(
        context: Context,
        studentId: String,
        admissionPhotoDataUri: String,
        liveSelfieBitmap: Bitmap,
        similarityThreshold: Float = 0.85f
    ): Result<Float> = runCatching {
        val detector = FaceDetection.getClient(detectorOptions)
        val liveInput = InputImage.fromBitmap(liveSelfieBitmap, 0)
        val liveFaces = detector.process(liveInput).await()

        if (liveFaces.isEmpty()) {
            throw SecurityException("No face detected in camera frame. Position your face clearly inside the biometric frame.")
        }
        if (liveFaces.size > 1) {
            throw SecurityException("Multiple faces detected in camera! Proxy assistance is strictly prohibited.")
        }

        val liveFace = liveFaces.first()
        if (!hasMandatoryLandmarks(liveFace)) {
            throw SecurityException("Facial landmarks unclear or covered. Remove obstructions and face the camera directly.")
        }
        if (abs(liveFace.headEulerAngleX) > MAX_HEAD_EULER_DEGREES ||
            abs(liveFace.headEulerAngleY) > MAX_HEAD_EULER_DEGREES
        ) {
            throw SecurityException("Look straight at the camera without tilting your head (${liveFace.headEulerAngleY.toInt()}°).")
        }

        val leftEye = liveFace.leftEyeOpenProbability
        val rightEye = liveFace.rightEyeOpenProbability
        if (leftEye == null || rightEye == null) {
            throw SecurityException("Liveness Check Failed: Eye visibility could not be confirmed.")
        }
        if (leftEye < MIN_LIVE_EYE_OPEN || rightEye < MIN_LIVE_EYE_OPEN) {
            throw SecurityException("Liveness Check Failed: Keep both eyes clearly open facing the camera.")
        }

        val liveUnified128D = com.waqar.institute.biometric.FaceBiometricVerificationEngine
            .extractUnified128DFaceIdentityEmbedding(liveSelfieBitmap, liveFace)
            .takeIf { com.waqar.institute.biometric.FaceBiometricVerificationEngine.isValidEmbeddingVector(it) }
            ?: extractNormalizedGeometryVector(liveFace)

        // Retrieve stored template or extract from Admin-enrolled admissionPhotoDataUri
        val cachedTemplate = loadTemplateFromVault(context, studentId)
        val storedTemplate = if (cachedTemplate != null && cachedTemplate.featureVector.size == liveUnified128D.size) {
            cachedTemplate
        } else if (admissionPhotoDataUri.isNotBlank()) {
            enrollFaceFromPhotoDataUri(context, studentId, admissionPhotoDataUri).getOrNull() ?: cachedTemplate
        } else {
            cachedTemplate
        }

        if (storedTemplate == null) {
            throw SecurityException(
                "No enrolled student admission photo found for ID $studentId. Contact Admin to upload your admission photo before taking this proctored exam."
            )
        }

        val liveVectorForComparison = if (storedTemplate.featureVector.size == 12 && liveUnified128D.size != 12) {
            extractNormalizedGeometryVector(liveFace)
        } else {
            liveUnified128D
        }

        val similarity = com.waqar.institute.biometric.FaceBiometricVerificationEngine.computeCosineSimilaritySafe(
            storedTemplate.featureVector,
            liveVectorForComparison
        )
        if (similarity < similarityThreshold) {
            throw SecurityException(
                "Biometric Face Mismatch (${(similarity * 100).toInt()}% < ${(similarityThreshold * 100).toInt()}%): Only the registered student can take this exam."
            )
        }
        similarity
    }

    fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-6f) (dot / denom).coerceIn(0f, 1f) else 0f
    }

    private fun extractNormalizedGeometryVector(face: Face): FloatArray {
        val box = face.boundingBox
        val w = box.width().toFloat().coerceAtLeast(1f)
        val h = box.height().toFloat().coerceAtLeast(1f)
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()

        fun relX(type: Int, neutralOffset: Float): Float {
            val pt = face.getLandmark(type)?.position ?: return 0f
            return (((pt.x - cx) / w) - neutralOffset).coerceIn(-0.5f, 0.5f)
        }

        fun relY(type: Int, neutralOffset: Float): Float {
            val pt = face.getLandmark(type)?.position ?: return 0f
            return (((pt.y - cy) / h) - neutralOffset).coerceIn(-0.5f, 0.5f)
        }

        // Zero-centered facial geometry deviations from canonical neutral face proportions
        val leftEyeX = relX(FaceLandmark.LEFT_EYE, -0.18f)
        val leftEyeY = relY(FaceLandmark.LEFT_EYE, -0.12f)
        val rightEyeX = relX(FaceLandmark.RIGHT_EYE, +0.18f)
        val rightEyeY = relY(FaceLandmark.RIGHT_EYE, -0.12f)
        val noseX = relX(FaceLandmark.NOSE_BASE, 0.0f)
        val noseY = relY(FaceLandmark.NOSE_BASE, +0.06f)
        val mouthLeftX = relX(FaceLandmark.MOUTH_LEFT, -0.14f)
        val mouthRightX = relX(FaceLandmark.MOUTH_RIGHT, +0.14f)
        val mouthBottomY = relY(FaceLandmark.MOUTH_BOTTOM, +0.28f)
        val leftCheekX = relX(FaceLandmark.LEFT_CHEEK, -0.26f)
        val rightCheekX = relX(FaceLandmark.RIGHT_CHEEK, +0.26f)
        val aspectRatio = ((w / h) - 0.85f).coerceIn(-0.4f, 0.4f)

        val raw = floatArrayOf(
            leftEyeX,
            leftEyeY,
            rightEyeX,
            rightEyeY,
            noseX,
            noseY,
            mouthLeftX,
            mouthRightX,
            mouthBottomY,
            leftCheekX,
            rightCheekX,
            aspectRatio
        )
        var normSq = 0f
        for (v in raw) normSq += v * v
        val denom = sqrt(normSq)
        return if (denom > 1e-5f) {
            FloatArray(raw.size) { i -> raw[i] / denom }
        } else {
            // Fallback for synthetic exact-neutral face so unit vector stays well-defined
            FloatArray(raw.size) { i -> if (i == 0) 1f else 0f }
        }
    }

    private fun saveTemplateToVault(context: Context, template: EnrolledFaceTemplate) {
        val serialized = template.featureVector.joinToString(",") { "%.4f".format(java.util.Locale.US, it) }
        val secPrefs = openSecurePrefs(context)
        if (secPrefs != null) {
            secPrefs.edit().putString("$SECURE_KEY_PREFIX${template.studentId}", serialized).apply()
        } else {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("face_v2_${template.studentId}", serialized).apply()
        }
    }

    fun loadTemplateFromVault(context: Context, studentId: String): EnrolledFaceTemplate? {
        val secPrefs = openSecurePrefs(context)
        var raw = secPrefs?.getString("$SECURE_KEY_PREFIX$studentId", null)
        if (raw == null) {
            val fallbackPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            raw = fallbackPrefs.getString("face_v2_$studentId", null)
        }
        raw ?: return null
        val floats = raw.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        if (floats.size != 128 && floats.size != 12) return null
        return EnrolledFaceTemplate(
            studentId = studentId,
            featureVector = floats,
            leftEyeOpenProb = 0.9f,
            rightEyeOpenProb = 0.9f,
            smilingProb = 0.5f,
            enrolledAtEpochMs = System.currentTimeMillis()
        )
    }

    fun decodeDataUriToBitmap(dataUri: String): Bitmap? = runCatching {
        val cleanBase64 = dataUri.substringAfter("base64,", dataUri)
        if (cleanBase64.isBlank()) return null
        val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
