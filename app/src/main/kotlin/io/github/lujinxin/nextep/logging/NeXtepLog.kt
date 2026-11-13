package io.github.lujinxin.nextep.logging

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keep a bounded, per-process copy of module logs in addition to Android
 * logcat. The ordinary app cannot read SystemUI's logcat on current Android,
 * so SystemUI returns its own snapshot via our signature-protected receiver.
 *
 * Only events from this module are retained; there is no global logcat read.
 */
object NeXtepLog {
    private const val TAG = "NeXtep"
    private const val MAX_ENTRIES = 300
    private const val MAX_LINE_CHARS = 1600
    private const val MAX_SNAPSHOT_CHARS = 64_000
    private val guard = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    fun debug(feature: String, message: String) = write(Log.DEBUG, feature, message, null)
    fun info(feature: String, message: String) = write(Log.INFO, feature, message, null)
    fun warn(feature: String, message: String, error: Throwable? = null) =
        write(Log.WARN, feature, message, error)
    fun error(feature: String, message: String, error: Throwable? = null) =
        write(Log.ERROR, feature, message, error)

    fun snapshot(maxChars: Int = MAX_SNAPSHOT_CHARS): String {
        val cap = maxChars.coerceIn(1024, MAX_SNAPSHOT_CHARS)
        return synchronized(guard) {
            val results = ArrayDeque<String>()
            var chars = 0
            for (entry in entries.reversed()) {
                if (chars + entry.length + 1 > cap) break
                results.addFirst(entry)
                chars += entry.length + 1
            }
            results.joinToString("\n")
        }
    }

    private fun write(priority: Int, feature: String, message: String, error: Throwable?) {
        val enriched = "pid=${Process.myPid()} thread=${Thread.currentThread().name} " +
            "feature=$feature sdk=${Build.VERSION.SDK_INT} message=$message"
        val output = if (error == null) enriched else "$enriched\n${Log.getStackTraceString(error)}"
        Log.println(priority, TAG, output)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val shortMessage = output.replace("\r", "").take(MAX_LINE_CHARS)
        val line = "$timestamp ${when (priority) { Log.DEBUG -> "D"; Log.INFO -> "I"; Log.WARN -> "W"; else -> "E" }} ${SystemClock.uptimeMillis()}ms $shortMessage"
        synchronized(guard) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }
}
