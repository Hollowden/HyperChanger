package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.util.WeakHashMap

internal const val DEFAULT_DEVICE_CARD_OPACITY = 55
internal const val DEVICE_CARD_OPACITY_KEY = "device_card_opacity"

internal fun SharedPreferences.deviceCardOpacity(): Int =
    runCatching { getInt(DEVICE_CARD_OPACITY_KEY, DEFAULT_DEVICE_CARD_OPACITY) }
        .getOrDefault(DEFAULT_DEVICE_CARD_OPACITY).coerceIn(0, 100)

internal fun SharedPreferences.Editor.putDeviceCardOpacity(opacity: Int): SharedPreferences.Editor =
    putInt(DEVICE_CARD_OPACITY_KEY, opacity.coerceIn(0, 100))

internal class DeviceCardOpacitySession(val root: View) {
    private data class Surface(val drawable: Drawable, val originalAlpha: Int)

    private val resources = root.resources
    private val packageName = root.context.packageName
    private val nameId = resources.getIdentifier("device_name_card_view", "id", packageName)
    private val cardIds = listOf(
        nameId,
        resources.getIdentifier("device_memory_card_view", "id", packageName),
        resources.getIdentifier("device_params", "id", packageName),
        resources.getIdentifier("device_guarantee_card_view", "id", packageName),
        resources.getIdentifier("device_mall_card_view", "id", packageName),
    ).filter { it != 0 }
    private val surfaces = WeakHashMap<View, Surface>()
    private val observer = root.viewTreeObserver
    private var opacity = 100
    private val listener = ViewTreeObserver.OnPreDrawListener {
        applyToCards()
        true
    }

    init {
        observer.addOnPreDrawListener(listener)
    }

    fun update(value: Int) {
        opacity = value
        applyToCards()
    }

    private fun applyToCards() {
        if (opacity == 100) {
            restore()
            return
        }
        for (id in cardIds) {
            root.findViewById<View>(id)?.let(::applyToCard)
        }
        val row = root.findViewById<View>(nameId)?.parent as? ViewGroup ?: return
        for (index in 0 until row.childCount) {
            val group = row.getChildAt(index)
            when (group) {
                is DeviceInfoCardsView -> for (cardIndex in 0 until group.childCount) {
                    applyToCard(group.getChildAt(cardIndex))
                }
            }
        }
    }

    private fun applyToCard(view: View) {
        val drawable = view.background ?: return
        val previous = surfaces[view]
        val surface = if (previous?.drawable === drawable) previous else {
            previous?.let { it.drawable.alpha = it.originalAlpha }
            Surface(drawable.mutate(), drawable.alpha).also { surfaces[view] = it }
        }
        val alpha = surface.originalAlpha * opacity / 100
        if (surface.drawable.alpha != alpha) surface.drawable.alpha = alpha
    }

    private fun restore() {
        surfaces.values.forEach { it.drawable.alpha = it.originalAlpha }
        surfaces.clear()
    }

    fun remove() {
        val activeObserver = if (observer.isAlive) observer else root.viewTreeObserver
        activeObserver.removeOnPreDrawListener(listener)
        restore()
    }
}
