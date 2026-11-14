package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Matrix
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import io.github.lujinxin.nextep.display.SlotGeometry
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.reflect.Method

/**
 * User touch -> the SAME live VirtualDisplay the selected floating task lives
 * on. This does not reparent, relaunch, or expose the task on Display 0.
 *
 * This runs only in LSPosed-injected SystemUI. The Android input service
 * enforces INJECT_EVENTS; if ColorOS denies injection, it fails closed with
 * diagnostics, and the original task/session remains untouched.
 */
internal class FloatingVirtualDisplayInput(context: Context) {
    private data class Injector(val receiver: Any, val method: Method)
    private val appContext = context.applicationContext ?: context
    private val injector by lazy { findInjector() }
    private val setMotionDisplay by lazy {
        runCatching {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }
    private val setKeyDisplay by lazy {
        runCatching {
            KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }
    private val setMotionSource by lazy {
        runCatching {
            MotionEvent::class.java.getMethod("setSource", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }
    private var failures = 0
    private var readyLogged = false

    fun sendTouch(
        event: MotionEvent,
        texture: TextureView,
        geometry: SlotGeometry,
        displayId: Int,
    ): Boolean {
        if (displayId <= 0 || texture.width <= 0 || texture.height <= 0 ||
            geometry.width <= 0 || geometry.height <= 0) return false
        val transform = texture.getTransform(Matrix())
        val inverse = Matrix()
        if (!transform.invert(inverse)) return false

        // MotionEvents delivered to the outer TaskSwitcherView are in the
        // TextureView's local pixels; undo its letterboxing/zoom transform,
        // then scale to the VirtualDisplay's fixed backing canvas.
        val touchToDisplay = Matrix(inverse).apply {
            postScale(
                geometry.width.toFloat() / texture.width,
                geometry.height.toFloat() / texture.height,
            )
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val sample = floatArrayOf(event.x, event.y)
            touchToDisplay.mapPoints(sample)
            if (sample[0] < 0f || sample[1] < 0f ||
                sample[0] >= geometry.width || sample[1] >= geometry.height) {
                return false // Black bars are not interactive application content.
            }
        }

        val copy = MotionEvent.obtain(event)
        return try {
            copy.transform(touchToDisplay) // Preserves all pointers and action indices.
            val setDisplay = setMotionDisplay
                ?: error("MotionEvent#setDisplayId unavailable")
            setDisplay.invoke(copy, displayId)
            setMotionSource?.invoke(copy, InputDevice.SOURCE_TOUCHSCREEN)
            inject(copy)
        } catch (error: Throwable) {
            reportFailure("touch display=$displayId", error)
            false
        } finally {
            copy.recycle()
        }
    }

    fun sendBack(displayId: Int): Boolean {
        if (displayId <= 0) return false
        var success = true
        val now = android.os.SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val event = KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0)
            try {
                val setDisplay = setKeyDisplay ?: error("KeyEvent#setDisplayId unavailable")
                setDisplay.invoke(event, displayId)
                success = inject(event) && success
            } catch (error: Throwable) {
                reportFailure("back display=$displayId", error)
                success = false
            }
        }
        return success
    }

    private fun inject(event: InputEvent): Boolean {
        val destination = injector ?: error("InputManager injection unavailable")
        val accepted = destination.method.invoke(destination.receiver, event, 0) as? Boolean == true
        if (!accepted) {
            reportFailure("event injection rejected by input dispatcher", null)
        } else if (!readyLogged) {
            readyLogged = true
            NeXtepLog.info("floating_input",
                "VIRTUAL_INPUT_READY targetDisplay=${event.displayId} " +
                    "receiver=${destination.receiver.javaClass.simpleName}")
        }
        return accepted
    }

    private fun findInjector(): Injector? {
        val receivers = buildList {
            appContext.getSystemService(InputManager::class.java)?.let(::add)
            runCatching {
                val type = Class.forName("android.hardware.input.InputManagerGlobal")
                type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            }.getOrNull()?.let(::add)
        }
        for (receiver in receivers) {
            val method = receiver.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "injectInputEvent" &&
                    candidate.parameterTypes.size == 2 &&
                    InputEvent::class.java.isAssignableFrom(candidate.parameterTypes[0]) &&
                    candidate.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: continue
            return runCatching {
                method.isAccessible = true
                Injector(receiver, method)
            }.getOrNull()
        }
        return null
    }

    private fun reportFailure(operation: String, error: Throwable?) {
        // Don't spam logcat on every animation frame if ColorOS blocks the
        // privileged input path. The first failure is detailed.
        failures++
        if (failures == 1 || failures == 20) {
            NeXtepLog.warn("floating_input",
                "VIRTUAL_INPUT_FAILED operation=$operation count=$failures " +
                    "systemUiPermission=${appContext.checkSelfPermission(
                        "android.permission.INJECT_EVENTS"
                    )}", error)
        }
    }
}
