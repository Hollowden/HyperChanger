// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Executable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在系统下拉「搜索」(com.android.quicksearchbox) 中原生注入实时翻译与学术词典卡片：
 * - 支持前缀触发（翻译 / 译 / fy / tr / fanyi）及英文自动查词/句译；
 * - 聚合有道学术词典（音标、考试标签、全词性释义、244万篇论文专业学科释义、词形变化、WordNet英英、短语搭配、双语例句、整句翻译）与 Google 多语言互译；
 * - 一键无缝联动本机「欧路词典悬浮查词 (eudic://peek)」与「DeepL 迷你翻译」；
 * - 零后台功耗：仅前台输入变化 350ms 防抖触发，LRU 缓存 64 条，按需单线程 15 秒空闲自动销毁。
 */
internal class QuickSearchTranslateHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var installed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controllers = Collections.synchronizedMap(WeakHashMap<Activity, OverlayController>())
    private val resultCache = object : LruCache<String, TranslationResult>(64) {}

    // 空闲 15 秒自动回收线程，杜绝常驻后台线程与额外功耗
    private val worker = ThreadPoolExecutor(
        0,
        1,
        15L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { r ->
        Thread(r, "HC-SearchTranslate").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    @Volatile private var settings = QuickSearchTranslateSettings()
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private companion object {
        const val EXTRA_HC_HANDLED = "hc_qsb_custom_search_handled"
    }

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (installed) return
        settings = readQuickSearchTranslateSettings(preferences)
        if (prefListener == null) {
            prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key.startsWith("quick_search_translate_")) {
                    settings = readQuickSearchTranslateSettings(preferences)
                }
            }.also { preferences.registerOnSharedPreferenceChangeListener(it) }
        }
        runCatching {
            val activityClass = classLoader.loadClass("com.android.quicksearchbox.SearchActivityTransparent")
            val onCreate = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)
            hookDeoptimized(onCreate)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("quick-search-translate:onCreate")
                .intercept { chain ->
                    val res = chain.proceed()
                    val activity = chain.thisObject as? Activity ?: return@intercept res
                    if (settings.anyFeatureActive) {
                        mainHandler.post { attachToActivity(activity) }
                    }
                    res
                }

            val onDestroy = activityClass.getDeclaredMethod("onDestroy")
            hookDeoptimized(onDestroy)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("quick-search-translate:onDestroy")
                .intercept { chain ->
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        controllers.remove(activity)?.dispose()
                    }
                    chain.proceed()
                }

            installBrowserIntentRedirectHooks()

            installed = true
            HookModuleLogger.log(module, Log.INFO, logTag, "Installed QuickSearchBox translate card hooks")
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, logTag, "Could not install QuickSearchBox translate hooks", err)
        }
    }

    private fun installBrowserIntentRedirectHooks() {
        // 统一拦截小米搜索发往内置浏览器 (FullSearchActivity / 网页搜索) 的所有跳转（含点击搜索建议/历史/热词）
        runCatching {
            val actStart = Activity::class.java.getDeclaredMethod(
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java,
            )
            hookDeoptimized(actStart)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("quick-search-translate:act-startActivity")
                .intercept { chain ->
                    val ctx = chain.thisObject as? Context
                    val intent = chain.args.getOrNull(0) as? Intent
                    if (ctx != null && intent != null && maybeRedirectWebSearchIntent(ctx, intent)) {
                        return@intercept null
                    }
                    chain.proceed()
                }
        }
        runCatching {
            val ctxImpl = Class.forName("android.app.ContextImpl")
            val ctxStart = ctxImpl.getDeclaredMethod("startActivity", Intent::class.java, Bundle::class.java)
            hookDeoptimized(ctxStart)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("quick-search-translate:ctx-startActivity")
                .intercept { chain ->
                    val ctx = chain.thisObject as? Context
                    val intent = chain.args.getOrNull(0) as? Intent
                    if (ctx != null && intent != null && maybeRedirectWebSearchIntent(ctx, intent)) {
                        return@intercept null
                    }
                    chain.proceed()
                }
        }
    }

    private fun maybeRedirectWebSearchIntent(context: Context, intent: Intent): Boolean {
        val current = settings
        if (!current.customWebSearchActive) return false
        if (intent.getBooleanExtra(EXTRA_HC_HANDLED, false)) return false

        val targetPkg = intent.component?.packageName ?: intent.`package`
        val targetCls = intent.component?.className.orEmpty()
        val dataUri = intent.data
        val dataStr = dataUri?.toString().orEmpty()

        val isFullSearch = targetCls.contains("FullSearch", ignoreCase = true)
        val isMiBrowserWeb = targetPkg == "com.android.browser" &&
            (dataStr.startsWith("http://", ignoreCase = true) || dataStr.startsWith("https://", ignoreCase = true) || isFullSearch)

        if (!isFullSearch && !isMiBrowserWeb) return false

        // 提取搜索关键词（优先 Extras，再解析搜索 URL 参数）
        val extractedQuery = extractSearchQueryFromIntent(intent, dataUri)
        val finalUrl = when {
            !extractedQuery.isNullOrBlank() -> buildWebSearchUrl(extractedQuery, current)
            dataStr.startsWith("http://", ignoreCase = true) || dataStr.startsWith("https://", ignoreCase = true) -> dataStr
            else -> return false
        }

        return launchUrlWithConfiguredBrowser(context, finalUrl, current)
    }

    private fun extractSearchQueryFromIntent(intent: Intent, uri: Uri?): String? {
        val extraKeys = listOf("query", "search_word", "keyword", "word", "q", "search_key", "text", "key_word", "searchWord")
        val extras = runCatching { intent.extras }.getOrNull()
        if (extras != null) {
            for (k in extraKeys) {
                val v = runCatching { extras.getString(k) }.getOrNull()?.trim()
                if (!v.isNullOrEmpty() && !v.startsWith("http://", true) && !v.startsWith("https://", true)) {
                    return v
                }
            }
            // 部分小米搜索会通过 url / target_url extra 传搜索页链接
            for (k in listOf("url", "target_url", "jump_url", "search_url")) {
                val u = runCatching { extras.getString(k) }.getOrNull()?.trim()
                if (!u.isNullOrEmpty()) {
                    extractQueryFromUri(Uri.parse(u))?.let { return it }
                }
            }
        }
        if (uri != null) {
            extractQueryFromUri(uri)?.let { return it }
        }
        return null
    }

    private fun extractQueryFromUri(uri: Uri): String? {
        if (!uri.isHierarchical) return null
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        val looksLikeSearch = path.contains("search") || path == "/s" || host.contains("baidu.") ||
            host.contains("bing.") || host.contains("google.") || host.contains("sogou.") ||
            host.contains("sm.cn") || host.contains("so.com") || host.contains("quark.") ||
            host.contains("mi.com") || host.contains("xiaomi.")
        if (!looksLikeSearch) return null
        for (param in listOf("wd", "word", "q", "query", "keyword", "kw", "text")) {
            val v = runCatching { uri.getQueryParameter(param) }.getOrNull()?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    private fun buildWebSearchUrl(query: String, current: QuickSearchTranslateSettings): String {
        val trimmed = query.trim()
        // 若用户直接输入了完整网址，优先直接打开网址
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val rawTemplate = when (current.engine) {
            QuickSearchEngine.CUSTOM -> current.customSearchUrl.trim().ifBlank { QuickSearchEngine.BING.urlTemplate }
            QuickSearchEngine.SYSTEM_DEFAULT -> QuickSearchEngine.BING.urlTemplate
            else -> current.engine.urlTemplate
        }
        val template = if (rawTemplate.startsWith("http://", ignoreCase = true) ||
            rawTemplate.startsWith("https://", ignoreCase = true)
        ) rawTemplate else "https://$rawTemplate"

        if (trimmed.isEmpty()) {
            return template.replace("%s", "").removeSuffix("?q=").removeSuffix("&q=").removeSuffix("?wd=")
        }
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return when {
            template.contains("%s") -> template.replace("%s", encoded)
            template.contains("?") -> "$template&q=$encoded"
            else -> "$template?q=$encoded"
        }
    }

    private fun launchUrlWithConfiguredBrowser(
        context: Context,
        url: String,
        current: QuickSearchTranslateSettings,
    ): Boolean {
        val uri = Uri.parse(url)
        val browserPkg = current.browser.packageName
        if (browserPkg != null && isPackageInstalled(context, browserPkg)) {
            val targeted = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(browserPkg)
                putExtra(EXTRA_HC_HANDLED, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(targeted); true }.getOrDefault(false)) {
                return true
            }
        }
        val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtra(EXTRA_HC_HANDLED, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(fallback); true }.getOrDefault(false)
    }

    private fun launchCustomWebSearch(activity: Activity, query: String): Boolean {
        val current = settings
        if (!current.customWebSearchActive) return false
        val url = buildWebSearchUrl(query, current)
        return launchUrlWithConfiguredBrowser(activity, url, current)
    }

    private fun <T : View> collectViewsById(root: View, id: Int, cls: Class<T>, out: MutableList<T>) {
        if (id == 0) return
        if (root.id == id && cls.isInstance(root)) {
            @Suppress("UNCHECKED_CAST")
            out.add(root as T)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectViewsById(root.getChildAt(i), id, cls, out)
            }
        }
    }

    private fun attachToActivity(activity: Activity, allowRetry: Boolean = true) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (controllers.containsKey(activity)) return
        val pkg = activity.packageName
        val res = activity.resources
        val rootId = res.getIdentifier("search_activity_view", "id", pkg)
        val inputContainerId = res.getIdentifier("input_container", "id", pkg)
        val queryTextId = res.getIdentifier("query_text", "id", pkg)
        val webBtnId = res.getIdentifier("search_in_web_btn", "id", pkg)

        val root = (if (rootId != 0) activity.findViewById<View>(rootId) else null) as? RelativeLayout
        val inputContainer = if (inputContainerId != 0) activity.findViewById<View>(inputContainerId) else null
        val editTexts = mutableListOf<EditText>()
        val webBtns = mutableListOf<View>()
        if (inputContainer != null) {
            // 小米搜索 InputViewContainer 内置 3 套共用相同 ID 的输入布局，前 2 套默认 GONE，必须全量绑定
            collectViewsById(inputContainer, queryTextId, EditText::class.java, editTexts)
            collectViewsById(inputContainer, webBtnId, View::class.java, webBtns)
        } else if (root != null) {
            collectViewsById(root, queryTextId, EditText::class.java, editTexts)
            collectViewsById(root, webBtnId, View::class.java, webBtns)
        }

        if (root == null || inputContainer == null || editTexts.isEmpty()) {
            if (allowRetry) {
                mainHandler.postDelayed({
                    attachToActivity(activity, allowRetry = false)
                }, 250L)
            }
            return
        }
        val controller = OverlayController(activity, root, inputContainer, editTexts, webBtns)
        controllers[activity] = controller
        controller.bind()
    }

    private data class ParsedQuery(
        val text: String,
        val explicit: Boolean,
        val overrideTargetLang: QuickSearchTranslateLanguage? = null,
    )

    private fun parseQuery(raw: String, current: QuickSearchTranslateSettings): ParsedQuery? {
        if (!current.enabled) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 500) return null

        // 1. 显式触发前缀（随时强制翻译单词/句子/任意语种）
        val explicitPrefixes = listOf(
            "翻译 ", "翻译：", "翻译:", "翻译",
            "译 ", "译：", "译:",
            "fy ", "fy:", "fanyi ", "tr ", "tr:", "trans ", "translate ",
        )
        for (prefix in explicitPrefixes) {
            if (trimmed.startsWith(prefix, ignoreCase = true)) {
                val body = trimmed.substring(prefix.length).trim()
                if (body.isNotEmpty()) {
                    return ParsedQuery(body, explicit = true)
                }
                return null
            }
        }

        // 2. 自动识别英文单词 / 英文短语 / 英文句子
        if (!current.autoDetectEnglish) return null
        if (trimmed.length < 2) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.") ||
            lower.contains("://") || lower.contains("@") || lower.contains("\\")
        ) return null

        var asciiLetters = 0
        var cjkChars = 0
        var digits = 0
        for (ch in trimmed) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> asciiLetters++
                ch in '\u4e00'..'\u9fff' -> cjkChars++
                ch in '0'..'9' -> digits++
            }
        }
        if (asciiLetters < 2) return null
        // 含中文时不自动触发（避免干扰日常中英混合搜本机 App/联系人，需翻译请加「翻译 」前缀）
        if (cjkChars > 0) return null
        // 纯编号/型号（数字占比过高）不触发
        if (digits > asciiLetters) return null
        return ParsedQuery(trimmed, explicit = false)
    }

    private fun resolveTargetLanguage(text: String, preferred: QuickSearchTranslateLanguage): QuickSearchTranslateLanguage {
        val hasCjk = text.any { it in '\u4e00'..'\u9fff' }
        val hasAscii = text.any { it in 'a'..'z' || it in 'A'..'Z' }
        // 智能双向互译：默认目标是中文但输入了纯中文 -> 自动译为英文；默认目标是英文但输入了纯英文 -> 自动译为中文
        return when {
            preferred == QuickSearchTranslateLanguage.ZH_CN && hasCjk && !hasAscii -> QuickSearchTranslateLanguage.EN
            preferred == QuickSearchTranslateLanguage.EN && hasAscii && !hasCjk -> QuickSearchTranslateLanguage.ZH_CN
            else -> preferred
        }
    }

    private data class MeaningEntry(val pos: String, val definition: String)
    private data class SpecialEntry(val major: String, val text: String)
    private data class PhraseEntry(val headword: String, val trans: String)
    private data class ExampleEntry(val source: String, val target: String)

    private data class TranslationResult(
        val query: String,
        val targetLang: QuickSearchTranslateLanguage,
        val primaryTranslation: String,
        val ukPhone: String = "",
        val usPhone: String = "",
        val phoneticOther: String = "",
        val examTags: List<String> = emptyList(),
        val meanings: List<MeaningEntry> = emptyList(),
        val specialFields: List<SpecialEntry> = emptyList(),
        val wordForms: List<String> = emptyList(),
        val englishDefs: List<MeaningEntry> = emptyList(),
        val phrases: List<PhraseEntry> = emptyList(),
        val synonyms: List<String> = emptyList(),
        val examples: List<ExampleEntry> = emptyList(),
        val engineLabel: String = "",
    ) {
        val hasAcademicDetails: Boolean
            get() = wordForms.isNotEmpty() || englishDefs.isNotEmpty() || phrases.isNotEmpty() ||
                synonyms.isNotEmpty() || examples.isNotEmpty() || specialFields.size > 3
    }

    private fun fetchTranslation(query: String, target: QuickSearchTranslateLanguage): TranslationResult? {
        val key = "${target.code}|$query"
        resultCache.get(key)?.let { return it }

        val isZhOrEn = target == QuickSearchTranslateLanguage.ZH_CN || target == QuickSearchTranslateLanguage.EN
        val result = if (isZhOrEn) {
            val yd = runCatching { queryYoudao(query, target) }.getOrNull()
            if (yd != null && (yd.primaryTranslation.isNotBlank() || yd.meanings.isNotEmpty())) {
                yd
            } else {
                runCatching { queryGoogle(query, target) }.getOrNull() ?: yd
            }
        } else {
            runCatching { queryGoogle(query, target) }.getOrNull()
                ?: runCatching { queryYoudao(query, target) }.getOrNull()
        }
        if (result != null && (result.primaryTranslation.isNotBlank() || result.meanings.isNotEmpty())) {
            resultCache.put(key, result)
            return result
        }
        return null
    }

    private fun httpGet(urlStr: String, timeoutMs: Int = 5500): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36")
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) "" else BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&").trim()

    private fun queryYoudao(query: String, target: QuickSearchTranslateLanguage): TranslationResult? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val raw = httpGet("https://dict.youdao.com/jsonapi_s?doctype=json&jsonversion=4&q=$encoded")
        if (raw.isBlank() || !raw.trimStart().startsWith("{")) return null
        val root = JSONObject(raw)

        var primary = ""
        var ukPhone = ""
        var usPhone = ""
        val examTags = mutableListOf<String>()
        val meanings = mutableListOf<MeaningEntry>()
        val specials = mutableListOf<SpecialEntry>()
        val wordForms = mutableListOf<String>()
        val englishDefs = mutableListOf<MeaningEntry>()
        val phrases = mutableListOf<PhraseEntry>()
        val synonyms = mutableListOf<String>()
        val examples = mutableListOf<ExampleEntry>()

        // 1. 整句翻译 (fanyi)
        root.optJSONObject("fanyi")?.optString("tran")?.takeIf { it.isNotBlank() }?.let {
            primary = it.trim()
        }

        // 2. 英汉权威词典 (ec)
        root.optJSONObject("ec")?.let { ec ->
            ec.optJSONArray("exam_type")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(examTags::add)
                }
            }
            ec.optJSONArray("special")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val major = item.optString("major").trim()
                    val nat = item.optString("nat").trim()
                    if (major.isNotBlank() && nat.isNotBlank()) {
                        specials.add(SpecialEntry(major, nat))
                    }
                }
            }
            val wordObj = ec.optJSONObject("word") ?: ec.optJSONArray("word")?.optJSONObject(0)
            if (wordObj != null) {
                ukPhone = wordObj.optString("ukphone").trim()
                usPhone = wordObj.optString("usphone").trim()
                wordObj.optJSONArray("trs")?.let { trs ->
                    for (i in 0 until trs.length()) {
                        val tr = trs.optJSONObject(i) ?: continue
                        val pos = tr.optString("pos").trim()
                        val tran = tr.optString("tran").trim()
                        if (tran.isNotBlank()) {
                            meanings.add(MeaningEntry(pos, tran))
                        } else {
                            // 兼容旧版 nested tr:[{l:{i:[...]}}] 结构
                            tr.optJSONArray("tr")?.optJSONObject(0)?.optJSONObject("l")
                                ?.optJSONArray("i")?.optString(0)?.trim()?.takeIf { it.isNotBlank() }
                                ?.let { line -> meanings.add(MeaningEntry("", line)) }
                        }
                    }
                }
                wordObj.optJSONArray("wfs")?.let { wfs ->
                    for (i in 0 until wfs.length()) {
                        val wf = wfs.optJSONObject(i)?.optJSONObject("wf") ?: continue
                        val name = wf.optString("name").trim()
                        val value = wf.optString("value").trim()
                        if (name.isNotBlank() && value.isNotBlank()) {
                            wordForms.add("$name: $value")
                        }
                    }
                }
            }
            if (primary.isBlank() && meanings.isEmpty()) {
                ec.optJSONArray("web_trans")?.let { wt ->
                    val list = (0 until minOf(4, wt.length())).mapNotNull {
                        wt.optString(it).takeIf(String::isNotBlank)
                    }
                    if (list.isNotEmpty()) primary = list.joinToString("；")
                }
            }
        }

        // 3. 汉英词典 (ce)
        if (meanings.isEmpty()) {
            root.optJSONObject("ce")?.let { ce ->
                val wordObj = ce.optJSONObject("word") ?: ce.optJSONArray("word")?.optJSONObject(0)
                wordObj?.optJSONArray("trs")?.let { trs ->
                    for (i in 0 until minOf(6, trs.length())) {
                        val tr = trs.optJSONObject(i) ?: continue
                        val text = tr.optString("#text").trim()
                        val tran = tr.optString("#tran").trim()
                        if (text.isNotBlank()) {
                            if (primary.isBlank()) primary = text
                            val detail = if (tran.isNotBlank()) "$text — $tran" else text
                            meanings.add(MeaningEntry("", detail))
                        }
                    }
                }
            }
        }

        // 4. 学术论文专业释义补充 (special.entries)
        root.optJSONObject("special")?.optJSONArray("entries")?.let { entries ->
            for (i in 0 until minOf(8, entries.length())) {
                val entry = entries.optJSONObject(i)?.optJSONObject("entry") ?: continue
                val major = entry.optString("major").trim()
                val nat = entry.optJSONArray("trs")?.optJSONObject(0)?.optJSONObject("tr")
                    ?.optString("nat")?.trim().orEmpty()
                if (major.isNotBlank() && nat.isNotBlank() && specials.none { it.major == major && it.text == nat }) {
                    specials.add(SpecialEntry(major, nat))
                }
            }
        }

        // 5. WordNet 英英权威释义 (ee)
        root.optJSONObject("ee")?.optJSONObject("word")?.optJSONArray("trs")?.let { trs ->
            for (i in 0 until minOf(4, trs.length())) {
                val trObj = trs.optJSONObject(i) ?: continue
                val pos = trObj.optString("pos").trim()
                val arr = trObj.optJSONArray("tr") ?: continue
                for (j in 0 until minOf(2, arr.length())) {
                    val t = arr.optJSONObject(j)?.optString("tran")?.trim().orEmpty()
                    if (t.isNotBlank()) englishDefs.add(MeaningEntry(pos, t))
                }
            }
        }

        // 6. 常用词组搭配 (phrs)
        root.optJSONObject("phrs")?.optJSONArray("phrs")?.let { arr ->
            for (i in 0 until minOf(6, arr.length())) {
                val p = arr.optJSONObject(i) ?: continue
                val hw = p.optString("headword").trim()
                val tr = p.optString("translation").trim()
                if (hw.isNotBlank() && tr.isNotBlank()) phrases.add(PhraseEntry(hw, tr))
            }
        }

        // 7. 同近义词 (syno)
        root.optJSONObject("syno")?.optJSONArray("synos")?.let { arr ->
            for (i in 0 until minOf(3, arr.length())) {
                val s = arr.optJSONObject(i) ?: continue
                val pos = s.optString("pos").trim()
                val ws = s.optJSONArray("ws") ?: continue
                val words = (0 until minOf(4, ws.length())).mapNotNull { ws.optString(it).takeIf(String::isNotBlank) }
                if (words.isNotEmpty()) {
                    synonyms.add(if (pos.isNotBlank()) "$pos ${words.joinToString(", ")}" else words.joinToString(", "))
                }
            }
        }

        // 8. 双语例句 (blng_sents_part)
        root.optJSONObject("blng_sents_part")?.optJSONArray("sentence-pair")?.let { arr ->
            for (i in 0 until minOf(2, arr.length())) {
                val pair = arr.optJSONObject(i) ?: continue
                val src = stripHtml(pair.optString("sentence"))
                val dst = stripHtml(pair.optString("sentence-translation"))
                if (src.isNotBlank() && dst.isNotBlank()) examples.add(ExampleEntry(src, dst))
            }
        }

        // 9. 网络释义兜底
        if (primary.isBlank() && meanings.isEmpty()) {
            root.optJSONObject("web_trans")?.optJSONArray("web-translation")?.optJSONObject(0)
                ?.optJSONArray("trans")?.let { trs ->
                    val vals = (0 until minOf(4, trs.length())).mapNotNull {
                        trs.optJSONObject(it)?.optString("value")?.takeIf(String::isNotBlank)
                    }
                    if (vals.isNotEmpty()) primary = vals.joinToString("；")
                }
        }

        if (primary.isBlank() && meanings.isNotEmpty()) {
            primary = meanings.first().let { if (it.pos.isNotBlank()) "${it.pos} ${it.definition}" else it.definition }
        }
        if (primary.isBlank() && meanings.isEmpty()) return null

        return TranslationResult(
            query = query,
            targetLang = target,
            primaryTranslation = primary,
            ukPhone = ukPhone,
            usPhone = usPhone,
            examTags = examTags.distinct().take(6),
            meanings = meanings,
            specialFields = specials.take(8),
            wordForms = wordForms.take(6),
            englishDefs = englishDefs.take(5),
            phrases = phrases,
            synonyms = synonyms,
            examples = examples,
            engineLabel = "有道学术词典",
        )
    }

    private fun queryGoogle(query: String, target: QuickSearchTranslateLanguage): TranslationResult? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${target.code}&dt=t&dt=bd&dt=rm&q=$encoded"
        val raw = httpGet(url)
        if (raw.isBlank() || !raw.trimStart().startsWith("[")) return null
        val arr = JSONArray(raw)

        val sb = StringBuilder()
        var phonetic = ""
        arr.optJSONArray(0)?.let { segs ->
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                val piece = seg.optString(0)
                if (!piece.isNullOrEmpty() && piece != "null") sb.append(piece)
                val rm = seg.optString(3)
                if (phonetic.isBlank() && !rm.isNullOrEmpty() && rm != "null") phonetic = rm
            }
        }
        val primary = sb.toString().trim()
        val meanings = mutableListOf<MeaningEntry>()
        arr.optJSONArray(1)?.let { dict ->
            for (i in 0 until minOf(6, dict.length())) {
                val entry = dict.optJSONArray(i) ?: continue
                val pos = entry.optString(0).trim()
                val terms = entry.optJSONArray(1) ?: continue
                val list = (0 until minOf(6, terms.length())).mapNotNull {
                    terms.optString(it).takeIf { s -> s.isNotBlank() && s != "null" }
                }
                if (list.isNotEmpty()) {
                    meanings.add(MeaningEntry(pos, list.joinToString("；")))
                }
            }
        }
        if (primary.isBlank() && meanings.isEmpty()) return null
        return TranslationResult(
            query = query,
            targetLang = target,
            primaryTranslation = primary.ifBlank { meanings.first().definition },
            phoneticOther = phonetic,
            meanings = meanings,
            engineLabel = "Google 翻译",
        )
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun resolveEudicPackage(context: Context): String? =
        listOf("com.qianyan.eudic", "com.eusoft.eudic").firstOrNull { isPackageInstalled(context, it) }

    private fun launchEudicLookup(context: Context, text: String) {
        val pkg = resolveEudicPackage(context) ?: return
        // 1. 优先打开欧路原生悬浮查词窗 (eudic://peek/<word>)
        val peekIntent = Intent(Intent.ACTION_VIEW, Uri.parse("eudic://peek/${Uri.encode(text)}")).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(peekIntent); true }.getOrDefault(false)) return

        // 2. 回退到 PROCESS_TEXT (LightpeekActivity)
        val processIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(processIntent); true }.getOrDefault(false)) return

        // 3. 回退到完整词典页
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("eudic://dict/${Uri.encode(text)}")).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun launchDeepLLookup(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            setPackage("com.deepl.mobiletranslator")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    setPackage("com.deepl.mobiletranslator")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    private inner class OverlayController(
        private val activity: Activity,
        private val root: RelativeLayout,
        private val inputContainer: View,
        private val editTexts: List<EditText>,
        private val webBtns: List<View>,
    ) {
        private val seq = AtomicInteger(0)
        private var pendingRunnable: Runnable? = null
        private var dismissedQuery: String? = null
        private var overrideLang: QuickSearchTranslateLanguage? = null
        private var expanded = false
        private var lastResult: TranslationResult? = null
        private var lastQueryText: String = ""
        private var activeEditText: EditText = editTexts.firstOrNull { it.isShown } ?: editTexts.last()
        private val boundWatchers = mutableListOf<Pair<EditText, TextWatcher>>()

        private val d = activity.resources.displayMetrics.density
        private fun dp(v: Float): Int = (v * d + 0.5f).toInt()
        private val isDark: Boolean
            get() = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        private val cardContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dp(10f).toFloat()
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            isClickable = true
            isFocusable = false
        }
        private val headerTitle = TextView(activity)
        private val langChip = TextView(activity)
        private val closeBtn = TextView(activity)
        private val metaLine = TextView(activity)
        private val scrollView = ScrollView(activity)
        private val bodyColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        private val primaryView = TextView(activity)
        private val meaningsView = TextView(activity)
        private val academicView = TextView(activity)
        private val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val expandBtn = TextView(activity)
        private val copyBtn = TextView(activity)
        private val eudicBtn = TextView(activity)
        private val deeplBtn = TextView(activity)

        private fun currentInputText(): String {
            val visible = editTexts.firstOrNull { it.isShown }
            if (visible != null) {
                activeEditText = visible
                return visible.text?.toString().orEmpty()
            }
            return activeEditText.text?.toString().orEmpty()
        }

        fun bind() {
            buildUi()
            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                addRule(RelativeLayout.ABOVE, inputContainer.id)
                marginStart = dp(16f)
                marginEnd = dp(16f)
                bottomMargin = dp(10f)
            }
            root.addView(cardContainer, lp)
            editTexts.forEach { et ->
                val w = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val txt = s?.toString().orEmpty()
                        if (et.isShown || et === activeEditText || txt.isNotEmpty()) {
                            activeEditText = et
                            onInputChanged(txt)
                        }
                    }
                }
                et.addTextChangedListener(w)
                boundWatchers.add(et to w)
            }
            installWebSearchInterceptors()
            HookModuleLogger.log(
                module,
                Log.INFO,
                logTag,
                "Attached QuickSearchBox overlay (inputs=${editTexts.size}, webBtns=${webBtns.size}, engine=${settings.searchEngine}, browser=${settings.browserMode})",
            )
            // 若打开时已有预填文本则同步一次
            currentInputText().takeIf { it.isNotBlank() }?.let { onInputChanged(it) }
        }

        private fun handleSearchAction(rawInput: String): Boolean {
            val trimmed = rawInput.trim()
            // 若显式输入了「翻译 / fy / tr」前缀，按搜索/回车时直接即时翻译并展开学术详释，不跳浏览器
            val parsed = parseQuery(trimmed, settings)
            if (parsed != null && parsed.explicit) {
                pendingRunnable?.let(mainHandler::removeCallbacks)
                pendingRunnable = null
                lastQueryText = parsed.text
                expanded = true
                val targetLang = overrideLang ?: resolveTargetLanguage(parsed.text, settings.language)
                triggerFetch(parsed.text, targetLang)
                return true
            }
            if (settings.customWebSearchActive) {
                return launchCustomWebSearch(activity, trimmed)
            }
            return false
        }

        private fun installWebSearchInterceptors() {
            // 1. 包装所有输入布局中的「搜索」按钮点击，仅在显式翻译或启用自定义搜索引擎/Edge 时接管
            webBtns.forEach { btn ->
                val origClick = runCatching {
                    val infoField = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }
                    val info = infoField.get(btn) ?: return@runCatching null
                    info.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }.get(info) as? View.OnClickListener
                }.getOrNull()
                btn.setOnClickListener { v ->
                    val raw = currentInputText()
                    if (!handleSearchAction(raw)) {
                        origClick?.onClick(v)
                    }
                }
            }
            // 2. 包装所有输入框的软键盘搜索/回车动作
            editTexts.forEach { et ->
                val origEditorListener = runCatching {
                    val editorField = android.widget.TextView::class.java.getDeclaredField("mEditor").apply { isAccessible = true }
                    val editor = editorField.get(et) ?: return@runCatching null
                    val contentField = editor.javaClass.getDeclaredField("mInputContentType").apply { isAccessible = true }
                    val content = contentField.get(editor) ?: return@runCatching null
                    content.javaClass.getDeclaredField("onEditorActionListener").apply { isAccessible = true }
                        .get(content) as? android.widget.TextView.OnEditorActionListener
                }.getOrNull()
                et.setOnEditorActionListener { tv, actionId, event ->
                    val isSubmit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                        actionId == EditorInfo.IME_ACTION_GO ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        (event != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
                    if (isSubmit && handleSearchAction(tv.text?.toString().orEmpty())) {
                        true
                    } else {
                        origEditorListener?.onEditorAction(tv, actionId, event) ?: false
                    }
                }
            }
        }

        fun dispose() {
            pendingRunnable?.let(mainHandler::removeCallbacks)
            pendingRunnable = null
            seq.incrementAndGet()
            boundWatchers.forEach { (et, w) -> runCatching { et.removeTextChangedListener(w) } }
            boundWatchers.clear()
            runCatching { (cardContainer.parent as? ViewGroup)?.removeView(cardContainer) }
        }

        private fun applyCardSurface() {
            val bgColor = if (isDark) Color.argb(226, 30, 32, 38) else Color.argb(236, 248, 249, 252)
            val strokeColor = if (isDark) Color.argb(46, 255, 255, 255) else Color.argb(30, 0, 0, 0)
            cardContainer.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22f).toFloat()
                setColor(bgColor)
                setStroke(dp(0.8f).coerceAtLeast(1), strokeColor)
            }
            // 尽量启用 MIUI 原生高斯毛玻璃，不支持时自动平滑降级为半透明磨砂底
            runCatching {
                View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
                    .invoke(cardContainer, 1)
                View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
                    .invoke(cardContainer, dp(28f))
            }
        }

        private fun pillBg(accent: Boolean = false): GradientDrawable {
            val fill = when {
                accent && isDark -> Color.argb(66, 94, 165, 255)
                accent -> Color.argb(36, 13, 132, 255)
                isDark -> Color.argb(36, 255, 255, 255)
                else -> Color.argb(18, 0, 0, 0)
            }
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(99f).toFloat()
                setColor(fill)
            }
        }

        private fun buildUi() {
            applyCardSurface()
            val textPrimary = if (isDark) Color.rgb(245, 246, 250) else Color.rgb(24, 26, 32)
            val textSecondary = if (isDark) Color.rgb(176, 182, 196) else Color.rgb(96, 104, 118)
            val accentColor = if (isDark) Color.rgb(112, 180, 255) else Color.rgb(13, 132, 255)

            // 1. 顶部栏：词头 + 目标语言快切 + 关闭
            val headerRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerTitle.apply {
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            headerRow.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            langChip.apply {
                setTextColor(accentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                background = pillBg(accent = true)
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                setOnClickListener { cycleLanguage() }
            }
            headerRow.addView(langChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8f)
            })

            closeBtn.apply {
                text = "✕"
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = pillBg()
                setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
                setOnClickListener {
                    dismissedQuery = currentInputText().trim()
                    cardContainer.visibility = View.GONE
                }
            }
            headerRow.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6f)
            })
            cardContainer.addView(headerRow)

            // 2. 音标与考试标签行
            metaLine.apply {
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                visibility = View.GONE
                setPadding(0, dp(3f), 0, dp(2f))
            }
            cardContainer.addView(metaLine)

            // 3. 可滚动释义区（限高防止遮挡上方推荐应用）
            primaryView.apply {
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(dp(2f).toFloat(), 1.0f)
                setPadding(0, dp(6f), 0, dp(4f))
                setTextIsSelectable(true)
            }
            meaningsView.apply {
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setLineSpacing(dp(3f).toFloat(), 1.0f)
                visibility = View.GONE
                setPadding(0, dp(2f), 0, dp(4f))
                setTextIsSelectable(true)
            }
            academicView.apply {
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(dp(3f).toFloat(), 1.0f)
                visibility = View.GONE
                setPadding(0, dp(4f), 0, dp(4f))
                setTextIsSelectable(true)
            }
            bodyColumn.addView(primaryView)
            bodyColumn.addView(meaningsView)
            bodyColumn.addView(academicView)

            scrollView.apply {
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(bodyColumn)
            }
            cardContainer.addView(scrollView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))

            // 4. 底部操作栏：复制、展开详释、欧路查词、DeepL
            fun styleAction(tv: TextView, label: String, accent: Boolean = false, onClick: () -> Unit) {
                tv.apply {
                    text = label
                    setTextColor(if (accent) accentColor else textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = pillBg(accent)
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                    setOnClickListener { onClick() }
                }
            }
            styleAction(copyBtn, "复制") {
                val res = lastResult ?: return@styleAction
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("translation", res.primaryTranslation))
                Toast.makeText(activity, "已复制译文", Toast.LENGTH_SHORT).show()
            }
            styleAction(expandBtn, "展开详释", accent = true) {
                expanded = !expanded
                lastResult?.let { renderResult(it) }
            }
            styleAction(eudicBtn, "欧路词典", accent = true) {
                val q = lastQueryText.ifBlank { lastResult?.query.orEmpty() }
                if (q.isNotBlank()) launchEudicLookup(activity, q)
            }
            styleAction(deeplBtn, "DeepL") {
                val q = lastQueryText.ifBlank { lastResult?.query.orEmpty() }
                if (q.isNotBlank()) launchDeepLLookup(activity, q)
            }

            val chipLp = { start: Int ->
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(start.toFloat())
                }
            }
            actionRow.addView(copyBtn, chipLp(0))
            actionRow.addView(expandBtn, chipLp(8))
            actionRow.addView(eudicBtn, chipLp(8))
            actionRow.addView(deeplBtn, chipLp(8))
            cardContainer.addView(actionRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8f)
            })
        }

        private fun cycleLanguage() {
            val all = QuickSearchTranslateLanguage.entries
            val cur = overrideLang ?: resolveTargetLanguage(lastQueryText, settings.language)
            overrideLang = all[(cur.ordinal + 1) % all.size]
            if (lastQueryText.isNotBlank()) {
                triggerFetch(lastQueryText, overrideLang!!)
            }
        }

        private fun onInputChanged(raw: String) {
            pendingRunnable?.let(mainHandler::removeCallbacks)
            pendingRunnable = null
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                dismissedQuery = null
                overrideLang = null
                lastQueryText = ""
                lastResult = null
                seq.incrementAndGet()
                cardContainer.visibility = View.GONE
                return
            }
            if (trimmed == dismissedQuery) return
            dismissedQuery = null

            val parsed = parseQuery(trimmed, settings)
            if (parsed == null) {
                seq.incrementAndGet()
                cardContainer.visibility = View.GONE
                return
            }
            lastQueryText = parsed.text
            expanded = settings.autoExpandAcademic
            val targetLang = overrideLang ?: parsed.overrideTargetLang
                ?: resolveTargetLanguage(parsed.text, settings.language)

            // 命中缓存直接零延迟渲染
            resultCache.get("${targetLang.code}|${parsed.text}")?.let { cached ->
                renderResult(cached)
                return
            }

            // 显式前缀立刻显示加载态，自动识别等防抖后再显示避免闪烁
            if (parsed.explicit) {
                renderLoading(parsed.text, targetLang)
            }
            val delayMs = if (parsed.explicit) 240L else 380L
            val runnable = Runnable { triggerFetch(parsed.text, targetLang) }
            pendingRunnable = runnable
            mainHandler.postDelayed(runnable, delayMs)
        }

        private fun triggerFetch(query: String, targetLang: QuickSearchTranslateLanguage) {
            val token = seq.incrementAndGet()
            renderLoading(query, targetLang)
            worker.execute {
                val res = runCatching { fetchTranslation(query, targetLang) }.getOrNull()
                mainHandler.post {
                    if (token != seq.get() || activity.isFinishing || activity.isDestroyed) return@post
                    if (res != null) {
                        renderResult(res)
                    } else {
                        renderError(query, targetLang)
                    }
                }
            }
        }

        private fun updateLocalAppButtons() {
            val showLocal = settings.showLocalAppShortcuts
            eudicBtn.visibility = if (showLocal && resolveEudicPackage(activity) != null) View.VISIBLE else View.GONE
            deeplBtn.visibility = if (showLocal && isPackageInstalled(activity, "com.deepl.mobiletranslator")) View.VISIBLE else View.GONE
        }

        private fun adjustScrollMaxHeight() {
            val maxH = dp(if (expanded) 340f else 190f)
            scrollView.post {
                val contentH = bodyColumn.height
                val lp = scrollView.layoutParams
                val targetH = if (contentH > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
                if (lp.height != targetH) {
                    lp.height = targetH
                    scrollView.layoutParams = lp
                }
            }
        }

        private fun renderLoading(query: String, targetLang: QuickSearchTranslateLanguage) {
            applyCardSurface()
            headerTitle.text = query
            langChip.text = "译→${targetLang.shortLabel} ▾"
            metaLine.visibility = View.GONE
            primaryView.text = "正在查询释义与翻译..."
            meaningsView.visibility = View.GONE
            academicView.visibility = View.GONE
            expandBtn.visibility = View.GONE
            copyBtn.visibility = View.GONE
            updateLocalAppButtons()
            cardContainer.visibility = View.VISIBLE
            cardContainer.bringToFront()
            adjustScrollMaxHeight()
        }

        private fun renderError(query: String, targetLang: QuickSearchTranslateLanguage) {
            headerTitle.text = query
            langChip.text = "译→${targetLang.shortLabel} ▾"
            metaLine.visibility = View.GONE
            primaryView.text = "暂未获取到在线释义，可直接点下方本机词典查阅"
            meaningsView.visibility = View.GONE
            academicView.visibility = View.GONE
            expandBtn.visibility = View.GONE
            copyBtn.visibility = View.GONE
            updateLocalAppButtons()
            cardContainer.visibility = View.VISIBLE
            cardContainer.bringToFront()
            adjustScrollMaxHeight()
        }

        private fun renderResult(res: TranslationResult) {
            lastResult = res
            applyCardSurface()
            val accentColor = if (isDark) Color.rgb(112, 180, 255) else Color.rgb(13, 132, 255)
            val tagColor = if (isDark) Color.rgb(142, 212, 168) else Color.rgb(24, 144, 78)

            headerTitle.text = res.query
            langChip.text = "译→${res.targetLang.shortLabel} ▾"

            // 音标 + 考试标签 + 引擎标识
            val metaParts = mutableListOf<String>()
            if (res.ukPhone.isNotBlank()) metaParts.add("英 /${res.ukPhone}/")
            if (res.usPhone.isNotBlank() && res.usPhone != res.ukPhone) metaParts.add("美 /${res.usPhone}/")
            if (res.ukPhone.isBlank() && res.usPhone.isBlank() && res.phoneticOther.isNotBlank()) {
                metaParts.add("/${res.phoneticOther}/")
            }
            if (res.examTags.isNotEmpty()) metaParts.add(res.examTags.joinToString(" · "))
            if (res.engineLabel.isNotBlank()) metaParts.add(res.engineLabel)
            if (metaParts.isNotEmpty()) {
                metaLine.text = metaParts.joinToString("  |  ")
                metaLine.visibility = View.VISIBLE
            } else {
                metaLine.visibility = View.GONE
            }

            // 首行主译文（当有多条词性释义且首行重复第一条时，直接展示完整多词性更清爽）
            val showPrimarySeparately = res.meanings.isEmpty() ||
                !res.meanings.first().let { "${it.pos} ${it.definition}".trim() }.equals(res.primaryTranslation.trim(), ignoreCase = true)
            if (showPrimarySeparately) {
                primaryView.text = res.primaryTranslation
                primaryView.visibility = View.VISIBLE
            } else {
                primaryView.visibility = View.GONE
            }

            // 多词性全部常见释义 + 核心专业学科释义（紧致模式展示全部词性与前 3 条专业释义）
            val mBuilder = SpannableStringBuilder()
            res.meanings.forEachIndexed { idx, m ->
                if (idx > 0) mBuilder.append("\n")
                if (m.pos.isNotBlank()) {
                    val start = mBuilder.length
                    mBuilder.append(m.pos).append(" ")
                    mBuilder.setSpan(StyleSpan(Typeface.BOLD), start, mBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    mBuilder.setSpan(ForegroundColorSpan(accentColor), start, mBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                mBuilder.append(m.definition)
            }
            val inlineSpecials = if (expanded) res.specialFields else res.specialFields.take(3)
            if (inlineSpecials.isNotEmpty()) {
                if (mBuilder.isNotEmpty()) mBuilder.append("\n")
                val labelStart = mBuilder.length
                mBuilder.append("专业释义：")
                mBuilder.setSpan(StyleSpan(Typeface.BOLD), labelStart, mBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                mBuilder.setSpan(ForegroundColorSpan(tagColor), labelStart, mBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                mBuilder.append(inlineSpecials.joinToString("；") { "[${it.major}] ${it.text}" })
            }
            if (mBuilder.isNotEmpty()) {
                meaningsView.text = mBuilder
                meaningsView.visibility = View.VISIBLE
            } else {
                meaningsView.visibility = View.GONE
            }

            // 展开后的完整学术详释：词形变化 + WordNet 英英释义 + 常用词组 + 同近义词 + 双语例句
            if (expanded && res.hasAcademicDetails) {
                val aBuilder = SpannableStringBuilder()
                fun appendSection(title: String, lines: List<String>) {
                    if (lines.isEmpty()) return
                    if (aBuilder.isNotEmpty()) aBuilder.append("\n\n")
                    val s = aBuilder.length
                    aBuilder.append(title)
                    aBuilder.setSpan(StyleSpan(Typeface.BOLD), s, aBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    aBuilder.setSpan(ForegroundColorSpan(accentColor), s, aBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    lines.forEach { aBuilder.append("\n").append(it) }
                }
                if (res.wordForms.isNotEmpty()) {
                    appendSection("词形变化", listOf(res.wordForms.joinToString("  ·  ")))
                }
                if (res.englishDefs.isNotEmpty()) {
                    appendSection(
                        "英英释义 (WordNet)",
                        res.englishDefs.map { if (it.pos.isNotBlank()) "• ${it.pos} ${it.definition}" else "• ${it.definition}" },
                    )
                }
                if (res.phrases.isNotEmpty()) {
                    appendSection("常见搭配", res.phrases.map { "• ${it.headword} — ${it.trans}" })
                }
                if (res.synonyms.isNotEmpty()) {
                    appendSection("同近义词", res.synonyms.map { "• $it" })
                }
                if (res.examples.isNotEmpty()) {
                    appendSection("双语例句", res.examples.flatMap { listOf("• ${it.source}", "  ${it.target}") })
                }
                academicView.text = aBuilder
                academicView.visibility = if (aBuilder.isNotEmpty()) View.VISIBLE else View.GONE
            } else {
                academicView.visibility = View.GONE
            }

            copyBtn.visibility = View.VISIBLE
            expandBtn.visibility = if (res.hasAcademicDetails) View.VISIBLE else View.GONE
            expandBtn.text = if (expanded) "收起详释" else "展开学术详释"
            updateLocalAppButtons()
            cardContainer.visibility = View.VISIBLE
            cardContainer.bringToFront()
            scrollView.scrollTo(0, 0)
            adjustScrollMaxHeight()
        }
    }
}
