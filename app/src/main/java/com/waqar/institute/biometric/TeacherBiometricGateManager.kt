package com.waqar.institute.biometric

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.media.BindScreenCapturePolicy
import com.example.media.ScreenCapturePolicy
import com.example.security.HardwareKeystoreVault
import com.example.ui.designsystem.WciColors
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lsparanoid.Obfuscate
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 24-Hour Rolling Biometric Session Gate & Mandatory Teacher Portal Interceptor
 * with High-Performance Animated Face Scanner.
 */
@Obfuscate
@Keep
object TeacherBiometricGateManager {
    private const val PREFS_NAME = "wci_teacher_24h_biometric_gate_prefs"
    private const val KEY_LAST_VERIFIED_PREFIX = "lastBiometricVerifiedTime_"
    private const val KEY_LAST_COSINE_PREFIX = "lastBiometricCosine_"
    const val ROLLING_24_HOURS_MS = 24 * 60 * 60 * 1000L // 86,400,000 ms

    // Per-login session verification map: cleared on every login/sign-out so live blink is mandatory on every login
    private val activeLoginSessionVerified = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun readVerifiedTimestampMs(context: Context, cleanId: String): Long {
        val key = "$KEY_LAST_VERIFIED_PREFIX$cleanId"
        val secPrefs = runCatching {
            HardwareKeystoreVault.openEncryptedPreferences(context.applicationContext ?: context)
        }.getOrNull()
        val secTs = secPrefs?.getLong(key, 0L) ?: 0L
        if (secTs > 0L) return secTs

        // Legacy read-once migration so active 24h verifications are preserved non-destructively
        val legacyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacyTs = legacyPrefs.getLong(key, 0L)
        if (legacyTs > 0L && secPrefs != null) {
            secPrefs.edit().putLong(key, legacyTs).apply()
            legacyPrefs.edit().remove(key).remove("$KEY_LAST_COSINE_PREFIX$cleanId").apply()
        }
        return legacyTs
    }

    private fun normalizeTeacherGateId(teacherUid: String): String? {
        val cleanId = teacherUid.trim().lowercase()
        return if (cleanId.isBlank() || cleanId in setOf("teacher", "default_teacher", "cred_teacher")) {
            null
        } else {
            cleanId
        }
    }

    fun isTeacherVerifiedForCurrentLogin(teacherUid: String): Boolean {
        val cleanId = normalizeTeacherGateId(teacherUid) ?: return false
        return activeLoginSessionVerified[cleanId] == true
    }

    fun recordSuccessfulLoginVerification(context: Context, teacherUid: String, cosineScore: Float) {
        val cleanId = normalizeTeacherGateId(teacherUid) ?: return
        activeLoginSessionVerified[cleanId] = true
        recordSuccessful24HourVerification(context, teacherUid, cosineScore)
    }

    fun clearLoginVerificationOnAuthChange(teacherUid: String? = null) {
        if (teacherUid.isNullOrBlank()) {
            activeLoginSessionVerified.clear()
        } else {
            normalizeTeacherGateId(teacherUid)?.let { activeLoginSessionVerified.remove(it) }
        }
    }

    fun isTeacherVerifiedWithin24Hours(context: Context, teacherUid: String): Boolean {
        val cleanId = normalizeTeacherGateId(teacherUid) ?: return false
        val lastVerifiedMs = readVerifiedTimestampMs(context, cleanId)
        if (lastVerifiedMs <= 0L) return false
        val elapsed = System.currentTimeMillis() - lastVerifiedMs
        return elapsed in 0 until ROLLING_24_HOURS_MS
    }

    fun getRemainingGateValidityMinutes(context: Context, teacherUid: String): Long {
        val cleanId = normalizeTeacherGateId(teacherUid) ?: return 0L
        val lastVerifiedMs = readVerifiedTimestampMs(context, cleanId)
        if (lastVerifiedMs <= 0L) return 0L
        val remainingMs = (ROLLING_24_HOURS_MS - (System.currentTimeMillis() - lastVerifiedMs)).coerceAtLeast(0L)
        return remainingMs / (60 * 1000L)
    }

    fun recordSuccessful24HourVerification(context: Context, teacherUid: String, cosineScore: Float) {
        val cleanId = normalizeTeacherGateId(teacherUid) ?: return
        val receipt = runCatching {
            HardwareBiometricAttestor.issueKeystoreAttestedPassReceipt(
                context = context,
                subjectId = cleanId,
                portalRole = "TEACHER",
                challenge = HardwareBiometricAttestor.LivenessChallenge.BLINK_BOTH_EYES,
                similarity = cosineScore
            )
        }.getOrNull()
        val targetPrefs = runCatching {
            HardwareKeystoreVault.openEncryptedPreferences(context.applicationContext ?: context)
        }.getOrElse {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        targetPrefs.edit()
            .putLong("$KEY_LAST_VERIFIED_PREFIX$cleanId", System.currentTimeMillis())
            .putFloat("$KEY_LAST_COSINE_PREFIX$cleanId", cosineScore)
            .apply {
                if (receipt != null) {
                    putString("lastBiometricAttestDigest_$cleanId", receipt.receiptDigestHex)
                }
            }
            .apply()
    }
}

enum class BiometricScanState {
    Scanning, Matched, NotRecognized
}

// Landmark types for animated mesh (mapped to circular preview)
private val landmarkTypes = listOf(
    FaceLandmark.LEFT_EYE,     // 0
    FaceLandmark.RIGHT_EYE,    // 1
    FaceLandmark.NOSE_BASE,    // 2
    FaceLandmark.MOUTH_LEFT,   // 3
    FaceLandmark.MOUTH_RIGHT,  // 4
    FaceLandmark.LEFT_CHEEK,   // 5
    FaceLandmark.RIGHT_CHEEK,  // 6
    FaceLandmark.MOUTH_BOTTOM  // 7
)

private val meshLines = listOf(
    0 to 1, 0 to 2, 1 to 2, 3 to 2, 4 to 2, 3 to 4, 5 to 0, 6 to 1, 3 to 7, 4 to 7
)

private val Purple get() = WciColors.ScannerPurple
private val BlueBg get() = WciColors.ScannerBlue
private val Teal get() = WciColors.ScannerTeal
private val PinkGlow get() = WciColors.ScannerPink
private val OrangeGlow get() = WciColors.ScannerOrange
private val VioletGlow get() = WciColors.ScannerViolet
private val Indigo get() = WciColors.ScannerIndigo
private val Yellow get() = WciColors.ScannerYellow
private val Green get() = WciColors.ScannerGreen
private val Red get() = WciColors.ScannerRed

@Composable
fun TeacherBiometricGateOverlay(
    teacherUid: String,
    teacherName: String,
    onSignOutRequested: () -> Unit,
    portalContent: @Composable () -> Unit
) {
    val context = LocalContext.current
    if (FaceBiometricVerificationEngine.isInvalidTeacherUid(teacherUid)) {
        GradientBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Lock, contentDescription = "Blocked", tint = WciColors.BiometricCoralAlert, modifier = Modifier.size(56.dp))
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Biometric Gate Blocked: Missing linked Teacher ID",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ask Admin to link this faculty credential to a valid Teacher record and upload your profile photo.",
                    color = WciColors.BiometricSubtext,
                    fontSize = 13.5.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedButton(
                    onClick = onSignOutRequested,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WciColors.BiometricCoralAlert),
                    border = BorderStroke(1.dp, WciColors.BiometricCoralAlert.copy(alpha = 0.5f))
                ) {
                    Text("Sign Out", color = WciColors.BiometricCoralAlert, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var isVerified by remember(teacherUid) {
        mutableStateOf(TeacherBiometricGateManager.isTeacherVerifiedForCurrentLogin(teacherUid))
    }

    if (isVerified) {
        portalContent()
        return
    }

    // Lock screenshots and screen capture while biometric authentication is active
    BindScreenCapturePolicy(ScreenCapturePolicy.LOCKED_SENSITIVE)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var isGpsGeofenceVerified by remember { mutableStateOf(false) }
    var isCheckingGpsLocation by remember { mutableStateOf(false) }
    var gpsDistanceMeters by remember { mutableStateOf<Float?>(null) }
    var geofenceAlertDialogMessage by remember { mutableStateOf<String?>(null) }
    var pendingFallbackCameraAfterGps by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var isFirstTimeEnrollment by remember(teacherUid, teacherName) { mutableStateOf(false) }
    var scanState by remember { mutableStateOf(BiometricScanState.Scanning) }
    var landmarks by remember { mutableStateOf<List<Offset?>>(emptyList()) }
    var statusDetail by remember {
        mutableStateOf(
            "Click 'Verify GPS & Start Biometric Attendance' to check 50m Institute Geofence (${TeacherGpsGeofenceVerifier.INSTITUTE_PLUS_CODE_LABEL}) before Face Scan."
        )
    }
    var isVerifying by remember { mutableStateOf(false) }

    LaunchedEffect(teacherUid, teacherName) {
        val lookup = withContext(Dispatchers.IO) {
            FaceBiometricVerificationEngine.resolveEnrolledEmbeddingResult(context, teacherUid, teacherName)
        }
        val missing = lookup is FaceBiometricVerificationEngine.EnrollmentLookupResult.Missing
        isFirstTimeEnrollment = missing
    }

    // Live photo capture fallback using real camera frame + 128-D Cosine verification
    val fallbackCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            isVerifying = true
            statusDetail = "Analyzing live camera photo with 128-D Cosine Matcher..."
            scope.launch {
                val res = FaceBiometricVerificationEngine.verifyTeacherLiveFace(
                    teacherUid = teacherUid,
                    teacherName = teacherName,
                    liveFrameBitmap = bitmap,
                    context = context
                )
                withContext(Dispatchers.Main) {
                    when (res) {
                        is FaceBiometricVerificationEngine.BiometricMatchResult.Verified -> {
                            val syncRes = withContext(Dispatchers.IO) {
                                TeacherBiometricGateManager.recordSuccessfulLoginVerification(
                                    context = context,
                                    teacherUid = teacherUid,
                                    cosineScore = res.cosineSimilarity
                                )
                                TeacherAttendanceSyncManager.markTeacherPresentOnBiometricPass(
                                    context = context,
                                    teacherUid = teacherUid,
                                    teacherName = teacherName,
                                    cosineSimilarity = res.cosineSimilarity,
                                    livenessConfidence = res.livenessConfidence
                                )
                            }
                            scanState = BiometricScanState.Matched
                            statusDetail = when (syncRes) {
                                is TeacherAttendanceSyncManager.TeacherAttendanceSyncResult.Synced ->
                                    "${res.message} · Attendance Synced ✓"
                                is TeacherAttendanceSyncManager.TeacherAttendanceSyncResult.QueuedOffline ->
                                    "${res.message} · Attendance Queued Offline (${syncRes.reason})"
                                is TeacherAttendanceSyncManager.TeacherAttendanceSyncResult.Failed ->
                                    "${res.message} · Attendance Sync Warning: ${syncRes.reason}"
                            }
                            delay(800)
                            isVerified = true
                        }
                        is FaceBiometricVerificationEngine.BiometricMatchResult.Mismatch -> {
                            scanState = BiometricScanState.NotRecognized
                            statusDetail = res.message
                            delay(1500)
                            scanState = BiometricScanState.Scanning
                        }
                        is FaceBiometricVerificationEngine.BiometricMatchResult.SpoofOrLivenessFailed -> {
                            scanState = BiometricScanState.NotRecognized
                            statusDetail = res.reason
                            delay(1500)
                            scanState = BiometricScanState.Scanning
                        }
                        is FaceBiometricVerificationEngine.BiometricMatchResult.Error -> {
                            scanState = BiometricScanState.NotRecognized
                            statusDetail = res.errorMessage
                            delay(1500)
                            scanState = BiometricScanState.Scanning
                        }
                    }
                    isVerifying = false
                }
            }
        }
    }

    // Shake animation on failure
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
                .setMinFaceSize(0.25f)
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
        ImageAnalysis.Analyzer { imageProxy ->
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
                    statusDetail = "Multiple faces ($count) detected. Ensure only you appear in the scanner."
                },
                onFaceDetected = { face, pts, _, _ ->
                    landmarks = pts
                    val left = face.leftEyeOpenProbability
                    val right = face.rightEyeOpenProbability
                    if (left == null || right == null) {
                        sawEyeOpen = false
                        sawEyeClosed = false
                        return@processLiveFrame
                    }
                    val open = left > 0.65f && right > 0.65f
                    val closed = left < 0.35f && right < 0.35f

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
                    if (scanState == BiometricScanState.Scanning && !isVerifying && livenessPassed && now - lastVerifyMs > 600) {
                        lastVerifyMs = now
                        isVerifying = true
                        livenessPassed = false

                        val livenessScore = ((left + right) * 0.5f).coerceIn(0f, 1f)
                        scope.launch {
                            val matchResult = FaceBiometricVerificationEngine.enrollOrVerifyTeacherLiveBlinkWithFaceObject(
                                teacherUid = teacherUid,
                                teacherName = teacherName,
                                face = face,
                                livenessScore = livenessScore,
                                blinkConfirmed = true,
                                context = context
                            )

                            withContext(Dispatchers.Main) {
                                when (matchResult) {
                                    is FaceBiometricVerificationEngine.BiometricMatchResult.Verified -> {
                                        if (matchResult.newlyEnrolled) {
                                            isFirstTimeEnrollment = false
                                        }
                                        val syncRes = withContext(Dispatchers.IO) {
                                            TeacherBiometricGateManager.recordSuccessfulLoginVerification(
                                                context = context,
                                                teacherUid = teacherUid,
                                                cosineScore = matchResult.cosineSimilarity
                                            )
                                            TeacherAttendanceSyncManager.markTeacherPresentOnBiometricPass(
                                                context = context,
                                                teacherUid = teacherUid,
                                                teacherName = teacherName,
                                                cosineSimilarity = matchResult.cosineSimilarity,
                                                livenessConfidence = matchResult.livenessConfidence
                                            )
                                        }
                                        scanState = BiometricScanState.Matched
                                        statusDetail = when (syncRes) {
                                            is TeacherAttendanceSyncManager.TeacherAttendanceSyncResult.Synced ->
                                                "${matchResult.message} · Attendance Synced ✓"
                                            is TeacherAttendanceSyncManager.TeacherAttendanceSyncResult.QueuedOffline ->
                                                "${matchResult.message} · Attendance Queued Offline (${syncRes.reason})"
                                            is TeacherAttendanceSyncManager.TeacherAttendanceSyncResult.Failed ->
                                                "${matchResult.message} · Attendance Sync Warning: ${syncRes.reason}"
                                        }
                                        delay(800)
                                        isVerified = true
                                    }
                                    is FaceBiometricVerificationEngine.BiometricMatchResult.Mismatch -> {
                                        scanState = BiometricScanState.NotRecognized
                                        statusDetail = matchResult.message
                                        delay(1500)
                                        scanState = BiometricScanState.Scanning
                                    }
                                    is FaceBiometricVerificationEngine.BiometricMatchResult.SpoofOrLivenessFailed -> {
                                        scanState = BiometricScanState.NotRecognized
                                        statusDetail = matchResult.reason
                                        delay(1500)
                                        scanState = BiometricScanState.Scanning
                                    }
                                    is FaceBiometricVerificationEngine.BiometricMatchResult.Error -> {
                                        scanState = BiometricScanState.NotRecognized
                                        statusDetail = matchResult.errorMessage
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

    var enrolledBitmap by remember(teacherUid, teacherName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(teacherUid, teacherName) {
        enrolledBitmap = withContext(Dispatchers.IO) {
            val uri = FaceBiometricVerificationEngine.getEnrolledTeacherPhotoUri(context, teacherUid, teacherName)
            if (uri.isBlank()) null
            else withContext(Dispatchers.Default) {
                FaceBiometricVerificationEngine.decodeDataUriToBitmap(uri)
            }
        }
    }

    GradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isFirstTimeEnrollment) {
                    "First-Time 128-D Live Blink Face Enrollment"
                } else {
                    "Teacher Biometric Live Blink Verification"
                },
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Faculty: $teacherName (ID: $teacherUid)",
                color = WciColors.BiometricSubtext,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            val localEnrolledHeaderBitmap = enrolledBitmap
            if (localEnrolledHeaderBitmap != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Green.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = localEnrolledHeaderBitmap.asImageBitmap(),
                            contentDescription = "Enrolled Teacher Profile Photo",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enrolled Gallery Profile Photo Active ✓",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "128-D Facial Biometric Signature linked · Live Blink Required",
                                color = WciColors.BiometricMintBadgeText,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            } else if (isFirstTimeEnrollment) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Yellow.copy(alpha = 0.8f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = "First-Time 128-D Live Blink Enrollment Mode",
                            color = Yellow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Blink once at the front camera to register your 128-D facial signature for future logins.",
                            color = Color.White,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            val verifiedDist = gpsDistanceMeters
            if (verifiedDist != null && isGpsGeofenceVerified) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Green.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, Green.copy(alpha = 0.85f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "GPS Geofence Verified (${"%.1f".format(verifiedDist)}m ≤ 50m · ${TeacherGpsGeofenceVerifier.INSTITUTE_PLUS_CODE_LABEL}) ✓",
                        color = WciColors.BiometricMintBadgeText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            val runPreBiometricGpsCheck: (Boolean) -> Unit = { launchFallbackPhotoOnPass ->
                isCheckingGpsLocation = true
                statusDetail = "Checking real-time High-Accuracy GPS distance from Institute (${TeacherGpsGeofenceVerifier.INSTITUTE_LATITUDE}, ${TeacherGpsGeofenceVerifier.INSTITUTE_LONGITUDE})…"
                scope.launch {
                    val gpsResult = TeacherGpsGeofenceVerifier.verifyTeacherLocationBeforeBiometric(context)
                    isCheckingGpsLocation = false
                    when (gpsResult) {
                        is TeacherGpsGeofenceVerifier.GeofenceVerificationResult.InsidePremises -> {
                            isGpsGeofenceVerified = true
                            gpsDistanceMeters = gpsResult.distanceMeters
                            statusDetail = if (isFirstTimeEnrollment) {
                                "GPS Inside Premises (${"%.1f".format(gpsResult.distanceMeters)}m ≤ 50m) ✓ Blink once at the camera to enroll & mark attendance."
                            } else {
                                "GPS Inside Premises (${"%.1f".format(gpsResult.distanceMeters)}m ≤ 50m) ✓ Blink once at the camera to verify & mark attendance."
                            }
                            if (!hasCameraPermission) {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            } else if (launchFallbackPhotoOnPass) {
                                fallbackCameraLauncher.launch(null)
                            }
                        }
                        is TeacherGpsGeofenceVerifier.GeofenceVerificationResult.OutsidePremises -> {
                            isGpsGeofenceVerified = false
                            gpsDistanceMeters = gpsResult.distanceMeters
                            geofenceAlertDialogMessage = TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE
                            statusDetail = "${TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE} (Distance: ${"%.1f".format(gpsResult.distanceMeters)}m > 50m)"
                        }
                        is TeacherGpsGeofenceVerifier.GeofenceVerificationResult.MockLocationRejected -> {
                            isGpsGeofenceVerified = false
                            gpsDistanceMeters = null
                            geofenceAlertDialogMessage = TeacherGpsGeofenceVerifier.MOCK_LOCATION_ALERT_MESSAGE
                            statusDetail = TeacherGpsGeofenceVerifier.MOCK_LOCATION_ALERT_MESSAGE
                        }
                        is TeacherGpsGeofenceVerifier.GeofenceVerificationResult.LocationUnavailable -> {
                            isGpsGeofenceVerified = false
                            gpsDistanceMeters = null
                            geofenceAlertDialogMessage = TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE
                            statusDetail = "${TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE} (${gpsResult.reason})"
                        }
                    }
                }
            }

            val locationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (granted) {
                    val shouldLaunchFallback = pendingFallbackCameraAfterGps
                    pendingFallbackCameraAfterGps = false
                    runPreBiometricGpsCheck(shouldLaunchFallback)
                } else {
                    pendingFallbackCameraAfterGps = false
                    isGpsGeofenceVerified = false
                    geofenceAlertDialogMessage = TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE
                    statusDetail = "Location permission denied. ${TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE}"
                }
            }

            val triggerAttendanceWithGpsGate: (Boolean) -> Unit = { launchFallbackPhoto ->
                if (TeacherGpsGeofenceVerifier.hasFineOrCoarseLocationPermission(context)) {
                    runPreBiometricGpsCheck(launchFallbackPhoto)
                } else {
                    pendingFallbackCameraAfterGps = launchFallbackPhoto
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

            if (geofenceAlertDialogMessage != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { geofenceAlertDialogMessage = null },
                    title = {
                        Text(
                            text = "GPS Geofence Attendance Lock",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(text = geofenceAlertDialogMessage ?: TeacherGpsGeofenceVerifier.OUT_OF_RANGE_ALERT_MESSAGE)
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { geofenceAlertDialogMessage = null }
                        ) {
                            Text("OK", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            if (hasCameraPermission && isGpsGeofenceVerified) {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                    contentAlignment = Alignment.Center
                ) {
                    CameraCircle(
                        analyzer = analyzer,
                        executor = executor,
                        modifier = Modifier.size(192.dp)
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
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.5f)),
                    modifier = Modifier.size(192.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val localEnrolledCircleBitmap = enrolledBitmap
                        if (localEnrolledCircleBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = localEnrolledCircleBitmap.asImageBitmap(),
                                contentDescription = "Enrolled Teacher Profile Photo",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = when {
                                    isCheckingGpsLocation -> "Checking 50m GPS Geofence…"
                                    !isGpsGeofenceVerified -> "50m GPS Geofence Check Required"
                                    else -> "Camera Permission Required"
                                },
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Crossfade(targetState = scanState, label = "scan_status") { st ->
                Text(
                    text = when (st) {
                        BiometricScanState.Scanning -> when {
                            isCheckingGpsLocation -> "Verifying 50m Institute GPS Geofence…"
                            !isGpsGeofenceVerified -> "Step 1: Verify Institute GPS Location (≤ 50m)"
                            isVerifying -> "Verifying 128-D Cosine Embedding…"
                            else -> "Scanning face…"
                        }
                        BiometricScanState.Matched -> "Face matched · Attendance marked"
                        BiometricScanState.NotRecognized -> "Face not recognized. Try again."
                    },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusDetail,
                color = WciColors.BiometricSubtext,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))

            if (!isGpsGeofenceVerified) {
                OutlinedButton(
                    onClick = { triggerAttendanceWithGpsGate(false) },
                    enabled = !isCheckingGpsLocation && !isVerifying,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.5.dp, Green.copy(alpha = 0.85f))
                ) {
                    Text(
                        text = if (isCheckingGpsLocation) {
                            "Checking GPS Location (50m Radius)…"
                        } else {
                            "Verify GPS & Start Biometric Attendance"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            } else if (!hasCameraPermission) {
                OutlinedButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
                ) {
                    Text("Grant Live Camera Permission", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Fallback live camera verification only available for Admin-enrolled teachers (first-time enrollment requires live blink)
            if (!isFirstTimeEnrollment) {
                OutlinedButton(
                    onClick = {
                        if (isGpsGeofenceVerified) {
                            fallbackCameraLauncher.launch(null)
                        } else {
                            triggerAttendanceWithGpsGate(true)
                        }
                    },
                    enabled = !isVerifying && !isCheckingGpsLocation,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Take Live Photo with Camera", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedButton(
                onClick = onSignOutRequested,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WciColors.BiometricCoralAlert),
                border = BorderStroke(1.dp, WciColors.BiometricCoralAlert.copy(alpha = 0.4f))
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = WciColors.BiometricCoralAlert)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel & Sign Out", color = WciColors.BiometricCoralAlert)
            }
        }
    }
}

/**
 * Animated Canvas Overlay on top of the live camera circle.
 */
@Composable
internal fun ScannerOverlay(
    state: BiometricScanState,
    landmarks: List<Offset?>,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "scanner_transition")

    val ring1 by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "ring1_clockwise"
    )
    val ring2 by transition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "ring2_counter_clockwise"
    )
    val pulseA by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearOutSlowInEasing)),
        label = "pulseA"
    )
    val pulseB by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            initialStartOffset = StartOffset(1000)
        ),
        label = "pulseB"
    )
    val scan by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_line"
    )
    val blink by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "mesh_blink"
    )

    val okProgress by animateFloatAsState(
        targetValue = if (state == BiometricScanState.Matched) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ok_anim"
    )
    val redTint by animateFloatAsState(
        targetValue = if (state == BiometricScanState.NotRecognized) 0.35f else 0f,
        label = "red_tint_anim"
    )

    Canvas(modifier = modifier) {
        val c = center
        val r = 96.dp.toPx() // camera circle radius (192dp diameter / 2)
        val circleClip = Path().apply { addOval(ComposeRect(center = c, radius = r)) }

        // 1. Dual expanding & fading pulse rings
        listOf(pulseA, pulseB).forEach { p ->
            drawCircle(
                color = Color.White.copy(alpha = 0.6f * (1f - p)),
                radius = 112.dp.toPx() * (1f + 0.22f * p),
                center = c,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // 2. Outer white dashed ring (clockwise, 6s)
        rotate(degrees = ring1, pivot = c) {
            drawCircle(
                color = Color.White,
                radius = 118.dp.toPx(),
                center = c,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(60.dp.toPx(), 40.dp.toPx(), 20.dp.toPx(), 40.dp.toPx())
                    )
                )
            )
        }

        // 3. Inner yellow dotted ring (counter-clockwise, 9s)
        rotate(degrees = ring2, pivot = c) {
            drawCircle(
                color = Yellow,
                radius = 108.dp.toPx(),
                center = c,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx()))
                )
            )
        }

        // 4. Everything inside the circular camera preview
        clipPath(circleClip) {
            // Faint grid lines
            for (i in -2..2) {
                val y = c.y + i * 30.dp.toPx()
                drawLine(Indigo.copy(alpha = 0.25f), Offset(c.x - r, y), Offset(c.x + r, y), 0.5.dp.toPx())
            }
            for (i in -1..1) {
                val x = c.x + i * 50.dp.toPx()
                drawLine(Indigo.copy(alpha = 0.25f), Offset(x, c.y - r), Offset(x, c.y + r), 0.5.dp.toPx())
            }

            // Face landmark mesh: real landmarks connected by thin lines + blinking dots
            val pts = landmarks.map { n ->
                n?.let { Offset(c.x - r + it.x * 2 * r, c.y - r + it.y * 2 * r) }
            }
            if (pts.isNotEmpty()) {
                meshLines.forEach { (a, b) ->
                    val pa = pts.getOrNull(a)
                    val pb = pts.getOrNull(b)
                    if (pa != null && pb != null) {
                        drawLine(Color.White.copy(alpha = 0.8f), pa, pb, 1.dp.toPx())
                    }
                }
                pts.forEachIndexed { i, p ->
                    if (p != null) {
                        val a = if (i % 3 == 0) blink else (1.3f - blink).coerceIn(0.3f, 1f)
                        drawCircle(Color.White.copy(alpha = a), 3.5.dp.toPx(), p)
                        drawCircle(BlueBg.copy(alpha = a), 2.dp.toPx(), p)
                    }
                }
            }

            // Moving scan line with pink glow trail
            if (okProgress < 0.99f) {
                val y = c.y + scan * 100.dp.toPx()
                val glow = 22.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, PinkGlow.copy(alpha = 0.35f * (1f - okProgress))),
                        startY = y - glow, endY = y
                    ),
                    topLeft = Offset(c.x - r, y - glow),
                    size = Size(2 * r, glow)
                )
                drawRect(
                    color = PinkGlow.copy(alpha = 1f - okProgress),
                    topLeft = Offset(c.x - r, y - 2.dp.toPx()),
                    size = Size(2 * r, 4.dp.toPx())
                )
            }
        }

        // 5. Failure red tint
        if (redTint > 0f) {
            drawCircle(Red.copy(alpha = redTint), r, c)
        }

        // 6. Success: white translucent veil, green disc, and bouncy checkmark
        if (okProgress > 0f) {
            val s = okProgress
            drawCircle(Color.White.copy(alpha = (0.7f * s).coerceIn(0f, 1f)), r, c)
            drawCircle(Green, 40.dp.toPx() * s, c)
            val check = Path().apply {
                moveTo(c.x - 18.dp.toPx() * s, c.y)
                lineTo(c.x - 4.dp.toPx() * s, c.y + 14.dp.toPx() * s)
                lineTo(c.x + 20.dp.toPx() * s, c.y - 12.dp.toPx() * s)
            }
            drawPath(
                check, Color.White,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 7. Four white corner brackets around the scanner
        val arm = 20.dp.toPx()
        val reach = 130.dp.toPx()
        for (sx in listOf(-1f, 1f)) {
            for (sy in listOf(-1f, 1f)) {
                val corner = Offset(c.x + sx * reach, c.y + sy * reach)
                val bracket = Path().apply {
                    moveTo(corner.x, corner.y - sy * arm)
                    lineTo(corner.x, corner.y)
                    lineTo(corner.x - sx * arm, corner.y)
                }
                drawPath(
                    bracket, Color.White,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

/**
 * CameraX Live Front Camera embedded in COMPATIBLE mode clipped to a CircleShape.
 */
@Composable
internal fun CameraCircle(
    analyzer: ImageAnalysis.Analyzer,
    executor: Executor,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        cameraProviderFuture.addListener({
            runCatching {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.clip(CircleShape)
    )
}

/**
 * Fullscreen colorful diagonal gradient with slowly drifting soft glows.
 */
@Composable
internal fun GradientBackground(content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg_glows")
    val d1 by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "d1"
    )
    val d2 by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "d2"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Purple, BlueBg, Teal)))
            .drawBehind {
                glow(PinkGlow, Offset(size.width * 0.10f + 40.dp.toPx() * d1, size.height * 0.12f - 30.dp.toPx() * d1), 170.dp.toPx())
                glow(OrangeGlow, Offset(size.width * 0.90f - 40.dp.toPx() * d2, size.height * 0.92f + 20.dp.toPx() * d2), 190.dp.toPx())
                glow(Teal, Offset(size.width * 0.95f, size.height * 0.15f), 120.dp.toPx())
                glow(VioletGlow, Offset(size.width * 0.05f, size.height * 0.88f), 130.dp.toPx())
            },
        content = content
    )
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

@OptIn(ExperimentalGetImage::class)
internal fun processLiveFrame(
    imageProxy: ImageProxy,
    detector: com.google.mlkit.vision.face.FaceDetector,
    executor: Executor,
    callbackExecutor: Executor,
    onNoFace: () -> Unit,
    onMultipleFaces: (Int) -> Unit,
    onFaceDetected: (Face, List<Offset?>, Int, Int) -> Unit
) {
    runCatching {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
        val rotatedW = if (rotation % 180 == 0) mediaImage.width else mediaImage.height
        val rotatedH = if (rotation % 180 == 0) mediaImage.height else mediaImage.width

        detector.process(inputImage)
            .addOnSuccessListener(executor) { faces ->
                runCatching {
                    if (faces.size > 1) {
                        callbackExecutor.execute { onMultipleFaces(faces.size) }
                        return@addOnSuccessListener
                    }
                    val face = faces.firstOrNull()
                    if (face == null) {
                        callbackExecutor.execute { onNoFace() }
                    } else {
                        val side = minOf(rotatedW, rotatedH).toFloat().coerceAtLeast(1f)
                        val ox = (rotatedW - side) / 2f
                        val oy = (rotatedH - side) / 2f
                        val mapped = landmarkTypes.map { type ->
                            face.getLandmark(type)?.position?.let { p ->
                                // Front camera is mirrored horizontally
                                Offset(1f - (p.x - ox) / side, (p.y - oy) / side)
                            }
                        }
                        callbackExecutor.execute { onFaceDetected(face, mapped, rotatedW, rotatedH) }
                    }
                }
            }
            .addOnCompleteListener(executor) {
                runCatching { imageProxy.close() }
            }
    }.onFailure {
        runCatching { imageProxy.close() }
    }
}
