// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import btm.m.os4.systemuihook.newnotificationcenter.IosNotificationLayoutSettingsStore
import io.github.libxposed.service.XposedService

internal const val KEY_SOFT_GLASS_UNIFIED_MODE = "soft_glass_unified_mode_v1"
private const val KEY_SOFT_GLASS_SLOT_PLAN_PREFIX = "soft_glass_slot_plan_v1_"

internal const val SOFT_GLASS_PLAN_SYSTEM_DEFAULT = "system_default"
internal const val SOFT_GLASS_PLAN_GLOBAL = "global"

/** 用于任意子页面一键跳转至统一「柔光玻璃参数调整」中心。 */
internal val LocalOpenSoftGlassPage = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 全局柔光玻璃材质策略模式。 */
internal enum class SoftGlassUnifiedMode(val title: String, val summary: String) {
    SYSTEM_DEFAULT(
        "全体使用系统默认柔光玻璃",
        "关闭全部位置的自定义柔光覆盖，恢复系统默认柔光材质，功耗最低。",
    ),
    GLOBAL_PRESET(
        "全体统一使用当前预设",
        "全系统所有柔光玻璃位置统一跟随下方选中的柔光玻璃预设与参数。",
    ),
    PER_SLOT_CUSTOM(
        "按位置分配方案（支持多预设与全选）",
        "可为每个位置分别指派「系统默认」「当前全局预设」或任意内置/自定义预设，并支持一键全选。",
    ),
}

/** 所有已接入柔光玻璃材质体系的系统界面与组件位置。 */
internal enum class SoftGlassSlot(
    val key: String,
    val title: String,
    val group: String,
    val supportsPerPreset: Boolean = true,
) {
    SHADE_CORE("shade_core", "通知中心 / 控制中心 / 悬浮通知", "系统界面"),
    IOS_NOTIFICATION_CLOCK("ios_clock", "iOS 通知中心大时钟", "系统界面"),
    CONTROL_CENTER_TOP_BUTTONS("cc_top_buttons", "控制中心顶部按钮", "系统界面"),
    EXPANDED_ISLAND("expanded_island", "超级岛展开态", "系统界面"),
    VOLUME_PANEL("volume_panel", "音量面板", "系统界面", supportsPerPreset = false),
    LOCKSCREEN_SHORTCUT("lockscreen_shortcut", "锁屏与通知中心快捷按钮", "锁屏与交互"),
    LOCKSCREEN_CAPSULE("lockscreen_capsule", "锁屏音乐胶囊", "锁屏与交互"),
    SIDEBAR("sidebar", "全局侧边栏", "桌面与辅助"),
    HOME_DESKTOP("home_desktop", "桌面全局", "桌面与辅助"),
    HOME_DOCK("home_dock", "桌面 Dock 栏", "桌面与辅助"),
    ASSISTANT_WIDGET("assistant_widget", "负一屏小部件", "桌面与辅助"),
    PORTAL("portal", "传送门", "桌面与辅助"),
}

internal data class SoftGlassUnifiedSnapshot(
    val mode: SoftGlassUnifiedMode,
    val slotPlans: Map<SoftGlassSlot, String>,
    val presets: List<SoftGlassPreset>,
    val appliedPresetId: String?,
) {
    fun effectivePlan(slot: SoftGlassSlot): String = when (mode) {
        SoftGlassUnifiedMode.SYSTEM_DEFAULT -> SOFT_GLASS_PLAN_SYSTEM_DEFAULT
        SoftGlassUnifiedMode.GLOBAL_PRESET -> SOFT_GLASS_PLAN_GLOBAL
        SoftGlassUnifiedMode.PER_SLOT_CUSTOM -> slotPlans[slot] ?: SOFT_GLASS_PLAN_SYSTEM_DEFAULT
    }

    fun describeSlot(slot: SoftGlassSlot): String {
        val plan = effectivePlan(slot)
        val globalPresetName = presets.firstOrNull { it.id == appliedPresetId }?.name
        return when (plan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> "系统默认柔光玻璃"
            SOFT_GLASS_PLAN_GLOBAL -> if (globalPresetName != null) {
                "跟随全局预设（$globalPresetName）"
            } else {
                "跟随当前全局参数"
            }
            else -> presets.firstOrNull { it.id == plan }?.let { "独立预设：${it.name}" }
                ?: "跟随当前全局参数"
        }
    }
}

internal object SoftGlassUnifiedPolicy {
    fun readMode(p: SharedPreferences): SoftGlassUnifiedMode {
        if (p.contains(KEY_SOFT_GLASS_UNIFIED_MODE)) {
            val ordinal = p.getInt(KEY_SOFT_GLASS_UNIFIED_MODE, 0)
            return SoftGlassUnifiedMode.entries.getOrElse(ordinal) { SoftGlassUnifiedMode.SYSTEM_DEFAULT }
        }
        // 兼容老配置：若全局柔光已开启则默认归入「全体统一使用当前预设」，否则归入「系统默认」
        return if (p.getBoolean(KEY_SOFT_GLASS_ENABLED, false)) {
            SoftGlassUnifiedMode.GLOBAL_PRESET
        } else {
            SoftGlassUnifiedMode.SYSTEM_DEFAULT
        }
    }

    fun readSlotPlan(p: SharedPreferences, slot: SoftGlassSlot): String {
        val stored = p.getString(KEY_SOFT_GLASS_SLOT_PLAN_PREFIX + slot.key, null)
        if (!stored.isNullOrBlank()) return stored
        // 首次迁移：按当前底层开关推断默认方案，避免破坏老用户配置
        return when (slot) {
            SoftGlassSlot.SHADE_CORE ->
                if (p.getBoolean(KEY_SOFT_GLASS_ENABLED, false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.IOS_NOTIFICATION_CLOCK ->
                if (p.getBoolean("ios_notification_clock_glass_enabled", false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.CONTROL_CENTER_TOP_BUTTONS ->
                if (p.getBoolean(CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + "use_global", false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.EXPANDED_ISLAND ->
                if (p.getBoolean(EXPANDED_ISLAND_GLASS_PREFIX + "use_global", false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.VOLUME_PANEL ->
                if (p.getBoolean(KEY_VOLUME_PANEL_GLOBAL_GLASS, false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.LOCKSCREEN_SHORTCUT ->
                if (p.getBoolean(KEY_LOCKSCREEN_SHORTCUT_USE_GLOBAL_GLASS, true)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.LOCKSCREEN_CAPSULE ->
                if (p.getBoolean(LOCKSCREEN_CAPSULE_GLASS_PREFIX + "use_global", false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.SIDEBAR ->
                if (p.getBoolean("${SIDEBAR_PREFIX}enabled", false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.HOME_DESKTOP ->
                if (p.getBoolean("home_full_glass_v1_use_global", false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.HOME_DOCK ->
                if (p.getInt("dock_full_glass_v1_source", 0) != 0) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.ASSISTANT_WIDGET ->
                if (p.getBoolean("${ASSISTANT_WIDGET_GLASS_PREFIX}enabled", false)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
            SoftGlassSlot.PORTAL ->
                if (p.getBoolean(KEY_PORTAL_USE_GLOBAL_GLASS, true)) SOFT_GLASS_PLAN_GLOBAL else SOFT_GLASS_PLAN_SYSTEM_DEFAULT
        }
    }

    fun readSnapshot(context: Context): SoftGlassUnifiedSnapshot {
        val prefs = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val store = SoftGlassSettingsStore(context)
        val presets = builtInSoftGlassPresets() + store.presets
        val mode = readMode(prefs)
        val plans = SoftGlassSlot.entries.associateWith { readSlotPlan(prefs, it) }
        return SoftGlassUnifiedSnapshot(
            mode = mode,
            slotPlans = plans,
            presets = presets,
            appliedPresetId = store.appliedPresetId,
        )
    }

    fun setMode(context: Context, service: XposedService?, mode: SoftGlassUnifiedMode) {
        val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        local.edit().putInt(KEY_SOFT_GLASS_UNIFIED_MODE, mode.ordinal).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putInt(KEY_SOFT_GLASS_UNIFIED_MODE, mode.ordinal)?.apply()
        applyAll(context, service)
    }

    fun setSlotPlan(context: Context, service: XposedService?, slot: SoftGlassSlot, plan: String) {
        val normalized = if (!slot.supportsPerPreset && plan != SOFT_GLASS_PLAN_SYSTEM_DEFAULT) {
            SOFT_GLASS_PLAN_GLOBAL
        } else {
            plan
        }
        val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        local.edit()
            .putInt(KEY_SOFT_GLASS_UNIFIED_MODE, SoftGlassUnifiedMode.PER_SLOT_CUSTOM.ordinal)
            .putString(KEY_SOFT_GLASS_SLOT_PLAN_PREFIX + slot.key, normalized)
            .apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putInt(KEY_SOFT_GLASS_UNIFIED_MODE, SoftGlassUnifiedMode.PER_SLOT_CUSTOM.ordinal)
            ?.putString(KEY_SOFT_GLASS_SLOT_PLAN_PREFIX + slot.key, normalized)
            ?.apply()
        applyAll(context, service)
    }

    fun setAllSlots(context: Context, service: XposedService?, plan: String) {
        val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val edit: (SharedPreferences) -> Unit = { p ->
            p.edit().apply {
                putInt(KEY_SOFT_GLASS_UNIFIED_MODE, SoftGlassUnifiedMode.PER_SLOT_CUSTOM.ordinal)
                SoftGlassSlot.entries.forEach { slot ->
                    val slotPlan = if (!slot.supportsPerPreset && plan != SOFT_GLASS_PLAN_SYSTEM_DEFAULT) {
                        SOFT_GLASS_PLAN_GLOBAL
                    } else {
                        plan
                    }
                    putString(KEY_SOFT_GLASS_SLOT_PLAN_PREFIX + slot.key, slotPlan)
                }
            }.apply()
        }
        edit(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(edit)
        applyAll(context, service)
    }

    /** 将统一的柔光玻璃策略与各位置预设分配同步落盘到所有底层组件配置与桌面原生配置。 */
    fun applyAll(context: Context, service: XposedService?) {
        val snapshot = readSnapshot(context)
        val presetById = snapshot.presets.associateBy { it.id }
        val glassStore = SoftGlassSettingsStore(context)
        val defaultGlass = SoftGlassSettings().resetParameters()

        fun resolvePreset(plan: String): SoftGlassPreset? =
            if (plan == SOFT_GLASS_PLAN_SYSTEM_DEFAULT || plan == SOFT_GLASS_PLAN_GLOBAL) null
            else presetById[plan]

        // 1. 通知中心 / 控制中心 / 悬浮通知（核心全局柔光管线）
        val shadePlan = snapshot.effectivePlan(SoftGlassSlot.SHADE_CORE)
        when (shadePlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> glassStore.update(service) { it.copy(enabled = false) }
            SOFT_GLASS_PLAN_GLOBAL -> glassStore.update(service) { it.copy(enabled = true) }
            else -> {
                val preset = resolvePreset(shadePlan)
                if (preset != null) {
                    glassStore.update(service) { preset.settings.copy(enabled = true) }
                } else {
                    glassStore.update(service) { it.copy(enabled = true) }
                }
            }
        }

        // 2. iOS 通知中心大时钟
        val clockPlan = snapshot.effectivePlan(SoftGlassSlot.IOS_NOTIFICATION_CLOCK)
        val clockStore = IosNotificationLayoutSettingsStore(context)
        when (clockPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> clockStore.updateClock(service) { it.copy(enabled = false, useGlobal = false) }
            SOFT_GLASS_PLAN_GLOBAL -> clockStore.updateClock(service) { it.copy(enabled = true, useGlobal = true) }
            else -> {
                val preset = resolvePreset(clockPlan)
                clockStore.updateClock(service) {
                    if (preset != null) it.copy(enabled = true, useGlobal = false, glass = preset.settings.copy(enabled = true))
                    else it.copy(enabled = true, useGlobal = true)
                }
            }
        }

        // 3. 控制中心顶部按钮
        val ccPlan = snapshot.effectivePlan(SoftGlassSlot.CONTROL_CENTER_TOP_BUTTONS)
        val ccStore = ControlCenterTopButtonsGlassSettingsStore(context)
        when (ccPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> ccStore.update(service) { it.copy(useGlobal = false, glass = defaultGlass) }
            SOFT_GLASS_PLAN_GLOBAL -> ccStore.update(service) { it.copy(useGlobal = true) }
            else -> {
                val preset = resolvePreset(ccPlan)
                ccStore.update(service) {
                    if (preset != null) it.copy(useGlobal = false, glass = preset.settings.copy(enabled = true))
                    else it.copy(useGlobal = true)
                }
            }
        }

        // 4. 超级岛展开态
        val islandPlan = snapshot.effectivePlan(SoftGlassSlot.EXPANDED_ISLAND)
        val islandStore = ExpandedIslandGlassSettingsStore(context)
        when (islandPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> islandStore.update(service) { it.copy(useGlobal = false, glass = defaultGlass) }
            SOFT_GLASS_PLAN_GLOBAL -> islandStore.update(service) { it.copy(useGlobal = true) }
            else -> {
                val preset = resolvePreset(islandPlan)
                islandStore.update(service) {
                    if (preset != null) it.copy(useGlobal = false, glass = preset.settings.copy(enabled = true))
                    else it.copy(useGlobal = true)
                }
            }
        }

        // 5. 音量面板
        val volPlan = snapshot.effectivePlan(SoftGlassSlot.VOLUME_PANEL)
        VolumePanelGlassSettingsStore(context).update(service, volPlan != SOFT_GLASS_PLAN_SYSTEM_DEFAULT)

        // 6. 锁屏与通知中心快捷按钮
        val shortcutPlan = snapshot.effectivePlan(SoftGlassSlot.LOCKSCREEN_SHORTCUT)
        val shortcutStore = LockscreenShortcutBackgroundSettingsStore(context)
        when (shortcutPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> shortcutStore.update(service) { it.copy(useGlobalGlass = false, glass = defaultGlass) }
            SOFT_GLASS_PLAN_GLOBAL -> shortcutStore.update(service) { it.copy(useGlobalGlass = true) }
            else -> {
                val preset = resolvePreset(shortcutPlan)
                shortcutStore.update(service) {
                    if (preset != null) it.copy(useGlobalGlass = false, glass = preset.settings.copy(enabled = true))
                    else it.copy(useGlobalGlass = true)
                }
            }
        }

        // 7. 锁屏音乐胶囊
        val capsulePlan = snapshot.effectivePlan(SoftGlassSlot.LOCKSCREEN_CAPSULE)
        val capsuleStore = LockscreenCapsuleGlassSettingsStore(context)
        when (capsulePlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> capsuleStore.update(service) { it.copy(useGlobal = false, glass = defaultGlass) }
            SOFT_GLASS_PLAN_GLOBAL -> capsuleStore.update(service) { it.copy(useGlobal = true) }
            else -> {
                val preset = resolvePreset(capsulePlan)
                capsuleStore.update(service) {
                    if (preset != null) it.copy(useGlobal = false, glass = preset.settings.copy(enabled = true))
                    else it.copy(useGlobal = true)
                }
            }
        }

        // 8. 全局侧边栏
        val sidebarPlan = snapshot.effectivePlan(SoftGlassSlot.SIDEBAR)
        val sidebarStore = SidebarSettingsStore(context)
        when (sidebarPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> sidebarStore.update(service) { it.copy(useGlobal = false, glass = it.glass.copy(enabled = false)) }
            SOFT_GLASS_PLAN_GLOBAL -> sidebarStore.update(service) { it.copy(useGlobal = true, glass = it.glass.copy(enabled = true)) }
            else -> {
                val preset = resolvePreset(sidebarPlan)
                sidebarStore.update(service) {
                    if (preset != null) it.copy(useGlobal = false, glass = preset.settings.copy(enabled = true))
                    else it.copy(useGlobal = true, glass = it.glass.copy(enabled = true))
                }
            }
        }

        // 9. 桌面全局 & 10. 桌面 Dock 栏
        val homeStore = HomeGlassSettingsStore(context)
        val desktopPlan = snapshot.effectivePlan(SoftGlassSlot.HOME_DESKTOP)
        when (desktopPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> homeStore.update(service, dock = false) { it.copy(useGlobal = false, glass = defaultGlass) }
            SOFT_GLASS_PLAN_GLOBAL -> homeStore.update(service, dock = false) { it.copy(useGlobal = true) }
            else -> {
                val preset = resolvePreset(desktopPlan)
                homeStore.update(service, dock = false) {
                    if (preset != null) it.copy(useGlobal = false, glass = preset.settings.copy(enabled = true))
                    else it.copy(useGlobal = true)
                }
            }
        }
        val dockPlan = snapshot.effectivePlan(SoftGlassSlot.HOME_DOCK)
        when (dockPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> homeStore.update(service, dock = true) { it.copy(dockSource = DockGlassParameterSource.SYSTEM_DEFAULT) }
            SOFT_GLASS_PLAN_GLOBAL -> homeStore.update(service, dock = true) { it.copy(dockSource = DockGlassParameterSource.GLOBAL) }
            else -> {
                val preset = resolvePreset(dockPlan)
                homeStore.update(service, dock = true) {
                    if (preset != null) it.copy(dockSource = DockGlassParameterSource.CUSTOM, glass = preset.settings.copy(enabled = true))
                    else it.copy(dockSource = DockGlassParameterSource.GLOBAL)
                }
            }
        }
        runCatching {
            val prefs = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
            stageGlobalHomeGlass(context)
            stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(prefs))
        }

        // 11. 负一屏小部件
        val widgetPlan = snapshot.effectivePlan(SoftGlassSlot.ASSISTANT_WIDGET)
        val widgetStore = WidgetGlassSettingsStore(context)
        when (widgetPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> widgetStore.update(service) { it.copy(useGlobal = false, glass = it.glass.copy(enabled = false)) }
            SOFT_GLASS_PLAN_GLOBAL -> widgetStore.update(service) { it.copy(useGlobal = true, glass = it.glass.copy(enabled = true)) }
            else -> {
                val preset = resolvePreset(widgetPlan)
                widgetStore.update(service) {
                    if (preset != null) it.copy(useGlobal = false, glass = preset.settings.copy(enabled = true))
                    else it.copy(useGlobal = true, glass = it.glass.copy(enabled = true))
                }
            }
        }

        // 12. 传送门
        val portalPlan = snapshot.effectivePlan(SoftGlassSlot.PORTAL)
        val prefs = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val portalCur = PortalSettings.read(prefs)
        val portalNext = when (portalPlan) {
            SOFT_GLASS_PLAN_SYSTEM_DEFAULT -> portalCur.copy(useGlobalGlass = false, glass = defaultGlass)
            SOFT_GLASS_PLAN_GLOBAL -> portalCur.copy(useGlobalGlass = true)
            else -> {
                val preset = resolvePreset(portalPlan)
                if (preset != null) portalCur.copy(useGlobalGlass = false, glass = preset.settings.copy(enabled = true))
                else portalCur.copy(useGlobalGlass = true)
            }
        }
        PortalSettings.save(context, service, portalNext)
    }
}

/** 各分散子页面统一替换为一行简洁入口：展示该位置当前柔光材质方案，点击直达「柔光玻璃参数调整」中心。 */
@Composable
internal fun UnifiedSoftGlassLinkRow(slot: SoftGlassSlot) {
    val context = LocalContext.current
    val openSoftGlass = LocalOpenSoftGlassPage.current
    var snapshot by remember(context) { mutableStateOf(SoftGlassUnifiedPolicy.readSnapshot(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = SoftGlassUnifiedPolicy.readSnapshot(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SharedViewArrowPreference(
        title = tr("柔光玻璃材质方案", "柔光玻璃材质方案"),
        summary = "${snapshot.describeSlot(slot)} · 点击前往统一调整",
        onClick = { openSoftGlass?.invoke() },
    )
}
