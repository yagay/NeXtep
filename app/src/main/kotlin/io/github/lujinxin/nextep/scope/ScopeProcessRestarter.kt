package io.github.lujinxin.nextep.scope

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.concurrent.TimeUnit

/**
 * Restrict restarts to the module's known, non-critical LSPosed app processes.
 * System server ('android' / 'system' scopes) cannot be restarted on its own:
 * killing it would restart Android services and is intentionally unsupported.
 *
 * This does not alter LSPosed scope enablement, and it cannot certify that
 * LSPosed has re-hooked until the restarted process loads the module.
 */
internal object ScopeProcessRestarter {
    enum class Target(val processName: String?, val canRestart: Boolean) {
        SYSTEM_UI("com.android.systemui", true),
        OPLUS_LAUNCHER("com.oplus.launcher", true),
        ANDROID_LAUNCHER("com.android.launcher", true),
        OPLUS_MULTIAPP("com.oplus.multiapp", true),
        ASSISTANT_SCREEN("com.coloros.assistantscreen", true),
        SYSTEM_FRAMEWORK(null, false),
    }

    sealed interface Outcome {
        data class Requested(val process: String) : Outcome
        data class Unavailable(val reason: String) : Outcome
    }

    fun restart(context: Context, target: Target, callback: (Outcome) -> Unit) {
        val processName = target.processName
        if (!target.canRestart || processName == null) {
            callback(Outcome.Unavailable("system_server requires a full device restart"))
            return
        }
        // The name comes from a hardcoded enum, never from user input, so it
        // cannot inject shell syntax. A second exact /proc cmdline check keeps
        // pidof results from targeting unexpected processes.
        check(processName.matches(Regex("[A-Za-z0-9._]+")))
        Thread({
            val result = runCatching {
                val script = """
                    [ "$(id -u)" = 0 ] || { echo ROOT_REQUIRED; exit 11; }
                    pids=$(pidof $processName) || { echo PROCESS_NOT_RUNNING; exit 12; }
                    count=0
                    for pid in $pids; do
                      case "$pid" in ''|*[!0-9]*) continue ;; esac
                      [ "$pid" -gt 100 ] || continue
                      actual=$(tr '\000' '\n' < "/proc/$pid/cmdline" | head -n 1)
                      [ "$actual" = "$processName" ] || continue
                      kill -TERM "$pid" || { echo SIGNAL_FAILED; exit 13; }
                      count=$((count+1))
                    done
                    [ "$count" -gt 0 ] || { echo PROCESS_NOT_FOUND; exit 14; }
                    echo SIGNAL_SENT
                """.trimIndent()
                val proc = ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
                val completed = proc.waitFor(8, TimeUnit.SECONDS)
                if (!completed) {
                    proc.destroyForcibly()
                    return@runCatching Outcome.Unavailable("Root command timed out")
                }
                val response = proc.inputStream.bufferedReader().use { it.readText() }.take(1200).trim()
                if (proc.exitValue() == 0 && response.contains("SIGNAL_SENT")) {
                    Outcome.Requested(processName)
                } else {
                    Outcome.Unavailable(
                        when {
                            response.contains("ROOT_REQUIRED") -> "Root permission was not granted"
                            response.contains("PROCESS_NOT_RUNNING") ||
                                response.contains("PROCESS_NOT_FOUND") -> "Target process is not running"
                            response.contains("SIGNAL_FAILED") -> "Could not signal the target process"
                            else -> "Root command failed: ${response.ifBlank { "exit ${proc.exitValue()}" }}"
                        },
                    )
                }
            }.getOrElse {
                Outcome.Unavailable(it.message ?: it.javaClass.simpleName)
            }
            when (result) {
                is Outcome.Requested ->
                    NeXtepLog.info("scope_restart", "TERM delivered to ${result.process}")
                is Outcome.Unavailable ->
                    NeXtepLog.warn("scope_restart", "Restart unavailable for $processName: ${result.reason}")
            }
            Handler(Looper.getMainLooper()).post { callback(result) }
        }, "NeXtep-ScopeRestart").apply { isDaemon = true }.start()
    }
}
