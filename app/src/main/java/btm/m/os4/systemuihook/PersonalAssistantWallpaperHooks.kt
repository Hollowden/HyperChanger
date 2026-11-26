// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/** Adjust only the overlay's blur radius; keep card materials and blend animations intact. */
internal class PersonalAssistantWallpaperHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val installed = mutableSetOf<String>()
    private var backgroundHooks: PersonalAssistantBackgroundHooks? = null

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        val settings = readPersonalAssistantSettings(preferences)
        // 开启「去除背景壁纸模糊」或将自定义模糊值调为 0 时，即使背景模式为「系统默认」，
        // 也自动启用纯净桌面壁纸底图：既避免透出桌面图标/小组件，又为同窗口 MiGlass 柔光玻璃提供真实采样底图。
        val effectiveBackgroundMode = when {
            settings.backgroundMode != 0 -> settings.backgroundMode
            settings.removeWallpaperBlur -> 1
            settings.customBlurActive && settings.wallpaperBlurRadius == 0 -> 1
            else -> 0
        }
        val effectiveSettings = settings.copy(backgroundMode = effectiveBackgroundMode)
        if (!effectiveSettings.removeWallpaperBlur && !effectiveSettings.customBlurActive && effectiveBackgroundMode == 0) return
        for (name in listOf("blurView", "blurOverlayWindow")) {
            if (name in installed) continue
            runCatching {
                val adapter = classLoader.loadClass("com.miui.personalassistant.device.DeviceBlurBlendAdapter")
                val method = adapter.getDeclaredMethod(name, java.lang.Float.TYPE)
                check(method.returnType == java.lang.Void.TYPE)
                val nativeMax = adapter.getDeclaredField("maxBlurRadius")
                    .apply { check(type == java.lang.Float.TYPE); isAccessible = true }
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:personal-assistant:wallpaper:$name")
                    .intercept { chain ->
                        val instance = chain.thisObject
                        val arg = chain.getArg(0) as Float
                        val replacing = backgroundHooks?.replacesBackground(instance) == true
                        if (replacing) {
                            // 纯壁纸/自定义背景 ImageView 已完全覆盖底层且自行渲染模糊（去模糊时 RenderEffect=null 零开销）。
                            // 离开负一屏（arg <= 0）时归零释放缓冲；在负一屏内确保 (int)(max * arg) >= 1，
                            // 既省去原生 100px 冗余全屏模糊 GPU 功耗，又保活 HyperOS PassBlur/MiGlass 通道防止柔光玻璃变黑/闪黑。
                            if (arg <= 0.001f) {
                                chain.proceed(arrayOf(0.0f))
                            } else if (name == "blurOverlayWindow") {
                                chain.proceed(arrayOf(1.0f))
                            } else {
                                val originalMax = nativeMax.getFloat(instance)
                                val keepAliveMax = (1.5f / arg.coerceAtLeast(0.01f)).coerceAtMost(originalMax.coerceAtLeast(2f))
                                nativeMax.setFloat(instance, keepAliveMax)
                                try { chain.proceed() } finally { nativeMax.setFloat(instance, originalMax) }
                            }
                        } else if (effectiveSettings.removeWallpaperBlur) {
                            // 兜底：若壁纸尚未加载完成，进入态同样确保 (int)(max * arg) >= 1 防止 MiGlass 柔光玻璃采样空缓冲变黑。
                            if (arg <= 0.001f) {
                                chain.proceed(arrayOf(0.0f))
                            } else if (name == "blurOverlayWindow") {
                                chain.proceed(arrayOf(1.0f))
                            } else {
                                val originalMax = nativeMax.getFloat(instance)
                                val keepAliveMax = (1.5f / arg.coerceAtLeast(0.01f)).coerceAtMost(originalMax.coerceAtLeast(2f))
                                nativeMax.setFloat(instance, keepAliveMax)
                                try { chain.proceed() } finally { nativeMax.setFloat(instance, originalMax) }
                            }
                        } else if (effectiveSettings.customBlurActive) {
                            val originalMax = nativeMax.getFloat(instance)
                            if (name == "blurOverlayWindow") {
                                val target = effectiveSettings.windowBlurRadius(arg, originalMax)
                                val safe = if (arg > 0.001f && target < 1.0f) 1.0f else target
                                chain.proceed(arrayOf(safe))
                            } else {
                                // Keep native progress (including blur scale) while changing only its radius.
                                val rawMax = effectiveSettings.wallpaperBlurRadius.toFloat()
                                val targetMax = if (arg > 0.001f && rawMax * arg < 1.0f) {
                                    (1.5f / arg.coerceAtLeast(0.01f)).coerceAtMost(originalMax.coerceAtLeast(2f))
                                } else rawMax
                                nativeMax.setFloat(instance, targetMax)
                                try { chain.proceed() } finally { nativeMax.setFloat(instance, originalMax) }
                            }
                        } else chain.proceed()
                    }
                installed += name
                HookModuleLogger.log(module, ModuleLog.INFO, TAG, "Installed overlay wallpaper hook: $name")
            }.onFailure {
                HookModuleLogger.log(module, ModuleLog.WARN, TAG, "Could not install overlay wallpaper hook: $name", it)
            }
        }
        if (effectiveBackgroundMode != 0 && installed.containsAll(listOf("blurView", "blurOverlayWindow")) && backgroundHooks == null) {
            runCatching {
                PersonalAssistantBackgroundHooks(module, hookDeoptimized, classLoader, effectiveSettings).also {
                    backgroundHooks = it
                    it.install()
                }
            }.onFailure {
                HookModuleLogger.log(module, ModuleLog.WARN, TAG, "Could not install replacement background", it)
            }
        }
    }

    private companion object { const val TAG = "PersonalAssistantWallpaper" }
}
