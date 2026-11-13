package io.github.lujinxin.nextep.logging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * User-initiated SAF ZIP export. Never requests blanket logcat permissions.
 * App and SystemUI snapshots are bounded and scoped to NeXtep; an optional
 * root-only logcat capture is also restricted to the NeXtep tag.
 */
object DiagnosticReportExporter {
    fun suggestedName(): String =
        "NeXtep-diagnostic-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"

    fun export(context: Context, uri: Uri) {
        val app = context.applicationContext ?: context
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val appSnapshot = NeXtepLog.snapshot()
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                writeAsync(app, uri, appSnapshot, null, "SystemUI did not respond within 3 seconds")
            }
        }
        handler.postDelayed(timeout, 3_000L)

        val intent = Intent(TriggerBroadcastContract.ACTION_QUERY_DIAGNOSTICS)
            .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        runCatching {
            app.sendOrderedBroadcast(
                intent, null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (!finished.compareAndSet(false, true)) return
                        handler.removeCallbacks(timeout)
                        val extras = getResultExtras(false)
                        val systemUiLogs = if (resultCode == TriggerBroadcastContract.RESULT_HANDLED)
                            extras?.getString(TriggerBroadcastContract.EXTRA_DIAGNOSTICS_LOG) else null
                        val reason = if (systemUiLogs == null) {
                            "SystemUI diagnostic broadcast not handled (result=$resultCode); " +
                                "the injected SystemUI may be running an older module. " +
                                "Check LSPosed scope and restart SystemUI or the phone."
                        } else null
                        writeAsync(app, uri, appSnapshot, systemUiLogs, reason)
                    }
                }, handler, TriggerBroadcastContract.RESULT_NOT_READY, null, Bundle(),
            )
        }.onFailure { error ->
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                writeAsync(app, uri, appSnapshot, null, "SystemUI request failed: ${error.message}")
            }
        }
    }

    private fun writeAsync(context: Context, uri: Uri, appLogs: String, systemLogs: String?, error: String?) {
        Thread({
            val result = runCatching {
                val device = buildString {
                    appendLine("Created: ${Date()}")
                    appendLine("Module package: ${context.packageName}")
                    appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
                    appendLine("Manufacturer: ${Build.MANUFACTURER}")
                    appendLine("Brand: ${Build.BRAND}")
                    appendLine("Device: ${Build.DEVICE}")
                    appendLine("Model: ${Build.MODEL}")
                    appendLine("Build: ${Build.DISPLAY}")
                    appendLine("Foreground process PID: ${android.os.Process.myPid()}")
                    appendLine("Elapsed realtime: ${SystemClock.elapsedRealtime()}ms")
                    appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
                    appendLine("Display density DPI: ${context.resources.displayMetrics.densityDpi}")
                    val packageInfo = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }.getOrNull()
                    appendLine("App version: ${packageInfo?.versionName ?: "unknown"}")
                    appendLine("SystemUI snapshot: ${if (systemLogs == null) "unavailable" else "received"}")
                    error?.let { appendLine("SystemUI note: $it") }
                }
                val settings = SettingsRepository(context).topBarSettings()
                val safeSettings = buildString {
                    appendLine("Status-bar gesture: ${settings.statusBarGestureEnabled}")
                    appendLine("Four-finger gesture: ${settings.fourFingerGestureEnabled}")
                    appendLine("Floating bubble: ${settings.floatingBubbleEnabled}")
                    appendLine("Floating Pad mode requested: ${settings.floatingPadModeEnabled}")
                    appendLine("Pad profile applies only to newly created floating displays")
                    appendLine("Background swipe: ${settings.slotBackgroundSwipeEnabled}")
                    appendLine("Block system drag/share: ${settings.blockSystemDragShareEnabled}")
                    appendLine("Return after sharing: ${settings.returnAfterSharingEnabled}")
                    appendLine("Auto minimize: ${settings.autoMinimizeMainOnTopAppSwitch}")
                    appendLine("Navigation buttons: ${settings.navigationButtonsEnabled}")
                    appendLine("Swap animation ms: ${settings.swapAnimationDurationMs}")
                    appendLine("No contacts, accounts, identifiers or selected-app lists are exported.")
                }
                val rootCapture = captureRootLogcat()
                val rootNote = rootCapture.status
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putText("README.txt", buildString {
                            appendLine("NeXtep diagnostic export; share only with trusted maintainers.")
                            appendLine("App and SystemUI memory snapshots are bounded, so earlier lines may be missing.")
                            appendLine("Logs may contain app package names, component names and exception details.")
                            appendLine("Sensitive data has not been comprehensively redacted; review before sharing.")
                            appendLine(rootNote)
                            if (error != null) appendLine(error)
                        })
                        zip.putText("device.txt", device)
                        zip.putText("settings-safe.txt", safeSettings)
                        zip.putText("logs/app-NeXtep.txt", appLogs.ifEmpty { "No app logs recorded in this process yet." })
                        zip.putText("logs/systemui-NeXtep.txt", systemLogs ?: "UNAVAILABLE: ${error ?: "No response"}")
                        zip.putText("logs/root-NeXtep-logcat.txt", rootCapture.text ?: rootNote)
                    }
                } ?: error("Could not open the selected destination")
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    if (result.isSuccess) context.getString(R.string.diagnostic_export_success)
                    else context.getString(R.string.diagnostic_export_failed,
                        result.exceptionOrNull()?.message ?: "unknown error"),
                    Toast.LENGTH_LONG,
                ).show()
            }
            result.onFailure { NeXtepLog.warn("diagnostics_export", "ZIP export failed", it) }
        }, "NeXtep-DiagnosticsExport").apply { isDaemon = true }.start()
    }

    private fun ZipOutputStream.putText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private data class RootCapture(val text: String?, val status: String)

    private fun captureRootLogcat(): RootCapture = runCatching {
        // Root is optional, but report precisely why this capture did not
        // work. Long first-run KernelSU prompts must not be mistaken for
        // successful collection of an empty buffer.
        val process = ProcessBuilder("su", "-c", "logcat -d -t 1500 -s NeXtep:*")
            .redirectErrorStream(true).start()
        val content = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(2048)
                    while (content.length < 130_000) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(content) {
                            content.append(buffer, 0, minOf(count, 130_000 - content.length))
                        }
                    }
                }
            }
        }, "NeXtep-LogcatReader").apply { isDaemon = true }
        reader.start()
        val completed = process.waitFor(10, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(750)
        val output = synchronized(content) { content.toString() }
        val status = when {
            !completed -> "Root logcat timed out after 10 seconds; check KernelSU authorization"
            process.exitValue() != 0 ->
                "Root logcat command failed (exit=${process.exitValue()}): ${output.take(500)}"
            output.isBlank() -> "Root logcat completed but returned no NeXtep-tagged lines"
            else -> "Root logcat captured (NeXtep tag only)"
        }
        RootCapture(if (completed && process.exitValue() == 0 && output.isNotBlank()) output else null,
            status)
    }.getOrElse { error ->
        RootCapture(null, "Root logcat could not execute: ${error.javaClass.simpleName}: ${error.message}")
    }
}
