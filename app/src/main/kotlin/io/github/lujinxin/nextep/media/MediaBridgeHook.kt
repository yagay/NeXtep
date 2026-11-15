package io.github.lujinxin.nextep.media

import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Per-app opt-in observer. Hooks only methods on a scoped, allowlisted process.
 * Does NOT synthesize media titles from video URLs or play synthetic audio.
 * Commands never target AudioTrack directly (that would desync video engines).
 */
object MediaBridgeHook {
    private var installedPackage: String? = null
    private var app: Application? = null
    private var enabled = false
    private val handler = Handler(Looper.getMainLooper())
    private val activeTracks = Collections.newSetFromMap(IdentityHashMap<AudioTrack, Boolean>())
    private var family = ""
    private var player = WeakReference<Any>(null)
    private var playing = false
    private var controllable = false
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastDuration = -1L
    private var lastPosition = -1L
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!enabled || !playing) return
            publish()
            handler.postDelayed(this, 9_000L)
        }
    }
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MediaBridgeContract.ACTION_COMMAND || !enabled || !controllable) return
            val active = player.get() ?: return
            val command = intent.getStringExtra(MediaBridgeContract.EXTRA_COMMAND)
            if (command != MediaBridgeContract.COMMAND_PLAY &&
                command != MediaBridgeContract.COMMAND_PAUSE) return
            runCatching {
                if (active is MediaPlayer) {
                    if (command == MediaBridgeContract.COMMAND_PLAY) active.start()
                    else active.pause()
                } else {
                    val method = active.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 &&
                            it.name == (if (command == MediaBridgeContract.COMMAND_PLAY) "play" else "pause")
                    }
                    if (method != null) method.invoke(active)
                    else active.javaClass.methods.firstOrNull {
                        it.name == "setPlayWhenReady" && it.parameterCount == 1 &&
                            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                    }?.invoke(active, command == MediaBridgeContract.COMMAND_PLAY)
                }
                NeXtepLog.debug("media_bridge", "COMMAND_APPLIED pkg=$installedPackage cmd=$command")
            }.onFailure { NeXtepLog.warn("media_bridge", "COMMAND_TARGET_FAILED", it) }
        }
    }

    fun install(module: XposedModule, classLoader: ClassLoader, packageName: String) {
        if (installedPackage != null) return
        installedPackage = packageName
        val bootstrap = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java)
        module.hook(bootstrap).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val application = chain.args.firstOrNull() as? Application
                if (application != null && app == null) {
                    handler.post { startScoped(application, module, classLoader) }
                }
                return result
            }
        })
    }

    private fun startScoped(application: Application, module: XposedModule,
        classLoader: ClassLoader) {
        if (app != null) return
        app = application
        val pkg = installedPackage ?: return
        enabled = runCatching {
            val request = Bundle().apply { putString(MediaBridgeContract.KEY_PACKAGE, pkg) }
            application.contentResolver.call(MediaBridgeContract.URI,
                MediaBridgeContract.METHOD_ALLOWED, null, request)
                ?.getBoolean(MediaBridgeContract.KEY_ALLOWED, false) == true
        }.onFailure { NeXtepLog.warn("media_bridge", "SCOPE_CHECK_FAILED pkg=$pkg", it) }
            .getOrDefault(false)
        if (!enabled) {
            NeXtepLog.info("media_bridge", "SCOPE_DISABLED pkg=$pkg")
            return
        }
        runCatching {
            application.registerReceiver(commandReceiver,
                IntentFilter(MediaBridgeContract.ACTION_COMMAND),
                MediaBridgeContract.COMMAND_SENDER_PERMISSION,
                handler, Context.RECEIVER_EXPORTED)
        }.onFailure { NeXtepLog.warn("media_bridge", "COMMAND_RECEIVER_FAILED", it) }
        installMediaPlayer(module)
        installAudioTrack(module)
        installExoPlayer(module, classLoader, "androidx.media3.exoplayer.ExoPlayerImpl")
        installExoPlayer(module, classLoader, "com.google.android.exoplayer2.ExoPlayerImpl")
        NeXtepLog.info("media_bridge", "SOURCE_HOOKS_READY pkg=$pkg")
    }

    private fun hookMethod(module: XposedModule, method: Method, id: String,
        after: (Any?, List<Any?>) -> Unit) {
        runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val args = chain.args.toList()
                    val result = chain.proceed()
                    runCatching { after(chain.thisObject, args) }
                        .onFailure { NeXtepLog.warn("media_bridge", "OBSERVER_FAILED $id", it) }
                    return result
                }
            })
        }.onFailure { NeXtepLog.warn("media_bridge", "HOOK_FAILED $id", it) }
    }

    private fun installMediaPlayer(module: XposedModule) {
        MediaPlayer::class.java.declaredMethods
            .filter { it.name in setOf("start", "pause", "stop", "release") &&
                it.parameterCount == 0 }
            .forEach { method ->
                hookMethod(module, method, "MediaPlayer#${method.name}") { instance, _ ->
                    val p = instance as? MediaPlayer ?: return@hookMethod
                    when (method.name) {
                        "start" -> update(p, "mediaplayer", true, true)
                        "pause" -> update(p, "mediaplayer", false, true)
                        "stop", "release" -> end(p, "mediaplayer")
                    }
                }
            }
    }

    private fun installAudioTrack(module: XposedModule) {
        AudioTrack::class.java.declaredMethods
            .filter { it.name in setOf("play", "pause", "stop", "release") &&
                it.parameterCount == 0 }
            .forEach { method ->
                hookMethod(module, method, "AudioTrack#${method.name}") { instance, _ ->
                    val track = instance as? AudioTrack ?: return@hookMethod
                    val media = runCatching {
                        track.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
                    }.getOrDefault(false)
                    if (!media && method.name == "play") return@hookMethod
                    handler.post {
                        if (!enabled) return@post
                        if (method.name == "play") {
                            activeTracks.add(track)
                            updateOnMain(track, "audiotrack", true, false)
                        } else {
                            activeTracks.remove(track)
                            if (activeTracks.isEmpty()) endOnMain(track, "audiotrack")
                        }
                    }
                }
            }
    }

    private fun installExoPlayer(module: XposedModule, loader: ClassLoader, name: String) {
        val cls = runCatching { loader.loadClass(name) }.getOrNull() ?: return
        cls.declaredMethods.filter { method ->
            (method.name == "setPlayWhenReady" && method.parameterCount == 1 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType) ||
                method.name in setOf("stop", "release", "prepare", "setMediaItems",
                    "setMediaItem", "replaceMediaItem")
        }.forEach { method ->
            hookMethod(module, method, "$name#${method.name}") { instance, args ->
                if (instance == null) return@hookMethod
                when (method.name) {
                    "stop", "release" -> end(instance, "exoplayer")
                    "setPlayWhenReady" -> {
                        val requested = args.firstOrNull() as? Boolean ?: false
                        val actual = runCatching {
                            instance.javaClass.getMethod("isPlaying").invoke(instance) as? Boolean
                        }.getOrNull()
                        update(instance, "exoplayer", actual ?: requested, true)
                    }
                    else -> handler.post {
                        if (player.get() === instance && family == "exoplayer") {
                            readMetadata(instance, "exoplayer")
                            publish()
                        }
                    }
                }
            }
        }
    }

    private fun update(instance: Any, source: String, isPlaying: Boolean, canControl: Boolean) {
        handler.post { updateOnMain(instance, source, isPlaying, canControl) }
    }

    private fun updateOnMain(instance: Any, source: String, isPlaying: Boolean,
        canControl: Boolean) {
        if (!enabled) return
        fun rank(name: String) = when (name) {
            "exoplayer" -> 3
            "mediaplayer" -> 2
            else -> 1
        }
        if (source != family && !isPlaying) return
        if (isPlaying && playing && rank(source) < rank(family)) return
        val changed = source != family || playing != isPlaying || player.get() !== instance
        family = source
        player = WeakReference(instance)
        playing = isPlaying
        controllable = canControl
        if (changed) {
            lastTitle = null
            lastArtist = null
            lastDuration = -1L
            lastPosition = -1L
        }
        readMetadata(instance, source)
        publish()
        handler.removeCallbacks(heartbeat)
        if (playing) handler.postDelayed(heartbeat, 9_000L)
    }

    private fun end(instance: Any, source: String) {
        handler.post { endOnMain(instance, source) }
    }

    private fun endOnMain(instance: Any, source: String) {
        if (!enabled || source != family || player.get() !== instance) return
        playing = false
        handler.removeCallbacks(heartbeat)
        send(MediaBridgeContract.STATE_STOPPED)
        family = ""
        player.clear()
        controllable = false
    }

    private fun readMetadata(instance: Any, source: String) {
        if (source == "audiotrack") return
        if (instance is MediaPlayer) {
            lastPosition = runCatching { instance.currentPosition.toLong() }.getOrDefault(-1L)
            lastDuration = runCatching { instance.duration.toLong() }.getOrDefault(-1L)
            return
        }
        fun invokeLong(name: String): Long = runCatching {
            (instance.javaClass.getMethod(name).invoke(instance) as? Number)?.toLong() ?: -1L
        }.getOrDefault(-1L)
        lastPosition = invokeLong("getCurrentPosition")
        lastDuration = invokeLong("getDuration")
        val metadata = runCatching {
            instance.javaClass.getMethod("getMediaMetadata").invoke(instance)
        }.getOrNull() ?: return
        fun field(name: String): String? = runCatching {
            metadata.javaClass.getField(name).get(metadata)?.toString()?.trim()
                ?.take(180)?.takeIf(String::isNotBlank)
        }.getOrNull()
        lastTitle = field("title")
        lastArtist = field("artist")
    }

    private fun publish() {
        if (!enabled || family.isEmpty()) return
        // Refresh the measured position where the player exposes one.
        player.get()?.let { readMetadata(it, family) }
        send(if (playing) MediaBridgeContract.STATE_PLAYING
            else MediaBridgeContract.STATE_PAUSED)
    }

    private fun send(state: String) {
        val context = app ?: return
        val pkg = installedPackage ?: return
        val bundle = Bundle().apply {
            putString(MediaBridgeContract.KEY_PACKAGE, pkg)
            putString(MediaBridgeContract.KEY_STATE, state)
            putString(MediaBridgeContract.KEY_SOURCE, family)
            putString(MediaBridgeContract.KEY_TITLE, lastTitle)
            putString(MediaBridgeContract.KEY_ARTIST, lastArtist)
            putLong(MediaBridgeContract.KEY_DURATION, lastDuration)
            putLong(MediaBridgeContract.KEY_POSITION, lastPosition)
            putBoolean(MediaBridgeContract.KEY_CONTROLLABLE, controllable)
        }
        // Main thread IPC is bounded by ContentProvider's tiny validation only;
        // no networking, screenshots, work loops or heavy MediaSession code here.
        runCatching {
            context.contentResolver.call(MediaBridgeContract.URI,
                MediaBridgeContract.METHOD_OBSERVE, null, bundle)
        }.onFailure { NeXtepLog.warn("media_bridge", "SOURCE_SEND_FAILED", it) }
    }
}
