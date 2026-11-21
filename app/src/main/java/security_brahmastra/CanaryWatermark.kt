package security_brahmastra

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas

/**
 * 2. `security_brahmastra/CanaryWatermark.kt`
 * Cryptographic Zero-Width Unicode Text Canary & Low-Opacity Diagonal Canvas Watermark.
 */
object CanaryWatermark {

    private const val ZW_ZERO = '\u200B' // Zero-Width Space (bit 0)
    private const val ZW_ONE = '\u200C'  // Zero-Width Non-Joiner (bit 1)
    private const val ZW_SEP = '\u200D'  // Zero-Width Joiner (frame boundary)

    /**
     * Embeds invisible zero-width Unicode characters (`\u200B`, `\u200C`, `\u200D`) encoding
     * `studentId|timestampSec` into every exam question string so any copy-pasted leak reveals the exact student.
     */
    fun injectTextCanary(
        text: String,
        studentId: String,
        timestampEpochSec: Long = System.currentTimeMillis() / 1000L
    ): String {
        val cleanStudent = studentId.trim().ifBlank { "anon" }
        val payload = "$cleanStudent|$timestampEpochSec"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val encoded = StringBuilder()
        encoded.append(ZW_SEP)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            for (bit in 7 downTo 0) {
                val isOne = ((unsigned ushr bit) and 1) == 1
                encoded.append(if (isOne) ZW_ONE else ZW_ZERO)
            }
        }
        encoded.append(ZW_SEP)

        val insertPos = (text.length / 2).coerceIn(0, text.length)
        return text.substring(0, insertPos) + encoded.toString() + text.substring(insertPos)
    }

    /**
     * Extracts the hidden `studentId|timestampSec` canary from a leaked question string.
     */
    fun extractTextCanary(watermarkedText: String): String? {
        val firstSep = watermarkedText.indexOf(ZW_SEP)
        if (firstSep < 0) return null
        val secondSep = watermarkedText.indexOf(ZW_SEP, firstSep + 1)
        if (secondSep <= firstSep + 1) return null

        val bits = watermarkedText.substring(firstSep + 1, secondSep)
            .filter { it == ZW_ZERO || it == ZW_ONE }
        if (bits.isEmpty() || bits.length % 8 != 0) return null

        val outBytes = ByteArray(bits.length / 8)
        for (i in outBytes.indices) {
            var value = 0
            for (bit in 0 until 8) {
                value = (value shl 1) or if (bits[i * 8 + bit] == ZW_ONE) 1 else 0
            }
            outBytes[i] = value.toByte()
        }
        return String(outBytes, Charsets.UTF_8)
    }
}

/**
 * Overlays a transparent, pointer-events-none, ultra-low-opacity (0.02f) repeating diagonal
 * watermark across the exam screen so any external camera photo or screenshot reveals the exact leaker.
 */
@Composable
fun MountCanvasCanary(
    studentId: String,
    sessionId: String,
    modifier: Modifier = Modifier
) {
    val tagText = "WCI-CANARY • $studentId • $sessionId"
    Canvas(modifier = modifier.fillMaxSize()) {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(6, 255, 255, 255) // ~0.023 opacity
            textSize = 28f
            isAntiAlias = true
        }
        rotate(degrees = -28f) {
            val stepX = 360f
            val stepY = 180f
            var y = -size.height * 0.5f
            while (y < size.height * 1.5f) {
                var x = -size.width * 0.5f
                while (x < size.width * 1.5f) {
                    drawContext.canvas.nativeCanvas.drawText(tagText, x, y, paint)
                    x += stepX
                }
                y += stepY
            }
        }
        drawRect(color = Color.Transparent)
    }
}
