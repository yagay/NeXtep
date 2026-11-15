package io.github.lujinxin.nextep.systemui

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * Observe the OEM media pipeline and preserve the click PendingIntent of
 * NeXtep's own active slot-media notification.
 *
 * AOSP/ColorOS can convert media notifications into retained/resumption cards.
 * When doing so, MediaDataManager replaces clickIntent with the posting app's
 * launcher PendingIntent (NeXtep's settings Activity), not the playing app.
 *
 * Keep the original creator-owned PendingIntent when a MediaData for NeXtep
 * is bound. Never change other apps' media data, controls, or notifications.
 * A failed reflection is fail-open and must not disrupt SystemUI.
 */
internal object SystemUiMediaPipelineDiagnostics {
    private const val MODULE_PACKAGE = "io.github.lujinxin.nextep"
    private const val BRIDGE_NOTIFICATION_ID = 18022
    @Volatile private var bridgeClickIntent: PendingIntent? = null
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
                            // The data must be fixed BEFORE SystemUI binds the view.
                            // Observing only after proceed is too late for click intents.
                            runCatching {
                                if (method.name == "onNotificationAdded") {
                                    val sbn = chain.args.firstOrNull {
                                        it is StatusBarNotification
                                    } as? StatusBarNotification
                                    if (sbn?.packageName == MODULE_PACKAGE &&
                                        sbn.id == BRIDGE_NOTIFICATION_ID) {
                                        val click = sbn.notification.contentIntent
                                        if (click?.creatorPackage == MODULE_PACKAGE) {
                                            bridgeClickIntent = click
                                            NeXtepLog.info("media_bridge",
                                                "SYSTEMUI_CLICK_SOURCE id=${sbn.id} " +
                                                    "creator=${click.creatorPackage} " +
                                                    "activity=${click.isActivity}")
                                        } else NeXtepLog.warn("media_bridge",
                                            "SYSTEMUI_CLICK_SOURCE_INVALID creator=${click?.creatorPackage}")
                                    }
                                } else if (method.name == "onMediaDataLoaded") {
                                    preserveBridgeClickIntent(chain.args, type.simpleName)
                                }
                            }.onFailure {
                                NeXtepLog.warn("media_bridge",
                                    "SYSTEMUI_CLICK_ROUTE_CHECK_FAILED source=${type.simpleName}", it)
                            }
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
                                        "mediaStyle=${style.contains("MediaStyle")} actions=$actions",
                                )
                            }.onFailure {
                                NeXtepLog.warn("media_bridge", "PIPELINE_OBSERVER_FAILED", it)
                            }
                            return result
                        }
                    })
                    hooks++
                }.onFailure {
                    NeXtepLog.warn("media_bridge",
                        "PIPELINE_HOOK_INSTALL_FAILED ${type.simpleName}", it)
                }
            }
        }
        NeXtepLog.info("media_bridge", "SYSTEMUI_PIPELINE_HOOK_COUNT=$hooks")
    }

    private fun preserveBridgeClickIntent(args: List<Any?>, source: String) {
        val data = args.firstOrNull { value ->
            value != null && value.javaClass.name.endsWith(".MediaData") &&
                value.javaClass.name.startsWith("com.android.systemui.")
        } ?: return
        val cls = data.javaClass
        val pkg = cls.getMethod("getPackageName").invoke(data) as? String ?: return
        if (pkg != MODULE_PACKAGE) return
        val cached = bridgeClickIntent
        val existing = cls.getMethod("getClickIntent").invoke(data) as? PendingIntent
        val resumption = runCatching {
            cls.getMethod("getResumption").invoke(data) as? Boolean
        }.getOrNull() ?: false
        if (cached == null) {
            NeXtepLog.warn("media_bridge",
                "SYSTEMUI_CLICK_ROUTE_MISSING source=$source resumption=$resumption " +
                    "existingCreator=${existing?.creatorPackage} activity=${existing?.isActivity}")
            return
        }
        if (existing == cached) return
        // Kotlin's MediaData.clickIntent is a final instance field. This
        // modification is scoped to the exact module package and is optional.
        val field = cls.getDeclaredField("clickIntent").apply { isAccessible = true }
        field.set(data, cached)
        val rebound = cls.getMethod("getClickIntent").invoke(data) as? PendingIntent
        if (rebound != cached) {
            NeXtepLog.warn("media_bridge",
                "SYSTEMUI_CLICK_ROUTE_NOT_APPLIED source=$source resumption=$resumption")
            return
        }
        NeXtepLog.info("media_bridge",
            "SYSTEMUI_CLICK_ROUTE_FIXED source=$source resumption=$resumption " +
                "previousCreator=${existing?.creatorPackage} previousActivity=${existing?.isActivity} " +
                "newCreator=${cached.creatorPackage} newActivity=${cached.isActivity}")
    }
}
