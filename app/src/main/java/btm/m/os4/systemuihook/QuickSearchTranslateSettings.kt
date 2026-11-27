// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_QUICK_SEARCH_TRANSLATE_ENABLED = "quick_search_translate_enabled_v1"
internal const val KEY_QUICK_SEARCH_TRANSLATE_AUTO_EN = "quick_search_translate_auto_en_v1"
internal const val KEY_QUICK_SEARCH_TRANSLATE_TARGET_LANG = "quick_search_translate_target_lang_v1"
internal const val KEY_QUICK_SEARCH_TRANSLATE_AUTO_EXPAND = "quick_search_translate_auto_expand_v1"
internal const val KEY_QUICK_SEARCH_TRANSLATE_LOCAL_APPS = "quick_search_translate_local_apps_v1"
internal const val KEY_QUICK_SEARCH_ENGINE_ID = "quick_search_translate_engine_id_v1"
internal const val KEY_QUICK_SEARCH_CUSTOM_URL = "quick_search_translate_custom_url_v1"
internal const val KEY_QUICK_SEARCH_BROWSER_MODE = "quick_search_translate_browser_mode_v1"

internal enum class QuickSearchTranslateLanguage(
    val code: String,
    val label: String,
    val shortLabel: String,
) {
    ZH_CN("zh-CN", "简体中文", "中文"),
    EN("en", "英语 (English)", "英语"),
    JA("ja", "日语 (日本語)", "日语"),
    KO("ko", "韩语 (한국어)", "韩语"),
    FR("fr", "法语 (Français)", "法语"),
    DE("de", "德语 (Deutsch)", "德语"),
    ES("es", "西班牙语 (Español)", "西语"),
    RU("ru", "俄语 (Русский)", "俄语");

    companion object {
        fun fromCode(code: String?): QuickSearchTranslateLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ZH_CN
    }
}

internal enum class QuickSearchEngine(
    val id: String,
    val label: String,
    val urlTemplate: String,
) {
    SYSTEM_DEFAULT("system", "系统默认", ""),
    BING("bing", "Bing (必应)", "https://www.bing.com/search?q=%s"),
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    BAIDU("baidu", "百度", "https://www.baidu.com/s?wd=%s"),
    DUCKDUCKGO("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    PERPLEXITY("perplexity", "Perplexity AI", "https://www.perplexity.ai/search?q=%s"),
    CUSTOM("custom", "自定义搜索网址", "");

    companion object {
        fun fromId(id: String?): QuickSearchEngine =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SYSTEM_DEFAULT
    }
}

internal enum class QuickSearchBrowserMode(
    val id: String,
    val label: String,
    val packageName: String?,
) {
    SYSTEM_DEFAULT("system", "系统默认浏览器", null),
    EDGE("edge", "Microsoft Edge", "com.microsoft.emmx");

    companion object {
        fun fromId(id: String?): QuickSearchBrowserMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SYSTEM_DEFAULT
    }
}

internal data class QuickSearchTranslateSettings(
    val enabled: Boolean = true,
    val autoDetectEnglish: Boolean = true,
    val targetLanguage: String = QuickSearchTranslateLanguage.ZH_CN.code,
    val autoExpandAcademic: Boolean = false,
    val showLocalAppShortcuts: Boolean = true,
    val searchEngine: String = QuickSearchEngine.SYSTEM_DEFAULT.id,
    val customSearchUrl: String = "https://www.bing.com/search?q=%s",
    val browserMode: String = QuickSearchBrowserMode.SYSTEM_DEFAULT.id,
) {
    val language: QuickSearchTranslateLanguage
        get() = QuickSearchTranslateLanguage.fromCode(targetLanguage)
    val engine: QuickSearchEngine
        get() = QuickSearchEngine.fromId(searchEngine)
    val browser: QuickSearchBrowserMode
        get() = QuickSearchBrowserMode.fromId(browserMode)
    val customWebSearchActive: Boolean
        get() = engine != QuickSearchEngine.SYSTEM_DEFAULT || browser != QuickSearchBrowserMode.SYSTEM_DEFAULT
    val anyFeatureActive: Boolean
        get() = enabled || customWebSearchActive
}

internal fun readQuickSearchTranslateSettings(p: SharedPreferences) = QuickSearchTranslateSettings(
    enabled = p.getBoolean(KEY_QUICK_SEARCH_TRANSLATE_ENABLED, true),
    autoDetectEnglish = p.getBoolean(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EN, true),
    targetLanguage = QuickSearchTranslateLanguage.fromCode(
        p.getString(KEY_QUICK_SEARCH_TRANSLATE_TARGET_LANG, QuickSearchTranslateLanguage.ZH_CN.code),
    ).code,
    autoExpandAcademic = p.getBoolean(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EXPAND, false),
    showLocalAppShortcuts = p.getBoolean(KEY_QUICK_SEARCH_TRANSLATE_LOCAL_APPS, true),
    searchEngine = QuickSearchEngine.fromId(
        p.getString(KEY_QUICK_SEARCH_ENGINE_ID, QuickSearchEngine.SYSTEM_DEFAULT.id),
    ).id,
    customSearchUrl = p.getString(KEY_QUICK_SEARCH_CUSTOM_URL, "https://www.bing.com/search?q=%s").orEmpty(),
    browserMode = QuickSearchBrowserMode.fromId(
        p.getString(KEY_QUICK_SEARCH_BROWSER_MODE, QuickSearchBrowserMode.SYSTEM_DEFAULT.id),
    ).id,
)

internal class QuickSearchTranslateSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings = readQuickSearchTranslateSettings(local); private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        val editor = local.edit()
        if (!local.contains(KEY_QUICK_SEARCH_TRANSLATE_ENABLED)) {
            editor.putBoolean(KEY_QUICK_SEARCH_TRANSLATE_ENABLED, remote.getBoolean(KEY_QUICK_SEARCH_TRANSLATE_ENABLED, true))
        }
        if (!local.contains(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EN)) {
            editor.putBoolean(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EN, remote.getBoolean(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EN, true))
        }
        if (!local.contains(KEY_QUICK_SEARCH_TRANSLATE_TARGET_LANG)) {
            editor.putString(
                KEY_QUICK_SEARCH_TRANSLATE_TARGET_LANG,
                remote.getString(KEY_QUICK_SEARCH_TRANSLATE_TARGET_LANG, QuickSearchTranslateLanguage.ZH_CN.code),
            )
        }
        if (!local.contains(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EXPAND)) {
            editor.putBoolean(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EXPAND, remote.getBoolean(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EXPAND, false))
        }
        if (!local.contains(KEY_QUICK_SEARCH_TRANSLATE_LOCAL_APPS)) {
            editor.putBoolean(KEY_QUICK_SEARCH_TRANSLATE_LOCAL_APPS, remote.getBoolean(KEY_QUICK_SEARCH_TRANSLATE_LOCAL_APPS, true))
        }
        if (!local.contains(KEY_QUICK_SEARCH_ENGINE_ID)) {
            editor.putString(
                KEY_QUICK_SEARCH_ENGINE_ID,
                remote.getString(KEY_QUICK_SEARCH_ENGINE_ID, QuickSearchEngine.SYSTEM_DEFAULT.id),
            )
        }
        if (!local.contains(KEY_QUICK_SEARCH_CUSTOM_URL)) {
            editor.putString(
                KEY_QUICK_SEARCH_CUSTOM_URL,
                remote.getString(KEY_QUICK_SEARCH_CUSTOM_URL, "https://www.bing.com/search?q=%s"),
            )
        }
        if (!local.contains(KEY_QUICK_SEARCH_BROWSER_MODE)) {
            editor.putString(
                KEY_QUICK_SEARCH_BROWSER_MODE,
                remote.getString(KEY_QUICK_SEARCH_BROWSER_MODE, QuickSearchBrowserMode.SYSTEM_DEFAULT.id),
            )
        }
        editor.apply()
        settings = readQuickSearchTranslateSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (QuickSearchTranslateSettings) -> QuickSearchTranslateSettings) {
        settings = transform(readQuickSearchTranslateSettings(local))
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit()
            .putBoolean(KEY_QUICK_SEARCH_TRANSLATE_ENABLED, settings.enabled)
            .putBoolean(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EN, settings.autoDetectEnglish)
            .putString(KEY_QUICK_SEARCH_TRANSLATE_TARGET_LANG, settings.targetLanguage)
            .putBoolean(KEY_QUICK_SEARCH_TRANSLATE_AUTO_EXPAND, settings.autoExpandAcademic)
            .putBoolean(KEY_QUICK_SEARCH_TRANSLATE_LOCAL_APPS, settings.showLocalAppShortcuts)
            .putString(KEY_QUICK_SEARCH_ENGINE_ID, settings.searchEngine)
            .putString(KEY_QUICK_SEARCH_CUSTOM_URL, settings.customSearchUrl)
            .putString(KEY_QUICK_SEARCH_BROWSER_MODE, settings.browserMode)
            .apply()
    }
}
