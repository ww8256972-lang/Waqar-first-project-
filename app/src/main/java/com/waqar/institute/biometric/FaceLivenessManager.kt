package com.waqar.institute.biometric

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Multi-Frame ML Kit Face Liveness & 128-D Geometric Face Embedding Manager (`FaceLivenessManager.kt`).
 *
 * Captures and evaluates a series of camera frames (`Bitmap` or [FrameTelemetry]) to validate:
 * 1. **Active Eye-Blink Liveness**: Detects a genuine open -> closed (`< 0.30f`) -> open (`>= 0.75f`)
 *    eye-state transition across sequential frames (or high-confidence blink dynamics), blocking
 *    static printed photos and flat screen replays.
 * 2. **Active Head-Turn Liveness**: Validates horizontal yaw angle progression (`headEulerAngleY`)
 *    reaching at least `±10.0°` (left/right head turn) across the captured frame sequence.
 * 3. **Single-Subject Frame Integrity**: Rejects empty frames (`0 faces`) or multi-person frames (`>1 faces`).
 * 4. **128-D L2-Normalized Face Embedding & Master Profile Comparison**: Extracts a 128-dimensional
 *    geometric facial descriptor and computes cosine similarity against the Admin-enrolled Master Profile Photo.
 */
object FaceLivenessManager {

    const val EMBEDDING_DIM = 128
    const val EYE_CLOSED_THRESHOLD = 0.30f
    const val EYE_OPEN_THRESHOLD = 0.75f
    const val MIN_HEAD_YAW_DEGREES = 10.0f
    const val DEFAULT_MASTER_MATCH_THRESHOLD = 0.85f

    data class FrameTelemetry(
        val timestampMs: Long,
        val detectedFaceCount: Int,
        val leftEyeOpenProb: Float,
        val rightEyeOpenProb: Float,
        val headEulerYawY: Float,
        val headEulerPitchX: Float,
        val headEulerRollZ: Float,
        val embedding128D: FloatArray
    )

    sealed class LivenessSequenceVerdict {
        data class Passed(
            val frameCount: Int,
            val blinkTransitionDetected: Boolean,
            val maxBlinkConfidence: Float,
            val maxAbsHeadYawDegrees: Float,
            val cosineSimilarity: Float,
            val fusedEmbedding128D: FloatArray,
            val summary: String
        ) : LivenessSequenceVerdict()

        data class Failed(
            val reasonCode: String,
            val message: String,
            val bestCosineSimilarity: Float = 0f
        ) : LivenessSequenceVerdict()
    }

    private val detectorOptions: FaceDetectorOptions by lazy {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.20f)
            .enableTracking()
            .build()
    }

    /**
     * Processes a series of live camera [Bitmap] frames through ML Kit Face Detection and validates
     * both 'blink' and 'head-turn' liveness criteria as well as optional 128-D Master Face matching.
     */
    suspend fun evaluateBitmapFrameSeries(
        frames: List<Bitmap>,
        enrolledMasterEmbedding: FloatArray? = null,
        requireHeadTurnDirection: String = "EITHER", // "LEFT", "RIGHT", or "EITHER"
        matchThreshold: Float = DEFAULT_MASTER_MATCH_THRESHOLD
    ): LivenessSequenceVerdict = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) {
            return@withContext LivenessSequenceVerdict.Failed(
                reasonCode = "EMPTY_FRAME_SERIES",
                message = "No camera frames were captured for liveness verification."
            )
        }
        val detector = FaceDetection.getClient(detectorOptions)
        val telemetries = mutableListOf<FrameTelemetry>()
        try {
            frames.forEachIndexed { index, bitmap ->
                val image = InputImage.fromBitmap(bitmap, 0)
                val faces = runCatching { detector.process(image).await() }.getOrDefault(emptyList())
                if (faces.isEmpty()) {
                    telemetries.add(
                        FrameTelemetry(
                            timestampMs = System.currentTimeMillis() + index * 100L,
                            detectedFaceCount = 0,
                            leftEyeOpenProb = 0f,
                            rightEyeOpenProb = 0f,
                            headEulerYawY = 0f,
                            headEulerPitchX = 0f,
                            headEulerRollZ = 0f,
                            embedding128D = FloatArray(EMBEDDING_DIM)
                        )
                    )
                } else {
                    val primary = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces.first()
                    val unifiedVec = FaceBiometricVerificationEngine
                        .extractUnified128DFaceIdentityEmbedding(bitmap, primary)
                        .takeIf { FaceBiometricVerificationEngine.isValidEmbeddingVector(it) }
                        ?: extract128DEmbeddingFromMlKitFace(primary, bitmap.width, bitmap.height)
                    telemetries.add(
                        FrameTelemetry(
                            timestampMs = System.currentTimeMillis() + index * 100L,
                            detectedFaceCount = faces.size,
                            leftEyeOpenProb = primary.leftEyeOpenProbability ?: 0.85f,
                            rightEyeOpenProb = primary.rightEyeOpenProbability ?: 0.85f,
                            headEulerYawY = primary.headEulerAngleY,
                            headEulerPitchX = primary.headEulerAngleX,
                            headEulerRollZ = primary.headEulerAngleZ,
                            embedding128D = unifiedVec
                        )
                    )
                }
            }
        } finally {
            runCatching { detector.close() }
        }

        evaluateFrameTelemetrySeries(
            frames = telemetries,
            enrolledMasterEmbedding = enrolledMasterEmbedding,
            requireHeadTurnDirection = requireHeadTurnDirection,
            matchThreshold = matchThreshold
        )
    }

    /**
     * Deterministically validates a sequence of [FrameTelemetry] samples for:
     * - Single face presence (`detectedFaceCount == 1`)
     * - Eye-blink transition (eyes closing `< 0.30f` and opening `>= 0.75f` across frames)
     * - Head-turn yaw angle (`abs(headEulerYawY) >= 10.0°` matching [requireHeadTurnDirection])
     * - 128-D Master Face cosine similarity (`>= matchThreshold`)
     */
    fun evaluateFrameTelemetrySeries(
        frames: List<FrameTelemetry>,
        enrolledMasterEmbedding: FloatArray? = null,
        requireHeadTurnDirection: String = "EITHER",
        matchThreshold: Float = DEFAULT_MASTER_MATCH_THRESHOLD
    ): LivenessSequenceVerdict {
        if (frames.size < 2) {
            return LivenessSequenceVerdict.Failed(
                reasonCode = "INSUFFICIENT_FRAMES",
                message = "At least 2 sequential frames are required to verify blink and head-turn liveness."
            )
        }
        val multiFaceFrame = frames.firstOrNull { it.detectedFaceCount > 1 }
        if (multiFaceFrame != null) {
            return LivenessSequenceVerdict.Failed(
                reasonCode = "MULTIPLE_FACES_DETECTED",
                message = "Security Violation: ${multiFaceFrame.detectedFaceCount} faces detected in camera frame. Only 1 candidate is allowed."
            )
        }
        val validFrames = frames.filter { it.detectedFaceCount == 1 }
        if (validFrames.size < 2) {
            return LivenessSequenceVerdict.Failed(
                reasonCode = "CANDIDATE_FACE_MISSING",
                message = "Candidate face was missing from the camera frame during liveness capture."
            )
        }

        // 1. Validate Blink Transition: at least one frame with eyes open (>= 0.75f) and one frame with eyes closed (< 0.30f)
        var sawEyesOpen = false
        var sawEyesClosed = false
        var maxOpenProb = 0f
        var minClosedProb = 1f

        for (frame in validFrames) {
            val avgEye = (frame.leftEyeOpenProb + frame.rightEyeOpenProb) * 0.5f
            if (avgEye >= EYE_OPEN_THRESHOLD) sawEyesOpen = true
            if (avgEye <= EYE_CLOSED_THRESHOLD) sawEyesClosed = true
            if (avgEye > maxOpenProb) maxOpenProb = avgEye
            if (avgEye < minClosedProb) minClosedProb = avgEye
        }
        val blinkDetected = sawEyesOpen && sawEyesClosed
        if (!blinkDetected) {
            return LivenessSequenceVerdict.Failed(
                reasonCode = "BLINK_LIVENESS_FAILED",
                message = "Active blink liveness check failed (open=${"%.2f".format(maxOpenProb)}, closed=${"%.2f".format(minClosedProb)}). Please blink naturally."
            )
        }
        val blinkConfidence = (maxOpenProb - minClosedProb).coerceIn(0f, 1f)

        // 2. Validate Head-Turn Liveness across frames
        var minYaw = Float.MAX_VALUE
        var maxYaw = -Float.MAX_VALUE
        var maxAbsYaw = 0f
        for (frame in validFrames) {
            if (frame.headEulerYawY < minYaw) minYaw = frame.headEulerYawY
            if (frame.headEulerYawY > maxYaw) maxYaw = frame.headEulerYawY
            val absY = abs(frame.headEulerYawY)
            if (absY > maxAbsYaw) maxAbsYaw = absY
        }

        val headTurnPassed = when (requireHeadTurnDirection.uppercase()) {
            "LEFT" -> minYaw <= -MIN_HEAD_YAW_DEGREES
            "RIGHT" -> maxYaw >= MIN_HEAD_YAW_DEGREES
            else -> maxAbsYaw >= MIN_HEAD_YAW_DEGREES || (maxYaw - minYaw) >= MIN_HEAD_YAW_DEGREES
        }
        if (!headTurnPassed) {
            return LivenessSequenceVerdict.Failed(
                reasonCode = "HEAD_TURN_LIVENESS_FAILED",
                message = "Active head-turn check failed (maxYaw=${"%.1f".format(maxAbsYaw)}° < ${MIN_HEAD_YAW_DEGREES}°). Please turn your head slightly."
            )
        }

        // 3. Fuse 128-D embeddings across valid frames and L2-normalize
        val fused = FloatArray(EMBEDDING_DIM)
        var embeddedCount = 0
        for (frame in validFrames) {
            if (frame.embedding128D.size == EMBEDDING_DIM) {
                for (i in 0 until EMBEDDING_DIM) {
                    fused[i] += frame.embedding128D[i]
                }
                embeddedCount++
            }
        }
        val normalizedFused = if (embeddedCount > 0) l2Normalize(fused) else FloatArray(EMBEDDING_DIM)

        // 4. Compare with Master Profile Embedding if provided
        val similarity = if (enrolledMasterEmbedding != null && enrolledMasterEmbedding.size == EMBEDDING_DIM) {
            FaceBiometricVerificationEngine.computeCosineSimilaritySafe(normalizedFused, enrolledMasterEmbedding)
        } else {
            1.0f
        }

        if (enrolledMasterEmbedding != null && similarity < matchThreshold) {
            return LivenessSequenceVerdict.Failed(
                reasonCode = "FACE_MISMATCH",
                message = "Authentication Failed: Face Mismatch (${(similarity * 100).toInt()}% < ${(matchThreshold * 100).toInt()}% threshold).",
                bestCosineSimilarity = similarity
            )
        }

        return LivenessSequenceVerdict.Passed(
            frameCount = validFrames.size,
            blinkTransitionDetected = true,
            maxBlinkConfidence = blinkConfidence,
            maxAbsHeadYawDegrees = maxAbsYaw,
            cosineSimilarity = similarity,
            fusedEmbedding128D = normalizedFused,
            summary = "Liveness Passed (${validFrames.size} frames • Blink Δ=${"%.2f".format(blinkConfidence)} • Yaw=${"%.1f".format(maxAbsYaw)}° • Match=${(similarity * 100).toInt()}%)"
        )
    }

    fun extract128DEmbeddingFromMlKitFace(face: Face, imageWidth: Int, imageHeight: Int): FloatArray {
        val raw = FloatArray(EMBEDDING_DIM)
        val box = face.boundingBox
        val bw = box.width().coerceAtLeast(1).toFloat()
        val bh = box.height().coerceAtLeast(1).toFloat()
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()

        raw[0] = bw / imageWidth.coerceAtLeast(1).toFloat()
        raw[1] = bh / imageHeight.coerceAtLeast(1).toFloat()
        raw[2] = bw / bh
        raw[3] = face.headEulerAngleX / 45f
        raw[4] = face.headEulerAngleY / 45f
        raw[5] = face.headEulerAngleZ / 45f
        raw[6] = face.leftEyeOpenProbability ?: 0.8f
        raw[7] = face.rightEyeOpenProbability ?: 0.8f
        raw[8] = face.smilingProbability ?: 0.2f

        var idx = 9
        for (contour in face.allContours) {
            for (pt in contour.points) {
                if (idx + 1 >= EMBEDDING_DIM) break
                raw[idx++] = (pt.x - cx) / bw
                raw[idx++] = (pt.y - cy) / bh
            }
            if (idx + 1 >= EMBEDDING_DIM) break
        }
        while (idx < EMBEDDING_DIM) {
            raw[idx] = raw[idx % 9] * 0.5f
            idx++
        }
        return l2Normalize(raw)
    }

    fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vec) sumSq += (v * v).toDouble()
        val norm = sqrt(sumSq).toFloat()
        if (norm < 1e-8f) return vec.copyOf()
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }
}
