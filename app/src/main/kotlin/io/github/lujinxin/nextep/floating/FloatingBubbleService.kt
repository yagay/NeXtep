package io.github.lujinxin.nextep.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.MainActivity
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.FloatingDockSafeArea
import kotlin.math.abs
import kotlin.math.roundToInt

/** A real user-authorized app overlay; SystemUI continues to own virtual displays and tasks. */
class FloatingBubbleService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val prefs by lazy { getSharedPreferences("floating_bubble_position", MODE_PRIVATE) }
    private val size by lazy { dp(52) }
    private var bubble: FrameLayout? = null
    private var layout: WindowManager.LayoutParams? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragged = false
    private val wakeRetry = Runnable {
        reconcileBubble(FloatingBubbleWakePolicy.Event.SCREEN_ON, "wake_retry")
    }
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(wakeRetry)
                    reconcileBubble(FloatingBubbleWakePolicy.Event.SCREEN_OFF, "screen_off")
                }
                Intent.ACTION_SCREEN_ON -> {
                    // SCREEN_ON precedes USER_PRESENT. Keep the icon available
                    // on the awake lock screen when the OEM permits overlays.
                    reconcileBubble(FloatingBubbleWakePolicy.Event.SCREEN_ON, "screen_on")
                    handler.removeCallbacks(wakeRetry)
                    handler.postDelayed(wakeRetry, 350L)
                    handler.postDelayed(wakeRetry, 1_200L)
                }
                Intent.ACTION_USER_PRESENT -> {
                    reconcileBubble(FloatingBubbleWakePolicy.Event.USER_PRESENT, "user_present")
                    // Some OEMs dispatch USER_PRESENT before PowerManager
                    // reports interactive. Do not discard the pending wake
                    // retries or the icon may stay invisible until next wake.
                    handler.removeCallbacks(wakeRetry)
                    handler.postDelayed(wakeRetry, 350L)
                    handler.postDelayed(wakeRetry, 1_200L)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SettingsRepository(this).setFloatingBubbleEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SettingsRepository(this).topBarSettings().floatingBubbleEnabled ||
            !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return runCatching {
            ensureForeground()
            reconcileBubble(FloatingBubbleWakePolicy.Event.SERVICE_START, "service_start")
            START_STICKY
        }.getOrElse { error ->
            NeXtepLog.warn("floating_workspace", "Service startup failed", error)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun ensureForeground() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.floating_service_title),
                NotificationManager.IMPORTANCE_LOW),
        )
        val configure = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nextep_tile)
            .setContentTitle(getString(R.string.floating_service_title))
            .setContentText(getString(R.string.floating_service_text))
            .setContentIntent(configure)
            .setOngoing(true)
            .addAction(0, getString(R.string.floating_service_stop), stop)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun reconcileBubble(
        event: FloatingBubbleWakePolicy.Event,
        reason: String,
    ) {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val enabled = SettingsRepository(this).topBarSettings().floatingBubbleEnabled
        val permitted = Settings.canDrawOverlays(this)
        val action = FloatingBubbleWakePolicy.decide(event, interactive, enabled, permitted)
        when (action) {
            FloatingBubbleWakePolicy.Action.REMOVE -> removeBubble()
            FloatingBubbleWakePolicy.Action.HIDE -> {
                // Do not detach the WM surface every time the phone sleeps.
                // That used to make the icon disappear until USER_PRESENT.
                bubble?.visibility = View.INVISIBLE
            }
            FloatingBubbleWakePolicy.Action.WAIT_FOR_SCREEN -> Unit
            FloatingBubbleWakePolicy.Action.SHOW -> {
                val existing = bubble
                if (existing != null && !existing.isAttachedToWindow) removeBubble()
                if (bubble == null) showBubble()
                val view = bubble
                val position = layout
                if (view != null && position != null) {
                    val safe = bubbleSafeRect()
                    position.x = position.x.coerceIn(
                        safe.left, (safe.right - size).coerceAtLeast(safe.left))
                    position.y = position.y.coerceIn(
                        safe.top, (safe.bottom - size).coerceAtLeast(safe.top))
                    view.visibility = View.VISIBLE
                    updateBubble()
                }
            }
        }
        NeXtepLog.info("floating_workspace",
            "Bubble lifecycle event=$event action=$action reason=$reason " +
                "interactive=$interactive keyguard=${isKeyguardVisible()} " +
                "attached=${bubble?.isAttachedToWindow == true}")
    }

    private fun isKeyguardVisible(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        val safe = bubbleSafeRect()
        val height = (windowManager?.maximumWindowMetrics?.bounds?.height()
            ?: resources.displayMetrics.heightPixels).coerceAtLeast(size + dp(16))
        val side = prefs.getInt(KEY_SIDE, 1).coerceIn(0, 1)
        val fraction = prefs.getFloat(KEY_Y, 0.42f).coerceIn(0.08f, 0.88f)
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (side == 0) safe.left else (safe.right - size).coerceAtLeast(safe.left)
            y = (height * fraction).roundToInt()
                .coerceIn(safe.top, (safe.bottom - size).coerceAtLeast(safe.top))
            title = "NeXtepFloatingWorkspace"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nextep_tile)
            setColorFilter(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }
        val view = FrameLayout(this).apply {
            contentDescription = getString(R.string.floating_bubble_open)
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(244, 27, 57, 68))
                setStroke(dp(1), Color.rgb(113, 213, 224))
            }
            elevation = dp(6).toFloat()
            addView(icon, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { toggleWorkspace() }
            setOnTouchListener { _, event -> dragOrClick(event) }
        }
        runCatching {
            checkNotNull(windowManager).addView(view, params)
            bubble = view
            layout = params
            // Re-anchor an already expanded SystemUI dock after rotation or
            // a service restart, without accidentally toggling its state.
            FloatingPanelCommand.updateAnchor(this, params.x, params.y, size)
            NeXtepLog.info("floating_workspace", "App-owned overlay visible")
        }.onFailure { error ->
            bubble = null
            layout = null
            NeXtepLog.warn("floating_workspace", "Unable to add overlay window", error)
            Toast.makeText(this, R.string.floating_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleWorkspace() {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (!FloatingBubbleWakePolicy.mayOpenTaskDock(isKeyguardVisible(), interactive)) {
            // Only the icon is allowed while the phone is locked. No tasks,
            // app names or previews are exposed across the keyguard.
            NeXtepLog.info("floating_workspace",
                "Ignored dock opening on locked or non-interactive display")
            return
        }
        val position = layout ?: return
        FloatingPanelCommand.toggle(this, position.x, position.y, size) { result ->
            if (result is FloatingPanelCommand.Result.Failed) {
                NeXtepLog.warn("floating_workspace", "Floating panel toggle rejected: ${result.reason}")
                // This is an ordered-broadcast failure from another process.
                // Preserve its original stack in the app-side diagnostic ring
                // even when SystemUI log snapshot is unavailable or stale.
                result.remoteTrace?.lineSequence()?.take(18)?.forEachIndexed { index, line ->
                    NeXtepLog.warn("floating_remote_trace", "SystemUI[$index]: $line")
                }
                handler.post {
                    Toast.makeText(this, getString(R.string.floating_module_not_ready, result.reason),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dragOrClick(event: MotionEvent): Boolean {
        val params = layout ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                downX = params.x; downY = params.y; dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragged && abs(dx) < dp(8) && abs(dy) < dp(8)) return true
                dragged = true
                val safe = bubbleSafeRect()
                params.x = (downX + dx.roundToInt()).coerceIn(
                    safe.left, (safe.right - size).coerceAtLeast(safe.left))
                params.y = (downY + dy.roundToInt()).coerceIn(
                    safe.top, (safe.bottom - size).coerceAtLeast(safe.top))
                updateBubble()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) bubble?.performClick()
                else {
                    val safe = bubbleSafeRect()
                    val right = params.x + size / 2 >= (safe.left + safe.right) / 2
                    params.x = if (right) (safe.right - size).coerceAtLeast(safe.left) else safe.left
                    updateBubble()
                    prefs.edit()
                        .putInt(KEY_SIDE, if (right) 1 else 0)
                        .putFloat(KEY_Y, params.y.toFloat() /
                            (windowManager?.maximumWindowMetrics?.bounds?.height()
                                ?: resources.displayMetrics.heightPixels).coerceAtLeast(1))
                        .apply()
                    // Move only the dock viewport: preserve all four live
                    // displays and task assignments while the icon moves.
                    FloatingPanelCommand.updateAnchor(this, params.x, params.y, size)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun updateBubble() {
        val view = bubble ?: return
        val params = layout ?: return
        runCatching { windowManager?.updateViewLayout(view, params) }
            .onFailure { NeXtepLog.warn("floating_workspace", "Unable to move bubble", it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeBubble()
        reconcileBubble(FloatingBubbleWakePolicy.Event.CONFIGURATION_CHANGED,
            "configuration_changed")
    }

    private fun removeBubble() {
        val view = bubble ?: return
        bubble = null
        layout = null
        runCatching { windowManager?.removeViewImmediate(view) }
            .onFailure { NeXtepLog.warn("floating_workspace", "Unable to remove bubble", it) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(wakeRetry)
        removeBubble()
        runCatching { unregisterReceiver(screenEvents) }
        super.onDestroy()
    }

    private fun bubbleSafeRect(): Rect {
        val metrics = resources.displayMetrics
        val physical = runCatching { windowManager?.maximumWindowMetrics?.bounds }.getOrNull()
        val width = (physical?.width() ?: metrics.widthPixels).coerceAtLeast(size + dp(16))
        val height = (physical?.height() ?: metrics.heightPixels).coerceAtLeast(size + dp(16))
        val insets = FloatingDockSafeArea.measure(this, windowManager)
        val left = (insets.left + dp(8)).coerceIn(0, width - 1)
        val top = (insets.top + dp(8)).coerceIn(0, height - 1)
        return Rect(left, top,
            (width - insets.right - dp(8)).coerceIn(left + 1, width),
            (height - insets.bottom - dp(8)).coerceIn(top + 1, height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val CHANNEL_ID = "nextep_floating_workspace"
        const val NOTIFICATION_ID = 41015
        const val ACTION_STOP = "io.github.lujinxin.nextep.action.STOP_FLOATING_BUBBLE"
        const val KEY_SIDE = "side"
        const val KEY_Y = "vertical"
    }
}
