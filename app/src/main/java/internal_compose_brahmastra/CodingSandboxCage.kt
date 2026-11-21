package internal_compose_brahmastra

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 3. `internal_compose_brahmastra/CodingSandboxCage.kt`
 * Pre-Execution Abstract Syntax Tree (AST) / Lexical Security Scanner and Strict `withTimeout`
 * Coroutine Execution Cage for the Student Live Coding Compiler.
 */
object CodingSandboxCage {

    private val FORBIDDEN_AST_PATTERNS = listOf(
        Regex("""\bRuntime\s*\.\s*getRuntime\b"""),
        Regex("""\bProcessBuilder\b"""),
        Regex("""\bSystem\s*\.\s*(exit|load|loadLibrary)\b"""),
        Regex("""\bjava\s*\.\s*lang\s*\.\s*reflect\b"""),
        Regex("""\bClass\s*\.\s*forName\b"""),
        Regex("""\bimport\s+(os|subprocess|socket|shutil|ctypes|pty)\b"""),
        Regex("""\b(__import__|eval|exec|open)\s*\("""),
        Regex("""\b(fetch|XMLHttpRequest|WebSocket|localStorage|indexedDB)\b"""),
        Regex("""while\s*\(\s*true\s*\)|while\s+True\s*:""")
    )

    data class AstScanReport(
        val isSafeToExecute: Boolean,
        val violatedRule: String?
    )

    sealed class CagedExecutionResult<out T> {
        data class Success<T>(val output: T, val elapsedMs: Long) : CagedExecutionResult<T>()
        data class BlockedByAstScanner(val violation: String) : CagedExecutionResult<Nothing>()
        data class TimedOut(val timeoutLimitMs: Long) : CagedExecutionResult<Nothing>()
        data class ExecutionError(val message: String) : CagedExecutionResult<Nothing>()
    }

    fun scanSourceCodeAst(sourceCode: String): AstScanReport {
        for (regex in FORBIDDEN_AST_PATTERNS) {
            val match = regex.find(sourceCode)
            if (match != null) {
                return AstScanReport(
                    isSafeToExecute = false,
                    violatedRule = "AST_FORBIDDEN_TOKEN:${match.value.trim()}"
                )
            }
        }
        return AstScanReport(isSafeToExecute = true, violatedRule = null)
    }

    suspend fun <T> executeInCoroutineCage(
        sourceCode: String,
        timeoutMs: Long = 2_500L,
        block: suspend () -> T
    ): CagedExecutionResult<T> {
        val scan = scanSourceCodeAst(sourceCode)
        if (!scan.isSafeToExecute) {
            return CagedExecutionResult.BlockedByAstScanner(scan.violatedRule ?: "AST_POLICY_VIOLATION")
        }
        val startMs = System.currentTimeMillis()
        return try {
            val out = withContext(Dispatchers.Default) {
                withTimeout(timeoutMs) {
                    block()
                }
            }
            CagedExecutionResult.Success(
                output = out,
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            CagedExecutionResult.TimedOut(timeoutMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CagedExecutionResult.ExecutionError(t.message ?: "Execution failed inside sandbox cage.")
        }
    }
}
