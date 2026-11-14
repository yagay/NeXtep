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
 * Floating-dock stable mode ONLY. Inject original touch gestures into the
 * already running application on its existing virtual display. No task
 * migration or Activity relaunch occurs.
 *
 * Runs inside privileged LSPosed-injected SystemUI; if an OEM denies input
 * injection the gesture is safely rejected, leaving the virtual app alive.
 */
internal class FloatingVirtualDisplayInput(context: Context) {
    private data class Injector(val receiver: Any, val method: Method)
    private val appContext = context.applicationContext ?: context
    private val injector: Injector? by lazy { findInjector() }
    private val setMotionDisplay: Method? by lazy { findDisplaySetter(MotionEvent::class.java) }
    private val setKeyDisplay: Method? by lazy { findDisplaySetter(KeyEvent::class.java) }
    private var failureCount = 0
    private var successLogged = false

    fun sendTouch(
        original: MotionEvent,
        texture: TextureView,
        geometry: SlotGeometry,
        displayId: Int,
    ): Boolean {
        if (displayId <= 0 || texture.width <= 0 || texture.height <= 0 ||
            geometry.width <= 0 || geometry.height <= 0) return false

        // TextureView may letterbox/scale the virtual content. Invert the
        // actual renderer matrix instead of assuming direct screen scaling.
        val inverse = Matrix()
        if (!texture.getTransform(Matrix()).invert(inverse)) return false
        val toCanvas = Matrix(inverse).apply {
            postScale(
                geometry.width.toFloat() / texture.width,
                geometry.height.toFloat() / texture.height,
            )
        }

        if (original.actionMasked == MotionEvent.ACTION_DOWN) {
            val point = floatArrayOf(original.x, original.y)
            toCanvas.mapPoints(point)
            if (point[0] < 0f || point[1] < 0f ||
                point[0] >= geometry.width || point[1] >= geometry.height) return false
        }
        val copy = MotionEvent.obtain(original)
        return try {
            copy.transform(toCanvas) // Multi-pointer positions are preserved.
            val setter = setMotionDisplay ?: error("MotionEvent displayId setter unavailable")
            setter.invoke(copy, displayId)
            copy.source = InputDevice.SOURCE_TOUCHSCREEN
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
        val now = android.os.SystemClock.uptimeMillis()
        var accepted = true
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0)
            try {
                val setter = setKeyDisplay ?: error("KeyEvent displayId setter unavailable")
                setter.invoke(event, displayId)
                accepted = inject(event) && accepted
            } catch (error: Throwable) {
                reportFailure("back display=$displayId", error)
                accepted = false
            }
        }
        return accepted
    }

    private fun inject(event: InputEvent): Boolean {
        val found = injector ?: error("Privileged input dispatcher unavailable")
        val allowed = found.method.invoke(found.receiver, event, 0) as? Boolean == true
        if (!allowed) reportFailure("SystemUI injection rejected by InputDispatcher", null)
        else if (!successLogged) {
            successLogged = true
            NeXtepLog.info("floating_input",
                "STABLE_INPUT_READY via=${found.receiver.javaClass.simpleName}")
        }
        return allowed
    }

    private fun findInjector(): Injector? {
        val receivers = buildList {
            appContext.getSystemService(InputManager::class.java)?.let(::add)
            runCatching {
                val global = Class.forName("android.hardware.input.InputManagerGlobal")
                global.getDeclaredMethod("getInstance").apply {
                    isAccessible = true
                }.invoke(null)
            }.getOrNull()?.let(::add)
        }
        for (receiver in receivers) {
            val signature = (receiver.javaClass.methods.asSequence() +
                receiver.javaClass.declaredMethods.asSequence())
                .firstOrNull {
                    it.name == "injectInputEvent" && it.parameterTypes.size == 2 &&
                        InputEvent::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: continue
            val method = runCatching { signature.apply { isAccessible = true } }.getOrNull()
                ?: continue
            return Injector(receiver, method)
        }
        reportFailure("No compatible InputManager.injectInputEvent(InputEvent,Int)", null)
        return null
    }

    private fun findDisplaySetter(type: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val klass = current
            val method = runCatching {
                klass.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            }.getOrNull()
            if (method != null) return method
            current = klass.superclass
        }
        return null
    }

    private fun reportFailure(where: String, error: Throwable?) {
        failureCount++
        if (failureCount == 1 || failureCount == 15) {
            NeXtepLog.warn("floating_input",
                "STABLE_INPUT_FAILED where=$where count=$failureCount " +
                    "permission=${appContext.checkSelfPermission("android.permission.INJECT_EVENTS")}",
                error,
            )
        }
    }
}
