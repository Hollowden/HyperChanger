// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

internal class SettingsPreferenceCardOpacitySession(
    val root: View,
    private val devicePage: Boolean,
) {
    private val homeRecyclerId = root.resources.getIdentifier("scroll_headers", "id", root.context.packageName)
    private val subRecyclerId = root.resources.getIdentifier("recycler_view", "id", root.context.packageName)
    private val scopeId = if (devicePage) {
        root.resources.getIdentifier("prefs_container", "id", root.context.packageName)
    } else {
        0
    }
    private var recycler: View? = null
    private var decorationCount: Method? = null
    private var decorationAt: Method? = null
    private var backgroundField: Field? = null
    private var background: Drawable? = null
    private var originalAlpha = 255
    private var opacity = 100
    private val observer = root.viewTreeObserver
    private val listener = ViewTreeObserver.OnPreDrawListener {
        applyOpacity()
        true
    }

    init {
        observer.addOnPreDrawListener(listener)
    }

    fun update(value: Int) {
        opacity = value
        applyOpacity()
    }

    private fun isLightMode(): Boolean =
        root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

    private fun findRecycler(): View? {
        if (devicePage) {
            val scope = root.findViewById<View>(scopeId)
            return scope?.findViewById(subRecyclerId)
        }
        // 兼容设置主页（scroll_headers）与各设置子页（recycler_view）
        return (if (homeRecyclerId != 0) root.findViewById<View>(homeRecyclerId) else null)
            ?: (if (subRecyclerId != 0) root.findViewById<View>(subRecyclerId) else null)
    }

    private fun applyOpacity() {
        val list = findRecycler()
        if (list !== recycler) {
            restore()
            recycler = list
            decorationCount = list?.let { runCatching { it.javaClass.getMethod("getItemDecorationCount") }.getOrNull() }
            decorationAt = list?.let { runCatching { it.javaClass.getMethod("getItemDecorationAt", Int::class.javaPrimitiveType) }.getOrNull() }
            backgroundField = null
        }
        val drawable = list?.let(::findCardBackground)
        if (drawable !== background) {
            restore()
            if (drawable != null) {
                background = drawable.mutate()
                originalAlpha = drawable.alpha
            }
        }
        val surface = background ?: return
        // 白天模式下 MIUI 原生纯白卡片即使 55% 不透明度也会形成沉重大白块；
        // 对非「我的设备」的设置页按 0.28x 轻透收敛，保留温和分组感同时呈现类似深色模式的通透背景视觉。
        val effectiveOpacity = if (!devicePage && isLightMode() && opacity < 100) {
            (opacity * 0.28f).roundToInt().coerceIn(0, 100)
        } else {
            opacity
        }
        val alpha = originalAlpha * effectiveOpacity / 100
        if (surface.alpha != alpha) {
            surface.alpha = alpha
            list?.invalidate()
        }
    }

    private fun findCardBackground(list: View): Drawable? = runCatching {
        val count = decorationCount?.invoke(list) as? Int ?: return null
        for (index in 0 until count) {
            val decoration = decorationAt?.invoke(list, index) ?: continue
            val clsName = decoration.javaClass.name
            val fieldName = when (clsName) {
                "miuix.recyclerview.card.CardItemDecoration" -> "mGroupDrawable"
                "miuix.preference.PreferenceFragment\$FrameDecoration" -> "mCardGroupBackground"
                else -> continue
            }
            val field = if (backgroundField?.declaringClass == decoration.javaClass) {
                backgroundField!!
            } else {
                decoration.javaClass.getDeclaredField(fieldName).apply {
                    isAccessible = true
                    backgroundField = this
                }
            }
            return field.get(decoration) as? Drawable
        }
        null
    }.getOrNull()

    private fun restore() {
        background?.let { surface ->
            if (surface.alpha != originalAlpha) {
                surface.alpha = originalAlpha
                recycler?.invalidate()
            }
        }
        background = null
    }

    fun remove() {
        val activeObserver = if (observer.isAlive) observer else root.viewTreeObserver
        activeObserver.removeOnPreDrawListener(listener)
        restore()
    }
}
