// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.app.Activity
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import btm.m.os4.systemuihook.ModuleLog as Log
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import btm.m.liquidglass.AppColorMode
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.ScopedSettings
import btm.m.liquidglass.hook.AppBottomNavHooks
import btm.m.liquidglass.hook.LuckinAntiDetectionHooks
import btm.m.os4.systemuihook.htmlviewer.HtmlViewerHooks
import btm.m.os4.systemuihook.joyose.JoyoseHooks
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Executable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import org.json.JSONObject

/**
 * Owns independent app navigation, Settings, MiSettings, MiSound, MiShare and rear-screen hooks.
 * Package/process dispatch and lifecycle boundaries remain in the module. Framework hooks
 * retain their original implementation and both original entry points there.
 * The Settings resource cache keeps the exact original monitor supplied by the module.
 */
internal class OthersHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val settingsResourcesLock: Any,
    private val logTag: String,
) {
    private var googleQuickShareIslandInstalled = false
    private val contactsDialerHooks = ContactsDialerHooks(module, hookDeoptimized, logTag)
    private val portalHooks = PortalHooks(module, hookDeoptimized, logTag)
    private val sidebarHooks = SidebarHooks(module, hookDeoptimized, logTag)
    private val sidebarAppearanceHooks = SidebarAppearanceHooks(module, hookDeoptimized, logTag)
    private val sidebarIconCountHooks = SidebarIconCountHooks(module, hookDeoptimized, logTag)
    private val widgetGlassHooks = WidgetGlassHooks(module, hookDeoptimized, logTag)
    private val personalAssistantWallpaperHooks = PersonalAssistantWallpaperHooks(module, hookDeoptimized)
    private val videoToolboxHooks = VideoToolboxHooks(module)
    private val powerBatteryHooks = PowerBatteryHooks(module, hookDeoptimized)
    private val powerBatteryInfoHooks = PowerBatteryInfoHooks(module, hookDeoptimized)
    private val hotspotTemperatureHooks = HotspotTemperatureHooks(module)
    private val htmlViewerHooks = HtmlViewerHooks(module)
    private val joyoseHooks = JoyoseHooks(module)
    private val settingsFloatingSearchHooks = SettingsFloatingSearchHooks(module, hookDeoptimized)
    private val settingsTitleBarBlurHooks = SettingsTitleBarBlurHooks(module, hookDeoptimized)

    fun installSettingsFloatingSearchHooks(param: PackageLoadedParam, preferences: SharedPreferences) =
        settingsFloatingSearchHooks.install(param, preferences)

    fun installSettingsTitleBarBlurHooks(param: PackageLoadedParam, preferences: SharedPreferences) =
        settingsTitleBarBlurHooks.install(param, preferences)

    fun installPortalHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        portalHooks.install(classLoader, preferences)

    fun installSidebarHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        sidebarHooks.install(classLoader, preferences)

    fun installSidebarAppearanceHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        sidebarAppearanceHooks.install(classLoader, preferences)

    fun installSidebarIconCountHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        sidebarIconCountHooks.install(classLoader, preferences)

    fun installWidgetGlassHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        widgetGlassHooks.install(classLoader, preferences)

    fun installPersonalAssistantWallpaperHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        personalAssistantWallpaperHooks.install(classLoader, preferences)

    fun installVideoToolboxHooks(param: PackageLoadedParam, preferences: SharedPreferences) =
        videoToolboxHooks.install(param, preferences)

    fun installPowerBatteryHooks(param: PackageLoadedParam, preferences: SharedPreferences, mainProcess: Boolean) =
        powerBatteryHooks.install(param, preferences, mainProcess)

    fun installPowerBatteryInfoHooks(param: PackageLoadedParam, preferences: SharedPreferences) =
        powerBatteryInfoHooks.install(param, preferences)

    fun installHotspotTemperatureHooks(param: PackageLoadedParam, preferences: SharedPreferences) =
        hotspotTemperatureHooks.install(param, preferences)

    fun installContactsDialerHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        contactsDialerHooks.install(classLoader, preferences)

    fun installHtmlViewerHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        htmlViewerHooks.install(classLoader, preferences)

    fun installJoyoseHooks(param: PackageLoadedParam, preferences: SharedPreferences) =
        joyoseHooks.install(param, preferences)

    fun installAppNavigation(param: PackageLoadedParam) {
        if (!ModuleSafetyRuntime.allows(SafetySection.NAVIGATION)) return
        val packageName = param.packageName
        if (packageName != "com.xiaomi.shop" && packageName != "com.mipay.wallet" &&
            packageName != "com.mi.health" && packageName != "com.apple.android.music" &&
            packageName != "com.sina.weibo" && packageName != "com.xingin.xhs" &&
            packageName != "com.lucky.luckyclient" && packageName != "com.tencent.wemeet.app" &&
            packageName != "cmb.pb" && packageName != "com.tencent.mm" &&
            packageName != "com.reddit.frontpage" &&
            packageName != "com.tencent.mobileqq"
            && packageName != "com.xunmeng.pinduoduo" && packageName != "com.instagram.android" && packageName != "com.twitter.android"
            && packageName != "com.github.android" && packageName != "com.pinterest"
            && packageName != "com.ss.android.ugc.lifeservices"
            && packageName != "com.sankuai.meituan" && packageName != "com.sankuai.meituan.takeoutnew"
        ) return
        if (!runCatching { android.app.Application.getProcessName() == packageName }.getOrDefault(false)) return
        runCatching {
            val prefs = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            if (!ScopedSettings.getBoolean(prefs, packageName, ScopedSettings.KEY_MODULE_ENABLED, true)) return
            if (packageName == "com.tencent.mm") {
                // Avatar tuning is independent from the optional custom bottom bar.
                btm.m.liquidglass.hook.WeChatAvatarRadiusHooks.install(module, param.defaultClassLoader)
            }
            if (packageName == "com.mi.health" && ScopedSettings.getBoolean(
                    prefs,
                    packageName,
                    ScopedSettings.KEY_REMOVE_WATCH_FACE_TRIAL_LIMIT,
                    false,
                )
            ) {
                runCatching {
                    installXiaomiHealthWatchFaceTrialBypass(param.defaultClassLoader)
                }.onFailure { error ->
                    btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.ERROR, logTag, "Could not install Xiaomi Health watch-face trial hook", error)
                }
            }
            if (!ScopedSettings.getBoolean(prefs, packageName, ScopedSettings.KEY_ENABLED, true)) return
            val style = ScopedSettings.getString(prefs, packageName, NavigationStyle.PREFERENCE_KEY, NavigationStyle.DEFAULT_VALUE)
            val label = ScopedSettings.getLabelMode(prefs, packageName, LabelMode.DEFAULT_VALUE)
            val color = ScopedSettings.getString(prefs, packageName, ScopedSettings.KEY_COLOR_MODE, AppColorMode.DEFAULT_VALUE)
            val blur = ScopedSettings.getBlurRadius(prefs, packageName, style, 18)
            val advanced = ScopedSettings.getBoolean(prefs, packageName, ScopedSettings.KEY_ADVANCED_MATERIAL, true)
            val accent = btm.m.liquidglass.NavigationAccentSettings.getColor(prefs, packageName)
            val showBadges = btm.m.liquidglass.NavigationBadgeSettings.getShowBadges(prefs, packageName)
            when (packageName) {
                "com.xiaomi.shop" -> AppBottomNavHooks.installXiaomiStore(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.mipay.wallet" -> AppBottomNavHooks.installXiaomiWallet(module, param.defaultClassLoader, blur, label, style, advanced, color, ScopedSettings.getWalletVisibleTabs(prefs), accent)
                "com.mi.health" -> {
                    AppBottomNavHooks.installXiaomiHealth(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                    AppBottomNavHooks.installXiaomiHealthWatchFaceMarket(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                }
                "com.apple.android.music" -> AppBottomNavHooks.installAppleMusic(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.sina.weibo" -> AppBottomNavHooks.installWeibo(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.xingin.xhs" -> AppBottomNavHooks.installXiaohongshu(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.tencent.wemeet.app" -> AppBottomNavHooks.installTencentMeeting(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.ss.android.ugc.lifeservices" -> AppBottomNavHooks.installDouShengSheng(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.sankuai.meituan" -> AppBottomNavHooks.installMeituan(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.sankuai.meituan.takeoutnew" -> AppBottomNavHooks.installMeituanTakeout(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.tencent.mobileqq" -> AppBottomNavHooks.installQQ(module, param.defaultClassLoader, blur, label, style, advanced, color, accent, showBadges)
                "com.reddit.frontpage" -> AppBottomNavHooks.installReddit(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "cmb.pb" -> AppBottomNavHooks.installCmbBank(module, blur, label, style, advanced, color, accent)
                "com.tencent.mm" -> {
                    // The personal SuperLiquid port owns the WeChat bottom bar in this
                    // build. Installing the stock one too stacks two bars; the port reads
                    // scope.com.tencent.mm.enabled, which stays as its on/off switch.
                }
                "com.xunmeng.pinduoduo" -> AppBottomNavHooks.installPinduoduo(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.instagram.android" -> AppBottomNavHooks.installInstagram(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.pinterest" -> AppBottomNavHooks.installPinterest(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.twitter.android" -> AppBottomNavHooks.installX(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.github.android" -> AppBottomNavHooks.installGitHub(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.lucky.luckyclient" -> {
                    LuckinAntiDetectionHooks.install(module)
                    AppBottomNavHooks.installLuckinCoffee(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed app navigation hooks for $packageName")
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.ERROR, logTag, "Could not install app navigation hooks for $packageName", error) }
    }

    /** Override MiShare's app-side AirDrop availability checks when enabled for its scope. */
    fun installAirDropForceEnableHooks(param: PackageLoadedParam) {
        val preferences = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (!preferences.getBoolean(KEY_AIRDROP_FORCE_ENABLE, false)) return
        installAirDropDeviceProfileHooks()
        val availability = param.defaultClassLoader.loadClass(AIRDROP_AVAILABILITY_CLASS)
        listOf("c", "e").forEach { methodName ->
            val method = availability.getDeclaredMethod(methodName).apply { isAccessible = true }
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:airdrop-force-enable:$methodName")
                .intercept { true }
        }
        // The settings entry immediately goes through AirDropCtaGateActivity. When the
        // force-enable switch is on, allow that gate to continue to the actual Mi Share page;
        // the transfer implementation and its runtime capability checks remain untouched.
        val ctaGate = param.defaultClassLoader.loadClass("com.miui.mishare.connectivity.R0")
        val ctaMethod = ctaGate.getDeclaredMethod("p", android.content.Context::class.java)
            .apply { isAccessible = true }
        module.hook(ctaMethod)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:airdrop-force-enable:cta")
            .intercept { true }
        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed MiShare AirDrop availability hooks in ${param.packageName}")
    }

    /**
     * Reproduce unlock_cn_gms_ksu's two feature removals inside Play services.
     * The original module edits permission XML before boot; this process-local equivalent only
     * changes the PackageManager answers observed by GMS and leaves the system feature registry
     * untouched. Quick Share still requires the installed provider and wireless backend.
     */
    fun installGoogleFeatureUnlockHooks(param: PackageLoadedParam) {
        val preferences = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (!preferences.getBoolean(KEY_GMS_UNLOCK_FEATURES, false)) return
        val packageManager = Class.forName("android.app.ApplicationPackageManager", false, null)
        packageManager.declaredMethods
            .filter { method ->
                method.name == "hasSystemFeature" &&
                    method.parameterTypes.firstOrNull() == String::class.java &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
            .forEach { method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:gms-feature-unlock:${method.toGenericString()}")
                    .intercept { chain ->
                        when (chain.getArg(0) as? String) {
                            // unlock_cn_gms_ksu removes these feature declarations from the
                            // PackageManager-visible XML. Returning false is the equivalent
                            // process-local answer; returning true would preserve the CN gate.
                            "cn.google.services", "com.google.android.feature.services_updater" -> false
                            else -> chain.proceed()
                        }
                    }
            }
        packageManager.declaredMethods
            .filter { method -> method.name == "getSystemAvailableFeatures" && method.parameterCount == 0 }
            .forEach { method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:gms-feature-unlock:list:${method.toGenericString()}")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val features = result as? Array<*> ?: return@intercept result
                        val filtered = features.filter { feature ->
                            val name = (feature as? android.content.pm.FeatureInfo)?.name
                            name != "cn.google.services" &&
                                name != "com.google.android.feature.services_updater"
                        }
                        // Preserve the framework method's FeatureInfo[] runtime type. A Kotlin
                        // toTypedArray() here would create Object[], which can fail at the GMS
                        // caller's checkcast even though the elements are valid FeatureInfo.
                        val componentType = result.javaClass.componentType ?: return@intercept result
                        val typed = java.lang.reflect.Array.newInstance(componentType, filtered.size)
                        filtered.forEachIndexed { index, feature ->
                            java.lang.reflect.Array.set(typed, index, feature)
                        }
                        typed
                    }
            }
        HookModuleLogger.log(module, Log.INFO, logTag, "Installed process-local GMS feature unlock hooks in ${param.packageName}")
    }

    /**
     * Quick Share owns the transfer notification and already updates it in place for progress.
     * Add the same Focus notification extras consumed by HyperOS Dynamic Island before GMS posts
     * it, so the original notification ID, actions, progress and cancellation remain GMS-owned.
     */
    @Synchronized
    fun installGoogleQuickShareIslandHook(param: PackageLoadedParam) {
        if (googleQuickShareIslandInstalled) return
        val preferences = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (!preferences.getBoolean(KEY_GMS_QUICK_SHARE_ISLAND, false)) return
        val methods = android.app.NotificationManager::class.java.declaredMethods.filter { method ->
            when (method.name) {
                "notify" -> method.parameterTypes.contentEquals(arrayOf(
                    String::class.java, Int::class.javaPrimitiveType, android.app.Notification::class.java,
                ))
                "notifyAsUser" -> method.parameterTypes.contentEquals(arrayOf(
                    String::class.java, Int::class.javaPrimitiveType, android.app.Notification::class.java,
                    android.os.UserHandle::class.java,
                ))
                "notifyAsPackage" -> method.parameterTypes.contentEquals(arrayOf(
                    String::class.java, String::class.java, Int::class.javaPrimitiveType, android.app.Notification::class.java,
                ))
                else -> false
            }
        }
        // Each process owns one installation. Successful registrations remain guarded even when
        // a later overload cannot be hooked, preventing duplicate callbacks on subsequent loads.
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                val notificationIndex = method.parameterTypes.indexOf(android.app.Notification::class.java)
                val tagIndex = if (method.name == "notifyAsPackage") 1 else 0
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:gms-quick-share-island:${method.toGenericString()}")
                    .intercept { chain ->
                        // Keep the original call outside the adaptation catch: never retry a
                        // framework notification call that has already thrown or had side effects.
                        val adapted = runCatching {
                            if (!preferences.getBoolean(KEY_GMS_QUICK_SHARE_ISLAND, false)) return@runCatching null
                            val original = chain.getArg(notificationIndex) as? android.app.Notification ?: return@runCatching null
                            if (method.name == "notifyAsPackage" && chain.getArg(0) != "com.google.android.gms") return@runCatching null
                            QuickShareIslandBridge.transform(chain.getArg(tagIndex) as? String, original)
                        }.getOrNull()
                        if (adapted != null) {
                            val args = chain.args.toTypedArray().also { it[notificationIndex] = adapted }
                            chain.proceedWith(chain.thisObject, args)
                        } else chain.proceed()
                    }
                googleQuickShareIslandInstalled = true
            }.onFailure { error ->
                HookModuleLogger.log(module, Log.WARN, logTag, "Could not install GMS island hook for ${method.name}", error)
            }
        }
        if (googleQuickShareIslandInstalled) {
            HookModuleLogger.log(module, Log.INFO, logTag, "Installed GMS Quick Share Dynamic Island bridge in ${param.packageName}")
        }
    }

    /** Keep the Xiaomi 18 Pro Max / madrid identity local to the Mi Share process. */
    private fun installAirDropDeviceProfileHooks() {
        val spoofedProperties = mapOf(
            "ro.product.model" to "Xiaomi 18 Pro Max",
            "ro.product.marketname" to "Xiaomi 18 Pro Max",
            "ro.product.device" to "madrid",
            "ro.product.name" to "madrid",
            "ro.build.product" to "madrid",
            "ro.product.brand" to "Xiaomi",
            "ro.product.manufacturer" to "Xiaomi",
        )
        runCatching {
            val properties = Class.forName("android.os.SystemProperties", false, null)
            properties.declaredMethods
                .filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name in setOf("get", "getBoolean", "getInt", "getLong") &&
                        method.parameterTypes.firstOrNull() == String::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:mishare-device-profile:${method.name}:${method.parameterTypes.size}")
                        .intercept { chain ->
                            val value = spoofedProperties[chain.getArg(0) as? String]
                                ?: return@intercept chain.proceed()
                            when (method.name) {
                                "get" -> value
                                "getBoolean" -> value.equals("true", ignoreCase = true)
                                "getInt" -> value.toIntOrNull() ?: 0
                                "getLong" -> value.toLongOrNull() ?: 0L
                                else -> chain.proceed()
                            }
                        }
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not install Mi Share property spoof", error) }

        runCatching {
            mapOf(
                "MODEL" to "Xiaomi 18 Pro Max",
                "DEVICE" to "madrid",
                "PRODUCT" to "madrid",
                "BRAND" to "Xiaomi",
                "MANUFACTURER" to "Xiaomi",
            ).forEach { (fieldName, value) ->
                android.os.Build::class.java.getDeclaredField(fieldName).apply {
                    isAccessible = true
                    set(null, value)
                }
            }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not update Mi Share Build identity", error) }
    }

    private fun installXiaomiHealthWatchFaceTrialBypass(loader: ClassLoader) {
        val moduleClass = loader.loadClass("com.xiaomi.wearable.yrn.modules.WatchFaceModule")
        val promiseClass = loader.loadClass("com.facebook.react.bridge.Promise")
        val method = moduleClass.getDeclaredMethod(
            "getEnterFaceMarketTime",
            String::class.java,
            promiseClass,
        ).apply { isAccessible = true }
        val resolve = promiseClass.getMethod("resolve", Any::class.java)
        hookDeoptimized(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:mi-health-watch-face-unlimited-trial")
            .intercept { chain ->
                val promise = chain.getArg(1) ?: return@intercept chain.proceed()
                val oneHundredYearsMs = 100L * 365L * 24L * 60L * 60L * 1000L
                resolve.invoke(promise, (System.currentTimeMillis() + oneHundredYearsMs).toDouble())
                null
            }
        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed Xiaomi Health unlimited watch-face trial hook")
    }

    private fun settingsField(target: Any, name: String): java.lang.reflect.Field? = generateSequence(target.javaClass) { it.superclass }
        .mapNotNull { type -> runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
        .firstOrNull()

    private fun getLongField(target: Any, name: String): Long = settingsField(target, name)?.get(target).let { value ->
        when (value) { is Number -> value.toLong(); else -> 0L }
    }

    private fun getIntField(target: Any, name: String): Int = settingsField(target, name)?.get(target).let { value ->
        when (value) { is Number -> value.toInt(); else -> 0 }
    }

    private fun setLongField(target: Any, name: String, value: Long) { settingsField(target, name)?.set(target, value) }

    private fun setIntField(target: Any, name: String, value: Int) { settingsField(target, name)?.set(target, value) }

    private fun setObjectField(target: Any, name: String, value: Any?) { settingsField(target, name)?.set(target, value) }

    private fun attachModuleResources(context: Context): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val loader = synchronized(settingsResourcesLock) {
            settingsModuleResourcesLoader ?: run {
                val apk = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).sourceDir
                val provider = ParcelFileDescriptor.open(java.io.File(apk), ParcelFileDescriptor.MODE_READ_ONLY).use {
                    ResourcesProvider.loadFromApk(it)
                }
                ResourcesLoader().apply { addProvider(provider) }.also { settingsModuleResourcesLoader = it }
            }
        }
        runCatching { context.resources.addLoaders(loader) }
        true
    }.getOrDefault(false)

    fun installSettingsAppEntryHook(loader: ClassLoader, prefs: SharedPreferences) {
        val settingsClass = runCatching { loader.loadClass("com.android.settings.MiuiSettings") }.getOrNull() ?: return
        val update = settingsClass.methods.firstOrNull {
            it.name == "updateHeaderList" && it.parameterTypes.size == 1 && java.util.List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return
        module.hook(update).setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:settings-app-entry").intercept { chain ->
            val result = chain.proceed()
            val headers = chain.getArg(0) as? MutableList<Any?> ?: return@intercept result
            // Read on every rebuild so changing the dropdown takes effect without restarting Settings.
            headers.removeAll { header -> runCatching { getLongField(header!!, "id") == SETTINGS_HEADER_ID }.getOrDefault(false) }
            val savedPosition = prefs.getInt(KEY_SETTINGS_APP_ENTRY_POSITION, 0).coerceIn(0, 3)
            if (savedPosition == 0) {
                return@intercept result
            }
            val settingsContext = (chain.thisObject as? android.app.Activity)?.baseContext ?: return@intercept result
            val headerClass = runCatching {
                loader.loadClass("com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header")
            }.getOrNull() ?: return@intercept result
            val header = runCatching { headerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull()
                ?: return@intercept result
            setLongField(header, "id", SETTINGS_HEADER_ID)
            val moduleContext = runCatching {
                settingsContext.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
            attachModuleResources(settingsContext)
            val settingsIcon = if (moduleContext != null) {
                R.drawable.ic_hyperchanger_settings_entry
            } else {
                android.R.drawable.ic_menu_manage
            }
            setIntField(header, "iconRes", settingsIcon)
            // A custom title wins over the module's application label; blank keeps the default.
            val customTitle = prefs.getString(KEY_SETTINGS_APP_ENTRY_TITLE, "").orEmpty()
                .trim().take(MAX_SETTINGS_APP_ENTRY_TITLE_LENGTH)
            val label = if (customTitle.isNotBlank()) {
                customTitle
            } else {
                runCatching {
                    (moduleContext ?: settingsContext).applicationInfo.loadLabel(settingsContext.packageManager).toString()
                }.getOrDefault("HyperChanger")
            }
            setObjectField(header, "title", label)
            setObjectField(header, "intent", Intent().setClassName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.MainActivity").apply {
                putExtra("isDisplayHomeAsUpEnabled", true)
            })
            val deviceId = settingsContext.resources.getIdentifier("my_device", "id", settingsContext.packageName)
            val launcherId = settingsContext.resources.getIdentifier("launcher_settings", "id", settingsContext.packageName)
            val specialId = settingsContext.resources.getIdentifier("other_special_feature_settings", "id", settingsContext.packageName)
            val timerId = settingsContext.resources.getIdentifier("app_timer", "id", settingsContext.packageName)
            val anchor = when (savedPosition) {
                1 -> deviceId
                2 -> launcherId
                else -> if (android.os.Build.VERSION.SDK_INT >= 35) timerId else specialId
            }
            val index = headers.indexOfFirst { item -> runCatching { getLongField(item!!, "id").toInt() == anchor }.getOrDefault(false) }
            val insertAt = if (index >= 0) index + 1 else headers.size.coerceAtMost(25)
            if (headers.isNotEmpty()) {
                val groupSource = headers[(insertAt - 1).coerceIn(0, headers.lastIndex)]
                setIntField(header, "groupId", getIntField(groupSource!!, "groupId"))
            }
            headers.add(insertAt.coerceIn(0, headers.size), header)
            result
        }
    }

    fun installSettingsEntryIconHook(loader: ClassLoader, prefs: SharedPreferences) {
        SettingsEntryIconHooks().install(module, loader, prefs)
    }

    fun installSettingsEntryLabelHook(loader: ClassLoader, prefs: SharedPreferences) {
        val adapter = runCatching { loader.loadClass(MIUI_SETTINGS_HEADER_ADAPTER) }.getOrNull() ?: return
        val bind = adapter.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterCount == 2 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return
        val getItem = runCatching { adapter.getMethod("getItem", Int::class.javaPrimitiveType) }.getOrNull() ?: return
        module.hook(bind).setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:settings-entry-labels").intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val labels = readSettingsEntryLabels(prefs)
                if (labels.isEmpty()) return@runCatching
                val position = chain.getArg(1) as? Int ?: return@runCatching
                val header = getItem.invoke(chain.thisObject, position) ?: return@runCatching
                val id = header.javaClass.getField("id").getLong(header)
                if (id <= 0L) return@runCatching
                val holder = chain.getArg(0)
                val title = holder.javaClass.getField("title").get(holder) as? TextView ?: return@runCatching
                val name = runCatching { title.resources.getResourceEntryName(id.toInt()) }.getOrNull()
                    ?: return@runCatching
                val custom = labels[name] ?: return@runCatching
                if (title.text?.toString() != custom) title.text = custom
            }
            result
        }
    }

    /** Sound effect support for com.miui.misound: unlock BOSE and rebrand its badge. */
    fun installSoundEffectHooks(loader: ClassLoader, prefs: SharedPreferences) {
        val nativeBosePrefs = module.getRemotePreferences(NATIVE_BOSE_PREFERENCES)
        if (!isNativeBoseDevice(nativeBosePrefs)) {
            runCatching {
                val getBoolean = Class.forName("android.os.SystemProperties").getDeclaredMethod(
                    "getBoolean", String::class.java, Boolean::class.javaPrimitiveType,
                )
                module.hook(getBoolean)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:sound-effect-props")
                    .intercept { chain ->
                        val key = chain.getArg(0) as? String
                        if (key == AUDIO_PROP_BOSE && !isNativeBoseDevice(nativeBosePrefs)) {
                            readSoundEffectPreferences(prefs).boseEnabled
                        } else chain.proceed()
                    }
            }
            runCatching {
                val gate = loader.loadClass("z.m")
                gate.methods.firstOrNull { it.name == "B" && it.parameterCount == 0 }?.let { target ->
                    hookDeoptimized(target)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:sound-effect-gate")
                        .intercept { chain ->
                            if (isNativeBoseDevice(nativeBosePrefs)) chain.proceed()
                            else readSoundEffectPreferences(prefs).boseEnabled
                        }
                }
            }
        }
        runCatching {
            val taGet = android.content.res.TypedArray::class.java.getDeclaredMethod(
                "getDrawable", Int::class.javaPrimitiveType,
            )
            module.hook(taGet)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:audio-logo-ta")
                .intercept { chain ->
                    val typed = chain.thisObject as? android.content.res.TypedArray
                    val index = chain.getArg(0) as? Int
                    val replacement = if (typed != null && index != null) {
                        val resId = typed.getResourceId(index, 0)
                        if (resId != 0) audioLogoDrawable(typed.resources, resId, prefs) else null
                    } else null
                    if (replacement != null) replacement else chain.proceed()
                }
        }
        runCatching {
            val getDrawable = Resources::class.java.getDeclaredMethod(
                "getDrawable", Int::class.javaPrimitiveType, Resources.Theme::class.java,
            )
            module.hook(getDrawable)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:audio-logo-res")
                .intercept { chain ->
                    val res = chain.thisObject as? Resources
                    val id = chain.getArg(0) as? Int
                    val replacement = if (res != null && id != null) audioLogoDrawable(res, id, prefs) else null
                    if (replacement != null) replacement else chain.proceed()
                }
        }
        runCatching {
            val setImage = ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType)
            module.hook(setImage)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:audio-logo-setter")
                .intercept { chain ->
                    val view = chain.thisObject as? ImageView
                    val resId = chain.getArg(0) as? Int
                    val replacement = if (view != null && resId != null) audioLogoDrawable(view.resources, resId, prefs) else null
                    if (replacement != null) { view!!.setImageDrawable(replacement); null } else chain.proceed()
                }
        }
        runCatching {
            val fragCls = loader.loadClass("androidx.preference.PreferenceFragmentCompat")
            val onCreateView = fragCls.getMethod(
                "onCreateView", android.view.LayoutInflater::class.java,
                android.view.ViewGroup::class.java, android.os.Bundle::class.java,
            )
            module.hook(onCreateView)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:audio-logo-view")
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? android.view.ViewGroup)?.let { root ->
                        runCatching { attachAudioBadge(chain.thisObject, root, prefs) }
                    }
                    result
                }
        }
    }

    /** Badge drawable for the selected mode, or null to leave the stock one alone. */
    private fun audioLogoDrawable(res: Resources, id: Int, prefs: SharedPreferences): Drawable? {
        val settings = readSoundEffectPreferences(prefs)
        if (!settings.supportsBose(isNativeBoseDevice(module.getRemotePreferences(NATIVE_BOSE_PREFERENCES)))) return null
        if (settings.logoMode == AUDIO_LOGO_NONE || settings.logoMode == AUDIO_LOGO_BOSE) return null
        var cached = boseLogoResId.get()
        if (cached <= 0) {
            cached = runCatching { res.getIdentifier("bose_logo", "drawable", "com.miui.misound") }.getOrDefault(0)
            if (cached > 0) boseLogoResId.set(cached)
        }
        if (cached <= 0 || id != cached) return null
        val appContext = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
        }.getOrNull() as? android.content.Context ?: return null
        val moduleContext = runCatching {
            appContext.createPackageContext(BuildConfig.APPLICATION_ID, android.content.Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        val drawable = audioLogoFor(settings.logoMode, appContext, moduleContext) ?: return null
        val night = res.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return drawable.mutate().apply { setTint(if (night) 0x33FFFFFF.toInt() else 0x33000000.toInt()) }
    }

    private fun audioLogoFor(mode: Int, appContext: android.content.Context, moduleContext: android.content.Context): Drawable? =
        when (mode) {
            AUDIO_LOGO_XIAOMI -> moduleContext.getDrawable(R.drawable.ic_audio_logo_xiaomi)
            AUDIO_LOGO_HARMAN -> moduleContext.getDrawable(R.drawable.ic_audio_logo_harman)
            AUDIO_LOGO_DOLBY -> moduleContext.getDrawable(R.drawable.ic_audio_logo_dolby)
            AUDIO_LOGO_CUSTOM -> {
                val source = SettingsAppearanceSources.query(appContext, APPEARANCE_SLOT_AUDIO_LOGO)
                if (source.exists) LogoDrawableLoader.load(appContext, source) else null
            }
            else -> null
        }

    /** Adds the badge as a bottom-aligned view on the regular sound page when BOSE is off. */
    private fun attachAudioBadge(fragment: Any?, root: android.view.ViewGroup, prefs: SharedPreferences) {
        val settings = readSoundEffectPreferences(prefs)
        if (settings.supportsBose(isNativeBoseDevice(module.getRemotePreferences(NATIVE_BOSE_PREFERENCES))) ||
            settings.logoMode == AUDIO_LOGO_NONE) return
        if (fragment == null) return
        val screen = runCatching {
            fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment)
        }.getOrNull() ?: return
        val findPreference = runCatching {
            screen.javaClass.getMethod("findPreference", CharSequence::class.java)
        }.getOrNull() ?: return
        if (runCatching { findPreference.invoke(screen, "effect_selection") }.getOrNull() == null) return
        val context = root.context
        val moduleContext = runCatching {
            context.createPackageContext(BuildConfig.APPLICATION_ID, android.content.Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return
        val drawable = audioLogoFor(settings.logoMode, context, moduleContext) ?: return
        val night = root.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        drawable.mutate().setTint(if (night) 0x33FFFFFF.toInt() else 0x33000000.toInt())
        val density = root.resources.displayMetrics.density
        val image = ImageView(context)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setImageDrawable(drawable)
        val params = android.widget.FrameLayout.LayoutParams((130 * density).toInt(), (24 * density).toInt())
        params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        params.bottomMargin = (36 * density).toInt()
        root.addView(image, params)
    }

    private val boseLogoResId = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Adds extra refresh rates to the Xiaomi Settings picker.
     *
     * `FeatureParser.getIntArray("fpsList")` is what the display settings page reads to build its
     * list, so replacing that array is enough to add choices. This does not change the panel itself:
     * anything above the hardware rating will be offered but will not really apply.
     */
    fun installMiSettingsHooks(loader: ClassLoader, prefs: SharedPreferences) {
        if (!readMiSettingsPreferences(prefs).showMoreFps) return
        runCatching {
            val cls = loader.loadClass("miui.util.FeatureParser")
            val target = cls.declaredMethods.firstOrNull {
                it.name == "getIntArray" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java
            } ?: return@runCatching
            hookDeoptimized(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:mi-settings-fps")
                .intercept { chain ->
                    if (chain.getArg(0) == "fpsList") {
                        intArrayOf(240, 185, 165, 144, 120, 90, 60, 30, 10)
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    /** Restore the full color-style page using the simplified-mode gate override. */
    fun installSettingsAdvancedColorStyleHook(loader: ClassLoader, prefs: SharedPreferences) {
        if (!readSettingsAdvancedColorStyleSettings(prefs).enabled) return
        val target = loader.loadClass("com.android.settings.display.ScreenEffectFragment")
            .getDeclaredMethod("isSupportSimplifiedColormode")
        check(java.lang.reflect.Modifier.isStatic(target.modifiers) && target.returnType == Boolean::class.javaPrimitiveType) {
            "Unexpected ScreenEffectFragment.isSupportSimplifiedColormode signature"
        }
        // Keep supportDisplayAdvanced's hardware and primary-user checks in the stock path.
        hookDeoptimized(target)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-advanced-color-style")
            .intercept { false }
        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed Settings advanced color style hook")
    }

    /** Best-effort tweaks that reshape the system Settings app itself. */
    fun installSettingsAppScopeHooks(loader: ClassLoader, prefs: SharedPreferences) {
        val settings = readSettingsAppScopePreferences(prefs)
        if (settings.oldControlCenter) {
            runCatching {
                val cls = loader.loadClass("com.android.settings.utils.StatusBarUtils")
                val target = cls.declaredMethods.firstOrNull {
                    it.name == "isForceUseControlPanel" && it.parameterCount == 1
                } ?: return@runCatching
                hookDeoptimized(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:settings-old-control-center")
                    .intercept { false }
            }
        }
        if (readFrameworkPreferences(prefs).disableDeviceNameCheck) {
            val internationalField = runCatching {
                loader.loadClass("miui.os.Build").getDeclaredField("IS_INTERNATIONAL_BUILD")
                    .apply { isAccessible = true }
            }.getOrNull()
            fun hookAsInternational(method: java.lang.reflect.Executable, id: String) {
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(id)
                    .intercept { chain ->
                        if (internationalField == null) return@intercept chain.proceed()
                        val original = runCatching { internationalField.getBoolean(null) }.getOrDefault(false)
                        runCatching { internationalField.setBoolean(null, true) }
                        try {
                            chain.proceed()
                        } finally {
                            runCatching { internationalField.setBoolean(null, original) }
                        }
                    }
            }
            // The edit pages branch before DeviceNameCheckManager is called. Temporarily making
            // the Settings process look international makes them take the direct save path.
            listOf(
                "com.android.settings.MiuiDeviceNameEditFragment",
                "com.android.settings.wifi.EditTetherFragment",
            ).forEach { className ->
                runCatching {
                    loader.loadClass(className).declaredMethods
                        .filter { it.name == "onSave" && it.parameterCount == 0 }
                        .forEach { method ->
                            hookAsInternational(
                                method,
                                "hyperchanger:settings-device-name-save-${className.substringAfterLast('.')}",
                            )
                        }
                }
            }
            runCatching {
                loader.loadClass("com.android.settings.DeviceNameCheckManager").declaredMethods
                    .filter { it.name == "getDeviceNameCheckResult" && it.parameterCount == 4 }
                    .forEach { method -> hookAsInternational(method, "hyperchanger:settings-device-name-check") }
            }
            runCatching {
                val cls = loader.loadClass("com.android.settings.bluetooth.MiuiBTUtils")
                cls.declaredMethods.filter { it.name == "isInternationalBuild" && it.parameterCount == 0 }
                    .forEach { method ->
                        hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("hyperchanger:fw-device-name-intl")
                            .intercept { true }
                    }
                cls.declaredMethods.filter {
                    it.name == "isSupportNameComplianceCheck" && it.parameterCount == 1
                }.forEach { method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:fw-device-name-compliance")
                        .intercept { false }
                }
            }
        }
    }

    companion object {
        private const val AIRDROP_AVAILABILITY_CLASS = "com.miui.mishare.connectivity.c"
        private const val SETTINGS_HEADER_ID = SETTINGS_MODULE_ENTRY_HEADER_ID
        private const val MIUI_SETTINGS_HEADER_ADAPTER = "com.android.settings.MiuiSettings\$HeaderAdapter"
        @Volatile private var settingsModuleResourcesLoader: ResourcesLoader? = null
    }
}
