// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import btm.m.os4.systemuihook.ModuleLog as Log
import btm.m.os4.systemuihook.newnotificationcenter.IosNotificationShortcutHost
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

internal class LockscreenShortcutGestureHooks(
    private val module: HyperSystemUiModule,
    private val logTag: String,
) {
    private val installed = mutableSetOf<Executable>()
    private val listeners = Collections.synchronizedMap(WeakHashMap<View, WeakReference<ShortcutTouchListener>>())
    private val launchInFlight = AtomicBoolean(false)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var listenerGuardInstalled = false
    private var prefListenerInstalled = false

    @Synchronized
    fun install(loader: ClassLoader, preferences: SharedPreferences) {
        IosNotificationShortcutHost.setGestureMode(
            preferences.getInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, 0).coerceIn(0, 2),
        )
        if (!prefListenerInstalled) {
            preferences.registerOnSharedPreferenceChangeListener { prefs, key ->
                if (key == null || key == KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE) {
                    IosNotificationShortcutHost.setGestureMode(
                        prefs.getInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, 0).coerceIn(0, 2),
                    )
                }
            }
            prefListenerInstalled = true
        }
        runCatching {
            val controller = loader.loadClass("com.miui.keyguard.shortcuts.controller.ShortcutViewLayoutController")
            val managerClass = loader.loadClass("com.miui.keyguard.shortcuts.manager.MiuiShortcutManager")
            val manager = managerClass.getField("INSTANCE").get(null)
            val setSide = managerClass.getMethod("setOccludedMovedShortcut", Boolean::class.javaPrimitiveType)
            val start = managerClass.getMethod("startShortcutActivity")
            val left = controller.getDeclaredField("shortcutViewLeft").apply { isAccessible = true }
            val right = controller.getDeclaredField("shortcutViewRight").apply { isAccessible = true }
            val launch: (View, Boolean) -> Unit = { _, isLeft ->
                if (launchInFlight.compareAndSet(false, true)) {
                    runCatching {
                        setSide.invoke(manager, isLeft)
                        start.invoke(manager)
                        handler.postDelayed({ launchInFlight.set(false) }, 800L)
                    }.onFailure {
                        launchInFlight.set(false)
                        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not launch lockscreen shortcut", it)
                    }
                }
            }
            val bind: (Any) -> Unit = { instance ->
                listOf(left to true, right to false).forEach { (field, side) ->
                    (field.get(instance) as? View)?.let { icon ->
                        var listener = listeners[icon]?.get()
                        if (listener == null) {
                            listener = ShortcutTouchListener(side, preferences, handler, currentListener(icon), launch)
                            listeners[icon] = WeakReference(listener)
                            icon.setOnTouchListener(listener)
                        }
                    }
                }
            }
            if (!listenerGuardInstalled) {
                module.hookDeoptimized(View::class.java.getMethod("setOnTouchListener", View.OnTouchListener::class.java))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-shortcut-listener-owner")
                    .intercept { chain ->
                        val listener = listeners[chain.thisObject]?.get()
                        val incoming = chain.getArg(0) as? View.OnTouchListener
                        if (listener != null && incoming !== listener) {
                            // Keep the replacement Folme listener as the stock delegate, while
                            // retaining our listener exactly as the stock guard does.
                            listener.original = incoming
                            chain.proceedWith(chain.thisObject, arrayOf(listener))
                        } else chain.proceed()
                    }
                listenerGuardInstalled = true
            }
            val bindings = controller.declaredConstructors.toList() + controller.declaredMethods.filter {
                (it.name == "updateShortcutView" && it.parameterTypes.map { type -> type.name } == listOf(
                    "com.miui.keyguard.shortcuts.data.ShortcutEntity", "android.widget.ImageView", "android.view.View",
                )) || (it.name == "updateShortcutFixedSize" && it.parameterCount == 0) ||
                    (it.name == "onShortcutDataChanged" && it.parameterCount == 2)
            }
            bindings.forEach { member ->
                if (member !in installed) {
                    module.hookDeoptimized(member)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-shortcut-bind-${member.name}-${member.parameterCount}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching { chain.thisObject?.let(bind) }.onFailure {
                                btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not bind lockscreen shortcut icons", it)
                            }
                            result
                        }
                    installed += member
                }
            }
            val move = loader.loadClass("com.miui.keyguard.shortcuts.controller.ShortcutMoveController")
                .getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
            if (move !in installed) {
                module.hookDeoptimized(move).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-shortcut-stock-movement")
                    .intercept { chain ->
                        if (preferences.getInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, 0) == 2) false else chain.proceed()
                    }
                installed += move
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed AOD shortcut gesture bindings with loader $loader")
        }.onFailure {
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not install AOD shortcut gesture bindings", it)
        }
    }

    private fun currentListener(view: View): View.OnTouchListener? = runCatching {
        val info = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(view)
        info?.javaClass?.getDeclaredField("mOnTouchListener")?.apply { isAccessible = true }
            ?.get(info) as? View.OnTouchListener
    }.getOrNull()

    private class ShortcutTouchListener(
        private val side: Boolean,
        private val preferences: SharedPreferences,
        private val handler: Handler,
        var original: View.OnTouchListener?,
        private val launch: (View, Boolean) -> Unit,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private var moved = false
        private var armed = false
        private var gestureMode = 0
        private var activeView: View? = null
        private var scaleTarget: View? = null
        private var lastEvent: MotionEvent? = null

        private val armRunnable = Runnable {
            val view = activeView ?: return@Runnable
            if (!tracking || moved) return@Runnable
            armed = true
            // 达到长按阈值立即给出苹果风格的线性马达震动提示 + 微微弹出视觉反馈，等待松手启动
            runCatching {
                view.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                )
            }
            animateScale(scaleTarget ?: view, ARMED_SCALE, 150L, overshoot = true)
            view.parent?.requestDisallowInterceptTouchEvent(true)
            lastEvent?.let { ev ->
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                try {
                    original?.onTouch(view, cancel)
                    if (gestureMode == 1) view.onTouchEvent(cancel)
                } finally {
                    cancel.recycle()
                }
            }
        }

        private fun findScaleTarget(view: View): View {
            val parent = view.parent as? ViewGroup
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    if (parent.getChildAt(i).tag == LockscreenShortcutGlassOwnership.TAG) return parent
                }
            }
            return view
        }

        private fun animateScale(target: View, scale: Float, durationMs: Long, overshoot: Boolean) {
            target.animate().cancel()
            target.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(durationMs)
                .setInterpolator(if (overshoot) ARM_INTERPOLATOR else RESTORE_INTERPOLATOR)
                .start()
        }

        private fun resetVisuals(view: View) {
            handler.removeCallbacks(armRunnable)
            val target = scaleTarget ?: view
            animateScale(target, 1f, 140L, overshoot = false)
            view.isPressed = false
            activeView = null
            scaleTarget = null
            lastEvent?.recycle()
            lastEvent = null
        }

        private fun isInside(view: View, event: MotionEvent, radiusScale: Float = 1.45f): Boolean {
            if (view.width <= 0 || view.height <= 0) return true
            val radius = (max(view.width, view.height) / 2f) * radiusScale
            return hypot(event.x - view.width / 2f, event.y - view.height / 2f) <= radius
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            // 通知中心借用快捷按钮时，由 IosNotificationShortcutHost 统一处理长按与启动，避免双端冲突
            if (IosNotificationShortcutHost.isHosting()) {
                return original?.onTouch(view, event) ?: false
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureMode = preferences.getInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, 0).coerceIn(0, 2)
                IosNotificationShortcutHost.setGestureMode(gestureMode)
                // Keep the system's accessibility click path.
                if ((view.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
                        ?.isTouchExplorationEnabled == true) gestureMode = 0
                downX = event.rawX
                downY = event.rawY
                tracking = true
                moved = false
                armed = false
                activeView = view
                scaleTarget = findScaleTarget(view)
            }
            if (gestureMode == 0) return original?.onTouch(view, event) ?: false
            lastEvent?.recycle()
            lastEvent = MotionEvent.obtain(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Add mode must allow the parent to recognize the stock swipe until armed.
                    if (gestureMode == 2) view.parent?.requestDisallowInterceptTouchEvent(true)
                    view.isPressed = true
                    animateScale(scaleTarget ?: view, PRESS_SCALE, 100L, overshoot = false)
                    handler.removeCallbacks(armRunnable)
                    handler.postDelayed(armRunnable, LONG_PRESS_ARM_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (armed) {
                        if (!isInside(view, event, 1.45f)) {
                            // 划出按钮范围即取消待启动态
                            armed = false
                            tracking = false
                            moved = true
                            resetVisuals(view)
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        return true
                    }
                    val slop = max(
                        view.resources.displayMetrics.density * 8f,
                        ViewConfiguration.get(view.context).scaledTouchSlop.toFloat(),
                    )
                    if (!moved && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                        moved = true
                        handler.removeCallbacks(armRunnable)
                        animateScale(scaleTarget ?: view, 1f, 120L, overshoot = false)
                        view.isPressed = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val shouldLaunch = tracking && armed && isInside(view, event, 1.45f)
                    tracking = false
                    armed = false
                    moved = false
                    resetVisuals(view)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (shouldLaunch) {
                        launch(view, side)
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    tracking = false
                    armed = false
                    moved = false
                    resetVisuals(view)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            if (armed) return true
            val stockHandled = original?.onTouch(view, event) ?: false
            return gestureMode == 2 || stockHandled
        }

        companion object {
            private const val LONG_PRESS_ARM_MS = 380L
            private const val PRESS_SCALE = 0.94f
            private const val ARMED_SCALE = 1.18f
            private val ARM_INTERPOLATOR = OvershootInterpolator(1.6f)
            private val RESTORE_INTERPOLATOR = DecelerateInterpolator()
        }
    }
}
