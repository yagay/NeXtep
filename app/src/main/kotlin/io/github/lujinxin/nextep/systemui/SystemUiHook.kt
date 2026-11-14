package io.github.lujinxin.nextep.systemui

import android.app.Application
import android.app.Instrumentation
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedModule
import android.view.MotionEvent
import android.view.ViewGroup
import io.github.lujinxin.nextep.safety.FeatureGate

object SystemUiHook {
    private data class StartupCandidate(val className: String, val methodName: String)

    private val touchRootCandidates = listOf(
        "com.android.systemui.statusbar.window.StatusBarWindowView",
        "com.android.systemui.shade.NotificationShadeWindowView",
        "com.android.systemui.statusbar.phone.StatusBarWindowView",
        "com.android.systemui.statusbar.phone.PhoneStatusBarView",
    )

    private val startupCandidates = listOf(
        StartupCandidate("com.android.systemui.SystemUIApplication", "onCreate"),
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("workspace_display_transition") { WorkspaceDisplayTransitionHook.install(module, classLoader) }
        HookGuard.run("task_surface_ownership") {
            val organizerClass = classLoader.loadClass("com.android.wm.shell.ShellTaskOrganizer")
            val methods = organizerClass.declaredMethods.filter {
                it.name in setOf("onTaskAppeared", "onTaskInfoChanged", "onTaskVanished") &&
                    it.parameterTypes.firstOrNull() == android.app.ActivityManager.RunningTaskInfo::class.java
            }
            check(methods.map { it.name }.toSet().size == 3) { "Incomplete task ownership callbacks" }
            methods.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                    override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                        val info = chain.args.firstOrNull() as? android.app.ActivityManager.RunningTaskInfo
                        if (info != null) HookGuard.run("task_surface_ownership_update") {
                            if (method.name == "onTaskVanished") {
                                io.github.lujinxin.nextep.framework.TaskSurfaceCompat.forgetTaskSurface(info.taskId)
                            } else {
                                io.github.lujinxin.nextep.framework.TaskSurfaceCompat.observeTaskInfo(
                                    info, if (method.name == "onTaskAppeared") chain.args.getOrNull(1) else null,
                                )
                            }
                        }
                        val result = chain.proceed()
                        // Coalesce callback bursts on the main looper; an idle
                        // workspace no longer needs constant fast Binder polling.
                        if (info != null) HookGuard.run("workspace_task_refresh") { SystemUiRuntime.onTasksChanged() }
                        return result
                    }
                })
            }
        }
        HookGuard.run("task_surface_transaction_guard") {
            val transactionClass = classLoader.loadClass("android.view.SurfaceControl\$Transaction")
            val methods = transactionClass.declaredMethods.filter {
                it.name == "apply" && !java.lang.reflect.Modifier.isNative(it.modifiers)
            }
            check(methods.isNotEmpty()) { "No compatible SurfaceControl.Transaction.apply method" }
            methods.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                    override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                        val transaction = chain.thisObject ?: return chain.proceed()
                        return io.github.lujinxin.nextep.framework.TaskSurfaceCompat
                            .interceptTransactionApply(transaction) { chain.proceed() }
                    }
                })
            }
            NeXtepLog.info("task_surface_transaction_guard", "Installed atomic task fitting at native commit")
        }
        HookGuard.run("slot_retention_display_area") {
            classLoader.loadClass("com.android.wm.shell.RootTaskDisplayAreaOrganizer")
                .declaredMethods.filter {
                    it.name in setOf("onDisplayAreaAppeared", "onDisplayAreaInfoChanged", "onDisplayAreaVanished")
                }.forEach { method ->
                    module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            HookGuard.run("slot_retention_display_area_info") {
                                chain.args.firstOrNull()?.let {
                                    if (method.name == "onDisplayAreaVanished") {
                                        io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat.forgetDisplayArea(it)
                                    } else {
                                        io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat.observeDisplayArea(it)
                                    }
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
        }
        HookGuard.run("system_dialog_layout") {
            Class.forName("android.view.WindowManagerGlobal").declaredMethods
                .filter { it.name in setOf("addView", "updateViewLayout") &&
                    it.parameterTypes.firstOrNull() == android.view.View::class.java }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            val view = chain.args.firstOrNull() as? android.view.View
                            val params = chain.args.getOrNull(1) as? android.view.WindowManager.LayoutParams
                            if (view != null && params != null) {
                                HookGuard.run("system_dialog_layout_apply") {
                                    SystemDialogLayoutController.beforeLayout(view, params)
                                    SystemUiShadeInputController.beforeLayout(view, params)
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
        }
        installFloatingNavigationHooks(module, classLoader)
        SystemUiBrightnessMirrorHook.install(module, classLoader)
        HookGuard.run("systemui_framework_startup") {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java,
            )
            method.isAccessible = true
            module.hook(method).intercept(SystemUiApplicationHooker())
            NeXtepLog.info(
                "systemui_framework_startup",
                "Installed framework Application bootstrap fallback",
            )
        }

        if (FeatureGate.TOP_RIGHT_STATUS_BAR_GESTURE.defaultEnabled) {
            HookGuard.run("systemui_root_touch") {
                val method = ViewGroup::class.java.getDeclaredMethod(
                    "dispatchTouchEvent",
                    MotionEvent::class.java,
                )
                method.isAccessible = true
                module.hook(method).intercept(SystemUiRootTouchHooker())
                NeXtepLog.info("systemui_root_touch", "Installed public root touch observer")
            }
            touchRootCandidates.forEach { className ->
                HookGuard.run("systemui_root_touch_override") {
                    val rootClass = classLoader.loadClass(className)
                    val method = rootClass.getDeclaredMethod(
                        "dispatchTouchEvent",
                        MotionEvent::class.java,
                    )
                    method.isAccessible = true
                    module.hook(method).intercept(SystemUiRootTouchHooker())
                    NeXtepLog.info(
                        "systemui_root_touch_override",
                        "Installed override observer on $className",
                    )
                }
            }
        }

        var installed = false
        for (candidate in startupCandidates) {
            val success = HookGuard.run("systemui_startup_candidate") {
                val startupClass = classLoader.loadClass(candidate.className)
                val method = startupClass.getDeclaredMethod(candidate.methodName)
                method.isAccessible = true
                module.hook(method).intercept(SystemUiStartupHooker())
                NeXtepLog.info(
                    "systemui_startup_candidate",
                    "Installed ${candidate.className}#${candidate.methodName}",
                )
            }
            if (success) {
                installed = true
                break
            }
        }
        if (!installed) {
            NeXtepLog.warn(
                "systemui_startup_candidate",
                "No startup candidate installed; device artifact analysis is required",
            )
        }
    }

    /**
     * Listen only to real SystemUI navigation surfaces. No global touch
     * interception, input pilfering, Activity lifecycle spoofing or polling.
     * Missing OEM entry points are optional; keep every original callback.
     */
    private fun installFloatingNavigationHooks(module: XposedModule, loader: ClassLoader) {
        var installed = 0
        fun hookCandidates(classNames: List<String>, methodNames: List<String>,
                           predicate: (io.github.libxposed.api.XposedInterface.Chain) -> Boolean) {
            classNames.forEach { className ->
                val clazz = runCatching { loader.loadClass(className) }.getOrNull()
                if (clazz == null) {
                    NeXtepLog.info("floating_nav", "NAV_CLASS_MISSING $className")
                    return@forEach
                }
                val methods = clazz.declaredMethods.filter { it.name in methodNames }
                if (methods.isEmpty()) {
                    val available = clazz.declaredMethods
                        .filter { it.name.contains("touch", true) || it.name.contains("event", true) ||
                            it.name.contains("overview", true) || it.name.contains("back", true) }
                        .map { it.name }.distinct().take(12)
                    NeXtepLog.info("floating_nav",
                        "NAV_METHOD_MISSING $className wanted=$methodNames candidates=$available")
                }
                methods.forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                            override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                                val relevant = runCatching { predicate(chain) }.getOrDefault(false)
                                val result = chain.proceed()
                                if (relevant) {
                                    NeXtepLog.info("floating_nav",
                                        "NAV_EVENT_RECEIVED $className#${method.name}")
                                    SystemUiRuntime.onNavigationCompleted(
                                        "$className#${method.name}")
                                }
                                return result
                            }
                        })
                        installed++
                        NeXtepLog.info("floating_nav",
                            "NAV_HOOK_INSTALLED $className#${method.name}")
                    }.onFailure { error ->
                        NeXtepLog.warn("floating_nav",
                            "NAV_HOOK_FAILED $className#${method.name}", error)
                    }
                }
            }
        }

        val navigationViews = listOf(
            "com.android.systemui.navigationbar.NavigationBarView",
            "com.android.systemui.statusbar.phone.NavigationBarView",
            "com.oplus.systemui.navigationbar.NavigationBarView",
        )
        // Restrict the touch fallback to navigation bar view classes, not ViewGroup.
        hookCandidates(navigationViews, listOf("dispatchTouchEvent", "onTouchEvent")) { chain ->
            (chain.args.firstOrNull() as? MotionEvent)?.actionMasked == MotionEvent.ACTION_UP
        }
        val keyButtons = listOf(
            "com.android.systemui.navigationbar.buttons.KeyButtonView",
            "com.android.systemui.statusbar.policy.KeyButtonView",
            "com.oplus.systemui.navigationbar.buttons.KeyButtonView",
        )
        hookCandidates(keyButtons, listOf("sendEvent")) { chain ->
            val action = chain.args.firstOrNull() as? Int
            val target = chain.thisObject
            val code = target?.let {
                runCatching {
                    generateSequence(it.javaClass as Class<*>?) { c -> c.superclass }
                        .mapNotNull { c -> c.declaredFields.firstOrNull { field -> field.name == "mCode" } }
                        .firstOrNull()?.apply { isAccessible = true }?.getInt(it)
                }.getOrNull()
            }
            action == android.view.KeyEvent.ACTION_UP &&
                code in setOf(android.view.KeyEvent.KEYCODE_HOME,
                    android.view.KeyEvent.KEYCODE_BACK,
                    android.view.KeyEvent.KEYCODE_APP_SWITCH)
        }
        hookCandidates(listOf(
            "com.android.systemui.recents.OverviewProxyService",
            "com.android.systemui.shared.recents.OverviewProxyService",
            "com.oplus.systemui.recents.OverviewProxyService",
        ), listOf("onOverviewShown")) { true }
        hookCandidates(listOf(
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
            "com.oplus.systemui.navigationbar.gestural.EdgeBackGestureHandler",
        ), listOf("onBackInvoked")) { true }
        NeXtepLog.info("floating_nav", "NAV_HOOK_SUMMARY installed=$installed")
    }
}
