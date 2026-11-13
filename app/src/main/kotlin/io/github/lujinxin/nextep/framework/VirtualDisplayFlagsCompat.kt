package io.github.lujinxin.nextep.framework

import android.hardware.display.DisplayManager
import io.github.lujinxin.nextep.logging.NeXtepLog

object VirtualDisplayFlagsCompat {
    data class Resolved(val value: Int, val names: List<String>)

    fun resolveCandidates(padMode: Boolean = false): List<Resolved> {
        val baseValue = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val flags = listOf(
            "VIRTUAL_DISPLAY_FLAG_TRUSTED" to "TRUSTED",
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS" to "OWN_FOCUS",
            "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" to "OWN_DISPLAY_GROUP",
            "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED" to "ALWAYS_UNLOCKED",
            "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED" to "STEAL_TOP_FOCUS_DISABLED",
        ).mapNotNull { (fieldName, label) ->
            readFlag(fieldName)?.let { label to it }
        }.toMap()
        val candidates = FloatingPadFocusPolicy.candidates(flags.keys, padMode)
            .map { names ->
                val value = names.fold(baseValue) { result, name ->
                    result or (flags[name] ?: 0)
                }
                Resolved(value, names)
            }.distinctBy { it.value }
        NeXtepLog.info("virtual_display_flags",
            "padMode=$padMode candidates=${candidates.joinToString { "${it.value}:${it.names}" }}")
        return candidates
    }

    private fun readFlag(name: String): Int? = runCatching {
        DisplayManager::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
    }.onFailure { error ->
        NeXtepLog.debug("virtual_display_flags",
            "Hidden flag unavailable name=$name error=${error.javaClass.simpleName}")
    }.getOrNull()
}
