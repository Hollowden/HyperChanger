// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阻止第三方应用在 Wi-Fi 已连接且可用时，通过 `requestNetwork(TRANSPORT_CELLULAR)`
 * 长期强占 5G/蜂窝数据通道。
 *
 * 命中条件时将 `REQUEST` 无缝降级为同能力的 `LISTEN`（调用 `listenForNetwork`）：
 * - 返回合法 `NetworkRequest`，调用方不崩溃、不触发 `onUnavailable` 重试死循环；
 * - 不会主动拉起 `MOBILE[NR]`，Wi-Fi 下蜂窝通道在 Linger 后正常休眠，消除后台 5G 偷电与反复弹跳 5GA；
 * - 放行所有系统/预装应用（含小米 WLAN 助理自动切流量）、特殊运营商通道（MMS/IMS/SUPL 等）以及带超时的短时请求（如本机号码一键登录）。
 */
internal object FrameworkCellularOnWifiGuard {

    private const val TAG = "HyperChanger-CellGuard"
    private const val CONNECTIVITY_SERVICE_APEX_CLASS =
        "android.net.connectivity.com.android.server.ConnectivityService"
    private const val CONNECTIVITY_SERVICE_LEGACY_CLASS =
        "com.android.server.ConnectivityService"

    private val hooked = AtomicBoolean(false)
    private val loaderWatchHooked = AtomicBoolean(false)
    private val thirdPartyCache = ConcurrentHashMap<String, Boolean>()
    private val loggedPackages = ConcurrentHashMap.newKeySet<String>()

    /** `NetworkRequest.Type.REQUEST` 对应的枚举 ordinal，动态解析避免跨版本写死。 */
    private val requestTypeOrdinal: Int by lazy {
        runCatching {
            val enumCls = Class.forName("android.net.NetworkRequest\$Type")
            val constants = enumCls.enumConstants ?: emptyArray()
            constants.indexOfFirst { (it as? Enum<*>)?.name == "REQUEST" }
        }.getOrDefault(-1)
    }

    /** 特殊运营商或高优先级能力，遇到这些能力时一律放行。 */
    private val exemptCapabilities: IntArray = intArrayOf(
        NetworkCapabilities.NET_CAPABILITY_MMS,
        NetworkCapabilities.NET_CAPABILITY_SUPL,
        NetworkCapabilities.NET_CAPABILITY_DUN,
        NetworkCapabilities.NET_CAPABILITY_FOTA,
        NetworkCapabilities.NET_CAPABILITY_IMS,
        NetworkCapabilities.NET_CAPABILITY_CBS,
        NetworkCapabilities.NET_CAPABILITY_EIMS,
        NetworkCapabilities.NET_CAPABILITY_XCAP,
        NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY,
        NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH,
    )

    fun install(
        module: HyperSystemUiModule,
        loader: ClassLoader,
        prefs: SharedPreferences,
    ) {
        if (hooked.get()) return

        // 通道 1：若 ConnectivityService 已加载，直接挂钩
        tryResolveAndHook(module, loader, prefs)
        tryHookFromServiceManager(module, loader, prefs)

        if (hooked.get()) return

        // 通道 2：在 system_server 启动期监听 APEX 服务类加载
        if (!loaderWatchHooked.compareAndSet(false, true)) return
        runCatching {
            val manager = loader.loadClass("com.android.server.SystemServiceManager")
            manager.declaredMethods
                .filter { it.name == "loadClassFromLoader" && it.parameterCount == 2 }
                .forEach { method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:fw-cell-guard-loader")
                        .intercept { chain ->
                            val name = chain.getArg(0) as? String
                            val serviceLoader = chain.getArg(1) as? ClassLoader
                            if (serviceLoader != null && name != null &&
                                name.contains("ConnectivityService", ignoreCase = true)
                            ) {
                                tryResolveAndHook(module, serviceLoader, prefs)
                            }
                            chain.proceed()
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, TAG, "安装 ConnectivityService 加载监听失败", err)
        }
    }

    private fun tryHookFromServiceManager(
        module: HyperSystemUiModule,
        loader: ClassLoader,
        prefs: SharedPreferences,
    ) {
        if (hooked.get()) return
        runCatching {
            val sm = loader.loadClass("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            val service = getService.invoke(null, "connectivity") ?: return@runCatching
            val cls = service.javaClass
            if (cls.name.endsWith(".ConnectivityService")) {
                hookConnectivityServiceClass(module, cls, prefs)
            } else {
                cls.classLoader?.let { tryResolveAndHook(module, it, prefs) }
            }
        }
    }

    private fun tryResolveAndHook(
        module: HyperSystemUiModule,
        classLoader: ClassLoader,
        prefs: SharedPreferences,
    ) {
        if (hooked.get()) return
        val cls = runCatching { classLoader.loadClass(CONNECTIVITY_SERVICE_APEX_CLASS) }.getOrNull()
            ?: runCatching { classLoader.loadClass(CONNECTIVITY_SERVICE_LEGACY_CLASS) }.getOrNull()
            ?: return
        hookConnectivityServiceClass(module, cls, prefs)
    }

    private fun hookConnectivityServiceClass(
        module: HyperSystemUiModule,
        csClass: Class<*>,
        prefs: SharedPreferences,
    ) {
        if (!hooked.compareAndSet(false, true)) return

        val requestMethod = csClass.declaredMethods.firstOrNull { m ->
            m.name == "requestNetwork" &&
                m.parameterCount == 11 &&
                m.parameterTypes[1] == NetworkCapabilities::class.java &&
                m.parameterTypes[3] == Messenger::class.java &&
                m.parameterTypes[5] == IBinder::class.java &&
                m.parameterTypes[8] == String::class.java
        }
        val listenMethod = csClass.declaredMethods.firstOrNull { m ->
            m.name == "listenForNetwork" &&
                m.parameterCount == 7 &&
                m.parameterTypes[0] == NetworkCapabilities::class.java &&
                m.parameterTypes[1] == Messenger::class.java &&
                m.parameterTypes[2] == IBinder::class.java &&
                m.parameterTypes[4] == String::class.java
        }
        if (requestMethod == null || listenMethod == null) {
            hooked.set(false)
            HookModuleLogger.log(module, Log.WARN, TAG, "未匹配到 ConnectivityService.requestNetwork / listenForNetwork 签名")
            return
        }

        val getDefaultNetworkMethod = findMethod(csClass, "getDefaultNetwork", 0)
        val contextField = findField(csClass, "mContext")
        listenMethod.isAccessible = true

        module.hookDeoptimized(requestMethod)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:fw-block-third-party-cellular-on-wifi")
            .intercept { chain ->
                val enabled = runCatching {
                    readFrameworkPreferences(prefs).blockThirdPartyCellularOnWifi
                }.getOrDefault(false)
                if (!enabled) return@intercept chain.proceed()

                val targetUid = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                val nc = chain.getArg(1) as? NetworkCapabilities ?: return@intercept chain.proceed()
                val reqType = chain.getArg(2) as? Int ?: return@intercept chain.proceed()
                val messenger = chain.getArg(3) as? Messenger ?: return@intercept chain.proceed()
                val timeoutMs = chain.getArg(4) as? Int ?: return@intercept chain.proceed()
                val binder = chain.getArg(5) as? IBinder ?: return@intercept chain.proceed()
                val legacyType = chain.getArg(6) as? Int ?: return@intercept chain.proceed()
                val callbackFlags = chain.getArg(7) as? Int ?: return@intercept chain.proceed()
                val callingPkg = chain.getArg(8) as? String ?: return@intercept chain.proceed()
                val attributionTag = chain.getArg(9) as? String
                val declaredMethodsFlag = chain.getArg(10) as? Int ?: return@intercept chain.proceed()

                // 仅处理无超时的主动 REQUEST（放行带超时的一键登录/号码认证，及非默认 uid/legacyType 请求）
                val expectedReqOrdinal = requestTypeOrdinal
                if (expectedReqOrdinal < 0 || reqType != expectedReqOrdinal) return@intercept chain.proceed()
                if (targetUid != -1 || legacyType != -1 || timeoutMs > 0) return@intercept chain.proceed()

                // 仅处理明确强占蜂窝且不包含 Wi-Fi 的纯互联网请求
                if (!isThirdPartyCellularInternetOnly(nc)) return@intercept chain.proceed()

                // 仅拦截三方用户应用，100% 放行系统/预装应用（含 WLAN 助理、GMS、运营商服务）
                val callingUid = Binder.getCallingUid()
                val appId = callingUid % 100000
                if (appId < Process.FIRST_APPLICATION_UID) return@intercept chain.proceed()

                val serviceInstance = chain.thisObject ?: return@intercept chain.proceed()
                val context = runCatching { contextField?.get(serviceInstance) as? Context }.getOrNull()
                    ?: return@intercept chain.proceed()

                if (!isThirdPartyApp(context, callingPkg)) return@intercept chain.proceed()

                // 若用户手动开启了双网加速/LinkTurbo，则自动让位
                if (isDualNetworkAccelerationEnabled(context)) return@intercept chain.proceed()

                // 仅在当前默认网络为「已验证可用 Wi-Fi」时降级；Wi-Fi 异常或无网时原样放行
                if (!isDefaultNetworkValidatedWifi(serviceInstance, getDefaultNetworkMethod)) {
                    return@intercept chain.proceed()
                }

                // 降级为同能力的被动 LISTEN：不拉起 5G 通道，切回 5G 时仍会自动回调 onAvailable
                val downgraded = runCatching {
                    listenMethod.invoke(
                        serviceInstance,
                        nc,
                        messenger,
                        binder,
                        callbackFlags,
                        callingPkg,
                        attributionTag,
                        declaredMethodsFlag,
                    )
                }.getOrNull()

                if (downgraded != null) {
                    if (loggedPackages.add(callingPkg)) {
                        HookModuleLogger.log(
                            module,
                            Log.INFO,
                            TAG,
                            "已将 Wi-Fi 下的三方蜂窝常驻请求降级为被动监听: pkg=$callingPkg uid=$callingUid",
                        )
                    }
                    downgraded
                } else {
                    chain.proceed()
                }
            }

        HookModuleLogger.log(module, Log.INFO, TAG, "已安装 Wi-Fi 下拦截三方抢占蜂窝 Hook (${csClass.name})")
    }

    private fun isThirdPartyCellularInternetOnly(nc: NetworkCapabilities): Boolean {
        if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        for (cap in exemptCapabilities) {
            if (nc.hasCapability(cap)) return false
        }
        return true
    }

    private fun isThirdPartyApp(context: Context, pkg: String): Boolean {
        if (pkg.isBlank() || pkg == "android" || pkg == "system") return false
        thirdPartyCache[pkg]?.let { return it }
        val result = runCatching {
            val token = Binder.clearCallingIdentity()
            try {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                val systemMask = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                (ai.flags and systemMask) == 0
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }.getOrDefault(false)
        thirdPartyCache[pkg] = result
        return result
    }

    private fun isDualNetworkAccelerationEnabled(context: Context): Boolean {
        return runCatching {
            val token = Binder.clearCallingIdentity()
            try {
                val cr = context.contentResolver
                val dualData = Settings.Global.getInt(cr, "dual_mobile_data", 0)
                val linkTurbo = Settings.System.getInt(cr, "link_turbo_mode", 0)
                dualData != 0 || linkTurbo != 0
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }.getOrDefault(false)
    }

    private fun isDefaultNetworkValidatedWifi(
        serviceInstance: Any,
        getDefaultNetworkMethod: Method?,
    ): Boolean {
        if (getDefaultNetworkMethod == null) return false
        return runCatching {
            val nai = getDefaultNetworkMethod.invoke(serviceInstance) ?: return@runCatching false
            val ncField = findField(nai.javaClass, "networkCapabilities") ?: return@runCatching false
            val defaultNc = ncField.get(nai) as? NetworkCapabilities ?: return@runCatching false
            defaultNc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                defaultNc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun findMethod(cls: Class<*>, name: String, paramCount: Int): Method? {
        var cur: Class<*>? = cls
        while (cur != null) {
            cur.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount }?.let {
                it.isAccessible = true
                return it
            }
            cur = cur.superclass
        }
        return null
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var cur: Class<*>? = cls
        while (cur != null) {
            runCatching { cur.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            cur = cur.superclass
        }
        return null
    }
}
