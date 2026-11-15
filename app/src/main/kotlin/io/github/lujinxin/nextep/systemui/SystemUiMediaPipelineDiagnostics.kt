package io.github.lujinxin.nextep.systemui

import android.app.Notification
import android.service.notification.StatusBarNotification
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * Optional, read-only observation of SystemUI's existing media pipeline.
 * No media data is modified, no notifications are inserted, and failures
 * never block SystemUI startup or other media notifications.
 */
internal object SystemUiMediaPipelineDiagnostics {
    private const val MODULE_PACKAGE = "io.github.lujinxin.nextep"
    private val candidates = listOf(
        "com.android.systemui.media.controls.domain.pipeline.MediaDataManager",
        "com.android.systemui.media.controls.pipeline.MediaDataManager",
        "com.android.systemui.media.controls.domain.pipeline.MediaDataFilter",
        "com.android.systemui.media.controls.pipeline.MediaDataFilter",
        "com.android.systemui.media.controls.ui.controller.MediaCarouselController",
        "com.android.systemui.media.controls.ui.view.MediaCarouselController",
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        var hooks = 0
        for (name in candidates) {
            val type = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            type.declaredMethods.filter { method ->
                method.name == "onNotificationAdded" ||
                    method.name == "onMediaDataLoaded"
            }.forEach { method ->
                runCatching {
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            runCatching {
                                val sbn = chain.args.firstOrNull {
                                    it is StatusBarNotification
                                } as? StatusBarNotification
                                val key = chain.args.firstOrNull { it is String } as? String
                                if (sbn?.packageName != MODULE_PACKAGE &&
                                    key?.contains(MODULE_PACKAGE) != true) return@runCatching
                                val style = sbn?.notification?.extras?.getString(
                                    Notification.EXTRA_TEMPLATE).orEmpty()
                                val actions = sbn?.notification?.actions?.size ?: -1
                                val stage = if (method.name == "onNotificationAdded")
                                    "NOTIFICATION_ADDED" else "MEDIA_DATA_LOADED"
                                NeXtepLog.info(
                                    "media_bridge",
                                    "SYSTEMUI_PIPELINE stage=$stage source=${type.simpleName} " +
                                        "mediaStyle=${style.contains("MediaStyle")} " +
                                        "actions=$actions",
                                )
                            }.onFailure {
                                NeXtepLog.warn("media_bridge", "PIPELINE_OBSERVER_FAILED", it)
                            }
                            return result
                        }
                    })
                    hooks++
                }.onFailure {
                    NeXtepLog.warn("media_bridge", "PIPELINE_HOOK_INSTALL_FAILED ${type.simpleName}", it)
                }
            }
        }
        NeXtepLog.info("media_bridge", "SYSTEMUI_PIPELINE_HOOK_COUNT=$hooks")
    }
}
