package com.waqar.institute.utils

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.designsystem.JetBrainsMono
import com.example.ui.designsystem.LiquidPill
import com.example.ui.designsystem.WciColors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecoverableErrorEvent(
    val id: Long = System.currentTimeMillis(),
    val source: String,
    val message: String,
    val recoverable: Boolean = true
)

/**
 * Global Crash Boundary & Graceful Degradation Handler (`GlobalExceptionHandler.kt`).
 *
 * Implements `Thread.UncaughtExceptionHandler` and a shared `CoroutineExceptionHandler`
 * so unexpected network timeouts, malformed JSON payloads, or background exceptions
 * never crash the process and instead surface a clean, dismissible "Retry" recovery banner.
 */
object GlobalExceptionHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "WciGlobalBoundary"
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val _lastErrorState = MutableStateFlow<RecoverableErrorEvent?>(null)
    val lastErrorState: StateFlow<RecoverableErrorEvent?> = _lastErrorState.asStateFlow()

    val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "Caught coroutine exception in global boundary: ${throwable.message}")
        _lastErrorState.value = RecoverableErrorEvent(
            source = "AsyncPipeline",
            message = throwable.localizedMessage ?: "Temporary network or sync interruption.",
            recoverable = true
        )
    }

    fun install() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !== this) {
            previousHandler = current
            Thread.setDefaultUncaughtExceptionHandler(this)
        }
    }

    fun reportRecoverableError(source: String, throwable: Throwable) {
        Log.w(TAG, "Recoverable error from $source: ${throwable.message}")
        _lastErrorState.value = RecoverableErrorEvent(
            source = source,
            message = throwable.localizedMessage ?: "Operation interrupted. Tap Retry to recover.",
            recoverable = true
        )
    }

    fun clearError() {
        _lastErrorState.value = null
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception intercepted on ${thread.name}", throwable)
        _lastErrorState.value = RecoverableErrorEvent(
            source = thread.name,
            message = throwable.localizedMessage ?: "Unexpected runtime error recovered safely.",
            recoverable = true
        )
        val isMainThread = runCatching {
            android.os.Looper.getMainLooper()?.thread === thread
        }.getOrDefault(thread.name == "main")

        if (isMainThread) {
            // When the Main UI thread crashes, do NOT freeze the looper into a 5-second black screen ANR.
            // Delegate cleanly to the platform default handler so Android does not hang on a dead looper.
            val handler = previousHandler
            if (handler != null) {
                handler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    @Composable
    fun GracefulFallbackBoundary(
        onRetry: () -> Unit,
        content: @Composable () -> Unit
    ) {
        val activeError by lastErrorState.collectAsState()
        Column {
            activeError?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(Color(0xFF1E1B4B), RoundedCornerShape(14.dp))
                        .border(1.dp, WciColors.Warning.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "Resilience Boundary",
                                    tint = WciColors.Warning
                                )
                                Text(
                                    text = "Graceful Recovery • ${err.source}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.Warning,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = err.message,
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
                            )
                        }
                        LiquidPill(
                            text = "Retry",
                            selected = true,
                            icon = Icons.Default.Refresh,
                            onClick = {
                                clearError()
                                onRetry()
                            }
                        )
                    }
                }
            }
            content()
        }
    }
}
