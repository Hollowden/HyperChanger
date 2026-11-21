// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.Field
import java.lang.reflect.Method

internal class SettingsPreferenceCardOpacitySession(
    val root: View,
    private val devicePage: Boolean,
) {
    private val recyclerId = root.resources.getIdentifier(
        if (devicePage) "recycler_view" else "scroll_headers",
        "id",
        root.context.packageName,
    )
    private val decorationClass = if (devicePage) {
        "miuix.preference.PreferenceFragment\$FrameDecoration"
    } else {
        "miuix.recyclerview.card.CardItemDecoration"
    }
    private val drawableFieldName = if (devicePage) "mCardGroupBackground" else "mGroupDrawable"
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

    private fun applyOpacity() {
        val scope = if (devicePage) root.findViewById<View>(scopeId) else root
        val list = scope?.findViewById<View>(recyclerId)
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
        val alpha = originalAlpha * opacity / 100
        if (surface.alpha != alpha) {
            surface.alpha = alpha
            list?.invalidate()
        }
    }

    private fun findCardBackground(list: View): Drawable? = runCatching {
        val count = decorationCount?.invoke(list) as? Int ?: return null
        for (index in 0 until count) {
            val decoration = decorationAt?.invoke(list, index) ?: continue
            if (decoration.javaClass.name != decorationClass) continue
            val field = backgroundField ?: decoration.javaClass.getDeclaredField(drawableFieldName).apply {
                isAccessible = true
                backgroundField = this
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
