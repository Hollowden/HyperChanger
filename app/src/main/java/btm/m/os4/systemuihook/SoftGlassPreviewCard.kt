// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 柔光玻璃真机硬件级实时预览小模块。
 *
 * 直接调用澎湃 OS 4 原生 View.setMiGlass / setMiGlassBlurRadius / setMiBackgroundBlurMode 等接口，
 * 在多彩高对比测试底图上实时渲染当前 SoftGlassSettings（含全部 14 项光学参数与背景模糊/缩放/不透明度），
 * 支持手指拖动玻璃卡片观察实时边缘折射，支持切换「多彩 / 深色 / 浅色」底图以及一键折叠。
 */
@Composable
internal fun SoftGlassLivePreviewSection(
    settings: SoftGlassSettings,
    presetLabel: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var bgTheme by rememberSaveable { mutableIntStateOf(0) } // 0=多彩 1=深色 2=浅色
    val themeNames = listOf("多彩", "深色", "浅色")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (!presetLabel.isNullOrBlank()) "实时效果预览 · $presetLabel" else "柔光玻璃实时效果预览",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (expanded) {
                    Text(
                        text = "底图：${themeNames[bgTheme % themeNames.size]}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { bgTheme = (bgTheme + 1) % themeNames.size }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = if (expanded) "收起" else "展开预览",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (expanded) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(20.dp)),
                factory = { ctx -> SoftGlassLivePreviewHost(ctx) },
                update = { host ->
                    host.update(
                        settings = settings,
                        presetLabel = presetLabel,
                        bgTheme = bgTheme,
                    )
                },
            )
        }
    }
}

/**
 * 原生 Android 预览容器：底层绘制高对比彩色/明暗几何纹理，上层悬浮一块可自由拖拽的真 MiGlass 玻璃卡片。
 */
private class SoftGlassLivePreviewHost(context: Context) : FrameLayout(context) {
    private val density = context.resources.displayMetrics.density
    private val backdropView = PreviewPatternView(context)
    private val glassCard = FrameLayout(context)
    private val titleView = TextView(context)
    private val subView = TextView(context)
    private val glassBg = GradientDrawable()

    private var lastSettings: SoftGlassSettings? = null
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartTx = 0f
    private var dragStartTy = 0f

    init {
        // 不在外层 FrameLayout 上启 clipToOutline 离屏裁剪（改由外层 Compose .clip 圆角），
        // 避免 RenderEngine 截断同窗下层彩色底图采样。
        clipChildren = true
        clipToPadding = true

        // 1. 高对比底图（负责清晰展现模糊、缩放、折射畸变与边缘高光）
        addView(backdropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 2. 真实澎湃 OS 4 MiGlass 玻璃预览卡片
        val cardRadius = 20f * density
        glassBg.cornerRadius = cardRadius
        glassBg.setColor(Color.argb(1, 255, 255, 255))
        glassCard.background = glassBg
        glassCard.clipToOutline = true
        glassCard.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cardRadius)
            }
        }

        val contentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (16f * density).roundToInt()
            val padV = (12f * density).roundToInt()
            setPadding(padH, padV, padH, padV)
        }
        titleView.apply {
            text = "柔光玻璃实时预览"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.argb(90, 0, 0, 0))
        }
        subView.apply {
            textSize = 11.5f
            setTextColor(Color.argb(225, 255, 255, 255))
            setShadowLayer(3f, 0f, 1f, Color.argb(80, 0, 0, 0))
        }
        contentCol.addView(titleView)
        contentCol.addView(subView)
        glassCard.addView(contentCol, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val cardParams = LayoutParams((228f * density).roundToInt(), (74f * density).roundToInt(), Gravity.CENTER)
        addView(glassCard, cardParams)

        // 卡片布局完成或尺寸变化后重新注入 SDF 尺寸与 MiGlass 参数
        glassCard.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r > l && b > t && (r - l != or - ol || b - t != ob - ot)) {
                lastSettings?.let { applyMiGlassToCard(it) }
            }
        }
        glassCard.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post { lastSettings?.let { applyMiGlassToCard(it) } }
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })

        // 允许手指拖动玻璃卡片观察边缘实时折射，松手弹性回中（同时拦截父列表垂直滚动冲突）
        glassCard.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    v.animate().cancel()
                    dragDownRawX = event.rawX
                    dragDownRawY = event.rawY
                    dragStartTx = v.translationX
                    dragStartTy = v.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = ((width - v.width) * 0.5f - 6f * density).coerceAtLeast(0f)
                    val maxY = ((height - v.height) * 0.5f - 6f * density).coerceAtLeast(0f)
                    v.translationX = (dragStartTx + (event.rawX - dragDownRawX)).coerceIn(-maxX, maxX)
                    v.translationY = (dragStartTy + (event.rawY - dragDownRawY)).coerceIn(-maxY, maxY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    v.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(260L)
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    fun update(settings: SoftGlassSettings, presetLabel: String?, bgTheme: Int) {
        backdropView.setTheme(bgTheme)
        val normalized = settings.copy(enabled = true).normalized()
        val label = presetLabel?.takeIf { it.isNotBlank() } ?: "当前参数"
        titleView.text = label
        val refDelta = normalized.adjustments["refraction"] ?: 0
        subView.text = "拖动卡片看折射 · 模糊 ${normalized.refractionBlur}% · 背景 ${normalized.backgroundBlur}% · 折射 ${if (refDelta >= 0) "+$refDelta" else "$refDelta"}%"

        // 浅色底图下自动加深一点文字阴影保证清晰
        val textColor = if (bgTheme == 2) Color.argb(245, 28, 32, 42) else Color.WHITE
        val subColor = if (bgTheme == 2) Color.argb(200, 36, 42, 54) else Color.argb(225, 255, 255, 255)
        val shadowColor = if (bgTheme == 2) Color.argb(60, 255, 255, 255) else Color.argb(95, 0, 0, 0)
        titleView.setTextColor(textColor)
        subView.setTextColor(subColor)
        titleView.setShadowLayer(4f, 0f, 1f, shadowColor)
        subView.setShadowLayer(3f, 0f, 1f, shadowColor)

        if (lastSettings != normalized) {
            lastSettings = normalized
            applyMiGlassToCard(normalized)
        }
    }

    private fun applyMiGlassToCard(settings: SoftGlassSettings) {
        runCatching {
            val viewCls = View::class.java
            val intType = Int::class.javaPrimitiveType
            val boolType = Boolean::class.javaPrimitiveType
            val floatType = Float::class.javaPrimitiveType

            // 1. 严格对齐 HyperLight 与系统同窗自采样管线：
            //    setPassWindowBlurEnabled(true) + setMiViewBlurMode(1) + setMiBackgroundBlurMode(1) + type=0，
            //    否则 RenderEngine 会断掉背景采样输入导致卡片变深灰！
            runCatching { viewCls.getMethod("disableMiBackgroundContainBelow", boolType).invoke(this, false) }
            runCatching { viewCls.getMethod("disableMiBackgroundContainBelow", boolType).invoke(glassCard, false) }
            runCatching { viewCls.getMethod("setMiBackgroundBlurType", intType).invoke(glassCard, 0) }
            runCatching { viewCls.getMethod("setPassWindowBlurEnabled", boolType).invoke(glassCard, true) }
            viewCls.getMethod("setMiViewBlurMode", intType).invoke(glassCard, 1)
            viewCls.getMethod("setMiBackgroundBlurMode", intType).invoke(glassCard, 1)
            viewCls.getMethod("setMiBackgroundBlurRadius", intType)
                .invoke(glassCard, softGlassBlur(80, settings.backgroundBlur))
            runCatching {
                viewCls.getMethod("setMiBackgroundBlurScaleRatio", floatType)
                    .invoke(glassCard, softGlassScale(0.075f, settings.backgroundScale))
            }
            runCatching {
                viewCls.getMethod("clearMiBackgroundBlendColor").invoke(glassCard)
                val alpha = softGlassBlur(26, settings.backgroundOpacity).coerceIn(0, 255)
                if (alpha > 0) {
                    viewCls.getMethod("addMiBackgroundBlendColor", intType, intType)
                        .invoke(glassCard, Color.argb(alpha, 255, 255, 255), 101)
                }
            }

            // 2. 应用澎湃 OS 4 原生 42 槽位 MiGlass 光学参数（与系统真实渲染完全一致），并触发即时重绘
            viewCls.getMethod("setMiGlassBlurRadius", intType, intType).invoke(
                glassCard,
                softGlassBlur(36, settings.refractionBlur),
                softGlassBlur(72, settings.refractionBlur),
            )
            viewCls.getMethod("setMiViewMaterialType", intType).invoke(glassCard, 1)
            if (glassCard.width > 0 && glassCard.height > 0) {
                runCatching {
                    viewCls.getMethod("setMiGlassSdfMaxSize", floatType, floatType)
                        .invoke(glassCard, glassCard.width.toFloat(), glassCard.height.toFloat())
                }
            }
            viewCls.getMethod("setMiGlass", FloatArray::class.java)
                .invoke(glassCard, tuneSoftGlass(PREVIEW_BASELINE_GLASS, settings, standaloneBackground = true))
            glassCard.invalidate()
            backdropView.invalidate()
            invalidate()
        }
    }

    companion object {
        // 与 PortalGlassMaterial / 系统悬浮玻璃一致的标准 42 槽位基准光学配方
        private val PREVIEW_BASELINE_GLASS = floatArrayOf(
            .67f, .16f, .09f, 0f, .24f, 1.4f, -.02f, .3f, .6f, 1f,
            .03f, 1f, 1f, 1f, .1f, .2f, .3f, 1f, 1f, 72f, 3.8f, 80f, 800f,
            1.2f, 1f, -.4f, .6f, -.8f, 1.4f, .7f, .8f, 1.15f, 4f, 2f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )
    }
}

/**
 * 多彩/深色/浅色三套高对比预览画布：包含柔和光斑、斜向网格线条与锐利色彩圆点，
 * 能够清晰反映玻璃折射弯曲、边缘高光、羽化反射、模糊与亮度变化。
 */
private class PreviewPatternView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var theme = 0

    fun setTheme(next: Int) {
        val clamped = next % 3
        if (theme == clamped) return
        theme = clamped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. 渐变底色
        val gradColors = when (theme) {
            1 -> intArrayOf(0xFF0E1324.toInt(), 0xFF1A1038.toInt(), 0xFF0A2438.toInt())
            2 -> intArrayOf(0xFFEAF4FF.toInt(), 0xFFFFEEF6.toInt(), 0xFFE9FBF2.toInt())
            else -> intArrayOf(0xFF2E6BFF.toInt(), 0xFF8B4DFF.toInt(), 0xFFFF5E9A.toInt())
        }
        bgPaint.shader = LinearGradient(0f, 0f, w, h, gradColors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. 高饱和彩色光斑（用于观察折射与饱和度）
        val orb1 = when (theme) {
            1 -> 0xCC00E5FF.toInt()
            2 -> 0xBB56A8FF.toInt()
            else -> 0xDD00F0FF.toInt()
        }
        val orb2 = when (theme) {
            1 -> 0xCCFF3D9A.toInt()
            2 -> 0xBBFF7EB3.toInt()
            else -> 0xDDFFD24C.toInt()
        }
        val orb3 = when (theme) {
            1 -> 0xAA7C4DFF.toInt()
            2 -> 0xAA4BE0A8.toInt()
            else -> 0xCCFF4D79.toInt()
        }
        drawOrb(canvas, w * 0.22f, h * 0.30f, h * 0.56f, orb1)
        drawOrb(canvas, w * 0.78f, h * 0.68f, h * 0.62f, orb2)
        drawOrb(canvas, w * 0.54f, h * 0.18f, h * 0.44f, orb3)

        // 3. 锐利高对比网格与彩色条纹（玻璃滑过时可一眼看清折射率与模糊程度）
        linePaint.color = when (theme) {
            1 -> Color.argb(55, 255, 255, 255)
            2 -> Color.argb(65, 30, 50, 90)
            else -> Color.argb(72, 255, 255, 255)
        }
        val step = 22f * density
        var x = step * 0.5f
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, linePaint)
            x += step
        }
        var y = step * 0.5f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, linePaint)
            y += step
        }

        // 4. 几个高对比实心色块（方便在玻璃边缘清晰观察色散/折射畸变）
        val chips = when (theme) {
            1 -> intArrayOf(0xFF00E5FF.toInt(), 0xFFFF4081.toInt(), 0xFFFFD740.toInt(), 0xFF69F0AE.toInt())
            2 -> intArrayOf(0xFF2979FF.toInt(), 0xFFFF4081.toInt(), 0xFFFF9100.toInt(), 0xFF00C853.toInt())
            else -> intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF00E5FF.toInt(), 0xFF18FFFF.toInt())
        }
        val r = 10f * density
        val positions = arrayOf(
            w * 0.16f to h * 0.72f,
            w * 0.38f to h * 0.26f,
            w * 0.64f to h * 0.76f,
            w * 0.84f to h * 0.28f,
        )
        positions.forEachIndexed { idx, (cx, cy) ->
            pillPaint.color = chips[idx % chips.size]
            canvas.drawCircle(cx, cy, r, pillPaint)
        }
    }

    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, centerColor: Int) {
        orbPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            centerColor,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, orbPaint)
    }
}
