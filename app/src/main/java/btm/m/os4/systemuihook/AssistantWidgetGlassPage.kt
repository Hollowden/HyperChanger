// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun AssistantWidgetGlassPage(back: () -> Unit) {
    val service by HookApplication.service.collectAsStateWithLifecycle()
    AppPage(tr("负一屏小部件柔光玻璃参数调整", "负一屏小部件柔光玻璃参数调整"), back,
        restartScopes = setOf(ScopeApplication.PERSONAL_ASSISTANT), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    UnifiedSoftGlassLinkRow(SoftGlassSlot.ASSISTANT_WIDGET)
                }
            }
        }
    }
}
