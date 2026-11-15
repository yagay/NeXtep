package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.media.MediaBridgeContract
import java.util.concurrent.Executors

/**
 * SystemUI-only observer for apps occupying NeXtep's own four VirtualDisplays.
 * No LSPosed hooks are installed in any player process.
 *
 * AudioPlaybackConfiguration identifies active media UID where the OEM permits
 * SystemUI to read it. Unknown/redacted UIDs do not become synthetic playback.
 */
internal class DockMediaAudioObserver(
    private val context: Context,
    private val occupiedSlots: () -> List<SlotApp>,
) {
    data class SlotApp(val packageName: String, val uid: Int)
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NeXtep-DockMedia").apply { isDaemon = true }
    }
    private var started = false
    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            val apps = runCatching { occupiedSlots() }.onFailure {
                NeXtepLog.warn("media_bridge", "DOCK_SLOT_QUERY_FAILED", it)
            }.getOrDefault(emptyList()).distinctBy { it.packageName to it.uid }
            io.execute { sendSnapshot(apps) }
            main.postDelayed(this, UPDATE_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        main.post(tick)
        NeXtepLog.info("media_bridge", "DOCK_ONLY_OBSERVER_STARTED")
    }

    fun refreshSoon() {
        if (!started) return
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun sendSnapshot(apps: List<SlotApp>) {
        val mediaUids = mutableSetOf<Int>()
        var uidReadable = false
        if (apps.isNotEmpty()) {
            runCatching {
                audio?.activePlaybackConfigurations.orEmpty().forEach { config ->
                    if (!config.isActive) return@forEach
                    val usage = config.audioAttributes.usage
                    if (usage != AudioAttributes.USAGE_MEDIA &&
                        usage != AudioAttributes.USAGE_GAME) return@forEach
                    // getClientUid is a SystemApi / hidden API on several SDKs.
                    // Query from SystemUI only, and never infer a UID from silence.
                    val uid = runCatching {
                        config.javaClass.getMethod("getClientUid").invoke(config) as? Int
                    }.getOrNull()
                    if (uid != null && uid >= 10_000) {
                        uidReadable = true
                        mediaUids.add(uid)
                    }
                }
            }.onFailure {
                NeXtepLog.warn("media_bridge", "AUDIO_CONFIG_QUERY_FAILED", it)
            }
        }
        val packages = apps.map { it.packageName }.distinct().take(4)
        val playing = io.github.lujinxin.nextep.media.DockMediaMatchPolicy
            .audiblePackages(apps.map { it.packageName to it.uid }, mediaUids)
            .toList()
        val payload = Bundle().apply {
            putStringArrayList(MediaBridgeContract.KEY_DOCK_PACKAGES, ArrayList(packages))
            putStringArrayList(MediaBridgeContract.KEY_PLAYING_PACKAGES, ArrayList(playing))
            putBoolean(MediaBridgeContract.KEY_UID_READABLE, uidReadable)
        }
        runCatching {
            context.contentResolver.call(MediaBridgeContract.URI,
                MediaBridgeContract.METHOD_DOCK_SNAPSHOT, null, payload)
        }.onFailure {
            NeXtepLog.warn("media_bridge", "DOCK_SNAPSHOT_SEND_FAILED", it)
        }
        // Log only state transitions and diagnostics, never app content or URLs.
        if (apps.isNotEmpty()) {
            NeXtepLog.debug("media_bridge",
                "DOCK_SCAN occupied=${packages.size} audioMatches=${playing.size} " +
                    "uidReadable=$uidReadable")
        }
    }

    private companion object { const val UPDATE_MS = 7_000L }
}
