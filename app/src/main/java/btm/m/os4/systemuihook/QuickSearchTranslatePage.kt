// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun QuickSearchTranslatePage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { QuickSearchTranslateSettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.settings) }
    var showCustomUrlDialog by remember { mutableStateOf(false) }
    var customUrlDraft by remember { mutableStateOf(settings.customSearchUrl) }

    LaunchedEffect(service) {
        service?.let { store.syncRemote(it) }
        settings = store.settings
    }
    fun update(transform: (QuickSearchTranslateSettings) -> QuickSearchTranslateSettings) {
        store.update(service, transform)
        settings = store.settings
    }
    val languages = QuickSearchTranslateLanguage.entries
    val engines = QuickSearchEngine.entries
    val browsers = QuickSearchBrowserMode.entries

    AppPage(
        tr("quick_search_translate_title", "下拉搜索增强"),
        back,
        restartScopes = setOf(ScopeApplication.QUICK_SEARCH_BOX),
        restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("实时翻译与学术词典", "实时翻译与学术词典")) {
                    SwitchPreference(
                        title = tr("启用下拉搜索翻译", "启用下拉搜索翻译"),
                        summary = tr("在系统下拉「搜索」输入框中实时查词与翻译，支持学术全释义与整句翻译。", "在系统下拉「搜索」输入框中实时查词与翻译，支持学术全释义与整句翻译。"),
                        checked = settings.enabled,
                        onCheckedChange = { v -> update { it.copy(enabled = v) } },
                    )
                    OverlayDropdownPreference(
                        title = tr("默认目标语言", "默认目标语言"),
                        items = languages.map { it.label },
                        selectedIndex = settings.language.ordinal,
                        onSelectedIndexChange = { index ->
                            update { it.copy(targetLanguage = languages[index].code) }
                        },
                    )
                    SwitchPreference(
                        title = tr("直接输入英文自动翻译", "直接输入英文自动翻译"),
                        summary = tr("开启后直接输入英文单词或句子即可出译文；关闭后仅在输入「翻译 / fy / tr」前缀时触发。", "开启后直接输入英文单词或句子即可出译文；关闭后仅在输入「翻译 / fy / tr」前缀时触发。"),
                        checked = settings.autoDetectEnglish,
                        onCheckedChange = { v -> update { it.copy(autoDetectEnglish = v) } },
                    )
                    SwitchPreference(
                        title = tr("默认展开学术详释", "默认展开学术详释"),
                        summary = tr("默认直接展开专业学科释义、WordNet 英英释义、词形变化、常用搭配与双语例句。", "默认直接展开专业学科释义、WordNet 英英释义、词形变化、常用搭配与双语例句。"),
                        checked = settings.autoExpandAcademic,
                        onCheckedChange = { v -> update { it.copy(autoExpandAcademic = v) } },
                    )
                    SwitchPreference(
                        title = tr("显示本机词典/翻译软件快捷入口", "显示本机词典/翻译软件快捷入口"),
                        summary = tr("自动识别已安装的欧路词典（悬浮查词）与 DeepL（迷你翻译），一键无缝接力深度查阅。", "自动识别已安装的欧路词典（悬浮查词）与 DeepL（迷你翻译），一键无缝接力深度查阅。"),
                        checked = settings.showLocalAppShortcuts,
                        onCheckedChange = { v -> update { it.copy(showLocalAppShortcuts = v) } },
                    )
                }
            }
            item {
                Group(tr("默认搜索引擎与浏览器", "默认搜索引擎与浏览器")) {
                    OverlayDropdownPreference(
                        title = tr("全网搜索引擎", "全网搜索引擎"),
                        items = engines.map { it.label },
                        selectedIndex = settings.engine.ordinal,
                        onSelectedIndexChange = { index ->
                            update { it.copy(searchEngine = engines[index].id) }
                        },
                    )
                    PortalAppearanceVisibility(settings.engine == QuickSearchEngine.CUSTOM) {
                        ArrowPreference(
                            title = tr("自定义搜索网址", "自定义搜索网址"),
                            summary = settings.customSearchUrl.ifBlank { "https://www.bing.com/search?q=%s" },
                            onClick = {
                                customUrlDraft = settings.customSearchUrl.ifBlank { "https://www.bing.com/search?q=%s" }
                                showCustomUrlDialog = true
                            },
                        )
                    }
                    OverlayDropdownPreference(
                        title = tr("打开搜索结果的浏览器", "打开搜索结果的浏览器"),
                        items = browsers.map { it.label },
                        selectedIndex = settings.browser.ordinal,
                        onSelectedIndexChange = { index ->
                            update { it.copy(browserMode = browsers[index].id) }
                        },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                    Text(
                        text = tr(
                            "quick_search_translate_usage",
                            "使用说明：\n" +
                                "• 需在 LSPosed 中勾选「搜索」(com.android.quicksearchbox) 作用域并重启搜索。\n" +
                                "• 显式翻译任意语言/句子：输入「翻译 内容」「译 内容」「fy 内容」「tr 内容」即可。\n" +
                                "• 智能中英互译：默认译为简体中文时，输入「翻译 特征值」会自动译为英文；卡片右上角支持一键切换目标语言。\n" +
                                "• 自定义搜索引擎：支持使用 %s 占位符（如 https://www.bing.com/search?q=%s），也可填任意搜索主页；选择 Microsoft Edge 后点击「搜索」或回车将直接拉起 Edge 搜索。",
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }
    }

    if (showCustomUrlDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showCustomUrlDialog = false },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr("自定义搜索网址", "自定义搜索网址"),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    tr("使用 %s 代表搜索关键词，例如：https://www.bing.com/search?q=%s 或 https://www.perplexity.ai/search?q=%s",
                        "使用 %s 代表搜索关键词，例如：https://www.bing.com/search?q=%s 或 https://www.perplexity.ai/search?q=%s"),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextField(
                    value = customUrlDraft,
                    onValueChange = { customUrlDraft = it.trim() },
                    label = tr("搜索网址模板或主页", "搜索网址模板或主页"),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    cornerRadius = 18.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(
                        onClick = { showCustomUrlDialog = false },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("取消", "取消")) }
                    GlassDialogButton(
                        onClick = {
                            val normalized = customUrlDraft.trim().ifBlank { "https://www.bing.com/search?q=%s" }
                            update { it.copy(customSearchUrl = normalized) }
                            showCustomUrlDialog = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(tr("保存", "保存"), fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}
