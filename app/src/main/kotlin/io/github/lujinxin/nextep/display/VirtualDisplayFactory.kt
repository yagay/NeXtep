package io.github.lujinxin.nextep.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import io.github.lujinxin.nextep.framework.VirtualDisplayFlagsCompat
import io.github.lujinxin.nextep.logging.NeXtepLog

class VirtualDisplayFactory(
    context: Context,
    private val floatingSlot: Boolean = false,
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    companion object {
        // Shared across all four slots: a denied privileged flag will be
        // rejected for every subsequent display in this process.
        private val unlockedPermissionDenied = AtomicBoolean(false)
    }

    fun create(index: Int, geometry: SlotGeometry, surface: Surface): Result<VirtualDisplay> =
        runCatching {
            val manager = checkNotNull(displayManager) { "DisplayManager unavailable" }
            var lastFailure: Throwable? = null
            VirtualDisplayFlagsCompat.resolveCandidates().forEach { flags ->
                if ("ALWAYS_UNLOCKED" in flags.names && unlockedPermissionDenied.get()) {
                    NeXtepLog.debug("virtual_display", "Skipping denied ALWAYS_UNLOCKED candidate slot=$index")
                    return@forEach
                }
                try {
                    // Preview displays need no 120/144 Hz mode simply because
                    // the physical display is fast. The main display stays native.
                    val config = VirtualDisplayConfig.Builder(FloatingSlotDisplayIdentity.displayName(index, floatingSlot),
                        geometry.width, geometry.height, geometry.densityDpi)
                        .setSurface(surface)
                        .setFlags(flags.value)
                        .setRequestedRefreshRate(60f)
                        .build()
                    val display = manager.createVirtualDisplay(config)
                    if (display != null) {
                        NeXtepLog.info(
                            "virtual_display",
                            "Allocated slot=$index displayId=${display.display.displayId} " +
                                "profile=${"standard"} " +
                                "flags=${flags.names.joinToString()} " +
                                "state=${display.display.state}",
                        )
                        if ("OWN_FOCUS" !in flags.names) {
                            NeXtepLog.warn(
                                "virtual_display",
                                "Slot=$index lacks OWN_FOCUS; Android may pause virtual " +
                                    "activities when another display owns global focus",
                            )
                        }
                        return@runCatching display
                    }
                    lastFailure = IllegalStateException("DisplayManager returned null flags=${flags.names}")
                } catch (error: Throwable) {
                    if ("ALWAYS_UNLOCKED" in flags.names && error is SecurityException &&
                        (error.message?.contains("ADD_ALWAYS_UNLOCKED_DISPLAY") == true)) {
                        if (unlockedPermissionDenied.compareAndSet(false, true)) {
                            NeXtepLog.warn("virtual_display",
                                "ALWAYS_UNLOCKED permission denied; skipping privileged " +
                                    "candidate for all remaining slots in this process")
                        }
                        lastFailure = error
                        return@forEach
                    }
                    lastFailure = error
                    NeXtepLog.warn(
                        "virtual_display",
                        "Candidate rejected slot=$index flags=${flags.names}",
                        error,
                    )
                }
            }
            throw lastFailure ?: IllegalStateException("No VirtualDisplay flag candidate")
        }.onSuccess { display ->
            NeXtepLog.info(
                "virtual_display",
                "Created slot=$index displayId=${display.display.displayId} geometry=$geometry",
            )
        }.onFailure { error ->
            NeXtepLog.error("virtual_display", "Creation failed slot=$index geometry=$geometry", error)
        }
}
