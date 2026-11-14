package io.github.lujinxin.nextep.systemserver

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Passive system_server instrumentation for NeXtep's own virtual displays.
 *
 * The AOSP display manager sends power requests per display group. Inspect
 * the group before recording a request, so the physical display, other
 * applications and unrelated virtual displays are never traced or changed.
 * NEVER change the requested state, Binder identity, or return value.
 */
internal object NeXtepDisplayPowerDiagnostics {
    private const val TAG = "NeXtepDisplayPower"
    private val recentlyLogged = ConcurrentHashMap<String, Long>()

    fun install(module: XposedModule, loader: ClassLoader) {
        // ColorOS 17 declares this entry point on the LocalService inner class.
        val service = loader.loadClass("com.android.server.display.DisplayManagerService\$LocalService")
        val candidates = service.declaredMethods.filter { method ->
            method.name == "requestPowerState" &&
                method.parameterCount == 3 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2] == Boolean::class.javaPrimitiveType
        }
        if (candidates.isEmpty()) {
            val available = service.declaredMethods.filter {
                it.name.contains("Power", ignoreCase = true) ||
                    it.name.contains("DisplayState", ignoreCase = true)
            }.take(16).joinToString { it.name + "/" + it.parameterCount }
            Log.w(TAG, "No compatible LocalService requestPowerState hook on this OS; candidates=$available")
            Log.w("NeXtep", "Display power tracing unsupported; no system state changed")
            return
        }
        candidates.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // Resolution failures must not block the real display-power request.
                    runCatching {
                        val localService = chain.thisObject ?: return@runCatching
                        val serviceInstance = getField(localService, "this\$0") ?: return@runCatching
                        val groupId = chain.args[0] as? Int ?: return@runCatching
                        val mapper = getField(serviceInstance, "mLogicalDisplayMapper")
                            ?: return@runCatching
                        val group = call(mapper, "getDisplayGroupLocked", groupId)
                            ?: return@runCatching
                        val size = call(group, "getSizeLocked") as? Int ?: return@runCatching
                        if (size !in 1..32) return@runCatching
                        val memberSlots = buildList {
                            for (i in 0 until size) {
                                val displayId = call(group, "getIdLocked", i) as? Int ?: continue
                                val display = call(mapper, "getDisplayLocked", displayId) ?: continue
                                val device = call(display, "getPrimaryDisplayDeviceLocked") ?: continue
                                val info = call(device, "getDisplayDeviceInfoLocked") ?: continue
                                val name = getField(info, "name") as? String ?: continue
                                if (name.startsWith("NeXtep-slot-")) {
                                    add("$displayId:$name")
                                }
                            }
                        }
                        if (memberSlots.isEmpty()) return@runCatching
                        val request = chain.args[1]
                        val policy = request?.let { getField(it, "policy") }
                        val key = "$groupId:$policy:${memberSlots.joinToString()}"
                        val now = SystemClock.elapsedRealtime()
                        val last = recentlyLogged[key]
                        if (last != null && now - last < 2_000L) return@runCatching
                        recentlyLogged[key] = now
                        if (recentlyLogged.size > 100) recentlyLogged.clear()
                        Log.i(TAG, "power_request group=$groupId policy=$policy " +
                            "slots=${memberSlots.joinToString()} " +
                            "waitForNegativeProximity=${chain.args[2]}")
                    }.onFailure { error ->
                        val key = "reflection_error"
                        val now = SystemClock.elapsedRealtime()
                        val last = recentlyLogged[key]
                        if (last == null || now - last > 60_000L) {
                            recentlyLogged[key] = now
                            Log.w(TAG, "Passive power tracing unavailable: " +
                                error.javaClass.simpleName + ": " + error.message)
                        }
                    }
                    return chain.proceed()
                }
            })
        }
        Log.i(TAG, "Installed passive group power tracing for NeXtep slots")
        Log.i("NeXtep", "Display power tracing installed methods=${candidates.size}")
    }

    private fun getField(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz -> runCatching { clazz.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String, vararg args: Any): Any? {
        val method: Method = generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: return null
        method.isAccessible = true
        return method.invoke(target, *args)
    }
}
