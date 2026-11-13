package io.github.lujinxin.nextep.config

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/** Uses Android's persisted app language; host resources are never changed. */
object AppLanguage {
    private const val MODULE_PACKAGE = "io.github.lujinxin.nextep"
    val languageTags = listOf("", "zh-Hans", "zh-Hant", "en", "ja", "ko")

    @Volatile private var hostLanguageTag = ""
    private var moduleContext: Context? = null
    private var localizedContext: Context? = null
    private var cachedConfiguration: Configuration? = null

    fun selectedTag(context: Context): String {
        val locales = checkNotNull(context.getSystemService(LocaleManager::class.java)).applicationLocales
        return if (locales.isEmpty) "" else locales[0]?.let(::supportedTag).orEmpty()
    }

    fun select(context: Context, languageTag: String) {
        require(languageTag in languageTags)
        checkNotNull(context.getSystemService(LocaleManager::class.java)).applicationLocales =
            LocaleList.forLanguageTags(languageTag)
        notifyChanged(context)
    }

    fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(WorkspaceConfigContract.ACTION_CHANGED)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).setPackage("com.android.systemui"))
    }

    fun updateHostLanguage(languageTag: String) {
        hostLanguageTag = languageTag.takeIf { it in languageTags }.orEmpty()
    }

    fun hostTag(): String = hostLanguageTag

    fun locale(context: Context): Locale {
        val explicit = if (context.packageName == MODULE_PACKAGE) selectedTag(context) else hostLanguageTag
        if (explicit.isNotEmpty()) return Locale.forLanguageTag(explicit)
        val locales = context.resources.configuration.locales
        for (index in 0 until locales.size()) {
            locales[index]?.let(::supportedTag)?.let { return Locale.forLanguageTag(it) }
        }
        return Locale.ENGLISH
    }

    fun string(context: Context, @StringRes resource: Int, vararg arguments: Any): String {
        // Host-injected contexts (especially OEM SystemUI after an app update)
        // can create a configuration context whose ContextImpl.mResources is
        // unexpectedly null. Calling Context.getString() then throws an NPE
        // and aborts the whole floating workspace before any slots exist.
        // Resolve Resources explicitly and fall back to PackageManager's APK
        // resources when the localized Context is unusable.
        if (context.packageName == MODULE_PACKAGE) {
            val ownResources = runCatching { context.resources }.getOrNull()
            if (ownResources != null) return ownResources.getString(resource, *arguments)
        }
        return synchronized(this) {
            val localized = runCatching {
                val base = moduleContext ?: context.createPackageContext(MODULE_PACKAGE, 0).also {
                    moduleContext = it
                }
                val configuration = Configuration(context.resources.configuration).apply {
                    setLocale(locale(context))
                }
                if (localizedContext == null || cachedConfiguration != configuration) {
                    localizedContext = base.createConfigurationContext(configuration)
                    cachedConfiguration = configuration
                }
                // Unlike Context.getString(), an explicitly checked Resources
                // instance cannot dereference a null ContextImpl.mResources.
                val resources = checkNotNull(localizedContext?.resources) {
                    "Localized module resources were null in ${context.packageName}"
                }
                resources.getString(resource, *arguments)
            }
            localized.getOrElse { error ->
                io.github.lujinxin.nextep.logging.NeXtepLog.warn(
                    "module_resources",
                    "Localized module resources unavailable in ${context.packageName}; using PackageManager",
                    error,
                )
                runCatching {
                    context.packageManager.getResourcesForApplication(MODULE_PACKAGE)
                        .getString(resource, *arguments)
                }.getOrElse { fallbackError ->
                    io.github.lujinxin.nextep.logging.NeXtepLog.warn(
                        "module_resources",
                        "Unable to resolve module resource id=$resource from APK",
                        fallbackError,
                    )
                    // Never crash SystemUI when all package resources are
                    // unavailable; use a diagnostic placeholder instead.
                    "NeXtep"
                }
            }
        }
    }

    private fun supportedTag(locale: Locale): String? = when (locale.language) {
        "zh" -> if (locale.script == "Hant" ||
            (locale.script != "Hans" && locale.country in setOf("TW", "HK", "MO"))) "zh-Hant" else "zh-Hans"
        "en", "ja", "ko" -> locale.language
        else -> null
    }
}
