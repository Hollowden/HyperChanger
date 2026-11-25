// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs

internal const val STATUS_BAR_POWER_TEMP_OFF = 0
internal const val STATUS_BAR_POWER_TEMP_ABOVE_CLOCK = 2

/**
 * 状态栏左上角时间上方常驻实时电池温度与功率显示。
 *
 * 功耗控制与排版设计：
 * - 息屏时立刻停止所有定时采样（零后台 CPU 与唤醒）；
 * - 亮屏期间每 3 秒仅直读一次 BatteryManager.BATTERY_PROPERTY_CURRENT_NOW，文本无变化不重绘；
 * - 紧缩双行行距并将时钟容器轻微向上抬升，确保下方主时间与右侧图标视觉重心齐平、上下不裁切不重叠。
 */
internal class StatusBarPowerTempHook(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockViews = WeakHashMap<TextView, ClockOriginState>()

    @Volatile private var enabled: Boolean = false
    @Volatile private var receiversRegistered = false
    @Volatile private var appContext: Context? = null

    // 电池实时采样缓存
    @Volatile private var voltageMv: Int = 0
    @Volatile private var tempTenthC: Int = Int.MIN_VALUE
    @Volatile private var isCharging: Boolean = false
    @Volatile private var currentWatts: Float = Float.NaN

    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val sampleTick = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            if (!enabled || !isScreenInteractive(ctx)) return
            sampleCurrentPower(ctx)
            dispatchRenderAll()
            scheduleNextTick()
        }
    }

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        enabled = readEnabled(preferences)
        installClockHooks(classLoader)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_STATUS_BAR_POWER_TEMP_MODE) {
                val nextEnabled = readEnabled(preferences)
                if (nextEnabled != enabled) {
                    enabled = nextEnabled
                    mainHandler.post { onEnabledChanged() }
                }
            }
        }
        prefListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun readEnabled(preferences: SharedPreferences): Boolean =
        preferences.getInt(KEY_STATUS_BAR_POWER_TEMP_MODE, STATUS_BAR_POWER_TEMP_OFF) != STATUS_BAR_POWER_TEMP_OFF

    private fun installClockHooks(classLoader: ClassLoader) {
        runCatching {
            val clockCls = classLoader.loadClass("com.android.systemui.statusbar.views.MiuiClock")
            val updateTime = clockCls.getDeclaredMethod("updateTime")
            hookDeoptimized(updateTime)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:power-temp:clock-update")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? TextView
                    if (view != null && isMainStatusBarClock(view)) {
                        ensureInitialized(view.context)
                        val origin = synchronized(clockViews) {
                            clockViews.getOrPut(view) { ClockOriginState.capture(view) }
                        }
                        renderClockOverlay(view, origin)
                    }
                    result
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, TAG, "安装状态栏时间上方功率温度 Hook 失败", err)
        }
    }

    private fun onEnabledChanged() {
        val ctx = appContext
        if (!enabled) {
            stopTicker()
            restoreAllClocks()
        } else {
            if (ctx != null) {
                readStickyBatteryIntent(ctx)
                sampleCurrentPower(ctx)
                if (isScreenInteractive(ctx)) {
                    scheduleNextTick()
                }
            }
            dispatchRenderAll()
        }
    }

    private fun restoreAllClocks() {
        synchronized(clockViews) { clockViews.entries.toList() }.forEach { (tv, origin) ->
            origin.restore(tv)
            runCatching { tv.javaClass.getMethod("updateTime").invoke(tv) }
        }
    }

    private fun dispatchRenderAll() {
        synchronized(clockViews) { clockViews.keys.toList() }.forEach { tv ->
            if (tv.isAttachedToWindow) {
                runCatching { tv.javaClass.getMethod("updateTime").invoke(tv) }
            }
        }
    }

    private fun renderClockOverlay(clock: TextView, origin: ClockOriginState) {
        if (!enabled) {
            if (origin.modified) origin.restore(clock)
            return
        }
        val topLine = formatClockTopLine()
        if (topLine.isEmpty()) return

        val rawTime = clock.text?.toString().orEmpty()
        // 若当前文本已含换行，取最后一行作为原始时间避免重复叠加
        val cleanTime = rawTime.substringAfterLast('\n').trim()
        if (cleanTime.isEmpty()) return

        if (!origin.modified) {
            origin.modified = true
            clock.isSingleLine = false
            clock.maxLines = 2
            clock.includeFontPadding = false
            clock.setLineSpacing(0f, 0.74f)
            clock.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            // 取消时钟容器顶部裁切，并整体上抬 3dp 让下方主时间与状态栏其余图标水平对齐
            clock.setPadding(clock.paddingLeft, 0, clock.paddingRight, 0)
            clock.translationY = origin.translationY - dpToPx(clock.context, 3f)
            (clock.parent as? ViewGroup)?.let { parent ->
                parent.clipChildren = false
                parent.clipToPadding = false
            }
        }

        val combined = SpannableStringBuilder(topLine).append('\n').append(cleanTime)
        combined.setSpan(
            RelativeSizeSpan(0.50f),
            0,
            topLine.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (!combined.toString().contentEquals(clock.text)) {
            clock.text = combined
        }
    }

    private fun ensureInitialized(context: Context) {
        val app = context.applicationContext ?: context
        if (appContext == null) appContext = app
        if (receiversRegistered) return
        synchronized(this) {
            if (receiversRegistered) return
            readStickyBatteryIntent(app)
            sampleCurrentPower(app)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_BATTERY_CHANGED -> {
                            updateFromBatteryIntent(intent)
                            if (enabled && isScreenInteractive(app)) {
                                sampleCurrentPower(app)
                                dispatchRenderAll()
                            }
                        }
                        Intent.ACTION_SCREEN_OFF -> {
                            stopTicker()
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            if (enabled) {
                                readStickyBatteryIntent(app)
                                sampleCurrentPower(app)
                                dispatchRenderAll()
                                scheduleNextTick()
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    app.registerReceiver(receiver, filter)
                }
                receiversRegistered = true
            }
            if (enabled && isScreenInteractive(app)) {
                scheduleNextTick()
            }
        }
    }

    private fun scheduleNextTick() {
        if (!enabled) return
        mainHandler.removeCallbacks(sampleTick)
        mainHandler.postDelayed(sampleTick, SAMPLE_INTERVAL_MS)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(sampleTick)
    }

    private fun isScreenInteractive(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive != false }.getOrDefault(true)

    private fun readStickyBatteryIntent(context: Context) {
        val intent = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        }.getOrNull() ?: return
        updateFromBatteryIntent(intent)
    }

    private fun updateFromBatteryIntent(intent: Intent) {
        val v = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        if (v > 0) voltageMv = v
        val t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (t != Int.MIN_VALUE) tempTenthC = t
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        isCharging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING
    }

    private fun sampleCurrentPower(context: Context) {
        val bm = runCatching { context.getSystemService(BatteryManager::class.java) }.getOrNull() ?: return
        val currentUa = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(Int.MIN_VALUE)
        if (currentUa == Int.MIN_VALUE || currentUa == 0) return
        val vMv = voltageMv.takeIf { it > 0 } ?: 4000
        // 兼容部分机型以 mA 上报（绝对值 < 20000 视为 mA，否则为 µA）
        val amps = if (abs(currentUa) < 20_000) abs(currentUa) / 1000f else abs(currentUa) / 1_000_000f
        val watts = (vMv / 1000f) * amps
        if (watts.isFinite() && watts in 0f..250f) {
            currentWatts = watts
        }
    }

    private fun formatClockTopLine(): String {
        val temp = if (tempTenthC != Int.MIN_VALUE) {
            String.format(Locale.US, "%.1f°C", tempTenthC / 10f)
        } else {
            ""
        }
        val power = if (!currentWatts.isNaN()) {
            val sign = if (isCharging) "+" else "-"
            if (currentWatts >= 10f) {
                String.format(Locale.US, "%s%.0fW", sign, currentWatts)
            } else {
                String.format(Locale.US, "%s%.1fW", sign, currentWatts)
            }
        } else {
            ""
        }
        return listOf(temp, power).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun isMainStatusBarClock(view: TextView): Boolean {
        val resName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        if (resName == "clock") return true
        return view.id == MAIN_STATUS_BAR_CLOCK_ID
    }

    private fun dpToPx(context: Context, dp: Float): Float =
        dp * context.resources.displayMetrics.density

    private class ClockOriginState(
        val maxLines: Int,
        val includeFontPadding: Boolean,
        val lineSpacingExtra: Float,
        val lineSpacingMult: Float,
        val gravity: Int,
        val paddingTop: Int,
        val paddingBottom: Int,
        val translationY: Float,
        var modified: Boolean = false,
    ) {
        fun restore(tv: TextView) {
            if (!modified) return
            modified = false
            tv.maxLines = maxLines
            if (maxLines == 1) tv.isSingleLine = true
            tv.includeFontPadding = includeFontPadding
            tv.setLineSpacing(lineSpacingExtra, lineSpacingMult)
            tv.gravity = gravity
            tv.setPadding(tv.paddingLeft, paddingTop, tv.paddingRight, paddingBottom)
            tv.translationY = translationY
        }

        companion object {
            fun capture(tv: TextView): ClockOriginState = ClockOriginState(
                maxLines = tv.maxLines.coerceAtLeast(1),
                includeFontPadding = tv.includeFontPadding,
                lineSpacingExtra = tv.lineSpacingExtra,
                lineSpacingMult = tv.lineSpacingMultiplier,
                gravity = tv.gravity,
                paddingTop = tv.paddingTop,
                paddingBottom = tv.paddingBottom,
                translationY = tv.translationY,
            )
        }
    }

    companion object {
        private const val TAG = "HyperChanger-PowerTemp"
        private const val SAMPLE_INTERVAL_MS = 3_000L
        private const val MAIN_STATUS_BAR_CLOCK_ID = 2131427965
    }
}
