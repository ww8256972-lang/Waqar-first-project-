# Teacher Face Attendance: animated scanner (Jetpack Compose, native Android APK)

This file has 3 parts:
1. The prompt (copy-paste into any AI coding tool)
2. The animation preview spec (what the scanner looks like and how it moves)
3. The complete reference code (Kotlin + Jetpack Compose, ready to paste into Android Studio)

Not a web app. Native Android APK, Kotlin, Jetpack Compose, CameraX, ML Kit, TensorFlow Lite.

---

## PART 1: THE PROMPT

```
Build a native Android screen in Kotlin with Jetpack Compose (NOT XML, NOT WebView, NOT a web app) for the "Teacher Face Attendance" feature of my computer institute app. It needs a beautiful animated circular face scanner.

BACKGROUND
- Full-screen colorful diagonal gradient: purple (#7C3AED) -> blue (#2563EB) -> teal (#06B6D4).
- Soft radial-gradient glow circles (pink #EC4899, orange #F97316, cyan, violet) that drift slowly. No black and no plain white background.
- White text. Title "Teacher attendance" at the top.

CAMERA
- CameraX front camera shown inside a circle (about 192dp diameter, centered), using PreviewView embedded with AndroidView, implementationMode COMPATIBLE, clipped with CircleShape.
- The circle must show the LIVE face of the person scanning. No drawn or fake face.

ANIMATED OVERLAY (Compose Canvas drawn on top of the camera circle)
1. Outer ring: white dashed ring, rotates clockwise, 6s per turn, infinite.
2. Inner ring: yellow (#FDE68A) dotted ring, rotates counter-clockwise, 9s per turn, infinite.
3. Pulse: two expanding, fading white rings repeating every 2s, the second offset by 1s.
4. Scan line: pink (#EC4899) horizontal line with a soft glow trail, moving up and down inside the circle (1.5s each way, reverse, infinite), clipped to the circle.
5. Face mesh: when ML Kit detects a face, show blinking dots on the real landmarks (eyes, nose, mouth corners, mouth bottom, cheeks) joined by thin lines, mapped correctly onto the circular preview (front camera is mirrored). Also faint grid lines inside the circle.
6. Four white corner brackets around the scanner.
All animations use rememberInfiniteTransition and Canvas drawing (no heavy recomposition), smooth 60fps.

STATES
- Scanning: text "Scanning face..." plus hint "Look at the camera and blink once".
- Matched: scan line fades out, white translucent overlay with green (#22C55E) circle and bouncy checkmark, text "Face matched · Attendance marked", then call onAttendanceMarked().
- Not recognized: red tint, horizontal shake animation, text "Face not recognized. Try again.", then automatically return to Scanning after 1.5s.

LOGIC
- CameraX ImageAnalysis (STRATEGY_KEEP_ONLY_LATEST) with ML Kit Face Detection (landmarks + classification).
- Liveness: require one blink (eyes open -> closed -> open) before matching, to block photo spoofing.
- Matching: crop the face, compute a 192-dim embedding with TensorFlow Lite MobileFaceNet (112x112 input), L2 normalize, compare to the teacher's enrolled embedding with cosine similarity. Threshold 0.6, easy to tune.
- Run analysis and TFLite on a background executor. Compose state may be written from there.
- Handle the camera permission request with rememberLauncherForActivityResult.
- Release camera, detector, executor and interpreter when the screen leaves.

DELIVERABLES
- build.gradle.kts dependencies, AndroidManifest permission, and these files: FaceAttendanceActivity.kt, FaceAttendanceScreen.kt, ScannerOverlay.kt, FaceScanController.kt, FaceAnalyzer.kt, FaceMatcher.kt.
- Clean, commented, compiling code.
```

---

## PART 2: ANIMATION PREVIEW SPEC

A live animated preview was shown in the chat. Same design, as a table:

| Element | Look | Motion |
|---|---|---|
| Background | Purple -> blue -> teal diagonal gradient | Pink, orange, cyan, violet glows drift slowly |
| Camera circle | 192dp, live front camera | Shakes sideways on failed match |
| Outer ring | White dashed, r = 118dp | Rotates clockwise, 6s, infinite |
| Inner ring | Yellow dotted, r = 108dp | Rotates counter-clockwise, 9s, infinite |
| Pulse rings | White, r = 112dp | Expand 22% and fade, every 2s, second ring offset 1s |
| Scan line | Pink line + glow, inside circle | Up/down, 1.5s each way, infinite |
| Face mesh | Dots + thin lines on real landmarks | Dots blink (0.3 -> 1 alpha, 0.6s) |
| Corner brackets | White L-shapes at 4 corners | Static |
| Success | White veil + green circle + white tick | Bouncy spring scale-in |
| Failure | Red tint on circle | Shake, auto-reset after 1.5s |

Text states: "Scanning face..." -> "Face matched · Attendance marked" (or "Face not recognized. Try again.").

---

## PART 3: COMPLETE CODE

Change the package name `com.vakaar.institute.face` to your own.

### 3.1 app/build.gradle.kts (add inside `android { }` and `dependencies { }`)

```kotlin
android {
    // ...your existing config...
    androidResources {
        noCompress += "tflite"   // required so the model can be memory-mapped
    }
}

dependencies {
    // Jetpack Compose (use your existing Compose BOM)
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // CameraX
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // ML Kit face detection (landmarks, blink probability)
    implementation("com.google.mlkit:face-detection:16.1.6")

    // TensorFlow Lite for the face embedding model
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
```

Model file: put a MobileFaceNet TFLite model (112x112 input, 192-float output) at `app/src/main/assets/mobilefacenet.tflite`.

### 3.2 AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.front" android:required="false" />
```

### 3.3 FaceAttendanceActivity.kt

```kotlin
package com.vakaar.institute.face

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

class FaceAttendanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: replace with the teacher's real enrolled embedding
        // (saved in Room / SharedPreferences / fetched from your server at enrollment time).
        val enrolledEmbedding: FloatArray = loadEnrolledEmbedding()

        setContent {
            MaterialTheme {
                FaceAttendanceScreen(
                    enrolledEmbedding = enrolledEmbedding,
                    onAttendanceMarked = {
                        // TODO: mark attendance in your backend, then finish() or navigate.
                    }
                )
            }
        }
    }

    private fun loadEnrolledEmbedding(): FloatArray {
        // Placeholder. Create it once with FaceMatcher.embed(croppedFaceBitmap)
        // during teacher enrollment and store the 192 floats.
        return FloatArray(192)
    }
}
```

### 3.4 FaceScanController.kt

```kotlin
package com.vakaar.institute.face

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

enum class ScanState { Scanning, Matched, NotRecognized }

/** Holds UI state. Compose state can be written from the analyzer thread safely. */
class FaceScanController(
    private val matcher: FaceMatcher,
    private val enrolled: FloatArray,
    private val threshold: Float = 0.6f,
    private val onMatched: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    var state by mutableStateOf(ScanState.Scanning)
        private set

    /** Normalized (0..1) landmark positions inside the circular preview; null = not found. */
    var landmarks by mutableStateOf<List<Offset?>>(emptyList())
        private set

    val isScanning: Boolean get() = state == ScanState.Scanning

    fun onNoFace() {
        landmarks = emptyList()
    }

    fun onLandmarks(points: List<Offset?>) {
        landmarks = points
    }

    fun onEmbedding(embedding: FloatArray) {
        if (matcher.similarity(embedding, enrolled) >= threshold) {
            state = ScanState.Matched
            main.post(onMatched)
        } else {
            state = ScanState.NotRecognized
            main.postDelayed({ state = ScanState.Scanning }, 1500)
        }
    }
}
```

### 3.5 FaceMatcher.kt

```kotlin
package com.vakaar.institute.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/** MobileFaceNet embedding + cosine similarity. Call embed() from one thread only. */
class FaceMatcher(context: Context, modelAsset: String = "mobilefacenet.tflite") {

    private val inputSize = 112
    private val interpreter: Interpreter
    private val outputSize: Int

    init {
        val fd = context.assets.openFd(modelAsset)
        val buffer = FileInputStream(fd.fileDescriptor).use {
            it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        interpreter = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
        outputSize = interpreter.getOutputTensor(0).shape().last()
    }

    fun embed(face: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(face, inputSize, inputSize, true)
        val input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        return l2Normalize(output[0])
    }

    /** Both vectors are L2-normalized, so the dot product is the cosine similarity. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    fun close() = interpreter.close()

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).coerceAtLeast(1e-8f)
        return FloatArray(v.size) { v[it] / norm }
    }
}
```

### 3.6 FaceAnalyzer.kt

```kotlin
package com.vakaar.institute.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.Executor

/**
 * Detects the face, reports landmarks for the mesh, checks a blink (liveness),
 * then computes the embedding and hands it to the controller.
 */
class FaceAnalyzer(
    private val matcher: FaceMatcher,
    private val controller: FaceScanController,
    private val executor: Executor
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.3f)
            .build()
    )

    private var sawOpen = false
    private var sawClosed = false
    private var livenessPassed = false
    private var lastEmbedMs = 0L

    // Order matters: it matches the line pairs drawn in ScannerOverlay.
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

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val rotatedW = if (rotation % 180 == 0) media.width else media.height
        val rotatedH = if (rotation % 180 == 0) media.height else media.width

        detector.process(input)
            .addOnSuccessListener(executor) { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() }
                if (face == null) {
                    controller.onNoFace()
                    sawOpen = false
                    sawClosed = false
                } else {
                    controller.onLandmarks(mapLandmarks(face, rotatedW, rotatedH))
                    updateLiveness(face)
                    val now = System.currentTimeMillis()
                    if (controller.isScanning && livenessPassed && now - lastEmbedMs > 400) {
                        lastEmbedMs = now
                        val crop = cropFace(imageProxy, face.boundingBox, rotation)
                        if (crop != null) {
                            livenessPassed = false // next retry needs a new blink
                            controller.onEmbedding(matcher.embed(crop))
                        }
                    }
                }
            }
            .addOnCompleteListener(executor) { imageProxy.close() }
    }

    /** Maps landmarks into the centered square that the circular preview shows (mirrored for front camera). */
    private fun mapLandmarks(face: Face, w: Int, h: Int): List<Offset?> {
        val side = minOf(w, h).toFloat()
        val ox = (w - side) / 2f
        val oy = (h - side) / 2f
        return landmarkTypes.map { type ->
            face.getLandmark(type)?.position?.let { p ->
                Offset(1f - (p.x - ox) / side, (p.y - oy) / side)
            }
        }
    }

    private fun updateLiveness(face: Face) {
        val left = face.leftEyeOpenProbability ?: return
        val right = face.rightEyeOpenProbability ?: return
        val open = left > 0.7f && right > 0.7f
        val closed = left < 0.3f && right < 0.3f
        if (open) {
            if (sawOpen && sawClosed) livenessPassed = true
            sawOpen = true
            sawClosed = false
        } else if (closed && sawOpen) {
            sawClosed = true
        }
    }

    private fun cropFace(proxy: ImageProxy, box: Rect, rotation: Int): Bitmap? {
        val bitmap = proxy.toBitmap()
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val left = box.left.coerceAtLeast(0)
        val top = box.top.coerceAtLeast(0)
        val width = box.right.coerceAtMost(rotated.width) - left
        val height = box.bottom.coerceAtMost(rotated.height) - top
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(rotated, left, top, width, height)
    }

    fun close() = detector.close()
}
```

### 3.7 ScannerOverlay.kt (all the animations)

```kotlin
package com.vakaar.institute.face

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
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

private val Blue = Color(0xFF2563EB)
private val Indigo = Color(0xFF4F46E5)
private val Pink = Color(0xFFEC4899)
private val Yellow = Color(0xFFFDE68A)
private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFEF4444)

// Pairs of landmark indexes joined by thin lines (see landmarkTypes order in FaceAnalyzer).
private val meshLines = listOf(
    0 to 1, 0 to 2, 1 to 2, 3 to 2, 4 to 2, 3 to 4, 5 to 0, 6 to 1, 3 to 7, 4 to 7
)

/**
 * Draws everything that sits on top of the camera circle.
 * Give it a 280.dp square box; the camera circle (192.dp) is centered under it.
 */
@Composable
fun ScannerOverlay(
    state: ScanState,
    landmarks: List<Offset?>,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "scanner")

    val ring1 by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "ring1"
    )
    val ring2 by transition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "ring2"
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
        label = "scan"
    )
    val blink by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink"
    )

    val okProgress by animateFloatAsState(
        targetValue = if (state == ScanState.Matched) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ok"
    )
    val redTint by animateFloatAsState(
        targetValue = if (state == ScanState.NotRecognized) 0.35f else 0f,
        label = "red"
    )

    Canvas(modifier) {
        val c = center
        val r = 96.dp.toPx()                    // camera circle radius
        val circleClip = Path().apply { addOval(Rect(center = c, radius = r)) }

        // 1) pulse rings
        listOf(pulseA, pulseB).forEach { p ->
            drawCircle(
                color = Color.White.copy(alpha = 0.6f * (1f - p)),
                radius = 112.dp.toPx() * (1f + 0.22f * p),
                center = c,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // 2) outer white dashed ring (clockwise)
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

        // 3) inner yellow dotted ring (counter-clockwise)
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

        // 4) everything inside the camera circle
        clipPath(circleClip) {
            // faint grid
            for (i in -2..2) {
                val y = c.y + i * 30.dp.toPx()
                drawLine(Indigo.copy(alpha = 0.25f), Offset(c.x - r, y), Offset(c.x + r, y), 0.5.dp.toPx())
            }
            for (i in -1..1) {
                val x = c.x + i * 50.dp.toPx()
                drawLine(Indigo.copy(alpha = 0.25f), Offset(x, c.y - r), Offset(x, c.y + r), 0.5.dp.toPx())
            }

            // face mesh: lines then blinking dots on real landmarks
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
                        drawCircle(Blue.copy(alpha = a), 2.dp.toPx(), p)
                    }
                }
            }

            // scan line with glow trail
            if (okProgress < 0.99f) {
                val y = c.y + scan * 100.dp.toPx()
                val glow = 22.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Pink.copy(alpha = 0.35f * (1f - okProgress))),
                        startY = y - glow, endY = y
                    ),
                    topLeft = Offset(c.x - r, y - glow),
                    size = Size(2 * r, glow)
                )
                drawRect(
                    color = Pink.copy(alpha = 1f - okProgress),
                    topLeft = Offset(c.x - r, y - 2.dp.toPx()),
                    size = Size(2 * r, 4.dp.toPx())
                )
            }
        }

        // 5) failure tint
        if (redTint > 0f) drawCircle(Red.copy(alpha = redTint), r, c)

        // 6) success: white veil, green disc, checkmark
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

        // 7) corner brackets
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
```

### 3.8 FaceAttendanceScreen.kt (background, camera circle, state texts)

```kotlin
package com.vakaar.institute.face

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private val Purple = Color(0xFF7C3AED)
private val BlueBg = Color(0xFF2563EB)
private val Teal = Color(0xFF06B6D4)
private val PinkGlow = Color(0xFFEC4899)
private val OrangeGlow = Color(0xFFF97316)
private val VioletGlow = Color(0xFFA78BFA)

@Composable
fun FaceAttendanceScreen(
    enrolledEmbedding: FloatArray,
    onAttendanceMarked: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    GradientBackground {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Teacher attendance", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(32.dp))
            if (granted) {
                ScannerContent(enrolledEmbedding, onAttendanceMarked)
            } else {
                Text("Camera permission is required", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ScannerContent(
    enrolledEmbedding: FloatArray,
    onAttendanceMarked: () -> Unit
) {
    val context = LocalContext.current
    val latestCallback by rememberUpdatedState(onAttendanceMarked)

    val matcher = remember { FaceMatcher(context) }
    val controller = remember { FaceScanController(matcher, enrolledEmbedding) { latestCallback() } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { FaceAnalyzer(matcher, controller, executor) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdownNow()
            analyzer.close()
            matcher.close()
        }
    }

    // Shake on failed match
    val shake = remember { Animatable(0f) }
    LaunchedEffect(controller.state) {
        if (controller.state == ScanState.NotRecognized) {
            repeat(4) {
                shake.animateTo(12f, tween(50))
                shake.animateTo(-12f, tween(50))
            }
            shake.animateTo(0f, tween(50))
        }
    }

    Box(
        modifier = Modifier
            .size(280.dp)
            .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
    ) {
        CameraCircle(
            analyzer = analyzer,
            executor = executor,
            modifier = Modifier.size(192.dp).align(Alignment.Center)
        )
        ScannerOverlay(
            state = controller.state,
            landmarks = controller.landmarks,
            modifier = Modifier.matchParentSize()
        )
    }

    Spacer(Modifier.height(24.dp))

    Crossfade(targetState = controller.state, label = "status") { s ->
        Text(
            text = when (s) {
                ScanState.Scanning -> "Scanning face…"
                ScanState.Matched -> "Face matched · Attendance marked"
                ScanState.NotRecognized -> "Face not recognized. Try again."
            },
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
    Spacer(Modifier.height(8.dp))
    Text("Look at the camera and blink once", color = Color(0xFFE0E7FF), fontSize = 13.sp)
}

/** Live front camera, clipped to a circle. COMPATIBLE mode (TextureView) so clipping and overlays work. */
@Composable
private fun CameraCircle(
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
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    AndroidView(factory = { previewView }, modifier = modifier.clip(CircleShape))
}

/** Purple -> blue -> teal diagonal gradient with slowly drifting soft glows. */
@Composable
private fun GradientBackground(content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
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
```

---

## Notes

- Threshold: `FaceScanController(threshold = 0.6f)`. Raise it for stricter matching, lower it if real teachers get rejected.
- Enrollment: use the same pipeline (same camera, same crop, same `FaceMatcher.embed`) when saving a teacher's embedding, so the vectors are comparable.
- Liveness here is a single blink, which stops simple photo attacks. For higher security add head-turn or a dedicated anti-spoofing model.
- Never store face photos unnecessarily. Store only the 192-float embedding, and protect it (EncryptedSharedPreferences or your server).
- If the camera circle ever looks black or is not clipped, keep `PreviewView.ImplementationMode.COMPATIBLE` as written.
